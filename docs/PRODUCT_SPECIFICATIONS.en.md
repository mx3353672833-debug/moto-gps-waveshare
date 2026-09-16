> **Language:** English · [中文](PRODUCT_SPECIFICATIONS.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS product description and technical parameters

MOTO GPS shows the route, the action at the next junction and the distance on the round display, and also provides a speedometer, heading and music control.
The current software development uses the Waveshare production board and an iPhone; the in-house A1 is another hardware candidate whose engineering files have already been saved.
The values below come from the repository's board-level notes, the A1 status sheet and the V3 mechanical specification. Items marked as design values have not yet been turned into volume-production measured values.

## The two hardware versions

| Item | Current Waveshare prototype | In-house H0175 EVT A1 candidate |
| --- | --- | --- |
| MCU | ESP32-S3, Waveshare ESP32-S3-Touch-AMOLED-1.75C production board | ESP32-S3-WROOM-1U-N16R8 |
| Round display | 1.75-inch, 466 × 466 AMOLED, CO5300 | Huaxia `H0175Y003AMT003 V1`, 1.75-inch, 466 × 466, CO5300 QSPI |
| Touch | CST9217, Waveshare official BSP | CST820, 31 Pin screen interface |
| Display format | Shared LVGL, 466 × 466 RGB565 | Same resolution as the design target, the board-level driver needs separate adaptation |
| Navigation positioning | iPhone Core Location; the phone computes the navigation state and sends it over BLE | The historical plan integrated an LC76GABMD standalone GNSS and went online through a phone hotspot |
| Motion and heading | On-board QMI8658; direction of travel from the phone | QMI8658A + IIS2MDCTR; whole-vehicle magnetic calibration still to be verified |
| Power | Waveshare on-board AXP2101 | BQ25628E + TPS63070 + TUSB320LAI design chain |
| Board body | The production board outline follows the official board documentation | Nominal Ø52 mm, four-layer board |
| Current status | The firmware and iOS joint-debugging chain is established, continuous real-hardware testing | Schematic, PCB and manufacturing review material have been generated, production has not been released |

Sources: [Waveshare firmware notes](../platforms/esp32/README.en.md), [A1 status sheet](../hardware/rev_a/H0175_EVT_A1_STATUS.en.md),
[historical A1 delivery notes](technical-proposal/DELIVERY_NOTES_RevA0.en.md).

## Software and usage features

| Feature | Current implementation |
| --- | --- |
| Destination search | Location-biased search on the phone; falls back to an area or nationwide search when there are no nearby results; keeps at most 8 recent places |
| Route preview | Full-route MapKit preview, at most 3 AMap candidate routes, compared by time, distance and traffic conditions before being started manually |
| Navigation | Shared C++ route progress, arrival detection, consecutive off-route confirmation, reroute, stale-response discard and traffic refresh |
| Round-display map | White selected route, heading up; grey roads and buildings are provided by the local map window |
| Offline base map | Jinan SQLite vector package: 47,468 roads, 26,702 buildings; the phone clips the nearby window and sends it |
| Page interaction | Swipe left and right to switch between the navigation, speedometer, compass and optional music pages; the page dots hide after 5 seconds |
| Music | Apple Music previous track, play/pause, next track and track state |
| Communication | BLE v1; CRC, fragmentation and reassembly, heartbeat, session validation and a two-phase handshake |
| Power on / off | Startup with the logo on a black background; holding Waveshare PWR for 3 seconds for software power-off, 4 seconds for the PMIC hardware fallback configuration |

The offline base map handles the surrounding-scene display and does not mean nationwide offline navigation. Normal route planning and rerouting use the online gateway;
real navigation does not automatically turn into a demo route. The compass currently mainly expresses the direction of travel; a stationary true north reference and general third-party music control still have to be extended.
The speed-limit sign is only shown when trustworthy data exists; the numbers in the first image are illustrative.

## V3 in-house enclosure parameters

| Item | Archived design value |
| --- | --- |
| Body diameter / thickness | Ø61 × 16 mm; total thickness about 19 mm including the mount |
| Front bezel / rear shell | Continuous aluminium front bezel, front bezel height 3 mm; plastic rear shell depth 13 mm |
| Fastening | Four M1.6 × 6 mm screws, arranged at 45°/135°/225°/315°, PCD 55.40 mm |
| Screen cover glass / pocket | Cover glass Ø48.96 ±0.05 mm; glass pocket Ø49.26 mm |
| Front opening | Ø45.00 mm |
| Mount | Replaceable Garmin Edge device-side quarter-turn male tabs |
| Tab envelope | Guide post Ø24.9 × 3.0 mm, maximum swing diameter Ø28.6 mm, tab band width 11 mm, thickness 1.5 mm |
| Fit reference | Original Garmin P/N 010-11430-00 base, the actual tolerances still need gauge verification |

These are archived V3 design values and must not be applied to the Waveshare production board or the silver first image.
For the source model and drawings see the [V3 mechanical specification](../hardware/mechanical/V3_MECHANICAL_SPEC.en.md) and the
[design notes](../hardware/mechanical/V3_DESIGN.md).

## Verification records and parameters still to be verified

The A1 status sheet records 93 PCB footprints, 458 pads, 1555 track segments and 189 vias,
with zero ERC, DRC, unconnected and schematic-consistency errors in KiCad 10.0.6.
These are archived electrical rule check results; the physical screen/FPC, power switching, RF, thermal behaviour, on-bike vibration and the mount still have to be tested.

Battery life, complete-unit weight, water-resistance rating, outdoor brightness, GNSS/compass accuracy and the final measured frame rate have no unified release data yet,
and this page does not fill in guessed values. For the software's locked-screen background behaviour and long-run BLE performance, [current known issues](KNOWN_ISSUES.en.md) is authoritative.
