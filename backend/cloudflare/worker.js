import { AsyncLocalStorage } from "node:async_hooks";
import { handleAsNodeRequest } from "cloudflare:node";
import { createGateway } from "../src/gateway.js";
import { createAmapProvider } from "../src/amap-provider.js";
import { createAmapPlacesProvider } from "../src/amap-places.js";
import { createAmapCitiesProvider } from "../src/amap-cities.js";
import { transformAmapRouteV2, transformAmapRouteOptionsV2 } from "../src/amap-transformer.js";
import { createMapTileProvider } from "../src/map-tiles.js";
import fixture from "../fixtures/amap-route-v2.json";
import { createR2Storage } from "./r2-storage.js";

// Workers only supports follow/manual. Preserve the Node provider's refusal to
// follow redirects by inspecting the response instead of using redirect:"error".
async function mapFetch(input, options) {
  const response = await fetch(input, { ...options, redirect: "manual" });
  if (response.status >= 300 && response.status < 400) {
    await response.body?.cancel();
    throw new Error("map source redirects are not allowed");
  }
  return response;
}

const requests = new AsyncLocalStorage();
let app;

function jsonError(code, message, status, origin, extraHeaders = {}) {
  return Response.json({ protocol_version: 1, request_id: null,
    error: { code, message, retryable: status !== 404 } }, { status, headers: {
    "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    ...(origin ? { "Access-Control-Allow-Origin": origin, "Vary": "Origin" } : {}),
    ...extraHeaders,
  } });
}

function createApp(env) {
  const mode = env.MOTO_PROVIDER ?? "disabled";
  const origin = env.WEB_ORIGIN ?? "";
  const basePath = env.MOTO_BASE_PATH ?? "";
  if (basePath && !/^\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+$/.test(basePath)) {
    throw new Error("MOTO_BASE_PATH must be empty or a path without a trailing slash");
  }
  let provider;
  if (mode === "amap") {
    const key = env.AMAP_WEB_SERVICE_KEY;
    if (typeof key !== "string" || !key.trim()) throw new Error("AMAP_WEB_SERVICE_KEY is required");
    provider = { ...createAmapProvider({ key }), ...createAmapPlacesProvider({ key }),
      ...createAmapCitiesProvider({ key }) };
  } else if (mode === "fixture") {
    provider = {
      async planRoute() { return transformAmapRouteV2(fixture, { generatedAtMs: Date.now() }); },
      async planRouteOptions() { return transformAmapRouteOptionsV2(fixture, { generatedAtMs: Date.now() }); },
      async searchPlaces({ keywords }) { return { protocol_version: 1, query: keywords, places: [] }; },
    };
  } else if (mode === "disabled") {
    const unavailable = async () => { throw Object.assign(new Error("AMap live navigation is not configured"), {
      code: "SERVER_MISCONFIGURED", retryable: false,
    }); };
    provider = { planRoute: unavailable, searchPlaces: unavailable };
  } else {
    throw new Error("unsupported MOTO_PROVIDER");
  }
  // Fixture and disabled modes must stay offline even with the production 'auto' variable.
  const mapUrl = mode === "amap" ? (env.MOTO_MAP_PMTILES_URL ?? "auto") : "disabled";
  if (mapUrl !== "disabled") createR2Storage(env.MAP_CACHE); // Validate the binding without I/O.
  function maps(ctx) {
    return createMapTileProvider({ url: mapUrl, scheduleRefresh: false, fetchImpl: mapFetch,
      storage: mapUrl === "disabled" ? undefined : createR2Storage(env.MAP_CACHE),
      backgroundTask: (task) => ctx.waitUntil(task),
    });
  }
  const mapProvider = mapUrl === "disabled" ? null : {
    async getTile(z, x, y) {
      // Never share in-flight R2/PMTiles I/O between Worker requests.
      const current = maps(requests.getStore());
      return current.getTile(z, x, y);
    },
    status: () => ({ enabled: true, mode: mapUrl === "auto" ? "auto" : "fixed", storage: "r2" }),
  };
  const server = createGateway({ provider, mapProvider, allowedOrigin: origin, providerMode: mode, trustProxy: true });
  server.listen(8787); // Workers uses this as an internal routing key, not a TCP listener.
  return { basePath, origin, maps };
}

function limiterFor(path, method, env) {
  if (method === "POST" && ["/v1/routes", "/v1/route-options"].includes(path)) return env.ROUTE_LIMITER;
  if (method === "GET" && path === "/v1/places") return env.PLACE_LIMITER;
  if (method === "GET" && path === "/v1/map/cities") return env.CITY_LIMITER;
  if (method === "GET" && path.startsWith("/v1/map/tiles/")) return env.TILE_LIMITER;
  return null;
}

export default {
  async fetch(request, env, ctx) {
    try {
      app ??= createApp(env);
      const url = new URL(request.url);
      if (app.basePath) {
        if (!url.pathname.startsWith(`${app.basePath}/`)) {
          return jsonError("NOT_FOUND", "endpoint not found", 404, app.origin);
        }
        url.pathname = url.pathname.slice(app.basePath.length);
      }
      const address = request.headers.get("CF-Connecting-IP") ?? "unknown";
      const limiter = limiterFor(url.pathname, request.method, env);
      if (limiter && !(await limiter.limit({ key: address })).success) {
        return jsonError("RATE_LIMITED", "too many requests", 429, app.origin, { "Retry-After": "60" });
      }
      const headers = new Headers(request.headers);
      // Never allow caller-supplied proxy headers to bypass the shared gateway's limiter.
      headers.set("X-Real-IP", address);
      headers.delete("X-Forwarded-For");
      const forwarded = new Request(url, { method: request.method, headers, body: request.body,
        redirect: "manual", signal: request.signal });
      return await requests.run(ctx, () => handleAsNodeRequest(8787, forwarded));
    } catch {
      // No raw errors: an upstream URL or binding error could contain deployment secrets.
      return jsonError("SERVER_MISCONFIGURED", "Worker gateway is unavailable; check configuration", 503, env.WEB_ORIGIN);
    }
  },
  async scheduled(_event, env, ctx) {
    app ??= createApp(env);
    const provider = app.maps(ctx);
    if (provider) await provider.initialize();
  },
};
