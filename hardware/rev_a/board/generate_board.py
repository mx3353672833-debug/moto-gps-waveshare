#!/usr/bin/env python3
"""Deterministically build the MOTO GPS Rev A engineering PCB.

This script intentionally creates an *engineering* board, not a fabrication
release.  It imports the native KiCad schematic through a KiCad XML netlist,
loads real upstream footprints, prefers the audited project footprint library,
and falls back to clearly marked geometry-only footprints for the packages that
are still being audited.  The generated PCB is always annotated NOT FOR FAB.

Run with KiCad's bundled Python (see ``generate_board.sh``).  No third-party
Python modules are required.
"""

from __future__ import annotations

import argparse
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

import pcbnew


HERE = Path(__file__).resolve().parent
REV_A = HERE.parent
SCHEMATIC = REV_A / "schematic" / "moto-gps-rev-a.kicad_sch"
NETLIST = HERE / "build" / "moto-gps-rev-a.xml"
BOARD_OUT = HERE / "moto-gps-rev-a.kicad_pcb"
REPORT_OUT = HERE / "build" / "placement-report.txt"
AUDITED_LIB = HERE / "MOTO_GPS.pretty"
GENERATED_LIB = HERE / "generated_footprints.pretty"

KICAD_ROOT = Path(
    os.environ.get(
        "KICAD_10_ROOT",
        "/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents",
    )
)
KICAD_CLI = Path(os.environ.get("KICAD_CLI", str(KICAD_ROOT / "MacOS/kicad-cli")))
STD_FP_ROOT = KICAD_ROOT / "SharedSupport/footprints"

CX = 100.0
CY = 100.0
BOARD_R = 26.0
SCREW_R = 27.70
SCALLOP_R = 2.70
SCREW_ANGLES = (45.0, 135.0, 225.0, 315.0)

F_CU = pcbnew.F_Cu
IN1_CU = pcbnew.In1_Cu
IN2_CU = pcbnew.In2_Cu
B_CU = pcbnew.B_Cu


def mm(value: float) -> int:
    return pcbnew.FromMM(value)


def pt(x: float, y: float) -> pcbnew.VECTOR2I:
    return pcbnew.VECTOR2I(mm(x), mm(y))


def natural_ref(ref: str) -> tuple[str, int]:
    match = re.match(r"([^0-9]+)([0-9]+)$", ref)
    return (match.group(1), int(match.group(2))) if match else (ref, 0)


def canonical_net_name(name: str) -> str:
    """Return the root-sheet net spelling used by the native schematic."""
    return name if name.startswith("/") else f"/{name}"


def short_net_name(name: str) -> str:
    return name.lstrip("/")


def find_net(board: pcbnew.BOARD, name: str):
    """Resolve either a human label or its KiCad root-sheet spelling."""
    return board.FindNet(canonical_net_name(name)) or board.FindNet(name)


def export_netlist(schematic: Path, output: Path) -> None:
    if not KICAD_CLI.is_file():
        raise SystemExit(f"KiCad CLI not found: {KICAD_CLI}")
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [str(KICAD_CLI), "sch", "export", "netlist", "--format", "kicadxml", "-o", str(output), str(schematic)],
        check=True,
    )


def parse_netlist(path: Path):
    root = ET.parse(path).getroot()
    components = {}
    for node in root.findall("./components/comp"):
        ref = node.attrib["ref"]
        footprint = node.findtext("footprint") or ""
        if not footprint:
            continue
        properties = {child.attrib.get("name", ""): child.attrib.get("value", "")
                      for child in node.findall("property")}
        components[ref] = {
            "value": node.findtext("value") or "",
            "footprint": footprint,
            "tstamp": node.findtext("tstamps") or "",
            "dnp": "dnp" in properties,
            "exclude_bom": "exclude_from_bom" in properties,
            "sheetname": properties.get("Sheetname", "根目录"),
            "sheetfile": properties.get("Sheetfile", SCHEMATIC.name),
        }

    nets = {}
    pin_net = {}
    for node in root.findall("./nets/net"):
        name = node.attrib["name"]
        # KiCad's XML exporter synthesizes ``unconnected-(...)`` net names for
        # explicit no-connect pins.  They must remain no-net pads in the PCB;
        # importing them as real nets causes schematic-parity conflicts.
        if name.startswith("unconnected-"):
            continue
        nets[name] = int(node.attrib["code"])
        for child in node.findall("node"):
            pin_net[(child.attrib["ref"], child.attrib["pin"])] = name
    return components, nets, pin_net


def add_fp_line(fp, layer, x1, y1, x2, y2, width=0.12):
    shape = pcbnew.PCB_SHAPE(fp)
    shape.SetShape(pcbnew.SHAPE_T_SEGMENT)
    shape.SetStart(pt(x1, y1))
    shape.SetEnd(pt(x2, y2))
    shape.SetLayer(layer)
    shape.SetWidth(mm(width))
    fp.Add(shape)


