import { gcj02ToWgs84, wgs84ToGcj02 } from "./coordinates.js";
import {
  fetchGetWithTransientRetry,
  isTransientUpstreamStatus,
} from "./upstream-retry.js";

const DEFAULT_TEXT_ENDPOINT = "https://restapi.amap.com/v5/place/text";
const DEFAULT_AROUND_ENDPOINT = "https://restapi.amap.com/v5/place/around";
const MAX_RESULTS = 12;
const NEARBY_RADIUS_M = 50_000;

export class AmapPlacesError extends Error {
  constructor(code, message, { retryable = true } = {}) {
    super(message);
    this.name = "AmapPlacesError";
    this.code = code;
    this.retryable = retryable;
  }
}

function compactText(value, maximumLength) {
  if (Array.isArray(value)) value = value.join(" / ");
  return String(value ?? "").trim().slice(0, maximumLength);
}

function parseGcj02Location(value) {
  const [longitudeText, latitudeText] = String(value ?? "").split(",");
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
    return null;
  }
  return { longitude_deg: longitudeDeg, latitude_deg: latitudeDeg };
}

function parseWgs84Origin(value) {
  if (value === undefined) return null;
  const longitudeDeg = Number(value?.longitude_deg);
  const latitudeDeg = Number(value?.latitude_deg);
  if (
    value?.coordinate_system !== "WGS84" ||
    !Number.isFinite(longitudeDeg) ||
    longitudeDeg < -180 ||
    longitudeDeg > 180 ||
    !Number.isFinite(latitudeDeg) ||
    latitudeDeg < -90 ||
    latitudeDeg > 90
  ) {
    throw new AmapPlacesError("INVALID_REQUEST", "origin must be a valid WGS84 point", {
      retryable: false,
    });
  }
  return { longitude_deg: longitudeDeg, latitude_deg: latitudeDeg };
}

function distanceMetres(left, right) {
  const toRadians = (degrees) => (degrees * Math.PI) / 180;
  const latitude1 = toRadians(left.latitude_deg);
  const latitude2 = toRadians(right.latitude_deg);
  const latitudeDelta = latitude2 - latitude1;
  const longitudeDelta = toRadians(right.longitude_deg - left.longitude_deg);
  const halfLatitude = Math.sin(latitudeDelta / 2);
  const halfLongitude = Math.sin(longitudeDelta / 2);
  const haversine =
    halfLatitude * halfLatitude +
    Math.cos(latitude1) * Math.cos(latitude2) * halfLongitude * halfLongitude;
  return Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine)));
}

function assertAmapSuccess(payload) {
  if (!payload || typeof payload !== "object") {
    throw new AmapPlacesError("INVALID_RESPONSE", "AMap returned a non-object response");
  }
  if (String(payload.status) !== "1") {
    const providerCode = compactText(payload.infocode, 24) || "UNKNOWN";
    throw new AmapPlacesError(
      `AMAP_${providerCode}`,
      `AMap place search failed (${providerCode})`,
      { retryable: providerCode.startsWith("10") === false },
    );
  }
}

export function buildAmapPlacesUrl(
  query,
  {
    key,
    endpoint = DEFAULT_TEXT_ENDPOINT,
    aroundEndpoint = DEFAULT_AROUND_ENDPOINT,
  } = {},
) {
  if (typeof key !== "string" || key.trim() === "") {
    throw new AmapPlacesError("SERVER_MISCONFIGURED", "AMap server key is not configured", {
      retryable: false,
    });
  }
  const keywords = compactText(query?.keywords, 80);
  if (keywords.length < 2) {
    throw new AmapPlacesError("INVALID_REQUEST", "keywords must contain at least 2 characters", {
      retryable: false,
    });
  }

  const origin = parseWgs84Origin(query?.origin);
  const url = new URL(origin ? aroundEndpoint : endpoint);
  if (url.protocol !== "https:") {
    throw new AmapPlacesError("SERVER_MISCONFIGURED", "AMap endpoint must use HTTPS", {
      retryable: false,
    });
  }
  url.searchParams.set("key", key);
  url.searchParams.set("keywords", keywords);
  url.searchParams.set("page_size", String(MAX_RESULTS));
  url.searchParams.set("page_num", "1");
  url.searchParams.set("show_fields", "navi");
  if (origin) {
    const gcj02Origin = wgs84ToGcj02(origin);
    url.searchParams.set(
      "location",
      `${gcj02Origin.longitude_deg.toFixed(6)},${gcj02Origin.latitude_deg.toFixed(6)}`,
    );
    url.searchParams.set("radius", String(NEARBY_RADIUS_M));
    // AMap's weight mode combines keyword relevance with proximity. Pure
    // distance order is wrong for navigation searches such as "奥体中心".
    url.searchParams.set("sortrule", "weight");
  }
  const region = compactText(query?.region, 32);
  if (region) url.searchParams.set("region", region);
  return url;
}

