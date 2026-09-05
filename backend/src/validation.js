const REQUEST_KEYS = new Set([
  "protocol_version",
  "request_id",
  "route_mode",
  "origin",
  "destination",
  "is_reroute",
  "previous_route_id",
  "destination_poi_id",
]);
const POINT_KEYS = new Set(["coordinate_system", "longitude_deg", "latitude_deg"]);

export class ProtocolValidationError extends Error {
  constructor(message) {
    super(message);
    this.name = "ProtocolValidationError";
    this.code = "INVALID_REQUEST";
    this.retryable = false;
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function rejectUnknownKeys(value, allowedKeys, label) {
  for (const key of Object.keys(value)) {
    if (!allowedKeys.has(key)) {
      throw new ProtocolValidationError(`${label} contains unsupported field: ${key}`);
    }
  }
}

function validateWgs84Point(value, label) {
  if (!isPlainObject(value)) {
    throw new ProtocolValidationError(`${label} must be an object`);
  }
  rejectUnknownKeys(value, POINT_KEYS, label);
  if (value.coordinate_system !== "WGS84") {
    throw new ProtocolValidationError(`${label}.coordinate_system must be WGS84`);
  }
  if (
    !Number.isFinite(value.longitude_deg) ||
    value.longitude_deg < -180 ||
    value.longitude_deg > 180
  ) {
    throw new ProtocolValidationError(`${label}.longitude_deg is out of range`);
  }
  if (
    !Number.isFinite(value.latitude_deg) ||
    value.latitude_deg < -90 ||
    value.latitude_deg > 90
  ) {
    throw new ProtocolValidationError(`${label}.latitude_deg is out of range`);
  }
}

export function validRequestId(value) {
  return Number.isInteger(value) && value >= 1 && value <= 0xffffffff;
}

export function validateRouteRequest(value) {
  if (!isPlainObject(value)) {
    throw new ProtocolValidationError("request body must be an object");
  }
  rejectUnknownKeys(value, REQUEST_KEYS, "request");
  if (value.protocol_version !== 1) {
    throw new ProtocolValidationError("protocol_version must be 1");
  }
  if (!validRequestId(value.request_id)) {
    throw new ProtocolValidationError("request_id must be a non-zero uint32");
  }
  if (value.route_mode !== "driving") {
    throw new ProtocolValidationError("route_mode must be driving");
  }
  if (typeof value.is_reroute !== "boolean") {
    throw new ProtocolValidationError("is_reroute must be a boolean");
  }
  if (value.previous_route_id !== undefined) {
    if (
      typeof value.previous_route_id !== "string" ||
      value.previous_route_id.length < 1 ||
      value.previous_route_id.length > 96
    ) {
      throw new ProtocolValidationError("previous_route_id must contain 1 to 96 characters");
    }
  }
  if (value.destination_poi_id !== undefined) {
    if (
      typeof value.destination_poi_id !== "string" ||
      value.destination_poi_id.length < 1 ||
      value.destination_poi_id.length > 64
    ) {
      throw new ProtocolValidationError("destination_poi_id must contain 1 to 64 characters");
    }
  }
  validateWgs84Point(value.origin, "origin");
  validateWgs84Point(value.destination, "destination");
  return value;
}
