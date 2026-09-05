#!/usr/bin/env python3
"""Semantic gates for the generated Rev A electrical capture."""

from __future__ import annotations

from pathlib import Path
import re

from generate_schematic import build_schematic


ROOT = Path(__file__).resolve().parent


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAIL: {message}")


def main() -> None:
    design = build_schematic()
    by_ref = {str(item["ref"]): item for item in design.component_records}
    require(len(by_ref) == len(design.component_records), "duplicate reference designator")

    required = {
        "U1": ("ESP32-S3-WROOM-1U-N16R8", "MOTO_GPS:ESP32-S3-WROOM-1U_MOTO_REVA"),
        "U2": ("BQ25628ERYKR", "MOTO_GPS:BQ25628E_RYK0018A"),
        "U3": ("TPS63070RNMR", "MOTO_GPS:TI_RNM0015A_VQFN-15"),
        "U4": ("TUSB320LAIRWBR", "Package_DFN_QFN:Texas_X2QFN-12_1.6x1.6mm_P0.4mm"),
        "U5": ("QMI8658A@0x6B", "MOTO_GPS:QMI8658C_LGA-14_2.5x3.0mm"),
        "U6": ("IIS2MDCTR@0x1E", "Package_LGA:LGA-12_2x2mm_P0.5mm"),
        "U10": ("LC76GABMD", "MOTO_GPS:Quectel_LC76GABMD_LCC18_LGA10"),
        "U11": ("TPD2EUSB30DRTR", "MOTO_GPS:Texas_DRT0003A_SON-3_1x1mm_P0.65mm_NoSilk"),
        "J3": ("XUNPU_FPC-0.3FX-31PWBH10_CANDIDATE", "MOTO_GPS:XUNPU_FPC-0.3FX-31PWBH10"),
        "J1": ("XKB_U262-161N-4BVC11_CANDIDATE", "MOTO_GPS:USB_C_XKB_U262-16XN-4BVC11_MOTO_REVA"),
    }
    for ref, (value, footprint) in required.items():
        require(ref in by_ref, f"missing {ref}")
        require(by_ref[ref]["value"] == value, f"{ref} value changed")
        require(by_ref[ref]["footprint"] == footprint, f"{ref} footprint changed")

    require(by_ref["C3"]["footprint"] == "Capacitor_SMD:C_1206_3216Metric", "C3 must use its 1 kV 1206 land pattern")
    require(by_ref["R15"]["footprint"] == "Resistor_SMD:R_0805_2012Metric", "R15 must use the stocked 900 k 0805 land pattern")
    require(by_ref["D2"]["value"] == "LESD8LL5.0CT5G_0.3pFmax", "D2 must meet the LC76G 0.6 pF maximum antenna requirement")
    require(by_ref["D2"]["procurement"].lcsc == "C2987702", "D2 LCSC code must match the audited UMW SOD-882 part")
    for ref in ("L1", "L2"):
        require(by_ref[ref]["value"] == "1uH_4A_5.6A_sat_C5832370", f"{ref} obsolete inductor selection returned")

    u1_expected = {
        "4": "LCD_SIO0_MCU",
        "5": "LCD_SIO1_MCU",
        "6": "LCD_SIO2_MCU",
        "7": "LCD_SIO3_MCU",
        "10": "GNSS_UART_TX_3V3",
        "11": "GNSS_UART_RX_3V3",
        "13": "USB_D_N_MCU",
        "14": "USB_D_P_MCU",
        "18": "FUNC_KEY_N",
        "19": "TP_INT_N",
        "20": "LCD_CS_N_MCU",
        "21": "LCD_TE",
        "22": "I2C_SCL",
        "23": "QMI_INT1",
        "24": "GNSS_RESET_DRV",
        "25": "TYPEC_INT_N",
        "27": "BOOT_N",
        "31": "LCD_CLK_MCU",
        "32": "QMI_INT2",
        "33": "CHARGER_INT_N",
        "34": "GNSS_PPS_3V3",
        "35": "MAG_DRDY",
        "36": "UART0_RX",
        "37": "UART0_TX",
        "38": "TP_RESET_N",
        "39": "LCD_RESET_N",
    }
    u1_nets = by_ref["U1"]["nets"]
    require(isinstance(u1_nets, dict), "U1 net map is not a dictionary")
    for pin, net in u1_expected.items():
        require(u1_nets.get(pin) == net, f"U1 pin {pin}: expected {net}")
    for unused_pin in ("12", "15", "16", "17", "26", "28", "29", "30"):
        require(u1_nets.get(unused_pin) is None, f"U1 strap/reserved pin {unused_pin} must be NC")

    j3_expected = {
        "1": "GND", "2": None, "3": None, "4": "GND", "5": None,
        "6": None, "7": "GND", "8": None, "9": None, "10": "GND",
        "11": "LCD_TE", "12": "LCD_SIO2", "13": "LCD_SIO3", "14": "LCD_CLK",
        "15": "LCD_SIO1", "16": "LCD_SIO0", "17": "LCD_CS_N",
        "18": "LCD_RESET_N", "19": None, "20": "GND",
        "21": "3V3_DISPLAY", "22": "3V3_DISPLAY", "23": "3V3_DISPLAY",
        "24": "3V3_DISPLAY", "25": "GND", "26": "3V3_DISPLAY",
        "27": "I2C_SCL", "28": "I2C_SDA", "29": "TP_RESET_N",
        "30": "TP_INT_N", "31": "GND",
    }
    require(by_ref["J3"]["nets"] == j3_expected, "31-pin display/touch map changed")
    for removed_ref in ("R35", "R36", "C38", "TP18"):
        require(removed_ref not in by_ref, f"{removed_ref} must not be fitted on conservative 3V3 EVT A1")

    require("R10" not in by_ref and "R11" not in by_ref, "parallel Type-C Rd parts returned")
    forbidden = re.compile(r"B39162|SAW|microphone|codec|speaker|microSD", re.IGNORECASE)
    for item in design.component_records:
        require(not forbidden.search(str(item["value"])), f"forbidden Rev A part at {item['ref']}")

    require(by_ref["U2"]["nets"].get("14") == "BQ_CE_N", "BQ CE must use hardware-default net")
    require(by_ref["U3"]["nets"].get("1") == "TPS_EN_SYNC", "TPS PS/SYNC resistor node missing")
    require(by_ref["U3"]["nets"].get("14") == "TPS_EN_SYNC", "TPS EN resistor node missing")
    require(by_ref["U3"]["nets"].get("6") is None, "unused TPS FB2 must be NC")

    schematic = (ROOT / "moto-gps-rev-a.kicad_sch").read_text(encoding="utf-8")
    wire_count = len(re.findall(r"(?m)^\s*\(wire\b", schematic))
    require(wire_count >= 20, "fewer than 20 real wire objects")
    require('(dnp yes)' in schematic, "RF tuning DNP state not serialized")
    require((ROOT / "moto-gps-rev-a.xml").is_file(), "exported netlist missing")
    require((ROOT / "moto-gps-rev-a.pdf").is_file(), "review PDF missing")

    print(
        f"PASS: {len(design.component_records)} schematic records, "
        f"{wire_count} wires, "
        "frozen GPIO/display/power/RF gates intact"
    )


if __name__ == "__main__":
    main()
