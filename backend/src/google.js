import { createHash } from "node:crypto";
import { wgs84ToGcj02 } from "./coordinates.js";

const MAX_RESULTS = 12;
const NEARBY_RADIUS_M = 50_000.0;

export class GoogleProviderError extends Error {
  constructor(code, message, { retryable = true } = {}) {
    super(message);
    this.name = "GoogleProviderError";
    this.code = code;
    this.retryable = retryable;
  }
}

function rounded(value, digits = 2) {
  return Number(value.toFixed(digits));
}

function compactText(value, maximumLength) {
  return String(value ?? "").trim().slice(0, maximumLength);
}

function distanceMetres(left, right) {
  const toRadians = (degrees) => (degrees * Math.PI) / 180;
  const lat1 = toRadians(left.latitude_deg);
  const lat2 = toRadians(right.latitude_deg);
  const dLat = lat2 - lat1;
  const dLng = toRadians(right.longitude_deg - left.longitude_deg);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) ** 2;
  return Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
}

export function decodePolyline(encoded) {
  const points = [];
  let index = 0, lat = 0, lng = 0;

  while (index < encoded.length) {
    let shift = 0, result = 0, byte;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lat += (result & 1) ? ~(result >> 1) : (result >> 1);

    shift = 0;
    result = 0;
    do {
      byte = encoded.charCodeAt(index++) - 63;
      result |= (byte & 0x1f) << shift;
      shift += 5;
    } while (byte >= 0x20);
    lng += (result & 1) ? ~(result >> 1) : (result >> 1);

    const gcj = wgs84ToGcj02({
      longitude_deg: lng / 1e5,
      latitude_deg: lat / 1e5,
    });
    points.push({
      longitude_deg: rounded(gcj.longitude_deg, 7),
      latitude_deg: rounded(gcj.latitude_deg, 7),
    });
  }
  return points;
}

function mapGoogleManeuver(maneuver) {
  switch (String(maneuver ?? "").toUpperCase()) {
    case "TURN_LEFT":
      return "left";
    case "TURN_RIGHT":
      return "right";
    case "TURN_SLIGHT_LEFT":
    case "FORK_LEFT":
    case "RAMP_LEFT":
      return "slight_left";
    case "TURN_SLIGHT_RIGHT":
    case "FORK_RIGHT":
    case "RAMP_RIGHT":
      return "slight_right";
    case "TURN_SHARP_LEFT":
      return "sharp_left";
    case "TURN_SHARP_RIGHT":
      return "sharp_right";
    case "UTURN_LEFT":
      return "u_turn_left";
    case "UTURN_RIGHT":
      return "u_turn_right";
    case "ROUNDABOUT_LEFT":
    case "ROUNDABOUT_RIGHT":
      return "roundabout";
    case "DEPART":
    case "STRAIGHT":
    case "MERGE":
    case "NAME_CHANGE":
      return "continue";
    default:
      return "unknown";
  }
}

function parseDurationSeconds(durationStr) {
  const num = parseInt(String(durationStr ?? "0s").replace("s", ""), 10);
  return Number.isFinite(num) ? Math.max(0, num) : 0;
}

function routeIdentifier(polyline, totalDistanceM, totalDurationS) {
  const signature = JSON.stringify({ totalDistanceM, totalDurationS, polyline });
  return `google-${createHash("sha256").update(signature).digest("hex").slice(0, 20)}`;
}

function transformGoogleRoute(route, generatedAtMs) {
  const leg = route.legs?.[0] ?? {};
  const steps = Array.isArray(leg.steps) ? leg.steps : [];
  const polyline = decodePolyline(route.polyline?.encodedPolyline ?? "");

  if (polyline.length < 2) {
    throw new GoogleProviderError("INVALID_POLYLINE", "Google route has fewer than two points");
  }

  const maneuvers = [];
  let routeOffsetM = 0;

  for (const [index, step] of steps.entries()) {
    const stepDistanceM = Math.max(0, Number(step.distanceMeters ?? 0));
    const nav = step.navigationInstruction ?? {};
    const isLast = index === steps.length - 1;
    const type = isLast && !nav.maneuver ? "arrive" : mapGoogleManeuver(nav.maneuver);

    maneuvers.push({
      id: index + 1,
      type,
      route_offset_m: rounded(routeOffsetM + stepDistanceM),
      road_name: "",
      instruction: compactText(nav.instructions, 256),
      roundabout_exit: 0,
    });
    routeOffsetM += stepDistanceM;
  }

  const totalDistanceM = Math.max(0, Number(route.distanceMeters ?? routeOffsetM)) || routeOffsetM;
  const totalDurationS = parseDurationSeconds(route.duration);

  return {
    schema_version: 1,
    route_id: routeIdentifier(polyline, totalDistanceM, totalDurationS),
    provider: "google",
    coordinate_system: "GCJ-02",
    generated_at_ms: Math.max(0, Math.trunc(generatedAtMs)),
    total_distance_m: rounded(totalDistanceM),
    total_duration_s: totalDurationS,
    polyline,
    maneuvers,
    traffic: [],
  };
}

