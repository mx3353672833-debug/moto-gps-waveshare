import { createHash } from "node:crypto";
import { gunzip } from "node:zlib";
import { promisify } from "node:util";
import { readFile, rename, writeFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { Compression, EtagMismatch, PMTiles, SharedPromiseCache, TileType } from "pmtiles";
import { VectorTile } from "@mapbox/vector-tile";
import { PbfReader } from "pbf";

import { wgs84ToGcj02 } from "./coordinates.js";
import { MapDiskCache, MAXIMUM_TILE_BYTES } from "./map-cache.js";
import { createSemaphore, readLimitedBody } from "./map-http.js";

export const MAP_BUILD_MANIFEST = "https://build-metadata.protomaps.dev/builds.json";
const DAY_MS = 86_400_000;
const MAXIMUM_RANGE_BYTES = 8 * 1024 * 1024;
const unzip = promisify(gunzip);
const MOTOR_ROADS = new Set([
  "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
  "secondary", "secondary_link", "tertiary", "tertiary_link", "residential",
  "living_street", "service", "unclassified", "road", "track",
]);

export class MapTileError extends Error {
  constructor(code, message, retryable = true) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }
}

export function validateTileCoordinates(z, x, y) {
  if (z !== 15 || !Number.isSafeInteger(x) || !Number.isSafeInteger(y) ||
      x < 0 || y < 0 || x >= 2 ** z || y >= 2 ** z) {
    throw new MapTileError("INVALID_REQUEST", "map tiles require z=15 and x/y in 0..32767", false);
  }
}

export function tileBoundsWgs84(z, x, y) {
  validateTileCoordinates(z, x, y);
  const latitude = (row) => Math.atan(Math.sinh(Math.PI * (1 - 2 * row / 2 ** z))) * 180 / Math.PI;
  return [x / 2 ** z * 360 - 180, latitude(y + 1), (x + 1) / 2 ** z * 360 - 180, latitude(y)];
}

function roadClass(detail) {
  if (detail.startsWith("motorway")) return "motorway";
  if (/^(primary|trunk)/.test(detail)) return "primary";
  if (/^(secondary|tertiary)/.test(detail)) return "secondary";
  if (["residential", "living_street"].includes(detail)) return "residential";
  return detail === "service" ? "service" : "other";
}

