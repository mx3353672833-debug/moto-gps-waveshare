import test from "node:test";
import assert from "node:assert/strict";
import { createOsmProvider } from "../src/osm-provider.js";

test("osm-provider searchPlaces respects origin viewbox and headers", async () => {
  let fetchedUrl = "";
  let fetchedOptions = {};
  globalThis.fetch = async (url, options) => {
    fetchedUrl = String(url);
    fetchedOptions = options || {};
    return {
      ok: true,
      json: async () => [
        {
          place_id: 123,
          name: "Colosseo",
          display_name: "Colosseo, Roma, Italia",
          lat: "41.8902",
          lon: "12.4922",
        },
      ],
    };
  };

  const provider = createOsmProvider();
  const res = await provider.searchPlaces({
    keywords: "Colosseo",
    origin: { longitude_deg: 12.4964, latitude_deg: 41.9028 },
  });

  assert.equal(res.protocol_version, 1);
  assert.equal(res.places.length, 1);
  assert.equal(res.places[0].name, "Colosseo");
  assert.ok(fetchedUrl.includes("viewbox="));
  assert.equal(
    fetchedOptions.headers["User-Agent"],
    "MotoGpsWaveshare/1.0 (https://github.com/thriller47/moto-gps-waveshare)"
  );
  assert.equal(fetchedOptions.headers["Accept-Language"], "it,en");
});

test("osm-provider searchPlaces falls back to Photon Komoot when Nominatim fails", async () => {
  const fetchedUrls = [];
  globalThis.fetch = async (url) => {
    const strUrl = String(url);
    fetchedUrls.push(strUrl);

    if (strUrl.includes("nominatim.openstreetmap.org")) {
      return {
        ok: false,
        status: 503,
      };
    }

    if (strUrl.includes("photon.komoot.io")) {
      return {
        ok: true,
        json: async () => ({
          type: "FeatureCollection",
          features: [
            {
              geometry: {
                coordinates: [12.4922, 41.8902],
              },
              properties: {
                osm_id: 999,
                name: "Colosseo",
                street: "Piazza del Colosseo",
                city: "Roma",
                country: "Italia",
              },
            },
          ],
        }),
      };
    }

    return { ok: false, status: 404 };
  };

  const provider = createOsmProvider();
  const res = await provider.searchPlaces({
    keywords: "Colosseo",
    origin: { longitude_deg: 12.4964, latitude_deg: 41.9028 },
  });

  assert.equal(res.protocol_version, 1);
  assert.equal(res.places.length, 1);
  assert.equal(res.places[0].id, "999");
  assert.equal(res.places[0].name, "Colosseo");
  assert.equal(res.places[0].location.longitude_deg, 12.4922);
  assert.equal(res.places[0].location.latitude_deg, 41.8902);
  assert.equal(fetchedUrls.length, 2);
  assert.ok(fetchedUrls[0].includes("nominatim"));
  assert.ok(fetchedUrls[1].includes("photon.komoot.io"));
});

test("osm-provider planRoute cycles OSRM endpoints until one succeeds", async () => {
  const requestedEndpoints = [];
  globalThis.fetch = async (url) => {
    const strUrl = String(url);
    requestedEndpoints.push(strUrl);

    // Primary endpoint (routed-car) fails with 503
    if (strUrl.includes("routing.openstreetmap.de/routed-car")) {
      return {
        ok: false,
        status: 503,
      };
    }

    // Fallback 1 (router.project-osrm.org) succeeds
    if (strUrl.includes("router.project-osrm.org")) {
      return {
        ok: true,
        json: async () => ({
          code: "Ok",
          routes: [
            {
              distance: 1250.5,
              duration: 180.2,
              geometry: {
                coordinates: [
                  [12.4964, 41.9028],
                  [12.4922, 41.8902],
                ],
              },
              legs: [
                {
                  steps: [
                    {
                      distance: 500,
                      name: "Via Nazionale",
                      maneuver: {
                        type: "turn",
                        modifier: "right",
                        instruction: "Svolta a destra in Via Nazionale",
                        location: [12.4964, 41.9028],
                      },
                    },
                    {
                      distance: 750,
                      name: "Via Cavour",
                      maneuver: {
                        type: "arrive",
                        modifier: "straight",
                        instruction: "Arrivo a destinazione",
                        location: [12.4922, 41.8902],
                      },
                    },
                  ],
                },
              ],
            },
          ],
        }),
      };
    }

    return { ok: false, status: 500 };
  };

  const provider = createOsmProvider();
  const route = await provider.planRoute({
    origin: { longitude_deg: 12.4964, latitude_deg: 41.9028 },
    destination: { longitude_deg: 12.4922, latitude_deg: 41.8902 },
  });

  assert.equal(route.protocol_version, 1);
  assert.equal(route.schema_version, 1);
  assert.equal(route.provider, "osm");
  assert.equal(route.total_distance_m, 1251);
  assert.equal(requestedEndpoints.length, 2);
  assert.ok(requestedEndpoints[0].includes("routing.openstreetmap.de/routed-car"));
  assert.ok(requestedEndpoints[1].includes("router.project-osrm.org"));
});
