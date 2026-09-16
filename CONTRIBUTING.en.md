> **Language:** English · [中文](CONTRIBUTING.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Contributing

Bug reproductions for noncommercial use, documentation corrections and code improvements are welcome. Before submitting a contribution please read LICENSE.md
and THIRD_PARTY_NOTICES.md; original contributions must be distributable under this project's licence, and third-party content must state its source.

- First state the target board model, the iOS / ESP-IDF version, the reproduction steps and the actual result.
- When changing the shared protocol, update both the C++ / iOS ends and the golden fixture tests together.
- For route/position bugs, use public POIs or anonymised data; do not publish your home location or a complete trip track.
- Do not submit keys, Apple signing certificates, device identifiers, service credentials or unauthorised map caches.
- List the tests actually run in the PR; a successful compile cannot replace Bluetooth, route-correctness or on-bike acceptance.
- Map geometry must not use random lines or invented buildings to fill gaps in the real data.

For security issues, do not paste keys or details that could be used to attack an existing service into a public issue.
This repository is a development snapshot and does not currently promise commercial maintenance, a response deadline or stability.
