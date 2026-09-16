import assert from "node:assert/strict";
import test from "node:test";
import { createAmapCitiesProvider, districtBoundsWgs84, transformAmapCities } from "../src/amap-cities.js";

const district = { adcode: "370102", level: "district", name: "历下区",
  polyline: "117.1,36.6;117.2,36.6;117.2,36.7;117.1,36.7;117.1,36.6" };

test("AMap city search uses district boundaries, server credentials and fixed official endpoint", async () => {
  const provider = createAmapCitiesProvider({ key: "test-server-key", fetchImpl: async (url) => {
    assert.equal(url.origin + url.pathname, "https://restapi.amap.com/v3/config/district");
    assert.equal(url.searchParams.get("keywords"), "历下区");
    assert.equal(url.searchParams.get("subdistrict"), "0");
    assert.equal(url.searchParams.get("extensions"), "all");
    assert.equal(url.searchParams.get("key"), "test-server-key");
    return Response.json({ status: "1", districts: [district] });
  } });
  const result = await provider.searchCities({ keywords: " 历下区 " });
  assert.equal(result.cities[0].id, "370102");
  assert.equal(result.cities[0].name, "历下区");
  assert.doesNotMatch(JSON.stringify(result), /test-server-key/);
  const [west, south, east, north] = result.cities[0].bounds_wgs84;
  assert.ok(west > 117.09 && west < 117.098, "GCJ longitude must be inverse-converted, not copied");
  assert.ok(east > 117.19 && east < 117.198);
  assert.ok(south > 36.59 && south < 36.602);
  assert.ok(north > 36.69 && north < 36.702);
});

test("city results exclude countries/provinces, retain district choices, and span all boundary islands", () => {
  const result = transformAmapCities({ status: 1, districts: [
    { ...district, level: "country" }, { ...district, level: "province" },
    { ...district, level: "city", adcode: "370100", name: "济南市" }, district, district,
  ] });
  assert.deepEqual(result.cities.map((c) => c.id), ["370100", "370102"]);
  assert.equal(result.cities[0].detail, "全市范围");
  assert.equal(result.cities[1].detail, "区县范围");
  const bounds = districtBoundsWgs84(`${district.polyline}|118.0,37.0;118.1,37.0;118.1,37.1`);
  assert.ok(bounds[2] > 118 && bounds[3] > 37);
  assert.deepEqual(transformAmapCities({ status: "1", districts: [] }), { cities: [] });
});

test("province-level municipalities remain searchable while ordinary provinces are excluded", () => {
  const result = transformAmapCities({ status: "1", districts: [
    ...["110000", "120000", "310000", "500000"].map((adcode) => ({ ...district, adcode, level: "province" })),
    { ...district, adcode: "370000", level: "province", name: "山东省" },
  ] });
  assert.deepEqual(result.cities.map((c) => c.id), ["110000", "120000", "310000", "500000"]);
  assert.ok(result.cities.every((c) => c.detail === "全市范围"));
});

test("bad boundaries and upstream failure cannot become fabricated city extents", async () => {
  for (const boundary of ["", "117,36", "oops,36;117,36;118,37", "181,36;117,36;118,37", "117,;118,37;119,38"]) {
    assert.equal(districtBoundsWgs84(boundary), null);
  }
  assert.throws(() => transformAmapCities({ status: "1", districts: [{ ...district, polyline: [] }] }),
    { code: "CITY_BOUNDARY_UNAVAILABLE" });
  assert.throws(() => transformAmapCities({ status: "0", infocode: "10001" }), { code: "AMAP_10001", retryable: false });
  const provider = createAmapCitiesProvider({ key: "key", maxAttempts: 1,
    fetchImpl: async () => { throw new Error("private key detail"); } });
  await assert.rejects(provider.searchCities({ keywords: "北京" }), { code: "PROVIDER_UNAVAILABLE" });
  await assert.rejects(provider.searchCities({ keywords: "" }), { code: "INVALID_REQUEST" });
  await assert.rejects(provider.searchCities({ keywords: "x".repeat(81) }), { code: "INVALID_REQUEST" });
  await assert.rejects(createAmapCitiesProvider().searchCities({ keywords: "北京" }), { code: "SERVER_MISCONFIGURED" });
});
