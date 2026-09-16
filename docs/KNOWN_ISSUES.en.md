> **Language:** English · [中文](KNOWN_ISSUES.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Known issues and release boundaries

What is published here is a development snapshot of the Waveshare production-board verification line, not a verified mass-production version.

| Status | Issue | Current conclusion |
| --- | --- | --- |
| Open | The round display stays on `WAITING FOR PHONE` after leaving home | The BLE / iOS background state recovery still needs further isolation; it is not proven that the phone has no network. The current version does not guarantee that the connection survives screen lock for a long time |
| Open | No selectable route from Linyi to Jinan West Railway Station | Needs reproduction and a check of the position, the gateway request/response and the upstream service state; it cannot be asserted outright that AMap does not support cross-city routes |
| Pending regression | The route on real hardware differs from the app preview, detours occur | Route-selection transfer already has logic and automated tests, but the corresponding cross-city on-bike regression has not been completed |
| Pending regression | Page flipping / a sense of tearing during fast turns | Double buffering, interpolation and CO5300 TE synchronisation logic already exist; the actual smoothness, sustained frame rate and power draw still need to be measured |
| Inherent boundary | Absolute north when stationary | The QMI8658 has only an accelerometer and a gyroscope and no magnetometer. With the phone in a bag, the phone body's orientation must not be treated as the direction the bike is facing |
| Inherent boundary | Background road network outside Jinan | The bundled OSM data covers Jinan only; other cities have no equivalent offline grey-road/building coverage, which does not mean the route API does not support that city |
| Inherent boundary | Speed limits, missing buildings | Depends on real data coverage, no fictitious content is drawn in to fill the gaps; the route service does not always give a trustworthy legal speed limit |
| Not accepted | Battery life, power-off on combined USB/battery supply, water resistance, vibration resistance, riding safety | The production development board is not a project-certified automotive-grade device |

The on-device demo uses real OSM geometry near public POIs, which allows checking the screen contents and how the actions relate, but it cannot be acceptance for live traffic conditions,
real positioning accuracy, background BLE, phone cellular handover or real navigation correctness.

When debugging BLE, stop first: confirm whether the app has returned to the foreground, the iOS Bluetooth/location permissions, the round-display connection state,
and whether the protocol handshake has completed, then compare against the gateway health check. Do not use a restart that "sometimes works" in place of a root-cause record.
Before submitting logs, delete personal information such as coordinates, device UUIDs, serial numbers and service tokens.
