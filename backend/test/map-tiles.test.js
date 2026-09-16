import assert from "node:assert/strict";
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createMapTileProvider, createRangeSource, MAP_BUILD_MANIFEST, MapTileError,
  selectLatestBuild, tileBoundsWgs84, transformVectorTile } from "../src/map-tiles.js";
import { MapDiskCache } from "../src/map-cache.js";

const fixture = await readFile(new URL("../fixtures/protomaps-jinan-15-27044-12791.mvt", import.meta.url));
const header = { tileType: 1, minZoom: 0, maxZoom: 15 };
const sourceUrl = "https://example.test/20260915.pmtiles";
const build = (date) => ({ key: `${date}.pmtiles`, size: 100_000, uploaded: "2026-09-16T00:00:00Z", version: "4.15.2" });
async function directory(t) {
  const value = await mkdtemp(join(tmpdir(), "moto-map-test-"));
  t.after(() => rm(value, { recursive: true, force: true }));
  return value;
}
function archive(getZxy = async () => ({ data: fixture })) {
  return { getHeader: async () => header, getZxy };
}
function provider(t, options) {
  const value = createMapTileProvider(options);
  t.after(() => value.close());
  return value;
}

test("real Jinan and Shanghai vector tiles become full GCJ footprints and drivable grey roads", async () => {
  for (const [city, x, y] of [["jinan", 27044, 12791], ["shanghai", 27440, 13389]]) {
    const data = await readFile(new URL(`../fixtures/protomaps-${city}-15-${x}-${y}.mvt`, import.meta.url));
    const value = transformVectorTile(data, { z: 15, x, y, revision: "20260915" });
    assert.equal(value.coordinate_system, "GCJ-02");
    assert.ok(value.roads.length > 30, "MultiLineStrings must retain all segments, without BLE feature caps");
    assert.ok(value.buildings.length > 100);
    assert.equal(value.source.source_revision, "20260915");
    assert.equal(value.source.licence, "ODbL-1.0");
    const [west, south, east, north] = value.source.bbox_wgs84;
    for (const feature of [...value.roads, ...value.buildings]) {
      assert.equal(feature.osm_way_id, undefined, "encoded MVT ids must not masquerade as OSM way ids");
      assert.ok(feature.points_e6.length <= 2048);
      for (const [lat, lon] of feature.points_e6) {
        assert.ok(Number.isInteger(lat) && Number.isInteger(lon));
        assert.ok(lat / 1e6 > south - 0.02 && lat / 1e6 < north + 0.02);
        assert.ok(lon / 1e6 > west - 0.02 && lon / 1e6 < east + 0.02);
      }
    }
    for (const feature of value.buildings) {
      assert.ok(feature.points_e6.length >= 3);
      assert.notDeepEqual(feature.points_e6[0], feature.points_e6.at(-1));
    }
  }
  const jinan = transformVectorTile(fixture, { z: 15, x: 27044, y: 12791, revision: "20260915" });
  assert.equal(jinan.roads.length, 104);
  assert.equal(jinan.buildings.length, 145);
  // The western tile border is WGS84 117.1142578; GCJ geometry is shifted east once.
  assert.ok(Math.min(...jinan.roads.flatMap((r) => r.points_e6.map((p) => p[1]))) > 117116000);
});

test("invalid coordinates, corrupt MVT and oversized tiles never produce successful partial maps", () => {
  for (const point of [[14, 1, 1], [15, -1, 0], [15, 32768, 0], [15, 0.5, 1], [15, NaN, 0]]) {
    assert.throws(() => tileBoundsWgs84(...point), { code: "INVALID_REQUEST" });
  }
  assert.throws(() => transformVectorTile(Buffer.from([255, 255, 255]), {
    z: 15, x: 1, y: 1, revision: "test",
  }), { code: "MAP_INVALID_TILE" });
  assert.throws(() => transformVectorTile(Buffer.alloc(8 * 1024 * 1024 + 1), {
    z: 15, x: 1, y: 1, revision: "test",
  }), { code: "MAP_TILE_TOO_LARGE" });
});

