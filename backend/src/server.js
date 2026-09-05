import { readFile } from "node:fs/promises";

import { createAmapProvider } from "./amap-provider.js";
import { createAmapPlacesProvider } from "./amap-places.js";
import { transformAmapRouteOptionsV2, transformAmapRouteV2 } from "./amap-transformer.js";
import { createGateway } from "./gateway.js";

async function createFixtureProvider() {
  const fixtureUrl = new URL("../fixtures/amap-route-v2.json", import.meta.url);
  const fixture = JSON.parse(await readFile(fixtureUrl, "utf8"));
  return {
    async planRoute() {
      return transformAmapRouteV2(fixture, { generatedAtMs: Date.now() });
    },
    async planRouteOptions() {
      return transformAmapRouteOptionsV2(fixture, { generatedAtMs: Date.now() });
    },
    async searchPlaces(query) {
      return {
        protocol_version: 1,
        query: query.keywords,
        places: [],
      };
    },
  };
}

function createDisabledProvider() {
  const unavailable = () => {
    const error = new Error("AMap live navigation is not configured");
    error.code = "SERVER_MISCONFIGURED";
    error.retryable = false;
    throw error;
  };
  return {
    async planRoute() {
      unavailable();
    },
    async planRouteOptions() {
      unavailable();
    },
    async searchPlaces() {
      unavailable();
    },
  };
}

async function main() {
  const providerMode = process.env.MOTO_PROVIDER ?? "fixture";
  let provider;
  if (providerMode === "amap") {
    const key = process.env.AMAP_WEB_SERVICE_KEY;
    if (typeof key !== "string" || key.trim() === "") {
      throw new Error("AMAP_WEB_SERVICE_KEY is required when MOTO_PROVIDER=amap");
    }
    provider = {
      ...createAmapProvider({ key }),
      ...createAmapPlacesProvider({ key }),
    };
  } else if (providerMode === "fixture") {
    provider = await createFixtureProvider();
  } else if (providerMode === "disabled") {
    provider = createDisabledProvider();
  } else {
    throw new Error(`unsupported MOTO_PROVIDER: ${providerMode}`);
  }
  const port = Number(process.env.PORT ?? 8787);
  const allowedOrigin = process.env.WEB_ORIGIN ?? "http://localhost:5173";
  const server = createGateway({ provider, allowedOrigin, providerMode });

  server.listen(port, "127.0.0.1", () => {
    console.log(`MOTO GPS gateway listening on http://127.0.0.1:${port} (${providerMode})`);
  });

  const close = () => server.close();
  process.once("SIGINT", close);
  process.once("SIGTERM", close);
}

main().catch((error) => {
  console.error(error.message);
  process.exitCode = 1;
});
