import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

async function readSchema(name) {
  const url = new URL(`../../shared/protocol/${name}`, import.meta.url);
  return JSON.parse(await readFile(url, "utf8"));
}

test("protocol schemas are versioned and make coordinate systems explicit", async () => {
  const requestSchema = await readSchema("route-request.v1.schema.json");
  const bundleSchema = await readSchema("route-bundle.v1.schema.json");
  const optionsSchema = await readSchema("route-options.v1.schema.json");

  assert.equal(requestSchema.properties.protocol_version.const, 1);
  assert.equal(requestSchema.$defs.wgs84Point.properties.coordinate_system.const, "WGS84");
  assert.equal(bundleSchema.properties.protocol_version.const, 1);
  assert.equal(
    bundleSchema.$defs.routeBundle.properties.coordinate_system.const,
    "GCJ-02",
  );
  assert.ok(bundleSchema.required.includes("request_id"));
  assert.equal(optionsSchema.properties.protocol_version.const, 1);
  assert.equal(optionsSchema.properties.routes.maxItems, 3);
});
