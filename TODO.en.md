> **Language:** English · [中文](TODO.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# TODO

Status convention: `[ ]` not started, `[x]` completed. Each item is marked with `(A)`, `(B)` or `(C)` for its portability level; the definitions are in the [portability contract](docs/PORTABILITY.en.md). The current priority is completing the Waveshare 1.75C + native iOS app prototype; the enclosure design and the Garmin mount are paused at the user's request, and must not be advanced, redrawn or prototyped without an explicit instruction to resume.

## Current sprint: Waveshare real hardware + iPhone

- [x] **(A)** Migrate the Web, Presenter and ESP32 single framebuffer to 466 × 466 RGB565
- [x] **(C)** Add the `?demo=1` four-page round-display simulation entry point that needs no positioning/key, and complete the visual check at iPhone size
- [x] **(A/B)** Freeze the versioned BLE GATT UUIDs, fragmentation, CRC, heartbeat, display snapshots and touch commands
- [x] **(B)** Integrate the CO5300, CST9217, LVGL and BLE sources of the official Waveshare BSP
- [x] **(B)** Install ESP-IDF 5.5.5, complete a real-target build for the Waveshare `esp32s3` and complete the first flashing after backing up the factory Flash, confirming the checksum and obtaining authorisation
- [x] **(B)** Complete the iOS Core Location, POI search that prefers the phone's current position, real AMap route and BLE state-recovery adaptation; normal/production navigation uses real data only
- [x] **(B)** Add a clearly labelled built-in navigation demo adapter to the current app beta; when online it requests a live AMap "Building D → Inspur headquarters" route through the existing gateway each time, and falls back to a real OSM route for the same places when the network fails, both still passing through the shared `NavApp → NavCore → BLE` chain and neither hard-coding the AMap response
- [x] **(A/B)** Fix the issues where the handshake write could queue forever when the BLE connection is restored, callbacks from an old session interfered with the new connection, and the backoff was reset too early after a failure
- [x] **(A/B)** Add the iOS Launch Screen, a 1.25-second `MOTO GPS` logo fade in and out on ESP32 power-up, 5-second auto-hide of the page dots, and a long press on the navigation page to toggle the on-device demo route
- [x] **(A)** Reduce the navigation page's main information to the standard manoeuvre icon and a large distance number + unit, with no explanatory distance caption; hide the manoeuvre, distance, route progress and speed limit when there is no real route and the demo is not enabled
- [x] **(A/B)** Establish the canonical OSM demo "near Building D of the Shandong Big Data Industry Base → near Inspur Group (headquarters)": 35 route points / about 1.49 km, 59 surrounding road points / 8 separate spans; both the white line and the grey lines record the `© OpenStreetMap contributors` / ODbL attribution and the coordinate-conversion boundary, and the two unmapped entrance gaps are only called "near"
- [x] **(A)** Add the complete round-display connection lifecycle: slow scan while disconnected, fast scan during the handshake, a green tick for about 920 ms on success, a gentle breathing effect for ready to ride, a moving white dot while planning, and the mask fading out after a valid route
- [x] **(A)** State transitions use a 140 ms fade out + 220 ms fade in; with reduced motion they switch instantly and pause the 40 ms animation clock
- [x] **(A/B)** Completed the visual re-check state by state with the Web `deviceState=offline|connecting|success|ready|planning`; shared native tests 8/8, Swift checks 10/10, and the Web and ESP32-S3 builds all pass
- [x] **(A/B)** Raise the app demo preview to continuous 25 Hz interpolation, merge BLE navigation snapshots to 5 Hz at the protocol limit, deduplicate route geometry by signature and send physical fragments at 15 ms intervals
- [x] **(A/B)** Decouple ESP32 BLE reception from LVGL drawing: the receive thread only submits the latest state, and a separate low-priority task merges the navigation, map, IMU and media refreshes; host tests 8/8, the ESP-IDF build and continuous map transfer on real hardware pass
- [x] **(B)** Generate and integrate the complete Jinan offline SQLite vector package: 47,468 roads, 26,702 buildings; the iPhone only sends the round display a limited window around the current position
- [x] **(B)** Fix the issue where ENC_CHANGE/SUBSCRIBE arriving before CONNECT cleared the handshake state when the ESP32 restored pairing; currently three consecutive iPhone app cold starts have all completed the handshake on the first attempt
- [x] **(B)** Implement software power-off on holding PWR for 3 seconds, and configure and read-back verify the AXP2101 4-second hold hardware power-off fallback
- [x] **(B)** Pass encode/decode tests on both the C++ and Swift sides with the same set of protocol fixtures
- [x] **(B)** Back up the factory 32 MB Flash, generate and report the SHA-256, and after explicit confirmation to flash, complete the first flashing of the Waveshare board and verify that it boots
- [ ] **(B)** Long-run real-hardware verification of normal screen lock, backgrounding, the fixed disconnection recovery, off-route rerouting and traffic refresh
- [ ] **(B)** On the physical CO5300 + iPhone, accept item by item the six state transitions of offline, handshake, success, ready to ride, planning and route present, and re-check repeated reconnection, reduced motion and that no stale navigation data is shown when there is no route
- [ ] **(B)** Evaluate the three BLE HID media keys; "like" and cross-player track information remain optional capabilities
- [x] **(B)** iOS uses the public `systemMusicPlayer` to connect Apple Music previous track, play/pause, next track and the MediaState return; "like", which has no public interface, stays hidden and returns Unsupported

## Base contracts and engineering

- [x] **(A)** Establish the A/B/C migration levels and the rule of announcing changes before making them
- [x] **(A)** Freeze the 466 × 466, RGB565 round-display baseline
- [x] **(B)** Establish the shared, Web, ESP32, backend and test directory skeleton
- [x] **(B)** Pin the toolchain, the LVGL version and a reproducible build method
- [x] **(A)** Let the same LVGL product screens build for both WebAssembly and ESP32
- [x] **(A)** Define and test the versioned `RouteBundle` protocol
- [x] **(B)** Establish the in-house backend and the AMap provider, ensuring the key does not enter the web page or the firmware
- [x] **(A)** Establish the explicit WGS84/GCJ-02 coordinate boundary and conversion tests
- [x] **(A)** Establish the layered control chain with `NavApp → NavCore` shared by Web/iOS and `NavPresenter → LVGL` shared by Web/ESP32
- [x] **(A)** Implement shared route matching, route progress, turn switching and arrival detection
- [x] **(A)** Implement the off-route confirmation, reroute deduplication, stale-response discard and network-loss recovery state machine

## Completed development work: round-display UI and web replay

- [x] **(A)** Freeze the page information hierarchy, vehicle anchor, route line width and key acceptance frames corresponding to the reference video
- [x] **(A)** Extend the shared display state: page, speed, heading, validity, speed limit and music capability bits
- [x] **(A)** Implement local route clipping, heading-up projection, circular clipping, resampling and heading smoothing
- [x] **(A)** Implement the fixed heading-up vehicle arrow, grey surrounding roads, white selected route, manoeuvre icon and large distance number + unit; progress and the speed limit are shown conditionally on valid data
- [x] **(A)** Connect the congestion, off-route, reroute, network-loss and arrival states to the new navigation page
- [x] **(A)** Establish the shared LVGL page state machine for the four pages: navigation, speedometer, compass and optional music
- [x] **(A)** Implement horizontal touch paging, direction lock, debounce, large hit areas and the reduced-motion mode
- [x] **(B)** Connect the browser mouse/touch input contract and keep the adaptation boundary for a physical touch controller
- [x] **(A)** Implement the separate, plain speedometer page: current speed, `km/h` and a circular scale
- [x] **(A)** Implement the separate compass page: degrees, eight compass points, a rotating scale and degradation for an invalid heading
- [x] **(A)** Implement the closable music control page and the play/pause, previous track, next track and like event contract
- [x] **(C)** Simulate the music state in the web page; state clearly that "like" and track information do not represent the iPhone's real-hardware capabilities
- [x] **(C)** Upgrade the fixed route into a repeatable navigation track with time, position, speed, heading and accuracy
- [x] **(C)** Extend the web debugging console: page, play/pause/speed, progress, low-speed heading, off-route, network loss and traffic-condition injection
- [x] **(C)** Complete the visual check of the round display, touch and the console at desktop and phone widths
- [ ] **(C)** Complete the long-run replay stability check
- [x] **(A)** Establish automated tests for the heading-up projection and the 359°→0° heading boundary
- [ ] **(A)** Freeze the RGB565 golden frame and the page-state hash on a screenshot baseline from the physical CO5300
- [ ] **(A)** Verify 30 fps, peak memory and missing font glyphs on real hardware; the ESP32 target build consistency and the Flash partition budget have passed

For the detailed scope, migration conclusions and exit conditions see the [round-display product UI and navigation simulation execution plan](docs/ROUND_UI_EXECUTION_PLAN.en.md).

## Completed historical verification line: foreground navigation in the mobile web edition

- [x] **(C)** Change the `maler.top/moto-gps/` main entry point into the phone flow of positioning → search → route confirmation → navigating
- [x] **(B)** Converge the browser `watchPosition`, GPS speed/heading and the iOS orientation permission into the shared `GnssFix`
- [x] **(B)** Establish the WASM input bridge for the real RouteBundle/GNSS and the shared-core command callbacks
- [x] **(B)** Connect AMap POI 2.0, return the WGS84 navigation entrance point and prevent a second GCJ-02 offset
- [x] **(B)** Connect the AMap standard driving Route v2, the destination POI ID and `cost/polyline/navi/tmcs`
- [x] **(A/B)** Use the shared core for consecutive off-route confirmation, with the web page rerouting from the current position
- [x] **(A/B)** Use the shared core to schedule a traffic refresh every 60 seconds, with the web page updating `tmcs` and the ETA through a fresh route
- [x] **(C)** Integrate the Screen Wake Lock, the page-visibility recovery calibration and a foreground-use warning
- [x] **(B)** Deploy the Node gateway, systemd, a same-domain Nginx reverse proxy, request rate limiting and a disabled mode on maler
- [x] **(B)** Configure the AMap "Web Service API" key on the server; the online health check already reports the real provider as ready
- [ ] **(B)** Verify POI, routes, off-route rerouting, the 60-second traffic refresh and long-run operation with the screen on, using a real iPhone and real roads
- [ ] **(B)** Confirm with AMap the written permission for in-vehicle projection, caching and hardware terminals beyond personal verification

For the detailed status and the iPhone foreground/background boundaries that cannot be bypassed see the [mobile web edition real navigation execution plan](docs/MOBILE_WEB_NAVIGATION_PLAN.en.md).

## Later: real navigation reliability and a second provider

- [x] **(B)** Connect the software chain for AMap POI search, standard driving planning, rerouting and live traffic data
- [ ] **(B)** Evaluate Baidu as a second provider; the shared navigation core or the round-display UI must not be duplicated
- [ ] **(A/B)** Implement the "only change route for a clear benefit" policy on the authoritative iPhone navigation side
- [ ] **(A/B)** Extend the upper limit, chunking and low-memory degradation tests of the BLE local route window
- [ ] **(A)** Expand the Chinese road-name font library and verify Flash, RAM and the missing-glyph fallback
- [ ] **(A)** Optimise long-route matching performance with a progress window and precomputed coordinates
- [ ] **(B)** Record real iPhone Core Location logs and verify the city, elevated road, tunnel, mountain road and parallel road cases
- [ ] **(B)** Before productisation, obtain AMap's written authorisation conclusion for hardware terminals, route caching, traffic data and commercial use
- [ ] **(B)** For the production online surrounding-road layer, enable the AMap "traffic situation query" advanced API, verify the `roads[].polyline` coverage with `extensions=all`, and obtain explicit written authorisation for in-vehicle / smart-hardware projection

## Deferred / paused: in-house integrated main board, media control and the V3 enclosure

> The enclosure design and the Garmin mount are explicitly paused at present; the existing files below are for historical reference only, and modelling, drawing, purchasing or prototyping will not continue until the user gives an instruction to resume.

- [x] **(B)** An in-house integrated PCB + custom enclosure Rev A project was once established; the software prototype is now done on the Waveshare production board instead, and that project is for historical reference only
- [x] **(B)** Freeze the V3 appearance: continuous aluminium front bezel, four diagonal functional screws, plastic rear shell and a hidden quick release
- [x] **(A)** Migrate the shared round-display framebuffer to the target AMOLED's 466 × 466 and sync Web/ESP32
- [ ] **(B)** Obtain and freeze a 1.75-inch 466 × 466 screen assembly with a fixed part number, the 31 Pin FPC drawing and at least 2 physical units
- [ ] **(B)** Freeze the physical battery with protection/NTC, the lead exit direction and the battery-life target
- [ ] **(B)** If a fully standalone navigation version is revived later, then evaluate the LC76G and a GNSS antenna; the current iPhone prototype does not purchase or fit them
- [ ] **(B)** Make and verify the BQ25628E + TPS63070 + TUSB320LAI power coupon; the AXP2101 does not go onto the main board
- [x] **(B)** Complete the Rev A six-volume schematic, the BOM field / part-number completeness check and the KiCad 10.0.6 ERC (R4: 0 violations)
- [x] **(B)** Complete the layout and routing of the Ø52 mm four-layer round board R4 and the full-track DRC (1555 track segments, 189 vias, 0 DRC, 0 unconnected, 0 consistency errors)
- [x] **(B)** Output the R4 review Gerber, drill files, a 57-row grouped BOM, a 93-point CPL, assembly instructions, previews and the SHA-256 manifest, and complete the BOM/CPL consistency check
- [ ] **(B)** If the in-house PCB is revived, review the components and cost again against a no-GNSS iPhone-peripheral architecture, and then decide how to order 1–2 engineering sample boards
- [ ] **(B)** If the in-house PCB is revived, debug the bare board in the order power → USB → ESP32 → screen/touch → IMU/compass → BLE
- [ ] **(B)** If a stationary true-north direction is required later, then build the QMI8658 + LIS2MDL low-speed heading fusion adapter
- [ ] **(B)** The current prototype verifies the long-term stability of iPhone BLE with the screen off, with a weak signal and after a disconnection; terminal connectivity does not rely on a phone hotspot
- [ ] **(B)** Evaluate and verify BLE HID / iPhone AMS play/pause, previous-track and next-track control of the current third-party player, such as NetEase Cloud Music; the in-app `MPRemoteCommandCenter` must not be treated as a global command sender
- [ ] **(B)** Show `LikeTrack` according to the AMS current-player support list; show only "sent" and do not promise the saved state or the result of the action in other apps
- [ ] **(B)** Complete the physical-board RAM, Flash, frame rate, power, temperature, traffic and continuous-run tests
- [x] **(B)** Complete the parametric enclosure CAD, STEP/STL, 2D assembly drawing and interference check for the V3 Rev A0 screen, battery, antenna and PCB placeholders
- [ ] **(B)** Print the all-plastic EVT case and verify RF, compass, touch, heat dissipation, rain, vibration and the mount on the bike
- [ ] **(B)** Complete the Rev B main board and enclosure based on the Rev A/EVT measurements
- [ ] **(B)** After Rev B passes, machine the CNC aluminium front bezel + plastic RF rear cover; do not claim IP67 before testing

For the detailed hardware boundaries, component selection, manufacturing deliverables and exit conditions see the [in-house integrated hardware and enclosure execution plan](docs/CUSTOM_HARDWARE_PLAN.en.md).
