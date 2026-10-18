import test from "node:test";
import assert from "node:assert/strict";
import { createOsmProvider } from "../src/osm-provider.js";

test("osm-provider searchPlaces respects origin viewbox", async () => {
  let fetchedUrl = "";
  globalThis.fetch = async (url) => {
    fetchedUrl = String(url);
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
});

test("osm-provider planRoute returns RouteBundle v1 with GCJ-02 geometry and maneuvers", async () => {
  globalThis.fetch = async () => {
    return {
      ok: true,
      json: async () => ({
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
  };

  const provider = createOsmProvider();
  const route = await provider.planRoute({
    origin: { longitude_deg: 12.4964, latitude_deg: 41.9028 },
    destination: { longitude_deg: 12.4922, latitude_deg: 41.8902 },
  });

  assert.equal(route.protocol_version, 1);
  assert.equal(route.schema_version, 1);
  assert.equal(route.provider, "osm");
  assert.equal(route.coordinate_system, "GCJ-02");
  assert.equal(route.total_distance_m, 1251);
  assert.equal(route.estimated_duration_s, 180);
  assert.equal(route.polyline.length, 2);
  assert.equal(route.geometry_gcj02.length, 2);
  assert.equal(route.maneuvers.length, 2);
  assert.equal(route.maneuvers[0].type, "right");
  assert.equal(route.maneuvers[1].type, "arrive");

  const options = await provider.planRouteOptions({
    origin: { longitude_deg: 12.4964, latitude_deg: 41.9028 },
    destination: { longitude_deg: 12.4922, latitude_deg: 41.8902 },
  });
  assert.ok(Array.isArray(options));
  assert.equal(options.length, 1);
});
