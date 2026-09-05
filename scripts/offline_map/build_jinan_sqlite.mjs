#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import crypto from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { fileURLToPath } from "node:url";

const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const ROOT_DIR = path.resolve(SCRIPT_DIR, "../..");
const DEFAULT_INPUT = path.join(
  ROOT_DIR,
  "tmp/offline_map_source/jinan-roads-buildings.geojsonseq",
);
const DEFAULT_OUTPUT = path.join(ROOT_DIR, "shared/offline_map/jinan-v1.sqlite");

const inputPath = path.resolve(process.argv[2] ?? DEFAULT_INPUT);
const outputPath = path.resolve(process.argv[3] ?? DEFAULT_OUTPUT);
const sourceMetadataPath = path.join(ROOT_DIR, "tmp/offline_map_source/source-metadata.json");
const sourceMetadata = fs.existsSync(sourceMetadataPath)
  ? JSON.parse(fs.readFileSync(sourceMetadataPath, "utf8"))
  : {
      source_url: "https://download.geofabrik.de/asia/china/shandong.html",
      source_timestamp: "unknown",
      source_sha256: "unknown",
      boundary_relation: "3486449",
    };

const ROAD_CLASS = Object.freeze({
  motorway: 0,
  primary: 1,
  secondary: 2,
  residential: 3,
  service: 4,
  other: 5,
});

const BUILDING_CLASS = Object.freeze({
  generic: 0,
  landmark: 1,
  parking: 2,
});

const PI = Math.PI;
const EARTH_AXIS_M = 6378245.0;
const ECCENTRICITY_SQUARED = 0.006693421622965943;
const LATITUDE_METRES_PER_DEGREE = 111_320;
const JINAN_REFERENCE_LATITUDE_RAD = (36.67 / 180) * Math.PI;
const LONGITUDE_METRES_PER_DEGREE =
  LATITUDE_METRES_PER_DEGREE * Math.cos(JINAN_REFERENCE_LATITUDE_RAD);

function transformLatitude(longitudeOffset, latitudeOffset) {
  let result =
    -100.0 +
    2.0 * longitudeOffset +
    3.0 * latitudeOffset +
    0.2 * latitudeOffset * latitudeOffset +
    0.1 * longitudeOffset * latitudeOffset +
    0.2 * Math.sqrt(Math.abs(longitudeOffset));
  result +=
    ((20.0 * Math.sin(6.0 * longitudeOffset * PI) +
      20.0 * Math.sin(2.0 * longitudeOffset * PI)) *
      2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(latitudeOffset * PI) +
      40.0 * Math.sin((latitudeOffset / 3.0) * PI)) *
      2.0) /
    3.0;
  result +=
    ((160.0 * Math.sin((latitudeOffset / 12.0) * PI) +
      320.0 * Math.sin((latitudeOffset * PI) / 30.0)) *
      2.0) /
    3.0;
  return result;
}

function transformLongitude(longitudeOffset, latitudeOffset) {
  let result =
    300.0 +
    longitudeOffset +
    2.0 * latitudeOffset +
    0.1 * longitudeOffset * longitudeOffset +
    0.1 * longitudeOffset * latitudeOffset +
    0.1 * Math.sqrt(Math.abs(longitudeOffset));
  result +=
    ((20.0 * Math.sin(6.0 * longitudeOffset * PI) +
      20.0 * Math.sin(2.0 * longitudeOffset * PI)) *
      2.0) /
    3.0;
  result +=
    ((20.0 * Math.sin(longitudeOffset * PI) +
      40.0 * Math.sin((longitudeOffset / 3.0) * PI)) *
      2.0) /
    3.0;
  result +=
    ((150.0 * Math.sin((longitudeOffset / 12.0) * PI) +
      300.0 * Math.sin((longitudeOffset / 30.0) * PI)) *
      2.0) /
    3.0;
  return result;
}

function wgs84ToGcj02E6([longitudeDeg, latitudeDeg]) {
  let latitudeDelta = transformLatitude(longitudeDeg - 105.0, latitudeDeg - 35.0);
  let longitudeDelta = transformLongitude(longitudeDeg - 105.0, latitudeDeg - 35.0);
  const radianLatitude = (latitudeDeg / 180.0) * PI;
  let magic = Math.sin(radianLatitude);
  magic = 1 - ECCENTRICITY_SQUARED * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  latitudeDelta =
    (latitudeDelta * 180.0) /
    (((EARTH_AXIS_M * (1 - ECCENTRICITY_SQUARED)) / (magic * sqrtMagic)) * PI);
  longitudeDelta =
    (longitudeDelta * 180.0) /
    ((EARTH_AXIS_M / sqrtMagic) * Math.cos(radianLatitude) * PI);
  return [
    Math.round((latitudeDeg + latitudeDelta) * 1_000_000),
    Math.round((longitudeDeg + longitudeDelta) * 1_000_000),
  ];
}

