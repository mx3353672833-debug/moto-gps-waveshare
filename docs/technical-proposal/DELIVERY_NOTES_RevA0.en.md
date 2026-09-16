> **Language:** English · [中文](交付说明_RevA0.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS H0175 EVT A1 stage delivery notes

Delivery date: 2026-09-03  
Electrical version: H0175 EVT A1 (derived from the frozen Rev A0 R4 routing baseline)

> **A frozen historical delivery note.** This directory describes the standalone GNSS / hotspot hardware candidate of 2026-09-03, and is not the execution baseline of the current Waveshare 1.75C + native iOS App software prototype. For the current behaviour, the demo / real navigation boundary, nearby place search and BLE reconnection state, the [Waveshare + iPhone prototype baseline](../WAVESHARE_IOS_PROTOTYPE.en.md) and the repository top-level README are authoritative; the PDF, DOCX and source ZIP inside this delivery are kept as an archive from that time.

## What to look at first

1. Open `MOTO_GPS_摩托车便携导航终端技术方案_H0175_EVT_A1.pdf` for the product definition, feasibility, system architecture, hardware, the V3 enclosure, the Garmin mount, risks and the phase plan.
2. `02_机械设计/二维图纸` holds the V3 assembly drawing; `02_机械设计/三维模型` holds the STEP/STL.
3. `03_网页导航/phone-web` is the built phone web edition, containing the WebAssembly runtime files.
4. `04_工程源码/MOTO_GPS_RevA0_源代码.zip` is the engineering source archive for this delivery, containing the pinned LVGL source and not depending on the original working directory.
5. `08_RevA1_H0175_EVT主板审阅包` is the complete electrical engineering candidate for this delivery, containing openable KiCad source files, ERC/DRC, Gerber, drill files, BOM, CPL, assembly PDF and front/back previews; the directory name and the files are all explicitly marked `DO NOT ORDER`.
6. `09_嘉立创A1仅询价` provides the Gerber ZIP, BOM and CPL together; they are only for checking the PCB+SMT price, and must not be paid for or put into production.

## What is frozen in this delivery

- V3 appearance: a continuous dark grey aluminium front bezel, four diagonally exposed functional screws, round black glass, a button on the right side, an amber index mark at 6 o'clock.
- Main body envelope: `Ø61 × 16 mm`; about `19 mm` total thickness including the Garmin mount.
- Rear mounting: a Garmin Edge device-side quarter-turn male tab, intended to fit directly into a Garmin base.
- Garmin A0 mount: guide post `Ø24.9 × 3.0 mm`, tab rotation envelope `Ø28.6 mm`, tab width `11 mm` and thickness `1.5 mm`.
- Round-display software: four pages of navigation, speedometer, compass and music; shared C/C++, NavCore, LVGL and WebAssembly.
- Navigation path: the phone provides the hotspot and the destination input; the terminal navigates continuously with its own GNSS, IMU and compass, and obtains AMap routes, traffic conditions and off-route rerouting through the hotspot.
- H0175 EVT A1 main board: derived from the complete `Ø52 mm` four-layer R4 routing baseline, with `ESP32-S3-WROOM-1U-N16R8` as the MCU on the first board, integrating LC76GABMD, QMI8658A (C3021082), IIS2MDCTR (C2655002), charging / buck-boost, USB-C and a 31 Pin display interface. The QMI8658A and IIS2MDC are EVT controlled substitutions and must each complete device ID / interrupt / axis regression and final in-enclosure, on-bike calibration.
- SW1–SW4 use EVQP7C01P (C388883); the actuator is `1.1 ± 0.1 mm`, and the enclosure button post pre-load, the full travel and the return are subject to physical verification.
- The official MPN of J1 is `U262-161N-4BVC11`; the historical KiCad footprint name still contains `16XN`, and that historical library name must not be used as the purchasing MPN.
- H0175 EVT A1 follows page 7 of the controlled display PDF and supplies TP3.3/IOVCC/VCI/VBAT/VCI_EN directly from `3V3_DISPLAY`; no unverified 4 V selector has been added, and the first board must pass cold-start, low-temperature and maximum-brightness tests.

## Verification results

- Native C++: 4/4 tests passed.
- Backend: 15/15 tests passed.
- Hardware release gate script: 9/9 tests passed.
- H0175 EVT A1 schematic: 93 production components; KiCad 10.0.6 ERC `0`.
- H0175 EVT A1 PCB: derived from the frozen R4 routing, 458 pads, 1555 track segments, 189 vias, 2 copper zones; full-track DRC `0`, unconnected `0`, schematic consistency errors `0`.
- BOM/CPL: the 57-line grouped BOM reconciles completely with the 93 placement references, with no missing, extra, duplicate or wrong-quantity items.
- A1 review package: the SHA-256 of the manifest files must be re-verified after the delivery is copied.
- V3 CAD: all STEP solids are valid.
- Garmin male tab envelope: `28.6 × 24.9 × 3.0 mm`.
- Complete-unit CAD envelope: `61 × 61 × 19 mm`.
- Technical proposal: A4, 20 pages; provided as PDF, DOCX and Markdown.

## Important boundaries

- The current version is no longer a blank PCB skeleton, but a complete, routed H0175 EVT A1 electrical engineering candidate that has passed ERC/DRC; it is however still marked `NOT FOR FABRICATION` and is not a production sign-off package that can be uploaded to place an order.
- Garmin has published the Edge quarter-turn compatibility system and the mounting method, but has not published the complete production tolerances. The locking slot, the lead-in fillets and the moulding shrinkage must be frozen against a physical gauge of the genuine Garmin P/N `010-11430-00` base.
- H0175 EVT A1 has generated review Gerber, drill files, BOM and CPL, but the physical, footprint, power, RF, stock re-check or sourcing gates are not all closed yet, and the release script deliberately refuses to generate a formal order ZIP.
- The display, battery, GNSS antenna, FPC, USB, sealing, QMI8658A firmware, IIS2MDC compass, EVQP7C01P buttons, hotspot, temperature rise, water resistance and on-bike vibration all require physical verification.
- Until tested, do not claim IP67, battery-life hours, GNSS accuracy, compass accuracy or compatibility with all third-party Garmin bases.

## Delivery contents

- `00_先看这里`: this note, the version manifest, the verification summary and SHA-256.
- `01_技术方案`: PDF, editable DOCX, Markdown, and the `assets` proposal figures the Markdown can reference directly.
- `02_机械设计`: V3 appearance, 2D drawings, parametric CAD, STEP/STL, the PCB envelope and verification files.
- `03_网页导航`: the finished phone web page, the WASM runtime and the backend deployment files.
- `04_工程源码`: the complete source ZIP, with the pinned third-party LVGL version.
- `05_验证记录`: CAD, ERC/DRC and software test summaries.
- `08_RevA1_H0175_EVT主板审阅包`: A1 KiCad source files, review manufacturing files, verification reports, assembly material, previews and a hash manifest that cannot be overwritten.
- `09_嘉立创A1仅询价`: the Gerber ZIP for quotation, the complete BOM/CPL and a note on the operational boundaries.

## Entry points for the next stage

1. Buy one genuine Garmin `010-11430-00` as the mount gauge.
2. Freeze the physical display, FPC, battery and GNSS antenna.
3. Complete the BQ25628E + TPS63070 + TUSB320LAI power test coupon and a second-person pin-by-pin sign-off for every in-house footprint.
4. After closing the purchasing / physical / power / RF status of H0175 EVT A1, have `fab_release.py` generate the formal production package from the same source state, and then order 5 PCBA boards.
5. Use the PA12/PC-ABS EVT enclosure for RF, compass, temperature rise, spray, Garmin mount and real-vehicle vibration verification, and revise Rev B accordingly.
