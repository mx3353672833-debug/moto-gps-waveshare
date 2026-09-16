> **Language:** English · [中文](RELEASE_CHECKS.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# First public snapshot check · 2026-09-05

The checks were completed inside a separate public source copy; the original project's build directory was not reused.

| Check | Result |
| --- | --- |
| CMake C++ native tests | 8 / 8 passed |
| Node backend tests | 33 / 33 passed; the real AMap service was not accessed |
| Swift Package core tests | 10 / 10 passed |
| C++ / Swift Jinan demo fixture consistency | `generate_jinan_demo_fixture.mjs --check` passed |
| OSM SQLite integrity | Passed; 47,468 roads, 26,702 building outlines |
| ESP32 full build | ESP-IDF 5.5.5 successfully generated the bootloader, partition table and application; the device was not flashed |
| iOS app full build | Xcode 26.6, iOS Simulator, signing disabled; the build succeeded, it was not installed on a phone |
| Web full build | Emscripten / CMake successfully generated the shared LVGL Wasm runtime |
| Release content scan | Gitleaks 8.30.1: no secrets found; local paths, signatures, private service addresses and file scope were also checked |

The build has warnings such as third-party headers, which does not amount to a zero-warning build. The results above apply to that public snapshot only and
do not mean real-road, iPhone screen-lock keep-alive, cross-city route, power consumption or water-resistance acceptance has been passed.
For the latest known issues see `KNOWN_ISSUES.md`; for the reproduction commands see `TESTING.md`.
