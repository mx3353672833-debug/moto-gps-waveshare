> **Language:** English · [中文](JINAN_OFFLINE_MAP_MVP.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Jinan offline road network and building outlines MVP

> Historical design note. The Jinan SQLite offline database and the iPhone scene query are now
> implemented; the current format, capacity and generation method are authoritative in
> `../offline_map/README.md`, and the text below keeps the record of how the approach was chosen,
> not a release acceptance record.

## Conclusion

It is feasible, but the recommendation is to download the "Jinan offline map" to the **iPhone**
and have the ESP32 receive only a small window around the current position. Do not write the
whole provincial raw map or an AMap offline package straight into the terminal, and do not
extract tiles or vector data from the AMap SDK on your own.

The current demo fixture for this route is already a purely offline, verifiable minimal sample:
24 real OSM roads, 192 real source nodes and about 9.98 km of polylines. It uses the round
display's current road-layer capacity in full, but it is not a complete Jinan map.

## Recommended architecture

1. The iPhone downloads the Jinan OSM vector package and stores it with SQLite + R-tree or in
   fixed 500 m tiles.
2. After every position change the app queries 500–800 m around the vehicle heading and takes
   only the roads and the larger building outlines that the screen can show.
3. Roads are simplified to a 2–3 m error and the coordinates are converted to GCJ-02, aligned
   with the AMap planned route.
4. The app sends at most 24 roads / 192 road points plus a window of 16 building outlines / 128
   points over the new BLE `MapScene (0x14)` message; it updates only after about 100 m of
   travel or when crossing a tile, and does not need to send a map on every frame.
5. The ESP32 keeps the current heading-up projection: the heading is fixed while the white
   planned route and the grey roads pan and rotate together. After a network loss an already
   downloaded window can still be displayed.

This is simpler and more reliable than doing city-wide spatial queries inside the ESP32, and a
map upgrade does not require reflashing the firmware.

## How buildings are handled

Buildings cannot be drawn by reusing the road array as a workaround. `MapScene` already reserves
a separate protocol budget of 16 footprints / 128 points for buildings; the terminal's LVGL
building layer is still not wired up. A real query should show only real outlines within about
300 m of the vehicle and larger than about 250 m², stroked in 1 px dark grey. That way they do
not blend into the 3 px roads and the 6 px white route.

OSM building coverage in Jinan is not complete. In a measured query over about 0.86 km² in the
southern half of this route there were only 53 building ways and 366 raw outline points; real
buildings that exist can therefore be shown, but the building density of the AMap base map
cannot be promised. Missing areas must be left empty and must not be filled in with random
rectangles. If a commercial version has to reach AMap level, you need to buy a Chinese
compliant vector data licence that explicitly allows custom terminal rendering and offline
distribution.

The AMap iOS 3D map SDK itself supports downloading offline maps by city, which suits the app's
whole-route overview page; its public interface provides no way to extract arbitrary
road/building vectors from the offline package and send them to the ESP32. The "AMap preview
inside the app" and the "OSM grey background on the terminal" should therefore be treated as two
data layers, and the AMap offline package must not be used as the in-house firmware database.
References:

- <https://lbs.amap.com/api/maps-sdk-for-ios/guide/create-map/use-offlinemap>
- <https://lbs.amap.com/api/ios-sdk/download/>

## Capacity estimate

The following is an engineering estimate, not a supplier commitment; after formal packaging the
generated artifacts are authoritative:

| Data scope | Suggested format | Estimated size |
| --- | --- | ---: |
| Current Building D → Inspur demo roads | C++ double constants, 24 roads / 192 points | about 4–6 KB Flash |
| Drivable roads in Jinan, without buildings and name full text | 500 m tiles, 0.5 m quantisation, 2–3 m simplification | about 4–8 MB |
| Jinan roads + existing OSM building outlines | as above, buildings clipped by area/level | about 7–15 MB |
| Plus road names / POI / search index | string table + spatial/text index | about 15–25 MB |
| Raw Shandong OSM PBF (unclipped) | current Geofabrik provincial source file | about 67 MB, cannot go straight into the terminal |

Basis for the estimate: the Jinan administrative area currently has about 55,475 `highway` ways
and 25,918 building ways/relations in OSM; in a representative sample of about 1.68 km² around
the route the drivable roads simplify to about 2.6 KB/km² of pure geometry at a 2 m error. A
complete package must also add the tile directory, road class, checksums, version and file
system overhead, so it cannot be extrapolated from raw coordinate data alone.

The Waveshare board has 32 MiB Flash and the current partition table gives only 7 MiB to
`storage`; about 16.9 MiB of the partition table is still unallocated. Keeping the 8 MiB factory
app and not doing dual OTA partitions, the map partition could in theory be enlarged to about
23.9 MiB. A heavily clipped road + OSM building package has a chance of fitting, but once
names/POI/search index are added the headroom shrinks quickly, and a city-wide index and map
updates become more complicated. The recommendation is still to keep the city-wide package on
the iPhone; only after a measured generated package comes in below about 15 MiB should you
consider writing a copy to the terminal.

## Offline package format (suggested)

- Tiles: 500 m × 500 m; each tile has a bbox, a version, a CRC32 and road/building offset tables.
- Coordinates: `uint16` X/Y within the tile, 0.5 m quantisation; 4 bytes per point.
- Road header: class, flags, point count, data offset; keep only the attributes needed for
  rendering.
- Building header: closed ring, area level, point count; do not store useless POI text.
- App side: SQLite/R-tree or mmap index; the terminal side parses only one 24/192 rolling
  window.
- Updates: incremental replacement by tile version, without tying the map version to the
  firmware version.

## Scope delivered in this round

Done: `MapScene (0x14)` in the shared C++ BLE codec, capability bit 7, range validation,
fragmentation/reassembly tests for the 182-byte characteristic value and the 20-byte
compatibility path, and `map-scene.v1.schema.json`. `jinan_map_scene_sample.json` is a traceable
sample generated from OSM vector entities, containing 24 roads / 192 points and 16 real
buildings / 94 points near the origin; every object keeps its OSM way ID. It can be fetched
again with the following command:

```sh
node scripts/update_jinan_map_scene_sample.mjs
```

iOS can already load this real sample as a local scene repository, clip a 500–800 m window by
the current position and produce a new revision after about 100 m of travel. The query result
strictly enforces the protocol limits of 24 roads / 192 road points and 16 buildings / 128
building points; outside the sample coverage it returns an empty window and does not add random
roads or buildings. The app hands the window to the shared C++ codec, fragments it by the
negotiated 182-byte frame length, and sends it only after the terminal handshake declares
capability bit 7. During a disconnection only the latest complete window is kept and it is sent
again after reconnection.

iOS currently has two `OfflineMapSceneQuerying` repositories: an in-memory index for small-sample
EVT validation and `SQLiteOfflineMapSceneIndex` for the city-wide package. The latter reads
GCJ-02 E6 geometry according to `shared/offline_map/jinan-v1.sql`, pre-selects with `road_rtree`
/ `building_rtree`, and then applies the same exact clipping, road-class priority and building
distance/area ordering as the small sample. The default file path is
`Application Support/OfflineMaps/jinan-v1.sqlite`, and a seed database with the same name can
also be placed directly in the App Bundle root. The whole-Jinan JSON is never read into memory in one go.

The whole-Jinan SQLite artifact now lives at `shared/offline_map/jinan-v1.sqlite` and is included
in the iOS App Bundle: about 9.63 MiB, with 47,468 roads / 266,985 road points and 26,702
buildings / 148,140 building points. Full geometry decoding of the database and SQLite
`integrity_check` both pass, and the Building D area also returns real road and building query
results. The iPhone side is therefore no longer a fixed demo route but can query the city-wide
package by current position.

What is not finished is online download / incremental version update of the offline package and
acceptance of OSM coverage density during real rides. Terminal reception, double-buffered atomic
commit and LVGL building fill/stroke belong to the ESP32 display chain and should be accepted in
a separate change; the completed iPhone package alone does not justify claiming that the current
real hardware already displays all Jinan buildings.

## Data sources and licensing

Road/building data may come from OpenStreetMap or from a commercial data source for which
written authorisation has been obtained. OSM data uses ODbL 1.0: a public product must clearly
credit OpenStreetMap and provide a licence entry point; when distributing an offline database or
a derived database you must also check the ODbL database attribution/sharing conditions. The
official requirements are at:

- <https://osmfoundation.org/wiki/Licence/Attribution_Guidelines>
- <https://www.openstreetmap.org/copyright>

Do not bulk-cache the public `tile.openstreetmap.org` tiles; the official tile policy explicitly
requires self-hosted tiles for offline use or a supplier that explicitly allows offline
download:

- <https://operations.osmfoundation.org/policies/tiles/>

You can start from the Geofabrik Shandong OSM PBF and clip it on a computer by the Jinan
administrative boundary to generate your own vector tiles. The current provincial download page
is:

- <https://download.geofabrik.de/asia/china/shandong.html>

Before commercial use you must also check the Chinese map display, map approval number and data
distribution compliance requirements separately; this document is only an engineering plan and
does not replace legal advice.
