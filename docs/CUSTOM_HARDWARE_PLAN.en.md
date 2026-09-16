> **Language:** English · [中文](CUSTOM_HARDWARE_PLAN.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# In-house integrated hardware and enclosure execution plan

Version: H0175 EVT A1 electrical engineering candidate (derived from the frozen R4 routing baseline), 2026-09-03.

> **A paused historical engineering effort, not the basis for ordering today.** This document is based on the old premise that "no native app is developed and the device must have its own GNSS + hotspot connectivity". The project has since changed to a native iOS app providing positioning, routes, off-route handling and traffic conditions, with the Waveshare 1.75C only displaying over BLE. If the in-house PCB is revived later, the architecture, BOM, layout and cost review must be redone on the basis that "there is no LC76G by default and the iPhone is the navigation authority", and the existing production package must not be ordered directly.

## Conclusion

The project changed from "buy an ESP32 round-display development board and extend it" to "an in-house round main board + a separate display assembly + a custom enclosure". This is technically feasible and better suited to the final product than flying-wire mounting of GNSS, compass and antenna inside a finished development board.

Rev A does not aim for production on the first tooling. There must be at least one working PCBA round and one on-bike enclosure verification before deciding whether to machine the aluminium front bezel. The first order quantity is 5 PCBAs, first making the power, display, positioning, wireless and sensors reliable, then shrinking the size and refining the appearance.

The current H0175 EVT A1 already synchronises the display, sensors, buttons and procurement fields on the frozen R4 routing baseline, and keeps the complete schematic, the Ø52 mm four-layer PCB placement and routing, and the review manufacturing exports. For KiCad 10.0.6 the ERC, full-track DRC, unconnected and schematic / PCB consistency results are all 0; the 93 placed references in the BOM/CPL are also fully reconciled. A1 is still not a production sign-off: until the display / FPC, USB-C, battery, footprints, power coupon, RF, stock and complete-unit structural gates are closed, the release script refuses to generate a formal order ZIP.

## Product boundaries that cannot be dropped

- The iPhone can be locked in a bag, and the device reaches the backend and the AMap route service through the phone's hotspot.
- Without a native app, Safari / PWA cannot reliably keep providing positioning after the screen locks. The default version must therefore have its own GNSS soldered on; the "no GNSS" option is kept only as a DNP debug configuration and is not a product option.
- Live traffic conditions and off-route rerouting are requested by the device from the backend over the hotspot; while the network is temporarily down it keeps navigating along the cached route and handles pending reroutes and traffic refreshes as soon as it recovers.
- At high speed / normal riding, GNSS course-over-ground is the primary direction of travel; when stationary and at low speed, magnetometer + gyroscope fusion fills in, and you must not trust only the orientation of a phone sitting in a bag.
- The round-display product UI keeps using the shared LVGL source. The web, the presenter and the Waveshare hardware are now unified at 466 × 466; later in-house boards keep the same canvas, so no web-only screens appear.

## Rev A system architecture

| Subsystem | Preferred option | Design decision |
| --- | --- | --- |
| MCU | ESP32-S3-WROOM-1U-N16R8 | The Rev A0 first board uses a mature module to reduce the risk of 0.5 mm LGA, separate Flash / PSRAM, the crystal and RF; 16 MB Flash + 8 MB PSRAM, with an external 2.4 GHz FPC antenna. PICO is kept only as an option for a later board shrink |
| Display | H0175Y003AMT003 V1 fully laminated display assembly | 1.75-inch, 466 × 466, CO5300, QSPI; the supplier V1 drawing is already in the baseline |
| Touch | CST820 single-point capacitive touch | I²C; the pins, address, reset timing and touch coordinate direction are signed off on the first sample |
| Display interconnection | 31P module-side connector + 31P/0.3 mm FFC (to be confirmed on real hardware) | The drawing gives the 31-pin functions and a recommended connector, but Pin 1, the contact side and the final end-to-end order at both ends of the cable must be confirmed by continuity testing on real hardware; do not treat the on-paper reversal as a frozen conclusion |
| GNSS | LC76GABMD | The bare module is about 10.1 × 9.7 × 2.4 mm, not the development carrier board costing around CNY 150; it outputs position, speed and heading over UART |
| GNSS alternative | ATGM336H-7N76 | Used when JLCPCB stock / assembly conditions for the LC76G are unsuitable; the two are not soldered at the same time |
| GNSS antenna | Rev A: U.FL/IPEX external active antenna; Rev B: a small antenna site + π matching | The module has no antenna of its own; establish a baseline with a reliable antenna first, then judge whether a hidden antenna can pass the C/N0, TTFF and on-bike tests |
| IMU | QMI8658A (C3021082) | Six-axis, close to the geometric centre of the device; as a controlled EVT substitution the firmware must regress the device ID, both interrupts, the measurement range and the board-level axes |
| Magnetometer | IIS2MDCTR (C2655002) | The current I²C connection is compatible; the 2 × 2 mm bare die is mounted directly, placed at the outermost edge of the PCB, and the firmware verifies the device ID / axes and calibrates it in the final enclosed, on-bike state |
| Power management | BQ25628E + TPS63070 + TUSB320LAI | Deterministic single-cell lithium switch-mode charging / NVDC power path, a stable 3.3 V buck-boost and USB-C current capability detection; a power test coupon is still made before the main board |
| Battery | Rev A is designed around a 702530 cell with protection / NTC first | About 500 mAh keeps it thin; a thicker rear cover using 800–1000 mAh is also being evaluated, with the final decision based on measured power draw and runtime |
| USB | USB-C, USB 2.0 device + 5 V charging | CC1/CC2 are owned exclusively by the TUSB320LAI; do not add discrete 5.1 kΩ Rd in parallel; data / power ESD, the connector faces 6 o'clock and gets a silicone plug |
| Wi-Fi/BLE antenna | U.FL/IPEX + 2.4 GHz FPC | The FPC is attached to the inside of the plastic rear cover; never fully enclose the antenna inside the aluminium shell |
| Audio | Not fitted | The speaker, microphone, amplifier and codec are removed; music is only a phone remote control, and removing them also reduces magnetic interference |
| Operating parts | EVQP7C01P (C388883) | SW1–SW4 keep the existing solder positions; the actuator is 1.1 ± 0.1 mm, and the outer silicone membrane and the button post travel must be frozen on real hardware |

## Why the LC76G is still kept

The LC76G product seen earlier is a development carrier board with a PCB, connectors and an antenna interface. The `LC76GABMD` soldered directly onto the in-house main board is only about 10 mm square, with JLCPCB part number `C7437114`, and is comparable in size to similar low-cost modules. What really takes up structural space is the GNSS antenna, not the positioning chip.

The completeness of Chinese road data is determined by the AMap route service; the GNSS only outputs coordinates, speed and heading of travel. The GNSS outputs WGS84, which continues to pass through the project's existing explicit WGS84/GCJ-02 conversion boundary before it is fed into AMap matching, off-route detection and rerouting.

## Schematic volumes

The schematic is split into the following pages for independent review and debugging:

1. `POWER_USB`: USB-C, ESD, TUSB320LAI, BQ25628E, TPS63070, battery protection / NTC, the individual power rails and test points. The AXP2101 has been removed from the main design.
2. `ESP32_RF`: ESP32-S3-WROOM-1U-N16R8, EN/BOOT, USB, UART logging and the external 2.4 GHz antenna interface.
3. `DISPLAY_TOUCH`: H0175Y003AMT003 V1, CO5300 QSPI, CST820 I²C, reset / interrupt and power sequencing; per page 7 of the controlled PDF, A1 supplies TP3.3/IOVCC/VCI/VBAT/VCI_EN directly from `3V3_DISPLAY`, with cold-start, low-temperature and maximum-brightness tests as the first-board gate; the interconnection is managed for now as "31P module-side connector + 31P/0.3 mm FFC + main-board-side connector", with the pin order at both ends pending sample continuity testing.
4. `GNSS_RF`: LC76G, backup power, UART, PPS, optional SAW/LNA, 50 Ω routing, π matching, ESD and U.FL.
5. `SENSORS`: QMI8658A, IIS2MDC, I²C pull-ups, sensor power isolation and test points; alternative parts can only be formally frozen after the EVT ID / interrupt / axes / calibration regression is complete.
6. `BUTTONS_DEBUG`: power / function / reset / BOOT keys and 17 production / debug test points; no audio parts fitted.

Every power rail must come with its power-up sequence, static / peak current and decoupling rationale. All external wiring and user-touchable interfaces get ESD protection; unverified small antennas are allowed only as DNP trial sites and must not replace Rev A's reliable antenna baseline.

### Power release gate

The AXP2101 charge mode, default voltages and power-up state on the early reference development boards are affected by the chip's factory EFUSE configuration. The same silkscreen or the same JLCPCB basic part number does not mean the same configuration, so its periphery must not be copied verbatim and a whole-board order placed on that basis.

The main design is frozen as `BQ25628E + TPS63070 + TUSB320LAI`: the first handles single-cell lithium switch-mode charging and the NVDC power path, the middle one maintains 3.3 V over a battery range of 3.0–4.2 V, and the last detects USB-C Default / 1.5 A / 3 A current capability. All three have public, deterministic power-up defaults and do not depend on supplier EFUSE configuration.

H0175 EVT A1 reuses the power chain already routed on R4, but that does not replace the roughly 20 × 20 mm power test coupon. Cold start without a battery, USB / battery switching, a 1.5 A load step at low battery, Type-C current limiting, the NTC, short-circuit recovery and temperature rise in a sealed shell still need to be verified; until the test coupon passes, the power section can only be treated as an electrical engineering candidate.

## PCB baseline

- Circular 4-layer board; the R4 outline is a full Ø52 mm at 1.0 mm thickness with ENIG; four places have R2.7 crescent clearance for the V3 screw posts.
- L1: components and high-speed signals; L2: continuous ground; L3: power / low speed; L4: low speed and necessary components. The stack-up in the RF area follows the antenna reference design; guessing a 50 Ω line width without impedance parameters is not allowed.
- The central through-board battery window has been dropped in exchange for the WROOM-1U, a complete reference ground and routing space. The protected battery of about 27 × 32 × 7 mm sits on an insulating tray behind the PCB; the rear shell depth must be recalculated after accounting for component height, the protection board, the wires, the Poron preload and the swelling allowance.
- Keep the QSPI display clock and data as short as possible with continuous reference ground beneath them, and keep 22–33 Ω series-resistor debug sites.
- The IMU sits close to the geometric centre. The magnetometer is placed at the outermost edge at 12 o'clock, with no high-current traces directly beneath the sensor, away from the PMIC inductor, USB, the antenna coax, the battery protection board and the mount fasteners.
- The PMIC, USB and battery entry are all placed near 6 o'clock to maximise the distance from the magnetometer.
- The 2.4 GHz and GNSS RF areas are on opposite sides; both RF lines are short with a complete ground fence, and each has a reserved π match and conducted test point.
- The first board keeps test points for power, reset, USB, UART, I²C, GNSS PPS, the individual power rails and key interrupts; debugability must not be removed for the sake of appearance.

## Enclosure baseline

### Form factor and stack-up

- The H0175Y003AMT003 V1 cover glass is nominally Ø48.96 ± 0.05 mm, the touch visible area is Ø44.16 mm and the AMOLED active area is Ø43.76 mm.
- The Rev A structural baseline is a Ø61.0 mm body about 16–20 mm thick (thicker locally where the mount is). A joint PCB / structural review is still needed once the display, antenna and battery hardware are in hand.
- The appearance direction is frozen as V3: a continuous, unsegmented sandblasted aluminium front bezel, four functional front screws on the diagonals, and no segmented armour styling from the reference products on the front; the screws must take part in clamping and allow teardown for maintenance, and must not be decorative only.
- Recommended front to back: display / touch assembly → continuous display adhesive ring → component / FPC cavity → full 1.0 mm circular PCB → insulating tray / Poron → rear battery → rear cover.
- V3 removes the top antenna cap visible on the front and prefers placing the GNSS antenna in the clear space inside the plastic rear shell at 12 o'clock; Rev A still keeps the external antenna as the RF baseline. The external baseline connector must not be removed before the hidden solution has passed the C/N0, TTFF and on-bike tests.

### Materials

- The EVT enclosure first uses MJF/SLS PA12 or CNC plastic to verify assembly, RF, the compass and vibration.
- The appearance version uses a CNC 6061-T6 aluminium front bezel + a one-piece PC/ABS or PA plastic rear cover. The aluminium only protects the display and provides finish; it does not enclose the antenna plane.
- An all-aluminium sealed enclosure, magnetic quick-release, permanent speaker magnets and untested "low-magnetic stainless steel" fasteners are forbidden.

### Sealing and mounting

- The first version is designed to a rain / IPX5 target and does not claim IP67 before testing.
- The display uses a continuous die-cut waterproof adhesive ring; for Rev A the front and rear shells are provisionally verified with a 50A silicone ring of about ID48.5 × CS1.0, with the groove centre at R25.50, width 1.20 and depth 0.75, and the final figures are still recalculated against the gasket actually purchased and its tolerances.
- The four front load-bearing screws are frozen as M1.6×6 TC4 titanium, with a centre radius of R27.70 / PCD Ø55.40; the rear shell uses brass blind-bottom inserts and Ø4.8 reinforcement posts, and the PCB crescent clearances are re-checked against the new screw centres. M2 would squeeze the seal and the display shoulder within the current envelope, so it is not used.
- The USB-C faces down and uses a silicone plug and a drainage channel; the official MPN of J1 is `U262-161N-4BVC11`, and the `16XN` in the historical project footprint name is kept only as an existing library name. The buttons are driven by silicone membranes clamped by the shell against an `EVQP7C01P`, and the button posts are frozen at a 1.1 ± 0.1 mm actuator and the physical travel.
- The rear cover reserves a location for an ePTFE vent membrane to balance the pressure difference between sun exposure and sudden rain.
- The rear cover uses a replaceable Garmin Edge device-side male mount that screws directly into the existing Garmin quarter-turn base; the in-house handlebar clamp is no longer part of the baseline. The male mount is modelled at A0 with a Ø24.9 guide post and a Ø28.6 mount rotation envelope, and the final locking groove and shrinkage must be frozen with an original P/N `010-11430-00` base gauge; the lanyard hole is kept.
- The magnetometer sits at 12 o'clock away from the mount. Calibration must be performed in the final enclosure, with the final mount and the motorcycle installation state.

## Four physical items that must be frozen first

Before these four are frozen, the schematic, layout and Gerber can be completed and reviewed, but the final production package and enclosure machining drawings for "order directly" cannot responsibly be signed off:

1. The H0175Y003AMT003 V1 display / touch assembly and one set of 31P FFC; one sample is enough to start EVT dimensional, continuity and power-on verification, with a batch re-test added before the production freeze.
2. The physical battery, the protection board position, the NTC parameters and the lead exit direction.
3. The GNSS antenna form and its reference ground / mounting requirements.
4. The original Garmin P/N `010-11430-00` base, the Garmin male-mount locking gauge and the device's mounting orientation relative to the handlebar.

The off-the-shelf round-display development board already purchased serves only as a UI, driver and performance reference, and no longer defines the display mechanics or pin baseline of the in-house board. In-house Rev A follows only the H0175Y003AMT003 V1 supplier drawing and the physical parts when they arrive.

## Deliverables

### Electrical

- KiCad schematic and PCB source files.
- Schematic PDF, ERC/DRC reports and a design review checklist.
- Gerber, drill files, BOM and CPL / coordinate files usable at JLCPCB.
- Datasheets for key parts, footprint sources and incoming orientation notes.
- Production test point drawing, first-board power-up sequence and fault isolation steps.

### Mechanical

- Parametric CAD source files for the enclosure, display retaining frame, rear cover, buttons, USB plug and replaceable Garmin Edge male mount.
- STEP assembly, STL print files, 2D machining drawings and a seal parts list.
- Interference checks and section views for the PCB / display / battery / antenna.

### Verification

- Wi-Fi RSSI / throughput, GNSS C/N0 / TTFF, off-route reroute and hotspot recovery logs.
- Compass error records on the bare board, in the complete unit, on the bike, and with the engine / charging switched on and off.
- Power draw, runtime, surface temperature, readability in strong light, burn-in protection, water spray, vibration and drop records.

## Execution and exit conditions

1. **Specification freeze**: obtain the display / FPC, battery, antenna and mounting orientation data; output the interface table and the mechanical placeholder model.
2. **Power verification**: complete the BQ25628E + TPS63070 + TUSB320LAI test coupon; pass cold start, switching, Type-C current limiting, load transient and temperature rise tests.
3. **Rev A schematic**: H0175 EVT A1 has completed the 6 volumes, the current procurement fields and ERC 0; it must be re-run after any change to the physical parts / footprints or the procurement state.
4. **Rev A PCB**: H0175 EVT A1 is derived from the frozen R4 four-layer routing with DRC 0, unconnected 0 and consistency 0, and outputs the review manufacturing files; the formal production package of 5 PCBAs is generated only after all the physical, power, RF and procurement gates are closed.
5. **Bare-board bring-up**: pass in the order power → USB → ESP32 → display / touch → IMU / compass → GNSS → Wi-Fi.
6. **EVT enclosure**: verify space, touch, RF, magnetic field, heat dissipation, rain and vibration with the plastic shell installed on the bike.
7. **Rev B**: revise the board and the shell from the measurements; the aluminium front bezel is machined only after it passes.
8. **DVT / production preparation**: complete small-batch consistency, tooling, test firmware and supply-chain alternatives; do not cut the injection mould before this stage.

## Biggest current risks

1. An off-the-shelf "1.75-inch 466 × 466 CO5300" does not guarantee the same FFC/FPC, touch chip, power supply and cover glass; the display must be managed as the fixed part number H0175Y003AMT003 V1, and the end-to-end cable pins still need physical sign-off.
2. The GNSS antenna is affected by the AMOLED metal back plate, the aluminium shell, the handlebar and the mounting angle; a hidden solution must be measured against the external baseline.
3. The magnetometer is polluted by the PMIC, battery current, steel screws, the handlebar and the motorcycle's electrical system, so bare-board readings alone mean nothing.
4. Strong-light readability of the 700 nit AMOLED, static-UI burn-in and high temperature in the sun need handling by the firmware and the structure together.
5. Pursuing thinnest, largest battery, all-aluminium, hidden dual antennas and first-time success all at once in the first version significantly raises the scrap rate; Rev A prioritises being testable, repairable and navigable.
6. When the BQ25628E/TPS63070 is at full load and charging at the same time, the power area of the sealed shell may generate about 0.8–1.2 W of heat; thermal pads / vias, copper pours and a thermal pad connecting to the aluminium front bezel must be used, and the charge current reduced dynamically from the battery NTC and the shell temperature.
