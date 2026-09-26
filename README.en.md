> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS · Waveshare Edition

[Glimpse website](https://maler.top/) · [2026-09-16 update](docs/UPDATES_2026-09-16.en.md) · [Web debugging entry](https://maler.top/moto-gps/ride.html)

![MOTO GPS round-display motorcycle navigation terminal](assets/brand/moto-gps-cover.png)

**Want to build the Waveshare edition yourself? Start with the [buying, firmware flashing and iPhone installation guide](docs/WAVESHARE_DIY_GUIDE.en.md).**

[Features and user manual](docs/USER_MANUAL.en.md) · [Live navigation gateway configuration](docs/GATEWAY_SETUP.en.md)

**Using an Android phone?** [Build an Android version with AI](docs/ANDROID_AI_GUIDE.en.md): a copyable development prompt, BLE and navigation adaptation, testing and GitHub release steps. There is no ready-to-install Android app yet.

**Help build Glimpse.** If you build an Android app from this project, we'd love you to share its source
on GitHub so others can learn from it, use it and help maintain it. Start a conversation in
[Issues](https://github.com/mx3353672833-debug/moto-gps-waveshare/issues), then contribute code, fixes or
documentation through Pull Requests with build instructions and tested-device details. We'll retain
contributor attribution, and the existing project licence still applies. See the
[Android collaboration invitation](docs/ANDROID_AI_GUIDE.en.md#lets-build-the-android-version-together) for ways to join in.

The source is provided for you to compile and install yourself; there is no prebuilt firmware or
App Store / TestFlight download published from this repository yet.
**iOS 0.3.0 (5) includes release preparation changes and is not yet distributed through TestFlight.**
The guide covers device selection, factory backup, Xcode personal signing, pairing, the demo and
frequently asked questions.

**Author: Maler X · Attribution / noncommercial use · Experimental prototype**

A round display on the handlebar, an iPhone in your bag. The phone handles positioning, search and
route calculation, while the round display shows terse navigation, speed and relative heading over
Bluetooth and controls Apple Music.

The project is built on the **Waveshare ESP32-S3-Touch-AMOLED-1.75C** and includes round-display
firmware, an iOS app, a route gateway and a web debugging tool.

The repository also collects the in-house circuit board, the V3 enclosure, manufacturing review
material and the complete technical proposal.
The version running today is still the Waveshare production board plus an iPhone; the in-house part
is archived as historical engineering candidates, and the versions are listed at the entry points
below.
Work on the B1 phone companion board and V3 enclosure is paused; the current focus is improving the Waveshare edition.
The first image is a product rendering; for physical and manufacturing dimensions rely on the
engineering files of the matching version.

Before setting off you search for a destination on the phone and compare routes; once navigation
starts, the round display on the handlebar shows the junction ahead, the turn action and the
distance. The map moves around a fixed heading arrow, with grey roads and buildings for surrounding
context.
Swipe the screen to view speed, heading, or to control Apple Music.

[Feature details](#feature-details) · [Using it](#using-it) · [Hardware and development environment](#hardware-and-development-environment) ·
[Installation and configuration](#installation-and-configuration) · [Development and testing](#development-and-testing) · [License and attribution](#license-and-attribution)

[Product specifications](docs/PRODUCT_SPECIFICATIONS.en.md) · [Circuit board and enclosure](hardware/README.en.md) ·
[Technical proposal PDF / DOCX](docs/technical-proposal/README.en.md) · [Documentation index](docs/PROJECT_DOCUMENTATION.en.md)

## Board, enclosure and product documentation

| Material | What it contains | Entry point |
| --- | --- | --- |
| Product description and technical parameters | Waveshare prototype compared with the in-house A1, display and communication, mechanical dimensions, data sources and verification status | [Product specifications](docs/PRODUCT_SPECIFICATIONS.en.md) |
| H0175 EVT A1 in-house main board | KiCad schematic and four-layer PCB, project symbol and footprint libraries, BOM/CPL, Gerber and drill files, assembly drawings, ERC/DRC and SHA-256 | [Full A1 review package](hardware/manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/README_DO_NOT_ORDER.en.md) |
| Rev A0 R4 main board | The routing baseline before A1 with the complete electrical review material, keeping the earlier revision for comparison | [R4 review package](hardware/manufacturing/engineering-candidates/rev-a0-20260903-r4/README_DO_NOT_ORDER.en.md) |
| V3 in-house enclosure | Parametric CAD, front bezel / rear shell / mount in STEP and STL, assembly model, 2D drawings, mechanical specification and appearance renders | [V3 file notes](hardware/mechanical/README.md) |
| Technical proposal | H0175 EVT A1 and Rev A0 in Markdown, PDF and editable Word, with system architecture and product figures | [Read and download](docs/technical-proposal/README.en.md) |
| Review and generation tools | Component footprint audit, electrical checks, candidate package export scripts and hardware release check tests | [Hardware tools](scripts/hardware/README.md) |

The in-house A1 uses a Ø52 mm four-layer board; the historical design integrated an ESP32-S3, an
LC76G, an IMU, a magnetometer and charging power.
The V3 enclosure has a nominal body of Ø61 × 16 mm, about 19 mm including the mount. These
parameters belong to the in-house design material and are listed separately from the dimensions and
component configuration of the current Waveshare board. Both A1 and R4 are engineering review
versions and keep their original `DO NOT ORDER` marking; publishing the material does not mean
prototyping has resumed or production acceptance has been completed.

![H0175 EVT A1 in-house main board, top preview](hardware/manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/previews/pcb-top-3d.png)

![V3 enclosure assembly model](hardware/mechanical/generated/v3/previews/v3-isometric.png)

## What it does today

| Module | What it does |
| --- | --- |
| Round-display navigation | White route line, next-manoeuvre icon and distance; live road speed limits and traffic-light countdown are not connected |
| iPhone | Native navigation bars and grouped lists, light/dark appearance and large text, location-biased search, history, route preview and selection |
| Navigation logic | Shared C++ core: route progress, off-route detection, online rerouting and periodic route / traffic refresh |
| Grey roads / buildings | Surrounding OSM / Protomaps maps load online by default; city, district and route-corridor downloads, with the bundled Jinan map retained as a fallback |
| Speedometer / heading | Phone positioning provides speed and direction of travel; the on-board QMI8658 assists with relative turning |
| Music | Apple Music previous track, play/pause, next track |
| Animation / touch | Black-and-white logo fade in and out, connection-state transitions, swipe page changes, auto-hiding page dots, long-press PWR to power off |
| Demo | Near Building D of the Jinan Big Data Industry Base → near Inspur headquarters; online requests and the OSM offline fallback are explicitly distinguished |

## Feature details

### Round-display navigation: see the next junction clearly

The navigation page is black-based, with the white route and turn prompts as the focus of the
screen. In the 466×466 round display area, every element has a definite meaning:

| Element on screen | What it expresses |
| --- | --- |
| Fixed heading arrow | Visual anchor for the current position, pointing to the top of the screen |
| Thick white line | Where the active route goes nearby |
| Thin grey lines | The actual geometry of surrounding roads, to help identify junctions, side roads and how roads relate |
| Grey building blocks | Building outlines recorded in OSM, as a reference for blocks and campuses |
| Manoeuvre icon at the lower left | The action at the next junction: straight on, turn left, turn right, U-turn and so on |
| Large distance next to the action | How far it is to that action, switching between `m` / `km` by distance |
| Speed-limit sign | Display support exists, but live road speed limits are not connected; hidden when data is missing |
| Arc at the bottom | Route completion progress, its colour changing with the current traffic state |

For example, a right-turn arrow and `300 m` on screen mean that you turn right after about 300
metres along the current route. As you approach the junction the distance counts down; once you are
through, the icon and the number update to the next instruction.

While riding and turning, the route, the surrounding roads and the buildings use the same set of
position, heading and zoom parameters.
The heading arrow stays fixed while the map pans and rotates beneath it, so you can watch the road
ahead continuously.
The round display focuses on the current section; the whole route before departure is shown on the
iPhone preview map.

### Place search and route selection

The iPhone app uses AMap place search and the standard driving route service. Searches carry the
phone's current position and prefer nearby, more relevant places. In Jinan you can start a search
with a keyword such as "Olympic Sports Center", and you can also enter a place elsewhere together
with its city name.
These are standard driving routes and do not guarantee avoidance of motorcycle-restricted roads.

Search and route selection work like this:

- Typing at least two characters starts a search; consecutive typing merges requests and the results
  update to the latest keyword.
- Search results show the place name, the address or the area it is in; the distance can be shown
  when a current position is available.
- Selecting a place saves it to a recent-places list, kept in order of use up to **8 entries** and
  retained after the app restarts.
- Tapping a recent place plans a route there again, and the history can be cleared in one action.
- The origin position is refreshed before planning, and candidate routes are then requested from the
  gateway.
- **Up to 3 routes** are offered, following what AMap actually returns, shown as a recommended route
  and alternative routes.

The route confirmation page shows the origin, the destination and the whole route. The selected
route is highlighted and the other candidates are drawn in lighter colours.
The cards below list each route's distance, estimated time and a per-segment traffic summary, such
as "traffic flowing" or "slower on part of the route".
Tapping a card switches plan; confirm and then tap "Start navigation".

Starting hands the selected route to the navigation core; if the origin has moved noticeably since
the preview, the app replans from the current position. Agreement between the route confirmation
and what the real hardware displays is one of the items under continuous regression testing.

### Navigation updates while riding

The navigation core updates route progress, the distance to the next action, the remaining distance
and the estimated time left, from continuous positioning.
The phone's navigation page also shows the destination, the round-display connection state, the
positioning state and the current traffic conditions.

Off-route detection combines the deviation distance with confirmation from consecutive fixes. Once
the conditions are met the app replans and syncs the guidance to the round display when the new
route arrives. Traffic conditions refresh on a periodic request; the core defaults to roughly one
update every 60 seconds, and the actual request cadence also depends on network conditions, retries
and service quota.
The route is still drawn in white; per-segment colouring and the display of unknown or stale traffic data remain in progress.
Traffic-light countdown and trustworthy current-road speed-limit data are not connected.

Positioning quality, request IDs and route versions all take part in state updates. The phone keeps
the current navigation state, and after the round display reconnects and completes the protocol
handshake the app tries to resend the latest route and display data.
Progress on verifying background connections and cross-city routes is collected under
[Development status](#development-status).

### Online surrounding maps and offline downloads

The background mini-map uses OpenStreetMap / Protomaps roads and buildings. With a working gateway
configured, navigation loads surrounding maps online by default and saves visited areas; local data
is reused when the network is unavailable. Maps are no longer limited to the bundled Jinan data,
but download availability and local road/building coverage depend on the source and the network.

Open "地图与离线下载" (Maps and Offline Downloads) on the iPhone home screen:

- "下载城市地图" (Download City Map) searches cities or districts and previews the area before downloading; start with a district for large areas.
- After selecting a route, "下载这条路线周边" (Download This Route's Surroundings) saves a corridor of approximately one kilometre around it.
- Downloads can be paused, resumed and removed; automatic cache is limited to 128 MB and manual downloads to 512 MB, with shared tiles stored once.
- Offline background maps do not include live traffic; place search, new route planning and online rerouting still need a network connection.

The original Jinan SQLite database remains bundled with the app as a local fallback within its coverage:

| Currently bundled data set | Count |
| --- | ---: |
| File size | 9.73 MiB |
| Road polylines | 47,468 |
| Road vertices | 266,985 |
| Building outlines | 26,702 |
| Building vertices | 148,140 |

During navigation the phone selects roads and buildings within roughly 500 metres of its position
from online tiles, saved maps or the available Jinan fallback, then sends them to the display over
BLE. The query window moves with the position; the display does not store the complete city map.

A single display window currently holds at most 24 background roads with 192 road points, and 16
buildings with 128 building points.
This division of labour lets the phone keep the complete area data while the round display draws the
current view. Map density depends on local OSM coverage, the feature-filtering strategy and window
capacity; areas with more complete building coverage show richer block detail.

Route planning and traffic come from online AMap services, the display's grey context layer comes
from OSM / Protomaps, and Apple Maps supplies the phone's whole-route preview background. These
are separate data layers; downloading a background map does not provide offline navigation.
Long-term deployments need stable data sources with appropriate usage permission, retaining OSM
attribution and licence links. See the [recent update](docs/UPDATES_2026-09-16.en.md) for the new
flow and the [offline map notes](shared/offline_map/README.en.md) for the original database format
and rebuild tools.

### Speedometer and heading pages

The speedometer page shows the current speed as a large number in `km/h`, with an outer scale and an
arc that change with speed.
The page is designed around live speed and is meant to be read quickly on the round display.

The heading page shows the angle, the compass letter, a rotating scale and the current speed. Phone
positioning provides the direction of travel, and the QMI8658 six-axis sensor on the round display
provides short-term angular rate to compensate the displayed changes while turning.

With the phone in a bag, the direction of travel comes from `CLLocation.course` produced by
movement; the relative rotation on the round display comes from the device's own gyroscope. At low
speed or at rest, unreliable heading changes are frozen and residual speed is suppressed; the
positioning heading gradually corrects it once you are moving again. An absolute north reference at rest is part of a
later magnetometer extension.

### Apple Music control

The music page controls Apple Music through the iPhone's system music player, showing the music
source, the track title, the artist and the playback state. The round display offers three buttons:
previous track, play/pause and next track.

On first use, grant media library access on the iPhone and prepare a playback queue in Apple Music.
Touching a button on the round display sends the command to the phone over BLE; after the player
handles the action, the latest track and state are sent back to the round display. The phone's
authorisation state and the player state determine whether the music feature is available.

Audio keeps using the iPhone's current output, for example a helmet Bluetooth headset. The round
display takes on display and remote control, which suits it to working alongside however you
already play music from the phone.

### Boot, connection and touch interaction

On power-up a black-and-white `MOTO GPS` logo appears first, fading in, holding and fading out into
the connection screen.
The panel lights up once a pure black first frame is ready, which reduces the startup flash.

```text
boot logo → ready to connect / connecting → green connected → ready to ride → navigation screen
```

While waiting to connect the screen shows a connection graphic and animation; once the protocol
handshake completes, the green success state holds for about 0.9 seconds before the ready-to-ride
page, which prompts you to choose a destination on the phone. The page keeps switching with state
as planning starts and the route arrives.

- Swipe left and right to switch between the navigation, speedometer, heading and music pages; the
  music page is available according to the phone's capabilities.
- Dots at the bottom identify the current page and hide automatically after five seconds.
- The dots reappear after a touch or a page change.
- Holding the side PWR button for about three seconds shows the power-off screen and requests
  shutdown from the AXP2101; the USB-powered case also has deep-sleep handling.

### Demo navigation

The app offers a "demo navigation" entry point that uses the following public places as a fixed
demo scenario:

**Near Building D of the Jinan Big Data Industry Base → near Inspur Group headquarters.**

The online demo first requests a live AMap route and then generates simulated positions continuously
along the returned route geometry.
The offline fallback uses an OSM route provided with the repository, running through campus lanes,
Xinluo Avenue, Chonghua Road and Inspur Road for about 1.49 kilometres in total. Demo positions also
pass through the navigation core, the BLE protocol and the round-display UI.

Sitting at a desk you can watch how the manoeuvre icons correspond to the distances, the map
scrolling, rotation while turning, the surrounding grey roads and buildings, and you can also check
connection-state transitions and page layout. The web tool provides a browser preview of the same
UI.
For where the demo roads come from, how the data is generated and the OSM attribution see the
[demo fixture notes](shared/demo_fixture/README.en.md).

## Using it

After the first install and configuration, a navigation session goes in this order:

1. Power on the round display and open MOTO GPS on the iPhone.
2. Check the connection state; on first pairing follow the iOS prompts to complete Bluetooth pairing
   and grant permissions.
3. Enter a destination, or choose a destination from recent places.
4. Look at the route on the whole-route map and compare the candidate cards by distance, duration
   and traffic conditions.
5. Select a route and tap "Start navigation"; the phone starts updating position and the round
   display shows the current section and the next action.
6. When stopped you can swipe through the speedometer, heading and music pages and change tracks as
   needed.
7. On arrival check the arrival state and end navigation on the phone; when putting the bike away,
   hold PWR on the round display to power it off.

The iPhone provides network access and positioning, and the round display connects to the phone over
BLE. Background connection reliability is still being verified, so first complete screen lock,
connection recovery and state sync tests in a safe, stationary setting.
Do your debugging and screen interaction while stopped, and while riding cross-check the route
against a mature navigation app.

## Hardware and development environment

### Device hardware

| Item | Currently supported |
| --- | --- |
| Development board | Waveshare ESP32-S3-Touch-AMOLED-1.75C |
| MCU | ESP32-S3 |
| Storage | 32 MB Flash, 8 MB PSRAM |
| Display | 1.75-inch round AMOLED, 466×466 |
| Display driver | CO5300, QSPI, RGB565 |
| Touch | CST9217 capacitive touch |
| Motion sensor | QMI8658 six-axis IMU |
| Power management | AXP2101, USB-C / matching lithium battery |
| Phone connection | BLE, custom navigation GATT service |

Prepare a USB-C cable that supports data transfer. When using a battery, choose one according to
Waveshare's specification, connector and polarity requirements for the **1.75C**. Check the complete
board model before flashing; board-level connections and firmware steps are in the
[ESP32 notes](platforms/esp32/README.en.md).

### Software tools

| Purpose | Environment |
| --- | --- |
| iPhone app | iOS 17+; Mac, Xcode 16+ / Swift 6 toolchain, XcodeGen |
| Round-display firmware | ESP-IDF **5.5.5**, Waveshare BSP **3.0.0** |
| Shared UI | LVGL at the **pinned commit for 9.5.0** |
| Route gateway | Node.js 20+; Node.js 24+ recommended to run the configuration examples in this document |
| C++ tests | CMake, a compiler with C++17 support |
| Web preview | Emscripten, CMake, an HTTP static server |
| Map rebuild | Node.js 24+, osmium-tool, jq |

Live navigation requires configuring your own AMap key, HTTPS gateway and Apple developer signature.

## Installation and configuration

### 1. Get the source

```sh
git clone --recurse-submodules https://github.com/mx3353672833-debug/moto-gps-waveshare.git
cd moto-gps-waveshare
```

That command also fetches the pinned version of LVGL. When using GitHub's Download ZIP, you must
additionally put the source of LVGL commit `85aa60d18b3d5e5588d7b247abf90198f07c8a63` into
`third_party/lvgl/`.

The commands below all start from the repository root.

### 2. Configure the route gateway

The gateway turns the app's search and route requests into AMap Web Service requests and returns
navigation data in a unified format.
The AMap key is held in a server-side environment variable.

Run the backend's automated tests first, then create your own configuration file:

```sh
npm ci --prefix backend
npm --prefix backend test
cp backend/.env.example backend/.env
```

Edit `backend/.env`:

| Setting | What to fill in |
| --- | --- |
| `MOTO_PROVIDER` | `amap` for live navigation; `fixture` for local protocol tests; `disabled` for a service not configured yet |
| `AMAP_WEB_SERVICE_KEY` | The AMap Web Service key you applied for yourself |
| `PORT` | Port the backend listens on, `8787` by default |
| `WEB_ORIGIN` | The web page's actual origin when you use the web tool |
| `MOTO_MAP_PMTILES_URL` | `auto` selects a compatible Protomaps build; alternatively set your own HTTPS PMTiles URL, or `disabled` to turn online maps off |
| `MOTO_MAP_CACHE_DIR` | Map cache directory, `.cache/map-tiles` by default |
| `MOTO_MAP_CACHE_MAX_BYTES` | Server cache size limit in bytes, `1073741824` by default |

Start it with Node.js 24+, loading the configuration explicitly:

```sh
node --env-file=backend/.env backend/src/server.js
```

The service listens on `127.0.0.1:8787` by default. When deploying, forward `/moto-gps/api/` to this
service with your own HTTPS reverse proxy so the phone can reach it.
Check that `provider` in `/healthz` is `amap` and `ready_for_live_navigation` is `true`, then
complete one real place search and route request to confirm account permissions, quota and network.

For deployment to the public internet, configure access control, TLS, quota limits and log
redaction.
The complete API and deployment details are in the
[route gateway documentation](backend/README.en.md).

### 3. Build and install the iPhone app

Edit `platforms/ios/project.yml` and set:

- `MOTOGPSGatewayBaseURL`: your own HTTPS gateway address, for example `https://YOUR-DOMAIN/moto-gps/api/`.
- `PRODUCT_BUNDLE_IDENTIFIER`: a unique identifier you can use; set it separately for the app and the two test targets.
- `DEVELOPMENT_TEAM`: your own signing team, or choose it in Xcode.

The app's default gateway is the example address `https://example.invalid/moto-gps/api/`, which you
replace with your own service before use.
The configuration source is `project.yml`, and XcodeGen updates the project and `Info.plist` from it.

```sh
(cd platforms/ios && xcodegen generate)
open platforms/ios/MotoGPS.xcodeproj
```

Connect and trust your iPhone, enable developer mode when Xcode prompts, select the MotoGPS scheme,
your own device and signing team, then click Run to install.
On first run, allow location, precise location, Bluetooth and the required media permissions when
prompted. Detailed steps and signing notes are in the [iOS documentation](platforms/ios/README.en.md).

### 4. Build and flash the round display

After installing and activating the ESP-IDF 5.5.5 environment, run:

```sh
idf.py -C platforms/esp32 set-target esp32s3
idf.py -C platforms/esp32 build
```

The build fetches the pinned dependencies and produces the application, bootloader and partition
table in `platforms/esp32/build/`.
Before flashing for the first time, follow the
[firmware documentation's backup steps](platforms/esp32/README.en.md#backup-and-flashing) to check
the model, Flash size and security state, and save a complete factory backup.

Once you have confirmed the backup is complete and accept replacing the factory application,
replace `PORT` with the actual USB serial port:

```sh
idf.py -C platforms/esp32 -p PORT flash monitor
```

The device advertises as `MOTO GPS` after it boots. Open the iPhone app to complete pairing and the
handshake, and once the round display reaches the ready-to-ride page you can choose a route
following the usage flow above.

### 5. Preview the UI in a browser

After installing and activating Emscripten, build the shared LVGL WebAssembly runtime:

```sh
./scripts/build_web.sh
python3 -m http.server 4173 --directory .
```

Open in a browser:

```text
http://127.0.0.1:4173/platforms/web/shell/index.html?demo=1
```

You can also preview the connection states individually by changing the query parameter to
`?deviceState=connecting`, `?deviceState=success`, `?deviceState=ready` or `?deviceState=planning`.
These states render with the shared LVGL UI, which makes them useful for checking animations and
layout during development.

The [web debugging tool](platforms/web/shell/README.en.md) is available for viewing and debugging
the UI; keep the page in the foreground with the screen on while using it.
The hosted debugging entry is [/moto-gps/ride.html](https://maler.top/moto-gps/ride.html);
The [Glimpse website](https://maler.top/) is at the domain root. The website itself is not open source.
Background positioning and the BLE connection on the phone are handled by the iOS app, and the
current verification progress is in [Known issues](docs/KNOWN_ISSUES.en.md).

## How it works

The iPhone holds the position, complete route, map downloads and cache, while the ESP32 receives
display state, handles local motion input, draws the round display and returns touch commands. The
backend handles AMap service calls, Protomaps reads, format conversion and caching.

```text
AMap Web Service ────┐
OSM / Protomaps ─────┴→ HTTPS gateway and cache ←→ iPhone app
                                       │
            Core Location + shared navigation core + online / offline maps
                                       │ BLE
                                       ▼
               ESP32 receives state + QMI8658 relative turning
                                       │
                        NavPresenter → LVGL → round display

round-display touch / music controls ───── BLE ─────→ iPhone
```

### Shared navigation and UI

Route progress, off-route state and display data structures live in shared C++ modules. iOS calls
the navigation core through an Objective-C++ bridge; web and ESP32 compile `NavPresenter` and the
LVGL UI together.
When you adjust the round-display layout, the manoeuvre icons or the map drawing, you can observe
the result on the web first and then verify it on real hardware.

The complete route is kept on the phone, and the round display receives the route geometry and the
surrounding context window needed for the current view.
Request positions use WGS84 and navigation geometry is unified as GCJ-02; the map preview converts
coordinates according to each platform's requirements.
The relevant conventions are in the [architecture notes](docs/ARCHITECTURE.en.md) and the
[route protocol](shared/protocol/README.en.md).

### BLE data and connection

The device works as a GATT peripheral and the iPhone connects as the central.
The protocol covers connection state, heartbeat, navigation snapshots, route geometry, traffic
conditions, road / building windows, media state and device commands.

The transport layer implements fragmentation and reassembly, CRC checks, message sequence numbers,
session isolation, ACKs for control commands and retries on timeout.
Navigation snapshots from the phone to the round display are scheduled at up to 5 Hz, and the
terminal interpolates position and turning animation further.
Pairing uses an encrypted BLE connection with bonding; connection recovery is still under continuous
testing.
Heartbeats send only the elapsed time of the current app session, not the phone's system uptime.
GATT UUIDs, byte formats and golden fixtures are in the
[BLE protocol](shared/protocol/ble-navigation-v1.en.md).

### Display and smoothness

The firmware uses PSRAM draw buffers and direct DMA, so that LVGL drawing and QSPI transfer
follow on from each other.
The CO5300's TE signal is used to synchronise the start of frames, and the map, interpolation and
rendering tasks are coordinated on a 25 ms target tick.
The actual frame rate, tearing during fast turns and power draw over a long run are still judged by
measurement and observation on real hardware.

## Source layout

```text
platforms/ios      iPhone positioning, route preview, BLE, Apple Music, online / offline maps
backend            Node.js AMap and Protomaps gateway, cache (the key stays on the server)
platforms/esp32    Waveshare board support, BLE, QMI8658, display and power
shared             shared navigation core, protocol, LVGL UI, OSM demo and map data
platforms/web      LVGL / Wasm debugging shell
website            Glimpse website source and deployment notes
tests              native C++ tests
scripts            web build, font and OSM map tools
```

See [Architecture and data boundaries](docs/ARCHITECTURE.en.md), [Testing](docs/TESTING.en.md),
[Known issues](docs/KNOWN_ISSUES.en.md) and
[Third-party notices](THIRD_PARTY_NOTICES.en.md) for details.

## Development and testing

### Run the automated tests

From the repository root:

```sh
cmake -S . -B build/native -DMOTO_BUILD_WEB=OFF -DMOTO_BUILD_TESTS=ON
cmake --build build/native --parallel 4
ctest --test-dir build/native --output-on-failure

npm ci --prefix backend
npm --prefix backend test
swift test --package-path platforms/ios

node scripts/generate_jinan_demo_fixture.mjs --check
node scripts/offline_map/validate_jinan_sqlite.mjs
```

The 2026-09-16 round passed 9 native C++ test suites, 60 backend tests and 12 Swift core tests,
with a recorded pass of 13 BLE policy tests. Demo fixture consistency and map database integrity
checks also passed. These results verify code and data paths, not completed road, extended
lock-screen or battery-life acceptance.
See the [recent update](docs/UPDATES_2026-09-16.en.md) for this round's scope; the first public
snapshot's historical results remain in the [release check record](docs/RELEASE_CHECKS.en.md), and
checks after submission are on
[GitHub Actions](https://github.com/mx3353672833-debug/moto-gps-waveshare/actions/workflows/checks.yml).

### Where to start making changes

| What you want to change | Main entry point |
| --- | --- |
| Round-display layout, colours, icons and state animations | `shared/nav_ui/src/moto_nav_ui.cpp` |
| Map projection and display data conversion | `shared/nav_presenter/src/moto_nav_presenter.cpp` |
| Default off-route, arrival, position validity and traffic refresh settings | `NavCoreConfig` in `shared/nav_core/nav_core.hpp` |
| Phone search, history and route confirmation flow | `platforms/ios/App/AppModel.swift`, `ContentView.swift` |
| Whole-route map and candidate display | `platforms/ios/App/RouteOverviewMap.swift` |
| BLE handshake, send scheduling and reconnection | `ESP32BLECentral.swift` on iOS and `ble_nav_transport_nimble.cpp` on ESP32 |
| Road and building filtering for the current window | `platforms/ios/App/Adapters/OfflineMap/` |
| Apple Music remote control | `platforms/ios/App/Adapters/Media/AppleMusicRemoteController.swift` |
| CO5300 display, TE synchronisation and power | `platforms/esp32/main/board_port_waveshare_1_75c.cpp` |
| Map data rebuild | `scripts/offline_map/` |

When you change the protocol, update both implementations and the golden fixture together; when you
change the map, keep the OSM source, licence and attribution.
The contribution process is in [CONTRIBUTING](CONTRIBUTING.en.md).

## Development status

The current version is at the real-hardware verification stage, and the next round of regression
focuses on:

- BLE connection recovery after the iPhone locks, switches network or leaves the original
  environment.
- Cross-city candidate route generation, and agreement between the route selected on the phone and
  what the round display shows.
- Online/offline map transitions, interrupted downloads, and per-segment traffic and stale-data display.
- Refresh continuity during fast turns, TE synchronisation behaviour and sustained frame rate.
- Power-off, wake, battery life and temperature rise on USB, battery and combined supply.

The feature descriptions describe what the source currently implements; see the
[recent update](docs/UPDATES_2026-09-16.en.md) for the latest release and verification limits, and
[Known issues](docs/KNOWN_ISSUES.en.md) for historical issues.
When reporting a problem, include the board model, firmware version, iOS version, reproduction steps
and redacted logs, so that positioning, gateway, protocol and display issues can be told apart.

## License and attribution

Original project code and artwork use the **[PolyForm Noncommercial 1.0.0](LICENSE.md)**.
Personal study, research, noncommercial use and modification are permitted within the scope of the
licence; when distributing the source, a modified version, firmware or an app build you must carry
the licence and the author notice in [NOTICE](NOTICE), naming **Maler X** and this repository as the
source.
The app's idle screen and the web status page also provide a visible attribution entry point.

**Noncommercial use only.** Selling preloaded devices, distributing firmware for a fee, or using the
project in a commercial product or a paid service requires separate commercial authorisation from
the author. The exact scope of the licence is defined by the text of LICENSE.md.

The licence type is **source-available (public source, noncommercial licence)**.
Third-party libraries, fonts and OSM data each follow their own licences; map services must be used
under the provider's terms.

© 2026 Maler X · Map data © OpenStreetMap contributors (ODbL 1.0).
