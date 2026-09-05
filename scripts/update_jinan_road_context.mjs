#!/usr/bin/env node

// Refresh the bounded Jinan demo background from original OpenStreetMap ways.
//
// This deliberately uses the official OSM API rather than rendered map tiles:
// every emitted coordinate is an existing OSM node. Ramer-Douglas-Peucker only
// chooses which source nodes to retain, so the updater never invents a street.

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "..");
const fixturePath = path.join(
  repositoryRoot,
  "shared/demo_fixture/jinan_big_data_to_inspur.json",
);

const apiRoot = "https://api.openstreetmap.org/api/0.6";
const maximumRoadPoints = 192;
const maximumRoadPolylines = 24;

// These ways were selected because, together, they keep visible real context
// around the full north-to-south D-building -> Inspur demo corridor. The
// tolerances were chosen to exactly fill the firmware's 192-point budget.
const selectedWays = [
  {
    osmWayId: 1_296_826_090,
    displayName: "大数据产业基地园区道路",
    toleranceM: 2,
  },
  {
    osmWayId: 1_222_956_211,
    displayName: "新泺大街北侧车道",
    toleranceM: 2,
  },
  {
    osmWayId: 136_622_039,
    displayName: "新泺大街西段双向车道",
    toleranceM: 2,
  },
  {
    osmWayId: 562_686_627,
    displayName: "舜华西路",
    toleranceM: 2,
  },
  {
    osmWayId: 261_478_935,
    displayName: "北段园区道路（OSM未命名）",
    toleranceM: 3,
  },
  {
    osmWayId: 261_478_934,
    displayName: "银荷大厦停车场",
    toleranceM: 2,
  },
  {
    osmWayId: 1_339_796_791,
    displayName: "D栋西北侧园区道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 188_224_840,
    displayName: "西侧居住区道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 910_664_044,
    displayName: "西侧园区环路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 910_896_680,
    displayName: "北中段园区支路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 915_085_979,
    displayName: "东侧园区道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 914_735_347,
    displayName: "中段园区环路（OSM未命名）",
    toleranceM: 3,
  },
  {
    osmWayId: 914_735_349,
    displayName: "中段东侧支路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 977_925_493,
    displayName: "中段西侧园区道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 915_078_291,
    displayName: "中南段东侧园区道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 790_389_632,
    displayName: "伯乐路",
    toleranceM: 2,
  },
  {
    osmWayId: 1_270_602_750,
    displayName: "伯乐路南侧支路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 1_270_602_752,
    displayName: "南段西侧道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 910_269_741,
    displayName: "南段西侧园区环路（OSM未命名）",
    toleranceM: 3,
  },
  {
    osmWayId: 973_997_336,
    displayName: "坤顺路双向车道",
    toleranceM: 2,
    // The full OSM way continues far outside the round display's useful
    // corridor. Keep its two real carriageways nearest the route.
    sourceNodeStart: 5,
    sourceNodeEnd: 16,
  },
  {
    osmWayId: 1_271_695_039,
    displayName: "浪潮园区北侧道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 610_243_118,
    displayName: "浪潮园区北侧横向道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 610_243_120,
    displayName: "浪潮园区中部横向道路（OSM未命名）",
    toleranceM: 2,
  },
  {
    osmWayId: 609_637_823,
    displayName: "浪潮科技园内部道路",
    toleranceM: 2,
  },
];

function invariant(condition, message) {
  if (!condition) throw new Error(message);
}

function pointSegmentDistanceM(point, start, end) {
  const referenceLatitudeRad = (point[0] * Math.PI) / 180;
  const metresPerLatitudeDegree = 111_195;
  const metresPerLongitudeDegree =
    metresPerLatitudeDegree * Math.cos(referenceLatitudeRad);
  const project = ([latitude, longitude]) => [
    longitude * metresPerLongitudeDegree,
    latitude * metresPerLatitudeDegree,
  ];
  const [px, py] = project(point);
  const [ax, ay] = project(start);
  const [bx, by] = project(end);
  const dx = bx - ax;
  const dy = by - ay;
  const denominator = dx * dx + dy * dy;
  const ratio = denominator === 0
    ? 0
    : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / denominator));
  return Math.hypot(px - (ax + ratio * dx), py - (ay + ratio * dy));
}

