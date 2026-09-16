> **Language:** English · [中文](PROTOTYPE_VERIFICATION_2026-09-04.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Waveshare + iPhone prototype delivery and verification record (2026-09-04)

## Delivery conclusion

- The current iOS App beta provides a clearly labelled built-in navigation demo; normal / production navigation uses only real Core Location, real POIs and live routes.
- The iPhone handles positioning, AMap routes, live traffic conditions, off-route rerouting and navigation state; the ESP32 handles touch and the 466 × 466 round-display output.
- The ESP32 firmware has been wired to the Waveshare 1.75C official BSP, CO5300, CST9217, NimBLE and the shared LVGL source.
- The BLE v1 UUIDs, frames, CRC, fragmentation, handshake, heartbeat, ACK and page commands are frozen.
- The native App has implemented nearby place search that prioritises the phone's current position; when there are no nearby results or no positioning, the gateway falls back to a nationwide / regional text search.
- BLE reconnection, out-of-order events, reliable handshake writes and retries, stale-session filtering and failure backoff have been fixed; the same iPhone completed the two-stage handshake at the first attempt in 3 consecutive cold starts, with a negotiated frame length of 182 and no failure/success flapping during the observation window. Long-duration locked-screen / repeated power-cycle verification is still to be done.
- Both iOS and the ESP32 have been wired up to the black-background white `MOTO GPS` boot screen; the ESP32 logo fades in, holds and fades out within the existing 1.25-second boot cadence. The round-display page dots hide automatically after 5 seconds of no interaction, and a long press on the navigation page toggles the on-device demo route.
- The navigation page shows only the large distance number + unit, and no explanatory distance heading; when there is no real route and the demo is not enabled it hides the manoeuvre icon, distance, route progress and speed-limit sign, so that a misleading straight-on `0 m` does not appear.
- The Web, ESP32 and iOS offline fallbacks use real Jinan roads: the white selected route and grey surrounding roads both come from verifiable OpenStreetMap ways, keeping the `© OpenStreetMap contributors` attribution and complying with ODbL; for the coordinates and road details see the [demo fixture notes](../shared/demo_fixture/README.en.md). Only the iOS online demo and official navigation request AMap live, and the responses are not frozen into the demo fixture.
- The ordinary AMap route interface does not provide a complete surrounding road network. The official online road layer still requires the AMap "traffic situation query" advanced API to be enabled, and explicit written authorisation before in-vehicle / smart-hardware projection; the current OSM fixture is only used for repeatable demos.
- The online AMap gateway health check already returns `ready_for_live_navigation: true`.
- After a complete backup of the factory 32 MB Flash and confirmation of the checksum, the first physical-device flash and boot verification has been completed.

## Checks that passed

| Check | Result |
| --- | --- |
| CMake host-side shared core build and CTest | 8/8 passed, including the next-action across a junction, BLE connection epoch and concurrent bridge checks |
| Backend Node automated tests | 30/30 passed, including bounded retries on transient upstream failures |
| Emscripten WebAssembly Release build | Passed |
| 466 × 466 desktop and iPhone-width visual checks | Passed |
| `xcodegen generate` | Passed |
| `swift run MotoNavigationCoreChecks` | Passed |
| Full Swift static type check of the App | Passed |
| Compilation checks of the two Objective-C++ shared bridges | Passed |
| plist, entitlements and Xcode project syntax checks | Passed |
| C++/Swift BLE golden frames, command sequence/ACK/duplicate packets | Passed |
| ESP-IDF 5.5.5 `esp32s3` real-target build | Passed |
| ESP32 application image / partition budget | 1,256,016 B / 8 MiB, about 15% |
| iPhone two-stage BLE handshake | Succeeded at the first attempt in 3 consecutive App cold starts, frame length 182 |
| This round's firmware and App installation | The latest firmware has been flashed; the latest signed App has been installed over the previous one and launched, and the serial console confirms that encryption, subscription and MTU 185 are complete |
| Online `/moto-gps/api/healthz` | `provider: amap`, ready |
| Online production `/moto-gps/api/v1/routes` | A live AMap route from Building D → the Inspur headquarters passed: 1,608 m / 36 points; manoeuvre points 65/165/295/560/1600/1608 m |

## Gates that cannot yet be claimed as complete

The current Mac has the complete Xcode installed, and the latest App has been signed, installed and launched on the current iPhone; the following physical gates are still to be completed:

1. Verify CO5300 long-duration refresh, CST9217 touch across the full edge, brightness, frame rate and temperature.
2. Verify BLE pairing, locked-screen background operation, active power-cycle recovery, off-route rerouting and periodic traffic refresh over a long run.
3. Measure PWR long-press power-off behaviour under all three supply combinations: USB, battery and USB + battery.
4. Verify handlebar mounting, readability in strong light, vibration and safety interaction.
5. Before productisation, enable and verify the AMap advanced traffic-situation API, and obtain written authorisation for in-vehicle / smart-hardware projection and the required data usage.

## Actual execution progress and the order that follows

1. Done: read-only identification of the USB device, chip model, Flash capacity and MAC.
2. Done: a full-chip backup of the factory firmware according to the measured capacity, generating a SHA-256, and reporting the verification result to the user.
3. Done: cross-checking the firmware and partition parameters, and completing the first flash and boot verification after obtaining explicit permission.
4. Done: App Launch Screen, firmware logo fade in and out, automatic hiding of the round-display dots, the large distance number + unit and the no-route hide rule, the Jinan real-road demo, the App test demo, the firmware long-press demo and joint debugging of the iPhone BLE cold-start handshake.
5. Next: complete normal navigation acceptance with real POIs, real positioning and real AMap routes.
6. Last: run, in order, 15 minutes of locked screen, repeated disconnect and reconnect, off-route rerouting, traffic refresh and a one-hour stress test.

No host-side or cross-compilation check can replace the physical tests above.