function perpendicularDistanceSquared(point, start, end) {
  const pointX = point[1] / 1_000_000 * LONGITUDE_METRES_PER_DEGREE;
  const pointY = point[0] / 1_000_000 * LATITUDE_METRES_PER_DEGREE;
  const startX = start[1] / 1_000_000 * LONGITUDE_METRES_PER_DEGREE;
  const startY = start[0] / 1_000_000 * LATITUDE_METRES_PER_DEGREE;
  const endX = end[1] / 1_000_000 * LONGITUDE_METRES_PER_DEGREE;
  const endY = end[0] / 1_000_000 * LATITUDE_METRES_PER_DEGREE;
  const deltaX = endX - startX;
  const deltaY = endY - startY;
  if (deltaX === 0 && deltaY === 0) {
    return (pointX - startX) ** 2 + (pointY - startY) ** 2;
  }
  const progress = Math.max(
    0,
    Math.min(1, ((pointX - startX) * deltaX + (pointY - startY) * deltaY) /
      (deltaX * deltaX + deltaY * deltaY)),
  );
  const projectedX = startX + progress * deltaX;
  const projectedY = startY + progress * deltaY;
  return (pointX - projectedX) ** 2 + (pointY - projectedY) ** 2;
}

function simplifyOpen(points, toleranceM) {
  if (points.length <= 2) return points;
  const keep = new Uint8Array(points.length);
  keep[0] = 1;
  keep[points.length - 1] = 1;
  const stack = [[0, points.length - 1]];
  const toleranceSquared = toleranceM * toleranceM;
  while (stack.length > 0) {
    const [first, last] = stack.pop();
    let farthestIndex = -1;
    let farthestDistance = toleranceSquared;
    for (let index = first + 1; index < last; index += 1) {
      const distance = perpendicularDistanceSquared(points[index], points[first], points[last]);
      if (distance > farthestDistance) {
        farthestDistance = distance;
        farthestIndex = index;
      }
    }
    if (farthestIndex >= 0) {
      keep[farthestIndex] = 1;
      stack.push([first, farthestIndex], [farthestIndex, last]);
    }
  }
  return points.filter((_, index) => keep[index] === 1);
}

function simplifyRing(points, toleranceM) {
  if (points.length <= 4) return points;
  // Rotate to a stable, distant split point so the closed ring can be treated
  // as two open polylines without collapsing its area.
  let split = 1;
  let bestDistance = -1;
  for (let index = 1; index < points.length; index += 1) {
    const distance = perpendicularDistanceSquared(points[index], points[0], points[0]);
    if (distance > bestDistance) {
      bestDistance = distance;
      split = index;
    }
  }
  const firstHalf = simplifyOpen(points.slice(0, split + 1), toleranceM);
  const secondHalf = simplifyOpen([...points.slice(split), points[0]], toleranceM);
  const merged = [...firstHalf, ...secondHalf.slice(1, -1)];
  return merged.length >= 3 ? merged : points;
}

function deduplicateAdjacent(points) {
  const result = [];
  for (const point of points) {
    const previous = result[result.length - 1];
    if (!previous || previous[0] !== point[0] || previous[1] !== point[1]) {
      result.push(point);
    }
  }
  return result;
}

function mapRoadClass(highway) {
  if (highway === "motorway" || highway === "motorway_link" ||
      highway === "trunk" || highway === "trunk_link") return ROAD_CLASS.motorway;
  if (highway === "primary" || highway === "primary_link") return ROAD_CLASS.primary;
  if (highway === "secondary" || highway === "secondary_link") return ROAD_CLASS.secondary;
  if (highway === "residential") return ROAD_CLASS.residential;
  if (highway === "service") return ROAD_CLASS.service;
  return ROAD_CLASS.other;
}

function mapBuildingClass(properties) {
  const building = properties.building;
  if (["parking", "garages", "garage", "carport"].includes(building) ||
      properties.amenity === "parking") return BUILDING_CLASS.parking;
  if (properties.historic || properties.tourism === "attraction" ||
      ["cathedral", "church", "mosque", "temple", "synagogue", "stadium", "tower"]
        .includes(building)) return BUILDING_CLASS.landmark;
  return BUILDING_CLASS.generic;
}

function shouldKeepRoad(properties) {
  const denied = new Set(["no", "private"]);
  return !denied.has(properties.access) &&
    !denied.has(properties.motor_vehicle) &&
    !denied.has(properties.vehicle);
}

