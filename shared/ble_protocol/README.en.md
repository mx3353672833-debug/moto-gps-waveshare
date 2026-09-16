> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Shared BLE protocol

`moto_ble_protocol` is the v1 binary codec shared by the phone and the ESP32. The public entry
point is `include/moto/ble_protocol/ble_protocol.hpp`, covering the fixed GATT UUIDs, the message
DTOs, CRC, frame fragmentation/reassembly, sequence numbers and the link watchdog.

For the protocol semantics, GATT permissions, ACK/timeout rules and the
`NavigationSnapshot -> nav::NavSnapshot -> NavPresenter` mapping see
[`../protocol/ble-navigation-v1.en.md`](../protocol/ble-navigation-v1.en.md). A cross-language
implementation should use the golden fixture as the byte-compatibility baseline and must not
rely only on its own language's round-trip.

The optional Jinan offline mini-map uses `MapScene (0x14)`: the phone clips and sends a fully
replacing road/building rolling window, and the ESP32 neither stores nor queries the city-wide
database. For the capacity, the generated sample and the boundary that is not yet wired up see
[`../demo_fixture/JINAN_OFFLINE_MAP_MVP.en.md`](../demo_fixture/JINAN_OFFLINE_MAP_MVP.en.md).

Building the codec on its own:

```sh
cmake -S shared/ble_protocol -B build/ble-protocol
cmake --build build/ble-protocol
```