export function transformVectorTile(data, { z, x, y, revision, retrievedAt = new Date().toISOString() }) {
  validateTileCoordinates(z, x, y);
  const result = {
    schema_version: 1, coordinate_system: "GCJ-02", tile: { z, x, y }, roads: [], buildings: [],
    source: { provider: "protomaps-openstreetmap", licence: "ODbL-1.0",
      attribution_url: "https://www.openstreetmap.org/copyright", retrieved_at: retrievedAt,
      source_revision: revision, bbox_wgs84: tileBoundsWgs84(z, x, y) },
  };
  if (!data) return result;
  if (data.byteLength > MAXIMUM_TILE_BYTES) throw new MapTileError("MAP_TILE_TOO_LARGE", "map tile exceeds decode limit");
  let pointCount = 0;
  function points(coordinates, polygon = false) {
    const output = [];
    for (const coordinate of coordinates) {
      if (++pointCount > 120_000) throw new MapTileError("MAP_TILE_TOO_LARGE", "map tile has too many vertices");
      const [longitude_deg, latitude_deg] = coordinate;
      if (!Number.isFinite(longitude_deg) || !Number.isFinite(latitude_deg) ||
          Math.abs(longitude_deg) > 180 || Math.abs(latitude_deg) > 90) continue;
      const point = wgs84ToGcj02({ longitude_deg, latitude_deg });
      const value = [Math.round(point.latitude_deg * 1e6), Math.round(point.longitude_deg * 1e6)];
      const previous = output.at(-1);
      if (!previous || previous[0] !== value[0] || previous[1] !== value[1]) output.push(value);
    }
    if (polygon && output.length > 1 && output[0][0] === output.at(-1)[0] && output[0][1] === output.at(-1)[1]) output.pop();
    if (output.length > 2048) throw new MapTileError("MAP_TILE_TOO_LARGE", "map feature has too many vertices");
    return output;
  }
  try {
    const tile = new VectorTile(new PbfReader(data));
    for (const layerName of ["roads", "buildings"]) {
      const layer = tile.layers[layerName];
      if (!layer) continue;
      if (layer.length > 50_000) throw new MapTileError("MAP_TILE_TOO_LARGE", "map tile has too many source features");
      for (let i = 0; i < layer.length; i += 1) {
        const feature = layer.feature(i);
        const properties = feature.properties;
        if (layerName === "roads") {
          const detail = String(properties.kind_detail ?? "");
          if (feature.type !== 2 || !MOTOR_ROADS.has(detail)) continue;
          const geometry = feature.toGeoJSON(x, y, z).geometry;
          const lines = geometry.type === "LineString" ? [geometry.coordinates] : geometry.coordinates;
          for (const line of lines) {
            const vertices = points(line);
            if (vertices.length >= 2) result.roads.push({ class: roadClass(detail), points_e6: vertices });
          }
        } else {
          // Ignore address points and elevated building parts: footprints use each polygon's outer ring.
          if (feature.type !== 3 || properties.kind !== "building") continue;
          const geometry = feature.toGeoJSON(x, y, z).geometry;
          const polygons = geometry.type === "Polygon" ? [geometry.coordinates] : geometry.coordinates;
          for (const polygon of polygons) {
            const vertices = points(polygon[0] ?? [], true);
            if (new Set(vertices.map((v) => v.join(","))).size < 3) continue;
            const building = { class: "generic", points_e6: vertices };
            if (typeof properties.name === "string" && properties.name.trim()) building.name = properties.name.trim().slice(0, 120);
            // Protomaps feature.id encodes source/type; it is not an OSM way ID.
            result.buildings.push(building);
          }
        }
      }
    }
  } catch (error) {
    if (error instanceof MapTileError) throw error;
    throw new MapTileError("MAP_INVALID_TILE", "map source returned an invalid vector tile");
  }
  if (result.roads.length + result.buildings.length > 12_000) {
    throw new MapTileError("MAP_TILE_TOO_LARGE", "map tile has too many features");
  }
  if (Buffer.byteLength(JSON.stringify(result)) > MAXIMUM_TILE_BYTES) {
    throw new MapTileError("MAP_TILE_TOO_LARGE", "map tile JSON exceeds the size limit");
  }
  return result;
}

async function boundedDecompress(data, compression) {
  if (data.byteLength > MAXIMUM_RANGE_BYTES) throw new MapTileError("MAP_TILE_TOO_LARGE", "compressed map block exceeds limit");
  if (compression === Compression.None) return data;
  if (compression !== Compression.Gzip) throw new MapTileError("MAP_INVALID_SOURCE", "unsupported map source compression");
  const buffer = await unzip(Buffer.from(data), { maxOutputLength: MAXIMUM_TILE_BYTES });
  return buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
}

function validatedSourceUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new TypeError("map source must be an HTTPS PMTiles URL"); }
  if (url.protocol !== "https:" || url.username || url.password || url.hash || !url.pathname.endsWith(".pmtiles")) {
    throw new TypeError("map source must be an HTTPS PMTiles URL without credentials or fragment");
  }
  return url.href;
}

export function createRangeSource(url, { fetchImpl = globalThis.fetch, timeoutMs = 10_000,
  withSlot = createSemaphore(4) } = {}) {
  const sourceUrl = validatedSourceUrl(url);
  return {
    getKey: () => sourceUrl,
    async getBytes(offset, length, signal, etag) {
      if (!Number.isSafeInteger(offset) || offset < 0 || !Number.isSafeInteger(length) ||
          length <= 0 || length > MAXIMUM_RANGE_BYTES || !Number.isSafeInteger(offset + length)) {
        throw new MapTileError("MAP_INVALID_SOURCE", "map source requested an invalid byte range");
      }
      return withSlot(async () => {
        const deadline = AbortSignal.timeout(timeoutMs);
        const requestSignal = signal ? AbortSignal.any([signal, deadline]) : deadline;
        try {
          const response = await fetchImpl(sourceUrl, {
            headers: { Range: `bytes=${offset}-${offset + length - 1}`, "Accept-Encoding": "identity" },
            signal: requestSignal, redirect: "error",
          });
          if (response.status !== 206) {
            await response.body?.cancel();
            throw new MapTileError(response.status === 404 ? "MAP_SOURCE_NOT_FOUND" : "MAP_UPSTREAM_UNAVAILABLE",
              "map source did not return a byte range");
          }
          const match = /^bytes (\d+)-(\d+)\/(\d+)$/.exec(response.headers.get("content-range") ?? "");
          const actualEnd = match ? Number(match[2]) : -1;
          const total = match ? Number(match[3]) : -1;
          // PMTiles probes the first 16 KiB, including for a smaller regional archive.
          const initialEof = offset === 0 && total > 0 && total < length && actualEnd === total - 1;
          if (!match || Number(match[1]) !== offset || (!initialEof && actualEnd !== offset + length - 1) ||
              total <= actualEnd || actualEnd < offset) {
            await response.body?.cancel();
            throw new MapTileError("MAP_INVALID_SOURCE", "map source returned an incorrect byte range");
          }
          const actualEtag = response.headers.get("etag") ?? undefined;
          if (etag && actualEtag && etag !== actualEtag) {
            await response.body?.cancel();
            throw new EtagMismatch("map source changed during range retrieval");
          }
          const buffer = await readLimitedBody(response, length);
          if (buffer.length !== actualEnd - offset + 1) throw new MapTileError("MAP_INVALID_SOURCE", "map source returned a truncated byte range");
          return { data: buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength), etag: actualEtag };
        } catch (error) {
          if (error instanceof MapTileError || error instanceof EtagMismatch) throw error;
          throw new MapTileError(deadline.aborted ? "MAP_UPSTREAM_TIMEOUT" : "MAP_UPSTREAM_UNAVAILABLE", "map byte-range request failed");
        }
      });
    },
  };
}

