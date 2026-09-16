import { gcj02ToWgs84 } from "./coordinates.js";
import { readLimitedBody } from "./map-http.js";
import { fetchGetWithTransientRetry } from "./upstream-retry.js";

const DISTRICT_ENDPOINT = "https://restapi.amap.com/v3/config/district";
// AMap documents the four municipalities at province level, although users search them as cities.
const MUNICIPALITIES = new Set(["110000", "120000", "310000", "500000"]);

export class AmapCitiesError extends Error {
  constructor(code, message, retryable = true) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }
}

export function validateCityKeywords(value) {
  const keywords = typeof value === "string" ? value.trim() : "";
  if (keywords.length < 2 || keywords.length > 80) {
    throw new AmapCitiesError("INVALID_REQUEST", "keywords must contain 2 to 80 characters", false);
  }
  return keywords;
}

export function districtBoundsWgs84(polyline) {
  if (typeof polyline !== "string" || polyline.length > 8 * 1024 * 1024) return null;
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity, count = 0;
  for (const pair of polyline.split(/[;|]/)) {
    if (!pair.trim()) continue;
    if (++count > 250_000) throw new AmapCitiesError("CITY_BOUNDARY_TOO_LARGE", "district boundary exceeds the size limit");
    const parts = pair.split(",");
    if (parts.length !== 2 || parts.some((part) => part.trim() === "")) return null;
    const [longitude_deg, latitude_deg] = parts.map(Number);
    if (!Number.isFinite(longitude_deg) || !Number.isFinite(latitude_deg) ||
        Math.abs(longitude_deg) > 180 || Math.abs(latitude_deg) > 90) return null;
    const point = gcj02ToWgs84({ longitude_deg, latitude_deg });
    west = Math.min(west, point.longitude_deg); east = Math.max(east, point.longitude_deg);
    south = Math.min(south, point.latitude_deg); north = Math.max(north, point.latitude_deg);
  }
  return count >= 3 && west < east && south < north ? [west, south, east, north] : null;
}

export function transformAmapCities(payload) {
  if (!payload || String(payload.status) !== "1") {
    const code = /^\d{5}$/.test(String(payload?.infocode)) ? String(payload.infocode) : "UNKNOWN";
    throw new AmapCitiesError(`AMAP_${code}`, `AMap city search failed (${code})`, !code.startsWith("10"));
  }
  if (!Array.isArray(payload.districts)) throw new AmapCitiesError("INVALID_RESPONSE", "AMap city results are invalid");
  const cities = [], seen = new Set();
  let missingBoundary = false;
  for (const district of payload.districts.slice(0, 20)) {
    const id = String(district.adcode ?? "");
    const municipality = district?.level === "province" && MUNICIPALITIES.has(id);
    if (!["city", "district"].includes(district?.level) && !municipality) continue;
    const name = typeof district.name === "string" ? district.name.trim().slice(0, 80) : "";
    if (!/^\d{6}$/.test(id) || !name || seen.has(id)) continue;
    const bounds = districtBoundsWgs84(district.polyline);
    if (!bounds) { missingBoundary = true; continue; }
    cities.push({ id, name, detail: district.level === "district" ? "区县范围" : "全市范围", bounds_wgs84: bounds });
    seen.add(id);
  }
  if (!cities.length && missingBoundary) {
    throw new AmapCitiesError("CITY_BOUNDARY_UNAVAILABLE", "city results have no usable district boundary");
  }
  return { cities };
}

export function createAmapCitiesProvider({ key, fetchImpl = globalThis.fetch, timeoutMs = 8000,
  maxAttempts = 3, retryDelayMs = 100 } = {}) {
  return {
    async searchCities({ keywords } = {}) {
      keywords = validateCityKeywords(keywords);
      if (typeof key !== "string" || !key.trim()) {
        throw new AmapCitiesError("SERVER_MISCONFIGURED", "AMap server key is not configured", false);
      }
      const url = new URL(DISTRICT_ENDPOINT);
      for (const [name, value] of Object.entries({ key, keywords, subdistrict: "0", extensions: "all",
        page: "1", offset: "20", output: "JSON" })) url.searchParams.set(name, value);
      const signal = AbortSignal.timeout(timeoutMs);
      try {
        const response = await fetchGetWithTransientRetry(fetchImpl, url, {
          headers: { Accept: "application/json" }, signal, maxAttempts, retryDelayMs,
        });
        if (!response.ok) {
          await response.body?.cancel();
          throw new AmapCitiesError("PROVIDER_UNAVAILABLE", "AMap district search is unavailable");
        }
        let payload;
        try { payload = JSON.parse((await readLimitedBody(response, 12 * 1024 * 1024)).toString("utf8")); }
        catch { throw new AmapCitiesError("INVALID_RESPONSE", "AMap district response is invalid or too large"); }
        return transformAmapCities(payload);
      } catch (error) {
        if (signal.aborted) throw new AmapCitiesError("PROVIDER_TIMEOUT", "AMap district search timed out");
        if (error instanceof AmapCitiesError) throw error;
        throw new AmapCitiesError("PROVIDER_UNAVAILABLE", "AMap district search is unavailable");
      }
    },
  };
}
