> **Language:** English · [中文](MOTO_GPS_摩托车便携导航终端技术方案_RevA0.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS Motorcycle Portable Navigation Terminal Technical Proposal (Rev A0 R4)

Version: 2026-09-03  ·  Status: functional prototype / Rev A0 R4 electrical engineering candidate  ·  NOT FOR FABRICATION

> **A historically frozen delivery document; it does not represent the current software prototype.** This document keeps the Rev A0 R4 assumption of "standalone GNSS + phone hotspot" in order to trace the hardware candidate; it must not be used as the basis for current joint debugging or for placing an order. The current baseline is the Waveshare 1.75C + native iOS app: the phone handles real positioning, place search that prefers the current location, and AMap routes, and the ESP32 displays them over BLE; both the App beta and the long press on the firmware navigation page have clearly labelled demo entry points, production navigation still uses real routes, and the known implementation issues with the iOS BLE recovery handshake / reconnection have been fixed. See [Waveshare + iPhone prototype baseline](../WAVESHARE_IOS_PROTOTYPE.en.md). The accompanying PDF/DOCX are the same historical archive and have not been rewritten with the current software baseline.

![V3 product appearance](assets/product-v3.png)

## Executive summary and conclusions

MOTO GPS is a round dedicated navigation terminal mounted on the motorcycle handlebar. The phone provides only the hotspot network and the destination input; once navigation starts, the terminal continuously handles positioning, vehicle heading, route progress, off-route detection, screen rendering and continued operation when the network drops.

### Key conclusions

The functional prototype is feasible; the Rev A0 R4 has produced a complete schematic, four-layer routing and review manufacturing files, but the first board still has to close the FPC, USB-C, power, RF, procurement and complete-product mechanical gates first. A pure Safari/PWA cannot deliver continuous positioning after the iPhone locks, so the formal path must keep the terminal's standalone GNSS.

### Not CarPlay screen mirroring

The device does not mirror the phone screen, does not run Android and does not read AMap or Baidu notifications. AMap provides the roads, routes and traffic conditions; the terminal determines its own current position and vehicle direction.

| Item | Conclusion | Boundary |
| --- | --- | --- |
| Web functional prototype | Feasible | Foreground positioning and route simulation already in place |
| Continuous navigation after the phone locks | Feasible | Depends on the terminal GNSS, not on a web background |
| Live traffic / off-route reroute | Feasible | The terminal requests the backend through the phone hotspot |
| Rev A0 R4 main board | Medium-high | ERC/DRC/unconnected/consistency all 0; physical gates not cleared |
| Garmin mount direct fit | Medium-high | A0 modelled; the genuine mount gauge awaits physical verification |
| Production-ready from a single design pass | Cannot be promised | RF, compass, power, sealing and vibration need measurement |

> The biggest difficulty today is not ESP32 compute, but the GNSS antenna, the compass magnetic environment, power heat, sealing and mechanical tolerances inside a small Ø61 mm volume.

## Product positioning and target usage scenarios

The target users are urban commuters and short- to medium-distance riders: they do not mount the phone on the handlebar, yet still get terse, continuous, reroutable turn-by-turn navigation.

![Product positioning and target usage scenarios](assets/target-scene-reference.png)

- After getting on the bike the phone turns on its hotspot and the terminal joins the network automatically.
- The user searches for a destination in the phone web page and sends it to the terminal.
- Once navigation starts the phone locks and goes into a bag; the web page does not need to stay in the foreground.
- The terminal obtains position, speed and vehicle heading on its own; the arrow stays fixed pointing to the top of the screen.
- When off route the terminal requests a new route automatically; traffic conditions refresh periodically.
- When the network drops it continues along the cached route, and once the network returns it catches up on the recompute and the traffic refresh.

> The illustration is a target-scenario reference provided by the user, not a photograph of this project's finished product.

## Product feature boundaries

The first version focuses on navigation, the speedometer, the compass and touch page changes; music remote control is an additional capability and does not affect the core navigation acceptance.

### Explicitly out of scope

No full CarPlay reproduction; no phone mirroring; no Android installation; no complex map tiles or large numbers of POIs; no dependence on how the phone is placed or oriented; the speaker and the microphone are not listed as required hardware.

| Feature | What the user sees | How it is done | Current state |
| --- | --- | --- | --- |
| Turn-by-turn navigation | Arrow points up, the route rotates with the vehicle heading | NavCore + LVGL | Shared logic and the first version UI are done |
| Destination search (historical design) | Text search for POIs in the phone web page | In-house backend + AMap POI | The chain was complete at the time; the current App has switched to nearby search that prefers the phone position, and falls back when there are no results nearby |
| Route / ETA | Distance, time, roads and turns | AMap Driving Route v2 | Usable on the prototype, licensing boundary to be confirmed |
| Off-route reroute | Automatically switches to a new route after a wrong turn | Terminal detection + backend recompute | State machine done, awaiting on-vehicle testing |
| Live traffic | Periodic congestion and ETA updates | 60 s refresh in the first version | Logic done, quota and benefit to be tested |
| Speedometer | Live speed / circular scale | GNSS speed | UI done |
| Compass | Vehicle heading rather than the phone direction | GNSS + IMU + LIS2MDL | Fusion and calibration await real hardware |
| Music | Play, pause, previous/next track | BLE HID; optional AMS on iPhone | Additional feature, awaiting on-device testing |
| Like song | Shown when the capability exists | AMS LikeTrack | Not a required acceptance item |

## Round-display interaction and navigation simulation

All four pages come from the current shared LVGL / WASM runtime, not from static mockups. The navigation page can already simulate progress on a fixed route fixture.

![Navigation: next turn / distance / speed limit](assets/ui-navigation.png)
*Navigation: next turn / distance / speed limit*

![Speedometer: live speed and circular scale](assets/ui-speedometer.png)
*Speedometer: live speed and circular scale*

![Compass: absolute vehicle heading](assets/ui-compass.png)
*Compass: absolute vehicle heading*

![Music: basic remote control, an additional feature](assets/ui-music.png)
*Music: basic remote control, an additional feature*

- Deep black background, thick white route line, a small amount of cool-coloured status light; readability in strong light comes first.
- The vehicle arrow sits slightly below the centre of the circle and is fixed pointing to 12 o'clock; the map rotates in the opposite direction to the vehicle heading.
- Swipe left and right to change page; touch targets are designed at a minimum of 44 × 44 logical pixels.
- The Web and Waveshare production display framebuffers are already unified from the same source at 466 × 466; fonts, cropping and golden frames still have to be checked on the real display.

## End-to-end system architecture

Standalone GNSS solves positioning while the phone is locked; the phone hotspot provides the data connection for AMap routes, traffic conditions and off-route rerouting.

![End-to-end system architecture](assets/system-architecture.png)

### Destination delivery

The terminal displays a short pairing code or a QR code; the phone web page associates the terminal ID with the session, writes the destination to the server once it is confirmed, and the terminal retrieves the destination and the standardised route over HTTPS.

### Security boundary

The AMap key is kept only in the backend; the terminal and the web page only ever see the project-defined RouteBundle. A late response from an old request must not overwrite a newer route.

## Navigation execution and lock-screen feasibility

The production product does not keep Safari navigating in the background; it keeps the terminal navigating after the web page has exited.

### Initial off-route parameters

A fix takes part in the decision only when its accuracy is better than 50 m; a recompute happens when the distance from the route exceeds 45 m for 3 consecutive reliable samples; the off-route confirmation clears within 25 m of the route. The parameters need tuning against logs from elevated roads, service roads and urban canyons.

### Behaviour without a network

When the network drops it keeps showing the cached route and continues progress locally; no new route or new traffic conditions can be obtained. Once the hotspot returns it reconnects automatically, handles the pending recompute first and then refreshes traffic conditions.

### Coordinate boundary

Raw GNSS WGS84 coordinates must go through an explicit WGS84 / GCJ-02 conversion before they reach the AMap route service; the conversion exists only at a clearly defined adapter boundary.

| Option | Continuous positioning while locked | Off-route reroute | Conclusion |
| --- | --- | --- | --- |
| Pure Safari / PWA | Unreliable | Unreliable | Foreground functional verification only |
| Reading AMap / Baidu notifications | Incomplete data | Cannot be implemented reliably | Not adopted |
| A native phone app running persistently | Achievable | Achievable | Cost and schedule not acceptable |
| Terminal GNSS + phone hotspot | The terminal can continue | The terminal can request on its own | The production product path |

## Vehicle heading: the arrow always points forward

The phone sits in a bag and takes no part in the vehicle heading. The terminal is mounted on the handlebar and fuses the GNSS course angle, short-term gyroscope change and the low-speed absolute direction from the magnetometer.

![Vehicle heading: the arrow always points forward](assets/heading-fusion.png)

- GNSS cannot give a stable heading at rest, a compass cannot give a position, and a gyroscope drifts over time, so the three have to complement each other.
- The LIS2MDL must be soft-iron / hard-iron calibrated on the final PCB, enclosure, Garmin mount, handlebar and a powered motorcycle.
- Steel screws, speaker magnets, inductors and high-current loops pollute the compass; V3 uses TC4 screws and brass inserts and does away with the speaker.

## Shared-source implementation for web and firmware

The web page is not a visual reference; it is a run target for the firmware's shared code in the browser. The platform drivers differ, but the navigation core and the UI are not rewritten.

![Shared-source implementation for web and firmware](assets/shared-source.png)

### Level A: shared source

The navigation core, route matching, off-route state machine, LVGL pages, fonts, icons, RGB565 resources and test fixtures are shared directly.

### Level B: adaptation

Web Geolocation corresponds to the physical GNSS; fetch corresponds to ESP32 HTTPS; browser storage corresponds to NVS; browser touch corresponds to the CST820.

### Still to be completed

The real H0175Y003AMT003 V1, the CO5300 QSPI, the CST820, DMA buffers, the 466 × 466 framebuffer and the on-device performance budget are not connected yet.

## In-house hardware overview

Instead of adding hand-soldered wires inside a production development board, an ESP32-S3 round main board integrates the GNSS, IMU, compass, power and display interface in one pass.

![In-house hardware overview](assets/pcb-r4-top.png)

| Subsystem | Preferred component / approach | Role and boundary |
| --- | --- | --- |
| MCU | ESP32-S3-WROOM-1U-N16R8 | The first board reduces LGA, Flash/PSRAM and RF risk; 16 MB Flash + 8 MB PSRAM |
| Display / touch | H0175Y003AMT003 V1 / CST820 | 1.75 in 466×466 fully laminated AMOLED; CO5300 QSPI; single-point touch |
| Display interconnect | 31P module connector + 31P FFC (awaiting physical parts) | Pin 1, the contact faces and the end-to-end order are settled by continuity checks on arrival; straight-through/reversed is not frozen in advance |
| GNSS | LC76GABMD bare module | About 10 mm square, not a CNY 150 development carrier board; UART / PPS |
| IMU / compass | QMI8658C + LIS2MDLTR | Turning dynamics + low-speed/at-rest absolute heading |
| Power | BQ25628E + TPS63070 + TUSB320LAI | Charging / NVDC, 3.3 V buck-boost, Type-C current identification |
| Battery | 702530 with protection / NTC | About 500 mAh initial envelope; runtime is frozen by measurement |
| Wireless | 2.4 GHz FPC + U.FL | Phone hotspot / BLE; attached to the inside of the plastic rear shell |
| Audio | Not fitted | Speaker, microphone and codec removed, saving space and reducing magnetic interference |

> The user has settled on battery power, not a 12 V connection to the motorcycle; a 12 V to 5 V converter, an inline fuse and a waterproof on-vehicle power connector are not part of the basic BOM.

## Power, PCB and RF implementation

The Rev A0 R4 has complete electrical connectivity, four-layer component placement and routing, and review manufacturing exports; the power test coupon, controlled impedance and complete-product RF/thermal verification are still board-release gates.

### Power path

USB-C 5 V → TUSB320LAI → BQ25628E ↔ single-cell lithium battery/NTC → TPS63070 3.3 V; the GNSS can be powered separately from a low-noise 3.0 V LDO.

### Four-layer board baseline

Full Ø52 mm, 1.0 mm, ENIG; the central battery cutout is dropped and the battery is instead stacked with insulation behind the PCB; four R2.7 crescent clearances. F.Cu carries the components and short traces, In1.Cu is preferably continuous ground, In2.Cu distributes power/low-speed signals, and B.Cu completes the low-speed and test nets.

### Component placement zones

The IMU sits near the geometric centre; the magnetometer near the outermost edge at 12 o'clock; the PMIC, USB and battery entry are grouped at 6 o'clock; the 2.4 GHz and GNSS RF areas are kept apart.

| What the power test coupon must pass | What is verified |
| --- | --- |
| R4 CAD | 93 production components, 458 pads, 1555 trace segments, 189 vias, 2 copper pours |
| Start-up and switching | Cold start without a battery, seamless USB/battery switching, low-battery reset |
| Load | 1 A continuous, 1.5 A step, Wi-Fi pulsed load |
| Charging safety | Type-C three-tier capability, NTC charge termination, short-circuit recovery |
| Thermal | Temperature rise in a sealed environment, derating at full load and while charging at the same time |

> KiCad 10.0.6 full-board check results: ERC 0, DRC 0, unconnected 0, schematic/PCB consistency errors 0. In the same state R4 exported Gerbers, drill files, a 57-line grouped BOM and a 93-point CPL; the files are still marked NOT FOR FABRICATION because 28 production references are still in a physical, power, RF, stock or sourcing release state.

## V3 appearance and mechanical design

The appearance is frozen as a continuous dark-grey sandblasted aluminium front bezel, four exposed functional screws on the diagonal, black round glass, a button on the right and an amber index mark at 6 o'clock.

![V3 appearance and mechanical design](assets/mechanical-stack.png)

| Item | Rev A0 nominal value | Status |
| --- | --- | --- |
| Body | Ø61.0 × 16.0 | H0175 V1 geometric baseline |
| Including the Garmin mount | Maximum thickness 19.0 | Geometry verified |
| Front bezel / rear shell | 6061-T6 3.0 / PC-ABS 13.0 | Material route |
| Cover glass / touch visible / active area | Ø48.96 / Ø44.16 / Ø43.76 | Supplier V1 drawing; awaiting physical verification |
| PCB / battery | Full Ø52 × 1.0 / 27 × 32 × 7 | The battery is stacked with insulation behind the PCB; thickness to be recalculated against the physical parts |
| Screws | M1.6×6 TC4, R27.70 / PCD Ø55.40 | Updated with the Ø61 mm envelope |

## Garmin Edge mount direct-fit approach

The back of the device uses a Garmin Edge device-side male mount tab that lines up directly with the notch of an existing Garmin mount, then presses down lightly and rotates 90° to lock; no dedicated handlebar clamp is needed.

![Garmin Edge mount direct-fit approach](assets/v3-general-arrangement.png)

### A0 geometry

Central guide post Ø24.9 × 3.0; maximum swing diameter of the tabs on both sides Ø28.6; tab width 11.0; thickness 1.5; the overall thickness stays 19.0 mm.

### Serviceable design

The male tab is a separate, replaceable engineering-plastic part. After a drop, wear or a shrinkage correction only the mount is replaced, not the whole rear shell.

### Gauge and acceptance

The genuine Garmin Quarter-turn Bike Mount P/N 010-11430-00 is the master gauge; it covers 20 fitting cycles, pull-out, torque, vibration and a post-rain looseness re-check, and a safety tether is used.

> Garmin has not published a complete production tolerance drawing. The current dimensions are an A0 reverse-engineered envelope, and the locking slot, the lead-in fillet and moulding shrinkage must be frozen against the physical genuine mount; a claim of "100% compatible with all third-party mounts" cannot be made directly.

## Feasibility assessment

The approach has no problem that is impossible in principle. The risk is concentrated in physical coordination, not in the functional idea itself.

| Module | Feasibility | Available evidence | Exit condition |
| --- | --- | --- | --- |
| Web destination and route | High | Page, gateway, RouteBundle and shared core already exist | Real key and long road testing |
| Off-route / traffic | High | Unit tests and a simulated chain already exist | iPhone / ESP32 on-vehicle verification |
| ESP32 round-screen display | High | LVGL/WASM and the display interface are clear | CO5300 / 466×466 performance |
| In-house main board | Medium-high | R4 has a complete schematic/four-layer routing; full-board DRC and consistency both 0 | The first board can be ordered only after the physical interface and a power/RF review |
| Standalone positioning | Medium-high | Mature GNSS module and interface | Antenna, complete-product RF, on-vehicle environment |
| Vehicle heading | Medium | The three-sensor fusion path is reasonable | Complete-product magnetic field and on-vehicle calibration |
| V3 enclosure | Medium-high | CAD/STEP/2D drawings and interference checks | Display, sealing, temperature rise, vibration |
| Garmin direct fit | Medium-high | The A0 male tab solid model is valid | Genuine gauge and road testing |
| Long-term hotspot connection | Medium | The standard Wi-Fi path holds | Long testing across lock screen, network switching and weak networks |
| Music like | Low to medium | AMS provides the command conditionally | Not listed as a required item |
| Mass production | Not yet in place | R4 is a reviewable electrical candidate, not a production release | First-board verification → EVT → Rev B → DVT |

> Overall judgement: the schematic and PCB CAD stage is complete; only after the FPC/USB-C physical parts, the footprint review, the power coupon, RF and the procurement states are closed is the formal package for ordering the first batch of 5 PCBAs generated. The current R4 review Gerbers must not be ordered directly.

## Main risks and countermeasures (1/2)

The five issues most likely to force a complete-product rework are turned into verifiable gates first.

| Risk | Impact | Countermeasure |
| --- | --- | --- |
| Display/FFC interconnect incompatibility | The board cannot light up, or the touch direction is wrong | Fix on the H0175Y003AMT003 V1; the first sample's continuity check confirms Pin 1, the contact faces at both ends, the end-to-end order and the power sequencing |
| Insufficient hidden GNSS antenna performance | Position drift, wrong off-route decisions | Rev A keeps a U.FL external active antenna as the baseline; compare TTFF, C/N0 and driven tracks |
| Compass polluted by the motorcycle | Wrong heading at low speed | Titanium screws, brass inserts, no speaker; calibrate in the final on-vehicle state and down-weight dynamically |
| iPhone hotspot disconnects | No new traffic conditions and no recompute | Automatic reconnection, exponential backoff, route cache; long testing across phone models, lock screen, network switching and incoming calls |
| Temperature rise in a sealed small enclosure | Charging derating, battery life and restarts | 500 mA initial charging; copper pours/vias/thermal pads connect to the aluminium bezel; NTC dynamic derating |

## Main risks and countermeasures (2/2)

The mechanical, display and data services equally need clear product boundaries.

| Risk | Impact | Countermeasure |
| --- | --- | --- |
| Garmin mount tolerance / self-release | It will not fit, it is loose, or it comes off while riding | Genuine 010-11430-00 gauge; tough material; 20 fitting cycles, pull-out, vibration; safety tether |
| AMOLED in strong light / burn-in | Unreadable while riding, long-term image retention | Black background, high-brightness mode, dynamic brightness, slight element movement, automatic dimming with no navigation |
| An ordinary driving route is not suitable for a motorcycle | Wrong restrictions and road choices | The prototype gives a clear warning; obtain a compliant motorcycle routing service or licence before productisation |
| AMap hardware terminal licensing | Commercialisation and caching boundaries are unclear | The key stays server-side only; obtain written permission for hardware, caching, traffic conditions and commercial use before mass production |
| Insufficient seal groove and insert rib thickness | Cracking or water ingress | First-article DFM; if needed, increase the body outer diameter to Ø61–62, but never cut through the sealing path |

> Until tested, make no claim of IP67, runtime hours, GNSS accuracy, compass error or full compatibility with any third-party Garmin mount.

## Verification and acceptance plan

Verification starts with the shared software and moves layer by layer into electrical, RF, mechanical and complete-vehicle work; each layer has logs and exit conditions.

### Garmin mount special test

The genuine mount is the master gauge, with the genuine out-front mount and one common third-party mount sampled in addition; the static pull-out and rotational torque thresholds are formally frozen after the first round of sample measurements.

### Water resistance target

The first version is verified by design against rain / IPX5; whether to raise the rating is decided after spray, thermal cycling and on-vehicle vibration testing.

| Layer | What is verified | Key output |
| --- | --- | --- |
| 1 Software | RouteBundle, coordinate conversion, off-route, discarding old responses, recovery after a network drop, RGB565 golden frames | Automated test report and fixed fixtures |
| 2 Electrical | Power coupon, USB, ESP32, display, touch, sensors, GNSS, Wi-Fi powered up in sequence | Oscilloscope waveforms, temperature rise and an issue list |
| 3 RF / navigation | Cold start in the open, urban canyons, parallel roads, elevated roads, tunnels, Wi-Fi interference at full load | TTFF, C/N0, tracks and off-route logs |
| 4 Mechanical environment | 20 fitting cycles, Garmin mount, spray, thermal cycling, vibration, drop, USB port cover, buttons | Looseness before/after / leakage / appearance re-check |
| 5 Complete vehicle | Engine start-stop, revs, handlebar material, phone in a bag, hotspot switching, continuous riding | Closing the loop on on-vehicle issues and Rev B changes |

## Phase plan and exit conditions

Each phase moves to the next only once its exit conditions are met, to avoid carrying five kinds of unknowns — display, RF, power, mechanics and appearance — at the same time.

| Phase | Work content | Exit condition |
| --- | --- | --- |
| P0 Specification freeze | Display, battery, GNSS antenna, genuine Garmin mount | All four physical items measured and an interface table produced |
| P1 Online software | AMap key, POI, route, off-route, traffic conditions | The real road chain is stable and leaves logs |
| P2 Power test coupon | BQ25628E / TPS63070 / TUSB320 | Cold start, switching, load and temperature rise pass |
| P3 Rev A design | Six-volume schematic, four-layer layout, BOM, Gerber | R4 has reached ERC/DRC/unconnected/consistency all 0 |
| P4 5 PCBAs | Close the physical/power/RF/procurement gates first, then order and power the subsystems up | Display, GNSS, sensors, Wi-Fi measurable |
| P5 Firmware integration | 466×466, touch, HTTPS, NVS, BLE | Web and on-device state/screen agree |
| P6 Plastic EVT | Assembly, RF, compass, temperature rise, rain, Garmin mount | Continuous on-vehicle testing passes |
| P7 Rev B / DVT | Board and enclosure fixes, aluminium front bezel, small-batch consistency | Production preparation only after the key issues are closed |

> It is recommended to make the PA12 / PC-ABS EVT enclosure first and machine the aluminium front bezel last; print the Garmin mount in tough engineering plastic for the first round, and do not ride with PLA or brittle resin.

## Current completion state and next steps

The Rev A0 R4 is now a complete, openable, reviewable electrical engineering candidate; it passes all KiCad connectivity and rule checks, but it still cannot be ordered directly until the physical and verification gates are closed.

![Current completion state and next steps](assets/v3-isometric.png)

| State | Done / to be done |
| --- | --- |
| Done | The shared NavCore, RouteBundle, Web round display, backend gateway, V3 CAD/STEP/2D assembly drawing/Garmin A0 mount, plus the R4 complete schematic, four-layer PCB, BOM, CPL and review Gerber/drill files |
| Passed | KiCad 10.0.6: ERC 0, full-board DRC 0, unconnected 0, consistency 0; the BOM/CPL 93 placed references fully reconciled; the hashes of the 58 files in the R4 package pass; the V3 STEP solid is valid |
| Awaiting physical parts | Long testing with a real AMap key, continuity of the H0175Y003AMT003 V1 display and the 31P FFC, GNSS antenna, compass calibration, hotspot, Garmin gauge, temperature rise, sealing, vibration |
| Not yet done | The power test coupon, 28 procurement/physical/RF release states, formal production sign-off, the first PCBAs, physical firmware drivers and EVT enclosure measurement |

> Immediate next step: measure the H0175Y003AMT003 V1 when it arrives and use the physical continuity check to lock down the end-to-end pins of the 31P module connector, the two FFC contact faces and the main board connector; at the same time keep closing the USB-C, power coupon, RF and footprint gates. Once the gates are closed, fab_release.py generates the formal board package.

## Sources, references and authenticity boundaries

This proposal treats official interface documentation, open-source reverse-engineered envelopes and this project's own engineering verification separately, and does not present any of them as a mass-production certification.

### Garmin official

Quarter-turn Bike Mount, P/N 010-11430-00: confirms the Edge compatibility family. The Garmin installation instructions confirm aligning the tab with the notch, pressing lightly and rotating to lock.

### AMap official

The Route Planning 2.0 Web Service confirms driving route, waypoint and strategy capabilities; the POI Search Web Service provides keyword, nearby and ID search. Commercial use still requires separate confirmation of hardware terminal, caching and traffic-condition licensing.

### Where the mount dimensions come from

The A0 Ø24.9 / Ø28.6 / 11 / 1.5 mm come from an open-source Garmin-style quarter-turn reverse-engineered model and serve only as the first-round sample envelope. Fitting against the genuine gauge takes priority over the open-source model.

- https://www.garmin.com/en-GB/p/65215/
- https://www8.garmin.com/manuals/webhelp/GUID-28E0106C-B05A-44E9-BF7C-9CB36A596B82/EN-US/GUID-60CB97C5-E3D6-4FF9-9147-9AAC1B773463.html
- https://github.com/chadkirby/quarter-turn-mount
- https://lbs.amap.com/api/webservice/guide/api/newroute
- https://lbs.amap.com/api/webservice/guide/api/search/

> NOT FOR FAB: the H0175Y003AMT003 V1 supplier drawing already defines the display and cover glass envelope, but the end-to-end pins of the 31P module connector + FFC + main board connector still need a continuity sign-off; the locking slot, material shrinkage, battery, antenna, USB, sealing and mount also all need to be frozen against physical parts.