export function transformAmapPlaces(payload, query) {
  assertAmapSuccess(payload);
  const origin = parseWgs84Origin(query?.origin);
  const places = [];
  for (const poi of Array.isArray(payload.pois) ? payload.pois : []) {
    if (!poi || typeof poi !== "object") continue;
    const navigationLocation =
      parseGcj02Location(poi.navi?.entr_location) ?? parseGcj02Location(poi.location);
    if (!navigationLocation) continue;
    const point = gcj02ToWgs84(navigationLocation);
    const city = compactText(poi.cityname, 32);
    const district = compactText(poi.adname, 32);
    const place = {
      id: compactText(poi.id, 64),
      name: compactText(poi.name, 96),
      address: compactText(poi.address, 160),
      city,
      district,
      display_area: [city, district].filter(Boolean).join(" · "),
      location: point,
    };
    if (origin) place.distance_m = distanceMetres(origin, point);
    places.push(place);
    if (places.length >= MAX_RESULTS) break;
  }

  return {
    protocol_version: 1,
    query: compactText(query?.keywords, 80),
    places,
  };
}

export function createAmapPlacesProvider({
  key,
  fetchImpl = globalThis.fetch,
  endpoint = DEFAULT_TEXT_ENDPOINT,
  aroundEndpoint = DEFAULT_AROUND_ENDPOINT,
  timeoutMs = 6000,
  maxAttempts = 3,
  retryDelayMs = 100,
} = {}) {
  if (typeof fetchImpl !== "function") {
    throw new TypeError("fetchImpl must be a function");
  }

  return {
    async searchPlaces(query, { signal } = {}) {
      const deadlineSignal = AbortSignal.timeout(timeoutMs);
      const signals = [deadlineSignal];
      if (signal) signals.push(signal);
      const requestSignal = AbortSignal.any(signals);

      const fetchPayload = async (url) => {
        let response;
        try {
          response = await fetchGetWithTransientRetry(fetchImpl, url, {
            headers: { Accept: "application/json" },
            signal: requestSignal,
            maxAttempts,
            retryDelayMs,
          });
        } catch (error) {
          const code =
            deadlineSignal.aborted || error?.name === "TimeoutError"
              ? "PROVIDER_TIMEOUT"
              : "PROVIDER_UNAVAILABLE";
          throw new AmapPlacesError(code, "AMap place search is unavailable");
        }
        if (!response.ok) {
          throw new AmapPlacesError(
            "PROVIDER_HTTP_ERROR",
            `AMap returned HTTP ${response.status}`,
            {
              retryable:
                isTransientUpstreamStatus(response.status) || response.status === 429,
            },
          );
        }
        try {
          return await response.json();
        } catch {
          throw new AmapPlacesError("PROVIDER_INVALID_JSON", "AMap returned invalid JSON");
        }
      };

      const nearbyUrl = buildAmapPlacesUrl(query, { key, endpoint, aroundEndpoint });
      const nearbyPayload = await fetchPayload(nearbyUrl);
      const nearbyResult = transformAmapPlaces(nearbyPayload, query);
      if (!query?.origin || nearbyResult.places.length > 0) return nearbyResult;

      // Around search is intentionally bounded to the rider's local area. If
      // it finds nothing, retain normal navigation behaviour by falling back
      // to the original nationwide/region-biased text search.
      const textUrl = buildAmapPlacesUrl({ ...query, origin: undefined }, { key, endpoint });
      const textPayload = await fetchPayload(textUrl);
      return transformAmapPlaces(textPayload, query);
    },
  };
}
