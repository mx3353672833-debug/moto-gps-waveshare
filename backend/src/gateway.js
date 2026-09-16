import { createServer } from "node:http";

import { validRequestId, validateRouteRequest } from "./validation.js";
import { validateTileCoordinates } from "./map-tiles.js";
import { validateCityKeywords } from "./amap-cities.js";

const DEFAULT_MAX_BODY_BYTES = 8192;
const RATE_WINDOW_MS = 60_000;

class BodyReadError extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
    this.retryable = false;
  }
}

async function readJsonBody(request, maximumBytes) {
  let receivedBytes = 0;
  const chunks = [];
  for await (const chunk of request) {
    receivedBytes += chunk.length;
    if (receivedBytes > maximumBytes) {
      throw new BodyReadError("REQUEST_TOO_LARGE", "request body exceeds the size limit");
    }
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new BodyReadError("INVALID_JSON", "request body is not valid JSON");
  }
}

function setCommonHeaders(response, allowedOrigin) {
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  if (allowedOrigin) {
    response.setHeader("Access-Control-Allow-Origin", allowedOrigin);
    response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    response.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
    response.setHeader("Vary", "Origin");
  }
}

function sendJson(response, statusCode, body, allowedOrigin) {
  setCommonHeaders(response, allowedOrigin);
  response.statusCode = statusCode;
  response.end(JSON.stringify(body));
}

function requestIdFrom(value) {
  return validRequestId(value?.request_id) ? value.request_id : null;
}

function safeError(error) {
  const knownCode = typeof error?.code === "string" && /^[A-Z0-9_]+$/.test(error.code);
  return {
    code: knownCode ? error.code : "INTERNAL_ERROR",
    message: knownCode ? String(error.message).slice(0, 160) : "route request failed",
    retryable: knownCode ? error.retryable !== false : true,
  };
}

function validatePlaceQuery(requestUrl) {
  const keywords = String(requestUrl.searchParams.get("keywords") ?? "").trim();
  const region = String(requestUrl.searchParams.get("region") ?? "").trim();
  const longitudeText = String(requestUrl.searchParams.get("longitude_deg") ?? "").trim();
  const latitudeText = String(requestUrl.searchParams.get("latitude_deg") ?? "").trim();
  if (keywords.length < 2 || keywords.length > 80) {
    throw new BodyReadError("INVALID_REQUEST", "keywords must contain 2 to 80 characters");
  }
  if (region.length > 32) {
    throw new BodyReadError("INVALID_REQUEST", "region must not exceed 32 characters");
  }
  if ((longitudeText === "") !== (latitudeText === "")) {
    throw new BodyReadError(
      "INVALID_REQUEST",
      "longitude_deg and latitude_deg must be provided together",
    );
  }

  let origin;
  if (longitudeText !== "") {
    const longitudeDeg = Number(longitudeText);
    const latitudeDeg = Number(latitudeText);
    if (!Number.isFinite(longitudeDeg) || longitudeDeg < -180 || longitudeDeg > 180) {
      throw new BodyReadError("INVALID_REQUEST", "longitude_deg is out of range");
    }
    if (!Number.isFinite(latitudeDeg) || latitudeDeg < -90 || latitudeDeg > 90) {
      throw new BodyReadError("INVALID_REQUEST", "latitude_deg is out of range");
    }
    origin = {
      coordinate_system: "WGS84",
      longitude_deg: longitudeDeg,
      latitude_deg: latitudeDeg,
    };
  }
  return { keywords, region, origin };
}

const MAXIMUM_RATE_BUCKETS = 4_096;

function isLoopbackAddress(address) {
  if (typeof address !== "string") return false;
  return address === "::1" || address.startsWith("127.") ||
    address.startsWith("::ffff:127.");
}

export function clientAddress(request) {
  const remote = request.socket?.remoteAddress;
  if (isLoopbackAddress(remote)) {
    const realIp = String(request.headers["x-real-ip"] ?? "").trim();
    if (realIp !== "") return realIp;
  }
  return remote ?? "unknown";
}

export function createRateLimiter() {
  const buckets = new Map();
  function rateLimit(request, category, maximumRequests, now = Date.now()) {
    const key = `${category}:${clientAddress(request)}`;
    let bucket = buckets.get(key);
    if (!bucket || now - bucket.startedAt >= RATE_WINDOW_MS) {
      bucket = { startedAt: now, count: 0 };
      buckets.set(key, bucket);
    }
    bucket.count += 1;

    if (buckets.size > MAXIMUM_RATE_BUCKETS) {
      for (const [bucketKey, value] of buckets) {
        if (now - value.startedAt >= RATE_WINDOW_MS) buckets.delete(bucketKey);
        if (buckets.size <= MAXIMUM_RATE_BUCKETS) break;
      }
      while (buckets.size > MAXIMUM_RATE_BUCKETS) {
        buckets.delete(buckets.keys().next().value);
      }
    }
    return bucket.count <= maximumRequests;
  }
  Object.defineProperty(rateLimit, "size", { get: () => buckets.size });
  return rateLimit;
}

