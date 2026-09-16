> **Language:** English · [中文](MOBILE_WEB_NAVIGATION_PLAN.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Phone web-edition live navigation verification plan (not the official app)

> This line is kept only as a development verification tool for the foreground web page and the shared UI. The official product uses a native iOS app
> connected to the ESP32; the current App beta additionally has a clearly labelled built-in navigation demo, but normal / production navigation always uses
> real Core Location and AMap routes. Reliable positioning and BLE after the phone locks are handled by native capabilities.

## 1. Delivery definition

The main entry point `https://maler.top/moto-gps/` is no longer a navigation demonstrator, but foreground navigation used directly on the phone:

```text
Open the web page
  → allow precise location
  → search for and select a destination
  → request an AMap normal driving route
  → check the route distance, estimated time and live traffic conditions
  → start navigation and request orientation permission / screen wake lock
  → continuous positioning, heading-up display, turn-by-turn progress
  → reroute from the current position after continuous off-route confirmation
  → refresh the route's per-segment traffic conditions every 60 seconds
  → arrival or manual end
```

The phone provides the Web platform capabilities, while the navigation business still goes into the shared core. The web page must not replace the firmware code with its own second set of route matching or off-route logic.

## 2. Current completion status

| Capability | Status | Migration level | Notes |
| --- | --- | --- | --- |
| Phone destination search and result list | Done | C/B | Implemented both in the Web outer layer and natively on iOS; iOS uses a separate platform adapter and does not copy the navigation core |
| AMap POI 2.0 server-side integration | Done and configured | B | The gateway is deployed; the key lives only in a server-side environment variable |
| POI GCJ-02 → WGS84 inverse conversion | Done and tested | B | Avoids a second offset once the destination enters the route provider |
| AMap normal driving Route v2 | Done and configured | B | `strategy=32`, reading `cost/polyline/navi/tmcs` |
| Browser live positioning | Done | B | `watchPosition` → standard `GnssFix` |
| iPhone low-speed direction | Browser adaptation done | B | GPS heading while moving; tries `webkitCompassHeading` at low speed |
| Real RouteBundle/GNSS input into WASM | Done | B/A boundary | The input bridge is B; the core, projection and LVGL after it are A |
| Turn-by-turn progress and heading-up screen | Done | A | Shared `NavCore → Presenter → LVGL` |
| Automatic off-route rerouting | Done | A+B | Continuous confirmation in the shared core; the platform performs the new route request |
| Periodic traffic refresh | First version done | A+B | The core schedules it every 60 seconds; the platform fetches a fresh route for the latest `tmcs` |
| Screen Wake Lock | Done | C | Only improves foreground phone use, and does not go into the firmware |
| Background / locked-screen continuous positioning | Not achievable on the Web | Not applicable | An iOS/Web platform limit, and must not be written up as a bug to fix |
| Live online AMap data | Route request verified, awaiting a real iPhone | B | The health check is ready and production `/v1/routes` already returns real `amap` routes; nearby POIs and the complete riding flow still await phone acceptance |

## 3. Data and control chain

```text
Phone web page (C/B)
  ├─ Geolocation / DeviceOrientation / Wake Lock
  ├─ POI search and route confirmation UI
  └─ HTTPS platform adaptation
          ↓ WGS84 Fix / RouteBundle
WebAssembly shared layer (A)
  NavApp → NavCore → NavPresenter → LVGL 466×466 RGB565
          ↓ NavCommand
Phone web page performs route / traffic requests (B)
          ↓
maler.top same-origin Node gateway
  ├─ Key isolation, validation, rate limiting, timeouts
  ├─ POI GCJ-02 → WGS84
  └─ AMap Route v2 → RouteBundle v1
```

The current official prototype does not let the ESP32 replace browser positioning directly, and does not install a GNSS. The native iOS app uses Core Location and HTTPS to drive the shared navigation core, and then sends the complete display snapshot to the ESP32 over BLE. The shared navigation core, off-route policy, route projection, four-page round-display UI and RGB565 assets continue to be reused.

The native iOS app currently obtains a phone position first for place ranking, and sends the WGS84 latitude/longitude with the search request to the gateway. The gateway preferentially returns results within 50 km of the current position, and falls back to a nationwide / regional text search when there are no nearby results; the fallback search can also be used directly when positioning is not authorised or temporarily unavailable. When the App beta's "demo navigation" is online it requests a live AMap route from Building D to the Inspur headquarters run by run, and falls back to the canonical OSM fixture for the same places on failure; that fixture's white selected route and grey surrounding roads both come from OpenStreetMap ways (`© OpenStreetMap contributors`, ODbL), and AMap responses are not frozen into it. Actually tapping "start navigation" still calls the real positioning and the online provider, and the interface clearly distinguishes the two paths.

## 4. Phone-side state machine

### 4.1 Settings page

- The backend, WASM and the network are checked in parallel at startup.
- Precise location is requested only after the user taps "enable location".
- The search box opens only when a real provider, WASM and the current position are all ready at the same time.
- Search needs at least two characters, with a 480 ms debounce that cancels the previous request; the server applies its own rate limiting.
- A missing service key, denied location, insufficient accuracy, being offline and an interface timeout must each be displayed separately, and must not all be disguised as "no results".

### 4.2 Route confirmation page

- As soon as a POI is selected, the destination and the current fix are fed into the shared `NavApp`.
- The shared core emits `RequestRoute`; the web page only executes the command and fills back `RouteReady/RouteFailed`.
- Shows the same LVGL round-display preview, plus the estimated time, total distance and the worst per-segment traffic conditions.
- "Start navigation" is disabled until the route succeeds; no synthetic fallback route is shown.

### 4.3 Navigation page

- Tapping "start navigation" requests the iOS orientation permission and a Screen Wake Lock.
- The round display occupies the main viewport; the outer layer keeps only end, remaining distance, arrival time, connection and accuracy.
- `visibilitychange → hidden` is treated as navigation data possibly pausing; after returning to the foreground the page re-requests the Wake Lock, forces a current position and immediately triggers a core check.
- When the network drops it keeps using the old route in memory, and once recovered it handles the reroute / traffic commands the core has queued up.

## 5. Off-route and traffic conditions

### Off-route

- By default the positioning accuracy is required to be no worse than 50 m.
- A reroute starts only when the distance to the route exceeds 45 m across 3 consecutive reliable fixes; dropping below 25 m clears the confirmation count.
- The reroute request uses the latest WGS84 position, the original destination and a new `request_id`.
- Stale responses are discarded by the shared core according to the active number; if a reroute fails the old route keeps being displayed and the request is retried with backoff.

### Traffic conditions

- In the first version the shared core emits a refresh command every 60 seconds.
- The web page calls the AMap driving route again from the current position, reading fresh `tmcs` and the remaining duration.
- The first version only updates the traffic hints and the ETA on the old route, and does not switch geometry immediately because of one refresh; the whole route is replaced atomically only on an off-route event.
- If "switch routes automatically only when the congestion benefit is large enough" is implemented later, the benefit threshold and the route-switching policy must go into the shared core, and must not be written only in the web page.

## 6. Explicit iPhone boundaries

- An HTTPS foreground page can navigate continuously with `watchPosition`.
- Screen Wake Lock can only request that the screen does not turn off automatically; the user locking the screen, switching to the background, low battery or a system policy can still release it.
- Once the page is hidden, the browser will not reliably deliver positioning updates and timers may also be suspended; a PWA / added-to-home-screen page does not gain native background positioning permission.
- The web page therefore only promises "direct navigation while kept in the foreground with the screen on", and cannot promise "the web page keeps navigating while the phone is locked in a bag". The latter goal has been moved to the native iOS app's background positioning and BLE state recovery, and must pass long-duration testing on real hardware.

Official references:

- [W3C Geolocation](https://www.w3.org/TR/geolocation/)
- [W3C Screen Wake Lock](https://www.w3.org/TR/screen-wake-lock/)
- [Safari 16.4 Release Notes](https://developer.apple.com/documentation/safari-release-notes/safari-16_4-release-notes)
- [WebKit page suspension and power usage](https://webkit.org/blog/8970/how-web-content-can-affect-power-usage/)

## 7. AMap configuration and licensing gates

The online service runs on `127.0.0.1:3023` and is exposed as `/moto-gps/api/` by Nginx. As of 2026-09-04 a real AMap provider is configured, and `/moto-gps/api/healthz` returns `ready_for_live_navigation: true`. If the key is removed later, the service still falls back to the `disabled` provider, and will never use a fixture to impersonate a real route.

Enabling requires:

1. creating a key of the "Web Service API" type;
2. binding the server's egress IP to a whitelist is recommended;
3. writing the key into the server's `/etc/moto-gps-api.env`, keeping the permissions at `600`;
4. setting `MOTO_PROVIDER=amap` and restarting the service;
5. testing POI, real routes, off-route, the 60-second traffic refresh and quota errors on a real phone.

The key must not enter Git, HTML, JavaScript, WASM or the ESP32 firmware.

An ordinary Web Service key only solves technical access and does not automatically grant in-vehicle product authorisation. AMap's current terms restrict in-vehicle devices / projection and data caching; written permission must be obtained before this reaches a handlebar physical device or a public product: [AMap Open Platform terms of service](https://lbs.amap.com/pages/terms/). Normal driving routes also do not guarantee avoidance of motorcycle restrictions.

## 8. Acceptance order

1. Done: 4/4 tests of the native navigation core.
2. Done: 15/15 tests of the backend conversion, coordinates, validation and gateway.
3. Done: WASM Release build and the complete phone-size mock flow.
4. Done: checks of the online static page, WASM MIME, the location / wake-lock permission headers, the Node service and the Nginx reverse proxy.
5. Server side done: a smoke test of a real AMap request on production `/v1/routes`; acceptance of nearby POIs and the whole route on a real iPhone is still pending.
6. Pending on real hardware: a short foreground walk / ride on one iPhone to verify off-route rerouting and returning to the foreground.
7. Pending on real hardware: 1–2 hours of continuous screen-on navigation, weak-network, network-switch, quota and heat testing.
8. Later: extending the glyph set for arbitrary Chinese road names, long-route matching performance and the traffic-benefit route-switching policy.
9. Later: display, ESP32-S3, power and physical consistency testing; GNSS will only be evaluated separately if the fully standalone terminal route is revived in future.
10. Last: the enclosure manufacturing plan, which stays on hold until all hardware dimensions and the thermal design are stable.
