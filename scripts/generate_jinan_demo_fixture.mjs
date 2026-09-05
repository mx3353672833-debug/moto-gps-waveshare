#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { wgs84ToGcj02 } from "../backend/src/coordinates.js";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..");
const sourcePath = path.join(
  repositoryRoot,
  "shared/demo_fixture/jinan_big_data_to_inspur.json",
);
const cppPath = path.join(
  repositoryRoot,
  "shared/demo_fixture/jinan_big_data_demo.hpp",
);
const swiftPath = path.join(
  repositoryRoot,
  "platforms/ios/App/Adapters/Navigation/JinanDemoFixture.generated.swift",
);

const source = JSON.parse(fs.readFileSync(sourcePath, "utf8"));

function invariant(condition, message) {
  if (!condition) throw new Error(`Invalid Jinan demo fixture: ${message}`);
}

function validatePoint(point, label) {
  invariant(Array.isArray(point) && point.length === 2, `${label} must be [latitude, longitude]`);
  invariant(point.every(Number.isFinite), `${label} must contain finite numbers`);
}

source.route_wgs84.forEach((point, index) => validatePoint(point, `route_wgs84[${index}]`));
invariant(source.route_wgs84.length >= 2, "route must have at least two points");
invariant(source.route_wgs84.length <= 255, "route point indices must fit uint8_t");
invariant(source.maneuvers.length >= 2, "route must contain maneuvers");
for (const maneuver of source.maneuvers) {
  invariant(
    Number.isInteger(maneuver.route_point_index) &&
      maneuver.route_point_index >= 0 &&
      maneuver.route_point_index < source.route_wgs84.length,
    `maneuver ${maneuver.id} has an invalid route point index`,
  );
}
invariant(source.road_context.length <= 24, "ESP32 UI supports at most 24 road polylines");
const roadPointCount = source.road_context.reduce(
  (total, road) => total + road.points_wgs84.length,
  0,
);
invariant(roadPointCount <= 192, "ESP32 UI supports at most 192 road points");
const roadWayIds = new Set();
for (const road of source.road_context) {
  invariant(road.points_wgs84.length >= 2, `road ${road.osm_way_id} is too short`);
  invariant(Number.isInteger(road.osm_way_id), `road ${road.name} needs an OSM way id`);
  invariant(!roadWayIds.has(road.osm_way_id), `duplicate OSM way ${road.osm_way_id}`);
  roadWayIds.add(road.osm_way_id);
  invariant(
    Number.isInteger(road.osm_way_version) && road.osm_way_version > 0,
    `road ${road.osm_way_id} needs its source OSM version`,
  );
  invariant(
    typeof road.osm_way_timestamp === "string" && road.osm_way_timestamp.length > 0,
    `road ${road.osm_way_id} needs its source OSM timestamp`,
  );
  invariant(
    typeof road.highway === "string" && road.highway.length > 0,
    `road ${road.osm_way_id} needs its highway class`,
  );
  invariant(
    Number.isFinite(road.simplification_tolerance_m) &&
      road.simplification_tolerance_m >= 0,
    `road ${road.osm_way_id} needs its simplification tolerance`,
  );
  road.points_wgs84.forEach((point, index) =>
    validatePoint(point, `road ${road.osm_way_id} point ${index}`),
  );
}

function gcj(point) {
  const converted = wgs84ToGcj02({
    latitude_deg: point[0],
    longitude_deg: point[1],
  });
  return [converted.latitude_deg, converted.longitude_deg];
}

function fixed(value) {
  return Number(value).toFixed(7);
}

function cppPoint(point) {
  return `    {${fixed(point[0])}, ${fixed(point[1])}},`;
}

function swiftWgsPoint(point) {
  return `        WGS84Point(longitudeDeg: ${fixed(point[1])}, latitudeDeg: ${fixed(point[0])}),`;
}

function swiftGcjPoint(point) {
  return `        GCJ02Point(longitudeDeg: ${fixed(point[1])}, latitudeDeg: ${fixed(point[0])}),`;
}

function cppManeuver(type) {
  const names = {
    continue: "Continue",
    slight_left: "SlightLeft",
    left: "Left",
    sharp_left: "SharpLeft",
    u_turn_left: "UTurnLeft",
    slight_right: "SlightRight",
    right: "Right",
    sharp_right: "SharpRight",
    u_turn_right: "UTurnRight",
    roundabout: "Roundabout",
    exit: "Exit",
    arrive: "Arrive",
  };
  invariant(names[type], `unsupported maneuver type ${type}`);
  return `nav::ManeuverType::${names[type]}`;
}

function swiftManeuver(type) {
  const names = {
    continue: "continue",
    slight_left: "slightLeft",
    left: "left",
    sharp_left: "sharpLeft",
    u_turn_left: "uTurnLeft",
    slight_right: "slightRight",
    right: "right",
    sharp_right: "sharpRight",
    u_turn_right: "uTurnRight",
    roundabout: "roundabout",
    exit: "exit",
    arrive: "arrive",
  };
  invariant(names[type], `unsupported maneuver type ${type}`);
  return `.${names[type]}`;
}

const routeGcj = source.route_wgs84.map(gcj);
const flatRoadWgs = source.road_context.flatMap((road) => road.points_wgs84);
const flatRoadGcj = flatRoadWgs.map(gcj);
let roadFirstPoint = 0;
const roadSpans = source.road_context.map((road) => {
  const span = {
    first: roadFirstPoint,
    count: road.points_wgs84.length,
    name: road.name,
    way: road.osm_way_id,
  };
  roadFirstPoint += road.points_wgs84.length;
  return span;
});

