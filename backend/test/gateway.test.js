import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { transformAmapRouteV2 } from "../src/amap-transformer.js";
import { createGateway } from "../src/gateway.js";

async function loadJson(relativeUrl) {
  return JSON.parse(await readFile(new URL(relativeUrl, import.meta.url), "utf8"));
}

async function startGateway(t, provider) {
  const server = createGateway({ provider, allowedOrigin: "http://localhost:5173" });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const address = server.address();
  return `http://127.0.0.1:${address.port}`;
}

test("exposes live readiness and provider-neutral place search", async (t) => {
  let receivedPlaceQuery;
  const provider = {
    async planRoute() {
      throw new Error("not used");
    },
    async searchPlaces(query) {
      receivedPlaceQuery = query;
      return { protocol_version: 1, query: query.keywords, places: [] };
    },
  };
  const server = createGateway({ provider, allowedOrigin: "http://localhost:5173", providerMode: "amap" });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(() => new Promise((resolve) => server.close(resolve)));
  const baseUrl = `http://127.0.0.1:${server.address().port}`;

  const health = await (await fetch(`${baseUrl}/healthz`)).json();
  const places = await (await fetch(
    `${baseUrl}/v1/places?keywords=%E5%8C%97%E4%BA%AC%E7%AB%99&longitude_deg=117.12&latitude_deg=36.67`,
  )).json();
  assert.equal(health.ready_for_live_navigation, true);
  assert.equal(health.provider, "amap");
  assert.equal(places.query, "北京站");
  assert.deepEqual(receivedPlaceQuery.origin, {
    coordinate_system: "WGS84",
    longitude_deg: 117.12,
    latitude_deg: 36.67,
  });
});

test("keeps old place queries compatible and rejects incomplete phone coordinates", async (t) => {
  let calls = 0;
  const provider = {
    async planRoute() {
      throw new Error("not used");
    },
    async searchPlaces(query) {
      calls += 1;
      assert.equal(query.origin, undefined);
      return { protocol_version: 1, query: query.keywords, places: [] };
    },
  };
  const baseUrl = await startGateway(t, provider);

  const legacyResponse = await fetch(`${baseUrl}/v1/places?keywords=%E5%8C%97%E4%BA%AC%E7%AB%99`);
  assert.equal(legacyResponse.status, 200);
  assert.equal(calls, 1);

  const invalidResponse = await fetch(
    `${baseUrl}/v1/places?keywords=%E5%8C%97%E4%BA%AC%E7%AB%99&longitude_deg=117.12`,
  );
  const invalidBody = await invalidResponse.json();
  assert.equal(invalidResponse.status, 400);
  assert.equal(invalidBody.error.code, "INVALID_REQUEST");
  assert.equal(calls, 1, "provider must not run for incomplete coordinates");
});

function postRoute(baseUrl, request) {
  return fetch(`${baseUrl}/v1/routes`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
}

function postRouteOptions(baseUrl, request) {
  return fetch(`${baseUrl}/v1/route-options`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
}

async function waitForPending(pending, ...ids) {
  for (let turn = 0; turn < 100; turn += 1) {
    if (ids.every((id) => pending.has(id))) return;
    await new Promise((resolve) => setImmediate(resolve));
  }
  throw new Error("gateway did not dispatch requests in time");
}

test("echoes request_id and wraps only the provider-neutral RouteBundle", async (t) => {
  const providerPayload = await loadJson("../fixtures/amap-route-v2.json");
  const routeRequest = await loadJson("../fixtures/route-request-v1.json");
  const provider = {
    async planRoute() {
      return transformAmapRouteV2(providerPayload, { generatedAtMs: 1700000000123 });
    },
  };
  const baseUrl = await startGateway(t, provider);
  const response = await postRoute(baseUrl, routeRequest);
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.equal(body.protocol_version, 1);
  assert.equal(body.request_id, 41);
  assert.equal(body.route.provider, "amap");
  assert.equal(body.route.coordinate_system, "GCJ-02");
  assert.equal(body.route.paths, undefined, "raw AMap paths must not cross the gateway");
});

test("returns at most three selectable route options without changing /v1/routes", async (t) => {
  const providerPayload = await loadJson("../fixtures/amap-route-v2.json");
  const routeRequest = await loadJson("../fixtures/route-request-v1.json");
  const route = transformAmapRouteV2(providerPayload, { generatedAtMs: 1700000000123 });
  const provider = {
    async planRoute() { return route; },
    async planRouteOptions() {
      return [route, { ...route, route_id: "amap-option-2" }, { ...route, route_id: "amap-option-3" }, { ...route, route_id: "amap-option-4" }];
    },
  };
  const baseUrl = await startGateway(t, provider);

  const response = await postRouteOptions(baseUrl, routeRequest);
  const body = await response.json();

  assert.equal(response.status, 200);
  assert.equal(body.request_id, 41);
  assert.equal(body.routes.length, 3);
  assert.deepEqual(body.routes.map((candidate) => candidate.route_id), [
    route.route_id,
    "amap-option-2",
    "amap-option-3",
  ]);
});

test("keeps IDs attached when a stale request completes after its replacement", async (t) => {
  const providerPayload = await loadJson("../fixtures/amap-route-v2.json");
  const route = transformAmapRouteV2(providerPayload, { generatedAtMs: 1700000000123 });
  const routeRequest = await loadJson("../fixtures/route-request-v1.json");
  const pending = new Map();
  const provider = {
    planRoute(request) {
      return new Promise((resolve) => pending.set(request.request_id, resolve));
    },
  };
  const baseUrl = await startGateway(t, provider);

  const oldResponsePromise = postRoute(baseUrl, { ...routeRequest, request_id: 100 });
  const currentResponsePromise = postRoute(baseUrl, {
    ...routeRequest,
    request_id: 101,
    is_reroute: true,
    previous_route_id: route.route_id,
  });
  await waitForPending(pending, 100, 101);

  pending.get(101)(route);
  const currentBody = await (await currentResponsePromise).json();
  pending.get(100)(route);
  const oldBody = await (await oldResponsePromise).json();

  assert.equal(currentBody.request_id, 101);
  assert.equal(oldBody.request_id, 100);
  assert.notEqual(oldBody.request_id, currentBody.request_id);
});

test("rejects client-supplied provider credentials and preserves a valid request ID", async (t) => {
  const routeRequest = await loadJson("../fixtures/route-request-v1.json");
  const provider = {
    async planRoute() {
      assert.fail("provider must not run for an invalid request");
    },
  };
  const baseUrl = await startGateway(t, provider);
  const response = await postRoute(baseUrl, {
    ...routeRequest,
    request_id: 72,
    key: "must-never-be-accepted-from-client",
  });
  const body = await response.json();

  assert.equal(response.status, 400);
  assert.equal(body.request_id, 72);
  assert.equal(body.error.code, "INVALID_REQUEST");
  assert.equal(body.error.retryable, false);
  assert.doesNotMatch(JSON.stringify(body), /must-never-be-accepted/);
});
