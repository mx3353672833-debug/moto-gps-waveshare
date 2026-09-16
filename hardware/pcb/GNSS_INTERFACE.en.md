> **Language:** English · [中文](GNSS_INTERFACE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# LC76G GNSS interface

Status: Rev A0 schematic constraint, the target part is the bare module `Quectel LC76GABMD` (approximately 10.1 × 9.7 × 2.4 mm), not a development board with an antenna. The footprint pad numbering must be checked item by item against the latest Quectel hardware design document and land pattern for the purchase batch; it cannot be inferred from product photos.

## Product-level decisions

- GNSS is the source from which the device independently obtains position, speed and direction of travel; the IIS2MDC only measures the vehicle-heading magnetic heading and cannot replace positioning. H0175 EVT A1 uses `IIS2MDCTR` (`C2655002`); the current I²C wiring is compatible, but the device ID, the axes and the final calibration with the enclosure and on the vehicle are still release gates.
- **LC76G must be connected to a GNSS antenna.** The bare module itself has no usable built-in antenna, and operation without an antenna cannot be treated as a normal design.
- A fully aluminium sealed enclosure noticeably shields GNSS. The production structure must use a plastic rear cover / plastic RF window, or place the antenna in a separate plastic top bay; the antenna must not be completely sealed inside an aluminium shell.
- Rev A keeps a U.FL/I-PEX external active antenna as the debugging baseline. An internal antenna may only be substituted after cold start, weak-signal and on-vehicle installation tests pass in measurement.
- The GNSS antenna should be kept away from the AMOLED, the ESP32 antenna, switching-power inductors, USB and high-speed QSPI. The antenna keep-out and ground plane must follow the antenna vendor's specification exactly.

## Power architecture

| Net | Connection | Requirement |
| --- | --- | --- |
| `GNSS_3V0` | LC76G `VCC` | Low-noise, high-PSRR 3.0 V rail; design for at least 150 mA including an active antenna |
| `GNSS_V_BCKP` | LC76G backup supply | Retains RTC/ephemeris state; use an always-available low-quiescent-current rail or approved backup source |
| `GND` | All module ground pads | Stitch directly into a continuous ground plane with short vias |

Recommended A0 power option:

- `GNSS_3V0`: TPS7A2030-class 3.0 V LDO fed from the stable 3.3 V buck-boost rail, with input/output capacitors exactly as required by its datasheet. LC76GABMD permits 2.55–3.6 V VCC.
- Do not fit a 3.3 V LDO after a 3.3 V rail: there is no guaranteed dropout headroom. The deliberate 3.0 V GNSS domain keeps the high-PSRR regulator in control throughout the battery discharge range.
- Budget approximately 58 mA GNSS peak plus active-antenna current and margin; verify the exact firmware mode/current with the selected LC76G variant.
- `GNSS_V_BCKP`: a TPS7A02-class low-Iq rail is a candidate. Confirm the LC76G backup voltage range and leakage before freezing values.
- Place the module's specified ceramic decoupling immediately at `VCC`/`V_BCKP`; do not route either supply through the RF keep-out.

## Digital/control connections

| LC76G function | Main-board net | ESP32-S3 GPIO | Rule |
| --- | --- | ---: | --- |
| UART TX | `GNSS_TX_TO_MCU` | 18 | 3.0 V GNSS output → 3.3 V MCU RX; add accessible test pad |
| UART RX/TX | `GNSS_RX_FROM_MCU` / `GNSS_TX_TO_MCU` | 17 / 18 | Translate both directions with TXU0202; add test pads on module side |
| 1PPS/time mark | `GNSS_PPS` | 16 | Translate to 3.3 V with a Schmitt buffer/validated transistor stage; firmware may account for inversion |
| `RESET_N` | `GNSS_RESET_N` | 21 | Drive only through an open-drain stage |
| Sleep/wake/reserved control | `GNSS_CTRL0` | 40 | Population/function subject to exact LC76G variant review |

Reset implementation:

- LC76G `RESET_N` is referenced to the module's internal low-voltage domain; do not drive it directly high from a 3.3 V push-pull GPIO.
- Use a 2N7002-class NMOS/open-drain pull-down and let the module's specified pull-up establish the released level.
- Firmware reset-low time baseline: at least 100 ms, then release and wait for the documented boot interval.
- Add a test pad on the module side of the open-drain stage.

Any additional pins such as boot/configuration, antenna detect or external LNA control must stay in the datasheet-defined default state unless the exact LC76GABMD function has been reviewed. Do not copy a pin assignment from another LC76G suffix.

LC76GABMD digital inputs allow at most `VCC + 0.3 V`, while its guaranteed output-high minimum is only 2.4 V. With a 3.0 V GNSS domain and 3.3 V ESP32 domain, neither direct direction has enough worst-case margin to be treated as production-safe. Use `TXU0202` (for example the 2.3 × 2.0 mm DCU package) with VCCA=3.3 V and VCCB=3.0 V: A1→B1 carries ESP TX to GNSS RX, and B2→A2 carries GNSS TX to ESP RX. The part also isolates the buses when either rail is off. Translate 1PPS separately with a 3.0 V-compatible Schmitt buffer or a validated transistor inverter.

## RF path for the Rev A external active antenna

Signal order from connector to module:

```text
U.FL connector
  -> ultra-low-capacitance ESD device
  -> active-antenna bias injection node
  -> DC-block capacitor
  -> pi matching network (DNP/0-ohm tuning positions)
  -> optional/recommended GNSS SAW filter
  -> LC76G RF_IN
```

Candidate implementation details:

- U.FL-compatible connector: JLC candidate `C88374`, subject to lifecycle and footprint verification.
- ESD: `UMW LESD8LL5.0CT5G`, JLC/LCSC `C2987702`, SOD-882,
  bidirectional, `0.25 pF` typical / `0.30 pF` maximum; place directly
  behind the connector.  The maximum is below Quectel's `0.6 pF` limit.
- Active-antenna feed: inject LC76G `VDD_RF` (approximately the `GNSS_3V0` level) through an approximately 68 nH, high-SRF RF choke on the connector side of the DC-block capacitor. The selected active antenna must explicitly support the resulting voltage. Decouple the feed at the choke supply side.
- DC block: C0G/NP0 100 pF starting value, 0402 preferred; verify insertion loss at the used GNSS bands.
- Pi match: shunt-series-shunt footprint, initially DNP / 0 Ω as appropriate. Keep it accessible for VNA tuning.
- SAW candidate: `B39162B2618P810`-class GNSS filter; confirm supported constellations/bands and matching requirements before freezing.
- No external LNA is required when using an active antenna unless the full noise/gain budget proves otherwise. Excess gain can overload the receiver.

The active-antenna bias topology must include a current limit or fault strategy appropriate to an externally accessible connector. Validate antenna open, short and hot-plug behavior.

## RF layout rules

- Use a controlled 50 Ω coplanar/microstrip trace calculated from the final four-layer stack-up; do not reuse a generic width.
- RF trace must be short, straight and on one layer from U.FL to `RF_IN`; avoid vias. If a via transition is unavoidable, simulate/measure it and surround it with ground vias.
- Maintain a continuous reference plane beneath the transmission line. Place a dense ground-via fence along the RF path without violating the calculated coplanar gap.
- No digital, display, USB or switching-power trace may cross the RF route or its reference plane split.
- Follow the LC76G land-pattern keep-out under and around the module RF section exactly.
- Keep the IIS2MDC away from the speaker magnet, high-current loops, steel fasteners and power inductors; the magnetometer's placement constraint is independent of GNSS RF clearance.

## Bring-up and acceptance

1. Power the GNSS rail without an antenna and verify rail voltage, inrush, idle/peak current and reset behavior.
2. Verify UART output and module identity at test pads before enabling active-antenna bias.
3. With a known-good external active antenna in open sky, record cold-start TTFF, warm-start TTFF, satellite count, C/N0 distribution, fix type and position stability.
4. Repeat with Wi-Fi transmitting, AMOLED at maximum brightness, USB connected and the charger/regulator active to expose self-interference.
5. Repeat after installation on the motorcycle with the intended enclosure and phone position.
6. Rotate the complete product and test beneath the final plastic RF window. A result obtained with the bare PCB is not enclosure validation.
7. Only after external-antenna baseline passes may an internal antenna/layout variant be released for RF tuning.

The GNSS circuit is not production-frozen until the exact module suffix, antenna model, final enclosure material, PCB stack-up and conducted/radiated tests are all recorded.

Main references: [Quectel LC76G Series Hardware Design](https://www.quectel.com/content/uploads/2023/05/Quectel_LC76G_Series_Hardware_Design_V1.3.pdf) and the [TI TXU0202 datasheet](https://www.ti.com/product/TXU0202).