export function createGoogleProvider({
  key,
  fetchImpl = globalThis.fetch,
  timeoutMs = 8000,
  clock = Date.now,
} = {}) {
  if (typeof key !== "string" || key.trim() === "") {
    throw new GoogleProviderError("SERVER_MISCONFIGURED", "Google Maps API key is not configured", {
      retryable: false,
    });
  }

  async function requestRoutes(request, { signal } = {}) {
    const deadline = AbortSignal.timeout(timeoutMs);
    const requestSignal = signal ? AbortSignal.any([signal, deadline]) : deadline;

    let response;
    try {
      response = await fetchImpl("https://routes.googleapis.com/directions/v2:computeRoutes", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Goog-Api-Key": key,
          "X-Goog-FieldMask": [
            "routes.distanceMeters",
            "routes.duration",
            "routes.polyline.encodedPolyline",
            "routes.legs.steps.distanceMeters",
            "routes.legs.steps.staticDuration",
            "routes.legs.steps.navigationInstruction",
          ].join(","),
        },
        body: JSON.stringify({
          origin: {
            location: {
              latLng: {
                latitude: request.origin.latitude_deg,
                longitude: request.origin.longitude_deg,
              },
            },
          },
          destination: {
            location: {
              latLng: {
                latitude: request.destination.latitude_deg,
                longitude: request.destination.longitude_deg,
              },
            },
          },
          travelMode: "TWO_WHEELER",
          routingPreference: "TRAFFIC_AWARE",
          computeAlternativeRoutes: true,
        }),
        signal: requestSignal,
      });
    } catch {
      throw new GoogleProviderError(
        deadline.aborted ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE",
        "Google Routes service is unavailable",
      );
    }

    if (!response.ok) {
      throw new GoogleProviderError(
        "PROVIDER_HTTP_ERROR",
        `Google Routes returned HTTP ${response.status}`,
        { retryable: response.status >= 500 || response.status === 429 },
      );
    }

    const payload = await response.json();
    const routes = Array.isArray(payload.routes) ? payload.routes : [];
    if (routes.length === 0) {
      throw new GoogleProviderError("NO_ROUTE", "Google returned no usable motorcycle route");
    }
    return routes;
  }

  return {
    async planRoute(request, options = {}) {
      const routes = await requestRoutes(request, options);
      return transformGoogleRoute(routes[0], clock());
    },

    async planRouteOptions(request, options = {}) {
      const routes = await requestRoutes(request, options);
      const now = clock();
      return routes.slice(0, 3).map((r) => transformGoogleRoute(r, now));
    },

    async searchPlaces(query, { signal } = {}) {
      const keywords = compactText(query?.keywords, 80);
      if (keywords.length < 2) {
        throw new GoogleProviderError("INVALID_REQUEST", "keywords must contain at least 2 characters", {
          retryable: false,
        });
      }

      const body = { textQuery: keywords, maxResultCount: MAX_RESULTS };
      if (query?.origin) {
        body.locationBias = {
          circle: {
            center: {
              latitude: query.origin.latitude_deg,
              longitude: query.origin.longitude_deg,
            },
            radius: NEARBY_RADIUS_M,
          },
        };
      }

      const deadline = AbortSignal.timeout(timeoutMs);
      const requestSignal = signal ? AbortSignal.any([signal, deadline]) : deadline;

      let response;
      try {
        response = await fetchImpl("https://places.googleapis.com/v1/places:searchText", {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Goog-Api-Key": key,
            "X-Goog-FieldMask": "places.id,places.displayName,places.formattedAddress,places.location",
          },
          body: JSON.stringify(body),
          signal: requestSignal,
        });
      } catch {
        throw new GoogleProviderError(
          deadline.aborted ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE",
          "Google Places service is unavailable",
        );
      }

      if (!response.ok) {
        throw new GoogleProviderError(
          "PROVIDER_HTTP_ERROR",
          `Google Places returned HTTP ${response.status}`,
          { retryable: response.status >= 500 || response.status === 429 },
        );
      }

      const data = await response.json();
      const places = [];
      for (const p of Array.isArray(data.places) ? data.places : []) {
        const lat = Number(p.location?.latitude);
        const lng = Number(p.location?.longitude);
        if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
        const point = {
          coordinate_system: "WGS84",
          longitude_deg: rounded(lng, 7),
          latitude_deg: rounded(lat, 7),
        };
        const place = {
          id: compactText(p.id, 64),
          name: compactText(p.displayName?.text, 96),
          address: compactText(p.formattedAddress, 160),
          city: "",
          district: "",
          display_area: compactText(p.formattedAddress, 64),
          location: point,
        };
        if (query?.origin) {
          place.distance_m = distanceMetres(query.origin, point);
        }
        places.push(place);
      }

      return {
        protocol_version: 1,
        query: keywords,
        places,
      };
    },
  };
}