function simplifySourceNodes(points, toleranceM) {
  if (points.length <= 2) return points;
  let furthestDistanceM = -1;
  let furthestIndex = 0;
  for (let index = 1; index < points.length - 1; ++index) {
    const distanceM = pointSegmentDistanceM(
      points[index],
      points[0],
      points.at(-1),
    );
    if (distanceM > furthestDistanceM) {
      furthestDistanceM = distanceM;
      furthestIndex = index;
    }
  }
  if (furthestDistanceM <= toleranceM) {
    return [points[0], points.at(-1)];
  }
  return [
    ...simplifySourceNodes(points.slice(0, furthestIndex + 1), toleranceM).slice(0, -1),
    ...simplifySourceNodes(points.slice(furthestIndex), toleranceM),
  ];
}

async function fetchWay(specification) {
  const response = await fetch(
    `${apiRoot}/way/${specification.osmWayId}/full.json`,
    {
      headers: {
        "User-Agent": "MOTO-GPS-prototype/0.1 (offline demo fixture updater)",
      },
    },
  );
  invariant(
    response.ok,
    `OSM way ${specification.osmWayId} returned HTTP ${response.status}`,
  );
  const payload = await response.json();
  const way = payload.elements.find(
    (element) => element.type === "way" && element.id === specification.osmWayId,
  );
  invariant(way, `OSM way ${specification.osmWayId} is missing from its API response`);
  invariant(way.tags?.highway, `OSM way ${specification.osmWayId} is no longer a highway`);
  const nodes = new Map(
    payload.elements
      .filter((element) => element.type === "node")
      .map((node) => [node.id, node]),
  );
  const fullPoints = way.nodes.map((nodeId) => {
    const node = nodes.get(nodeId);
    invariant(node, `OSM way ${specification.osmWayId} is missing node ${nodeId}`);
    return [node.lat, node.lon];
  });
  const selectedPoints = fullPoints.slice(
    specification.sourceNodeStart ?? 0,
    specification.sourceNodeEnd,
  );
  invariant(selectedPoints.length >= 2, `OSM way ${specification.osmWayId} is too short`);
  const simplified = simplifySourceNodes(
    selectedPoints,
    specification.toleranceM,
  );
  return {
    name: specification.displayName,
    osm_way_id: specification.osmWayId,
    osm_way_version: way.version,
    osm_way_timestamp: way.timestamp,
    highway: way.tags.highway,
    source_node_range: [
      specification.sourceNodeStart ?? 0,
      specification.sourceNodeEnd ?? fullPoints.length,
    ],
    source_point_count: selectedPoints.length,
    simplification_tolerance_m: specification.toleranceM,
    points_wgs84: simplified,
  };
}

const fixture = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
const roadContext = [];
for (const specification of selectedWays) {
  roadContext.push(await fetchWay(specification));
}
const pointCount = roadContext.reduce(
  (total, road) => total + road.points_wgs84.length,
  0,
);
invariant(
  roadContext.length <= maximumRoadPolylines,
  `selected ${roadContext.length} roads, capacity is ${maximumRoadPolylines}`,
);
invariant(
  pointCount <= maximumRoadPoints,
  `selected ${pointCount} points, capacity is ${maximumRoadPoints}`,
);

// The selected navigation route itself did not change, so keep its stable
// fixture/route IDs. Version the independently replaceable context layer.
fixture.road_context_revision = 3;
fixture.road_context_source = {
  provider: "OpenStreetMap",
  api: apiRoot,
  licence: "ODbL-1.0",
  attribution_url: "https://www.openstreetmap.org/copyright",
  refreshed_at: new Date().toISOString(),
  selection: "fixed real OSM ways around the D-building to Inspur demo corridor",
  simplification: "Ramer-Douglas-Peucker retaining original OSM nodes only",
};
fixture.road_context = roadContext;

fs.writeFileSync(fixturePath, `${JSON.stringify(fixture, null, 2)}\n`);
console.log(
  `updated ${path.relative(repositoryRoot, fixturePath)}: ` +
    `${roadContext.length} real OSM polylines, ${pointCount} retained source nodes`,
);
