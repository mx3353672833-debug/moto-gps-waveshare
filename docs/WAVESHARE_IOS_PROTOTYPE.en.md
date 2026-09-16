> **Language:** English · [中文](WAVESHARE_IOS_PROTOTYPE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Waveshare 1.75C + iPhone navigation prototype implementation baseline

## 1. Current prototype boundaries

This stage does not make an in-house PCB and does not install a standalone GNSS. The hardware uses
the already-purchased `Waveshare ESP32-S3-Touch-AMOLED-1.75C`, and the phone side uses a native iOS
app.

- The iPhone is the single authoritative source of navigation data: continuous positioning, speed /
  direction of travel, AMap routes, traffic refresh and off-route rerouting all happen on the phone
  side.
- The ESP32 is the deterministic instrument side: it receives versioned BLE data and uses the shared
  `NavPresenter + LVGL` to render the four pages: navigation, speedometer, compass and music.
- The web page remains a quick debugging entry point for the same 466 × 466 LVGL screens, and no
  longer carries the responsibility of keeping navigation running after the screen locks, which it
  cannot do.
- After a complete backup of the factory 32 MB Flash, the first real-hardware flash and boot
  verification has been completed. The current App beta provides a clearly labelled built-in
  navigation demo, and a long press on the firmware's navigation page can also toggle the on-device
  demo route. The App's online demo and normal navigation request a live AMap route for each run; the
  web build, the ESP32 on-device demo and the App's offline fallback use the real OSM fixture inside
  the project, and AMap responses are not written into the source or the fixture.

## 2. Data and control chain

```text
iPhone CoreLocation
        │  WGS84 fix / speed / course
        ▼
SharedNavigationRuntime / shared NavApp + NavCore
        │                       └── HTTPS ──> MOTO Gateway ──> AMap Web Service
        │                                  │
        │  route / traffic / reroute       └─ the key stays only on the server
        ▼
BLE Display Snapshot + GCJ-02 route window
        │
        ▼
ESP32 BLE adapter → NavSnapshot → NavPresenter → LVGL 466×466 → CO5300
        ▲                                                     │
        └──────── DeviceCommand (page change / media keys) ───┘
```

The ESP32 does not request AMap again and does not maintain a second route state machine. That way,
after a disconnection and reconnection the iPhone only has to resend the latest complete snapshot and
the current local route window, and the instrument can recover without depending on some lost
incremental event.

The current native App also obtains a separate phone position for place-search biasing: the gateway
prefers POIs within 50 km of the current position, and falls back to a nationwide / regional text
search when there are no near-field results. That one-off search position source does not replace the
high-accuracy, background-capable Core Location data source used after navigation starts.

The demo's two ends are fixed as "near Building D of the Shandong Big Data Industry Base → near
Inspur Group (headquarters)". When iOS is online it uses these two POIs to request AMap live each
time and advances along that run's polyline; only when the network is down does it fall back to the
canonical OSM route with 35 route points and about 1.49 km. The OSM endpoints are about 70 m from the
Building D POI and about 53 m from the Inspur entrance, and the wording only says "near", without
patching the missing entrance connections into roads. The grey background likewise comes from OSM,
with 192 points in 24 separate road spans and about 9.98 km in total; it is still the real road window
of that demo corridor, and does not mean the whole Jinan map is already bundled.

## 3. Data sources of the four pages

| Page | What it shows | Data source | Behaviour without the phone |
|---|---|---|---|
| Navigation | The standard action icons for a valid route, the large distance number + unit, the fixed heading arrow, grey surrounding roads, the white selected route, and the speed limit and traffic conditions when there is trustworthy data | iOS + the authorised road data source | Shows the connection lifecycle; only an explicitly enabled on-device Demo Ride keeps demonstrating with the OSM route, otherwise the old action, distance, route progress and speed limit are not shown |
| Speedometer | The current speed | iPhone `CLLocation.speed` | Zeroes after a timeout and is marked stale |
| Compass | The current direction of travel heading and bearing | iPhone `CLLocation.course` while moving | True north cannot be guaranteed at rest |
| Music | Apple Music track title / artist and previous track, play/pause, next track | The iOS `systemMusicPlayer` adapter | Keeps the page but does not fabricate track information when unauthorised or with no queue |

The on-board QMI8658 is a six-axis IMU, not a magnetometer. It can detect attitude, vibration and
rotation, but cannot give absolute north, south, east and west on its own. The prototype uses GNSS
course while riding, which exactly meets the core requirement that "the arrow always faces the front
of the bike and the roads rotate as you turn"; if reliable true north at rest is required later, add
an LIS2MDL/IIS2MDC and do a full-device magnetic calibration.

## Current startup and test interaction

- iOS uses a black-background white `MOTO GPS` Launch Screen; on power-up the ESP32 plays a fade-in,
  hold and fade-out of the same-named logo on a 1.25-second cadence.
- The round-display page indicator dots appear after power-up, a touch or a page change, and hide
  automatically after 5 seconds of no operation, so that they do not occupy the rider's view for
  long.
- A long press on the navigation page toggles the ESP32 on-device demo route; the demo snapshot and
  the route geometry still go through the official `NavPresenter → LVGL` path.
- The App beta's "Demo navigation" requests the AMap route from Building D to the Inspur headquarters
  live when online and falls back to the canonical OSM route when that fails; both paths go through
  the shared `NavApp → NavCore → BLE` chain. The actual "Start navigation" uses the real POI selected
  in search, the real phone position and a live online route.
- The distance area shows no explanatory title, only the large number and the `m` / `km` unit; this
  value is the distance to the next turn, U-turn or arrival action, not the total distance remaining.
- With no real route and the demo not explicitly enabled, the action icon, the distance, the circular
  route progress and the speed-limit sign are all hidden; a straight-on icon, `0 m` or a test speed
  limit must not be used to disguise it as workable navigation.
- The canonical offline Demo Ride uses the real OSM roads from near Building D to near the Inspur
  headquarters. The white selected route and the grey background roads both come from verifiable,
  physically connected OpenStreetMap ways, with the WGS84 coordinates uniformly converted to GCJ-02
  and drawn in the same scene. The demo must keep the `© OpenStreetMap contributors` attribution and
  comply with ODbL; see the [demo data notes](../shared/demo_fixture/README.en.md) for details. Only
  the iOS online demo and live navigation request AMap live, and the responses are not pinned into
  the project.
- The ordinary AMap route API returns only the selected route, not the complete surrounding road
  network. A production online grey road layer needs the AMap "traffic status query" advanced API's
  `extensions=all` / `roads[].polyline`, and explicit written authorisation from AMap must be obtained
  before projecting it in a vehicle or smart hardware; when it is not enabled or not authorised, only
  the real selected route is shown and no fake background roads are generated.
- The GATT establishment on iOS reconnection, out-of-order events, reliable handshake writes,
  handshake retries, old-session filtering and backoff timing have been fixed. On real hardware,
  3 consecutive App cold starts each completed the two-stage handshake in one attempt, with a
  negotiated frame length of 182; several hours of screen lock and repeated power cycling still need
  long tests against the real-hardware threshold.
- Holding the side PWR for 3 seconds shows the power-off screen and requests a software power-off
  from the AXP2101; if the firmware is stuck, holding on to 4 seconds lets the PMIC power it off in
  hardware. The actual power-off / wake behaviour on USB, battery and both connected at once still
  needs item-by-item measurement.

### Round-display connection lifecycle

When navigation is not yet available it no longer stays on a fake navigation page with `0 m`, and
instead the shared LVGL shows the complete state:

| Condition | Round-display text | Graphics and animation |
| --- | --- | --- |
| Phone offline | `CONNECT PHONE` / `Open the phone app` | Phone outline with a slow circular scan |
| BLE link set-up or protocol handshake | `CONNECTING` / `Establishing the connection` | The same phone graphic, with the scan speeded up |
| Going from offline to online | `CONNECTED` / `Connection succeeded` | A green circle and tick breathing gently, automatically moving to the next state after about 920 ms |
| Online but with no valid route | `READY TO RIDE` / `Choose a destination on the phone` | A black-and-white route start/end graphic breathing gently |
| `route_request_in_flight` | `BUILDING ROUTE` / `Building the route` | A white dot moving along the route graphic |
| A valid route already exists, or the on-device demo is explicitly enabled | The lifecycle overlay is hidden | The overlay fades out and the real navigation screen appears |

A normal state switch first fades the old content out over 140 ms and then fades the new content in
over 220 ms; when appearing directly from the hidden state only the fade-in runs. With Reduce Motion
enabled it switches immediately and pauses the 40 ms lifecycle animation clock. The connection-success
screen is triggered by a real `OFFLINE/CONNECTING → ONLINE` edge, not replayed by ordinary snapshot
refreshes. On the web you can pin the preview of each of the five overlays with
`?deviceState=offline|connecting|success|ready|planning`.

## 4. iOS background constraints

The native App declares the `location` and `bluetooth-central` background modes and uses continuous
location and BLE state restoration. During a normal screen lock, a switch to the background or the
phone being in a bag, the system can keep delivering location and BLE events. The following situations
cannot be avoided by the App:

- the user manually force-quitting the App from the multitasking interface;
- the user turning off location or Bluetooth, or revoking permissions;
- the phone running out of battery, or the system terminating the process because of abnormal
  resource use;
- the phone and the instrument going beyond BLE range.

The protocol therefore carries heartbeats, timeouts and complete snapshot recovery; when the ESP32 is
disconnected it must clearly show the state and must not keep treating an old speed or an old turn as
live data.

## 5. The realistic scope of the music feature

"Previous track / play-pause / next track" can be implemented as an additional capability; the safest
production path may need the ESP32 to also provide BLE HID Consumer Control. A third-party iOS app
cannot generically read complete track information for NetEase Cloud Music or for any player on the
system, nor generically "like" a track. The current version connects only to Apple Music's public
`systemMusicPlayer`: previous track, play/pause, next track and now-playing information; the protocol
still keeps the capability bit, but the App declares `like available = 0` and the round display hides
"Like". NetEase Cloud Music does not enter this stage for now.

## 6. This round's verification and the first real-hardware acceptance

Software and data checks already completed:

- [x] The web build, the ESP32 on-device demo and the iOS offline fallback use the same canonical OSM
  data from near Building D → near the Inspur headquarters; the C++ and Swift generation results
  agree.
- [x] The white route is 35 points and about 1.49 km; the grey roads are 192 points / 24 separate
  spans and about 9.98 km in total; the unsurveyed connections at the two ends are kept as gaps of
  about 70 m and 53 m and are not filled with a straight line.
- [x] The iOS online demo requests the same two POIs live through the existing gateway; the route
  measured this round was 1608 m / 289 s, and the AMap response was not written into the source or the
  fixture.
- [x] Shared native tests 8/8, Swift checks 10/10, the web build and the ESP32-S3 build pass.
- [x] Web visual checks were completed item by item with
  `?deviceState=offline|connecting|success|ready|planning`, confirming the five overlays and the
  hidden path after entering a valid route.
- [x] The close-range BLE two-stage handshake between the iPhone and the ESP32 and 3 App cold-start
  recoveries have been completed, each with a negotiated frame length of 182.

Still to be completed on physical devices:

- [ ] Check the six screens on the CO5300 item by item - not connected, handshake, connection
  succeeded, ready to ride, planning and valid route - confirming the text, the round edges and the
  contrast of the grey roads.
- [ ] Measure the 140 ms fade-out + 220 ms fade-in, the ~920 ms success hold, the immediate switch
  with Reduce Motion, and that the success screen does not get stuck or flicker repeatedly during
  repeated disconnections and reconnections.
- [ ] Switch between the App online demo, the offline OSM fallback and the ESP32 long-press demo,
  confirming that the source indication, the distance progression, the action points and the OSM
  attribution are always correct.
- [ ] Continue verifying touch, a 15-minute screen lock, active power cycling, weak signal, off-route
  rerouting, traffic refresh and one hour of continuous operation.
- [ ] Use real Core Location and a ready AMap gateway for a low-speed walking test, then mount it on
  the handlebar for a closed-road test.
- [ ] Only then decide on an external magnetometer, an in-house PCB and the dimensions of the V3
  Garmin mount enclosure.

Before any on-road riding test you must complete the checks for secure mounting, obstruction of the
view and accidental touch operations; the development board's original enclosure must not be treated
as a motorcycle product with long-term water and vibration resistance.
