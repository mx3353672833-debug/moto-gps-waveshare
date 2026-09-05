# MOTO GPS Rev A electrical architecture freeze

Status: `ENGINEERING / NOT RELEASED FOR FABRICATION`

This file is the source of truth for the first electrically complete round PCB.  It
replaces the earlier mechanical-only KiCad placeholder. H0175 EVT A1 is derived
from the immutable Rev A0 R4 routed four-layer baseline and carries the current
display/sensor/switch procurement choices. It has ERC 0, all-track DRC 0,
unconnected 0 and schematic-parity 0, plus reconciled review BOM/CPL outputs.
Production release stays blocked until the physical-part, power, RF and
procurement gates listed below all pass.

## Fixed product architecture

- Board: four-layer, 1.0 mm, nominal `Ø52 mm`, full circular board with the four
  V3 screw-boss edge scallops.  The earlier central battery window is removed.
- Battery: protected 1-cell LiPo behind the PCB on an insulating bridge/tray; it
  must not bear on the WROOM module or discrete components.  The full-board choice
  gives Rev A a continuous ground plane and enough routing area, but the actual rear
  depth must be recalculated from the measured battery: the current stack estimate is
  about 14–15 mm rather than a guaranteed 1–2 mm increase.
- MCU: `ESP32-S3-WROOM-1U-N16R8` with external 2.4 GHz antenna.  The module is used
  for the first spin to avoid a custom 0.5 mm-pitch ESP32/flash/PSRAM/RF design.
- Display/touch: Huaxia RGB `H0175Y003AMT003 V1`, 1.75-inch full-laminated
  466 × 466 AMOLED, CO5300 QSPI display and CST820 single-touch I2C
  controller. The module main interface is 31-pin / 0.30 mm pitch. Drawing page
  5 indicates that the module may already carry an OK-F302-31115 ZIF connector,
  requiring a separate 31-way FFC/FPC jumper to the PCB. The existing Rev A1
  net map is electrically correct only when the complete module/cable/board
  chain gives `module pin = 32 - J3 pad`; Type A/Type B cable construction and
  both connector orientations remain an incoming-sample continuity gate.
- Navigation sensors: `LC76GABMD` raw GNSS module, `IIS2MDCTR` (`C2655002`)
  magnetometer and `QMI8658A` (`C3021082`) six-axis IMU. GNSS is the source of
  position/speed/course while moving; IIS2MDC supplies vehicle/body heading at
  low speed or while stationary. Both sensor substitutions are EVT-controlled:
  firmware must verify device identity, interrupts and board-axis mapping before
  either is promoted to a released production BOM.
- Power: USB-C 5 V through `TUSB320LAI` Type-C attach/current detection into
  `BQ25628E` charger/NVDC power path, then `TPS63070` 3.3 V buck-boost.
  `TPS7A2030` supplies the low-noise 3.0 V GNSS rail.  The port controller owns
  CC1/CC2; do not place a second parallel pair of discrete Rd resistors.
- Display power for H0175 EVT A1 follows the controlled supplier PDF page 7:
  `TP3.3`, `IOVCC`, `VCI`, `VBAT` and `VCI_EN` are supplied from the filtered
  `3V3_DISPLAY` domain. There is no unvalidated 4 V selector in A1. The first
  assembled board must pass repeated cold start, low-temperature start and
  maximum-brightness tests before this direct-3.3 V choice is production-frozen.
- Audio hardware is omitted.  Optional phone music control is BLE HID/remote-control
  firmware and does not require a speaker, microphone or codec.
- Mechanical interface: V3 four visible M1.6 screws, right-side function button and
  the replaceable device-side Garmin quarter-turn male insert. `SW1`–`SW4` use
  Panasonic `EVQP7C01P` (`C388883`); its `1.1 ± 0.1 mm` actuator makes the final
  enclosure button-post height and travel a physical-sample release gate.

## Layer stack and placement zoning

