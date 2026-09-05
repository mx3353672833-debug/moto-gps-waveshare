import assert from "node:assert/strict";
import test from "node:test";

import {
  buildAmapPlacesUrl,
  createAmapPlacesProvider,
  transformAmapPlaces,
} from "../src/amap-places.js";
import { wgs84ToGcj02 } from "../src/coordinates.js";

test("builds a server-keyed POI request without exposing platform details", () => {
  const url = buildAmapPlacesUrl(
    { keywords: "北京站", region: "北京市" },
    { key: "test-only-placeholder" },
  );

  assert.equal(url.origin, "https://restapi.amap.com");
  assert.equal(url.pathname, "/v5/place/text");
  assert.equal(url.searchParams.get("keywords"), "北京站");
  assert.equal(url.searchParams.get("region"), "北京市");
  assert.equal(url.searchParams.get("show_fields"), "navi");
});

test("uses AMap nearby weight search when a WGS84 phone position is supplied", () => {
  const origin = { coordinate_system: "WGS84", longitude_deg: 117.1224488, latitude_deg: 36.6748039 };
  const expectedGcj02 = wgs84ToGcj02(origin);
  const url = buildAmapPlacesUrl(
    { keywords: "大数据产业中心D栋", origin },
    { key: "test-only-placeholder" },
  );

  assert.equal(url.pathname, "/v5/place/around");
  assert.equal(
    url.searchParams.get("location"),
    `${expectedGcj02.longitude_deg.toFixed(6)},${expectedGcj02.latitude_deg.toFixed(6)}`,
  );
  assert.equal(url.searchParams.get("radius"), "50000");
  assert.equal(url.searchParams.get("sortrule"), "weight");
});

test("returns WGS84 navigation entrances from GCJ-02 POI data", () => {
  const source = { longitude_deg: 116.397389, latitude_deg: 39.908722 };
  const gcj02 = wgs84ToGcj02(source);
  const result = transformAmapPlaces(
    {
      status: "1",
      pois: [
        {
          id: "B000A83V3S",
          name: "北京站",
          location: `${gcj02.longitude_deg},${gcj02.latitude_deg}`,
          address: "毛家湾胡同甲13号",
          cityname: "北京市",
          adname: "东城区",
          navi: { entr_location: `${gcj02.longitude_deg},${gcj02.latitude_deg}` },
        },
      ],
    },
    { keywords: "北京站" },
  );

  assert.equal(result.protocol_version, 1);
  assert.equal(result.places[0].name, "北京站");
  assert.equal(result.places[0].location.coordinate_system, "WGS84");
  assert.ok(Math.abs(result.places[0].location.longitude_deg - source.longitude_deg) < 0.000002);
});

test("keeps AMap relevance order and reports distance from the phone", () => {
  const origin = { coordinate_system: "WGS84", longitude_deg: 117.1224488, latitude_deg: 36.6748039 };
  const location = wgs84ToGcj02(origin);
  const result = transformAmapPlaces(
    {
      status: "1",
      pois: [
        {
          id: "TARGET-D",
          name: "山东省大数据产业基地D栋",
          location: `${location.longitude_deg},${location.latitude_deg}`,
          address: "舜华路879号",
          cityname: "济南市",
          adname: "历下区",
        },
      ],
    },
    { keywords: "大数据产业中心D栋", origin },
  );

  assert.equal(result.places[0].name, "山东省大数据产业基地D栋");
  assert.ok(result.places[0].distance_m <= 2);
});

test("falls back to nationwide text search when nearby search has no matches", async () => {
  const requests = [];
  const provider = createAmapPlacesProvider({
    key: "test-only-placeholder",
    endpoint: "https://provider.test/v5/place/text",
    aroundEndpoint: "https://provider.test/v5/place/around",
    fetchImpl: async (url) => {
      requests.push(url);
      const payload = requests.length === 1
        ? { status: "1", pois: [] }
        : {
            status: "1",
            pois: [
              {
                id: "B000A83V3S",
                name: "北京站",
                location: "116.427268,39.902358",
                cityname: "北京市",
                adname: "东城区",
              },
            ],
          };
      return new Response(JSON.stringify(payload), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    },
  });

  const result = await provider.searchPlaces({
    keywords: "北京站",
    origin: { coordinate_system: "WGS84", longitude_deg: 117.12, latitude_deg: 36.67 },
  });

  assert.equal(requests.length, 2);
  assert.equal(requests[0].pathname, "/v5/place/around");
  assert.equal(requests[1].pathname, "/v5/place/text");
  assert.equal(result.places[0].name, "北京站");
  assert.ok(result.places[0].distance_m > 300_000);
});

test("retries a temporary POI transport failure and keeps the request bounded", async () => {
  const source = { longitude_deg: 117.1224488, latitude_deg: 36.6748039 };
  const location = wgs84ToGcj02(source);
  let calls = 0;
  const provider = createAmapPlacesProvider({
    key: "test-only-placeholder",
    retryDelayMs: 0,
    fetchImpl: async () => {
      calls += 1;
      if (calls < 3) throw new TypeError("temporary network failure");
      return new Response(JSON.stringify({
        status: "1",
        pois: [{
          id: "TARGET-D",
          name: "山东省大数据产业基地D栋",
          location: `${location.longitude_deg},${location.latitude_deg}`,
        }],
      }), { status: 200, headers: { "Content-Type": "application/json" } });
    },
  });

  const result = await provider.searchPlaces({ keywords: "大数据产业中心D栋" });

  assert.equal(calls, 3);
  assert.equal(result.places[0].name, "山东省大数据产业基地D栋");
});

test("does not retry POI HTTP or AMap business errors", async (t) => {
  await t.test("HTTP 400 is returned immediately", async () => {
    let calls = 0;
    const provider = createAmapPlacesProvider({
      key: "test-only-placeholder",
      retryDelayMs: 0,
      fetchImpl: async () => {
        calls += 1;
        return new Response("{}", { status: 400 });
      },
    });

    await assert.rejects(
      provider.searchPlaces({ keywords: "北京站" }),
      (error) => error.code === "PROVIDER_HTTP_ERROR" && error.retryable === false,
    );
    assert.equal(calls, 1);
  });

  await t.test("HTTP 200 business error is returned immediately", async () => {
    let calls = 0;
    const provider = createAmapPlacesProvider({
      key: "test-only-placeholder",
      retryDelayMs: 0,
      fetchImpl: async () => {
        calls += 1;
        return new Response(JSON.stringify({
          status: "0",
          infocode: "10001",
          info: "INVALID_USER_KEY",
        }), { status: 200, headers: { "Content-Type": "application/json" } });
      },
    });

    await assert.rejects(
      provider.searchPlaces({ keywords: "北京站" }),
      (error) => error.code === "AMAP_10001",
    );
    assert.equal(calls, 1);
  });
});