const cpp = `// Generated by scripts/generate_jinan_demo_fixture.mjs. Do not edit.
// Source: shared/demo_fixture/jinan_big_data_to_inspur.json
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

#include "port_api/nav_types.hpp"

namespace moto::demo::jinan_big_data {

struct ManeuverFixture {
  std::uint32_t id;
  nav::ManeuverType type;
  std::uint8_t route_point_index;
  const char* road_name;
  const char* instruction;
};

struct RoadSpanFixture {
  std::uint8_t first_point_index;
  std::uint8_t point_count;
  const char* name;
  std::uint64_t osm_way_id;
};

inline constexpr std::array<nav::Wgs84Point, ${source.route_wgs84.length}> kRouteWgs84{{
${source.route_wgs84.map(cppPoint).join("\n")}
}};

// The same OSM geometry converted through the project's tested WGS84 ->
// GCJ-02 transform. The firmware renders and route-matches in GCJ-02.
inline constexpr std::array<nav::Gcj02Point, ${routeGcj.length}> kRoute{{
${routeGcj.map(cppPoint).join("\n")}
}};

inline constexpr std::array<ManeuverFixture, ${source.maneuvers.length}> kManeuvers{{
${source.maneuvers
  .map(
    (maneuver) =>
      `    {${maneuver.id}, ${cppManeuver(maneuver.type)}, ${maneuver.route_point_index}, "${maneuver.road_name}", "${maneuver.instruction}"},`,
  )
  .join("\n")}
}};

inline constexpr std::array<nav::Gcj02Point, ${flatRoadGcj.length}> kRoadPoints{{
${flatRoadGcj.map(cppPoint).join("\n")}
}};

inline constexpr std::array<RoadSpanFixture, ${roadSpans.length}> kRoads{{
${roadSpans
  .map(
    (span) =>
      `    {${span.first}, ${span.count}, "${span.name}", ${span.way}ULL},`,
  )
  .join("\n")}
}};

inline constexpr std::array<std::uint64_t, ${source.route_source_ways.length}>
    kRouteSourceWayIds{{
${source.route_source_ways.map((way) => `        ${way.osm_way_id}ULL,`).join("\n")}
    }};

inline constexpr nav::Wgs84Point kRequestedOriginWgs84{
    ${fixed(source.requested_origin_wgs84[0])},
    ${fixed(source.requested_origin_wgs84[1])}};
inline constexpr nav::Wgs84Point kRequestedDestinationWgs84{
    ${fixed(source.requested_destination_wgs84[0])},
    ${fixed(source.requested_destination_wgs84[1])}};
inline constexpr std::uint32_t kFallbackDurationS = ${source.fallback_duration_s};
inline constexpr char kFixtureId[] = "${source.fixture_id}";
inline constexpr char kRouteId[] = "${source.route_id}";
inline constexpr char kReroutedRouteId[] = "${source.route_id}-rerouted";
inline constexpr char kDestinationPoiId[] = "${source.destination_poi_id}";
inline constexpr char kRoadAttribution[] =
    "Map data (c) OpenStreetMap contributors, ODbL";
inline constexpr char kRoadLicenceUrl[] =
    "https://www.openstreetmap.org/copyright";

}  // namespace moto::demo::jinan_big_data
`;

const swift = `// Generated by scripts/generate_jinan_demo_fixture.mjs. Do not edit.
// Source: shared/demo_fixture/jinan_big_data_to_inspur.json
import MotoNavigationCore

struct JinanDemoManeuverFixture: Sendable {
    let id: UInt32
    let type: ManeuverType
    let routePointIndex: Int
    let roadName: String
    let instruction: String
}

enum JinanDemoFixture {
    static let fixtureID = "${source.fixture_id}"
    static let routeID = "${source.route_id}"
    static let reroutedRouteID = "${source.route_id}-rerouted"
    static let destinationPOIID = "${source.destination_poi_id}"
    static let fallbackDurationS = ${source.fallback_duration_s}
    static let roadAttribution = "道路 © OpenStreetMap contributors"

    static let requestedOriginWGS84 = WGS84Point(
        longitudeDeg: ${fixed(source.requested_origin_wgs84[1])},
        latitudeDeg: ${fixed(source.requested_origin_wgs84[0])}
    )
    static let requestedDestinationWGS84 = WGS84Point(
        longitudeDeg: ${fixed(source.requested_destination_wgs84[1])},
        latitudeDeg: ${fixed(source.requested_destination_wgs84[0])}
    )

    static let routeWGS84: [WGS84Point] = [
${source.route_wgs84.map(swiftWgsPoint).join("\n")}
    ]

    static let routeGCJ02: [GCJ02Point] = [
${routeGcj.map(swiftGcjPoint).join("\n")}
    ]

    static let maneuvers: [JinanDemoManeuverFixture] = [
${source.maneuvers
  .map(
    (maneuver) =>
      `        JinanDemoManeuverFixture(id: ${maneuver.id}, type: ${swiftManeuver(maneuver.type)}, routePointIndex: ${maneuver.route_point_index}, roadName: "${maneuver.road_name}", instruction: "${maneuver.instruction}"),`,
  )
  .join("\n")}
    ]
}
`;

const outputs = [
  [cppPath, cpp],
  [swiftPath, swift],
];
const checkOnly = process.argv.includes("--check");
let stale = false;
for (const [outputPath, content] of outputs) {
  const existing = fs.existsSync(outputPath)
    ? fs.readFileSync(outputPath, "utf8")
    : null;
  if (existing === content) continue;
  stale = true;
  if (checkOnly) {
    console.error(`${path.relative(repositoryRoot, outputPath)} is stale`);
  } else {
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, content);
    console.log(`generated ${path.relative(repositoryRoot, outputPath)}`);
  }
}

if (checkOnly && stale) process.exitCode = 1;
