> **Language:** English · [中文](COMPONENT_PACKAGE_AUDIT.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Rev A component, footprint and assembly audit

Status: **engineering audit draft, not a board release approval**  
Audit baseline: 2026-09-03  
Current mechanical premise: a complete `Ø52 × 1.0 mm` four-layer main board with the central through-board battery window removed; the battery is stacked behind the PCB. With the current stacked relation between the `27 × 32 × 7 mm` battery and the `3.2 mm`-high WROOM-1U, the rear cavity depth is provisionally budgeted at about `14–15 mm`, and the final figure must be frozen on a measured stack-up of the battery protection board, wiring, insulating tray, foam preload and swelling allowance; a thickness increase of only 1–2 mm cannot be promised.

This document freezes only component identity, footprint basis and layout boundaries. The LCSC/JLCPCB numbers are for purchasing search and do not mean that stock is guaranteed on the audit date, that economical assembly is guaranteed to be supported, or that the part belongs to the basic library; before ordering you must still verify all three of **full MPN + footprint + manufacturer** on the JLCPCB PCBA page.

## Conclusions first

1. For the current complete round-board design, the first-round EVT MCU is recommended and frozen as **`ESP32-S3-WROOM-1U-N16R8`**. It has an officially recommended land pattern and a mature module assembly form, and integrates the crystal, Flash, PSRAM and RF matching; compared with the PICO, the footprint, RF and rework risk of the first prototype is clearly lower.
2. The `18.0 × 19.2 mm` WROOM-1U has already been through real placement and fully-connected routing on the complete Ø52 mm R4 round board; the KiCad courtyard/DRC has closed. The display FPC, USB-C, battery stacking on the board, coaxial cable bending and antenna clearance must still be closed with physical parts in a full-unit 3D check, and cannot be released from a PCB view alone.
3. If the central through-board battery window of about `27 × 32 mm` is restored later, the WROOM-1U cannot fit on the remaining annular board band; under that condition the design must change to the **`ESP32-S3-PICO-1-N8R8`** or change the enclosure / battery structure. The PICO is not the current primary Rev A choice.
4. `BQ25628E`, `TPS63070` and `TUSB320LAI` are all irregular HR/QFN-class small packages; the in-project footprint must be generated and reviewed using the TI official package drawing, and a "same-size QFN" may not be substituted.
5. The two most direct mechanical blockers to fabrication are: **the contact face / thickness / insertion direction of the original screen's 31-pin FPC has not been closed by measurement of the physical part**, and **the Z height / opening of the USB-C receptacle in the enclosure has not been frozen**. The 31-pin electrical order has already been checked pin by pin against the Waveshare official schematic of 2026-01. Beyond those, the power coupon, RF/antenna, second-person sign-off for every in-house footprint and order-day stock / substitute sourcing must also be closed; even with zero R4 DRC these gates may not be bypassed.

## Audit status definitions

- **A — freezable**: the MPN, the package and the candidate footprint all have a direct official basis; the normal pin-by-pin comparison is enough to enter the library.
- **B — needs in-house build / review**: the component is usable, but the footprint must be built from the manufacturer drawing, or the stock / assembly status still needs review before ordering.
- **C — blocking**: the physical part or the mechanical definition is insufficient, so no fabrication-ready footprint can be generated from the available information.

## Component / footprint master table

| Function | Frozen MPN | Manufacturer package and pad count | LCSC/JLCPCB search number | KiCad handling | Status |
|---|---|---|---|---|---|
| MCU module | `ESP32-S3-WROOM-1U-N16R8` | 18.0 × 19.2 × 3.2 mm; 40 perimeter pads + central GND 41, 41 pin in total | `C3013946` | upstream candidate `RF_Module:ESP32-S3-WROOM-1U`; copy into the project library and check item by item against figure 11-2 of official v1.8 | A |
| Compact MCU alternative | `ESP32-S3-PICO-1-N8R8` | 7 × 7 mm LGA; 56 perimeter pad + central GND pad 57, 57 PCB pad in total | `C7545129`; see also JLC `C9900172181` | no dedicated footprint that can be frozen directly was found in the upstream library; must be built in-house | B |
| Charging / power path | `BQ25628ERYKR` | TI `RYK0018A`, 18-pin WQFN-HR, 2.5 × 3.0 mm | `C18221178` | must be built in-house per `RYK0018A`; a normal symmetrical QFN substitution is prohibited | B |
| 3.3 V buck-boost | `TPS63070RNMR` | TI `RNM0015A`, 15-pin VQFN-HR, 2.5 × 3.0 mm | `C109322` | must be built in-house per `RNM0015A`; a normal QFN substitution is prohibited | B |
| Type-C current detection | `TUSB320LAIRWBR` | TI `RWB0012A`, 12-pin X2QFN, about 1.6 × 1.6 mm | `C132554` | build in-house per `RWB0012A` and review the solder-mask bridge capability | B |
| Six-axis IMU | `QMI8658A` | 14-pin LGA, 2.5 × 3.0 mm, 0.5 mm pitch | `C3021082` | reuse the reviewed project-derived 14-pin land pattern; as a controlled EVT drop-in it must still be reviewed against the QMI8658A manufacturer drawing | B (firmware regression) |
| Three-axis magnetometer | `IIS2MDCTR` | ST LGA-12L, 2.0 × 2.0 × 0.7 mm, 12 pad, 0.5 mm pitch | `C2655002` | the current I²C connection and the project-derived LGA-12 land pattern are compatible; review against the IIS2MDC datasheet/TN0018 | B (firmware / calibration) |
| GNSS | `LC76GABMD` | 10.1 × 9.7 × 2.4 mm; 18 LCC + 10 LGA, 28 pad in total | `C7437114` is only the existing candidate, not independently closed in the current public catalogue | must be built in-house per figure 18 of the Quectel LC76G Series Hardware Design | B |
| GNSS 3.0 V LDO | `TPS7A2030PDBVR` | TI `DBV0005A`, 5-pin SOT-23, 2.90 × 1.60 mm | `C963429` | candidate `Package_TO_SOT_SMD:SOT-23-5`; review per DBV pin 1 and the pad dimensions | A |
| GNSS UART level shifting | `TXU0202DCUR` | TI `DCU0008A`, 8-pin VSSOP, 2.30 × 2.00 mm | `C5186957` | candidate `Package_SO:VSSOP-8_2.3x2mm_P0.5mm`; review per DCU0008A | A |
| Tactile switches (SW1–SW4) | `Panasonic EVQP7C01P` | 3.6 × 3.5 mm body, 1.35 mm total height; actuator 1.1 ± 0.1 mm | `C388883` | reuse `Button_Switch_SMD:SW_SPST_EVQP7C`; pad compatibility does not mean the button post height is frozen | B (enclosure gate) |
| USB-C receptacle candidate | `XKB U262-161N-4BVC11` | 16 signal contacts + 4 shell mounting solder tabs, plus 2 NPTH locating posts | `C319148` | the project footprint's historical name is still `Connector_USB:USB_C_Receptacle_XKB_U262-16XN-4BVC11`; the official MPN must not be rewritten backwards from the library name | C (mechanics not frozen) |
| Display FPC candidate | `XUNPU FPC-0.3FX-31PWBH10` | 0.3 mm pitch, 31 electrical contacts, bottom contact, flip-lock, plus mounting solder tabs | `C5343228` | must be built in-house per the manufacturer/JLC drawing; a Hirose 31P footprint must not be guessed as a substitute | C (real screen not verified) |

## 1. MCU selection audit

### 1.1 Current EVT: ESP32-S3-WROOM-1U-N16R8

Reasons for the recommendation:

- The official module already integrates 16 MB Quad SPI Flash, 8 MB Octal PSRAM, a 40 MHz crystal and RF matching, so the first board does not have to solve the double risk of a 0.4 mm pitch LGA and separate RF matching.
- The `-1U` uses a first-generation micro antenna connector compatible with U.FL / I-PEX MHF I / Amphenol AMC, which suits putting the 2.4 GHz antenna into a plastic RF window in the enclosure instead of letting the aluminium shell shield an on-board antenna.
- The official KiCad library already has a dedicated footprint candidate, but it must still be copied into the project library with the audited geometry version saved; you must not rely on the system library changing automatically in the future.
- N16R8 is officially rated for an ambient temperature of `-40…65 °C`. If PSRAM ECC is enabled, the official documentation says the maximum ambient temperature can be raised to 85 °C, but the usable PSRAM drops by 1/16; a sealed black vehicle enclosure must still be tested for solar exposure and full-load temperature rise.

Layout / electrical mandatory items:

- Strictly use the land pattern recommended in official figure 11-2; the central pin 41 goes to continuous GND with a ground via array nearby, and the solder-mask / stencil openings follow the module and assembly house recommendations.
- Place bulk storage and high-frequency decoupling close to `3V3`; design the supply for Wi-Fi transients, and do not size the regulator and power path on average current.
- `EN` must not float; implement the RC and reset as in the Espressif peripheral reference. The strapping pins at boot need the power-on default levels of their peripherals reviewed.
- Route USB D+/D− from module GPIO19/20 to USB-C as short, length-matched 90 Ω differential pairs over a continuous reference plane, avoiding inductors, SW copper and board-edge screws.
- Although the module itself does not need the base-board keepout of the WROOM-1's on-board antenna, the antenna connector, the coaxial mating space, the cable bend radius, the antenna plastic window and the isolation from the GNSS antenna must be verified in 3D and on physical parts.
- The complete Ø52 mm board can accommodate this module, but during layout a density drawing computed from real courtyards for "all key components + both antennas + FPC/USB" must be provided as a mechanical review item.

Official basis: [ESP32-S3-WROOM-1/1U Datasheet v1.8](https://www.espressif.com/sites/default/files/documentation/esp32-s3-wroom-1_wroom-1u_datasheet_en.pdf), [Espressif ESP32-S3 Hardware Design Guidelines](https://docs.espressif.com/projects/esp-hardware-design-guidelines/en/latest/esp32s3/).

### 1.2 Conditional alternative: ESP32-S3-PICO-1-N8R8

Switch to the PICO only when the central through-board battery window is restored, when the WROOM area really cannot be routed, or when a second product version must shrink further.

- The official documentation calls the device a 7 × 7 mm LGA; the schematic pin numbers cover 1–56 plus a central `GND 57`, so the PCB footprint must have 57 numbered pads, and "LGA56" must not be read as only 56 PCB pads.
- N8R8 is 8 MB Flash + 8 MB Octal PSRAM; GPIO33–37 and SPICS1 are occupied by the OSPI PSRAM, so the pin budget must be re-run before switching.
- The project must build a 57-pad footprint in-house per the official package drawing, reviewing the top view / bottom view, Pin 1, the central GND, the stencil window split, the solder-mask openings and the assembly tolerances, and must make an LGA soldering coupon / X-ray check.
- The PICO still needs a 50 Ω RF trace, matching / reserved networks and an external 2.4 GHz antenna interface; a small package does not mean the RF design is dropped.
- N8R8 is likewise `-40…65 °C`; enabling PSRAM ECC raises the maximum ambient temperature to 85 °C while losing 1/16 of the usable PSRAM.

Official basis: [ESP32-S3-PICO-1 Series Datasheet v1.2](https://documentation.espressif.com/esp32-s3-pico-1_datasheet_en.pdf).

## 2. Power chain

### 2.1 BQ25628ERYKR

- `RYK0018A` is an irregular 18-pin WQFN-HR power package; several pins / exposed copper areas carry different high-current nodes, so it cannot be created as a normal 18-pin QFN with a single central EP.
- Use the TI package drawing and the datasheet layout example as the only geometry / topology basis, and build the pads, solder mask and split stencil in-house; before submitting for assembly, have JLC engineering confirm the minimum solder-mask bridge and paste aperture.
- The input capacitor, SYS/BAT capacitors, REGN capacitor and BTST capacitor must sit tight against their pins; minimise the `PMID/SW/BTST` high-frequency loop; keep small signals such as TS, ILIM and I²C away from SW copper.
- Use short, wide copper for high-current traces, and copper pours / vias on the thermal path as TI recommends. Running "charge + screen + Wi-Fi" at the same time inside a sealed enclosure must be verified on a separate power coupon for start-up, USB plug/unplug, load steps, battery switching, thermal derating and protection behaviour.

Official basis: [TI BQ25628E Datasheet](https://www.ti.com/lit/ds/symlink/bq25628e.pdf).

### 2.2 TPS63070RNMR

- The exact order number is `TPS63070RNMR`, the package is `RNM0015A`, 15-pin VQFN-HR; do not mistakenly choose the `TPS630701/702` of the same family.
- The inductor and the VIN/VOUT ceramic capacitors form the smallest thermal loop around the chip; keep the two switching nodes as small as possible, and take the feedback divider from a clean VOUT Kelvin point away from SW and the inductor.
- Converge AGND/PGND and the thermal copper per the TI layout example; the return path must not be left to an automatic copper pour.
- When the 1.5 A system peak, the minimum battery voltage, Wi-Fi transmission and full display brightness occur at the same time, check inductor saturation, input current limiting, output droop, efficiency and sealed-enclosure temperature rise.

Official basis: [TI TPS63070 Datasheet](https://www.ti.com/lit/ds/symlink/tps63070.pdf).

### 2.3 TUSB320LAIRWBR

- The exact order number is `TUSB320LAIRWBR`, the package is `RWB0012A`, 12-pin X2QFN; it must be built in-house per the TI drawing, with particular attention to the solder mask and stencil of the small pads.
- CC1/CC2 to the Type-C socket short and symmetrical; take the values of `VBUS_DET`, `PORT/ADDR/EN_N` and the decoupling from the TI reference circuit. The CC network must not be connected to the TUSB320 on one side and designed again on the other as a "pure 5.1 kΩ Rd scheme"; one architecture must be frozen.
- Firmware may only raise the BQ25628E input current limit after detecting 1.5 A / 3 A upstream capability; by default it still starts at a safe current.

Official basis: [TI TUSB320LAI Datasheet](https://www.ti.com/lit/ds/symlink/tusb320lai.pdf).

## 3. Attitude and heading sensors

### 3.1 QMI8658A (C3021082)

- The H0175 EVT A1 exact component changes to `QMI8658A`, JLC/LCSC `C3021082`, 14-pin LGA, 2.5 × 3.0 mm, 0.5 mm pitch. With the current pad / pin connections it serves as a controlled drop-in for the QMI8658C, but "same family, same package" does not mean no verification is needed; a pad-by-pad check against the QMI8658A package/land data must still be done.
- Place it near the PCB mechanical centre, away from screw load points, board edges and large cut-outs; make the silkscreen X/Y axes, Pin 1 and the product "vehicle front direction" explicit, and the assembly drawing must not show only a dot.
- Decoupling tight against the part and a continuous local ground; only one set of bus pull-ups on the I²C. The fixed address is `0x6B`, avoiding a conflict with the BQ25628E `0x6A`, and both interrupt pins are brought out.
- EVT firmware must regress WHO_AM_I / chip revision, the initialisation registers, both interrupt pins, full scale / ODR, static zero offset, six-face orientation and the board-level X/Y/Z axis mapping. Under handlebar rotation and bumps you cannot rely on gyro integration alone as an absolute heading.

Official basis: [QST QMI8658A Datasheet](https://www.qstcorp.com/upload/pdf/202301/13-52-25%20QMI8658A%20Datasheet%20Rev%20A.pdf), [JLCPCB C3021082](https://jlcpcb.com/partdetail/QST-/C3021082).

### 3.2 IIS2MDCTR (C2655002)

- The H0175 EVT A1 exact component is `IIS2MDCTR`, JLC/LCSC `C2655002`, LGA-12L, 2 × 2 × 0.7 mm, 12 pad. The current project's I²C connection and pad position are compatible with this device; the position, dimensions and Pin 1 must still be checked pad by pad against the IIS2MDC datasheet/TN0018.
- `C1 = 220 nF` tight against the device as in the ST reference connection; in I²C mode handle the CS / interface select pin per the IIS2MDC datasheet, and verify the address as `0x1E`.
- Place it at the outer edge of the round board, as close as possible to the product's 12 o'clock direction, and keep the maximum achievable distance from the TPS63070 inductor, the BQ25628 high-frequency currents, USB/VBUS, speaker magnets, the battery protection board, steel screws and the GNSS / 2.4 GHz coaxial connector.
- High-current traces must not run under or beside the IIS2MDC. EVT firmware must verify WHO_AM_I, data ready / self-test and the product axes; use non-magnetic fasteners in the end, and carry out hard-iron / soft-iron calibration in all directions on the complete PCB, enclosure, Garmin mount and vehicle-powered state.

Official basis: [ST IIS2MDC Datasheet](https://www.st.com/resource/en/datasheet/iis2mdc.pdf), [JLCPCB C2655002](https://jlcpcb.com/partdetail/STMicroelectronics-IIS2MDCTR/C2655002), [ST TN0018 MEMS LGA mounting guideline](https://www.st.com/resource/en/technical_note/tn0018-surface-mounting-guidelines-for-mems-sensors--in-an-lga-package--stmicroelectronics.pdf).

### 3.3 EVQP7C01P (SW1–SW4)

- `EVQP7C01P` (`C388883`) keeps the current `SW_SPST_EVQP7C` land pattern, the `3.6 × 3.5 mm` body, `1.35 mm` total height, about `2.2 N` operating force and `0.2 mm` travel.
- The P suffix actuator is `1.1 ± 0.1 mm`, different from the `1.2 ± 0.1 mm` of the discontinued / out-of-stock K suffix. The PCB pads can be reused, but the enclosure button post must not copy the old height.
- After the EVT is assembled into the enclosure, check static preload, free travel, margin at full press, return, lateral eccentric loading and repeated operation; any permanently pressed mis-trigger, or failure to actuate before bottoming out, means the button post must be changed before the structure is frozen.

Official basis: [Panasonic EVQP7C01P](https://industry.panasonic.com/global/en/products/control/switch/light-touch/number/evqp7c01p), [JLCPCB C388883](https://jlcpcb.com/partdetail/PANASONIC-EVQP7C01P/C388883).

## 4. GNSS and level-shifting interface

### 4.1 LC76GABMD

- The exact suffix is `LC76GABMD` (LC76G AB version); do not treat the PA/PB versions as a same-package direct replacement; their supply and power-consumption conditions differ.
- The module measures `10.1 × 9.7 × 2.4 mm`, with 18 LCC + 10 LGA, 28 pad in total. It must be built in-house from the footprint recommended in the Quectel Hardware Design matching the purchased batch, and not reverse-engineered from product images or a third-party EasyEDA footprint.
- Quectel recommends at least 3 mm between the module and surrounding components to improve soldering quality and serviceability; this is an assembly / rework DFM recommendation, not an RF electrical clearance. To keep the RF_IN matching chain as short as possible, A1 has L3, R33, C31 and several low-speed resistors closer than 3 mm. The 5 EVT boards can keep this after JLCPCB accepts a local DFM / rework waiver in writing; mass-production Rev B must re-lay out around U10 or obtain a formal process approval, and "DRC 0" cannot be passed off as this item being met.
- The bare module has no usable built-in GNSS antenna. `RF_IN` must connect to a 1559–1606 MHz antenna; provide a π match close to the antenna and a low-capacitance ESD (Quectel recommends a junction capacitance no higher than 0.6 pF), with 50 Ω throughout, short traces and a continuous reference ground.
- As officially recommended, place `10 µF + 100 nF + 33 pF` and similar decoupling near VCC, with the smallest value closest to the supply pin; do not feed power directly from a high-noise switching node. GND pad 1, 10, 12 and 28 go to continuous ground; reserved pins stay open as officially required.
- `RESET_N` is pulled to 1.8 V internally; do not add an external 3.3 V pull-up or drive it high directly with a 3.3 V push-pull; control it per the official reference circuit.
- `C7437114` can currently be kept only as a BOM candidate number; when the BOM is released, the page MPN, the manufacturer Quectel, the ABMD suffix and the actual assemblable status must all four agree. Do not quietly substitute the PA version that can be found by search.

Official basis: [Quectel LC76G Series Hardware Design v1.3](https://www.quectel.com/content/uploads/2023/05/Quectel_LC76G_Series_Hardware_Design_V1.3.pdf), [Quectel LC76G product page](https://www.quectel.com/product/gnss-lc76g-series/).

### 4.2 TPS7A2030PDBVR

- `TPS7A2030PDBVR` is a fixed 3.0 V, 300 mA LDO in `DBV0005A` SOT-23-5. A standard KiCad SOT-23-5 can be used, but pin 1, EN and the pin map of the fixed-output version must be checked.
- Use at least the 1 µF low-ESR ceramic the datasheet allows on both input and output, close to the pins; keep the GNSS supply trace short and away from the SW copper of the TPS63070/BQ25628.
- The margin from the 3.3 V buck-boost down to 3.0 V is small; do a worst-case analysis over the LC76G peak current, trace loss, LDO dropout, low temperature and tolerances; if it does not hold, typical values may not be used to release it.

Official basis: [TI TPS7A20 product page / datasheet](https://www.ti.com/product/TPS7A20/part-details/TPS7A2030PDBVR).

### 4.3 TXU0202DCUR

- `TXU0202DCUR` is `DCU0008A` 8-pin VSSOP, 2.30 × 2.00 mm; the candidate KiCad VSSOP-8 footprint can be frozen once it has been compared item by item with the TI drawing.
- `VCCA = 3.3 V`, `VCCB = 3.0 V`. The two TXU0202 channels run in opposite directions: A1→B1 for ESP TX to GNSS RX, B2→A2 for GNSS TX to ESP RX; do not treat A2/B2 as a second A→B channel.
- Put 100 nF local decoupling on each supply, with OE pulled down by default so that the outputs are high impedance while the two supplies are not yet stable; place the device near the GNSS digital interface boundary. For 1PPS use a separately verified 3.0→3.3 V receive scheme.

Official basis: [TI TXU0202 Datasheet](https://www.ti.com/lit/ds/symlink/txu0202.pdf).

## 5. Connectors

### 5.1 USB-C candidate: XKB U262-161N-4BVC11

This MPN/footprint may only be frozen as an **electrical candidate**, and boards must not be fabricated on that basis:

- This is a USB 2.0 16-contact Type-C receptacle with 4 shell mounting solder tabs and 2 NPTH locating posts; the existing dedicated KiCad footprint is a reasonable starting point.
- The candidate in the product listing / library is a horizontal SMT construction with through-hole shell legs, and must not be mislabelled "mid-mount / recessed board type" in technical drawings. If the enclosure really requires a true mid-mount, an exact MPN must be chosen again and the footprint / opening redone.
- The mechanics must be closed: PCB board edge to receptacle datum, tongue centre Z, enclosure opening dimensions, plug envelope, rubber plug / water-resistant structure, and interference between the mounting legs and the mount / battery.
- A6/B6 merge at the receptacle edge into D+ and A7/B7 into D−, then route as 90 Ω differential pairs; low-capacitance ESD tight against the interface, surge / ESD protection on VBUS, CC1/CC2 straight to the TUSB320LAI, and the shell grounding strategy reviewed separately.

The official MPN of `C319148` is `U262-161N-4BVC11`. The project footprint's historical library name still contains `U262-16XN-4BVC11`; that string only identifies the existing pad resource and is not a basis for purchasing `16XN`.

Candidate material: [JLCPCB C319148](https://jlcpcb.com/partdetail/XKBIndustrialPrecision-U262_161N4BVC11/C319148), [KiCad official footprints](https://gitlab.com/kicad/libraries/kicad-footprints).

**Inputs needed to clear the C blocker**: the final enclosure STEP / section, the PCB Z height, the physical connector dimensions or the manufacturer dimension drawing, and a written choice of "top-mount / mid-mount".

### 5.2 Display 0.3 mm / 31-pin FPC candidate

The candidate `XUNPU FPC-0.3FX-31PWBH10` is a 0.3 mm pitch, 31 electrical contact, bottom-contact, flip-lock connector about 1.0 mm high; but it cannot be frozen directly before the original screen's physical part has been measured:

- The electrical mapping was checked pin by pin on 2026-09-03 against page 1 of the Waveshare official `ESP32-S3-Touch-AMOLED-1.75C` schematic; pins 1–31 agree with `schematic/pin-net-map.csv`.
- The candidate footprint has been checked against the EasyEDA package data of JLCPCB `C5343228` and the recommended PCB drawing of the XUNPU manufacturer `FPC-0.3FX-NPWBH10 Rev A`: the 31 staggered signal pads, the 0.30 mm X pitch, the relative position of the two rows' centres, the pad dimensions and the two mechanical pads all match. It can be reported reproducibly as `validation/fpc-footprint-audit.txt`.

- The original screen FPC's pitch, total contact count, bare gold-finger width, FPC thickness, contact face up/down, insertion direction, Pin 1 position and stiffener thickness must be confirmed.
- Besides the 31 electrical pads there are mechanical mounting solder tabs; in the symbol/footprint the mechanical pins must not be wrongly numbered as signal 32/33.
- A Hirose footprint such as `FH26-31S-0.3SHW` must not be guessed as a substitute. Even when "0.3 mm / 31P" is the same, different manufacturers may differ in contact arrangement, mounting legs, latch and acceptable FPC thickness.
- The footprint must be built in-house per the final connector manufacturer / JLC drawing, checked by overlaying a 1:1 print on the physical part; at least one FPC breakout coupon must be soldered to verify Pin 1, mating and contact orientation.
- Route the display's roughly 40 MHz QSPI lines short over a continuous ground plane, and provide series resistor positions close to the MCU; keep the screen supply decoupling close to the FPC and preserve operable latch space and a reasonable bend radius.
- Following page 7 of the controlled supplier PDF, H0175 EVT A1 supplies `TP3.3`, `IOVCC`, `VCI`, `VBAT` and `VCI_EN` directly from `3V3_DISPLAY`, without adding a 4 V selector that the panel maker has not confirmed. Because the same PDF describes VBAT inconsistently on other pages, the first board must be put through repeated cold starts, low-temperature starts and maximum-brightness tests; if the tests fail, the Rev B supply is revised from measurements / written panel-maker data, rather than guessing the voltage on A1.

Candidate material: [JLCPCB C5343228](https://jlcpcb.com/partdetail/XUNPU-FPC_0_3FX31PWBH10/C5343228).

**Inputs needed to clear the C blocker**: macro photographs of both sides of the original 1.75C screen FPC, calliper measurements, contact orientation and Pin 1 continuity tests; the electrical symbol has been built per the official drawing, and the measurements serve to finally confirm the footprint orientation and mechanical fit.

## 6. Rev A footprint release flow

Every project in-house footprint must retain all of: manufacturer package drawing screenshots / page numbers, pad coordinate table, KiCad 1:1 PDF, Pin 1 marking, courtyard, component height, solder mask and paste rules, 3D STEP (if available) and the second-person review record.

Release sequence:

1. Freeze the exact MPN; a family name must not stand in for the order number.
2. Do a pin-by-pin check against the official top/bottom view and pin table; for the WROOM, PICO and LC76G check the central / bottom pads in particular.
3. Overlay a 1:1 print on the physical part; for connectors also do a mating / insertion envelope check.
4. Make test coupons for the BQ25628E, TPS63070, TUSB320LAI, PICO (if used) and the 31P FPC to confirm stencil, reflow and inspectability.
5. Copy the footprint into the versioned project library and lock the library commit/hash; the schematic and PCB must not reference floating global libraries on a user's computer.
6. After running ERC/DRC, still carry out an independent pin-number audit, mechanical 3D interference check, power loop review and RF review.
7. On the day of ordering, re-check the LCSC/JLCPCB part number, stock, assembly category, substitutes and minimum order quantity; any suffix change means a fresh audit.

## 7. Current fabrication gates

Until every item below is closed, the files may only be marked `ENGINEERING / NOT FOR FABRICATION`:

- [x] WROOM-1U courtyard placement and fully-connected routing on the complete Ø52 mm R4 round board pass the KiCad checks.
- [ ] Battery stacking on the board, rear cover thickening, connector mating and the bending space for both coaxial cables pass a full-unit 3D check driven by physical parts.
- [ ] The physical definition of the 31-pin original screen FPC is closed and passes the breakout coupon.
- [ ] The exact USB-C construction / MPN and the enclosure section are frozen.
- [ ] The BQ25628E + TPS63070 + TUSB320LAI power coupon passes cold start, hot plug, load step, charge/discharge switching, current limiting and sealed temperature-rise tests.
- [ ] QMI8658A completes device ID/revision, interrupt, full-scale and board-level axis regression.
- [ ] IIS2MDC passes device ID / self-test, axes and hard-iron / soft-iron calibration in the assembled state, confirming that screws, residual speaker parts and power inductors do not distort the heading.
- [ ] EVQP7C01P and the enclosure button post complete preload, full travel, return and repeated-operation verification.
- [ ] The H0175 `3V3_DISPLAY` direct supply passes verification under cold start, low temperature and maximum brightness.
- [ ] The LC76GABMD purchasing part number is closed, and the GNSS antenna, 50 Ω routing, plastic RF window and full-unit positioning tests are complete; the local 3 mm DFM / rework spacing on A1 is waived in writing by JLCPCB, or closed by re-layout in Rev B.
- [ ] All in-house footprints have a second-person pin-by-pin sign-off, with no unconfirmed items in the JLC DFM / assembly capability.

Only once these conditions are met may Rev A be promoted from "designable" to "fabrication-ready".
