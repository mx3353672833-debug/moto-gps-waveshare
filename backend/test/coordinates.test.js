import assert from "node:assert/strict";
import test from "node:test";

import {
  gcj02ToWgs84,
  isOutsideGcj02Coverage,
  wgs84ToGcj02,
} from "../src/coordinates.js";

test("converts a mainland WGS84 point at the explicit provider boundary", () => {
  const converted = wgs84ToGcj02({
    coordinate_system: "WGS84",
    longitude_deg: 116.397389,
    latitude_deg: 39.908722,
  });

  assert.equal(converted.coordinate_system, "GCJ-02");
  assert.ok(Math.abs(converted.longitude_deg - 116.397389) > 0.001);
  assert.ok(Math.abs(converted.latitude_deg - 39.908722) > 0.0001);
  assert.ok(Math.abs(converted.longitude_deg - 116.403633) < 0.0001);
  assert.ok(Math.abs(converted.latitude_deg - 39.910125) < 0.0001);
});

test("leaves coordinates outside GCJ-02 coverage numerically unchanged", () => {
  assert.equal(isOutsideGcj02Coverage(2.3522, 48.8566), true);
  assert.deepEqual(
    wgs84ToGcj02({ longitude_deg: 2.3522, latitude_deg: 48.8566 }),
    {
      coordinate_system: "GCJ-02",
      longitude_deg: 2.3522,
      latitude_deg: 48.8566,
    },
  );
});

test("round-trips an AMap destination without applying the offset twice", () => {
  const source = { longitude_deg: 116.397389, latitude_deg: 39.908722 };
  const gcj02 = wgs84ToGcj02(source);
  const restored = gcj02ToWgs84(gcj02);

  assert.equal(restored.coordinate_system, "WGS84");
  assert.ok(Math.abs(restored.longitude_deg - source.longitude_deg) < 0.000002);
  assert.ok(Math.abs(restored.latitude_deg - source.latitude_deg) < 0.000002);
});
