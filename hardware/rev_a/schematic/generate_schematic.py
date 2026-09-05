#!/usr/bin/env python3
"""Generate the Rev A EVT electrical schematic in legacy KiCad syntax.

KiCad 10 upgrades the generated ``.sch`` into the checked-in ``.kicad_sch``.
The generator keeps the large pin-accurate custom symbols auditable as data.
It intentionally does not generate a PCB or fabrication output.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import csv
import shutil
import subprocess
import uuid


ROOT = Path(__file__).resolve().parent
BASENAME = "moto-gps-rev-a"


@dataclass(frozen=True)
class Pin:
    number: str
    name: str
    x: int
    y: int
    length: int
    orientation: str
    electrical: str = "P"


@dataclass(frozen=True)
class Symbol:
    name: str
    ref_prefix: str
    pins: tuple[Pin, ...]
    half_width: int = 500


@dataclass(frozen=True)
class ProcurementGroup:
    """Audited assembly choice shared by one or more designators.

    ``expected_value`` and ``expected_footprint`` deliberately duplicate the
    electrical design.  The generator fails closed if either side drifts, so a
    syntactically valid C-number cannot silently select the wrong package.
    Stock is still time-dependent; ``status`` records the remaining release
    work rather than pretending that a catalogue match is a fab sign-off.
    """

    refs: tuple[str, ...]
    expected_value: str
    expected_footprint: str
    lcsc: str
    manufacturer: str
    mpn: str
    status: str
    source_url: str
    checked_on: str = "2026-09-03"
    notes: str = ""


def distributed_symbol(
    name: str,
    ref_prefix: str,
    pins: list[tuple[str, str, str]],
    half_width: int = 500,
    spacing: int = 100,
) -> Symbol:
    """Place odd-index pins left and even-index pins right on a rectangle."""
    left = pins[::2]
    right = pins[1::2]
    count = max(len(left), len(right))
    top = (count - 1) * spacing // 2
    result: list[Pin] = []
    for index, (number, pin_name, electrical) in enumerate(left):
        result.append(Pin(number, pin_name, -half_width - 100, top - index * spacing, 100, "R", electrical))
    for index, (number, pin_name, electrical) in enumerate(right):
        result.append(Pin(number, pin_name, half_width + 100, top - index * spacing, 100, "L", electrical))
    return Symbol(name, ref_prefix, tuple(result), half_width)


def two_pin_symbol(name: str, ref_prefix: str = "R") -> Symbol:
    return Symbol(
        name,
        ref_prefix,
        (
            Pin("1", "1", -300, 0, 150, "R", "P"),
            Pin("2", "2", 300, 0, 150, "L", "P"),
        ),
        150,
    )


SYMBOLS: dict[str, Symbol] = {}


def add(symbol: Symbol) -> None:
    SYMBOLS[symbol.name] = symbol


add(two_pin_symbol("MOTO_GPS_RESISTOR", "R"))
add(two_pin_symbol("MOTO_GPS_CAPACITOR", "C"))
add(two_pin_symbol("MOTO_GPS_INDUCTOR", "L"))
add(two_pin_symbol("MOTO_GPS_FERRITE", "FB"))
add(two_pin_symbol("MOTO_GPS_SWITCH", "SW"))
add(two_pin_symbol("MOTO_GPS_TVS", "D"))
add(Symbol("MOTO_GPS_TESTPOINT", "TP", (Pin("1", "TP", -250, 0, 150, "R", "P"),), 100))
add(Symbol("MOTO_GPS_POWER_FLAG", "#FLG", (Pin("1", "PWR_FLAG", -250, 0, 150, "R", "w"),), 100))

add(
    distributed_symbol(
        "MOTO_GPS_ESP32_S3_WROOM_1U",
        "U",
        [
            ("1", "GND", "W"), ("2", "3V3", "W"), ("3", "EN", "I"),
            ("4", "IO4", "B"), ("5", "IO5", "B"), ("6", "IO6", "B"),
            ("7", "IO7", "B"), ("8", "IO15", "B"), ("9", "IO16", "B"),
            ("10", "IO17", "B"), ("11", "IO18", "B"), ("12", "IO8", "B"),
            ("13", "USB_D-", "B"), ("14", "USB_D+", "B"), ("15", "IO3", "B"),
            ("16", "IO46", "B"), ("17", "IO9", "B"), ("18", "IO10", "B"),
            ("19", "IO11", "B"), ("20", "IO12", "B"), ("21", "IO13", "B"),
            ("22", "IO14", "B"), ("23", "IO21", "B"), ("24", "IO47", "B"),
            ("25", "IO48", "B"), ("26", "IO45", "B"), ("27", "IO0", "B"),
            ("28", "IO35", "B"), ("29", "IO36", "B"), ("30", "IO37", "B"),
            ("31", "IO38", "B"), ("32", "IO39", "B"), ("33", "IO40", "B"),
            ("34", "IO41", "B"), ("35", "IO42", "B"), ("36", "RXD0/IO44", "B"),
            ("37", "TXD0/IO43", "B"), ("38", "IO2", "B"), ("39", "IO1", "B"),
            ("40", "GND", "W"), ("41", "EP_GND", "W"),
        ],
        half_width=650,
        spacing=100,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_BQ25628E",
        "U",
        [
            ("1", "BTST", "P"), ("2", "REGN", "w"), ("3", "PG", "C"),
            ("4", "ILIM", "B"), ("5", "TS_BIAS", "w"), ("6", "TS", "I"),
            ("7", "QON", "I"), ("8", "BAT", "W"), ("9", "SYS", "w"),
            ("10", "STAT", "C"), ("11", "INT", "C"), ("12", "SDA", "B"),
            ("13", "SCL", "I"), ("14", "CE", "I"), ("15", "GND", "W"),
            ("16", "SW", "w"), ("17", "PMID", "P"), ("18", "VBUS", "W"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_TPS63070",
        "U",
        [
            ("1", "PS/SYNC", "I"), ("2", "PG", "C"), ("3", "VAUX", "w"),
            ("4", "GND", "W"), ("5", "FB", "I"), ("6", "FB2", "P"),
            ("7", "VOUT", "w"), ("8", "VOUT", "P"), ("9", "L2", "w"),
            ("10", "PGND", "W"), ("11", "L1", "w"), ("12", "VIN", "W"),
            ("13", "VIN", "W"), ("14", "EN", "I"), ("15", "VSEL", "I"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_TUSB320LAI",
        "U",
        [
            ("1", "CC1", "B"), ("2", "CC2", "B"), ("3", "PORT", "I"),
            ("4", "VBUS_DET", "I"), ("5", "ADDR", "I"), ("6", "INT_N/OUT3", "C"),
            ("7", "SDA/OUT1", "B"), ("8", "SCL/OUT2", "B"), ("9", "ID", "C"),
            ("10", "GND", "W"), ("11", "EN_N", "I"), ("12", "VDD", "W"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_QMI8658C",
        "U",
        [
            # In I2C mode SDO/SA0 is an address strap. SDx/SCx are unused
            # auxiliary-bus pins and are intentionally left open below.
            ("1", "SDO/SA0", "I"), ("2", "SDx", "B"), ("3", "SCx", "B"),
            ("4", "INT1", "O"), ("5", "VDDIO", "W"), ("6", "GND", "W"),
            ("7", "GND", "W"), ("8", "VDD", "W"), ("9", "INT2", "O"),
            ("10", "RESV", "I"), ("11", "RESV-NC", "N"), ("12", "CS", "I"),
            ("13", "SCL", "B"), ("14", "SDA", "B"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_LIS2MDL",
        "U",
        [
            ("1", "SCL/SPC", "I"), ("2", "NC", "N"), ("3", "CS", "I"),
            ("4", "SDA/SDI/SDO", "B"), ("5", "C1", "P"), ("6", "GND", "W"),
            ("7", "INT/DRDY", "O"), ("8", "GND", "W"), ("9", "VDD", "W"),
            ("10", "VDD_IO", "W"), ("11", "NC", "N"), ("12", "NC", "N"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_TPS7A2030",
        "U",
        [
            ("1", "IN", "W"), ("2", "GND", "W"), ("3", "EN", "I"),
            ("4", "NC", "N"), ("5", "OUT", "w"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_TXU0202",
        "U",
        [
            ("1", "B2", "I"), ("2", "GND", "W"), ("3", "VCCA", "W"),
            ("4", "A2Y", "O"), ("5", "A1", "I"), ("6", "OE", "I"),
            ("7", "VCCB", "W"), ("8", "B1Y", "O"),
        ],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_SN74LVC1G17",
        "U",
        [("1", "NC", "N"), ("2", "A", "I"), ("3", "GND", "W"), ("4", "Y", "O"), ("5", "VCC", "W")],
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_2N7002",
        "Q",
        [("1", "G", "I"), ("2", "S", "P"), ("3", "D", "P")],
        half_width=300,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_LC76GABMD",
        "U",
        [
            ("1", "GND", "W"), ("2", "TXD", "O"), ("3", "RXD", "I"),
            ("4", "1PPS", "O"), ("5", "RESERVED", "N"), ("6", "V_BCKP", "W"),
            ("7", "RESERVED", "N"), ("8", "VCC", "W"), ("9", "RESET_N", "I"),
            ("10", "GND", "W"), ("11", "RF_IN", "I"), ("12", "GND", "W"),
            ("13", "ANT_ON", "O"), ("14", "VDD_RF", "w"), ("15", "RESERVED", "N"),
            ("16", "I2C_SDA", "B"), ("17", "I2C_SCL", "B"), ("18", "RESERVED", "N"),
            ("19", "RESERVED", "N"), ("20", "SPI_CS", "I"), ("21", "GEOFENCE", "O"),
            ("22", "JAM_IND", "O"), ("23", "3D_FIX", "O"), ("24", "D_SEL", "I"),
            ("25", "SPI_CLK", "I"), ("26", "SPI_MISO", "O"), ("27", "SPI_MOSI", "I"),
            ("28", "GND", "W"),
        ],
        half_width=600,
        spacing=100,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_DISPLAY_FPC_31",
        "J",
        [(str(index), f"FPC_{index}", "P") for index in range(1, 32)],
        half_width=550,
        spacing=100,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_USB_C_16P",
        "J",
        [
            ("A1", "GND", "P"), ("A4", "VBUS", "P"), ("A5", "CC1", "B"),
            ("A6", "D+", "B"), ("A7", "D-", "B"), ("A8", "SBU1", "P"),
            ("A9", "VBUS", "P"), ("A12", "GND", "P"), ("B1", "GND", "P"),
            ("B4", "VBUS", "P"), ("B5", "CC2", "B"), ("B6", "D+", "B"),
            ("B7", "D-", "B"), ("B8", "SBU2", "P"), ("B9", "VBUS", "P"),
            ("B12", "GND", "P"), ("S1", "SHIELD", "P"),
        ],
        half_width=550,
        spacing=100,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_BATTERY_3P",
        "J",
        [("1", "BAT+", "P"), ("2", "NTC", "P"), ("3", "BAT-", "P")],
        half_width=300,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_USB_ESD",
        "U",
        [("1", "D+", "P"), ("2", "GND", "W"), ("3", "D-", "P")],
        half_width=300,
    )
)

add(
    distributed_symbol(
        "MOTO_GPS_UFL",
        "J",
        [("1", "RF", "P"), ("2", "GND", "W"), ("3", "GND", "W")],
        half_width=300,
    )
)

def make_cache_library() -> str:
    lines = ["EESchema-LIBRARY Version 2.4", "#encoding utf-8"]
    for symbol in SYMBOLS.values():
        ys = [pin.y for pin in symbol.pins]
        top = max(ys, default=0) + 100
        bottom = min(ys, default=0) - 100
        lines.extend(
            [
                "#",
                f"# {symbol.name}",
                "#",
                f"DEF {symbol.name} {symbol.ref_prefix} 0 20 Y Y 1 F N",
                f'F0 "{symbol.ref_prefix}" 0 {top + 100} 50 H V C CNN',
                f'F1 "{symbol.name}" 0 {bottom - 100} 50 H V C CNN',
                "DRAW",
                f"S {-symbol.half_width} {top} {symbol.half_width} {bottom} 0 1 12 f",
            ]
        )
        for pin in symbol.pins:
            clean_name = pin.name.replace(" ", "_")
            lines.append(
                f"X {clean_name} {pin.number} {pin.x} {pin.y} {pin.length} {pin.orientation} "
                f"40 40 1 1 {pin.electrical}"
            )
        lines.extend(["ENDDRAW", "ENDDEF"])
    lines.extend(["#", "#End Library", ""])
    return "\n".join(lines)


def _jlc(code: str) -> str:
    return f"https://jlcpcb.com/partdetail/{code}"


PROCUREMENT_GROUPS: tuple[ProcurementGroup, ...] = (
    # Capacitors.  Voltage/dielectric/tolerance are frozen in the MPN even
    # where the short human-facing Value field omits them.
    ProcurementGroup(("C1", "C19"), "10uF/6.3V", "Capacitor_SMD:C_0603_1608Metric", "C19702", "Samsung Electro-Mechanics", "CL10A106KP8NNNC", "VERIFIED_CATALOG", _jlc("C19702")),
    ProcurementGroup(("C2", "C5", "C6", "C18", "C20", "C22", "C24", "C29", "C30", "C35"), "100nF", "Capacitor_SMD:C_0402_1005Metric", "C1525", "Samsung Electro-Mechanics", "CL05B104KO5NNNC", "VERIFIED_CATALOG", _jlc("C1525")),
    ProcurementGroup(("C3",), "4.7nF/1kV", "Capacitor_SMD:C_1206_3216Metric", "C303960", "Walsin", "1206B472K102CT", "VERIFIED_CATALOG", "https://www.lcsc.com/product-detail/C303960.html", notes="1206 is required; the former 0603 land pattern was not valid for this 1 kV part."),
    ProcurementGroup(("C4", "C9", "C12"), "10uF/10V", "Capacitor_SMD:C_0603_1608Metric", "C19702", "Samsung Electro-Mechanics", "CL10A106KP8NNNC", "VERIFIED_CATALOG", _jlc("C19702")),
    ProcurementGroup(("C7",), "47nF/10V", "Capacitor_SMD:C_0402_1005Metric", "C82219", "FH (Guangdong Fenghua Advanced Tech)", "0402B473K500NT", "VERIFIED_CATALOG", _jlc("C82219")),
    ProcurementGroup(("C8",), "4.7uF/10V", "Capacitor_SMD:C_0603_1608Metric", "C1705", "Samsung Electro-Mechanics", "CL10A475KP8NNNC", "VERIFIED_CATALOG", _jlc("C1705")),
    ProcurementGroup(("C10", "C11"), "22uF/10V", "Capacitor_SMD:C_0805_2012Metric", "C380336", "CCTC", "TCC0805X5R226K100FT", "VERIFIED_CATALOG", _jlc("C380336")),
    ProcurementGroup(("C13",), "1uF/25V", "Capacitor_SMD:C_0603_1608Metric", "C513775", "Samwha Capacitor", "CS1608X7R105K250NRB", "VERIFIED_CATALOG", _jlc("C513775")),
    ProcurementGroup(("C14", "C15"), "10uF/10V", "Capacitor_SMD:C_0805_2012Metric", "C1713", "Samsung Electro-Mechanics", "CL21A106KOQNNNE", "VERIFIED_CATALOG", _jlc("C1713"), notes="16 V-rated replacement provides margin over the 10 V design minimum."),
    ProcurementGroup(("C16", "C17"), "22uF/6.3V", "Capacitor_SMD:C_0805_2012Metric", "C19841964", "IHHEC / 21169363", "C0805B226M007T", "VERIFIED_CATALOG_STOCK_RECHECK", _jlc("C19841964")),
    ProcurementGroup(("C21", "C27"), "1uF", "Capacitor_SMD:C_0402_1005Metric", "C52923", "Samsung Electro-Mechanics", "CL05A105KA5NQNC", "VERIFIED_CATALOG", _jlc("C52923")),
    ProcurementGroup(("C23", "C25", "C28"), "2.2uF", "Capacitor_SMD:C_0603_1608Metric", "C86013", "Murata Electronics", "GRM188R61A225KE34D", "VERIFIED_CATALOG", _jlc("C86013")),
    ProcurementGroup(("C26",), "220nF", "Capacitor_SMD:C_0402_1005Metric", "C47129", "Samsung Electro-Mechanics", "CL05A224KA5NNNC", "VERIFIED_CATALOG", _jlc("C47129")),
    ProcurementGroup(("C31",), "100pF_C0G", "Capacitor_SMD:C_0402_1005Metric", "C106200", "YAGEO", "CC0402JRNPO9BN101", "RF_VALIDATION_GATE", _jlc("C106200")),
    ProcurementGroup(("C34",), "10uF", "Capacitor_SMD:C_0603_1608Metric", "C19702", "Samsung Electro-Mechanics", "CL10A106KP8NNNC", "VERIFIED_CATALOG", _jlc("C19702")),
    ProcurementGroup(("C36",), "100nF_VBCKP", "Capacitor_SMD:C_0402_1005Metric", "C1525", "Samsung Electro-Mechanics", "CL05B104KO5NNNC", "VERIFIED_CATALOG", _jlc("C1525")),
    ProcurementGroup(("C37",), "33pF_C0G", "Capacitor_SMD:C_0402_1005Metric", "C107005", "YAGEO", "CC0402JRNPO9BN330", "RF_VALIDATION_GATE", _jlc("C107005")),

    # Protection, filtering and connectors.
    ProcurementGroup(("D1",), "ESD5Z5.0T1G", "Diode_SMD:D_SOD-523", "C5199848", "UMW", "ESD5Z5.0T1G", "VERIFIED_CATALOG_STOCK_RECHECK", _jlc("C5199848")),
    ProcurementGroup(("D2",), "LESD8LL5.0CT5G_0.3pFmax", "Diode_SMD:D_SOD-882", "C2987702", "UMW", "LESD8LL5.0CT5G", "RF_VALIDATION_GATE", _jlc("C2987702"), notes="0.25 pF typical / 0.30 pF maximum bidirectional antenna TVS; meets Quectel LC76G <=0.6 pF requirement. Exact SOD-882 package matches the assigned footprint; RF match remains an EVT measurement gate."),
    ProcurementGroup(("FB1",), "120R@100MHz_2A", "Inductor_SMD:L_0603_1608Metric", "C14709", "Murata Electronics", "BLM18PG121SN1D", "VERIFIED_CATALOG", _jlc("C14709")),
    ProcurementGroup(("J1",), "XKB_U262-161N-4BVC11_CANDIDATE", "MOTO_GPS:USB_C_XKB_U262-16XN-4BVC11_MOTO_REVA", "C319148", "XKB Industrial Precision", "U262-161N-4BVC11", "PHYSICAL_SAMPLE_GATE", _jlc("C319148"), notes="C319148 official MPN is U262-161N-4BVC11. The legacy project footprint name contains 16XN, but its audited land geometry follows C319148. Shell aperture, tongue depth and board Z remain a physical-sample gate."),
    ProcurementGroup(("J2",), "1S_LiPo_10k_NTC_PROTECTED", "MOTO_GPS:JST_SH_SM03B-SRSS-TB_MOTO_REVA", "C160403", "JST", "SM03B-SRSS-TB(LF)(SN)", "PHYSICAL_SAMPLE_GATE", _jlc("C160403"), notes="Battery harness pinout and 10 k NTC curve must be frozen before assembly."),
    ProcurementGroup(("J3",), "XUNPU_FPC-0.3FX-31PWBH10_CANDIDATE", "MOTO_GPS:XUNPU_FPC-0.3FX-31PWBH10", "C5343228", "XUNPU", "FPC-0.3FX-31PWBH10", "PHYSICAL_SAMPLE_GATE", _jlc("C5343228"), notes="Display FPC contact side, insertion direction, thickness and pin 1 require sample sign-off."),
    ProcurementGroup(("J4",), "U.FL_GNSS_ACTIVE_ANT", "MOTO_GPS:UFL_Hirose_UFL-R-SMT-1_MOTO_REVA", "C88373", "Hirose", "U.FL-R-SMT-1(10)", "RF_VALIDATION_GATE", _jlc("C88373")),

    # Power and RF inductors.  C5832370 replaces discontinued C492725 while
    # keeping the reviewed 2520 land pattern and improving saturation margin.
    ProcurementGroup(("L1", "L2"), "1uH_4A_5.6A_sat_C5832370", "Inductor_SMD:L_Changjiang_FTC252012S", "C5832370", "cjiang (Changjiang Microelectronics Tech)", "FTC252012S1R0MBCA", "ELECTRICAL_VALIDATION_GATE", _jlc("C5832370"), notes="Recalculate both converter worst-case ripple and verify on the power coupon before release."),
    ProcurementGroup(("L3",), "68nH_high-SRF", "Inductor_SMD:L_0402_1005Metric", "C108965", "Murata Electronics", "LQW15AN68NG00D", "RF_VALIDATION_GATE", _jlc("C108965")),
    ProcurementGroup(("Q1",), "2N7002", "Package_TO_SOT_SMD:SOT-23", "C8545", "Jiangsu Changjing Electronics", "2N7002", "VERIFIED_CATALOG", _jlc("C8545")),

    # Resistors.
    ProcurementGroup(("R1", "R2", "R3", "R4", "R5", "R6"), "0R", "Resistor_SMD:R_0402_1005Metric", "C17168", "Uniroyal Electronics", "0402WGF0000TCE", "VERIFIED_CATALOG", _jlc("C17168")),
    ProcurementGroup(("R7", "R8", "R9", "R16", "R17", "R21", "R22", "R29", "R30", "R31"), "10k", "Resistor_SMD:R_0402_1005Metric", "C25744", "Uniroyal Electronics", "0402WGF1002TCE", "VERIFIED_CATALOG", _jlc("C25744")),
    ProcurementGroup(("R12",), "1M", "Resistor_SMD:R_0402_1005Metric", "C26083", "Uniroyal Electronics", "0402WGF1004TCE", "VERIFIED_CATALOG", _jlc("C26083")),
    ProcurementGroup(("R13", "R14"), "22R", "Resistor_SMD:R_0402_1005Metric", "C25092", "Uniroyal Electronics", "0402WGF220JTCE", "VERIFIED_CATALOG", _jlc("C25092")),
    ProcurementGroup(("R15",), "900k_1%", "Resistor_SMD:R_0805_2012Metric", "C52209984", "CHANGLONG", "CL0805FN900KPS", "VERIFIED_CATALOG", _jlc("C52209984"), notes="0805 replaces the discontinued generic 0402 selection; 150 V rating comfortably exceeds USB VBUS."),
    ProcurementGroup(("R18",), "4.99k_1%_ILIM=501mA_typ", "Resistor_SMD:R_0402_1005Metric", "C25903", "Uniroyal Electronics", "0402WGF4991TCE", "VERIFIED_CATALOG", _jlc("C25903")),
    ProcurementGroup(("R19",), "5.23k_1%", "Resistor_SMD:R_0402_1005Metric", "C477746", "YAGEO", "RC0402FR-075K23L", "VERIFIED_CATALOG_STOCK_RECHECK", _jlc("C477746"), notes="In-stock 0402 5.23 kOhm +/-1% direct replacement for the unavailable C4273622; verify stock again when ordering."),
    ProcurementGroup(("R20",), "30.1k_1%", "Resistor_SMD:R_0402_1005Metric", "C2998131", "FOJAN", "FRC0402F3012TS", "VERIFIED_CATALOG", _jlc("C2998131")),
    ProcurementGroup(("R23",), "100k_default_charge_on", "Resistor_SMD:R_0402_1005Metric", "C25741", "Uniroyal Electronics", "0402WGF1003TCE", "VERIFIED_CATALOG", _jlc("C25741")),
    ProcurementGroup(("R24",), "470k_1%", "Resistor_SMD:R_0402_1005Metric", "C25790", "Uniroyal Electronics", "0402WGF4703TCE", "VERIFIED_CATALOG", _jlc("C25790")),
    ProcurementGroup(("R25",), "150k_1%", "Resistor_SMD:R_0402_1005Metric", "C25755", "Uniroyal Electronics", "0402WGF1503TCE", "VERIFIED_CATALOG", _jlc("C25755")),
    ProcurementGroup(("R26", "R32"), "100k", "Resistor_SMD:R_0402_1005Metric", "C25741", "Uniroyal Electronics", "0402WGF1003TCE", "VERIFIED_CATALOG", _jlc("C25741")),
    ProcurementGroup(("R27", "R28"), "4.7k_EVT", "Resistor_SMD:R_0402_1005Metric", "C25900", "Uniroyal Electronics", "0402WGF4701TCE", "VERIFIED_CATALOG", _jlc("C25900")),
    ProcurementGroup(("R33",), "0R_RF_SERIES", "Resistor_SMD:R_0402_1005Metric", "C17168", "Uniroyal Electronics", "0402WGF0000TCE", "RF_VALIDATION_GATE", _jlc("C17168")),
    ProcurementGroup(("R34",), "100k_EN+PFM", "Resistor_SMD:R_0402_1005Metric", "C25741", "Uniroyal Electronics", "0402WGF1003TCE", "VERIFIED_CATALOG", _jlc("C25741")),

    ProcurementGroup(("SW1",), "POWER_QON", "Button_Switch_SMD:SW_SPST_EVQP7C", "C388883", "Panasonic", "EVQP7C01P", "PHYSICAL_SAMPLE_GATE", _jlc("C388883"), notes="In-stock P suffix retains the reviewed land pattern, 3.6 x 3.5 mm body, 1.35 mm height, 2.2 N force and 0.2 mm travel. Its 1.1+/-0.1 mm actuator differs from the discontinued K suffix; validate the enclosure button post."),
    ProcurementGroup(("SW2",), "RESET", "Button_Switch_SMD:SW_SPST_EVQP7C", "C388883", "Panasonic", "EVQP7C01P", "PHYSICAL_SAMPLE_GATE", _jlc("C388883"), notes="P-suffix actuator geometry requires enclosure button-post validation."),
    ProcurementGroup(("SW3",), "BOOT", "Button_Switch_SMD:SW_SPST_EVQP7C", "C388883", "Panasonic", "EVQP7C01P", "PHYSICAL_SAMPLE_GATE", _jlc("C388883"), notes="P-suffix actuator geometry requires enclosure button-post validation."),
    ProcurementGroup(("SW4",), "FUNCTION", "Button_Switch_SMD:SW_SPST_EVQP7C", "C388883", "Panasonic", "EVQP7C01P", "PHYSICAL_SAMPLE_GATE", _jlc("C388883"), notes="P-suffix actuator geometry requires enclosure button-post validation."),

    # ICs/modules.
    ProcurementGroup(("U1",), "ESP32-S3-WROOM-1U-N16R8", "MOTO_GPS:ESP32-S3-WROOM-1U_MOTO_REVA", "C3013946", "Espressif Systems", "ESP32-S3-WROOM-1U-N16R8", "PHYSICAL_SAMPLE_GATE", _jlc("C3013946"), notes="Custom land pattern and antenna cable keepout require second-person sign-off."),
    ProcurementGroup(("U2",), "BQ25628ERYKR", "MOTO_GPS:BQ25628E_RYK0018A", "C18221178", "Texas Instruments", "BQ25628ERYKR", "ELECTRICAL_VALIDATION_GATE", _jlc("C18221178")),
    ProcurementGroup(("U3",), "TPS63070RNMR", "MOTO_GPS:TI_RNM0015A_VQFN-15", "C109322", "Texas Instruments", "TPS63070RNMR", "ELECTRICAL_VALIDATION_GATE", _jlc("C109322")),
    ProcurementGroup(("U4",), "TUSB320LAIRWBR", "Package_DFN_QFN:Texas_X2QFN-12_1.6x1.6mm_P0.4mm", "C132554", "Texas Instruments", "TUSB320LAIRWBR", "VERIFIED_CATALOG_STOCK_RECHECK", _jlc("C132554")),
    ProcurementGroup(("U5",), "QMI8658A@0x6B", "MOTO_GPS:QMI8658C_LGA-14_2.5x3.0mm", "C3021082", "QST", "QMI8658A", "ELECTRICAL_VALIDATION_GATE", _jlc("C3021082"), notes="Pin-compatible stocked EVT replacement for unavailable QMI8658C. Validate WHO_AM_I/revision handling, interrupts, axes and ranges in firmware before release."),
    ProcurementGroup(("U6",), "IIS2MDCTR@0x1E", "Package_LGA:LGA-12_2x2mm_P0.5mm", "C2655002", "STMicroelectronics", "IIS2MDCTR", "ELECTRICAL_VALIDATION_GATE", _jlc("C2655002"), notes="Pin-compatible I2C replacement for LIS2MDLTR; SPI removal is irrelevant to this design. Validate ID, axes, self-test and hard/soft-iron calibration in the CNC enclosure."),
    ProcurementGroup(("U7",), "TPS7A2030PDBVR", "Package_TO_SOT_SMD:SOT-23-5", "C963429", "Texas Instruments", "TPS7A2030PDBVR", "ELECTRICAL_VALIDATION_GATE", _jlc("C963429")),
    ProcurementGroup(("U8",), "TXU0202DCUR", "MOTO_GPS:VSSOP-8_2.3x2mm_P0.5mm_NoSilk", "C5186957", "Texas Instruments", "TXU0202DCUR", "VERIFIED_CATALOG_STOCK_RECHECK", _jlc("C5186957")),
    ProcurementGroup(("U9",), "SN74LVC1G17DBVR", "MOTO_GPS:SOT-23-5_NoSilk", "C7836", "Texas Instruments", "SN74LVC1G17DBVR", "VERIFIED_CATALOG", _jlc("C7836")),
    ProcurementGroup(("U10",), "LC76GABMD", "MOTO_GPS:Quectel_LC76GABMD_LCC18_LGA10", "C7437114", "Quectel", "LC76GABMD", "RF_VALIDATION_GATE", _jlc("C7437114"), notes="Active antenna path, controlled impedance and sealed-enclosure C/N0/TTFF require validation."),
    ProcurementGroup(("U11",), "TPD2EUSB30DRTR", "MOTO_GPS:Texas_DRT0003A_SON-3_1x1mm_P0.65mm_NoSilk", "C97502", "Texas Instruments", "TPD2EUSB30DRTR", "VERIFIED_CATALOG", _jlc("C97502")),
)


def _build_procurement_index() -> dict[str, ProcurementGroup]:
    result: dict[str, ProcurementGroup] = {}
    for group in PROCUREMENT_GROUPS:
        if not group.lcsc.startswith("C") or not group.lcsc[1:].isdigit():
            raise ValueError(f"invalid LCSC code {group.lcsc!r} for {group.refs}")
        for ref in group.refs:
            if ref in result:
                raise ValueError(f"duplicate procurement mapping for {ref}")
            result[ref] = group
    return result


PROCUREMENT_BY_REF = _build_procurement_index()


def validate_procurement_design(design: "Schematic") -> None:
    records = {str(record["ref"]): record for record in design.component_records}
    production_refs = {
        ref
        for ref, record in records.items()
        if not ref.startswith(("TP", "#"))
        and not str(record["value"]).startswith("DNP")
        and bool(record["footprint"])
    }
    mapped_refs = set(PROCUREMENT_BY_REF)
    missing = sorted(production_refs - mapped_refs)
    extra = sorted(mapped_refs - production_refs)
    if missing or extra:
        raise ValueError(f"procurement coverage mismatch: missing={missing}, extra={extra}")
    for ref in sorted(production_refs):
        record = records[ref]
        procurement = PROCUREMENT_BY_REF[ref]
        if str(record["value"]) != procurement.expected_value:
            raise ValueError(
                f"{ref} procurement value mismatch: design={record['value']!r}, "
                f"mapping={procurement.expected_value!r}"
            )
        if str(record["footprint"]) != procurement.expected_footprint:
            raise ValueError(
                f"{ref} procurement footprint mismatch: design={record['footprint']!r}, "
                f"mapping={procurement.expected_footprint!r}"
            )


class Schematic:
    def __init__(self) -> None:
        self.component_records: list[dict[str, object]] = []
        self.note_records: list[tuple[int, int, str, int]] = []
        self.lines = [
            "EESchema Schematic File Version 4",
            "LIBS:moto-gps-rev-a-cache",
            "EELAYER 29 0",
            "EELAYER END",
            "$Descr A3 16535 11693",
            "Sheet 1 1",
            'Title "MOTO GPS Rev A EVT electrical schematic"',
            'Date "2026-09-03"',
            'Rev "A-EVT-SCH-01"',
            'Comp "MOTO GPS"',
            'Comment1 "ENGINEERING / NOT FOR FABRICATION"',
            'Comment2 "NO AUDIO, MICROPHONE, RTC OR SD"',
            'Comment3 "FPC + CUSTOM LAND PATTERNS BLOCK RELEASE"',
            'Comment4 "POWER COUPON REQUIRED"',
            "$EndDescr",
        ]
        self.counter = 0xA0000000

    def note(self, x: int, y: int, text: str, size: int = 80) -> None:
        self.note_records.append((x, y, text, size))
        self.lines.extend([f"Text Notes {x} {y} 0    {size}   ~ 16", text])

    def component(
        self,
        symbol_name: str,
        ref: str,
        value: str,
        footprint: str,
        x: int,
        y: int,
        nets: dict[str, str | None],
    ) -> None:
        symbol = SYMBOLS[symbol_name]
        self.component_records.append(
            {
                "symbol_name": symbol_name,
                "ref": ref,
                "value": value,
                "footprint": footprint,
                "x": x,
                "y": y,
                "nets": dict(nets),
                "procurement": PROCUREMENT_BY_REF.get(ref),
            }
        )
        self.counter += 1
        ys = [pin.y for pin in symbol.pins]
        top = max(ys, default=0)
        self.lines.extend(
            [
                "$Comp",
                f"L {symbol_name} {ref}",
                f"U 1 1 {self.counter:X}",
                f"P {x} {y}",
                f'F 0 "{ref}" H {x} {y - top - 220} 50  0000 C CNN',
                f'F 1 "{value}" H {x} {y - top - 130} 50  0000 C CNN',
                f'F 2 "{footprint}" H {x} {y} 50  0001 C CNN',
                'F 3 "" H 0 0 50  0001 C CNN',
                f"\t1    {x} {y}",
                "\t1    0    0    -1",
                "$EndComp",
            ]
        )
        for pin in symbol.pins:
            if pin.number not in nets:
                continue
            px = x + pin.x
            py = y - pin.y
            net = nets[pin.number]
            if net is None:
                self.lines.append(f"NoConn ~ {px} {py}")
            else:
                # Keep the label anchor clear of the adjacent 800 mil-spaced
                # two-pin device.  A 100 mil stub on both facing pins made
                # neighbouring anchors coincide and unintentionally joined
                # every horizontal passive row into one net.
                stub_x = px - 50 if pin.orientation == "R" else px + 50
                self.lines.extend(
                    [
                        f"Wire Wire Line",
                        f"\t{px} {py} {stub_x} {py}",
                        f"Text Label {stub_x} {py} 0    40   ~ 0",
                        net,
                    ]
                )

    def finish(self) -> str:
        return "\n".join(self.lines + ["$EndSCHEMATC", ""])


def p2(s: Schematic, symbol: str, ref: str, value: str, footprint: str, x: int, y: int, a: str, b: str) -> None:
    s.component(symbol, ref, value, footprint, x, y, {"1": a, "2": b})


def build_schematic() -> Schematic:
    s = Schematic()
    s.note(2500, 550, "MOTO GPS REV A — FUNCTIONAL EVT SCHEMATIC", 110)
    s.note(3600, 760, "NOT FOR FABRICATION: complete electrical capture; PCB/layout/footprint/power-coupon validation still required", 55)
    s.note(2050, 1050, "MCU / NATIVE USB / DISPLAY", 70)
    s.note(9850, 850, "USB-C / POWER PATH", 70)
    s.note(2350, 6500, "MOTION + MAGNETOMETER", 70)
    s.note(7700, 6500, "GNSS / LEVEL SHIFT / RF", 70)

    s.component(
        "MOTO_GPS_ESP32_S3_WROOM_1U", "U1", "ESP32-S3-WROOM-1U-N16R8", "MOTO_GPS:ESP32-S3-WROOM-1U_MOTO_REVA", 3200, 3300,
        {
            "1": "GND", "2": "3V3", "3": "MCU_EN", "4": "LCD_SIO0_MCU", "5": "LCD_SIO1_MCU",
            "6": "LCD_SIO2_MCU", "7": "LCD_SIO3_MCU", "8": "I2C_SDA", "9": None,
            "10": "GNSS_UART_TX_3V3", "11": "GNSS_UART_RX_3V3", "12": None,
            "13": "USB_D_N_MCU", "14": "USB_D_P_MCU", "15": None, "16": None, "17": None,
            "18": "FUNC_KEY_N", "19": "TP_INT_N", "20": "LCD_CS_N_MCU", "21": "LCD_TE",
            "22": "I2C_SCL", "23": "QMI_INT1", "24": "GNSS_RESET_DRV", "25": "TYPEC_INT_N",
            "26": None, "27": "BOOT_N", "28": None, "29": None, "30": None, "31": "LCD_CLK_MCU",
            "32": "QMI_INT2", "33": "CHARGER_INT_N", "34": "GNSS_PPS_3V3", "35": "MAG_DRDY",
            "36": "UART0_RX", "37": "UART0_TX", "38": "TP_RESET_N", "39": "LCD_RESET_N",
            "40": "GND", "41": "GND",
        },
    )

    display_nets = {
        "1": "GND", "2": None, "3": None, "4": "GND", "5": None, "6": None, "7": "GND", "8": None,
        "9": None, "10": "GND", "11": "LCD_TE", "12": "LCD_SIO2", "13": "LCD_SIO3", "14": "LCD_CLK",
        "15": "LCD_SIO1", "16": "LCD_SIO0", "17": "LCD_CS_N", "18": "LCD_RESET_N", "19": None,
        "20": "GND", "21": "3V3_DISPLAY", "22": "3V3_DISPLAY", "23": "3V3_DISPLAY", "24": "3V3_DISPLAY",
        "25": "GND", "26": "3V3_DISPLAY", "27": "I2C_SCL", "28": "I2C_SDA", "29": "TP_RESET_N",
        "30": "TP_INT_N", "31": "GND",
    }
    s.component(
        "MOTO_GPS_DISPLAY_FPC_31", "J3", "XUNPU_FPC-0.3FX-31PWBH10_CANDIDATE", "MOTO_GPS:XUNPU_FPC-0.3FX-31PWBH10", 6500, 3100, display_nets,
    )

    s.component(
        "MOTO_GPS_USB_C_16P", "J1", "XKB_U262-161N-4BVC11_CANDIDATE", "MOTO_GPS:USB_C_XKB_U262-16XN-4BVC11_MOTO_REVA", 10300, 1800,
        {
            "A1": "GND", "A4": "USB_VBUS", "A5": "USB_CC1", "A6": "USB_D_P_CONN", "A7": "USB_D_N_CONN",
            "A8": None, "A9": "USB_VBUS", "A12": "GND", "B1": "GND", "B4": "USB_VBUS", "B5": "USB_CC2",
            "B6": "USB_D_P_CONN", "B7": "USB_D_N_CONN", "B8": None, "B9": "USB_VBUS", "B12": "GND", "S1": "USB_SHIELD",
        },
    )
    s.component("MOTO_GPS_USB_ESD", "U11", "TPD2EUSB30DRTR", "MOTO_GPS:Texas_DRT0003A_SON-3_1x1mm_P0.65mm_NoSilk", 11800, 1400, {"1": "USB_D_P_CONN", "2": "GND", "3": "USB_D_N_CONN"})

    s.component(
        "MOTO_GPS_TUSB320LAI", "U4", "TUSB320LAIRWBR", "Package_DFN_QFN:Texas_X2QFN-12_1.6x1.6mm_P0.4mm", 13800, 1900,
        {"1": "USB_CC1", "2": "USB_CC2", "3": "GND", "4": "TYPEC_VBUS_DET", "5": "GND", "6": "TYPEC_INT_N", "7": "I2C_SDA", "8": "I2C_SCL", "9": None, "10": "GND", "11": "GND", "12": "3V3"},
    )

    s.component(
        "MOTO_GPS_BQ25628E", "U2", "BQ25628ERYKR", "MOTO_GPS:BQ25628E_RYK0018A", 10600, 4500,
        {"1": "BQ_BTST", "2": "BQ_REGN", "3": "CHARGER_PG_N", "4": "BQ_ILIM", "5": "BQ_TS_BIAS", "6": "BAT_TS", "7": "BQ_QON", "8": "BAT", "9": "SYS", "10": "CHARGER_STAT_N", "11": "CHARGER_INT_N", "12": "I2C_SDA", "13": "I2C_SCL", "14": "BQ_CE_N", "15": "GND", "16": "BQ_SW", "17": "BQ_PMID", "18": "USB_VBUS"},
    )
    s.component("MOTO_GPS_BATTERY_3P", "J2", "1S_LiPo_10k_NTC_PROTECTED", "MOTO_GPS:JST_SH_SM03B-SRSS-TB_MOTO_REVA", 12200, 4600, {"1": "BAT", "2": "BAT_TS", "3": "GND"})

    s.component(
        "MOTO_GPS_TPS63070", "U3", "TPS63070RNMR", "MOTO_GPS:TI_RNM0015A_VQFN-15", 14100, 4550,
        {"1": "TPS_EN_SYNC", "2": "3V3_PG_N", "3": "TPS63070_VAUX", "4": "GND", "5": "TPS63070_FB", "6": None, "7": "3V3", "8": "3V3", "9": "TPS63070_L2", "10": "GND", "11": "TPS63070_L1", "12": "SYS", "13": "SYS", "14": "TPS_EN_SYNC", "15": "GND"},
    )

    s.component(
        "MOTO_GPS_QMI8658C", "U5", "QMI8658A@0x6B", "MOTO_GPS:QMI8658C_LGA-14_2.5x3.0mm", 2900, 7600,
        {"1": "GND", "2": None, "3": None, "4": "QMI_INT1", "5": "3V3", "6": "GND", "7": "GND", "8": "3V3", "9": "QMI_INT2", "10": "3V3", "11": None, "12": "3V3", "13": "I2C_SCL", "14": "I2C_SDA"},
    )
    s.component(
        "MOTO_GPS_LIS2MDL", "U6", "IIS2MDCTR@0x1E", "Package_LGA:LGA-12_2x2mm_P0.5mm", 5400, 7600,
        {"1": "I2C_SCL", "2": None, "3": "3V3", "4": "I2C_SDA", "5": "LIS2MDL_C1", "6": "GND", "7": "MAG_DRDY", "8": "GND", "9": "3V3", "10": "3V3", "11": None, "12": None},
    )

    s.component("MOTO_GPS_TPS7A2030", "U7", "TPS7A2030PDBVR", "Package_TO_SOT_SMD:SOT-23-5", 7700, 7400, {"1": "3V3", "2": "GND", "3": "3V3", "4": None, "5": "GNSS_3V0"})
    s.component("MOTO_GPS_TXU0202", "U8", "TXU0202DCUR", "MOTO_GPS:VSSOP-8_2.3x2mm_P0.5mm_NoSilk", 9300, 7400, {"1": "GNSS_TX_3V0", "2": "GND", "3": "3V3", "4": "GNSS_UART_RX_3V3", "5": "GNSS_UART_TX_3V3", "6": "3V3", "7": "GNSS_3V0", "8": "GNSS_RX_3V0"})
    s.component("MOTO_GPS_SN74LVC1G17", "U9", "SN74LVC1G17DBVR", "MOTO_GPS:SOT-23-5_NoSilk", 10700, 7400, {"1": None, "2": "GNSS_PPS_3V0", "3": "GND", "4": "GNSS_PPS_3V3", "5": "3V3"})
    s.component("MOTO_GPS_2N7002", "Q1", "2N7002", "Package_TO_SOT_SMD:SOT-23", 11900, 7400, {"1": "GNSS_RESET_DRV", "2": "GND", "3": "GNSS_RESET_N_1V8"})
    s.component(
        "MOTO_GPS_LC76GABMD", "U10", "LC76GABMD", "MOTO_GPS:Quectel_LC76GABMD_LCC18_LGA10", 14100, 7600,
        {"1": "GND", "2": "GNSS_TX_3V0", "3": "GNSS_RX_3V0", "4": "GNSS_PPS_3V0", "5": None, "6": "GNSS_3V0", "7": None, "8": "GNSS_3V0", "9": "GNSS_RESET_N_1V8", "10": "GND", "11": "GNSS_RF_IN", "12": "GND", "13": "GNSS_ANT_ON", "14": "GNSS_VDD_RF", "15": None, "16": None, "17": None, "18": None, "19": None, "20": None, "21": None, "22": None, "23": None, "24": "GND", "25": None, "26": None, "27": None, "28": "GND"},
    )

    # Display signal conditioning and rail filtering.
    p2(s, "MOTO_GPS_RESISTOR", "R1", "0R", "Resistor_SMD:R_0402_1005Metric", 5600, 5100, "LCD_SIO0_MCU", "LCD_SIO0")
    p2(s, "MOTO_GPS_RESISTOR", "R2", "0R", "Resistor_SMD:R_0402_1005Metric", 6400, 5100, "LCD_SIO1_MCU", "LCD_SIO1")
    p2(s, "MOTO_GPS_RESISTOR", "R3", "0R", "Resistor_SMD:R_0402_1005Metric", 7200, 5100, "LCD_SIO2_MCU", "LCD_SIO2")
    p2(s, "MOTO_GPS_RESISTOR", "R4", "0R", "Resistor_SMD:R_0402_1005Metric", 8000, 5100, "LCD_SIO3_MCU", "LCD_SIO3")
    p2(s, "MOTO_GPS_RESISTOR", "R5", "0R", "Resistor_SMD:R_0402_1005Metric", 8800, 5100, "LCD_CLK_MCU", "LCD_CLK")
    p2(s, "MOTO_GPS_RESISTOR", "R6", "0R", "Resistor_SMD:R_0402_1005Metric", 9600, 5100, "LCD_CS_N_MCU", "LCD_CS_N")
    p2(s, "MOTO_GPS_FERRITE", "FB1", "120R@100MHz_2A", "Inductor_SMD:L_0603_1608Metric", 7200, 5600, "3V3", "3V3_DISPLAY")
    p2(s, "MOTO_GPS_RESISTOR", "R7", "10k", "Resistor_SMD:R_0402_1005Metric", 8000, 5600, "3V3", "LCD_RESET_N")
    p2(s, "MOTO_GPS_RESISTOR", "R8", "10k", "Resistor_SMD:R_0402_1005Metric", 8800, 5600, "3V3", "TP_RESET_N")
    p2(s, "MOTO_GPS_RESISTOR", "R9", "10k", "Resistor_SMD:R_0402_1005Metric", 9600, 5600, "3V3", "TP_INT_N")
    p2(s, "MOTO_GPS_CAPACITOR", "C1", "10uF/6.3V", "Capacitor_SMD:C_0603_1608Metric", 7200, 6000, "3V3_DISPLAY", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C2", "100nF", "Capacitor_SMD:C_0402_1005Metric", 8000, 6000, "3V3_DISPLAY", "GND")
    # The H0175 QSPI reference drawing explicitly permits VBAT=3.3 V.  EVT A1
    # follows that conservative reference connection and keeps all five panel
    # rails behind FB1 on 3V3_DISPLAY.  Do not apply 5 V or a raw cell.

    # USB-C, USB2 and Type-C controller passives.
    p2(s, "MOTO_GPS_RESISTOR", "R12", "1M", "Resistor_SMD:R_0402_1005Metric", 11900, 2850, "USB_SHIELD", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C3", "4.7nF/1kV", "Capacitor_SMD:C_1206_3216Metric", 12700, 2850, "USB_SHIELD", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R13", "22R", "Resistor_SMD:R_0402_1005Metric", 10300, 3250, "USB_D_P_CONN", "USB_D_P_MCU")
    p2(s, "MOTO_GPS_RESISTOR", "R14", "22R", "Resistor_SMD:R_0402_1005Metric", 11100, 3250, "USB_D_N_CONN", "USB_D_N_MCU")
    p2(s, "MOTO_GPS_RESISTOR", "R15", "900k_1%", "Resistor_SMD:R_0805_2012Metric", 11900, 3250, "USB_VBUS", "TYPEC_VBUS_DET")
    p2(s, "MOTO_GPS_RESISTOR", "R16", "10k", "Resistor_SMD:R_0402_1005Metric", 12700, 3250, "3V3", "TYPEC_INT_N")
    p2(s, "MOTO_GPS_TVS", "D1", "ESD5Z5.0T1G", "Diode_SMD:D_SOD-523", 13500, 3250, "USB_VBUS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C4", "10uF/10V", "Capacitor_SMD:C_0603_1608Metric", 14300, 3250, "USB_VBUS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C5", "100nF", "Capacitor_SMD:C_0402_1005Metric", 15100, 3250, "USB_VBUS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C6", "100nF", "Capacitor_SMD:C_0402_1005Metric", 15100, 2800, "3V3", "GND")

    # Charger and power-path passives.
    p2(s, "MOTO_GPS_CAPACITOR", "C7", "47nF/10V", "Capacitor_SMD:C_0402_1005Metric", 10100, 5850, "BQ_BTST", "BQ_SW")
    p2(s, "MOTO_GPS_CAPACITOR", "C8", "4.7uF/10V", "Capacitor_SMD:C_0603_1608Metric", 10900, 5850, "BQ_REGN", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R17", "10k", "Resistor_SMD:R_0402_1005Metric", 11700, 5850, "3V3", "CHARGER_PG_N")
    p2(s, "MOTO_GPS_RESISTOR", "R18", "4.99k_1%_ILIM=501mA_typ", "Resistor_SMD:R_0402_1005Metric", 12500, 5850, "BQ_ILIM", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R19", "5.23k_1%", "Resistor_SMD:R_0402_1005Metric", 13300, 5850, "BQ_TS_BIAS", "BAT_TS")
    p2(s, "MOTO_GPS_RESISTOR", "R20", "30.1k_1%", "Resistor_SMD:R_0402_1005Metric", 14100, 5850, "BAT_TS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C9", "10uF/10V", "Capacitor_SMD:C_0603_1608Metric", 14900, 5850, "BAT", "GND")
    p2(s, "MOTO_GPS_INDUCTOR", "L1", "1uH_4A_5.6A_sat_C5832370", "Inductor_SMD:L_Changjiang_FTC252012S", 10100, 6250, "BQ_SW", "SYS")
    p2(s, "MOTO_GPS_CAPACITOR", "C10", "22uF/10V", "Capacitor_SMD:C_0805_2012Metric", 10900, 6250, "SYS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C11", "22uF/10V", "Capacitor_SMD:C_0805_2012Metric", 11700, 6250, "SYS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C12", "10uF/10V", "Capacitor_SMD:C_0603_1608Metric", 12500, 6250, "BQ_PMID", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C13", "1uF/25V", "Capacitor_SMD:C_0603_1608Metric", 13300, 6250, "USB_VBUS", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R21", "10k", "Resistor_SMD:R_0402_1005Metric", 14100, 6250, "3V3", "CHARGER_STAT_N")
    p2(s, "MOTO_GPS_RESISTOR", "R22", "10k", "Resistor_SMD:R_0402_1005Metric", 14900, 6250, "3V3", "CHARGER_INT_N")
    p2(s, "MOTO_GPS_RESISTOR", "R23", "100k_default_charge_on", "Resistor_SMD:R_0402_1005Metric", 15700, 6250, "BQ_CE_N", "GND")
    s.note(12300, 6550, "BQ25628E ILIM: IINREG = KILIM / RILIM = 2500 A-ohm / 4.99 kohm = 0.501 A typical", 42)

    # Buck-boost support network.
    p2(s, "MOTO_GPS_INDUCTOR", "L2", "1uH_4A_5.6A_sat_C5832370", "Inductor_SMD:L_Changjiang_FTC252012S", 10300, 9100, "TPS63070_L1", "TPS63070_L2")
    p2(s, "MOTO_GPS_CAPACITOR", "C14", "10uF/10V", "Capacitor_SMD:C_0805_2012Metric", 11100, 9100, "SYS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C15", "10uF/10V", "Capacitor_SMD:C_0805_2012Metric", 11900, 9100, "SYS", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C16", "22uF/6.3V", "Capacitor_SMD:C_0805_2012Metric", 12700, 9100, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C17", "22uF/6.3V", "Capacitor_SMD:C_0805_2012Metric", 13500, 9100, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C18", "100nF", "Capacitor_SMD:C_0402_1005Metric", 14300, 9100, "TPS63070_VAUX", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R24", "470k_1%", "Resistor_SMD:R_0402_1005Metric", 15100, 9100, "3V3", "TPS63070_FB")
    p2(s, "MOTO_GPS_RESISTOR", "R25", "150k_1%", "Resistor_SMD:R_0402_1005Metric", 15900, 9100, "TPS63070_FB", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R26", "100k", "Resistor_SMD:R_0402_1005Metric", 15100, 9500, "3V3", "3V3_PG_N")
    p2(s, "MOTO_GPS_RESISTOR", "R34", "100k_EN+PFM", "Resistor_SMD:R_0402_1005Metric", 15900, 9500, "SYS", "TPS_EN_SYNC")
    s.note(12200, 8800, "TPS63070: SYS -> 100k -> shared EN/PS-SYNC; 1uH + 44uF nominal allowed, effective C/load-step requires coupon", 42)

    # MCU reset/boot, bus pull-ups and sensor decoupling.
    p2(s, "MOTO_GPS_RESISTOR", "R27", "4.7k_EVT", "Resistor_SMD:R_0402_1005Metric", 700, 9300, "3V3", "I2C_SCL")
    p2(s, "MOTO_GPS_RESISTOR", "R28", "4.7k_EVT", "Resistor_SMD:R_0402_1005Metric", 1500, 9300, "3V3", "I2C_SDA")
    p2(s, "MOTO_GPS_CAPACITOR", "C19", "10uF/6.3V", "Capacitor_SMD:C_0603_1608Metric", 2300, 9300, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C20", "100nF", "Capacitor_SMD:C_0402_1005Metric", 3100, 9300, "3V3", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R29", "10k", "Resistor_SMD:R_0402_1005Metric", 3900, 9300, "3V3", "MCU_EN")
    p2(s, "MOTO_GPS_CAPACITOR", "C21", "1uF", "Capacitor_SMD:C_0402_1005Metric", 4700, 9300, "MCU_EN", "GND")
    p2(s, "MOTO_GPS_SWITCH", "SW2", "RESET", "Button_Switch_SMD:SW_SPST_EVQP7C", 5500, 9300, "MCU_EN", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R30", "10k", "Resistor_SMD:R_0402_1005Metric", 6300, 9300, "3V3", "BOOT_N")
    p2(s, "MOTO_GPS_SWITCH", "SW3", "BOOT", "Button_Switch_SMD:SW_SPST_EVQP7C", 7100, 9300, "BOOT_N", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R31", "10k", "Resistor_SMD:R_0402_1005Metric", 7900, 9300, "3V3", "FUNC_KEY_N")
    p2(s, "MOTO_GPS_SWITCH", "SW4", "FUNCTION", "Button_Switch_SMD:SW_SPST_EVQP7C", 8700, 9300, "FUNC_KEY_N", "GND")
    p2(s, "MOTO_GPS_SWITCH", "SW1", "POWER_QON", "Button_Switch_SMD:SW_SPST_EVQP7C", 9500, 9300, "BQ_QON", "GND")

    p2(s, "MOTO_GPS_CAPACITOR", "C22", "100nF", "Capacitor_SMD:C_0402_1005Metric", 700, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C23", "2.2uF", "Capacitor_SMD:C_0603_1608Metric", 1500, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C24", "100nF", "Capacitor_SMD:C_0402_1005Metric", 2300, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C25", "2.2uF", "Capacitor_SMD:C_0603_1608Metric", 3100, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C26", "220nF", "Capacitor_SMD:C_0402_1005Metric", 3900, 9700, "LIS2MDL_C1", "GND")

    # GNSS rails, level shifters, reset and RF chain.
    p2(s, "MOTO_GPS_CAPACITOR", "C27", "1uF", "Capacitor_SMD:C_0402_1005Metric", 4700, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C28", "2.2uF", "Capacitor_SMD:C_0603_1608Metric", 5500, 9700, "GNSS_3V0", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C29", "100nF", "Capacitor_SMD:C_0402_1005Metric", 6300, 9700, "3V3", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C30", "100nF", "Capacitor_SMD:C_0402_1005Metric", 7100, 9700, "GNSS_3V0", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R32", "100k", "Resistor_SMD:R_0402_1005Metric", 7900, 9700, "GNSS_RESET_DRV", "GND")
    s.component("MOTO_GPS_UFL", "J4", "U.FL_GNSS_ACTIVE_ANT", "MOTO_GPS:UFL_Hirose_UFL-R-SMT-1_MOTO_REVA", 9100, 9900, {"1": "GNSS_ANT_BIASED", "2": "GND", "3": "GND"})
    p2(s, "MOTO_GPS_TVS", "D2", "LESD8LL5.0CT5G_0.3pFmax", "Diode_SMD:D_SOD-882", 10000, 9900, "GNSS_ANT_BIASED", "GND")
    p2(s, "MOTO_GPS_INDUCTOR", "L3", "68nH_high-SRF", "Inductor_SMD:L_0402_1005Metric", 10800, 9900, "GNSS_VDD_RF", "GNSS_ANT_BIASED")
    p2(s, "MOTO_GPS_CAPACITOR", "C31", "100pF_C0G", "Capacitor_SMD:C_0402_1005Metric", 11600, 9900, "GNSS_ANT_BIASED", "GNSS_RF_MATCH_IN")
    p2(s, "MOTO_GPS_CAPACITOR", "C32", "DNP_RF_SHUNT", "Capacitor_SMD:C_0402_1005Metric", 12400, 9900, "GNSS_RF_MATCH_IN", "GND")
    p2(s, "MOTO_GPS_RESISTOR", "R33", "0R_RF_SERIES", "Resistor_SMD:R_0402_1005Metric", 13200, 9900, "GNSS_RF_MATCH_IN", "GNSS_RF_IN")
    p2(s, "MOTO_GPS_CAPACITOR", "C33", "DNP_RF_SHUNT", "Capacitor_SMD:C_0402_1005Metric", 14000, 9900, "GNSS_RF_IN", "GND")
    s.note(8500, 10300, "1559-1606 MHz multi-constellation RF path: no GPS-only narrow SAW; pi network starts DNP / 0R / DNP", 42)
    p2(s, "MOTO_GPS_CAPACITOR", "C34", "10uF", "Capacitor_SMD:C_0603_1608Metric", 700, 10100, "GNSS_3V0", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C35", "100nF", "Capacitor_SMD:C_0402_1005Metric", 1500, 10100, "GNSS_3V0", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C36", "100nF_VBCKP", "Capacitor_SMD:C_0402_1005Metric", 2300, 10100, "GNSS_3V0", "GND")
    p2(s, "MOTO_GPS_CAPACITOR", "C37", "33pF_C0G", "Capacitor_SMD:C_0402_1005Metric", 3100, 10100, "GNSS_3V0", "GND")

    # Test points for bring-up and production programming.
    test_nets = [
        ("TP1", "VBUS", "USB_VBUS"), ("TP2", "BAT", "BAT"), ("TP3", "SYS", "SYS"),
        ("TP4", "3V3", "3V3"), ("TP5", "GND", "GND"), ("TP6", "SCL", "I2C_SCL"),
        ("TP7", "SDA", "I2C_SDA"), ("TP8", "U0TX", "UART0_TX"), ("TP9", "U0RX", "UART0_RX"),
        ("TP10", "GNSS_TX", "GNSS_TX_3V0"), ("TP11", "GNSS_RX", "GNSS_RX_3V0"),
        ("TP12", "GNSS_PPS", "GNSS_PPS_3V0"), ("TP13", "GNSS_RST", "GNSS_RESET_N_1V8"),
        ("TP14", "BQ_INT", "CHARGER_INT_N"), ("TP15", "TYPEC_INT", "TYPEC_INT_N"),
        ("TP16", "GNSS_3V0", "GNSS_3V0"), ("TP17", "ANT_ON", "GNSS_ANT_ON"),
    ]
    for index, (ref, value, net) in enumerate(test_nets):
        row, col = divmod(index, 9)
        s.component("MOTO_GPS_TESTPOINT", ref, value, "TestPoint:TestPoint_Pad_D1.0mm", 1000 + col * 1200, 10800 + row * 400, {"1": net})

    s.component("MOTO_GPS_POWER_FLAG", "#FLG01", "PWR_FLAG", "", 11200, 10600, {"1": "USB_VBUS"})
    s.component("MOTO_GPS_POWER_FLAG", "#FLG02", "PWR_FLAG", "", 11200, 11000, {"1": "BAT"})
    s.component("MOTO_GPS_POWER_FLAG", "#FLG03", "PWR_FLAG", "", 11200, 11400, {"1": "GND"})
    validate_procurement_design(s)
    return s


def _stable_uuid(name: str) -> str:
    return str(uuid.uuid5(uuid.UUID("d9367564-c358-4fc2-8d3d-0eb95e0196a8"), name))


def _mil_to_mm(value: int) -> float:
    return round(value * 0.0254, 4)


def build_modern_schematic(design: Schematic) -> str:
    """Serialize the same design as a native s-expression KiCad schematic.

    ``kiutils`` is a build-time helper only.  The resulting file embeds every
    symbol it uses and opens without the Python package.
    """
    try:
        from kiutils.schematic import Schematic as KicadSchematic
        from kiutils.symbol import SymbolLib
        from kiutils.items.common import Effects, Font, PageSettings, Position, Property, TitleBlock
        from kiutils.items.schitems import (
            Connection,
            HierarchicalSheetInstance,
            LocalLabel,
            NoConnect,
            SchematicSymbol as KicadSchematicSymbol,
            SymbolProjectInstance,
            SymbolProjectPath,
            Text,
        )
    except ImportError as exc:  # pragma: no cover - build environment guard
        raise SystemExit("Install kiutils (build-time only) to emit .kicad_sch") from exc

    root_uuid = _stable_uuid("moto-gps-rev-a-root")
    project_name = BASENAME
    output = KicadSchematic.create_new()
    output.version = "20231120"
    output.generator = "eeschema"
    output.uuid = root_uuid
    output.paper = PageSettings(paperSize="A3")
    output.titleBlock = TitleBlock(
        title="MOTO GPS Rev A EVT electrical schematic",
        date="2026-09-03",
        revision="A-EVT-SCH-01",
        company="MOTO GPS",
        comments={
            1: "ENGINEERING / NOT FOR FABRICATION",
            2: "NO AUDIO, MICROPHONE, RTC OR SD",
            3: "FPC + CUSTOM LAND PATTERNS BLOCK RELEASE",
            4: "POWER COUPON REQUIRED",
        },
    )
    output.sheetInstances = [HierarchicalSheetInstance(instancePath="/", page="1")]

    lib = SymbolLib.from_file(str(ROOT / f"{BASENAME}.kicad_sym"), encoding="utf-8")
    output.libSymbols = lib.symbols
    for embedded_symbol in output.libSymbols:
        embedded_symbol.libId = f"MOTO_GPS:{embedded_symbol.entryName}"

    visible = Effects(font=Font(height=1.0, width=1.0))
    hidden = Effects(font=Font(height=1.0, width=1.0), hide=True)
    label_effects = Effects(font=Font(height=0.8, width=0.8))

    for x, y, text, size in design.note_records:
        output.texts.append(
            Text(
                text=text,
                position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0),
                effects=Effects(font=Font(height=max(0.8, size * 0.0254), width=max(0.8, size * 0.0254), bold=size >= 70)),
                uuid=_stable_uuid(f"note:{x}:{y}:{text}"),
            )
        )

    for record in design.component_records:
        symbol_name = str(record["symbol_name"])
        ref = str(record["ref"])
        value = str(record["value"])
        footprint = str(record["footprint"])
        x = int(record["x"])
        y = int(record["y"])
        nets = record["nets"]
        assert isinstance(nets, dict)
        procurement = record.get("procurement")
        assert procurement is None or isinstance(procurement, ProcurementGroup)
        model_symbol = SYMBOLS[symbol_name]
        pin_top = max((pin.y for pin in model_symbol.pins), default=0)
        reference_y = y - pin_top - 250
        value_y = y - pin_top - 150
        symbol_uuid = _stable_uuid(f"symbol:{ref}")
        instance = KicadSchematicSymbol(
            entryName=symbol_name,
            position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0),
            unit=1,
            # Test pads are electrical/PCB features, not purchasable assembly
            # line items.  Keeping them out of the BOM also makes schematic ↔
            # PCB production reconciliation unambiguous.
            inBom=not ref.startswith(("TP", "#")),
            onBoard=True,
            dnp=value.startswith("DNP"),
            uuid=symbol_uuid,
        )
        instance.libId = f"MOTO_GPS:{symbol_name}"
        instance.properties = [
            Property(key="Reference", value=ref, id=0, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(reference_y), angle=0), effects=visible),
            Property(key="Value", value=value, id=1, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(value_y), angle=0), effects=visible),
            Property(key="Footprint", value=footprint, id=2, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
            Property(key="Datasheet", value="~", id=3, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
            Property(key="Description", value="MOTO GPS Rev A pin-accurate engineering symbol", position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
        ]
        if procurement is not None:
            instance.properties.extend(
                [
                    Property(key="LCSC Part #", value=procurement.lcsc, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Manufacturer", value=procurement.manufacturer, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Manufacturer Part Number", value=procurement.mpn, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Procurement Status", value=procurement.status, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Procurement Source", value=procurement.source_url, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Procurement Checked", value=procurement.checked_on, position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                    Property(key="Procurement Notes", value=procurement.notes or "-", position=Position(X=_mil_to_mm(x), Y=_mil_to_mm(y), angle=0), effects=hidden),
                ]
            )
        instance.pins = {pin.number: _stable_uuid(f"pin:{ref}:{pin.number}") for pin in model_symbol.pins}
        instance.instances = [
            SymbolProjectInstance(
                name=project_name,
                paths=[SymbolProjectPath(sheetInstancePath=f"/{root_uuid}/{symbol_uuid}", reference=ref, unit=1)],
            )
        ]
        output.schematicSymbols.append(instance)

        for pin in model_symbol.pins:
            if pin.number not in nets:
                continue
            px = _mil_to_mm(x + pin.x)
            py = _mil_to_mm(y - pin.y)
            net = nets[pin.number]
            if net is None:
                output.noConnects.append(NoConnect(position=Position(X=px, Y=py), uuid=_stable_uuid(f"nc:{ref}:{pin.number}")))
            else:
                # 50 mil is long enough to produce a real wire object, while
                # avoiding coincident anchors between 800 mil-spaced passives.
                stub_x_mil = x + pin.x - 50 if pin.orientation == "R" else x + pin.x + 50
                stub_x = _mil_to_mm(stub_x_mil)
                output.graphicalItems.append(
                    Connection(
                        type="wire",
                        points=[Position(X=px, Y=py), Position(X=stub_x, Y=py)],
                        uuid=_stable_uuid(f"wire:{ref}:{pin.number}:{net}"),
                    )
                )
                output.labels.append(
                    LocalLabel(
                        text=str(net),
                        position=Position(X=stub_x, Y=py, angle=0),
                        effects=label_effects,
                        uuid=_stable_uuid(f"label:{ref}:{pin.number}:{net}"),
                    )
                )

    return output.to_sexpr()


def write_audit_exports(design: Schematic) -> None:
    """Write deterministic, review-friendly design and pin/net tables."""
    with (ROOT / "schematic-components.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(
            [
                "Reference", "Value", "Symbol", "Footprint", "DNP",
                "LCSC Part #", "Manufacturer", "Manufacturer Part Number",
                "Procurement Status", "Procurement Source", "Procurement Checked",
                "Procurement Notes",
            ]
        )
        for record in design.component_records:
            value = str(record["value"])
            procurement = record.get("procurement")
            assert procurement is None or isinstance(procurement, ProcurementGroup)
            writer.writerow(
                [
                    record["ref"],
                    value,
                    record["symbol_name"],
                    record["footprint"],
                    "YES" if value.startswith("DNP") else "NO",
                    procurement.lcsc if procurement else "",
                    procurement.manufacturer if procurement else "",
                    procurement.mpn if procurement else "",
                    procurement.status if procurement else "",
                    procurement.source_url if procurement else "",
                    procurement.checked_on if procurement else "",
                    procurement.notes if procurement else "",
                ]
            )

    with (ROOT / "pin-net-map.csv").open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(["Reference", "Pin", "PinName", "ElectricalType", "Net"])
        for record in design.component_records:
            symbol = SYMBOLS[str(record["symbol_name"])]
            nets = record["nets"]
            assert isinstance(nets, dict)
            for pin in symbol.pins:
                net = nets.get(pin.number, "UNSPECIFIED")
                writer.writerow(
                    [
                        record["ref"],
                        pin.number,
                        pin.name,
                        pin.electrical,
                        "NC" if net is None else net,
                    ]
                )


def main() -> None:
    design = build_schematic()
    write_audit_exports(design)
    (ROOT / f"{BASENAME}-cache.lib").write_text(make_cache_library(), encoding="utf-8")
    (ROOT / f"{BASENAME}.sch").write_text(design.finish(), encoding="utf-8")

    kicad_cli = shutil.which("kicad-cli") or "/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents/MacOS/kicad-cli"
    if not Path(kicad_cli).is_file():
        raise SystemExit("kicad-cli not found; cannot convert the project-local symbol library")
    symbol_output = ROOT / f"{BASENAME}.kicad_sym"
    symbol_output.unlink(missing_ok=True)
    subprocess.run(
        [kicad_cli, "sym", "upgrade", "-o", str(symbol_output), str(ROOT / f"{BASENAME}-cache.lib")],
        check=True,
    )
    (ROOT / f"{BASENAME}.kicad_sch").write_text(build_modern_schematic(design), encoding="utf-8")


if __name__ == "__main__":
    main()
