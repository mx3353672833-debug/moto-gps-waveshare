> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Jinan real-road demo fixture

> This page mainly describes the fixed demo fixture. The iPhone now loads the whole-Jinan OSM
> SQLite background data with the app, and normal navigation inside Jinan can use that offline
> scene, see `../offline_map/README.md`.
> The discussion below about building a separate online road Provider does not mean you have to
> apply for an AMap road interface in order to use the existing OSM layer.

This fixture makes the Web preview, the iOS offline fallback and the ESP32 on-device long-press
demo use the same real road: **near Building D of the Shandong Big Data Industry Base → near
Inspur Group (headquarters)**. The white line is the selected route and the grey lines are the
surrounding roads; the heading is fixed and both use the same translation, heading rotation and
zoom transforms.

The only hand-maintained data source is `jinan_big_data_to_inspur.json`. Running
`node scripts/generate_jinan_demo_fixture.mjs` generates the C++ and Swift constants; CI can use
`node scripts/generate_jinan_demo_fixture.mjs --check` to check whether the two ends are still
exactly consistent. Manoeuvre positions are stored as route node indices, and distances and
route progress are computed from accumulated geometric distance, not drawn by hand against
time.

## The white planned route (offline fallback)

The fixture only freezes road nodes that can be verified on OpenStreetMap, that are legally
directed and physically connected, with a total length of about 1.49 km: campus lanes → Xinluo
Avenue → Chonghua Road → Inspur Road.

- [Campus road of the Big Data Industry Base, way 1296826090](https://www.openstreetmap.org/way/1296826090)
- [Xinluo Avenue, way 1222956187](https://www.openstreetmap.org/way/1222956187)
- [Xinluo Avenue connector, way 562686625](https://www.openstreetmap.org/way/562686625)
- [Chonghua Road, way 562686626](https://www.openstreetmap.org/way/562686626)
- [Inspur Road north section, way 790389631](https://www.openstreetmap.org/way/790389631)
- [Inspur Road south section, way 136622031](https://www.openstreetmap.org/way/136622031)

OSM is missing the complete final connection at the gateways of both POIs, so the offline demo
starts on the real campus lane near Building D (about 70 m from the POI) and ends on Inspur Road
near the Inspur Group entrance (about 53 m from the entrance). The UI and the documentation both
say "near", and an unsurveyed straight line is not passed off as a road.

## Grey surrounding roads

The dense background uses **24 separate OSM ways and 192 points (the current firmware capacity,
fully used)**, with a total polyline length of about 9.98 km. Among the 8 positions sampled along
the demo route, each position has 4–12 background roads within 250 m, covering the starting
campus, both sides of Xinluo Avenue, the campus ring road mid-route, Bole Road, both carriageways
of Kunshun Road and the roads inside the Inspur campus. Compared with the older 8 roads / 59
points, the local view is no longer down to one or two isolated grey lines.

Each road keeps its own span, and roads that are not connected are not forced into one line. The
complete way ID, version, edit time, road type, source node range and simplification error are
all recorded in the canonical JSON. Run:

```sh
node scripts/update_jinan_road_context.mjs
node scripts/generate_jinan_demo_fixture.mjs
node scripts/generate_jinan_demo_fixture.mjs --check
```

The first step reads the raw way/node data directly from the official OpenStreetMap API. The
simplification algorithm only deletes redundant source nodes and never invents coordinates;
different roads stay separate. The maximum polyline error of the retained nodes is 2–3 m. The
raw WGS84 coordinates are then converted through the project's tested WGS84 → GCJ-02 conversion,
so the same display coordinate system as the online AMap route is used.

`jinan_map_scene_sample.json` is a separate sample for the offline rolling-window protocol: it
reuses the 24 real roads above and adds 16 real building footprints near the Building D start
from OSM vector entities. It is used to validate the MapScene capacity and the shape of the
building data, and the current demo firmware does not load it automatically:

```sh
node scripts/update_jinan_map_scene_sample.mjs
```

Route and background road data: © OpenStreetMap contributors, ODbL. A product that publicly
displays the map must still provide readable attribution and a licence entry point at
`https://www.openstreetmap.org/copyright`; offline storage is not a reason to remove the
attribution.

## Jinan offline map and buildings

A complete Jinan offline map is feasible, but unclipped OSM/AMap tiles must not be pushed
straight into the ESP32. The recommendation is for the iPhone to hold vector tiles downloaded
per area and to send only simplified roads/building outlines within about 500–800 m of the
current position to the round display during navigation; the ESP32 maintains only one rolling
window. Buildings must be real footprints from a lawful data source and must not be passed off
as random rectangles. For the capacity estimate, the tile format, the Flash bounds and licence
handling see [`JINAN_OFFLINE_MAP_MVP.en.md`](JINAN_OFFLINE_MAP_MVP.en.md).

## Online demo and data boundary

Every time, the iOS "demo navigation" first requests a live AMap driving route through the
existing gateway from the fixed POI `B0JAAZDW32` (Building D of the Shandong Big Data Industry
Base) to `B02130VXRE` (Inspur Group headquarters), and simulates positions continuously along
the polyline returned by that request. The AMap response is not written into the source or the
fixture; only when the network is down does it fall back to the OSM route above. The stable demo
route token is only used to make the ESP32 load the OSM grey road background for the same place.

The ordinary AMap driving route v5 only returns the selected route and does not return the
complete surrounding road network. A production online background road network needs a separate
`RoadContextProvider`; the AMap "traffic conditions query" advanced Web Service can return
`roads[].polyline` with `extensions=all`, but that requires a commercial application for the
corresponding permission. Without that permission or another compliant road data source,
production mode shows only the real planned route and does not fabricate surrounding roads.