def add_smd_pad(fp, number: str, x: float, y: float, w: float, h: float, *, roundrect=False):
    pad = pcbnew.PAD(fp)
    pad.SetNumber(number)
    pad.SetAttribute(pcbnew.PAD_ATTRIB_SMD)
    pad.SetShape(pcbnew.PAD_SHAPE_ROUNDRECT if roundrect else pcbnew.PAD_SHAPE_RECT)
    if roundrect:
        pad.SetRoundRectRadiusRatio(0.20)
    pad.SetSize(pt(w, h))
    pad.SetPosition(pt(x, y))
    pad.SetLayerSet(pad.SMDMask())
    fp.Add(pad)


def finish_placeholder(fp, name: str, body_w: float, body_h: float):
    fp.SetFPIDAsString(f"generated_footprints:{name}")
    fp.SetLibDescription("ENGINEERING GEOMETRY PLACEHOLDER - NOT FOR FABRICATION")
    margin = 0.25
    for layer, grow, width in ((pcbnew.F_Fab, 0.0, 0.10), (pcbnew.F_CrtYd, margin, 0.05)):
        x = body_w / 2 + grow
        y = body_h / 2 + grow
        add_fp_line(fp, layer, -x, -y, x, -y, width)
        add_fp_line(fp, layer, x, -y, x, y, width)
        add_fp_line(fp, layer, x, y, -x, y, width)
        add_fp_line(fp, layer, -x, y, -x, -y, width)
    # Asymmetric pin-one corner on fabrication layer.
    add_fp_line(fp, pcbnew.F_SilkS, -body_w / 2 - 0.35, -body_h / 2, -body_w / 2 - 0.35, -body_h / 2 + 0.55, 0.16)
    return fp


