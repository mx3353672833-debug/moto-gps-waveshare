import { createHash } from "node:crypto";

const MAX_POLYLINE_POINTS = 8192;
const MAX_MANEUVERS = 512;
const MAX_TRAFFIC_SEGMENTS = 2048;

export class AmapTransformError extends Error {
  constructor(code, message, { retryable = false } = {}) {
    super(message);
    this.name = "AmapTransformError";
    this.code = code;
    this.retryable = retryable;
  }
}

function finiteNumber(value, fallback = 0) {
  const number = Number(value);
  return Number.isFinite(number) ? number : fallback;
}

function nonNegativeNumber(value, fallback = 0) {
  return Math.max(0, finiteNumber(value, fallback));
}

function compactText(value, maximumLength) {
  if (Array.isArray(value)) {
    value = value.join("/");
  }
  return String(value ?? "").trim().slice(0, maximumLength);
}

function rounded(value, digits = 2) {
  return Number(value.toFixed(digits));
}

function parsePolyline(polyline) {
  if (typeof polyline !== "string" || polyline.trim() === "") {
    return [];
  }

  const points = [];
  for (const encodedPoint of polyline.split(";")) {
    const [longitudeText, latitudeText] = encodedPoint.trim().split(",");
    const longitudeDeg = Number(longitudeText);
    const latitudeDeg = Number(latitudeText);
    if (
      !Number.isFinite(longitudeDeg) ||
      !Number.isFinite(latitudeDeg) ||
      longitudeDeg < -180 ||
      longitudeDeg > 180 ||
      latitudeDeg < -90 ||
      latitudeDeg > 90
    ) {
      throw new AmapTransformError("INVALID_POLYLINE", "AMap returned an invalid polyline point");
    }
    points.push({
      longitude_deg: rounded(longitudeDeg, 7),
      latitude_deg: rounded(latitudeDeg, 7),
    });
  }
  return points;
}

function samePoint(left, right) {
  return (
    left?.longitude_deg === right?.longitude_deg &&
    left?.latitude_deg === right?.latitude_deg
  );
}

function appendPolyline(target, points) {
  for (const point of points) {
    if (!samePoint(target.at(-1), point)) {
      target.push(point);
    }
  }
}

export function mapAmapManeuver(action, assistantAction = "") {
  const primary = compactText(action, 96);
  const assistant = compactText(assistantAction, 96);
  const combined = `${primary} ${assistant}`;

  if (/到达|目的地/.test(assistant) || /到达目的地/.test(primary)) return "arrive";
  if (/驶出环岛|离开环岛|出环岛/.test(combined)) return "exit";
  if (/环岛/.test(combined)) return "roundabout";
  if (/右后方|右转掉头|向右掉头/.test(combined)) return "u_turn_right";
  if (/左后方|掉头/.test(combined)) return "u_turn_left";
  if (/右前方|稍向右|靠右/.test(combined)) return "slight_right";
  if (/左前方|稍向左|靠左/.test(combined)) return "slight_left";
  if (/急向右|大幅右转/.test(combined)) return "sharp_right";
  if (/急向左|大幅左转/.test(combined)) return "sharp_left";
  if (/右转/.test(combined)) return "right";
  if (/左转/.test(combined)) return "left";
  if (/直行|沿|前行|继续/.test(combined)) return "continue";
  return "unknown";
}

export function mapAmapTrafficStatus(status) {
  const normalized = compactText(status, 32).toLowerCase();
  if (normalized.includes("严重拥堵") || normalized === "blocked" || normalized === "severe") {
    return "severe";
  }
  if (normalized.includes("拥堵") || normalized === "congested") return "congested";
  if (normalized.includes("缓行") || normalized === "slow") return "slow";
  if (normalized.includes("畅通") || normalized === "smooth" || normalized === "free") {
    return "free_flow";
  }
  return "unknown";
}

function roundaboutExit(action, assistantAction) {
  const match = `${action ?? ""} ${assistantAction ?? ""}`.match(/第\s*(\d{1,2})\s*个?出口/);
  return match ? Math.min(32, Number(match[1])) : 0;
}

function appendTraffic(target, segment) {
  if (segment.end_offset_m <= segment.start_offset_m) return;
  const previous = target.at(-1);
  if (
    previous &&
    previous.level === segment.level &&
    Math.abs(previous.end_offset_m - segment.start_offset_m) < 0.01
  ) {
    previous.end_offset_m = segment.end_offset_m;
    return;
  }
  target.push(segment);
}

function routeIdentifier(path, polyline, totalDistanceM, totalDurationS) {
  const providerId = compactText(path.path_id ?? path.pathid ?? path.id, 80);
  if (providerId) return `amap-${providerId}`.slice(0, 96);

  const signature = JSON.stringify({
    totalDistanceM,
    totalDurationS,
    polyline,
  });
  return `amap-${createHash("sha256").update(signature).digest("hex").slice(0, 20)}`;
}

