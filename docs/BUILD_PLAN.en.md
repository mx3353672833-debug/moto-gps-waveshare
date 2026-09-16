> **Language:** English · [中文](BUILD_PLAN.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Build plan

## Product goals

> **A historical plan, which must not be executed or ordered against today.** What this file keeps is the early standalone-terminal route of "in-house board + LC76G + phone hotspot". The current official prototype has changed to the Waveshare 1.75C + native iOS app, with the ESP32 acting only as the BLE instrument side: it does not carry an LC76G and does not request AMap through a hotspot. For the current baseline see the [Waveshare + iPhone prototype baseline](WAVESHARE_IOS_PROTOTYPE.en.md).

Build a portable motorcycle navigator centred on an ESP32-S3 and a 1.75-inch 466 × 466 round display. The shared UI has been unified into a single 466 × 466 framebuffer. The current prototype gets positioning, routes, traffic conditions and off-route rerouting from a native iPhone app; a standalone GNSS and an in-house PCB are optional directions after prototype verification. The navigator must support:

- clear turn-by-turn navigation;
- automatic off-route detection and rerouting;
- periodic live traffic condition updates;
- cached navigation, automatic reconnection and recovery after the hotspot disconnects;
- AMap normal driving routes as the data source during the prototype stage;
- later replacement with a motorcycle route service without changing the round-display UI and the navigation core.

## Architecture

```text
Web debug console (level C)
  ├─ Destination search, map, track playback, fault injection
  └─ WebAssembly
       └─ Shared C/C++ (level A)
            ├─ LVGL round-display UI and assets
            ├─ RouteBundle decoding
            ├─ Route matching, route progress and arrival detection
            ├─ Off-route confirmation, reroute and traffic refresh state machine
            └─ Coordinate handling
                  ├─ Web platform adapter (level B)
                  └─ ESP32 platform adapter (level B)

Own backend
  ├─ POI search
  ├─ AMap normal driving routes and traffic conditions
  ├─ Route reroute
  └─ Normalise, compress and return a RouteBundle

Physical device
  ├─ In-house ESP32-S3 + 466×466 AMOLED round board
  ├─ LC76G GNSS + separate antenna
  ├─ QMI8658 IMU + LIS2MDL magnetometer
  ├─ Wi-Fi phone hotspot
  └─ Local cache
```

The AMap API key exists only in the backend. Both the browser and the firmware call only our own interface, which avoids leaking the key and also makes the route provider replaceable.

## Suggested directory layout

```text
shared/
  nav_core/       Route matching, route progress, off-route and arrival detection
  nav_state/      Networking, traffic refresh and reroute state machine
  nav_protocol/   RouteBundle and version compatibility
  nav_ui/         The single LVGL round-display UI
  assets/         Fonts, icons, colours and images
platforms/
  web/            Emscripten, Canvas output and the simulation adapter
  esp32/          LCD, GNSS, Wi-Fi, HTTPS, NVS and clock adapters
web_console/      Level C web debug console
backend/          AMap provider, own API and key management
tests/
  fixtures/       Route, positioning, network and traffic events
  golden_frames/  Golden RGB565 frames
docs/
```

The directories can be adjusted slightly along with the build system, but the dependency direction between the shared layer and the platform layer must not be reversed.

## Data flow

1. The web page searches POIs, selects the origin and destination and requests a route through our own backend.
2. The backend calls the AMap normal driving route service and converts the raw response into a versioned `RouteBundle`.
3. The Web feasibility build feeds simulated positioning samples into the shared navigation core; the round display is rendered by the shared LVGL code.
4. The real-hardware build gets positioning samples from the GNSS adapter and requests the same backend through the phone hotspot.
5. The navigation core advances the route and computes the next action; the UI consumes only the standardised navigation state.
6. Once the off-route conditions are met continuously, the state machine immediately requests a reroute from the backend based on the current position, and atomically replaces the route on success.
7. The device refreshes traffic conditions periodically according to policy; only when the benefit of a new route exceeds a threshold does it suggest or perform a route change.
8. When the network is interrupted it keeps using the cached route, shows the connection state locally and reconnects in the background; once recovered it handles the pending reroute or traffic refresh first.

## Phases and exit conditions

### Phase 0: freeze the portability contract

- Pin 466 × 466, RGB565, shared LVGL rendering and the A/B/C classification.
- Pin the dependency direction, the platform interface boundaries and the rule of announcing changes beforehand.
- Exit condition: `docs/PORTABILITY.md` becomes the acceptance basis for later modifications.

### Phase 1: repository and minimal dual-target build

- Set up the shared layer, Web, ESP32, test and backend skeletons.
- Compile the same LVGL code to WebAssembly and to the ESP32.
- First display a single static 466 × 466 RGB565 round-display frame.
- Exit condition: no UI fork between the two targets; the firmware build must not depend on the web debug console.

### Phase 2: standard data protocol

- Define a `RouteBundle` with a version number, checksum and length limits.
- Express route geometry, manoeuvres, road names, remaining distance / time and per-segment traffic conditions.
- Design atomic replacement of the whole route, so a reroute cannot read half a data set.
- Exit condition: a fixed fixture can be encoded and decoded, corrupted data is rejected, and the agreed versions are compatible.

### Phase 3: navigation backend

- Wire up POI search, normal driving planning, route rerouting and traffic refresh.
- Configure the server-side key, timeouts, rate limiting, cache boundaries and observable logs.
- AMap responses appear only inside the provider; externally only the internal protocol is returned.
- Exit condition: a sample route converts reliably; errors, timeouts and quota exhaustion all have definite responses.

### Phase 4: shared navigation core

- Complete coordinate standardisation, route matching, progress advance, action switching and arrival detection.
- Implement off-route confirmation with distance, duration, direction and positioning-accuracy conditions, to avoid false positives from a single drifting fix.
- Implement reroute deduplication, discarding stale responses, backoff retries, periodic traffic refresh and recovery after a network outage.
- Exit condition: a fixed event stream produces identical state in desktop tests and in WebAssembly.

### Phase 5: web feasibility simulator

- The round-display part uses only the shared LVGL; the surrounding debug console may use HTML/CSS.
- Support play / pause / speed-up, dragging off the route, GNSS drift, hotspot disconnection, interface timeouts, congestion and out-of-order responses.
- Show route matching results, the state machine, the request log and performance metrics.
- Exit condition: off-route rerouting, traffic refresh and network recovery can be verified repeatedly without real riding.

### Phase 6: round-display visuals and interaction polish

- Complete the main turn, action distance, road name, remaining time / distance, traffic conditions, off-route and connection state inside the real circular visible area.
- Fonts, icons and animations are all implemented as shared LVGL assets, and RGB565 is simulated in the web.
- Establish golden frames for key states and a 30 fps performance budget.
- Exit condition: every design modification has been marked A/B/C, and level A screens can be generated from the same source as the firmware.

### Phase 7: real GNSS and road-scenario verification

- Connect real NMEA/UBX log playback first, then physical GNSS.
- Cover urban high-rises, parallel service roads, elevated road levels, tunnels, low-speed U-turns, missed junctions and short-term drift.
- When adjusting off-route thresholds, freeze them with test fixtures, so as not to optimise only for a single route.
- Exit condition: the main scenarios do not cause frequent false reroutes, and a real off-route triggers within an acceptable time.

### Phase 8: physical board consistency verification

- Connect the LCD, GNSS, Wi-Fi hotspot, HTTPS, NVS and power-loss recovery.
- Compare the state hashes and RGB565 frame buffers of Web and ESP32 with the same event stream.
- Measure RAM, PSRAM, Flash, frame rate, boot time, network traffic, temperature and sustained-run stability.
- Exit condition: core functionality does not need to be written a second time in the firmware; the hotspot screen-off scenario can run continuously and reconnect automatically.

### Phase 9: in-house main board and EVT enclosure

- Freeze the fixed part numbers of the display / FPC, battery, GNSS / 2.4 GHz antennas and the handlebar mounting orientation.
- Complete the Rev A schematic, the roughly Ø51–52 mm four-layer round board, the JLCPCB production package and joint debugging of 5 PCBA boards.
- The enclosure is co-designed with the antennas and the PCB from Rev A onwards, but only an all-plastic EVT shell is made first; the CNC aluminium front bezel and the plastic RF rear cover are frozen only after the display, battery, RF, magnetic field, thermal and on-bike vibration tests pass.
- Exit condition: the Rev B main board and enclosure pass on-bike, hotspot, GNSS, compass, power, rain and vibration tests, and the manufacturing files have no unexplained ERC/DRC errors.

For detailed components, structure, deliverables and gates see the [in-house integrated hardware and enclosure execution plan](CUSTOM_HARDWARE_PLAN.en.md).

## Risks that must be verified as early as possible

### Coordinate systems and road matching

GNSS normally outputs WGS84, while AMap route data uses GCJ-02. Coordinate conversion must sit on a clear, testable boundary, and every position sample and route object must carry a coordinate-system identifier; mixing them by default convention is forbidden.

Mathematical conversion alone is still not enough to solve elevated road levels, parallel roads and urban canyons. Phase 7 must use real-world Chinese road logs to verify a matching strategy in which direction, speed, positioning accuracy and route topology all take part.

### AMap licensing, quota and productisation

Using normal driving routes during the prototype stage only represents technical feasibility; it does not automatically mean they can be used in production hardware or a commercial service. Before productisation you must confirm with AMap and keep the written conclusion, covering at least:

- whether the in-vehicle / hardware terminal scenario requires additional licensing;
- whether route, traffic and POI data may be transmitted to the device, how long it may be cached and whether it may be used offline;
- the admission, billing, call quota and display requirements of the normal driving and motorcycle route services;
- the boundaries of coordinate conversion, derived data, log retention and privacy compliance.

If the licence does not allow raw routes to be stored long term, the internal protocol and the caching strategy must be tightened according to the licence. No demo key or personal quota may go directly into a production solution.

### iPhone hotspot stability

The ESP32-S3 uses 2.4 GHz Wi-Fi. You must test with a real iPhone screen-off, locked, on an incoming call, on a weak signal, switching mobile networks and briefly disconnecting the hotspot, rather than only verifying a desk scenario with the screen always on. The device side should have keep-alive, automatic reconnection, exponential backoff, cached navigation and an explicit offline state; "stable" is judged by long-duration measurements and must not be claimed on the basis of the interface capabilities alone.
