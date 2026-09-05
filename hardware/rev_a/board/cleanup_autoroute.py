#!/usr/bin/env python3
"""Repair deterministic DRC defects left by the bounded autorouter.

The general router is useful for proving that the dense circular placement can
be connected, but it is not allowed to own the final geometry around the ESP32
exposed-pad via array, the USB-C connector, the magnetometer, or the charger.
This script replaces those local paths with reviewed geometry and keeps the
result reproducible from the imported Specctra session.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import os
from pathlib import Path
import shutil
import sys
import xml.etree.ElementTree as ET

import pcbnew


F_CU = pcbnew.F_Cu
IN2_CU = pcbnew.In2_Cu
B_CU = pcbnew.B_Cu
TOL_MM = 0.003
SCHEMATIC_NETLIST = Path(__file__).resolve().parent.parent / "schematic" / "moto-gps-rev-a.xml"
PROCUREMENT_FIELDS = (
    "LCSC Part #",
    "Manufacturer",
    "Manufacturer Part Number",
    "Procurement Status",
    "Procurement Source",
    "Procurement Checked",
    "Procurement Notes",
)


@dataclass
class TrackRecord:
    item: pcbnew.PCB_TRACK
    net_name: str
    layer: int | None
    start: tuple[float, float] | None
    end: tuple[float, float] | None
    via_at: tuple[float, float] | None
    locked: bool


def mm(value: float) -> int:
    return pcbnew.FromMM(value)


def point(x: float, y: float) -> pcbnew.VECTOR2I:
    return pcbnew.VECTOR2I(mm(x), mm(y))


def xy(vector: pcbnew.VECTOR2I) -> tuple[float, float]:
    return pcbnew.ToMM(vector.x), pcbnew.ToMM(vector.y)


def close(a: tuple[float, float], b: tuple[float, float]) -> bool:
    return abs(a[0] - b[0]) <= TOL_MM and abs(a[1] - b[1]) <= TOL_MM


def short_net_name(name: str) -> str:
    return name.lstrip("/")


def find_net(board: pcbnew.BOARD, name: str):
    short = short_net_name(name)
    return board.FindNet(name) or board.FindNet(short) or board.FindNet(f"/{short}")


def footprint(board: pcbnew.BOARD, reference: str) -> pcbnew.FOOTPRINT:
    matches = [fp for fp in board.GetFootprints() if fp.GetReference() == reference]
    if len(matches) != 1:
        raise RuntimeError(f"expected one footprint {reference}, found {len(matches)}")
    return matches[0]


def pad_position(board: pcbnew.BOARD, reference: str, number: str) -> tuple[float, float]:
    pads = [pad for pad in footprint(board, reference).Pads() if pad.GetNumber() == number]
    if len(pads) != 1:
        raise RuntimeError(f"expected one pad {reference}.{number}, found {len(pads)}")
    return xy(pads[0].GetPosition())


def segment_matches(
    record: TrackRecord,
    net_name: str,
    layer: int,
    a: tuple[float, float],
    b: tuple[float, float],
) -> bool:
    if record.via_at is not None:
        return False
    if short_net_name(record.net_name) != short_net_name(net_name) or record.layer != layer:
        return False
    assert record.start is not None and record.end is not None
    start, end = record.start, record.end
    return (close(start, a) and close(end, b)) or (close(start, b) and close(end, a))


def collect_track_records(board: pcbnew.BOARD) -> list[TrackRecord]:
    records: list[TrackRecord] = []
    for item in board.GetTracks():
        is_via = isinstance(item, pcbnew.PCB_VIA)
        records.append(
            TrackRecord(
                item=item,
                net_name=item.GetNetname(),
                layer=None if is_via else item.GetLayer(),
                start=None if is_via else xy(item.GetStart()),
                end=None if is_via else xy(item.GetEnd()),
                via_at=xy(item.GetPosition()) if is_via else None,
                locked=item.IsLocked(),
            )
        )
    return records


def remove_autorouter_defects(board: pcbnew.BOARD) -> None:
    records = collect_track_records(board)
    selected: dict[int, TrackRecord] = {}

    segment_specs = (
        ("CHARGER_PG_N", F_CU, (108.0403, 109.1070), (107.8010, 108.8677)),
        ("CHARGER_PG_N", IN2_CU, (107.8010, 108.8677), (107.8010, 105.5351)),
        ("GND", F_CU, (102.8973, 120.5121), (102.6328, 120.5121)),
        ("GND", IN2_CU, (101.7527, 120.5121), (102.6328, 120.5121)),
        ("3V3", F_CU, (99.2375, 79.45), (100.0998, 79.45)),
        ("3V3", F_CU, (100.0998, 79.45), (100.5998, 78.95)),
        ("3V3", F_CU, (100.5998, 78.95), (100.7625, 78.95)),
        ("GNSS_RF_IN", F_CU, (118.14, 98.30), (117.60, 98.30)),
    )
    for net_name, layer, a, b in segment_specs:
        matches = [record for record in records if segment_matches(record, net_name, layer, a, b)]
        if len(matches) > 1:
            raise RuntimeError(f"expected at most one {net_name} segment {a}->{b}, found {len(matches)}")
        if matches:
            selected[id(matches[0].item)] = matches[0]

    via_specs = (
        ("CHARGER_PG_N", (107.8010, 108.8677)),
        ("GND", (102.6328, 120.5121)),
    )
    for net_name, at in via_specs:
        matches = [
            record
            for record in records
            if short_net_name(record.net_name) == short_net_name(net_name)
            and record.via_at is not None
            and close(record.via_at, at)
        ]
        if len(matches) > 1:
            raise RuntimeError(f"expected at most one {net_name} via at {at}, found {len(matches)}")
        if matches:
            selected[id(matches[0].item)] = matches[0]

    # No board iteration occurs after the first removal: pcbnew invalidates
    # SWIG wrappers for removed BOARD_CONNECTED_ITEM objects.
    for record in selected.values():
        board.Remove(record.item)


def add_path(
    board: pcbnew.BOARD,
    net_name: str,
    coordinates: list[tuple[float, float]],
    width: float = 0.15,
    layer: int = F_CU,
) -> None:
    net = find_net(board, net_name)
    if net is None:
        raise KeyError(f"missing net {net_name}")
    for start, end in zip(coordinates, coordinates[1:]):
        if close(start, end):
            continue
        track = pcbnew.PCB_TRACK(board)
        track.SetStart(point(*start))
        track.SetEnd(point(*end))
        track.SetWidth(mm(width))
        track.SetLayer(layer)
        track.SetNet(net)
        board.Add(track)


def add_via(
    board: pcbnew.BOARD,
    net_name: str,
    at: tuple[float, float],
    diameter: float = 0.45,
    drill: float = 0.20,
) -> None:
    net = find_net(board, net_name)
    if net is None:
        raise KeyError(f"missing net {net_name}")
    via = pcbnew.PCB_VIA(board)
    via.SetPosition(point(*at))
    via.SetWidth(mm(diameter))
    via.SetDrill(mm(drill))
    via.SetLayerPair(F_CU, B_CU)
    via.SetNet(net)
    board.Add(via)


def repair_charger_pg(board: pcbnew.BOARD) -> None:
    # Preserve the autorouter's clean centreline and the 0.45/0.20 mm standard
    # via.  A dedicated 0.13 mm clearance rule covers this low-speed status net.
    status_via = (107.8010, 108.8677)
    add_path(board, "CHARGER_PG_N", [(108.0403, 109.1070), status_via])
    add_via(board, "CHARGER_PG_N", status_via)
    add_path(board, "CHARGER_PG_N", [status_via, (107.8010, 105.5351)], layer=IN2_CU)


def resize_esp32_thermal_vias(board: pcbnew.BOARD) -> None:
    # The official module thermal land is retained; only the plated heat-via
    # annular rings shrink from 0.60 to 0.45 mm around a 0.20 mm drill.  That is
    # a 0.125 mm annular ring and clears the four autorouted signal escapes.
    module = next(fp for fp in board.GetFootprints() if fp.GetReference() == "U1")
    count = 0
    for pad in module.Pads():
        if pad.GetNumber() == "41" and pad.GetDrillSize().x > 0:
            pad.SetSize(point(0.45, 0.45))
            count += 1
    if count != 12:
        raise RuntimeError(f"expected 12 ESP32 thermal vias, found {count}")


def clean_silkscreen(board: pcbnew.BOARD) -> None:
    # Edge connectors and sub-2 mm ICs do not have enough exposed board area
    # for a useful body outline.  Preserve their assembly outlines on F.Fab.
    suppress = {"J1", "J2", "U8", "U9", "U11"}
    for component in board.GetFootprints():
        if component.GetReference() not in suppress:
            continue
        for graphic in component.GraphicalItems():
            if graphic.GetLayer() == pcbnew.F_SilkS:
                graphic.SetLayer(pcbnew.F_Fab)

    for drawing in board.GetDrawings():
        if not isinstance(drawing, pcbnew.PCB_TEXT):
            continue
        if drawing.GetText() == "MOTO GPS REV A EVT":
            drawing.SetLayer(pcbnew.Dwgs_User)
        elif drawing.GetText() == "ENGINEERING - NOT FOR FABRICATION":
            drawing.SetMirrored(True)


def repair_usb_vbus(board: pcbnew.BOARD) -> None:
    # Keep the short autorouted VBUS branch.  Its only 0.134 mm clearance is
    # covered by the dedicated VBUS rule; a two-via detour crosses both CC1 and
    # the bottom-side 3V3 trunk and is electrically worse.
    moved_ground_via = (102.660, 120.5121)
    add_via(board, "GND", moved_ground_via)
    add_path(board, "GND", [(102.8973, 120.5121), moved_ground_via])
    add_path(board, "GND", [(101.7527, 120.5121), moved_ground_via], layer=IN2_CU)


def repair_magnetometer_power(board: pcbnew.BOARD) -> None:
    # Separate U6 pad 3 from the pad-8 ground escape.  Pads 9/10 already reach
    # the main 3V3 rail across the top of the package.
    local_via = (98.650, 79.700)
    rail_via = (97.390, 80.000)
    add_path(board, "3V3", [(99.2375, 79.45), local_via])
    add_via(board, "3V3", local_via)
    add_via(board, "3V3", rail_via)
    add_path(board, "3V3", [local_via, rail_via], layer=IN2_CU)


def repair_usb_shield(board: pcbnew.BOARD, shield_pad: tuple[float, float]) -> None:
    """Bind the connector shell to its RC/ESD shield net.

    The upstream XKB footprint calls all four stakes ``SH`` while the
    schematic symbol calls the shell ``S1``.  The project-local footprint uses
    S1 and declares the repeated stakes internally common, so only one short
    reviewed connection is required in the PCB ratnest.
    """
    shield = find_net(board, "USB_SHIELD")
    if shield is None:
        raise KeyError("missing net USB_SHIELD")
    # Escape C3 on F.Cu, change layer outside its solder fillet, then reach the
    # nearest shell stake on In2.  This path was checked against the USB CC1
    # trace above it and the SYS trunk on B.Cu.
    shield_via = (shield_pad[0] + 0.450, shield_pad[1])
    add_path(board, "USB_SHIELD", [shield_pad, shield_via], width=0.20)
    add_via(board, "USB_SHIELD", shield_via, diameter=0.50, drill=0.20)
    add_path(
        board,
        "USB_SHIELD",
        [shield_via, (106.400, 119.700), (104.320, 118.895)],
        width=0.20,
        layer=IN2_CU,
    )


def finalize_schematic_metadata(board: pcbnew.BOARD, netlist: Path = SCHEMATIC_NETLIST) -> None:
    """Write the UUID, footprint and root-net metadata required by parity DRC."""
    if not netlist.is_file():
        raise FileNotFoundError(f"schematic netlist not found: {netlist}")
    root = ET.parse(netlist).getroot()
    components: dict[str, dict[str, str]] = {}
    pin_net: dict[tuple[str, str], str] = {}
    for node in root.findall("./components/comp"):
        props = {
            child.attrib.get("name", ""): child.attrib.get("value", "")
            for child in node.findall("property")
        }
        components[node.attrib["ref"]] = {
            "value": node.findtext("value") or "",
            "footprint": node.findtext("footprint") or "",
            "tstamp": node.findtext("tstamps") or "",
            "dnp": "dnp" in props,
            "exclude_bom": "exclude_from_bom" in props,
            "sheetname": props.get("Sheetname", "根目录"),
            "sheetfile": props.get("Sheetfile", "moto-gps-rev-a.kicad_sch"),
            **{field: props.get(field, "") for field in PROCUREMENT_FIELDS},
        }
    for node in root.findall("./nets/net"):
        name = node.attrib["name"]
        for child in node.findall("node"):
            pin_net[(child.attrib["ref"], child.attrib["pin"])] = name

    net_objects: dict[str, pcbnew.NETINFO_ITEM] = {}
    for net in list(board.GetNetInfo().NetsByName().values()):
        name = net.GetNetname()
        if not name:
            continue
        if not name.startswith("/"):
            name = f"/{name}"
            net.SetNetname(name)
        net_objects[name] = net

    for fp in board.GetFootprints():
        ref = fp.GetReference()
        component = components.get(ref)
        if component is None:
            continue
        if ref == "J1":
            fp.SetDuplicatePadNumbersAreJumpers(True)
            for pad in fp.Pads():
                if pad.GetNumber() == "SH":
                    pad.SetNumber("S1")
        elif ref == "J4":
            # The stock U.FL footprint uses duplicate pad 2 for both ground
            # tabs; the pin-accurate symbol exposes them as pins 2 and 3.
            ground_index = 0
            for pad in fp.Pads():
                if pad.GetNumber() == "2":
                    ground_index += 1
                    if ground_index == 2:
                        pad.SetNumber("3")
        fp.SetFPIDAsString(component["footprint"])
        fp.SetValue(component["value"])
        fp.SetDNP(component["dnp"])
        fp.SetExcludedFromBOM(component["exclude_bom"])
        fp.SetExcludedFromPosFiles(component["exclude_bom"])
        if component["tstamp"]:
            fp.SetPath(pcbnew.KIID_PATH(f"/{component['tstamp']}"))
        fp.SetSheetname(component["sheetname"])
        fp.SetSheetfile(component["sheetfile"])
        fp.GetField("Description").SetText("MOTO GPS Rev A pin-accurate engineering symbol")
        # KiCad's schematic-parity DRC compares every custom symbol field.
        # Keep procurement metadata hidden on F.Fab so the board and schematic
        # remain exactly synchronized without adding visible manufacturing text.
        for field_name in PROCUREMENT_FIELDS:
            fp.SetField(field_name, component[field_name])
            field = fp.GetField(field_name)
            field.SetVisible(False)
            field.SetLayer(pcbnew.F_Fab)
        if ref.startswith("SW"):
            for pad in fp.Pads():
                pad.SetLocalZoneConnection(pcbnew.ZONE_CONNECTION_INHERITED)
        for pad in fp.Pads():
            net_name = pin_net.get((ref, pad.GetNumber()))
            if net_name and not net_name.startswith("unconnected-"):
                pad.SetNet(net_objects[net_name])
            else:
                pad.SetNetCode(0)


def copy_project_sidecars(source_board: Path, output_board: Path) -> None:
    for suffix in (".kicad_pro", ".kicad_dru", ".kicad_prl"):
        source = source_board.with_suffix(suffix)
        if suffix == ".kicad_dru":
            canonical_rules = Path(__file__).with_name("moto-gps-rev-a.kicad_dru")
            if canonical_rules.exists():
                source = canonical_rules
        if source.exists():
            shutil.copy2(source, output_board.with_suffix(suffix))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    board = pcbnew.LoadBoard(str(args.input))
    # Do this before any BOARD_CONNECTED_ITEM removal: pcbnew 10 invalidates
    # some SWIG net-container wrappers after a track is deleted.
    finalize_schematic_metadata(board)
    clean_silkscreen(board)
    resize_esp32_thermal_vias(board)
    # Capture this coordinate before remove_autorouter_defects invalidates
    # pcbnew's SWIG footprint collection wrappers.
    shield_pad = pad_position(board, "C3", "1")
    remove_autorouter_defects(board)
    repair_charger_pg(board)
    repair_usb_vbus(board)
    repair_magnetometer_power(board)
    repair_usb_shield(board, shield_pad)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.SaveBoard(str(args.output), board)
    copy_project_sidecars(args.input, args.output)
    print(f"cleaned board written to {args.output}")
    # pcbnew 10's SWIG bindings can dereference already-removed track wrappers
    # during Python interpreter teardown.  The board and sidecars are fully
    # flushed above, so bypassing teardown prevents a false macOS crash dialog.
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
