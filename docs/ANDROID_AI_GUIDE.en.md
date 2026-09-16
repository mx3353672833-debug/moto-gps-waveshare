> **Language:** English · [中文](ANDROID_AI_GUIDE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Develop Glimpse for Android with AI

If you want to connect an Android phone to the round display, you can start with the existing source.
The repository does not yet include an installable Android App. This guide provides a development
path and a prompt you can give directly to an AI coding tool; it is not a download page.

The goal is a native Android companion App that communicates with the existing Waveshare firmware:
the phone handles positioning, search and route planning, then sends the data needed by the display
over Bluetooth. The existing iOS App can serve as a reference for behaviour.

## Let's build the Android version together

You're welcome to help develop Glimpse. If you build an Android app using this guide, we'd love you
to share its source on GitHub so other Android users can build, use and improve it. Even if you've
only finished Bluetooth connectivity, a screen or support for a particular phone, please feel free
to share your progress. You do not need to wait until every feature is complete.

Start by discussing your plans or sharing your public repository in
[Issues](https://github.com/mx3353672833-debug/moto-gps-waveshare/issues), then contribute features,
fixes and documentation back through Pull Requests. Include build steps, tested phone models,
the corresponding firmware and known issues so others can pick up the work. We'll retain credit
for the people who contributed, and welcome anyone interested in helping maintain the Android
version over time.

This is an invitation; participating is your choice. The existing project and third-party licences
still apply. See [Contributing](../CONTRIBUTING.en.md) for contribution requirements.

## What you need first

- A Windows, macOS or Linux computer capable of running Android Studio, plus an AI coding tool that
  can read and modify project files.
- An Android phone with BLE support. An emulator can check UI and some logic, but Bluetooth and
  navigation with the screen locked require a physical device.
- A Waveshare **ESP32-S3-Touch-AMOLED-1.75C** running firmware that matches the repository version.
  See the [DIY guide](WAVESHARE_DIY_GUIDE.en.md) for the board, backups and firmware; its iPhone /
  Xcode sections do not apply to Android.
- Your own [navigation gateway](GATEWAY_SETUP.en.md). Keep the AMap Web Service key on the backend,
  not in the APK. The website is not a free public gateway for arbitrary third-party Apps.

### Put the project on your own GitHub account

1. Open the [project repository](https://github.com/mx3353672833-debug/moto-gps-waveshare) and click
   **Fork** to create a copy under your account.
2. Copy the repository URL from your Fork and download it with
   `git clone --recurse-submodules YOUR_REPOSITORY_URL`. If you already cloned it but are missing
   submodules, run `git submodule update --init --recursive` from the repository root.
3. Open the whole repository in your AI coding tool and paste the complete prompt below. Providing
   only a webpage link may not give the tool access to all the source files.

For forks, commits and Pull Requests, see the
[official GitHub guide](https://docs.github.com/en/pull-requests/how-tos/work-with-forks/fork-a-repo).

## Prompt to copy into your AI coding tool

Copy the entire block below. If you do not know what to enter for the phone or development environment,
leave those fields blank and let the AI inspect the project before asking you.

```text
Develop a native Android companion App in the Glimpse / MOTO GPS project I have opened.
Upstream project: https://github.com/mx3353672833-debug/moto-gps-waveshare
Target hardware: Waveshare ESP32-S3-Touch-AMOLED-1.75C, using the existing Waveshare firmware.
My Android phone model / OS version: (fill in; if blank, ask me when physical-device testing is needed)
My GitHub Fork: (fill in; if origin already exists, verify it first and do not push to upstream)

Create the project, implement the code and run the checks that can actually be executed. Do not stop
at a plan or a set of static screens. Follow the stages below. At each stage, report what is
implemented, which tests you ran, what remains unverified and the next stage's tasks. If hardware,
permissions, SDKs or keys block progress, identify exactly what is missing and first finish the work
that does not depend on it. Never fabricate passing results.

1. Read the source and establish the actual interfaces
Read the repository's AGENTS.md if present, README.md, LICENSE.md, NOTICE and THIRD_PARTY_NOTICES.md,
along with these files and directories:
- docs/ANDROID_AI_GUIDE.en.md, docs/GATEWAY_SETUP.en.md and backend/README.en.md
- shared/protocol/ble-navigation-v1.md and shared/protocol/fixtures/ble-navigation-v1.golden.txt
- shared/ble_protocol, shared/nav_core, shared/nav_app and shared/coordinates
- The route-request, route-options, route-bundle and map-scene schemas under shared/protocol
- platforms/ios/App/Adapters/BLE, platforms/ios/App/Adapters/Navigation,
  platforms/ios/App/Adapters/Location and platforms/ios/App/Adapters/OfflineMap
- platforms/ios/App/AppModel.swift and the relevant AppTests
- platforms/esp32/main/phone_nav_bridge.cpp and ble_nav_transport_nimble.cpp in the same directory
- tests/native, backend/test and .github/workflows/checks.yml
Compare the documents with the current implementation and flag discrepancies. Do not infer protocol
details or field names from screenshots.

2. Project structure and reuse
Create a Kotlin + Jetpack Compose project under platforms/android/, using native Android interaction
patterns. Separate UI, navigation sessions, location, gateway access, BLE and map storage. Do not make
an Activity manage every connection state directly.
Prefer NDK + CMake + JNI to reuse the shared C++ navigation core, coordinate conversion and BLE codecs.
JNI is a new Android adaptation layer. SwiftUI, CoreBluetooth, CoreLocation, MapKit and the
Objective-C++ bridge cannot be copied directly to Android. Do not put the ESP32 display driver or
LVGL UI into the phone's navigation engine. If a pure-logic module needs a Kotlin rewrite, explain
why first and verify parity with the existing golden bytes and fixtures.
Preserve existing iOS, ESP32 and backend functionality. By default, do not change the display protocol
or require users to flash Android-specific firmware.
Use an officially supported, stable toolchain combination. Record the JDK, Gradle, AGP, Kotlin, SDK,
NDK and CMake versions, and commit the Gradle Wrapper. Evaluate API 26 as an initial minSdk option,
then choose based on the libraries and actual devices. Do not claim compatibility with every phone.

3. Connect the phone to the display first
Implement Bluetooth availability checks, scanning by service UUID, explicit device selection,
pairing/encryption, service discovery and subscription to TX notifications.
Keep the existing UUIDs, version negotiation, capability intersection and complete handshake. Wait
for the device's final Ready confirmation before sending application data.
Respect the negotiated MTU; do not assume it is always 512. Protocol frames include headers and CRC,
so do not write JSON directly to a Bluetooth characteristic.
Queue GATT operations serially, without interleaving fragments from different complete messages.
Handle transmission backpressure, CRC, sequence numbers, reassembly timeouts, application ACKs,
retry limits, queue clearing on disconnect, a new handshake and resending the latest state.
A successful GATT write is not an application ACK.
Heartbeat timing must use elapsed time in the current application session, not phone system uptime.
Verify encoding and decoding against the protocol's golden bytes, then use a phone and a real display
to test connection states, disconnection and recovery. Do not disable firmware encryption requirements
to work around pairing failures.

4. Add real navigation
Implement destination search, location bias, candidate routes, time/distance previews, and starting
and ending navigation. Make the gateway address configurable and prompt for configuration if it is
empty. Use the backend's existing HTTP endpoints and schemas; do not invent endpoints.
Choose a location implementation for the actual device environment. Do not assume every Android phone
in mainland China has Google Play services. Preserve accuracy, timestamps, speed and course, and
handle stale or inaccurate positions explicitly.
Label WGS84 and GCJ-02 at every boundary. Confirm the location provider's coordinate system first.
Gateway route inputs are WGS84 and route geometry is GCJ-02. Do not convert twice or overlay the two
coordinate systems without conversion.
Pass positions and routes to the shared navigation core, then send snapshots, route windows and
off-route/traffic state. Clearly label demo mode as simulated. Real-location or network failures must
not automatically switch to fake positions or routes.
Do not invent traffic-light countdowns, road speed limits or motorcycle-restriction capabilities.
Express missing data as unknown according to the protocol.

5. Maintain location and Bluetooth during navigation
Read the current official Android documentation and handle Bluetooth scan/connect permissions,
location, notifications and foreground services according to the OS version and targetSdk.
Distinguish BLE scanning permissions from navigation location permissions. Using neverForLocation
for scanning does not remove navigation's need for location permission.
After the user taps Start navigation while the App is in the foreground, start a foreground service
that meets the requirements for location / connectedDevice use. Show an ongoing notification with
an end-navigation action. When navigation ends, release location updates, connections/services and
unnecessary wake resources.
Explain separately any scenario that truly requires background location permission. Do not make
Allow all the time a blanket requirement.
Handle permission denial, approximate-only location, Bluetooth being turned off, screen-off use,
task switching, process termination and reconnection. Follow background-start restrictions and do
not promise that the App can never be killed. Record the scope and battery use of lock-screen
navigation tests on actual devices.

6. Add map downloads and optional music control afterward
Reuse the existing map/city APIs for online surrounding maps by default, city or district downloads,
route-corridor downloads, pause/resume, deletion and cache budgets. Tile IDs use WGS84 Web Mercator;
response geometry is GCJ-02. Read it according to the schema.
Preserve source information, timestamps and attribution. Respect MapScene feature/point limits and
prioritise correct routes and stable transmission. Offline packages contain background roads and
buildings only; do not describe them as complete offline route planning.
For phone previews, choose an Android map library/SDK usable on the target devices and check its
licence, key restrictions and coordinate system.
Leave music for a later stage. Evaluate what Android's supported media-session APIs and the user's
granted permissions allow. Do not copy the iOS Apple Music API, assume every music App is controllable,
or use accessibility permissions to bypass restrictions.

7. Testing and delivery
First deliver a version that builds, installs, connects to the display and passes protocol self-checks.
Then add real navigation, followed by map downloads.
At every stage, update platforms/android/README.md and development status, distinguishing implemented,
automated tests passed, physical-device tests passed and not yet verified.
Cover golden bytes, coordinate boundaries, JNI lifecycle, GATT queuing and reconnection, invalid
locations, stale responses, incorrect gateway configuration, network loss and download recovery.
Keep upstream C++, backend and Swift core tests passing and do not break existing CI.
Android CI must start from a clean checkout, install pinned toolchains, fetch submodules, and run
Gradle build, unit-test and lint tasks that actually exist.
Record emulator UI checks separately from real BLE and lock-screen tests. Without a physical device,
do not mark hardware acceptance complete. Give me reproducible installation steps, build commands,
test results, permission explanations and current limitations.

8. Share the work on GitHub
Develop on a branch in my Fork. Keep upstream attribution, LICENSE, NOTICE, third-party licences and
map-data licences. Upstream original code currently uses PolyForm Noncommercial 1.0.0. Do not change
the whole repository to MIT/Apache or remove noncommercial restrictions without authorisation.
Link the README back to upstream and identify this as a community Android port, not a version that
has already received official upstream support.
In the README, express an interest in collaborating on Glimpse and retain attribution to the actual
contributors; prepare contribution notes and a list of changes suitable for upstream.
Do not commit .env, local.properties, keystores, signing passwords, tokens, real addresses/traces or
raw device logs. Keep the AMap Web Service key on the server. Restrict SDK keys that must be included
in the client according to the SDK's rules; do not treat them as credentials that can be kept secret.
After tests pass, commit and push the changes to my Fork and provide a commit link. You may prepare
a Pull Request to upstream.
Publish a tested APK with GitHub Releases, identifying its version, source commit, compatible firmware,
tested phones, limitations and SHA-256. Public APKs must use the maintainer's own stable release
signing key, with a backup of that key. Clearly label temporary debug builds as test-only.
Store signing material only in a protected local location or appropriate CI Secrets. Workflows for
external Pull Requests must not have access to release keys.
Until real testing is complete, keep the release in draft/pre-release status and do not promote it
as a finished stable Android release.

Start by inspecting the repository and development environment. List the reusable interfaces and
the specific changes for the first stage, then begin implementation.
```

## When is it ready for others to try?

| Stage | Required result |
| --- | --- |
| Project and Bluetooth | Build and install from a clean checkout; complete pairing, the full handshake, protocol golden-byte checks and disconnect recovery on a physical device |
| Real navigation | Real positioning and gateway route selection; the selected phone route matches the display, with explicit states for weak networks and invalid positions |
| Screen lock and maps | An ongoing notification and a stop action; recorded lock-screen test duration; resumable city/corridor downloads with clear offline limitations |
| Community release | Fork source, reproducible instructions, CI results, a signed APK, SHA-256, tested devices and an issue list |

An APK installing successfully does not replace Bluetooth or riding validation. Initial device checks
can be done while stationary or walking. Record the OS version, firmware commit and reproduction
steps, and remove personal addresses, traces and device identifiers before publishing logs.

## Sharing code and licences

Forks, learning, DIY and contributions are welcome. The upstream project's original work is currently
available under [PolyForm Noncommercial 1.0.0](../LICENSE.md). When redistributing or releasing derived
versions, retain [NOTICE](../NOTICE) and the [third-party notices](../THIRD_PARTY_NOTICES.en.md).
These are the existing project's terms; this guide does not change them into a licence permitting
arbitrary commercial use.

For a first release, use a pre-release clearly stating its tested scope, with an APK, matching source
and checksum. See [GitHub's release guide](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)
for publishing and [GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)
for automated signing configuration. To contribute back to the main project, open a Pull Request
from your development branch with protocol-compatibility and physical-device test results.

## Official references for the AI to consult

The following references were checked on 2026-09-16. Recheck them for the actual OS version and
targetSdk during implementation.

- [Android BLE permissions](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions):
  scan/connect permissions and compatibility with older systems.
- [Android background BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background):
  background communication options and restrictions.
- [Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types):
  service types and prerequisites for navigation location and device connections.
- [NDK introduction](https://developer.android.com/ndk/guides) and
  [CMake integration](https://developer.android.com/ndk/guides/cmake): integrating existing C++ libraries into Android.

Protocol bytes, coordinates and gateway fields must follow the repository's current implementation
and tests, not examples an AI recalls from memory.
