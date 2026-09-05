import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  AmapTransformError,
  mapAmapManeuver,
  mapAmapTrafficStatus,
  transformAmapRouteOptionsV2,
  transformAmapRouteV2,
} from "../src/amap-transformer.js";

async function fixture() {
  const fixtureUrl = new URL("../fixtures/amap-route-v2.json", import.meta.url);
  return JSON.parse(await readFile(fixtureUrl, "utf8"));
}

test("maps AMap polyline, navi steps, and TMCs into RouteBundle v1", async () => {
  const route = transformAmapRouteV2(await fixture(), { generatedAtMs: 1700000000123 });

  assert.equal(route.schema_version, 1);
  assert.equal(route.route_id, "amap-fixture-beijing-001");
  assert.equal(route.provider, "amap");
  assert.equal(route.coordinate_system, "GCJ-02");
  assert.equal(route.generated_at_ms, 1700000000123);
  assert.equal(route.total_distance_m, 2150);
  assert.equal(route.total_duration_s, 420);
  assert.equal(route.polyline.length, 9, "duplicate step-boundary points must be removed");

  assert.deepEqual(
    route.maneuvers.map(({ type, route_offset_m }) => ({ type, route_offset_m })),
    [
      { type: "continue", route_offset_m: 800 },
      { type: "right", route_offset_m: 1700 },
      { type: "arrive", route_offset_m: 2150 },
    ],
  );
  assert.deepEqual(
    route.traffic.map(({ start_offset_m, end_offset_m, level }) => ({
      start_offset_m,
      end_offset_m,
      level,
    })),
    [
      { start_offset_m: 0, end_offset_m: 400, level: "free_flow" },
      { start_offset_m: 400, end_offset_m: 800, level: "slow" },
      { start_offset_m: 800, end_offset_m: 1300, level: "congested" },
      { start_offset_m: 1300, end_offset_m: 1700, level: "severe" },
      { start_offset_m: 1700, end_offset_m: 2150, level: "unknown" },
    ],
  );
});

test("normalizes maneuver and traffic variants without leaking provider strings", () => {
  assert.equal(mapAmapManeuver("进入环岛", "从第3个出口驶出"), "roundabout");
  assert.equal(mapAmapManeuver("驶出环岛"), "exit");
  assert.equal(mapAmapManeuver("向左后方行驶"), "u_turn_left");
  assert.equal(mapAmapTrafficStatus("严重拥堵"), "severe");
  assert.equal(mapAmapTrafficStatus("没有定义的新状态"), "unknown");
});

test("returns up to three independent provider-neutral route options", async () => {
  const payload = await fixture();
  const second = structuredClone(payload.route.paths[0]);
  second.path_id = "fixture-beijing-002";
  second.distance = "2280";
  second.cost.duration = "390";
  payload.route.paths.push(second);

  const routes = transformAmapRouteOptionsV2(payload, { generatedAtMs: 1700000000123 });

  assert.equal(routes.length, 2);
  assert.deepEqual(routes.map((route) => route.route_id), [
    "amap-fixture-beijing-001",
    "amap-fixture-beijing-002",
  ]);
  assert.deepEqual(routes.map((route) => route.total_distance_m), [2150, 2280]);
});

test("rejects unsuccessful or geometry-free provider responses", () => {
  assert.throws(
    () => transformAmapRouteV2({ status: "0", infocode: "10003" }),
    (error) => error instanceof AmapTransformError && error.code === "AMAP_10003",
  );
  assert.throws(
    () =>
      transformAmapRouteV2({
        status: "1",
        route: { paths: [{ distance: "5", cost: { duration: "2" }, steps: [] }] },
      }),
    (error) => error instanceof AmapTransformError && error.code === "INVALID_POLYLINE",
  );
});
