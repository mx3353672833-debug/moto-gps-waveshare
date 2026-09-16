> **Language:** English · [中文](PORTABILITY.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Portability contract

## Goal

The web version is not a visual reference draft of the firmware interface, but a run of the shared round-display code in the browser. The round-display `NavPresenter + LVGL` is maintained in one place only and compiled by both the Web and the ESP32; the navigation `NavApp + NavCore` is also maintained in one place only and used by both the Web and the native iOS bridge. The ESP32 is currently the BLE instrument side and does not re-run route planning, off-route rerouting or AMap requests on the device.

This document is a hard constraint for the project. Any new feature or visual modification must be marked A, B or C level before implementation; if it cannot be presented on the firmware as-is, that must be stated before starting work, rather than being exposed at the porting stage.

## Fixed display baseline

| Item | Baseline |
| --- | --- |
| Logical and physical resolution | 466 × 466 |
| Pixel format | RGB565 |
| Screen shape | Round; the four corners are treated as invisible areas |
| UI engine | LVGL, sharing the same version, configuration and assets |
| Web output | The LVGL software framebuffer is fed into the Canvas as-is, without Canvas redrawing components or text |
| Firmware target | ESP32-S3, preferring a model with 8 MB PSRAM and 16 MB Flash |
| Initial performance target | 30 fps; ultimately judged by tests on the physical screen and the bus |

The Web, the Presenter and the Waveshare real hardware currently all use the 466 × 466 baseline. If this is later changed to another resolution or pixel format, it must be evaluated separately and clearly announced: pixel-level appearance, text wrapping, asset sizes and animation timing are no longer guaranteed to migrate as-is.

## A/B/C migration levels

| Level | Definition | Typical content | Acceptance method |
| --- | --- | --- | --- |
| A: same-source port | The corresponding runtime compiles the same business or UI source and assets | Web/iOS share `NavApp + NavCore`; Web/ESP32 share `NavPresenter + LVGL`, fonts, icons and page layout | The core fixture produces the same state; the same snapshot produces the same key RGB565 frame |
| B: same-contract adaptation | The upper-layer behaviour and interface stay unchanged, but the platform bottom layer is implemented separately | Web Geolocation/`fetch` and iOS Core Location/URLSession; iOS BLE Central and ESP32 BLE Peripheral; browser input and CST9217 | Both sides pass the same protocol fixture; error codes, timeouts, handshake and retry semantics are identical |
| C: Web only | Serves development, observation or fault injection only, and does not go into the firmware | HTML/CSS debug console, the complete AMap map, mouse dragging of the vehicle, timeline, log view, browser developer tools | Isolated from the shared round-display code; must not be depended on when building the firmware |

### Exceptions allowed for level A

Level A emphasises the same source, assets and behaviour, and does not promise that different LCD panels are physically identical in brightness, colour temperature and viewing angle. The Web must simulate RGB565 quantisation, and fonts must use the LVGL fonts generated inside the project, and must not call browser system fonts.

### Level B boundaries

A level B adapter may only handle platform capabilities, and must not secretly copy business logic. For example:

- The network adapter only handles requests, timeouts, cancellation and returning data, and must not implement the reroute policy separately on the Web and iOS sides.
- The positioning adapter only provides standardised positioning samples; route matching and off-route detection must stay in the shared core.
- The storage adapter only stores versioned data; the route data format and compatibility logic must be shared.
- The BLE adapter is only responsible for reliably transporting versioned snapshots and commands, and must not duplicate a navigation core on the ESP32.

## Implementation constraints

- Inside the round display, HTML, CSS, DOM, browser SVG filters, system emoji, Lottie, GIF and video are forbidden.
- Fonts, icons, colours, layout constants and animation curves all go into shared assets or shared source.
- Do not depend on browser font typography, subpixel antialiasing, frosted glass, blurred shadows and other effects the ESP32 cannot implement equivalently.
- The AMap key must not enter WebAssembly, the web frontend or the firmware; routes and traffic conditions all go through our own backend.
- The shared core does not call browser, ESP-IDF, Arduino or specific display driver APIs directly.
- Route provider data is first converted into a versioned internal `RouteBundle`; the UI and the navigation core must not depend on the raw AMap response structure.
- Every cross-platform interface must allow a fixed fixture to be injected, so the data contract can be verified without hardware. The current iOS beta can start the level B built-in demo adapter from a clearly labelled button, and the ESP32 can also toggle the on-device demo route by a long press on the navigation page; both must go through the official shared core, protocol or Presenter chain, and must not replace the normal / production navigation entry point for real routes.
- Phone positioning / route coordinates use `Wgs84Point`/`Gcj02Point` respectively in the shared C++; crossing the boundary with an unmarked generic point type is forbidden.
- The Web and iOS entry points may only inject events into the shared `NavApp` and execute the commands it returns. The ESP32 may only decode BLE snapshots, update `NavPresenter`, and send touch commands back to the phone, and must not directly copy or tamper with the authoritative navigation state.
- The round-display power-on logo fade in and out, the page dots and their 5-second no-interaction hide rule are shared product interactions; a touch or a page change must briefly bring the dots back. The ESP32 on-device demo can only be triggered explicitly by a long press on the navigation page, and after exiting it the iPhone snapshot remains authoritative.
- A real-road fixture must record its source, coordinate system, conversion and licence: the current Jinan white selected route and grey surrounding roads both come from OpenStreetMap ways, keeping the `© OpenStreetMap contributors` attribution in the display and complying with ODbL. AMap is used only for live online requests in the iOS online demo or official navigation, and its responses must not be frozen into the demo fixture; the official online road layer can only be connected after the AMap "traffic situation query" advanced API is enabled and written authorisation for in-vehicle / smart-hardware projection has been obtained.

## Pre-change checklist

Before each feature or visual change, record the following:

1. Migration level: A, B or C.
2. Whether it changes the 466 × 466, RGB565, font, memory, Flash or frame-rate budget.
3. If it is level B, what the two adapters are and how the behaviour contract is verified.
4. If it is level C, why it is only used for debugging and how it is isolated from the shared code.
5. If it cannot be presented on the firmware as-is, state the specific difference and the alternative first, and implement only after it is confirmed.

## Consistency acceptance

- Web and iOS share the same set of route, positioning, network and clock fixtures; iOS/ESP32 share the same set of BLE golden frames.
- Compare the Web/iOS navigation state after every input event; the off-route, reroute, network-recovery and arrival results must be identical.
- Key UI states are saved as an RGB565 golden frame; the Web outputs a framebuffer in that format directly.
- The firmware build must not link the `web_only` directory, and the web debug console must not contain a second implementation of the round-display business logic.
- Anything that can only be "visually approximate" must not be marked level A.