function parseOsmId(featureId) {
  const match = /^([wra])(\d+)(?:\/(\d+))?$/.exec(String(featureId));
  if (!match) return null;
  const sourceId = Number(match[2]);
  if (match[1] === "a") {
    // libosmium area ids are 2 * way_id for closed ways, and
    // 2 * relation_id + 1 for multipolygon relations.
    return sourceId % 2 === 0 ? sourceId / 2 : -((sourceId - 1) / 2);
  }
  // Negative values preserve that a multipolygon came from an OSM relation,
  // while ordinary OSM way ids remain positive.
  return match[1] === "r" ? -sourceId : sourceId;
}

function encodePoints(points) {
  const buffer = Buffer.allocUnsafe(points.length * 8);
  for (let index = 0; index < points.length; index += 1) {
    buffer.writeInt32LE(points[index][0], index * 8);
    buffer.writeInt32LE(points[index][1], index * 8 + 4);
  }
  return buffer;
}

function bounds(points) {
  let minLat = 90_000_000;
  let maxLat = -90_000_000;
  let minLon = 180_000_000;
  let maxLon = -180_000_000;
  for (const [latitude, longitude] of points) {
    minLat = Math.min(minLat, latitude);
    maxLat = Math.max(maxLat, latitude);
    minLon = Math.min(minLon, longitude);
    maxLon = Math.max(maxLon, longitude);
  }
  return [minLat, maxLat, minLon, maxLon];
}

function lineStrings(geometry) {
  if (geometry.type === "LineString") return [geometry.coordinates];
  if (geometry.type === "MultiLineString") return geometry.coordinates;
  return [];
}

function outerRings(geometry) {
  if (geometry.type === "Polygon") return geometry.coordinates.length ? [geometry.coordinates[0]] : [];
  if (geometry.type === "MultiPolygon") {
    return geometry.coordinates.flatMap((polygon) => polygon.length ? [polygon[0]] : []);
  }
  return [];
}

if (!fs.existsSync(inputPath)) {
  throw new Error(`Input does not exist: ${inputPath}`);
}
fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.rmSync(outputPath, { force: true });

const schemaPath = path.join(ROOT_DIR, "shared/offline_map/jinan-v1.sql");
if (!fs.existsSync(schemaPath)) {
  throw new Error(`Schema does not exist: ${schemaPath}`);
}
const database = new DatabaseSync(outputPath);
database.exec(`
  PRAGMA journal_mode=OFF;
  PRAGMA synchronous=OFF;
  PRAGMA temp_store=MEMORY;
  PRAGMA page_size=4096;
`);
database.exec(fs.readFileSync(schemaPath, "utf8"));
database.exec("BEGIN IMMEDIATE");

const insertRoad = database.prepare(`
  INSERT INTO roads(osm_way_id,class,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6,points)
  VALUES(?,?,?,?,?,?,?)
`);
const insertRoadRtree = database.prepare(
  "INSERT INTO road_rtree VALUES(?,?,?,?,?)",
);
const insertBuilding = database.prepare(`
  INSERT INTO buildings(osm_way_id,name,class,min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6,points)
  VALUES(?,?,?,?,?,?,?,?)
`);
const insertBuildingRtree = database.prepare(
  "INSERT INTO building_rtree VALUES(?,?,?,?,?)",
);
const insertMetadata = database.prepare("INSERT OR REPLACE INTO metadata VALUES(?,?)");

const stats = {
  inputFeatures: 0,
  roads: 0,
  roadPointsBefore: 0,
  roadPointsAfter: 0,
  buildings: 0,
  buildingPointsBefore: 0,
  buildingPointsAfter: 0,
  excludedPrivateRoads: 0,
  invalidFeatures: 0,
};

const input = fs.createReadStream(inputPath, "utf8");
const reader = readline.createInterface({ input, crlfDelay: Infinity });

