> **Language:** English · [中文](ROUND_UI_EXECUTION_PLAN.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Round-display product UI and navigation simulation execution plan

> Status note: this document records the round-display and web replay development phase that has already been completed. The web replay here is still only for visual / protocol checks on a computer; the later app test build and the ESP32 firmware each added a clearly marked Jinan real-road demo, while normal / production navigation still uses real phone positioning and online routes. The live AMap gateway is now ready; the ESP32 has no GNSS connection and does not request HTTPS directly. The same-source migration constraint for the round display's `NavPresenter + LVGL` still applies.

## 1. Goals for this round

This round only completes the "product UI + repeatable navigation simulation"; it does not select hardware, does not design an enclosure, and does not package the demo result as real AMap or Baidu navigation.

The deliverable is a 466 × 466 round-display program you can experience directly in a browser: after the boot logo fades in and out it enters the heading-up navigation page, and you can switch sideways by touch / mouse to the separate speedometer and compass; music control is first built as a toggleable extra page and interaction simulation. Every production screen inside the round display is generated from the shared C/C++, LVGL and in-project resources, and the firmware continues to compile the same code.

The reference images and videos provide the product direction; they do not mean pasting phone screenshots, videos or web pages into the round display. The following core characteristics should be extracted and implemented:

- Deep black background, thick white route, a small amount of cool-coloured status light, so the screen is readable at a glance while riding;
- The vehicle arrow is fixed pointing to 12 o'clock on the screen, and the route rotates and pans beneath the arrow;
- Only the information needed for the current decision is shown; no full map tiles, dense POI labels or web controls;
- With a valid route, the perimeter may carry secondary information such as route progress, traffic conditions or tick marks; with no route, no empty progress is shown, and the centre area is reserved for the route, speed and direction;
- Touch actions are few and the targets are large, so nothing depends on tiny buttons when wearing gloves or riding over bumps.

## 2. Portability levels

Following the project's existing contract:

- **A: same-source migration** — the web and ESP32 compile the same shared code and resources, and the firmware can present it as-is.
- **B: same-contract adaptation** — behaviour and data interfaces are the same, but the browser and the physical device each connect to platform capabilities themselves.
- **C: web only** — used only for replay, injection, observation and debugging; it does not enter the firmware's product UI.

The level of each deliverable in this round is as follows.

| ID | Feature or deliverable | Level | Firmware migration verdict |
| --- | --- | --- | --- |
| UI-01 | 466 × 466, RGB565, round safe area, colours, fonts, icons and layout | A | The same LVGL source and resources go into web / ESP32 |
| UI-02 | Page state machine for the four pages: navigation, speedometer, compass, music | A | Page order and display rules are same-source |
| UI-03 | Horizontal swipe, tap, press feedback and accidental-touch prevention rules | A | LVGL gesture and widget logic are same-source |
| IO-01 | Browser mouse / touch input and the physical touch controller | B | Both submit coordinates and press state to the LVGL input contract; the underlying drivers differ |
| NAV-01 | Heading-up route projection, clipping, resampling and smoothing | A | The algorithm uses only the shared route and positioning state |
| NAV-02 | Fixed vehicle arrow, grey surrounding roads, white selected route, manoeuvre icon, large distance number + unit, plus the speed limit and perimeter progress when valid data exists | A | All drawn by LVGL; with no route the action, distance, progress and speed limit are hidden, with no second drawing pass in HTML / Canvas |
| NAV-03 | Round-display feedback for off-route, reroute, network loss, congestion and arrival | A | Consumes the existing shared navigation state machine results |
| NAV-04 | Real data sources for GNSS, heading, speed, network and clock | B | The web uses fixtures / a virtual clock; the firmware will later use GNSS, sensors, Wi-Fi and RTC |
| SIM-01 | Heading, speed and position advancing over time on a Jinan real-road fixture | C (web control) / A (shared data) | The app and the firmware demo use the same controlled route / surrounding road data and still go through the production presenter / LVGL path |
| SIM-02 | Play, pause, speed multiplier, seek, off-route, network loss, traffic conditions and low-speed heading injection | C | Exists only in the web outer debug console |
| SIM-03 | Web event log, state readouts and performance observation | C | Used only for development verification |
| SPD-01 | Separate speedometer page: current speed, `km/h`, a clean perimeter scale | A | The speed value comes from the platform data source; the screen is same-source |
| CMP-01 | Separate compass page: degrees, compass letter, rotating scale and a fixed reference line | A | The heading value comes from the platform data source; the screen is same-source |
| MUS-01 | Music page layout, and touch events for play / pause, previous track, next track and like | A | The page and event contracts are same-source, but that does not mean the phone app can already be controlled |
| MUS-02 | Simulation of the track, playback state and button feedback in the web page | C | Proves only the interaction and the visuals, not the ability to control the phone |
| MUS-03 | On real hardware, control the currently active player through BLE HID + Apple Media Service | B | Verified in a later hardware phase; no separate business logic is written for Apple Music or NetEase Cloud Music |
| MAP-01 | AMap / Baidu real POI, route, reroute and traffic condition provider | B | Converted uniformly into a `RouteBundle` in our own backend; no real service is connected in this round |
| QA-01 | Shared state tests, route projection tests, RGB565 golden frames and performance budget | A | Web / ESP32 use the same set of input fixtures |
| QA-02 | Browser desktop / phone viewport, touch and debug console usability tests | C | Verifies the development entry point; not part of the round-display firmware |

### Content in this round that must be stated up front as not migrating as-is

The following cannot be claimed to carry over as-is "once the web page is finished":

1. The web outer debug console, sliders, logs and fault-injection panels are level C; the firmware only has the screens inside the round display.
2. The web's mouse / touch, virtual track and browser clock are replaced by physical touch, a GNSS / heading source and the device clock, which is level B.
3. The music control page can migrate at level A, but the web page cannot prove that it controls Apple Music or NetEase Cloud Music on the iPhone; the real control chain must be verified in the hardware phase.
4. BLE HID can only reliably fall back to play / pause, previous track and next track; the iPhone's Apple Media Service (AMS) can additionally provide player / track metadata and a conditional `LikeTrack` command. AMS does not provide a "currently liked" state, and a successfully written command does not mean the app will necessarily execute it, so "like" can only appear dynamically according to the current player's capabilities and show "sent"; it must not be disguised as a toggleable favourite state.
5. The web, ESP32 and iOS offline navigation simulation use a fixed Jinan real-road fixture and a synthetic along-route position advance; they are not live online navigation. The white selected route and the grey surrounding roads both come from real OSM ways; the iOS online demo requests the AMap route between the same two POIs live each time, but does not freeze the response into the fixture. The real POI, online planning, off-route reroute and traffic condition providers stay clearly separated from the demo entry point.

## 3. Round-display information architecture

### 3.1 Page order and touch rules

The default page order is:

```text
navigation ←→ speedometer ←→ compass ←→ music (when the feature toggle is enabled)
```

- Swipe left and right to switch pages; page switching is horizontal only, and vertical jitter does not trigger it.
- While navigation is running the navigation page is the default page; switching pages must not pause the navigation core or the track replay.
- All touch hot zones are designed at least 44 × 44 logical pixels, and the main music control buttons should be larger.
- The navigation page carries no small buttons that need a precise tap. Page position is hinted with very faint dots; they appear after power-up, a touch or a page change and hide automatically after 5 seconds of no operation, so they do not keep competing with the route for attention.
- When Reduce Motion is on, page changes and route updates become an immediate refresh or a short fade-in, with no inertial sliding.
- Whether to "return to the navigation page automatically as a turn approaches" starts as a configurable policy and is not forced in the first version, so that a user who has switched to the speedometer is not interrupted repeatedly.

### 3.2 Navigation page

The navigation page is the main screen of this round, and its visuals and behaviour must be completed before the other pages.

Fixed elements:

- The vehicle arrow sits slightly below the centre of the circle and always points to 12 o'clock on the screen;
- The currently selected route uses a highlighted white or cool-white thick line, with a low-saturation cool colour on the outer edge to strengthen its outline at night;
- The route already travelled drops to dark grey and does not compete for attention with the route ahead;
- Surrounding roads are shown as thin grey lines and use the same heading-up transform as the white selected route;
- The manoeuvre icon and the distance sit in the lower safe area; the distance shows only a large number and the separate `m` / `km` unit, with no explanatory caption added;
- The speed limit uses a small red-and-white round sign and is shown only when the data exists;
- The thin perimeter arc carries the progress of a valid route and the traffic conditions ahead, and the number of colours stays restrained;
- Reroute, network loss, arrival and similar states use short text and a colour change, without covering the vehicle arrow.

The page does not show full map tiles, buildings, POIs, a search box or a long road list. The route geometry must come from the data; it must not just switch between a few static images according to "turn left / turn right".
When there is no real route and the demo has not been explicitly enabled, the manoeuvre icon, the distance, the perimeter route progress and the speed-limit sign are all hidden; waiting for the phone or waiting for a route is a connection state, and a straight-on `0 m` must not be presented. When a route exists but there is no trustworthy speed limit, the speed-limit sign is hidden as well.

### 3.3 Separate speedometer page

The speedometer stays a "plain speedometer":

- A large integer speed in the centre;
- Only `km/h` below it;
- The perimeter scale gives a stable instrument feel and advances smoothly with speed;
- When positioning is invalid it shows `--` and does not keep passing the last speed off as live speed for a long time;
- The first version adds no extra information such as average speed, distance travelled, altitude, acceleration or ranking.

### 3.4 Compass page

- The fixed reference line points to 12 o'clock on the screen and the scale ring rotates in the opposite direction;
- It shows `0–359°` and `N / NE / E / SE / S / SW / W / NW`;
- When the heading is invalid or the accuracy is insufficient it degrades clearly and does not show falsely precise degrees;
- At low speed the heading source may need a magnetometer or IMU / GNSS fusion; the sensor selection is deferred, and this round uses a simulated heading to verify the screen and the algorithm interface.

### 3.5 Optional music page

The first version only verifies a safe, simple control layout:

- Play / pause in the centre;
- Previous / next track on the left and right;
- "Like" is shown separately but controlled by a switch for the current AMS capability: it is hidden when the platform does not support it;
- The web does not assume it can read the real track title, cover art, lyrics or favourite state; the physical device may later read the limited metadata AMS provides, but there is still no reliable favourite state;
- Navigation turn prompts have higher priority, but the first version does no audio mixing or voice announcements.

## 4. How heading-up navigation is implemented

The key effect the user sees is "the arrow does not turn, the route turns with the vehicle". This is not a matter of putting a right-turn icon in the centre of the screen, but of generating a local view from the real route geometry every frame.

### 4.1 Inputs

The shared route view consumes at least:

- the route progress currently matched;
- the current coordinate and its coordinate system;
- the stabilised vehicle heading;
- the current speed and positioning accuracy;
- the route polyline, manoeuvre points and per-segment traffic conditions in the `RouteBundle`;
- the screen's logical size, the look-ahead distance and the circular clipping radius.

### 4.2 Projection steps

1. Locate the vehicle's mileage position on the polyline from the route match result.
2. Cut a local route window covering a short distance behind and a longer distance ahead of the vehicle. The look-ahead distance is adjusted by speed, the distance to the next manoeuvre and the curve density.
3. Convert the local longitude / latitude into metric east / north coordinates near the current point; the route and the current position must be in the same coordinate system.
4. Rotate the local coordinates by the stabilised heading so that "ahead of the vehicle" always maps to the negative Y direction of the screen.
5. Place the vehicle anchor near `(180, 190)` rather than at the absolute centre of the circle, leaving more room for the route ahead.
6. Resample the polyline by distance, limit sharp corners and clip it off-screen, then generate LVGL line segments; drawing a second copy of the route with the browser Canvas is forbidden.
7. Split the route into the travelled, current and upcoming sections by route progress; traffic conditions change only the corresponding segment or the perimeter hint, not the colour of the whole screen.
8. Smoothly update the position and heading when a new GNSS sample arrives. After an off-route reroute succeeds, replace the route atomically and use a short transition to avoid the route flickering.

### 4.3 Heading stabilisation rules

- While riding normally, prefer the GNSS course-over-ground filtered by accuracy and speed thresholds.
- At low speed or when stopped, freeze the most recent trustworthy heading, or use the low-speed heading source that future hardware provides; the route must not keep rotating because of GNSS noise.
- Rate-limit or interpolate a sudden heading change first; for a real U-turn, allow a large angle change within a short time.
- With no trustworthy heading it degrades to "route tangent pointing up" and flags that degradation in the state, so it is not passed off as an accurate compass.

### 4.4 Simulated route

The current fixed fixture sits around Building D of the Jinan Big Data Industry Base: the white selected route and the grey background roads both consist of verifiable, physically connected real ways from OpenStreetMap. The OSM WGS84 coordinates are uniformly converted to GCJ-02 and drawn in the same scene; keep the `© OpenStreetMap contributors` attribution and follow ODbL when distributing and displaying it, and the way details are in the [demo fixture notes](../shared/demo_fixture/README.en.md). AMap responses are not frozen into this fixture.

The fixture covers the following screens:

- going straight into a gentle bend;
- approaching a 90° right turn;
- the route rotating continuously during the turn;
- the new road pointing up again after the junction;
- grey surrounding roads and a speed limit when trustworthy data exists;
- a manual off-route triggering a reroute, then switching to a new route;
- traffic conditions switching from flowing to slow / congested;
- final arrival.

Track playback is not a simple incrementing percentage. Every sample contains at least position, time, speed, heading and accuracy, so that you can check whether the heading-up, speedometer and compass pages use the same real state.

## 5. Shared data and module boundaries

The target dependency relationships stay as:

```text
web fixture / later physical sensors (B/C)
        ↓ standard events
NavApp → NavCore → NavPresenter (A)
        ↓ read-only presentation state
route view projection + page state machine + LVGL (A)
        ↓
WASM framebuffer / ESP32 LCD driver (B)
```

The presentation state to be extended includes:

- the current page;
- speed, heading, heading validity and the speed limit;
- whether the current time is valid;
- the local route window, or a read-only route reference needed to generate the local window;
- the optional music capability bits and button feedback state.

Platform entry points can still only submit events to `NavApp` or a dedicated input adapter, and must not modify `moto_ui_state_t` directly for the sake of a demo. Simulated speed, heading and routes must also go through the public event contract, so that the UI business logic does not change when they are replaced by GNSS in future.

Music events are separated from navigation state. Define only intents such as `PlayPause / Previous / Next / Like`, and have the platform adapter return "supported, submitted or failed"; the shared UI does not depend directly on Apple Music, NetEase Cloud Music, iOS or browser APIs.

## 6. Phased execution and exit conditions

### Phase R0: freeze the reference screens and acceptance frames

- Confirm the information hierarchy and page order of the four pages: navigation, speedometer, compass and music;
- Determine the vehicle anchor, route thickness, look-ahead range and night contrast from the reference videos;
- Fix a set of states: going straight, approaching a turn, turning, after the turn, off-route, reroute, network loss and arrival.

Exit condition: every state has a definite input and an expected screen, so that correctness is not later judged by dragging the UI around by hand.

### Phase R1: extend the shared presentation contract

- Add page, speed, heading, speed-limit and validity fields;
- Add a data structure for the local route view, or a shared projection interface;
- Keep structures at a fixed capacity with definite limits, avoiding unbounded containers for the web;
- Native tests cover default values, boundary values and invalid data.

Exit condition: both the web and ESP32 targets compile; platform entry points do not write LVGL state directly.

### Phase R2: build the page container and touch paging

- Manage the four pages with one shared LVGL root container;
- Implement the horizontal swipe threshold, direction lock, debouncing and large touch zones;
- Page transition effects respect the RGB565 and frame-rate budget;
- The music page is controlled by a build / runtime capability switch.

Exit condition: both mouse dragging and touch events switch pages reliably, and consecutive swipes do not lose page state.

### Phase R3: complete the heading-up navigation page

- Implement the local route projection and the fixed vehicle arrow;
- Add the manoeuvre icon, the large distance number + unit, grey surrounding roads, the white selected route, and the speed limit, perimeter progress and traffic conditions shown according to data validity;
- Verify that with no route the action, distance, perimeter progress and speed limit are all hidden and no straight-on `0 m` appears;
- Wire in the off-route, reroute, network loss and arrival states;
- Use the simulated track to verify that the route changes continuously before and after a turn.

Exit condition: while the fixture plays, the vehicle arrow always points up and the route shape changes continuously with position and heading; the screen is not a static set of manoeuvre images.

### Phase R4: complete the speedometer and compass

- Both pages use the same sample speed, heading and time as navigation;
- Verify low speed, zero speed, invalid positioning, 359°→0° and heading jumps;
- Control the animation update rate, avoiding value jitter and pointless redraws.

Exit condition: switching pages does not change the data source; boundary states have a definite degraded screen.

### Phase R5: extend the web simulation console

- Change the synthetic track into a sample sequence with time, speed and heading;
- Add injection of the current page, speed, heading, speed limit and music capability;
- Keep play / pause / speed multiplier, progress, off-route, network and traffic condition controls;
- Mark the outer debug console clearly as level C, and keep it out of device screen screenshots.

Exit condition: a complete navigation, turn, off-route reroute, speedometer and compass demo can be given without connecting a real map or hardware.

### Phase R6: verify the extra music page

- Complete the level A page and touch intents;
- The web does only level C state simulation;
- "Like" is shown according to the capability bit and does not fake a favourited result;
- Record the checklist for the on-device BLE HID fallback and AMS enhancement and defer it to the hardware phase.

Exit condition: the extra page can be turned off independently without affecting navigation, the speedometer, the compass or the firmware memory budget.

### Phase R7: dual-target build and quality verification

- Run the native state / projection tests;
- Compile the WASM and ESP32 skeletons, and keep web-only code out of the firmware;
- Save RGB565 golden frames of the key screens;
- Check the 466 × 466 round safe area and the desktop and phone browser viewports;
- Measure the frame rate, update rate, static / peak memory and font / resource Flash usage;
- Check touch focus, Reduce Motion and long replay.

Exit condition: key screens are consistent, the simulation flow is repeatable, and there is no dependence on browser-specific visual effects.

## 7. Acceptance checklist for this round

- [ ] Opening the web page shows product-grade round-display navigation directly, not an engineering placeholder screen.
- [ ] The vehicle arrow stays pointing up throughout the simulation.
- [ ] The route pans / rotates continuously with position and heading, and the change in road geometry is visible during a right turn.
- [ ] With a valid route, the manoeuvre icon, large distance number + unit, speed limit and traffic conditions are readable inside the round safe area; with no route these cues do not appear.
- [ ] After going off-route it enters the reroute state and continues navigating once the new route arrives.
- [ ] Swiping sideways reaches the separate speedometer and compass in turn, with data consistent with the navigation track.
- [ ] The speedometer shows only the current speed and unit; with no valid speed it does not keep fake data.
- [ ] The compass handles 359°→0° correctly and does not spin erratically at low speed or with an invalid heading.
- [ ] The music page can be toggled; the web clearly shows it is a simulation and the like feature makes no false promise.
- [ ] Inside the round display there is no HTML, Canvas redraw, system Emoji, online font, blur or video asset.
- [ ] Key states are checked with the shared tests and RGB565 screenshots.
- [ ] At phone width the debug console does not clip the round display and the main actions are tappable.

## 8. Work explicitly deferred

### Real AMap / Baidu integration

At the time of this round a fixed `RouteBundle` was used to verify the product interaction and the shared rendering; since then the AMap POI search, standard driving route, off-route reroute and live traffic condition chains have been connected, and the native app also searches nearby places first, based on the phone's current position. Real data still goes through our own backend, the key does not enter the app, the web page or the firmware, and the interchangeable-provider boundary is kept.

The standard AMap route interface returns only the selected route and does not provide a complete surrounding road network. A production online grey road layer needs a separate connection to the "traffic situation query" advanced API of AMap with `extensions=all` / `roads[].polyline`, plus explicit written authorisation from AMap before projecting it onto a vehicle or smart hardware; when the service is not enabled or authorisation has not been granted, it is better to draw only the real selected route than to generate random side roads or reuse the fixed demo roads.

Whether Baidu becomes a second provider will be scheduled only after the real AMap chain works end to end and the authorisation boundary is confirmed; the navigation core or the round-display UI must not be duplicated just to connect both at once.

### Hardware procurement

The main board, the round touch display, GNSS, the low-speed heading sensor, power and the battery are all deferred in this round. The web approach keeps the fixed resource limits and the level B input interfaces, but no procurement decision is made before the display interface, brightness, touch chip, outdoor readability and power draw have been measured.

### Enclosure

The enclosure is still the last step of the whole project. Only once the display size, PCB, antenna, power, heat dissipation, water resistance, buttons / touch and handlebar mounting position are all stable does work start on the structure, materials, sealing and prototyping plan, avoiding repeated tooling or printing.
