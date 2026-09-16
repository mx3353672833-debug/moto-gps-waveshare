> **Language:** English · [中文](POWER_DECISION.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# Rev A power architecture: BQ25628E + TPS63070

Current status: `SELECTED / COUPON NOT YET PASSED`. The main solution has been chosen, but the complete main board still must not go into production before an independent power test coupon is completed.

## Why you cannot simply copy Waveshare

The AXP2101's charge mode, default outputs and startup behaviour are decided by the factory EFUSE configuration. The linear-charge and switching-charge versions need different external circuitry, and once the EFUSE is burned it cannot be changed to the other type by ordinary firmware. The Waveshare schematic proves that its particular chip version works, but it does not prove that JLCPCB stock `C3036461` has the same configuration.

In addition, the AXP2101's 3.3 V DCDC is a buck, not a buck-boost; when a single-cell lithium battery approaches low charge the system enters the dropout region. This can be avoided by shutting down early at about 3.4 V, but that sacrifices part of the usable capacity.

## Selected main solution

```text
USB-C 5 V
  -> TUSB320LAI (detects Default / 1.5 A / 3 A)
  -> BQ25628E (single-cell lithium switching charge + NVDC power-path)
  -> SYS
  -> TPS63070 (3.3 V buck-boost)
  -> ESP32-S3 / AMOLED / touch / sensors
  -> TPS7A2030-class low-noise LDO (3.0 V)
  -> LC76GABMD
```

Candidate JLCPCB placement parts:

| Function | Part | JLCPCB/LCSC number |
| --- | --- | --- |
| Charging and power path | BQ25628ERYKR | C18221178 |
| 3.3 V buck-boost | TPS63070RNMR | C109322 |
| USB-C current capability detection | TUSB320LAIRWBR | C132554 |
| Two 1 µH power inductor candidates | 252012CDMCDDS-1R0MC | C492725 |

The BQ25628E default hardware input current limit is set to 500 mA; only after the ESP32 reads the TUSB320LAI and confirms that the upstream supports 1.5 A/3 A is the firmware allowed to raise the charger input current limit. You must not draw a fixed 1.5 A on the strength of two 5.1 kΩ Rd resistors alone.

## Power test coupon

First make a separate test board of about 20 × 20 mm and verify:

- Battery cold start, automatic start on USB insertion, seamless switching on USB removal;
- The 3.3 V rail at no load, at a sustained 1 A, under a 1.5 A step and under a Wi-Fi-equivalent pulsed load;
- Input current limiting for the three USB-C sources Default/1.5 A/3 A;
- 500/800 mA charging, NTC charge termination and temperature rise in a sealed environment;
- The 3.3 V rail, shutdown point and reset behaviour over the whole 4.2 V to low-battery range;
- Consistency across at least 5 boards from the same batch.

Only when all of these pass may this circuitry be moved into the Rev A main board. The initial charge current limit for the sealed small enclosure is 500 mA; evaluate 800 mA later if the temperature rise allows.

## Requirements that must hold whichever route is chosen

- USB-C CC1 and CC2 each with a 5.1 kΩ pull-down; D+/D− as a 90 Ω differential pair with low-capacitance ESD added;
- A single-cell 4.2 V lithium polymer battery, with the pack carrying its own overcharge, over-discharge and short-circuit protection and a 10 kΩ NTC;
- When Type-C current detection fails or has not started, the USB input current limit must stay at no more than 500 mA;
- The GNSS main supply uses a separate low-noise LDO with the 3.3 V main rail as input and a 3.0 V output; the ESP32 and GNSS UARTs go through TXU0202 dual-supply directional level translation and the two voltage domains must not be tied together directly;
- Place the PMIC / inductors towards 6 o'clock and the magnetometer at the 12 o'clock outer edge, keeping a 25–30 mm spacing between the two as far as possible;
- Test points for VBUS, BAT, SYS, 3V3, GND and the I²C/status pins must be retained.

## AXP2101 record

The AXP2101 no longer enters the main-board main solution. Its charge mode, default outputs and startup behaviour depend on the factory EFUSE, and it cannot be proven that ordinary stock `C3036461` is the same as the Waveshare customised version; at the same time its 3.3 V DCDC is only a buck, so a single-cell lithium battery drops out at low charge. This record is kept so that it is not mistakenly added back to the BOM later.

The production package may only be generated once the status in this file has been changed to `RELEASED`.

## Main references

- [TI BQ25628E product page and datasheet](https://www.ti.com/product/BQ25628E)
- [TI TPS63070 product page and datasheet](https://www.ti.com/product/TPS63070)
- [TI TUSB320LAI product page and datasheet](https://www.ti.com/product/TUSB320LAI)
- [TI TXU0202 dual-supply directional level translator](https://www.ti.com/product/TXU0202)
- [Quectel LC76G Series Hardware Design](https://www.quectel.com/content/uploads/2023/05/Quectel_LC76G_Series_Hardware_Design_V1.3.pdf)
