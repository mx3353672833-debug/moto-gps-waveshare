#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const rootDirectory = path.resolve(scriptDirectory, "../..");
const databasePath = path.resolve(
  process.argv[2] ?? path.join(rootDirectory, "shared/offline_map/jinan-v1.sqlite"),
);

function invariant(condition, message) {
  if (!condition) throw new Error(message);
}

invariant(fs.existsSync(databasePath), `Missing database: ${databasePath}`);
const database = new DatabaseSync(databasePath, { readOnly: true });

const metadata = Object.fromEntries(
  database.prepare("SELECT key,value FROM metadata").all().map(({ key, value }) => [key, value]),
);
invariant(metadata.schema_version === "1", "schema_version must be 1");
invariant(metadata.coordinate_system === "GCJ-02", "coordinate_system must be GCJ-02");
invariant(metadata.attribution?.includes("OpenStreetMap"), "OSM attribution is missing");
invariant(database.prepare("PRAGMA user_version").get().user_version === 1, "user_version must be 1");
invariant(
  database.prepare("PRAGMA application_id").get().application_id === 0x4d475053,
  "application_id must be MGPS",
);
invariant(database.prepare("PRAGMA integrity_check").get().integrity_check === "ok", "integrity_check failed");

const roadCount = database.prepare("SELECT count(*) count FROM roads").get().count;
const roadRtreeCount = database.prepare("SELECT count(*) count FROM road_rtree").get().count;
const buildingCount = database.prepare("SELECT count(*) count FROM buildings").get().count;
const buildingRtreeCount = database.prepare("SELECT count(*) count FROM building_rtree").get().count;
invariant(roadCount === roadRtreeCount, "road R-tree count mismatch");
invariant(buildingCount === buildingRtreeCount, "building R-tree count mismatch");
invariant(roadCount > 10_000, "road coverage is unexpectedly sparse");
invariant(buildingCount > 10_000, "building coverage is unexpectedly sparse");

const invalidRoads = database.prepare(`
  SELECT count(*) count FROM roads
  WHERE osm_way_id IS NULL
     OR length(points) < 16 OR length(points) % 8 != 0
     OR class NOT BETWEEN 0 AND 5
     OR min_lat_e6 > max_lat_e6 OR min_lon_e6 > max_lon_e6
`).get().count;
invariant(invalidRoads === 0, `${invalidRoads} invalid road rows`);

const invalidBuildings = database.prepare(`
  SELECT count(*) count FROM buildings
  WHERE osm_way_id IS NULL
     OR length(points) < 24 OR length(points) % 8 != 0
     OR class NOT BETWEEN 0 AND 2
     OR min_lat_e6 > max_lat_e6 OR min_lon_e6 > max_lon_e6
`).get().count;
invariant(invalidBuildings === 0, `${invalidBuildings} invalid building rows`);

function decodePoint(buffer, byteOffset) {
  const view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
  return [view.getInt32(byteOffset, true), view.getInt32(byteOffset + 4, true)];
}

for (const table of ["roads", "buildings"]) {
  const rows = database.prepare(`
    SELECT id,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6,points FROM ${table}
  `).iterate();
  for (const row of rows) {
    const pointCount = row.points.length / 8;
    let minimumLatitude = Number.POSITIVE_INFINITY;
    let maximumLatitude = Number.NEGATIVE_INFINITY;
    let minimumLongitude = Number.POSITIVE_INFINITY;
    let maximumLongitude = Number.NEGATIVE_INFINITY;
    for (let index = 0; index < pointCount; index += 1) {
      const [latitude, longitude] = decodePoint(row.points, index * 8);
      invariant(latitude >= 35_000_000 && latitude <= 39_000_000, `${table} ${row.id}: latitude out of range`);
      invariant(longitude >= 115_000_000 && longitude <= 120_000_000, `${table} ${row.id}: longitude out of range`);
      minimumLatitude = Math.min(minimumLatitude, latitude);
      maximumLatitude = Math.max(maximumLatitude, latitude);
      minimumLongitude = Math.min(minimumLongitude, longitude);
      maximumLongitude = Math.max(maximumLongitude, longitude);
    }
    invariant(
      minimumLatitude === row.min_lat_e6 && maximumLatitude === row.max_lat_e6 &&
      minimumLongitude === row.min_lon_e6 && maximumLongitude === row.max_lon_e6,
      `${table} ${row.id}: stored bounds do not match geometry`,
    );
    if (table === "buildings") {
      const first = decodePoint(row.points, 0);
      const last = decodePoint(row.points, row.points.length - 8);
      invariant(first[0] !== last[0] || first[1] !== last[1], `building ${row.id}: ring is repeated closed`);
    }
  }
}

// GCJ-02 position of the D-building / Inspur demo neighbourhood. These
// minimums catch accidental bbox-only exports and coordinate-system mistakes.
const demoBounds = {
  minLat: 36_668_000,
  maxLat: 36_681_000,
  minLon: 117_120_000,
  maxLon: 117_140_000,
};
const nearbyRoads = database.prepare(`
  SELECT count(*) count FROM road_rtree
  WHERE max_lat_e6 >= ? AND min_lat_e6 <= ?
    AND max_lon_e6 >= ? AND min_lon_e6 <= ?
`).get(demoBounds.minLat, demoBounds.maxLat, demoBounds.minLon, demoBounds.maxLon).count;
const nearbyBuildings = database.prepare(`
  SELECT count(*) count FROM building_rtree
  WHERE max_lat_e6 >= ? AND min_lat_e6 <= ?
    AND max_lon_e6 >= ? AND min_lon_e6 <= ?
`).get(demoBounds.minLat, demoBounds.maxLat, demoBounds.minLon, demoBounds.maxLon).count;
invariant(nearbyRoads >= 100, "D-building neighbourhood roads are unexpectedly sparse");
invariant(nearbyBuildings >= 100, "D-building neighbourhood buildings are unexpectedly sparse");

const roadPoints = database.prepare("SELECT sum(length(points)/8) count FROM roads").get().count;
const buildingPoints = database.prepare("SELECT sum(length(points)/8) count FROM buildings").get().count;
const byteSize = fs.statSync(databasePath).size;

console.log(JSON.stringify({
  database: databasePath,
  schemaVersion: metadata.schema_version,
  coordinateSystem: metadata.coordinate_system,
  byteSize,
  mebibytes: Number((byteSize / 1024 / 1024).toFixed(2)),
  roads: Number(roadCount),
  roadPoints: Number(roadPoints),
  buildings: Number(buildingCount),
  buildingPoints: Number(buildingPoints),
  demoNeighbourhood: {
    roads: Number(nearbyRoads),
    buildings: Number(nearbyBuildings),
  },
  integrity: "ok",
}, null, 2));

database.close();