export function createGateway({
  provider,
  mapProvider = null,
  allowedOrigin = "http://localhost:5173",
  maximumBodyBytes = DEFAULT_MAX_BODY_BYTES,
  providerMode = "fixture",
} = {}) {
  if (!provider || typeof provider.planRoute !== "function") {
    throw new TypeError("provider.planRoute is required");
  }
  const rateLimit = createRateLimiter();

  return createServer(async (request, response) => {
    const requestUrl = new URL(request.url ?? "/", "http://gateway.local");

    if (request.method === "OPTIONS") {
      setCommonHeaders(response, allowedOrigin);
      response.statusCode = 204;
      response.end();
      return;
    }

    if (request.method === "GET" && requestUrl.pathname === "/healthz") {
      sendJson(
        response,
        200,
        {
          status: "ok",
          ready_for_live_navigation: providerMode === "amap",
          provider: providerMode,
          capabilities: {
            route_planning: providerMode === "amap",
            route_traffic: providerMode === "amap",
            // Route Planning v2 exposes neither legal road speed limits nor
            // live signal phases. API readiness must not imply those feeds.
            road_speed_limits: false,
            traffic_light_countdown: false,
            surrounding_map: typeof mapProvider?.getTile === "function",
            map_city_search: typeof provider.searchCities === "function",
          },
          map_source: mapProvider?.status?.() ?? { enabled: false },
        },
        allowedOrigin,
      );
      return;
    }

    const isMapTile = request.method === "GET" && requestUrl.pathname.startsWith("/v1/map/tiles/");
    const isCitySearch = request.method === "GET" && requestUrl.pathname === "/v1/map/cities";
    if (isMapTile || isCitySearch) {
      if (!rateLimit(request, isMapTile ? "map-tiles" : "map-cities", isMapTile ? 600 : 30)) {
        response.setHeader("Retry-After", "60");
        sendJson(response, 429, { protocol_version: 1, error: {
          code: "RATE_LIMITED", message: "too many map requests", retryable: true,
        } }, allowedOrigin);
        return;
      }
      try {
        let result;
        if (isMapTile) {
          const match = /^\/v1\/map\/tiles\/(\d+)\/(\d+)\/(\d+)$/.exec(requestUrl.pathname);
          if (!match || requestUrl.search) throw new BodyReadError("INVALID_REQUEST", "invalid map tile path or query");
          const [z, x, y] = match.slice(1).map(Number);
          validateTileCoordinates(z, x, y);
          if (!mapProvider) throw new BodyReadError("MAP_DISABLED", "surrounding map source is disabled");
          result = await mapProvider.getTile(z, x, y);
        } else {
          if ([...requestUrl.searchParams.keys()].some((key) => key !== "keywords") ||
              requestUrl.searchParams.getAll("keywords").length !== 1) {
            throw new BodyReadError("INVALID_REQUEST", "city search accepts only keywords");
          }
          const keywords = validateCityKeywords(requestUrl.searchParams.get("keywords"));
          if (typeof provider.searchCities !== "function") throw new BodyReadError("SERVER_MISCONFIGURED", "city search is unavailable");
          result = await provider.searchCities({ keywords });
        }
        sendJson(response, 200, result, allowedOrigin);
      } catch (error) {
        const publicError = safeError(error);
        if (publicError.code === "MAP_BUSY") response.setHeader("Retry-After", "5");
        sendJson(response, publicError.code === "INVALID_REQUEST" ? 400 : 503,
          { protocol_version: 1, error: publicError }, allowedOrigin);
      }
      return;
    }

    if (request.method === "GET" && requestUrl.pathname === "/v1/places") {
      if (!rateLimit(request, "places", 60)) {
        response.setHeader("Retry-After", "60");
        sendJson(
          response,
          429,
          { protocol_version: 1, error: { code: "RATE_LIMITED", message: "too many place searches", retryable: true } },
          allowedOrigin,
        );
        return;
      }
      if (typeof provider.searchPlaces !== "function") {
        sendJson(
          response,
          503,
          {
            protocol_version: 1,
            error: { code: "SERVER_MISCONFIGURED", message: "place search is unavailable", retryable: false },
          },
          allowedOrigin,
        );
        return;
      }
      try {
        const query = validatePlaceQuery(requestUrl);
        const result = await provider.searchPlaces(query);
        sendJson(response, 200, result, allowedOrigin);
      } catch (error) {
        const publicError = safeError(error);
        sendJson(
          response,
          publicError.code === "INVALID_REQUEST" ? 400 : 503,
          { protocol_version: 1, error: publicError },
          allowedOrigin,
        );
      }
      return;
    }

    const isRouteRequest =
      request.method === "POST" &&
      (requestUrl.pathname === "/v1/routes" || requestUrl.pathname === "/v1/route-options");
    if (!isRouteRequest) {
      sendJson(
        response,
        404,
        {
          protocol_version: 1,
          request_id: null,
          error: { code: "NOT_FOUND", message: "endpoint not found", retryable: false },
        },
        allowedOrigin,
      );
      return;
    }

    if (!rateLimit(request, "routes", 30)) {
      response.setHeader("Retry-After", "60");
      sendJson(
        response,
        429,
        {
          protocol_version: 1,
          request_id: null,
          error: { code: "RATE_LIMITED", message: "too many route requests", retryable: true },
        },
        allowedOrigin,
      );
      return;
    }

    let body;
    try {
      body = await readJsonBody(request, maximumBodyBytes);
      validateRouteRequest(body);
      if (requestUrl.pathname === "/v1/route-options") {
        const routes = typeof provider.planRouteOptions === "function"
          ? await provider.planRouteOptions(body)
          : [await provider.planRoute(body)];
        sendJson(
          response,
          200,
          { protocol_version: 1, request_id: body.request_id, routes: routes.slice(0, 3) },
          allowedOrigin,
        );
      } else {
        const route = await provider.planRoute(body);
        sendJson(
          response,
          200,
          { protocol_version: 1, request_id: body.request_id, route },
          allowedOrigin,
        );
      }
    } catch (error) {
      const publicError = safeError(error);
      const clientError = ["INVALID_REQUEST", "INVALID_JSON", "REQUEST_TOO_LARGE"].includes(
        publicError.code,
      );
      sendJson(
        response,
        clientError ? 400 : 503,
        { protocol_version: 1, request_id: requestIdFrom(body), error: publicError },
        allowedOrigin,
      );
    }
  });
}