test("Range source rejects whole-archive responses, wrong offsets and truncated data", async () => {
  for (const response of [
    new Response("archive", { status: 200 }),
    new Response("abcd", { status: 206, headers: { "content-range": "bytes 1-4/100" } }),
    new Response("abc", { status: 206, headers: { "content-range": "bytes 0-3/100" } }),
    new Response("abcde", { status: 206, headers: { "content-range": "bytes 0-3/100" } }),
  ]) {
    const source = createRangeSource(sourceUrl, { fetchImpl: async () => response });
    await assert.rejects(source.getBytes(0, 4));
  }
  const source = createRangeSource(sourceUrl, { fetchImpl: async (_url, options) => {
    assert.equal(options.headers.Range, "bytes=12-15");
    assert.equal(options.redirect, "error");
    return new Response("abcd", { status: 206, headers: { "content-range": "bytes 12-15/100", etag: '"v1"' } });
  } });
  assert.equal(Buffer.from((await source.getBytes(12, 4)).data).toString(), "abcd");
  await assert.rejects(source.getBytes(12, 4, undefined, '"v2"'), /changed/);
  await assert.rejects(source.getBytes(0, 9 * 1024 * 1024), { code: "MAP_INVALID_SOURCE" });
  assert.throws(() => createRangeSource("http://example.test/data.pmtiles"));
});

test("Range source accepts a legal EOF in the initial PMTiles header probe", async () => {
  const source = createRangeSource(sourceUrl, { fetchImpl: async () => new Response(Buffer.alloc(1000), {
    status: 206, headers: { "content-range": "bytes 0-999/1000" },
  }) });
  assert.equal((await source.getBytes(0, 16384)).data.byteLength, 1000);
});

test("same-tile requests coalesce; restart and source outage retain the disk cache", async (t) => {
  const cacheDirectory = await directory(t);
  let calls = 0;
  const first = provider(t, { url: sourceUrl, cacheDirectory, archiveFactory: () => archive(async () => {
    calls += 1;
    await new Promise((resolve) => setImmediate(resolve));
    return { data: fixture };
  }) });
  const values = await Promise.all(Array.from({ length: 12 }, () => first.getTile(15, 27044, 12791)));
  assert.equal(calls, 1);
  assert.deepEqual(values[0], values[11]);
  first.close();
  const offline = provider(t, { url: sourceUrl, cacheDirectory, archiveFactory: () => archive(async () => {
    throw new MapTileError("MAP_UPSTREAM_UNAVAILABLE", "offline");
  }) });
  assert.deepEqual(await offline.getTile(15, 27044, 12791), values[0]);
  await assert.rejects(offline.getTile(15, 27045, 12791), { code: "MAP_UPSTREAM_UNAVAILABLE" });
  assert.equal(offline.status().cache.tiles, 1);
});

test("upstream tile work is bounded to four concurrent loads", async (t) => {
  const cacheDirectory = await directory(t);
  let active = 0, maximum = 0;
  const value = provider(t, { url: sourceUrl, cacheDirectory, archiveFactory: () => archive(async () => {
    maximum = Math.max(maximum, ++active);
    await new Promise((resolve) => setTimeout(resolve, 10));
    active -= 1;
    return undefined;
  }) });
  await Promise.all(Array.from({ length: 15 }, (_, i) => value.getTile(15, 27044 + i, 12791)));
  assert.equal(maximum, 4);
});

test("auto selects completed compatible builds only and retains old tiles across build changes", async (t) => {
  assert.equal(selectLatestBuild([build("20260914"), build("20260915"),
    { ...build("20260916"), version: "5.0.0" }, { ...build("20260917"), key: "https://attacker.test/a.pmtiles" },
  ]).revision, "20260915");
  assert.throws(() => selectLatestBuild([{ ...build("20260916"), version: "5.0.0" }]), { code: "MAP_INVALID_MANIFEST" });
  const cacheDirectory = await directory(t);
  let latest = "20260915", time = Date.parse("2026-09-16T00:00:00Z"), calls = 0;
  let offline = false;
  const options = { cacheDirectory, now: () => time,
    fetchImpl: async (url) => {
      assert.equal(url, MAP_BUILD_MANIFEST);
      if (offline) throw new Error("offline");
      return Response.json([build(latest)]);
    },
    archiveFactory: () => archive(async () => {
      if (offline) throw new MapTileError("MAP_UPSTREAM_UNAVAILABLE", "offline");
      calls += 1; return { data: fixture };
    }),
  };
  const value = provider(t, options);
  await value.initialize();
  const original = await value.getTile(15, 27044, 12791);
  latest = "20260916";
  time += 86_400_001;
  // Disk hit is immediate and remains tagged with its actual source, then refreshes in the background.
  assert.equal((await value.getTile(15, 27044, 12791)).source.source_revision, "20260915");
  const deadline = Date.now() + 2000;
  while (Date.now() < deadline && value.status().last_success_at !== new Date(time).toISOString()) {
    await new Promise((r) => setTimeout(r, 10));
  }
  assert.equal(value.status().source_revision, "20260916");
  assert.equal(calls, 2);
  assert.equal(value.status().last_success_at, new Date(time).toISOString(), "background write must finish before restart");
  value.close();
  offline = true;
  const restarted = provider(t, options);
  await restarted.initialize();
  const saved = await restarted.getTile(15, 27044, 12791);
  assert.deepEqual(saved.roads, original.roads);
  assert.equal(saved.source.source_revision, "20260916");
  assert.equal(restarted.status().last_error, "MAP_METADATA_UNAVAILABLE");
});

