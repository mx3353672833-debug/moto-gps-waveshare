> **Language:** English · [中文](H0175_EVT_A1_STATUS.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS H0175 EVT A1 status sheet

Date: 2026-09-03  
Status: **engineering candidate generated; JLCPCB CAM/DFM and price pre-review can be done, but payment for production is not yet allowed.**

## What has been completed this round

- The screen is frozen to Huaxia Caiguang `H0175Y003AMT003 V1`, 1.75 inch, 466 × 466, CO5300 QSPI, CST820 single-point touch.
- J3 uses the `C5343228 / XUNPU FPC-0.3FX-31PWBH10` candidate socket. The current logical mapping is `screen pin = 32 - J3 pad`; continuity on the physical screen, the flex cable and the connectors at both ends is still the final authority.
- The screen's `TP3.3 / IOVCC / VCI / VBAT / VCI_EN` are all supplied in A1 from the filtered `3V3_DISPLAY`. Page 7 of the vendor's controlled material explicitly allows VBAT 3.3 V, so the first board uses the minimum-component solution; the material also carries a contradictory 3.7–4.5 V description, so low temperature, high brightness and cold start must be verified on the physical screen.
- The IMU is changed to `QMI8658A / C3021082`, reusing the already reviewed 14-LGA pads. The footprint, pin-by-pin definition, WHO_AM_I and register map of A/C agree, so it is a controlled drop-in for EVT; the firmware still has to regress the ID, both interrupts, the axes and the ranges.
- The magnetometer is changed to `IIS2MDCTR / C2655002`. The current circuit uses only I2C, so it is compatible with the original LIS2MDL wiring; the calibration in the firmware and inside the complete aluminium enclosure still needs measurement.
- The four buttons are changed to `EVQP7C01P / C388883`. The circuit pads, actuation force, height and travel match; the actuator is about 0.1 mm lower than the original K version, and the button-post preload must be verified on an enclosure sample.
- The official MPN for USB-C `C319148` is corrected to `U262-161N-4BVC11`. The `16XN` in the project footprint file name is a historical name; the actual pads were reviewed against C319148.
- R19 is changed to `5.23 kΩ / C477746`; the GNSS RF ESD is changed to `LESD8LL5.0CT5G / C2987702`, SOD-882, about 0.3 pF maximum.
- The board outline is unified with the V3 four-screw enclosure: PCB nominal Ø52 mm, screw-axis PCD 55.40 mm, and the base keeps the Garmin quarter-turn mount.

## Automated verification results

| Check | A1 result |
| --- | ---: |
| Schematic components / connections | 115 records / 354 wires |
| PCB footprint / pad | 93 / 458 |
| Named nets | 74 |
| Copper layers | 4 |
| Tracks / vias / zone | 1555 / 189 / 2 |
| KiCad ERC | 0 |
| KiCad full-trace DRC | 0 |
| Unconnected | 0 |
| Schematic / PCB consistency | 0 |
| BOM / CPL reference and quantity reconciliation | no additional errors |
| Automated tests | 9 / 9 passed |
| Candidate package SHA-256 | all passed |

The verification version is pinned to KiCad `10.0.6`. The corresponding reports are in the engineering candidate package's `reports/` and `references/`.

## Why payment for production is still not allowed now

1. Once the physical screen arrives, the screen-side connector, whether the 31P flex cable is same-side or opposite-side, the gold-finger thickness, Pin 1 and the GND connectivity must be confirmed; until then one wrong insertion can directly destroy the screen.
2. The screen's 3.3 V solution needs current-limited power-up, low-temperature, high-brightness white screen and repeated cold-start testing.
3. The USB-C, flex cable, buttons, battery, antenna and enclosure Z height need a physical closed loop; the current 3D models do not cover all the in-house footprints.
4. The `BQ25628E + TPS63070` power chain needs cold start, charge/discharge switching, a 1.5 A load step and sealed temperature-rise testing first.
5. The LC76G's 3 mm surrounding clearance is a DFM recommendation Quectel makes for soldering and rework, not RF electrical clearance; A1's near-end RF matching parts such as L3/R33 cannot satisfy it at the same time. The 5 EVT boards may only be placed once JLCPCB explicitly accepts a local DFM/rework exemption, and production Rev B will be re-laid-out. The GNSS 50 Ω/antenna, the ESP32 external 2.4 GHz antenna and the complete-product RF are still release gates.
6. The QMI8658A, IIS2MDC and EVQP7C01P are controlled EVT substitutions and need firmware/mechanical regression on the first board.
7. On the day of ordering, stock, minimum placement quantity and substitutes must be re-checked on the JLCPCB page, and automatic substitution of key chips must not be ticked.

## Purpose of the files

- `hardware/rev_a/board/build/h0175_evt_a1/`: the A1 editable KiCad project.
- `hardware/manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/`: the complete review candidate, containing the schematic, PCB, BOM, CPL, Gerber/drill files, assembly drawings, 3D previews and hashes.
- `review_gerbers_DO_NOT_ORDER/`: for CAM/DFM/quotation only. A "production release" ZIP is deliberately not provided at present, to avoid a mistaken order before the physical gates are closed.

## Shortest set of actions once the physical screen arrives

1. Without powering up, use a multimeter to confirm the screen's several GND groups, Pin 1 and the end-to-end relationship of the 31P flex cable.
2. Record the flex thickness, contact face, insertion direction, total screen thickness and connector height with callipers.
3. Verify 3.3 V screen startup with a current-limited supply; start with a black screen / low brightness, then a high-brightness white screen and touch.
4. Feed the measured values back into J3/the enclosure; re-run ERC, DRC, BOM/CPL and the Gerber export.
5. Only after all physical gates are closed, generate the formal `FOR_FAB` JLCPCB PCB+SMT package.
