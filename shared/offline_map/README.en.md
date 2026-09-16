> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Jinan offline mini-map package v1

`jinan-v1.sqlite` is the whole-Jinan offline scene database used by the MOTO GPS iPhone app. It
is not a pre-drawn demo line and it does not cache AMap or OSM online tiles; it is queryable
vector data generated from real OpenStreetMap geometry:

- clipped exactly by the OSM Jinan municipal boundary relation `3486449`;
- keeps roads usable by motor vehicles and excludes roads explicitly marked `access/private/no`;
- keeps building outlines that actually exist in OSM and does not generate or draw in buildings;
- WGS84 coordinates are converted uniformly to the GCJ-02 E6 used by the navigation chain;
- roads and buildings each get a SQLite R-tree and the phone only queries 500–800 m around the
  vehicle;
- the terminal still receives only the current window and does not need the 9.73 MiB whole-city
  data inside the ESP32.

## 2026-09-03 measured data

| Metric | Value |
| --- | ---: |
| SQLite file | 9.73 MiB / 10,199,040 bytes |
| Road polylines | 47,468 |
| Road points | 266,985 |
| Building outlines | 26,702 |
| Building points | 148,140 |
| Candidate roads in the Building D–Inspur demo area | 291 |
| Candidate buildings in the Building D–Inspur demo area | 283 |
| SHA-256 | `d4ce2ebb0a9be68b060964e063858bc2b9a127d68b9796412feec6dc5b9fa2c3` |

The hash is authoritative in `jinan-v1.manifest.json`; regenerating with unchanged input versions
gives a deterministic result.

## File format

The database schema is in `jinan-v1.sql`. The core conventions:

- `metadata`: version, coordinate system, source, attribution, data date and statistics;
- `roads` / `road_rtree`: road geometry and extent index;
- `buildings` / `building_rtree`: building outlines and extent index;
- `points` BLOB: repeated little-endian `[int32 lat_e6, int32 lon_e6]`;
- the first building point is not repeated at the end, and the renderer closes the ring;
- multi-polygon buildings generated from an OSM relation keep their source type with a negative
  `osm_way_id`;
- building interior holes are omitted in v1. The 466×466 grey background blocks do not need hole
  information.

Road class: `0 motorway`, `1 primary`, `2 secondary`, `3 residential`, `4 service`, `5 other`.
Building class: `0 generic`, `1 landmark`, `2 parking`.

## Feasibility boundary

This scheme lets any navigation position inside Jinan draw on the same high-density offline road
database; but "having data" does not mean the screen draws all of it at once. The iPhone first
filters several hundred candidate features by distance and road class, and BLE v1 sends at most
24 roads, 192 road points, 16 buildings and 128 building points per window, matching the 466×466
round display. If the real hardware still looks too sparse, the next step is to adjust the window
selection and the BLE capacity, not to fabricate background lines.

OSM building coverage is not uniform: central urban areas are usually denser, while suburbs or
newly built parks may lack outlines. Missing buildings can only be added later from a lawful data
source or by a measured survey; the current package does not guess building positions.

## Licensing and attribution

The data comes from OpenStreetMap and uses ODbL 1.0. The product UI or an "About / Map data" page
must provide a discoverable `© OpenStreetMap contributors` attribution and a licence link.
Building an offline package by bulk-downloading `tile.openstreetmap.org` tiles is forbidden; this
project uses the PBF data provided by Geofabrik.

- Data source: https://download.geofabrik.de/asia/china/shandong.html
- Attribution guidelines: https://osmfoundation.org/wiki/Licence/Attribution_Guidelines
- ODbL: https://opendatacommons.org/licenses/odbl/1-0/

## Regenerating

Dependencies: `osmium-tool`, `jq`, Node.js 22+ (needs the built-in `node:sqlite`).

```bash
mkdir -p tmp/offline_map_source
curl -L https://download.geofabrik.de/asia/china/shandong-latest.osm.pbf \
  -o tmp/offline_map_source/shandong-latest.osm.pbf
./scripts/offline_map/extract_jinan.sh
node scripts/offline_map/build_jinan_sqlite.mjs
node scripts/offline_map/validate_jinan_sqlite.mjs
```

The first step generates the boundary, the Jinan PBF and the GeoJSONSeq intermediate files; the
second step generates the SQLite and the manifest; the third step decodes every geometry one by
one and validates the boundary, the point counts, the R-tree, the version, the coordinate ranges
and the Building D area density.
