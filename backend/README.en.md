> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Route and surrounding-map gateway

Node.js 20+ is required; Node.js 24+ is recommended for the `--env-file` examples below.
Keep the AMap key on the server, never in the iOS app, website or firmware. Map decoding depends on
pinned versions of `pmtiles`, `@mapbox/vector-tile` and `pbf`; install dependencies before deployment.

## Run local tests first

```sh
cd backend
npm ci
npm test
MOTO_PROVIDER=fixture WEB_ORIGIN=http://127.0.0.1:4173 npm start
```

The service listens on `127.0.0.1:8787`. The fixture returns a fixed synthetic route and empty search
results, not live navigation. An unset `MOTO_PROVIDER` defaults to fixture; configure it explicitly
when deploying. Online maps default to disabled in fixture/disabled modes, so tests need no external
network. The map-source setting can be overridden independently.

## Configure the live service

```sh
cp .env.example .env
chmod 600 .env
# Edit .env locally: use your own AMAP_WEB_SERVICE_KEY and set MOTO_PROVIDER=amap.
# Set WEB_ORIGIN to your web page's actual Origin; never commit .env to Git.
node --env-file=.env src/server.js
```

`npm start` does not read `.env` automatically; a service manager can inject the environment instead.
Until the key is ready, keep `MOTO_PROVIDER=disabled`. Do not use fixture as a production fallback.
Production dependency installation can use `npm ci --omit=dev`; retain `package-lock.json`.

Use your own HTTPS reverse proxy to forward `/moto-gps/api/` to `http://127.0.0.1:8787/`.
Keep the trailing slash and rewrite the prefix: client `/moto-gps/api/v1/routes` must reach backend
`/v1/routes`. Use your own domain certificate and configure the same HTTPS base URL on the iPhone;
the computer's localhost is not usable there. See the [gateway guide](../docs/GATEWAY_SETUP.en.md).

```sh
curl --fail-with-body https://YOUR-DOMAIN/moto-gps/api/healthz
```

Check `provider=amap`, `ready_for_live_navigation=true`, `capabilities.surrounding_map` and
`capabilities.map_city_search`. Capability flags indicate configuration, **not a substitute for real
place, route, city and uncached-tile requests**. `map_source` reports the revision, cache counts, last
success and errors. `road_speed_limits` and `traffic_light_countdown` are currently both `false`.

Neither the repository nor the website provides a free public gateway. Rate limiting and CORS do not
replace authentication. Public deployments need access control, TLS, quota limits, log handling and
alerts. New authentication schemes require matching client changes. GET search URLs may include
search terms and precise locations, and tile paths reveal an area; reverse-proxy access logs may keep
these. Verify rotation, backups, retention and privacy disclosures. Do not claim zero collection or log keys.

## API

| Endpoint | Purpose |
| --- | --- |
| `GET /healthz` | Service mode, configured capabilities and map-source/cache status |
| `GET /v1/places?keywords=...&longitude_deg=...&latitude_deg=...&region=...` | POI search; optional WGS84 latitude and longitude must be supplied together |
| `POST /v1/route-options` | Up to three ordinary driving routes for preview and selection |
| `POST /v1/routes` | A route, off-route rerouting and periodic route/traffic refresh |
| `GET /v1/map/cities?keywords=...` | City, district and four province-level municipality download bounds; ordinary provinces and countries are excluded |
| `GET /v1/map/tiles/15/{x}/{y}` | One road/building JSON tile; z15 only, integer x/y from 0 to 32767, no query parameters |

Route schemas are in `shared/protocol`; see `fixtures/route-request-v1.json` for a request example.
Route input positions are WGS84 and output geometry is GCJ-02; place-search result positions are WGS84.
`request_id` is echoed unchanged so the navigation core can reject stale responses. Route and AMap
responses do not enter the OSM map disk cache. Ordinary driving routes are not motorcycle-specific
and cannot guarantee avoidance of motorcycle restrictions. Traffic permissions, frequency and quota
depend on your AMap account and agreement. Verify authorisation for your use case before release or
use with smart hardware.

### Map coordinates and download bounds

- Tile IDs use the **WGS84 Web Mercator** grid. Convert a GCJ-02 position back before selecting a tile.
- Response `coordinate_system` is `GCJ-02`; road and building `points_e6` contain integer
  **`[latitude × 1e6, longitude × 1e6]`** pairs. Do not apply a second offset.
- `source` includes provider, licence, attribution link, `source_revision`, `retrieved_at` and WGS84
  bounds. These describe a data version and retrieval time, not live street imagery or traffic.