test("source 404 forces a manifest refresh and retries a replacement archive once", async (t) => {
  const cacheDirectory = await directory(t);
  let manifests = 0;
  const value = provider(t, { cacheDirectory,
    fetchImpl: async () => Response.json([build(++manifests === 1 ? "20260914" : "20260915")]),
    archiveFactory: (url) => archive(async () => {
      if (url.includes("20260914")) throw new MapTileError("MAP_SOURCE_NOT_FOUND", "expired");
      return { data: fixture };
    }),
  });
  assert.equal((await value.getTile(15, 27044, 12791)).source.source_revision, "20260915");
  assert.equal(manifests, 2);
});

test("disk LRU enforces its byte budget without deleting unrelated files", async (t) => {
  const cacheDirectory = await directory(t);
  const cache = new MapDiskCache(cacheDirectory, 1024);
  const make = (x) => transformVectorTile(undefined, { z: 15, x, y: 1, revision: "test" });
  for (let x = 0; x < 6; x += 1) await cache.put(`map-v1-0123456789abcdef-15-${x}-1.json`, make(x));
  assert.ok(cache.status().bytes <= 1024);
  assert.equal(await cache.get("map-v1-0123456789abcdef-15-0-1.json", { z: 15, x: 0, y: 1 }), null);
  assert.ok(await cache.get("map-v1-0123456789abcdef-15-5-1.json", { z: 15, x: 5, y: 1 }));
  assert.ok((await readdir(cacheDirectory)).length < 6);
});

test("corrupt-cache cleanup cannot remove a concurrent successful replacement", async (t) => {
  const cacheDirectory = await directory(t);
  const cache = new MapDiskCache(cacheDirectory);
  const name = "map-v1-0123456789abcdef-15-1-1.json";
  const good = transformVectorTile(undefined, { z: 15, x: 1, y: 1, revision: "test" });
  await cache.put(name, { ...good, tile: { z: 15, x: 2, y: 1 } });
  await Promise.all([cache.get(name, good.tile), cache.put(name, good)]);
  assert.deepEqual(await cache.get(name, good.tile), good);
});

test("empty tiles remain bounded by file count, in addition to the byte budget", async (t) => {
  const cache = new MapDiskCache(await directory(t), 1024 * 1024, 2);
  for (let x = 0; x < 5; x++) {
    await cache.put(`map-v1-0123456789abcdef-15-${x}-1.json`,
      transformVectorTile(undefined, { z: 15, x, y: 1, revision: "test" }));
  }
  assert.equal(cache.status().tiles, 2);
  assert.equal(cache.status().maximum_tiles, 2);
});

test("source 404 cannot bypass manifest failure backoff for each downloaded tile", async (t) => {
  let calls = 0;
  const value = provider(t, { cacheDirectory: await directory(t),
    fetchImpl: async () => ++calls === 1 ? Response.json([build("20260915")]) : new Response("down", { status: 503 }),
    archiveFactory: () => archive(async () => { throw new MapTileError("MAP_SOURCE_NOT_FOUND", "expired"); }),
  });
  await value.initialize();
  for (let x = 1; x < 4; x++) await assert.rejects(value.getTile(15, x, 1));
  assert.equal(calls, 2, "only first 404 may immediately force the metadata check");
});
