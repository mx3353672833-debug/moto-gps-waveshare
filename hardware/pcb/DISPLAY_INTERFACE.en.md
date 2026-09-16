> **Language:** English · [中文](DISPLAY_INTERFACE.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# H0175Y003AMT003 V1 display interface

Status: **Rev A1 EVT design baseline / connector physical orientation pending sign-off on arrival**  
Controlled document: `hardware/rev_a/references/H0175Y003AMT003_V1.pdf`  
Document SHA-256: `3e08cf17e99822025f1b2c1781c538004b4bed50ea2776c94abda12583350fbe`

The target screen is the Huaxia `H0175Y003AMT003 V1`: a 1.75-inch full round AMOLED, 466 × 466, CO5300, CST820 single-point touch, 31 Pin / 0.30 mm main interface. Rev A1 uses only QSPI and does not route MIPI.

## Connector numbering convention

Page 5 of the specification marks `OK-F302-31115` on the module's main flexible board, and both the appearance and the notes indicate that the 31 Pin end is very likely an already-installed ZIF receptacle rather than bare gold fingers that plug directly into the main board. If both the panel end and the board end are receptacles, a 31-way, 0.30 mm pitch FFC/FPC jumper about 0.20 mm thick must be used in between.

The current R4 electrical table is routed on the following "end-to-end reversed order" assumption:

`Panel pin = 32 - J3 pad`

If that assumption holds, all 31 functions in the table below match. But this is not a frozen physical fact: whether the final order is the same number or reversed is decided jointly by the orientation of the module-end socket, the orientation of the main-board-end socket and whether the exposed copper at the two ends of the jumper is on the same side (Type A) or on opposite sides (Type B). Confirmation must wait until the screen, the cable and the sockets form a complete link, using continuity checks on multiple GND pins; while it is unconfirmed, plugging in the screen and powering it is strictly prohibited.

| Module socket pin | Module signal | J3 pad | Rev A1 net | Notes |
| ---: | --- | ---: | --- | --- |
| 1 | GND | 31 | `GND` | ground |
| 2 | TP_INT | 30 | `TP_INT_N` | CST820 interrupt |
| 3 | TP_RST | 29 | `TP_RESET_N` | CST820 reset |
| 4 | TP_SDA | 28 | `I2C_SDA` | touch I²C data |
| 5 | TP_SCL | 27 | `I2C_SCL` | touch I²C clock |
| 6 | TP_3.3V | 26 | `3V3_DISPLAY` | touch supply |
| 7 | GND | 25 | `GND` | ground |
| 8 | IOVCC | 24 | `3V3_DISPLAY` | display logic supply |
| 9 | VCI | 23 | `3V3_DISPLAY` | display analogue supply |
| 10 | VBAT | 22 | `3V3_DISPLAY` | BV6802W input inside the panel; use 3.3 V for now per the p7 QSPI reference diagram |
| 11 | VCI_EN | 21 | `3V3_DISPLAY` | display supply enable |
| 12 | GND | 20 | `GND` | ground |
| 13 | MTP_PWR | 19 | `NC` | left open in normal operation |
| 14 | ERSRT / RESET | 18 | `LCD_RESET_N` | CO5300 reset, active low |
| 15 | CSX | 17 | `LCD_CS_N` | QSPI chip select, active low |
| 16 | SDI/RDX | 16 | `LCD_SIO0` | QSPI data 0 |
| 17 | DCX | 15 | `LCD_SIO1` | QSPI data 1 |
| 18 | WRX/SCL | 14 | `LCD_CLK` | QSPI clock |
| 19 | D(1) | 13 | `LCD_SIO3` | QSPI data 3 |
| 20 | D(0) | 12 | `LCD_SIO2` | QSPI data 2 |
| 21 | TE | 11 | `LCD_TE` | tearing-effect sync output |
| 22 | GND | 10 | `GND` | ground |
| 23 | D1P | 9 | `NC` | MIPI, left open in QSPI mode |
| 24 | D1N | 8 | `NC` | MIPI, left open in QSPI mode |
| 25 | GND | 7 | `GND` | ground |
| 26 | CLKP | 6 | `NC` | MIPI, left open in QSPI mode |
| 27 | CLKN | 5 | `NC` | MIPI, left open in QSPI mode |
| 28 | GND | 4 | `GND` | ground |
| 29 | D0P | 3 | `NC` | MIPI, left open in QSPI mode |
| 30 | D0N | 2 | `NC` | MIPI, left open in QSPI mode |
| 31 | GND | 1 | `GND` | ground |

## Supply baseline and first-board protection

- The QSPI reference wiring on p7 of the specification states explicitly: TP_3.3V=3.3 V, IOVCC=1.65–3.3 V, VCI=2.7–3.6 V, VBAT=3.3–5.5 V, VCI_EN=3.3 V, so the first EVT version connects all five through `FB1` to the same `3V3_DISPLAY`.
- The same specification on p6/p8 also gives the normal VBAT range as 3.7–4.5 V, while 5.5 V is at the same time the absolute maximum; this is a contradiction in the manufacturer's documentation. The first board does not apply 5 V to the screen and does not connect a bare battery directly; it is powered first according to the p7 reference diagram and the 3.3 V input stated in the product listing.
- Keep local 10 µF + 100 nF decoupling after `FB1`. For the first power-up use a current-limited supply and record the steady-state / peak current on a black screen, a full-white screen and a 700 nit high-brightness image.
- Keep 0 Ω series positions close to the ESP32 for the QSPI `CLK/SIO0..3/CS`; fit 0 Ω on the first board and only adjust it when an oscilloscope confirms ringing.
- The six MIPI pins and MTP_PWR must be left open.
- In the next schematic revision `VBAT` should be separated from the other four screen supplies, with a 0 Ω selection position and a test point allowing either 3.3 V or a controlled somewhat higher test supply; trying 5 V / a bare battery without current limiting is not permitted.

## Display software baseline

- Driver IC: CO5300; interface: QSPI; physical canvas: 466 × 466; first-version pixel format: RGB565.
- The seller did not provide an initialisation array specific to this panel, but this does not block purchasing or the PCB electrical design. The public `kodediy/esp_lcd_co5300` and several CO5300/CST820 open-source boards can serve as a bring-up baseline.
- The initialisation array may still contain panel-maker-specific gamma, scan direction or window offsets. After the first screen arrives, verify in turn the red/green/blue/white/black solid colours, a 1 px border, the four corner coordinates, refresh tearing and sleep/wake, and then freeze the final array into the firmware.
- The specification notes that the scan direction does not support reverse scanning; UI rotation is preferably handled in the software coordinate / drawing layer, and does not rely on a CO5300 reverse-scan command.

## Touch software baseline

- Touch IC: CST820; used as single-point touch as confirmed by the seller.
- The default candidate 7-bit I²C address is `0x15`; during firmware bring-up scan the bus at 100 kHz first, and raise it to 400 kHz after the address is confirmed.
- A public ESP-IDF/Arduino CST816-series compatible driver can be used with the CST820; purchasing does not have to wait for a seller driver.
- On the first board verify the INT polarity, reset timing, coordinate swap / mirroring and edge coordinates. The product interaction must not depend on two-finger zoom.

## Ten-minute sign-off on arrival

1. Confirm whether the 31 Pin end is an installed ZIF socket or bare gold fingers, and whether a cable is supplied with the screen, and record the jumper length, thickness and whether the exposed copper at the two ends is on the same side or on opposite sides.
2. Without plugging in the screen, confirm that J3 1/4/7/10/20/25/31 are all GND and 21/22/23/24/26 are 3.3 V, and that the supply is not shorted to ground.
3. With a multimeter, work back from a known GND point on the screen metal back plate / module socket through the actual jumper to several GND pins on J3 to confirm the end-to-end netlist; if any item does not match, plugging in the screen and powering it is prohibited.
4. Set 3.3 V with an initial 150 mA current limit and light it up; observe the inrush and then relax the limit step by step.
5. Complete the five-colour, border, four-edge touch, sleep/wake and 30-minute high-brightness temperature-rise tests.

Reference implementations:

- [ESP-IDF CO5300 QSPI driver](https://github.com/kodediy/esp_lcd_co5300)
- [ESP-IDF CST820 touch driver](https://github.com/kodediy/esp_lcd_touch_cst820)
- [Waveshare CO5300/CST820 examples](https://github.com/waveshareteam/ESP32-S3-Touch-AMOLED-1.8)