def perimeter_placeholder(name: str, body_w: float, body_h: float, pins: int):
    """Make a compact placeholder with numbered perimeter pads.

    This is used only until the audited project library exists.  It is visibly
    tagged in the PCB and the release tooling blocks it.
    """
    fp = pcbnew.FOOTPRINT(None)
    # Split pins approximately evenly across four edges, clockwise from upper-left.
    counts = [pins // 4] * 4
    for i in range(pins % 4):
        counts[i] += 1
    number = 1
    # Keep adjacent corner pads separated. These conservative dimensions are
    # only for placement/routing feasibility and are not a land-pattern claim.
    pad_len = 0.42
    pad_w = 0.18
    for edge, count in enumerate(counts):
        span = (body_h if edge in (0, 2) else body_w) - 1.1
        for index in range(count):
            offset = 0.0 if count == 1 else -span / 2 + index * span / (count - 1)
            if edge == 0:      # left, top to bottom
                x, y, w, h = -body_w / 2, offset, pad_len, pad_w
            elif edge == 1:    # bottom, left to right
                x, y, w, h = offset, body_h / 2, pad_w, pad_len
            elif edge == 2:    # right, bottom to top
                x, y, w, h = body_w / 2, -offset, pad_len, pad_w
            else:              # top, right to left
                x, y, w, h = -offset, -body_h / 2, pad_w, pad_len
            add_smd_pad(fp, str(number), x, y, w, h)
            number += 1
    return finish_placeholder(fp, name, body_w, body_h)


def lc76_placeholder():
    name = "Quectel_LC76GABMD_LCC18_LGA10"
    fp = pcbnew.FOOTPRINT(None)
    # 18 LCC perimeter pads, then ten bottom LGA pads. Geometry is an engineering
    # envelope only; the audited library replaces this before any release.
    perimeter = [
        (1, -5.05, -3.20), (2, -5.05, -1.92), (3, -5.05, -0.64),
        (4, -5.05, 0.64), (5, -5.05, 1.92), (6, -5.05, 3.20),
        (7, -3.25, 4.85), (8, -1.95, 4.85), (9, -0.65, 4.85),
        (10, 0.65, 4.85), (11, 1.95, 4.85), (12, 3.25, 4.85),
        (13, 5.05, 3.20), (14, 5.05, 1.92), (15, 5.05, 0.64),
        (16, 5.05, -0.64), (17, 5.05, -1.92), (18, 5.05, -3.20),
    ]
    for number, x, y in perimeter:
        add_smd_pad(fp, str(number), x, y, 0.75 if abs(x) > 4 else 0.45, 0.45 if abs(x) > 4 else 0.75)
    for number, (x, y) in enumerate(
        [(-3.25, -3.4), (-1.95, -3.4), (-0.65, -3.4), (0.65, -3.4), (1.95, -3.4),
         (3.25, -3.4), (-2.4, 0.0), (-0.8, 0.0), (0.8, 0.0), (2.4, 0.0)],
        start=19,
    ):
        add_smd_pad(fp, str(number), x, y, 0.75, 0.75)
    return finish_placeholder(fp, name, 10.1, 9.7)


def xunpu_fpc_placeholder():
    """Reserve the candidate XUNPU 31-contact connector envelope.

    Contact-side and mechanical dimensions remain blocked on the purchased
    display, so this must never pass the fabrication release gate.
    """
    name = "XUNPU_FPC-0.3FX-31PWBH10"
    fp = pcbnew.FOOTPRINT(None)
    for index in range(31):
        x = (index - 15) * 0.30
        add_smd_pad(fp, str(index + 1), x, -1.15, 0.15, 0.90, roundrect=True)
    add_smd_pad(fp, "MP", -5.25, 0.75, 1.20, 1.60)
    add_smd_pad(fp, "MP", 5.25, 0.75, 1.20, 1.60)
    return finish_placeholder(fp, name, 11.5, 3.2)


def placeholder_for(name: str):
    makers = {
        "BQ25628E_RYK0018A": lambda: perimeter_placeholder(name, 2.5, 3.0, 18),
        "TI_RNM0015A_VQFN-15": lambda: perimeter_placeholder(name, 2.5, 3.0, 15),
        "QMI8658C_LGA-14_2.5x3.0mm": lambda: perimeter_placeholder(name, 2.5, 3.0, 14),
        "Quectel_LC76GABMD_LCC18_LGA10": lc76_placeholder,
        "TI_DRT0003A_USON-3_1x1mm_P0.65mm": lambda: perimeter_placeholder(name, 1.0, 1.0, 3),
        "Texas_DRT0003A_SON-3_1x1mm_P0.65mm": lambda: perimeter_placeholder(name, 1.0, 1.0, 3),
        "XUNPU_FPC-0.3FX-31PWBH10": xunpu_fpc_placeholder,
    }
    if name not in makers:
        return None
    return makers[name]()


ENGINEERING_SUBSTITUTIONS = {
    # The schematic intentionally does not claim the display connector is frozen.
    # This upstream footprint is used only to reserve the known 31-contact envelope.
    "Connector_FFC-FPC:Hirose_FH26-31S-0.3SHW_2Rows-31Pins-1MP_P0.60mm_Horizontal":
        "Connector_FFC-FPC:Hirose_FH26-31S-0.3SHW_2Rows-31Pins-1MP_P0.60mm_Horizontal",
    # The generated schematic's generic name is not present in KiCad 10.  The
    # smaller Panasonic switch is a placement envelope, pending physical buttons.
    "Button_Switch_SMD:SW_SPST_TL3301AN": "Button_Switch_SMD:SW_SPST_EVQP7C",
    "Button_Switch_SMD:SW_Push_1P1T_NO_E-Switch_TL3301NxxxxxG": "Button_Switch_SMD:SW_SPST_EVQP7C",
}


def load_footprint(lib_id: str):
    original_lib_id = lib_id
    lib_id = ENGINEERING_SUBSTITUTIONS.get(lib_id, lib_id)
    substituted = lib_id != original_lib_id
    if ":" not in lib_id:
        return None, "invalid"
    library, name = lib_id.split(":", 1)
    if library == "MOTO_GPS":
        audited = pcbnew.FootprintLoad(str(AUDITED_LIB), name) if AUDITED_LIB.is_dir() else None
        if audited:
            return audited, "audited-project"
        fallback = placeholder_for(name)
        return fallback, "generated-placeholder"
    fp = pcbnew.FootprintLoad(str(STD_FP_ROOT / f"{library}.pretty"), name)
    if fp:
        return fp, "engineering-substitution" if substituted else "kicad-upstream"
    # Handle switch substitution emitted by different schematic generator versions.
    if library == "Button_Switch_SMD":
        fp = pcbnew.FootprintLoad(str(STD_FP_ROOT / "Button_Switch_SMD.pretty"), "SW_SPST_EVQP7C")
        if fp:
            return fp, "engineering-substitution"
    return None, "missing"


def hide_fields(fp) -> None:
    # This board is too dense for per-part reference text on silkscreen.  The
    # references remain present on fabrication/assembly outputs and in the BOM;
    # keeping them off F.Silk also prevents the placement drawing from hiding
    # actual pads.
    fp.Reference().SetVisible(False)
    fp.Reference().SetTextSize(pt(0.65, 0.65))
    fp.Reference().SetTextThickness(mm(0.11))
    fp.Value().SetVisible(False)


FIXED = {
    # ref: (x, y, rotation, side)
    "U1": (86.6, 98.3, 0, "F"),       # WROOM-1U, left
    "U10": (112.2, 95.0, 0, "F"),     # LC76G, right
    "U6": (100.0, 79.2, 0, "F"),      # magnetometer, 12 o'clock
    "U5": (100.3, 96.0, 90, "F"),     # IMU, mechanical centre region
    "J1": (100.0, 122.0, 0, "F"),     # USB, 6 o'clock; shell intentionally crosses outline
    "J3": (100.0, 111.5, 0, "F"),     # display FPC, 6 o'clock
    "J2": (84.0, 118.0, 0, "F"),      # battery connector, lower-left
    "J4": (123.1, 95.0, 0, "F"),      # GNSS U.FL, right edge
    "U2": (110.0, 109.1, 0, "F"),
    "U3": (118.3, 108.9, 0, "F"),
    "U4": (110.5, 116.2, 0, "F"),
    "U11": (105.2, 116.4, 0, "F"),
    "U7": (107.4, 79.8, 0, "F"),
    "U8": (112.5, 81.5, 0, "F"),
    "U9": (115.0, 84.5, 0, "F"),
    "Q1": (102.2, 88.3, 90, "F"),
    # Switching inductors and their high-frequency capacitors are fixed as a
    # layout cell instead of being left to the generic placement solver.
    "L1": (113.8, 109.1, 270, "F"),
    "L2": (122.0, 108.9, 270, "F"),
    # The bootstrap capacitor sits behind the charger but clear of the SW and
    # BTST escape vias.  Its pad order faces the two dedicated escape vias.
    "C7": (109.8, 106.4, 180, "B"),
    "C8": (106.2, 107.9, 180, "F"),
    "C9": (107.0, 112.8, 90, "F"),
    "C10": (113.1, 112.4, 0, "F"),
    "C11": (109.45, 112.4, 0, "F"),
    "C12": (107.5, 105.4, 270, "F"),
    "C13": (111.7, 105.7, 270, "F"),
    "C14": (117.0, 104.95, 90, "F"),
    "C15": (119.2, 104.95, 90, "F"),
    "C16": (117.2, 113.0, 90, "F"),
    "C17": (119.5, 113.0, 90, "F"),
    "C18": (114.9, 106.4, 90, "F"),
    "R24": (115.5, 111.8, 90, "F"),
    "R25": (115.5, 114.0, 270, "F"),
    "R26": (117.0, 102.3, 0, "F"),
    "R16": (102.0, 114.5, 0, "F"),
    "R17": (108.8, 103.0, 0, "F"),
    "R18": (113.2, 105.95, 90, "F"),
    "R19": (113.75, 104.3, 0, "F"),
    "R20": (113.2, 114.2, 0, "F"),
    "R21": (112.2, 120.2, 0, "F"),
    "R22": (112.0, 102.3, 0, "F"),
    "R23": (114.2, 102.3, 0, "F"),
    # USB ESD and series resistors form one short connector-side cell.
    "R13": (107.0, 117.0, 0, "F"),
    "R14": (107.0, 115.6, 0, "F"),
    # The stocked 900k option is an 0805.  Keep it clear of SW1's two NPTH
    # locating holes; this position is the routed R3 placement.
    "R15": (115.8, 116.9, 90, "F"),
    # GNSS RF matching/bias/ESD parts stay on F.Cu between the module and U.FL.
    "L3": (118.5, 95.0, 0, "F"),
    "R33": (118.65, 98.3, 180, "F"),
    "C31": (120.3, 97.8, 90, "F"),
    "C32": (120.3, 99.7, 270, "F"),
    "C33": (118.65, 99.8, 0, "F"),
    "D2": (121.7, 99.7, 90, "F"),
    "FB1": (98.2, 105.7, 90, "F"),
    "SW1": (113.8, 117.0, 0, "B"),
    "SW2": (83.5, 85.5, 0, "F"),
    "SW3": (89.0, 82.0, 0, "F"),
    "SW4": (123.0, 104.0, 90, "F"),
}


GROUP_ANCHOR = {
    "display": (101.5, 105.2),
    "usb": (106.0, 120.0),
    "charger": (111.0, 106.5),
    "buck": (117.0, 106.0),
    "mcu": (99.0, 91.0),
    "imu": (100.2, 92.5),
    "mag": (100.0, 82.0),
    "gnss-digital": (105.5, 87.0),
    "gnss-rf": (119.0, 96.0),
}


def group_for(ref: str) -> str:
    number = int(re.search(r"\d+", ref).group()) if re.search(r"\d+", ref) else 0
    if ref in {"R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R9", "C1", "C2", "FB1"}:
        return "display"
    if ref in {"U4", "U11", "D1", "R12", "R13", "R14", "R15", "R16", "C3", "C4", "C5", "C6"}:
        return "usb"
    if ref in {"U2", "J2", "L1", "C7", "C8", "C9", "C10", "C11", "C12", "C13", "R17", "R18", "R19", "R20", "R21", "R22", "R23"}:
        return "charger"
    if ref in {"U3", "L2", "C14", "C15", "C16", "C17", "C18", "R24", "R25", "R26", "R34"}:
        return "buck"
    if ref in {"R27", "R28", "R29", "R30", "R31", "C19", "C20", "C21", "SW1", "SW2", "SW3", "SW4"}:
        return "mcu"
    if ref in {"C22", "C23", "C24"}:
        return "imu"
    if ref in {"C25", "C26"}:
        return "mag"
    if ref in {"U7", "U8", "U9", "Q1", "C27", "C28", "C29", "C30", "C34", "C35", "C36", "R32"}:
        return "gnss-digital"
    if ref in {"J4", "D2", "L3", "C31", "C32", "C33", "R33"}:
        return "gnss-rf"
    return "mcu"


def board_radius_at(x: float, y: float) -> float:
    angle = math.atan2(y - CY, x - CX)
    result = BOARD_R
    for screw_deg in SCREW_ANGLES:
        delta = math.atan2(math.sin(angle - math.radians(screw_deg)), math.cos(angle - math.radians(screw_deg)))
        disc = SCALLOP_R * SCALLOP_R - SCREW_R * SCREW_R * math.sin(delta) ** 2
        if disc >= 0:
            hit = SCREW_R * math.cos(delta) - math.sqrt(disc)
            if 0 < hit < result:
                result = hit
    return result


def inside_board_box(box, *, allow_usb=False) -> bool:
    x1, y1, x2, y2 = box
    if allow_usb:
        # USB shell may extend through the 6-o'clock edge, but its X envelope must
        # remain inside the machined port width.
        return x1 >= 94.0 and x2 <= 106.0 and y1 >= 116.5 and y2 <= 127.0
    probes = [
        (x1, y1), (x2, y1), (x1, y2), (x2, y2),
        ((x1 + x2) / 2, y1), ((x1 + x2) / 2, y2),
        (x1, (y1 + y2) / 2), (x2, (y1 + y2) / 2),
    ]
    return all(math.hypot(x - CX, y - CY) <= board_radius_at(x, y) - 0.20 for x, y in probes)


def footprint_box(fp, margin=0.20):
    bbox = fp.GetBoundingBox(False, False)
    return (
        pcbnew.ToMM(bbox.GetLeft()) - margin,
        pcbnew.ToMM(bbox.GetTop()) - margin,
        pcbnew.ToMM(bbox.GetRight()) + margin,
        pcbnew.ToMM(bbox.GetBottom()) + margin,
    )


def overlaps(a, b) -> bool:
    return not (a[2] <= b[0] or a[0] >= b[2] or a[3] <= b[1] or a[1] >= b[3])


def candidate_points(anchor, step=0.55, radius=25.0):
    ax, ay = anchor
    # Increasing Manhattan rings preserves locality and remains deterministic.
    cells = int(radius / step)
    for ring in range(cells + 1):
        offsets = []
        for ix in range(-ring, ring + 1):
            iy = ring - abs(ix)
            offsets.append((ix, iy))
            if iy:
                offsets.append((ix, -iy))
        offsets.sort(key=lambda d: (d[0] * d[0] + d[1] * d[1], math.atan2(d[1], d[0])))
        for ix, iy in offsets:
            yield ax + ix * step, ay + iy * step


def add_edge(board):
    points = []
    for degree in range(360):
        angle = math.radians(degree)
        outer = board_radius_at(CX + math.cos(angle), CY + math.sin(angle))
        points.append((CX + outer * math.cos(angle), CY + outer * math.sin(angle)))
    for index in range(len(points)):
        a = points[index]
        b = points[(index + 1) % len(points)]
        shape = pcbnew.PCB_SHAPE(board)
        shape.SetShape(pcbnew.SHAPE_T_SEGMENT)
        shape.SetStart(pt(*a))
        shape.SetEnd(pt(*b))
        shape.SetLayer(pcbnew.Edge_Cuts)
        shape.SetWidth(mm(0.05))
        board.Add(shape)


def add_circle(board, layer, x, y, radius, width=0.15):
    shape = pcbnew.PCB_SHAPE(board)
    shape.SetShape(pcbnew.SHAPE_T_CIRCLE)
    shape.SetCenter(pt(x, y))
    shape.SetEnd(pt(x + radius, y))
    shape.SetLayer(layer)
    shape.SetWidth(mm(width))
    board.Add(shape)


def add_rect(board, layer, x1, y1, x2, y2, width=0.15):
    for a, b in [((x1, y1), (x2, y1)), ((x2, y1), (x2, y2)), ((x2, y2), (x1, y2)), ((x1, y2), (x1, y1))]:
        shape = pcbnew.PCB_SHAPE(board)
        shape.SetShape(pcbnew.SHAPE_T_SEGMENT)
        shape.SetStart(pt(*a))
        shape.SetEnd(pt(*b))
        shape.SetLayer(layer)
        shape.SetWidth(mm(width))
        board.Add(shape)


def add_text(board, text, x, y, layer, size=0.8, thickness=0.13):
    item = pcbnew.PCB_TEXT(board)
    item.SetText(text)
    item.SetPosition(pt(x, y))
    item.SetLayer(layer)
    item.SetTextSize(pt(size, size))
    item.SetTextThickness(mm(thickness))
    board.Add(item)
    return item


def add_zones(board, nets):
    def zone(layer, net_name, inset):
        resolved_name = canonical_net_name(net_name)
        if resolved_name not in nets:
            return
        z = pcbnew.ZONE(board)
        z.SetLayer(layer)
        z.SetNet(find_net(board, resolved_name))
        z.SetLocalClearance(mm(0.20))
        poly = z.Outline()
        outline = poly.NewOutline()
        for degree in range(0, 360, 3):
            angle = math.radians(degree)
            radius = min(BOARD_R - inset, board_radius_at(CX + math.cos(angle), CY + math.sin(angle)) - inset)
            poly.Append(mm(CX + radius * math.cos(angle)), mm(CY + radius * math.sin(angle)), outline)
        board.Add(z)

    # In1 is the primary continuous reference plane. B.Cu ground is for shielding
    # and low-speed/test access. Zones are filled by KiCad after generation.
    zone(IN1_CU, "GND", 0.35)
    zone(B_CU, "GND", 0.45)


def set_stackup(board):
    board.SetCopperLayerCount(4)
    settings = board.GetDesignSettings()
    settings.SetBoardThickness(mm(1.0))
    settings.m_MinClearance = mm(0.15)
    settings.m_TrackMinWidth = mm(0.15)
    settings.m_ViasMinSize = mm(0.45)
    settings.m_MinThroughDrill = mm(0.20)
    settings.m_HoleClearance = mm(0.15)
    settings.m_HoleToHoleMin = mm(0.20)
    settings.m_CopperEdgeClearance = mm(0.25)
    settings.m_MinSilkTextHeight = mm(0.50)
    settings.m_MinSilkTextThickness = mm(0.08)


def configure_netclasses(board):
    # The explicit rules file carries the release constraints. This keeps sane
    # defaults in the board itself for interactive work.
    # Fresh API-created boards do not expose their implicit Default netclass in
    # every KiCad build. The same hard limits are stored in design settings and
    # in ``moto-gps-rev-a.kicad_dru``; update the class when the map is present.
    try:
        default = board.GetNetClasses()["Default"]
    except (IndexError, KeyError):
        return
    default.SetClearance(mm(0.15))
    default.SetTrackWidth(mm(0.18))
    default.SetViaDiameter(mm(0.50))
    default.SetViaDrill(mm(0.25))


def add_test_via_stitching(board, gnd):
    # Conservative ground-stitch ring; avoid the magnetometer and USB mouth.
    for degree in range(0, 360, 12):
        if 246 <= degree <= 294:  # USB/FPC mouth
            continue
        angle = math.radians(degree)
        x = CX + 23.0 * math.cos(angle)
        y = CY + 23.0 * math.sin(angle)
        if 72 <= degree <= 108:  # magnetometer quiet zone at 12 o'clock
            continue
        via = pcbnew.PCB_VIA(board)
        via.SetPosition(pt(x, y))
        via.SetWidth(mm(0.50))
        via.SetDrill(mm(0.25))
        via.SetLayerPair(F_CU, B_CU)
        via.SetNet(gnd)
        board.Add(via)


def route_segment(board, net, a, b, width=0.20, layer=F_CU):
    track = pcbnew.PCB_TRACK(board)
    track.SetStart(a)
    track.SetEnd(b)
    track.SetLayer(layer)
    track.SetWidth(mm(width))
    track.SetNet(net)
    board.Add(track)


def route_safe_local_nets(board, pin_net):
    """Route only indisputable, short local two-pad networks.

    Power switch nodes, feedback, USB differential and GNSS RF are intentionally
    not auto-routed here. They require datasheet/stack-up review.
    """
    by_name = {}
    for fp in board.GetFootprints():
        for pad in fp.Pads():
            if pad.GetNetCode() > 0 and pad.GetNumber():
                by_name.setdefault(pad.GetNetname(), []).append(pad)
    excluded_tokens = ("SW", "BTST", "PMID", "FB", "USB_D_", "GNSS_RF", "ANT_", "CC1", "CC2")
    for name, pads in by_name.items():
        if len(pads) != 2 or any(token in name for token in excluded_tokens):
            continue
        if short_net_name(name) in {"GND", "3V3", "SYS", "BAT", "USB_VBUS", "GNSS_3V0"}:
            continue
        a, b = pads
        distance = pcbnew.ToMM((a.GetPosition() - b.GetPosition()).EuclideanNorm())
        if distance <= 2.8:
            route_segment(board, a.GetNet(), a.GetPosition(), b.GetPosition(), 0.18, F_CU)


def generate(args):
    export_netlist(args.schematic, args.netlist)
    components, net_codes, pin_net = parse_netlist(args.netlist)
    board = pcbnew.BOARD()
    set_stackup(board)

    net_objects = {}
    for name in sorted(net_codes):
        net = pcbnew.NETINFO_ITEM(board, name)
        board.Add(net)
        net_objects[name] = net

    footprints = {}
    sources = {}
    missing = []
    for ref in sorted(components, key=natural_ref):
        component = components[ref]
        fp, source = load_footprint(component["footprint"])
        if not fp:
            missing.append((ref, component["footprint"]))
            continue
        fp.SetReference(ref)
        fp.SetValue(component["value"])
        fp.SetDNP(component["dnp"])
        fp.SetExcludedFromBOM(component["exclude_bom"])
        # Bare-copper test pads are board features, not pick-and-place items.
        fp.SetExcludedFromPosFiles(component["exclude_bom"])
        fp.SetFPIDAsString(component["footprint"])
        if component["tstamp"]:
            fp.SetPath(pcbnew.KIID_PATH(f"/{component['tstamp']}"))
        fp.SetSheetname(component["sheetname"])
        fp.SetSheetfile(component["sheetfile"])
        fp.GetField("Description").SetText("MOTO GPS Rev A pin-accurate engineering symbol")
        hide_fields(fp)
        if ref in {"J1", "J2", "U8", "U9", "U11"}:
            for graphic in fp.GraphicalItems():
                if graphic.GetLayer() == pcbnew.F_SilkS:
                    graphic.SetLayer(pcbnew.F_Fab)
        # Bind all repeated physical copper shapes with the same pad number.
        for pad in fp.Pads():
            net_name = pin_net.get((ref, pad.GetNumber()))
            if net_name:
                pad.SetNet(net_objects[net_name])
            # The stock WROOM-1U land pattern uses generous 0.60 mm annular
            # rings on the exposed-pad heat vias.  Rev A uses JLC-compatible
            # 0.45/0.20 mm plated vias instead, retaining a 0.125 mm annular
            # ring while opening a legal signal-escape corridor under U1.
            if ref == "U1" and pad.GetNumber() == "41" and pad.GetDrillSize().x > 0:
                pad.SetSize(pt(0.45, 0.45))
        board.Add(fp)
        footprints[ref] = fp
        sources[ref] = source

    if missing:
        details = "\n".join(f"  {ref}: {lib}" for ref, lib in missing)
        raise SystemExit(f"Missing footprints; board not written:\n{details}")

    occupied = []
    # Place immutable mechanical/architectural anchors first.
    for ref, (x, y, angle, side) in FIXED.items():
        fp = footprints[ref]
        fp.SetPosition(pt(x, y))
        fp.SetOrientationDegrees(angle)
        if side == "B":
            fp.Flip(fp.GetPosition(), pcbnew.FLIP_DIRECTION_LEFT_RIGHT)
        occupied.append((ref, footprint_box(fp, 0.18), side))

    # Explicit exclusion envelopes used by the placer. They are also drawn on
    # User.Drawings for review. Supporting passives C25/C26 and the RF chain are
    # allowed to enter their respective electrical exception regions.
    exclusions = [
        ("MAG_QUIET", (94.0, 74.6, 106.0, 87.0)),
        ("GNSS_3MM", (104.0, 87.0, 120.3, 103.0)),
        # Copper-escape corridors are real placement constraints: allowing a
        # generic passive here would make the reviewed charger/USB routes
        # collide again every time the deterministic placer is regenerated.
        ("BQ_SW_ESCAPE", (109.45, 106.00, 110.80, 107.50)),
        ("USB_DN_ESCAPE", (103.15, 115.35, 103.85, 116.25)),
    ]

    def can_place(ref, box):
        if not inside_board_box(box):
            return False
        for other_ref, other, side in occupied:
            if side != "F":
                continue
            if overlaps(box, other):
                return False
        group = group_for(ref)
        for label, exclusion in exclusions:
            if label == "MAG_QUIET" and group == "mag":
                continue
            if label == "GNSS_3MM" and group == "gnss-rf":
                continue
            if overlaps(box, exclusion):
                return False
        return True

    # Test pads live on the back-side outer service ring, outside the battery
    # projection. They do not consume front-side component placement area.
    test_refs = sorted((r for r in footprints if r.startswith("TP")), key=natural_ref)
    # Keep TP2 clear of the side function switch/NPTH pair.
    test_angles = [0, 30, 75, 90, 105, 120, 135, 150, 165, 180, 195, 240, 255, 270, 285, 300, 315, 345]
    for index, ref in enumerate(test_refs):
        angle = math.radians(test_angles[index])
        x, y = CX + 22.0 * math.cos(angle), CY + 22.0 * math.sin(angle)
        fp = footprints[ref]
        fp.SetPosition(pt(x, y))
        fp.SetOrientationDegrees(math.degrees(angle) + 90)
        fp.Flip(fp.GetPosition(), pcbnew.FLIP_DIRECTION_LEFT_RIGHT)
        occupied.append((ref, footprint_box(fp, 0.05), "B"))

    unplaced = [r for r in sorted(footprints, key=natural_ref) if r not in FIXED and r not in test_refs]
    for ref in unplaced:
        fp = footprints[ref]
        anchor = GROUP_ANCHOR[group_for(ref)]
        placed = False
        for x, y in candidate_points(anchor):
            for angle in (0, 90):
                fp.SetPosition(pt(x, y))
                fp.SetOrientationDegrees(angle)
                box = footprint_box(fp, 0.05)
                if can_place(ref, box):
                    occupied.append((ref, box, "F"))
                    placed = True
                    break
            if placed:
                break
        if not placed:
            raise SystemExit(f"Placement solver could not place {ref} ({components[ref]['footprint']})")

    add_edge(board)
    # Review overlays: exact screw axes and system-level keep-outs.
    for angle in SCREW_ANGLES:
        a = math.radians(angle)
        add_circle(board, pcbnew.Dwgs_User, CX + SCREW_R * math.cos(a), CY + SCREW_R * math.sin(a), 0.90, 0.12)
    add_rect(board, pcbnew.Dwgs_User, 94.0, 74.6, 106.0, 87.0, 0.15)
    add_text(board, "MAG QUIET / NO HIGH CURRENT", 100.0, 85.7, pcbnew.Dwgs_User, 0.60, 0.10)
    add_rect(board, pcbnew.Dwgs_User, 104.0, 87.0, 120.3, 103.0, 0.15)
    add_text(board, "LC76G 3 mm REWORK ENVELOPE", 112.1, 102.4, pcbnew.Dwgs_User, 0.55, 0.09)
    add_text(board, "MOTO GPS REV A EVT", 86.5, 85.0, pcbnew.Dwgs_User, 0.75, 0.13)
    fabrication_notice = add_text(
        board,
        "ENGINEERING - NOT FOR FABRICATION",
        100.0,
        116.2,
        pcbnew.B_SilkS,
        0.72,
        0.12,
    )
    fabrication_notice.SetMirrored(True)
    add_text(board, "FRONT +Y / 12 O'CLOCK", 100.0, 76.0, pcbnew.F_SilkS, 0.58, 0.10)

    add_zones(board, net_objects)
    # Ground stitching and all copper routing are a later, reviewed operation.
    # Keeping the placement generator copper-free prevents a geometry preview
    # from being mistaken for validated power/RF/USB routing.
    configure_netclasses(board)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.SaveBoard(str(args.output), board)

    # Placement report is part of the engineering evidence, not a release claim.
    placeholders = sorted(ref for ref, source in sources.items() if source in {"generated-placeholder", "engineering-substitution"})
    rows = [
        "MOTO GPS Rev A placement report",
        "STATUS: ENGINEERING / NOT FOR FABRICATION",
        f"source schematic: {args.schematic}",
        f"components placed: {len(footprints)}",
        f"electrical nets: {len(net_objects)}",
        f"front-side components: {sum(1 for fp in footprints.values() if not fp.IsFlipped())}",
        f"back-side test pads: {sum(1 for fp in footprints.values() if fp.IsFlipped())}",
        f"generated/engineering substitutions: {len(placeholders)} ({', '.join(placeholders)})",
        f"board: four copper layers, 1.0 mm, nominal diameter 52.0 mm, four R2.70 scallops",
        "",
        "Fixed architectural placements (mm, KiCad coordinates):",
    ]
    for ref in sorted(FIXED, key=natural_ref):
        fp = footprints[ref]
        rows.append(f"  {ref:>4}  x={pcbnew.ToMM(fp.GetPosition().x):6.2f}  y={pcbnew.ToMM(fp.GetPosition().y):6.2f}  rot={fp.GetOrientationDegrees():6.1f}")
    rows += [
        "",
        "Open physical blockers:",
        "  - J3 31-pin display FPC contact side, pin 1, FPC thickness, connector height and exact MPN",
        "  - J1 exact USB-C MPN, shell Z datum, board-edge datum, plug and waterproof-port envelope",
        "  - custom RYK/RNM/QMI/LC76G footprints require audited library and second-person pin review",
        "  - GNSS/Wi-Fi antenna, coax bend radius and enclosure RF-window validation",
        "  - battery/protection/wire/swelling envelope and connector polarity",
        "  - BQ25628E/TPS63070 power coupon, thermal and load-step validation",
        "  - automatic local routing is not approval of USB, RF, switch-node or power-current layout",
    ]
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text("\n".join(rows) + "\n", encoding="utf-8")
    print("\n".join(rows[:9]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--schematic", type=Path, default=SCHEMATIC)
    parser.add_argument("--netlist", type=Path, default=NETLIST)
    parser.add_argument("--output", type=Path, default=BOARD_OUT)
    parser.add_argument("--report", type=Path, default=REPORT_OUT)
    args = parser.parse_args()
    generate(args)


if __name__ == "__main__":
    main()