export function selectLatestBuild(manifest) {
  if (!Array.isArray(manifest)) throw new MapTileError("MAP_INVALID_MANIFEST", "map build list is invalid");
  // Official manifest lists completed uploads, not in-progress builds. Never use a URL from its data.
  const builds = manifest.filter((item) => /^\d{8}\.pmtiles$/.test(item?.key ?? "") &&
    Number.isSafeInteger(item.size) && item.size > 0 && Number.isFinite(Date.parse(item.uploaded)) &&
    /^4\.\d+\.\d+$/.test(item.version ?? ""));
  builds.sort((a, b) => b.key.localeCompare(a.key));
  const latest = builds[0];
  if (!latest) throw new MapTileError("MAP_INVALID_MANIFEST", "map build list has no completed source");
  return { url: `https://build.protomaps.com/${latest.key}`, revision: latest.key.slice(0, 8),
    schema_version: latest.version, uploaded_at: latest.uploaded };
}

export function createMapTileProvider({ url = "auto", cacheDirectory = resolve(".cache/map-tiles"),
  maximumCacheBytes = 1024 ** 3, fetchImpl = globalThis.fetch, timeoutMs = 10_000,
  now = () => Date.now(), archiveFactory } = {}) {
  if (url === "disabled") return null;
  const automatic = url === "auto";
  if (!automatic) validatedSourceUrl(url);
  const namespace = createHash("sha256").update(automatic ? "protomaps-auto" : url).digest("hex").slice(0, 16);
  const cache = new MapDiskCache(cacheDirectory, maximumCacheBytes);
  const statePath = join(cacheDirectory, `source-${namespace}.json`);
  const withRangeSlot = createSemaphore(4);
  const withTileSlot = createSemaphore(4);
  const inflight = new Map();
  let source = null, archive = null, refreshing = null, lastMetadataAttempt = 0, metadataFailed = false;
  let lastMetadataSuccess = null, lastSuccess = null, lastError = null;
  let closed = false;

  function useSource(candidate) {
    if (source?.url === candidate.url) return;
    const rangeSource = createRangeSource(candidate.url, { fetchImpl, timeoutMs, withSlot: withRangeSlot });
    archive = archiveFactory ? archiveFactory(candidate.url) : new PMTiles(rangeSource,
      new SharedPromiseCache(64, false, boundedDecompress), boundedDecompress);
    source = candidate;
  }

  const ready = (async () => {
    await cache.ready;
    if (automatic) {
      try {
        const stored = JSON.parse(await readFile(statePath, "utf8"));
        if (/^https:\/\/build\.protomaps\.com\/\d{8}\.pmtiles$/.test(stored.url)) useSource(stored);
      } catch { /* Offline cache remains usable even without source metadata. */ }
    } else {
      useSource({ url, revision: new URL(url).pathname.split("/").at(-1), schema_version: null });
    }
  })();

  async function refreshSource(force = false) {
    await ready;
    if (!automatic) return;
    if (refreshing) return refreshing;
    // Retry metadata outages after one minute, not once per downloaded tile.
    if (metadataFailed && lastMetadataAttempt && now() - lastMetadataAttempt < 60_000) return;
    if (!force && lastMetadataAttempt && now() - lastMetadataAttempt < (metadataFailed ? 60_000 : DAY_MS)) return;
    lastMetadataAttempt = now();
    refreshing = (async () => {
      try {
        const response = await fetchImpl(MAP_BUILD_MANIFEST, {
          headers: { Accept: "application/json" }, signal: AbortSignal.timeout(timeoutMs), redirect: "error",
        });
        if (!response.ok) { await response.body?.cancel(); throw new Error("manifest unavailable"); }
        const candidate = selectLatestBuild(JSON.parse((await readLimitedBody(response, 2 * 1024 * 1024)).toString("utf8")));
        useSource(candidate);
        lastMetadataSuccess = new Date(now()).toISOString();
        metadataFailed = false;
        const temporary = `${statePath}.tmp`;
        await writeFile(temporary, JSON.stringify(candidate), { mode: 0o600 });
        await rename(temporary, statePath);
      } catch {
        metadataFailed = true;
        lastError = "MAP_METADATA_UNAVAILABLE";
        if (!source) throw new MapTileError(lastError, "map source metadata is unavailable");
      } finally { refreshing = null; }
    })();
    return refreshing;
  }

  function load(z, x, y, name) {
    if (inflight.has(name)) return inflight.get(name);
    if (inflight.size >= 128) return Promise.reject(new MapTileError("MAP_BUSY", "too many map downloads are pending"));
    const pending = withTileSlot(async () => {
      await refreshSource();
      if (!archive) throw new MapTileError("MAP_UPSTREAM_UNAVAILABLE", "map source is unavailable");
      const retrieve = async () => {
        const current = archive;
        const revision = source.revision;
        const header = await current.getHeader();
        if (header.tileType !== TileType.Mvt || header.minZoom > 15 || header.maxZoom < 15) {
          throw new MapTileError("MAP_INVALID_SOURCE", "map source does not contain z15 vector tiles");
        }
        const data = await current.getZxy(z, x, y, AbortSignal.timeout(timeoutMs * 3));
        return transformVectorTile(data?.data, { z, x, y, revision, retrievedAt: new Date(now()).toISOString() });
      };
      let result;
      try { result = await retrieve(); } catch (error) {
        if (!automatic || error.code !== "MAP_SOURCE_NOT_FOUND") throw error;
        await refreshSource(true);
        result = await retrieve();
      }
      await cache.put(name, result).catch(() => { cache.lastError = "MAP_CACHE_WRITE_FAILED"; });
      lastSuccess = new Date(now()).toISOString();
      lastError = null;
      return result;
    }).catch((error) => {
      lastError = error.code ?? "MAP_UPSTREAM_UNAVAILABLE";
      if (error instanceof MapTileError) throw error;
      throw new MapTileError(lastError, "map tile download failed");
    }).finally(() => inflight.delete(name));
    inflight.set(name, pending);
    return pending;
  }

  const timer = automatic ? setInterval(() => { refreshSource().catch(() => {}); }, DAY_MS) : null;
  timer?.unref();
  return {
    async initialize() { await ready; await refreshSource().catch(() => {}); },
    async getTile(z, x, y) {
      validateTileCoordinates(z, x, y);
      await ready;
      const name = `map-v1-${namespace}-${z}-${x}-${y}.json`;
      const cached = await cache.get(name, { z, x, y });
      if (cached) {
        // Serve stored data immediately, including during source/manifest outages.
        if (!closed) refreshSource().then(() => {
          if (source && (source.revision !== cached.source.source_revision ||
              now() - Date.parse(cached.source.retrieved_at) > 7 * DAY_MS)) return load(z, x, y, name);
        }).catch(() => {});
        return cached;
      }
      return load(z, x, y, name);
    },
    status() {
      return { enabled: true, mode: automatic ? "auto" : "fixed", source_revision: source?.revision ?? null,
        source_schema_version: source?.schema_version ?? null, last_metadata_refresh: lastMetadataSuccess,
        last_metadata_error: metadataFailed ? "MAP_METADATA_UNAVAILABLE" : null,
        last_success_at: lastSuccess, last_error: lastError, cache: cache.status() };
    },
    close() { closed = true; if (timer) clearInterval(timer); },
  };
}
