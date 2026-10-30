> **语言 / Language:** [中文](README.md) · English

# Android App

A native Kotlin + Jetpack Compose app for the same Waveshare
**ESP32-S3-Touch-AMOLED-1.75C** round screen as the iPhone app. It talks to the
existing firmware; no Android-specific firmware is needed.

The flow matches iOS: search for a destination → compare up to three AMap driving
routes → start navigating. Turn guidance, speed and the nearby road/building map are
synchronized to the round screen over BLE. Gateway, BLE protocol and navigation state
machine are the same implementation iOS uses.

## Shared with iOS

| Area | Android implementation |
| --- | --- |
| Navigation state machine (route matching, rerouting, arrival, traffic refresh) | `shared/nav_core` and `shared/nav_app`, compiled into the APK through NDK + JNI |
| BLE v1 encoding, fragmentation, CRC, reassembly | `shared/ble_protocol` via JNI; Kotlin re-implements no byte layout |
| Golden bytes | `shared/protocol/fixtures/ble-navigation-v1.golden.txt`, copied into the APK at build time and self-checked before the handshake in debug builds |
| Jinan demo route and bundled offline map | `shared/demo_fixture` and `shared/offline_map/jinan-v1.sqlite`, copied at build time |
| Gateway API | `/healthz`, `/v1/places`, `/v1/route-options` (older gateways fall back to `/v1/routes`), `/v1/map/cities`, `/v1/map/tiles` |

The JNI adapter is `app/src/main/cpp/moto_jni.cpp`. Handshake, write pacing,
heartbeat, ACKs, command de-duplication, 5 Hz snapshot coalescing and map ACKs are
ported from `ESP32BLECentral.swift` into `ble/BleProtocolEngine.kt`; the GATT side is
`ble/Esp32BleCentral.kt`.

## Android specifics

- **No Google Play services required.** Navigation uses the platform `LocationManager`
  GPS provider (WGS84); search ranking uses a one-shot network/GPS fix. Works on phones
  sold in China without GMS.
- **Coordinate boundaries.** Fix WGS84 → gateway request WGS84 → route geometry GCJ-02
  → round screen GCJ-02. Only the phone's route overview and Web Mercator tile
  addressing convert between GCJ-02 and WGS84, once.
- **Pairing.** The first connection shows the system pairing prompt (LE Secure
  Connections, Just Works); tap "Pair". The firmware requires encryption and the app never
  bypasses it. If pairing breaks, remove MOTO GPS in system Bluetooth settings, clear the
  bond on the screen and retry; the device page also offers "forget this screen".
- **MTU.** The app requests 517 and frames by `min(MTU-3, 512)`; ATT MTU 23 works too.
- **Locked-screen rides.** "Start navigation" starts a `location|connectedDevice`
  foreground service with a persistent notification (remaining distance, "end
  navigation") and a bounded partial wake lock for the ride only. "Allow all the time"
  location is not required. Aggressive battery managers on some Android skins may still
  need MOTO GPS set to unrestricted background activity.
- **Music.** With notification access granted, the app reads the active media session
  and runs previous / play-pause / next from the round screen. Works with players that
  publish a media session (NetEase Cloud Music, QQ Music, Spotify, …). "Like" is reported
  unavailable. Notification content is never read; no accessibility service is used.
- **Route overview** is drawn locally (routes, endpoints, scale bar); no third-party map
  SDK or key.

## Build

Requires JDK 17+ and the Android SDK.

| Component | Version |
| --- | --- |
| Gradle Wrapper | 8.14.3 |
| Android Gradle Plugin | 8.13.2 |
| Kotlin | 2.2.21 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 (Android 8.0) |
| NDK | 28.2.13676358 (16 KB page aligned by default) |
| CMake | 3.22.1 |
| ABIs | arm64-v8a, armeabi-v7a, x86_64 |

```sh
cd platforms/android
./gradlew :app:assembleDebug
```

Private builds may bake in a gateway with `-PmotoGatewayBaseUrl=https://…/`; public
builds should leave it empty so riders configure their own in the app.

Release signing reads a local `keystore.properties` (`storeFile`, `storePassword`,
`keyAlias`, `keyPassword`) named by `MOTO_KEYSTORE_PROPERTIES`. Signing material never
enters the repository; updates must be signed with the same key.

## Tests

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest   # needs a phone or emulator
```

Unit tests port the iOS cases (gateway rules, gateway client, tile planning, offline
scenes, tile cache, handshake/pacing/map ACK policies, route preview) and read the same
shared fixtures. Device tests cover JNI golden bytes, a full NavCore ride, the protocol
engine against a **simulated round screen** built on the firmware's shared C++ codec
(handshake, snapshots, media, command ACK and duplicate ACK, map ACK, heartbeat, silent
link recovery, missing capabilities, MTU 20) and an offline UI smoke test.

## Status

| Item | Status |
| --- | --- |
| Build, unit tests, lint | Passing |
| Emulator (Android 16 / API 36) device tests and demo ride | Passing |
| Protocol run against the simulated screen | Passing |
| Real Android phone + real screen: pairing, navigation, locked screen | **Not yet verified** |
| Real road rides, battery life | **Not yet verified** |

The emulator has no Bluetooth radio, so scanning, pairing and MTU negotiation can only
be confirmed on hardware. This is a community Android port under the repository's
[PolyForm Noncommercial 1.0.0](../../LICENSE.md) licence, [NOTICE](../../NOTICE) and
[third-party notices](../../THIRD_PARTY_NOTICES.en.md).
