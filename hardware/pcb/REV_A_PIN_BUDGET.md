# Rev A GPIO / interface budget

状态：A0 已由 `hardware/rev_a/ARCHITECTURE_FREEZE.md` 取代。下表同步保留供旧文档引用；新设计以 Rev A freeze 为准。

## 已保留映射

| ESP32-S3 GPIO | Net | Direction | Use |
| ---: | --- | --- | --- |
| 1 | `LCD_RESET_N` | output | CO5300 reset |
| 2 | `TP_RESET_N` | output | CST820 reset |
| 4 | `LCD_SIO0` | bidirectional | AMOLED QSPI data 0 |
| 5 | `LCD_SIO1` | bidirectional | AMOLED QSPI data 1 |
| 6 | `LCD_SIO2` | bidirectional | AMOLED QSPI data 2 |
| 7 | `LCD_SIO3` | bidirectional | AMOLED QSPI data 3 |
| 11 | `TP_INT_N` | input | CST820 interrupt |
| 12 | `LCD_CS_N` | output | AMOLED QSPI chip select |
| 13 | `LCD_TE` | input | AMOLED tearing-effect sync |
| 14 | `I2C_SCL` | bidirectional | CST820 + BQ25628E + TUSB320LAI + QMI8658A + IIS2MDC shared bus |
| 15 | `I2C_SDA` | bidirectional | CST820 + BQ25628E + TUSB320LAI + QMI8658A + IIS2MDC shared bus |
| 38 | `LCD_CLK` | output | AMOLED QSPI clock |

## Rev A proposed mapping

| ESP32-S3 GPIO | Net | Direction | Use / note |
| ---: | --- | --- | --- |
| 0 | `BOOT_N` | input | Internal test pad/button only; strapping pin, keep pulled high |
| 10 | `FUNC_KEY_N` | input | Optional side button; DNP if touch UX is sufficient |
| 17 | `GNSS_RX_FROM_MCU` | output | ESP UART TX → GNSS RX |
| 18 | `GNSS_TX_TO_MCU` | input | GNSS TX → ESP UART RX |
| 19 | `USB_D_N` | bidirectional | Native USB D− |
| 20 | `USB_D_P` | bidirectional | Native USB D+ |
| 21 | `IMU_INT1` | input | QMI8658A primary interrupt |
| 39 | `IMU_INT2` | input | QMI8658A secondary interrupt / wake |
| 40 | `CHARGER_INT_N` | input | BQ25628E open-drain interrupt |
| 41 | `GNSS_PPS` | input | 1PPS/time mark |
| 42 | `MAG_DRDY` | input | IIS2MDC data-ready; may be DNP and polled over I²C |
| 43 | `UART0_TX` | output | Manufacturing/debug test pad |
| 44 | `UART0_RX` | input | Manufacturing/debug test pad |
| 47 | `GNSS_RESET_N` | output | Open-drain stage only; never push-pull 3.3 V into LC76G reset |
| 48 | `TYPEC_INT_N` | input | TUSB320LAI open-drain attach/current-mode interrupt |

## Pins deliberately avoided

- `GPIO3`, `GPIO45`, `GPIO46`: strapping/JTAG/boot behavior; do not connect to peripherals that can force a reset-time level.
- `GPIO33`–`GPIO37`: internally used by the 8 MB Octal PSRAM in `ESP32-S3-PICO-1-N8R8` and unavailable to the application.

## Shared I²C addresses to verify

| Device | Expected address | Rule |
| --- | ---: | --- |
| BQ25628E | `0x6A` | Fixed 7-bit address; must not share the QMI8658 `0x6A` option |
| TUSB320LAI | `0x47` | Tie `ADDR` low; `0x67` remains a population alternative |
| CST820 | candidate `0x15` | Do not freeze from public driver defaults; scan the bus and confirm reset/interrupt behavior on H0175Y003AMT003 V1 |
| QMI8658A (`C3021082`) | **`0x6B`** | Force the address pin state that selects `0x6B`; `0x6A` conflicts with BQ25628E. EVT must regress WHO_AM_I/revision, both interrupts and board axes |
| IIS2MDCTR (`C2655002`) | `0x1E` | Current I²C wiring is compatible; freeze only after identity/self-test, axis and final-assembly calibration |

The I²C pull-ups must be fitted only once on the main board. Any pull-ups already present on a screen flex must be measured before selecting the final value.

The display module is currently treated as a likely 31P module-side connector plus a separate 31P/0.3 mm FFC. The MCU net allocation above is stable, but connector-to-connector pin numbering is **not** frozen: confirm Pin 1, both contact faces, cable orientation and end-to-end continuity on the delivered screen/cable before releasing J3 or fabrication outputs.

For H0175 EVT A1, the controlled PDF page 7 is implemented by feeding TP3.3,
IOVCC, VCI, VBAT and VCI_EN directly from `3V3_DISPLAY`. This is a first-board
testable decision: cold start, low-temperature start and maximum-brightness
operation must pass before release.
