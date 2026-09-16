import assert from "node:assert/strict";
import test from "node:test";
import { createGateway } from "../src/gateway.js";

async function start(t, options = {}) {
  const server = createGateway({ provider: { planRoute: async () => ({}) }, ...options });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  return `http://127.0.0.1:${server.address().port}`;
}

test("gateway validates map coordinates/query and exposes disabled capabilities accurately", async (t) => {
  const base = await start(t);
  for (const path of ["/v1/map/tiles/14/0/0", "/v1/map/tiles/15/-1/0", "/v1/map/tiles/15/32768/0",
    "/v1/map/tiles/15/0/0?url=https://attacker.test/", "/v1/map/cities?keywords=济南&key=secret"]) {
    assert.equal((await fetch(base + path)).status, 400);
  }
  assert.equal((await fetch(base + "/v1/map/tiles/15/0/0")).status, 503);
  assert.equal((await fetch(base + "/v1/map/cities?keywords=济南")).status, 503);
  const health = await (await fetch(base + "/healthz")).json();
  assert.equal(health.capabilities.surrounding_map, false);
  assert.equal(health.capabilities.map_city_search, false);
  assert.deepEqual(health.map_source, { enabled: false });
});

test("map, city and route rate budgets are independent and retries carry a delay", async (t) => {
  let tileCalls = 0;
  const base = await start(t, { providerMode: "amap", provider: {
    planRoute: async () => ({}), searchCities: async ({ keywords }) => ({ cities: [{ name: keywords }] }),
  }, mapProvider: { getTile: async (z, x, y) => { tileCalls++; return { tile: { z, x, y } }; },
    status: () => ({ enabled: true, source_revision: "20260915" }) } });
  const map = await (await fetch(base + "/v1/map/tiles/15/1/2")).json();
  assert.deepEqual(map.tile, { z: 15, x: 1, y: 2 });
  for (let i = 0; i < 30; i++) assert.equal((await fetch(base + "/v1/map/cities?keywords=济南")).status, 200);
  const limited = await fetch(base + "/v1/map/cities?keywords=济南");
  assert.equal(limited.status, 429);
  assert.equal(limited.headers.get("retry-after"), "60");
  assert.equal((await fetch(base + "/v1/map/tiles/15/1/2")).status, 200);
  assert.equal(tileCalls, 2);
  const health = await (await fetch(base + "/healthz")).json();
  assert.equal(health.capabilities.surrounding_map, true);
  assert.equal(health.capabilities.map_city_search, true);
  assert.equal(health.map_source.source_revision, "20260915");
});