for await (let line of reader) {
  line = line.replace(/^\x1e/, "").trim();
  if (!line) continue;
  const feature = JSON.parse(line);
  stats.inputFeatures += 1;
  const properties = feature.properties ?? {};
  const osmId = parseOsmId(feature.id);

  if (properties.highway) {
    if (!shouldKeepRoad(properties)) {
      stats.excludedPrivateRoads += 1;
      continue;
    }
    for (const coordinates of lineStrings(feature.geometry)) {
      stats.roadPointsBefore += coordinates.length;
      let points = deduplicateAdjacent(coordinates.map(wgs84ToGcj02E6));
      points = simplifyOpen(points, 1.25);
      if (points.length < 2) {
        stats.invalidFeatures += 1;
        continue;
      }
      const [minLat, maxLat, minLon, maxLon] = bounds(points);
      const result = insertRoad.run(
        osmId,
        mapRoadClass(properties.highway),
        minLat,
        maxLat,
        minLon,
        maxLon,
        encodePoints(points),
      );
      insertRoadRtree.run(result.lastInsertRowid, minLat, maxLat, minLon, maxLon);
      stats.roads += 1;
      stats.roadPointsAfter += points.length;
    }
  }

  if (properties.building) {
    for (let coordinates of outerRings(feature.geometry)) {
      stats.buildingPointsBefore += coordinates.length;
      // OSM polygons repeat the first coordinate at the end. The v1 schema
      // explicitly stores an open ring and the renderer closes it.
      if (coordinates.length > 1) {
        const first = coordinates[0];
        const last = coordinates[coordinates.length - 1];
        if (first[0] === last[0] && first[1] === last[1]) {
          coordinates = coordinates.slice(0, -1);
        }
      }
      let points = deduplicateAdjacent(coordinates.map(wgs84ToGcj02E6));
      points = simplifyRing(points, 0.55);
      if (points.length < 3) {
        stats.invalidFeatures += 1;
        continue;
      }
      const [minLat, maxLat, minLon, maxLon] = bounds(points);
      const result = insertBuilding.run(
        osmId,
        properties.name ?? properties["name:zh"] ?? null,
        mapBuildingClass(properties),
        minLat,
        maxLat,
        minLon,
        maxLon,
        encodePoints(points),
      );
      insertBuildingRtree.run(result.lastInsertRowid, minLat, maxLat, minLon, maxLon);
      stats.buildings += 1;
      stats.buildingPointsAfter += points.length;
    }
  }
}

const metadata = new Map([
  ["schema_version", "1"],
  ["coordinate_system", "GCJ-02"],
  ["region", "济南市"],
  ["region_osm_relation", String(sourceMetadata.boundary_relation)],
  ["source", "OpenStreetMap / Geofabrik Shandong extract"],
  ["source_url", String(sourceMetadata.source_url)],
  ["source_snapshot", String(sourceMetadata.source_timestamp)],
  ["source_sha256", String(sourceMetadata.source_sha256)],
  ["attribution", "© OpenStreetMap contributors, ODbL 1.0"],
  ["road_count", String(stats.roads)],
  ["building_count", String(stats.buildings)],
  ["road_point_count", String(stats.roadPointsAfter)],
  ["building_point_count", String(stats.buildingPointsAfter)],
]);
for (const [key, value] of metadata) insertMetadata.run(key, value);

database.exec(`
  COMMIT;
  ANALYZE;
  PRAGMA optimize;
  VACUUM;
`);

const integrity = database.prepare("PRAGMA integrity_check").get().integrity_check;
if (integrity !== "ok") throw new Error(`SQLite integrity check failed: ${integrity}`);
const coverage = database.prepare(`
  SELECT min(min_lat_e6) min_lat_e6, max(max_lat_e6) max_lat_e6,
         min(min_lon_e6) min_lon_e6, max(max_lon_e6) max_lon_e6
  FROM (
    SELECT min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6 FROM roads
    UNION ALL
    SELECT min_lat_e6,max_lat_e6,min_lon_e6,max_lon_e6 FROM buildings
  )
`).get();
const roadClasses = Object.fromEntries(
  database.prepare("SELECT class,count(*) count FROM roads GROUP BY class ORDER BY class")
    .all().map(({ class: classId, count }) => [classId, Number(count)]),
);
const buildingClasses = Object.fromEntries(
  database.prepare("SELECT class,count(*) count FROM buildings GROUP BY class ORDER BY class")
    .all().map(({ class: classId, count }) => [classId, Number(count)]),
);
database.close();

const byteSize = fs.statSync(outputPath).size;
const sha256 = crypto.createHash("sha256").update(fs.readFileSync(outputPath)).digest("hex");
const report = {
  input: path.relative(ROOT_DIR, inputPath),
  output: path.relative(ROOT_DIR, outputPath),
  byteSize,
  mebibytes: Number((byteSize / 1024 / 1024).toFixed(2)),
  sha256,
  source: sourceMetadata,
  coverageGcj02E6: coverage,
  classCounts: { roads: roadClasses, buildings: buildingClasses },
  ...stats,
};
fs.writeFileSync(
  outputPath.replace(/\.sqlite$/, ".manifest.json"),
  `${JSON.stringify(report, null, 2)}\n`,
);
console.log(JSON.stringify(report, null, 2));
