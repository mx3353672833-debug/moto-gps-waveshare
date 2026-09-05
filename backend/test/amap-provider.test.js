import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import { buildAmapDrivingUrl, createAmapProvider } from "../src/amap-provider.js";

const request = {
  origin: {
    coordinate_system: "WGS84",
    longitude_deg: 116.397389,
    latitude_deg: 39.908722,
  },
  destination: {
    coordinate_system: "WGS84",
    longitude_deg: 116.410886,
    latitude_deg: 39.92015,
  },
  destination_poi_id: "B000A83V3S",
};

test("builds an HTTPS provider request with server-only key and GCJ-02 points", () => {
  const url = buildAmapDrivingUrl(request, { key: "test-only-placeholder" });
  const origin = url.searchParams.get("origin");

  assert.equal(url.protocol, "https:");
  assert.equal(url.searchParams.get("key"), "test-only-placeholder");
  assert.notEqual(origin, "116.397389,39.908722");
  assert.equal(url.searchParams.get("show_fields"), "cost,polyline,navi,tmcs");
  assert.equal(url.searchParams.get("strategy"), "32");
  assert.equal(url.searchParams.get("destination_id"), "B000A83V3S");
  assert.match(origin, /^\d+\.\d{6},\d+\.\d{6}$/);
});

test("refuses to build a provider request without a server key", () => {
  assert.throws(() => buildAmapDrivingUrl(request, {}), /server key is not configured/);
});

async function routeFixture() {
  const url = new URL("../fixtures/amap-route-v2.json", import.meta.url);
  return JSON.parse(await readFile(url, "utf8"));
}

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

test("retries bounded route transport failures before succeeding", async () => {
  const payload = await routeFixture();
  let calls = 0;
  const provider = createAmapProvider({
    key: "test-only-placeholder",
    retryDelayMs: 0,
    fetchImpl: async () => {
      calls += 1;
      if (calls < 3) throw new TypeError("temporary network failure");
      return jsonResponse(payload);
    },
  });

  const route = await provider.planRoute(request);

  assert.equal(calls, 3);
  assert.equal(route.route_id, "amap-fixture-beijing-001");
});

test("exposes all AMap candidates for route preview", async () => {
  const payload = await routeFixture();
  const second = structuredClone(payload.route.paths[0]);
  second.path_id = "fixture-beijing-preview-2";
  payload.route.paths.push(second);
  const provider = createAmapProvider({
    key: "test-only-placeholder",
    fetchImpl: async () => jsonResponse(payload),
    clock: () => 1700000000123,
  });

  const routes = await provider.planRouteOptions(request);

  assert.equal(routes.length, 2);
  assert.equal(routes[1].route_id, "amap-fixture-beijing-preview-2");
});

test("stops retrying a persistently unavailable route service after three GETs", async () => {
  let calls = 0;
  const provider = createAmapProvider({
    key: "test-only-placeholder",
    retryDelayMs: 0,
    fetchImpl: async () => {
      calls += 1;
      throw new TypeError("network remains unavailable");
    },
  });

  await assert.rejects(
    provider.planRoute(request),
    (error) => error.code === "PROVIDER_UNAVAILABLE",
  );
  assert.equal(calls, 3);
});

test("retries transient route HTTP failures but not non-transient HTTP failures", async (t) => {
  const payload = await routeFixture();

  await t.test("HTTP 503 is retried", async () => {
    let calls = 0;
    const provider = createAmapProvider({
      key: "test-only-placeholder",
      retryDelayMs: 0,
      fetchImpl: async () => {
        calls += 1;
        return calls === 1 ? jsonResponse({}, 503) : jsonResponse(payload);
      },
    });

    await provider.planRoute(request);
    assert.equal(calls, 2);
  });

  await t.test("HTTP 400 is returned immediately", async () => {
    let calls = 0;
    const provider = createAmapProvider({
      key: "test-only-placeholder",
      retryDelayMs: 0,
      fetchImpl: async () => {
        calls += 1;
        return jsonResponse({}, 400);
      },
    });

    await assert.rejects(
      provider.planRoute(request),
      (error) => error.code === "PROVIDER_HTTP_ERROR" && error.retryable === false,
    );
    assert.equal(calls, 1);
  });

  await t.test("HTTP 429 is surfaced without an immediate retry", async () => {
    let calls = 0;
    const provider = createAmapProvider({
      key: "test-only-placeholder",
      retryDelayMs: 0,
      fetchImpl: async () => {
        calls += 1;
        return jsonResponse({}, 429);
      },
    });

    await assert.rejects(
      provider.planRoute(request),
      (error) => error.code === "PROVIDER_HTTP_ERROR" && error.retryable === true,
    );
    assert.equal(calls, 1);
  });
});

test("does not retry an AMap route business error returned over HTTP 200", async () => {
  let calls = 0;
  const provider = createAmapProvider({
    key: "test-only-placeholder",
    retryDelayMs: 0,
    fetchImpl: async () => {
      calls += 1;
      return jsonResponse({ status: "0", infocode: "10001", info: "INVALID_USER_KEY" });
    },
  });

  await assert.rejects(provider.planRoute(request), (error) => error.code === "AMAP_10001");
  assert.equal(calls, 1);
});