| Layer | Use |
| --- | --- |
| F.Cu | Components and short signal/power routes |
| In1.Cu | Unbroken ground reference wherever possible |
| In2.Cu | 3V3/SYS/GNSS power distribution plus low-speed signals where required |
| B.Cu | Low-speed routing, test pads and ground fill |

The magnetometer is at 12 o'clock.  USB-C, charger, power inductors and battery
connector are at 6 o'clock.  The ESP32 module is on the left and the GNSS receiver/RF
chain is on the right.  QMI8658A is near the board centre.  No switching-current loop,
steel fastener or battery lead may enter the magnetometer keep-out.

## Frozen GPIO assignment

| GPIO | Net / function |
| ---: | --- |
| 1 | `LCD_RESET_N` |
| 2 | `TP_RESET_N` |
| 4, 5, 6, 7 | `LCD_SIO0..3` |
| 10 | `FUNC_KEY_N` |
| 11 | `TP_INT_N` |
| 12 | `LCD_CS_N` |
| 13 | `LCD_TE` |
| 14, 15 | shared `I2C_SCL`, `I2C_SDA` |
| 17, 18 | GNSS UART MCU-TX / MCU-RX through `TXU0202` |
| 19, 20 | native USB D- / D+ |
| 21 | `IMU_INT1` (matches the Waveshare reference design) |
| 38 | `LCD_CLK` |
| 39 | `IMU_INT2` |
| 40 | `CHARGER_INT_N` |
| 41 | `GNSS_PPS` |
| 42 | `MAG_DRDY` |
| 43, 44 | manufacturing UART TX / RX test pads |
| 47 | `GNSS_RESET_N` through an open-drain stage |
| 48 | `TYPEC_INT_N` |

GPIO 0 is only the BOOT test/button node.  GPIO 3, 45 and 46 are not assigned to a
peripheral because they are strap-sensitive. The shared I2C addresses are charger
`0x6A`, QMI8658A `0x6B`, CST820 touch candidate `0x15`, and IIS2MDC `0x1E`.
Firmware must scan and record the as-built CST820 address during first-panel
bring-up before that address is marked production-frozen.

## Non-negotiable release gates

The manufacturing directory must not be released until all of these are true:

1. The purchased `H0175Y003AMT003 V1` display is measured and the complete
   module-connector / 31-way jumper / PCB-connector chain is recorded: Pin 1,
   cable length/thickness, same-side or opposite-side contacts, insertion
   directions and connector heights. Multiple GND pins must prove the intended
   end-to-end map before powered insertion. If it is not
   `module pin = 32 - J3 pad`, the PCB/cable definition must be corrected first.
2. Exact LC76G suffix, land pattern and antenna implementation match the purchased
   part; GNSS has a real antenna path and an enclosure RF window.
3. Battery size includes the protection PCB, wiring and swelling allowance; its
   polarity is verified at the connector.
4. The power coupon passes cold start, USB/battery switchover, charge-current,
   1.5 A load-step and sealed-enclosure temperature tests.
5. Schematic ERC and PCB DRC pass with every waiver reviewed; there are no missing
   footprints, unconnected copper nets or zero-item fabrication exports.
6. Gerber, drill, board stack, BOM and CPL are regenerated from the same Git state
   and pass the repository semantic release check.
7. QMI8658A passes WHO_AM_I/revision, both interrupt pins, ranges and product-axis
   regression; IIS2MDC passes identity, data-ready/self-test, product-axis and
   final-assembly hard/soft-iron calibration.
8. The `EVQP7C01P` samples pass enclosure button-post clearance, full travel,
   release and repeated-actuation tests; the historical K-suffix actuator geometry
   must not be used to release the case.
9. JLC accepts the A1 LC76G local 3 mm assembly/rework-spacing waiver before any
   EVT PCBA order. The nearby RF matching chain remains short in A1; production
   Rev B must either restore the recommended clearance or carry an approved DFM
   disposition. This is separate from the GNSS 50-ohm/RF validation gate.