- City responses contain a `cities` array with `id`, `name`, `detail` and `bounds_wgs84`, ordered
  `[west, south, east, north]`. Bounds enclose inverse-transformed AMap administrative boundaries and
  may include neighbouring areas.
- The App downloads city/district regions or roughly one kilometre around a selected route. Offline
  packages contain only roads and buildings, not offline search, routing, live traffic, speed limits
  or countdowns. Bundled Jinan data remains a fallback.
- Completeness depends on local OSM coverage. The API does not truncate to the display's feature-count
  limit; the phone separately selects a BLE window. Oversized tiles return errors, not silently
  truncated success responses.

### Rate limits and errors

Independent limits per source address per minute are 60 place searches, 30 route requests, 30 city
searches and 600 map tiles. HTTP 429 includes `Retry-After: 60`. Download clients should stay around
3 requests/second and honour retry delays. `X-Real-IP` is read only for loopback connections; a trusted
reverse proxy must overwrite it, not pass through a client-supplied value. Keep Node bound to loopback.

Requests for the same tile are coalesced. Upstream tile jobs and Range requests each allow at most
4 concurrent operations, with at most 128 pending keys. Overload returns `MAP_BUSY` / HTTP 503 and
`Retry-After: 5`. Other source failures also return explicit errors; never store error responses as
successfully downloaded maps.

## Map source and persistent cache

| Environment variable | Default and purpose |
| --- | --- |
| `MOTO_MAP_PMTILES_URL` | `auto` in `amap` mode; `disabled` in fixture/disabled modes. Can be your own HTTPS `.pmtiles` URL |
| `MOTO_MAP_CACHE_DIR` | `.cache/map-tiles` under the service working directory; use dedicated persistent writable storage in production |
| `MOTO_MAP_CACHE_MAX_BYTES` | `1073741824`, or 1 GiB of JSON content; accepts integer limits from 1 KiB to 2 GiB |

```dotenv
MOTO_MAP_PMTILES_URL=auto
MOTO_MAP_CACHE_DIR=/YOUR_WRITABLE_STATE_DIRECTORY/maps
MOTO_MAP_CACHE_MAX_BYTES=1073741824
```

`disabled` turns off surrounding tiles only; city search depends on the AMap provider. The cache also
has a fixed limit of 100,000 files and evicts least-recently-used entries. Actual filesystem usage may
exceed JSON byte counts. The directory must be writable by the ordinary user running Node and survive
service restarts. A systemd sandbox must also permit writes to it.

`auto` uses the official [builds.json](https://build-metadata.protomaps.dev/builds.json) to select the
latest uploaded v4 archive compatible with the decoder, checking on startup and every 24 hours.
Source URLs are constructed only as `https://build.protomaps.com/YYYYMMDD.pmtiles`. An archive 404
triggers an additional manifest check and retry, with at least 60 seconds of backoff after manifest
failure. Persisted metadata allows an attempt to reuse the previous source after an offline restart.

Cache hits return immediately. On access, a changed source version or an entry older than 7 days
triggers background refresh; failure preserves the old tile, and source changes do not clear the auto
cache. An explicit HTTPS source bypasses the public manifest; immutable version paths are recommended.
Server-side URLs can include storage-authentication query parameters, which are not exposed to clients.
Clients cannot choose arbitrary upstream URLs.

Upstream storage must support HTTP Range. Each Range request is limited to 10 seconds and 8 MiB.
HTTP 200 whole-file responses that ignore Range are rejected, and gzip decompression is bounded;
the global archive is never downloaded in full. JSON is limited to 8 MiB, 12,000 total features,
2,048 points per feature and 120,000 points per tile. JSON gzip can be enabled at the HTTPS proxy;
iOS URLSession handles HTTP decompression.

**Public daily archives are a transitional source, not a production availability guarantee.**
The [official Protomaps download guidance](https://docs.protomaps.com/basemaps/downloads) recommends
copying data to your own storage; public hosting retains recent and version archives only. Before
serving more users, place a compatible regional PMTiles archive on your own HTTPS storage with Range
support and change the server environment variable. Users need not modify the App. Do not use bulk
downloads from public OSM standard tiles or Overpass as a fallback. Retain `© OpenStreetMap contributors`,
source information and the [data-licence link](https://www.openstreetmap.org/copyright).

## Pre-release verification

Verify real place search, candidate routes, city/district bounds, an uncached tile and repeated cached
reads. Then confirm that old cache remains readable when upstream is unavailable. Inspect
`map_source.last_success_at`, `last_error`, `last_metadata_error` and cache statistics. Tests and sample
requests in two cities do not replace display road tests, weak-network checks, cross-city downloads
or long-running validation. TestFlight is not live, and real speed limits and countdowns are not connected.