function assertAmapSuccess(payload) {
  if (!payload || typeof payload !== "object") {
    throw new AmapTransformError("INVALID_RESPONSE", "AMap returned a non-object response", {
      retryable: true,
    });
  }
  if (String(payload.status) !== "1") {
    const providerCode = compactText(payload.infocode, 24) || "UNKNOWN";
    throw new AmapTransformError(
      `AMAP_${providerCode}`,
      `AMap route planning failed (${providerCode})`,
      { retryable: providerCode.startsWith("10") === false },
    );
  }
}

/**
 * Converts an AMap Route Planning v2 driving response into protocol v1.
 * No AMap field is allowed to escape this function.
 */
export function transformAmapRouteV2(payload, { generatedAtMs = Date.now(), pathIndex = 0 } = {}) {
  assertAmapSuccess(payload);
  const paths = payload.route?.paths;
  if (!Array.isArray(paths) || !paths[pathIndex]) {
    throw new AmapTransformError("NO_ROUTE", "AMap returned no usable driving path");
  }

  const path = paths[pathIndex];
  const steps = Array.isArray(path.steps) ? path.steps : [];
  if (steps.length > MAX_MANEUVERS) {
    throw new AmapTransformError("ROUTE_TOO_LARGE", "AMap route contains too many maneuvers");
  }

  const polyline = [];
  const maneuvers = [];
  const traffic = [];
  let routeOffsetM = 0;

  for (const [index, step] of steps.entries()) {
    const stepPolyline = parsePolyline(step.polyline);
    appendPolyline(polyline, stepPolyline);

    const navi = step.navi && typeof step.navi === "object" ? step.navi : {};
    const action = navi.action ?? step.action ?? "";
    const assistantAction = navi.assistant_action ?? step.assistant_action ?? "";
    const stepDistanceM = nonNegativeNumber(step.step_distance ?? step.distance);

    // AMap describes a step as "travel this step, then perform action".
    // The maneuver therefore happens at the end of the step, not at its
    // beginning. Recording the start offset made a freshly planned route show
    // the first turn at 0 m and advanced every later instruction one road too
    // early on the device.
    maneuvers.push({
      id: index + 1,
      type: mapAmapManeuver(action, assistantAction),
      route_offset_m: rounded(routeOffsetM + stepDistanceM),
      road_name: compactText(step.road_name ?? step.road, 96),
      instruction: compactText(step.instruction, 256),
      roundabout_exit: roundaboutExit(action, assistantAction),
    });

    const tmcs = Array.isArray(step.tmcs) ? step.tmcs : [];
    let tmcOffsetM = routeOffsetM;
    for (const tmc of tmcs) {
      const segmentDistanceM = nonNegativeNumber(tmc.tmc_distance ?? tmc.distance);
      const endOffsetM = tmcOffsetM + segmentDistanceM;
      appendTraffic(traffic, {
        start_offset_m: rounded(tmcOffsetM),
        end_offset_m: rounded(endOffsetM),
        level: mapAmapTrafficStatus(tmc.tmc_status ?? tmc.status),
      });
      tmcOffsetM = endOffsetM;
    }

    routeOffsetM += stepDistanceM;
  }

  if (polyline.length < 2) {
    appendPolyline(polyline, parsePolyline(path.polyline));
  }
  if (polyline.length < 2) {
    throw new AmapTransformError("INVALID_POLYLINE", "AMap route has fewer than two points");
  }
  if (polyline.length > MAX_POLYLINE_POINTS || traffic.length > MAX_TRAFFIC_SEGMENTS) {
    throw new AmapTransformError("ROUTE_TOO_LARGE", "AMap route exceeds protocol v1 limits");
  }

  const totalDistanceM = nonNegativeNumber(path.distance, routeOffsetM) || routeOffsetM;
  const durationCandidate = path.cost?.duration ?? path.duration;
  const stepDurationS = steps.reduce(
    (sum, step) => sum + nonNegativeNumber(step.cost?.duration ?? step.duration),
    0,
  );
  const totalDurationS = Math.round(nonNegativeNumber(durationCandidate, stepDurationS));
  const integerGeneratedAtMs = Math.max(0, Math.trunc(finiteNumber(generatedAtMs)));

  return {
    schema_version: 1,
    route_id: routeIdentifier(path, polyline, totalDistanceM, totalDurationS),
    provider: "amap",
    coordinate_system: "GCJ-02",
    generated_at_ms: integerGeneratedAtMs,
    total_distance_m: rounded(totalDistanceM),
    total_duration_s: totalDurationS,
    polyline,
    maneuvers,
    traffic,
  };
}

/**
 * Converts every usable AMap candidate returned by the multi-route strategy.
 * The phone uses this envelope only for preview/selection; the selected
 * RouteBundle continues through the unchanged navigation protocol.
 */
export function transformAmapRouteOptionsV2(
  payload,
  { generatedAtMs = Date.now(), maximumRoutes = 3 } = {},
) {
  assertAmapSuccess(payload);
  const paths = payload.route?.paths;
  if (!Array.isArray(paths) || paths.length === 0) {
    throw new AmapTransformError("NO_ROUTE", "AMap returned no usable driving path");
  }

  const count = Math.min(Math.max(1, Math.trunc(maximumRoutes)), 3, paths.length);
  return Array.from({ length: count }, (_, pathIndex) =>
    transformAmapRouteV2(payload, { generatedAtMs, pathIndex }),
  );
}
