#!/usr/bin/env python3
"""Create the H0175 Rev-A1 EVT board from the immutable routed R4 board.

The R4 route remains immutable evidence. EVT A1 synchronizes schematic and
procurement metadata, updates the GNSS antenna TVS, follows the H0175 QSPI
reference drawing with panel VBAT at 3.3 V, and moves the enclosure screw axes
to PCD 55.40 mm. The 31-pin cable remains a physical continuity gate.
"""

from __future__ import annotations

import argparse
import math
import os
from pathlib import Path
import shutil
import sys

import pcbnew

import cleanup_autoroute as common
import generate_board as geometry


def replace_rev_a1_outline(board: pcbnew.BOARD) -> None:
    old_edges = [drawing for drawing in board.GetDrawings() if drawing.GetLayer() == pcbnew.Edge_Cuts]
    if len(old_edges) != 360:
        raise RuntimeError(f"expected 360 frozen R4 edge segments, found {len(old_edges)}")
    for drawing in old_edges:
        board.Remove(drawing)
    geometry.add_edge(board)

    expected_old = {
        (80.766695, 80.766695),
        (80.766695, 119.233304),
        (119.233304, 80.766695),
        (119.233304, 119.233304),
    }
    screw_circles: list[pcbnew.PCB_SHAPE] = []
    for drawing in board.GetDrawings():
        if not isinstance(drawing, pcbnew.PCB_SHAPE):
            continue
        if drawing.GetLayer() != pcbnew.Dwgs_User or drawing.GetShape() != pcbnew.SHAPE_T_CIRCLE:
            continue
        centre = common.xy(drawing.GetCenter())
        if any(common.close(centre, old) for old in expected_old):
            screw_circles.append(drawing)
    if len(screw_circles) != 4:
        raise RuntimeError(f"expected four R4 screw-axis circles, found {len(screw_circles)}")

    new_centres = []
    for angle in geometry.SCREW_ANGLES:
        radians = math.radians(angle)
        new_centres.append(
            (
                geometry.CX + geometry.SCREW_R * math.cos(radians),
                geometry.CY + geometry.SCREW_R * math.sin(radians),
            )
        )
    screw_circles.sort(
        key=lambda item: math.atan2(
            common.xy(item.GetCenter())[1] - geometry.CY,
            common.xy(item.GetCenter())[0] - geometry.CX,
        )
    )
    new_centres.sort(key=lambda item: math.atan2(item[1] - geometry.CY, item[0] - geometry.CX))
    for drawing, centre in zip(screw_circles, new_centres):
        drawing.SetCenter(common.point(*centre))
        drawing.SetEnd(common.point(centre[0] + 0.90, centre[1]))

    for drawing in board.GetDrawings():
        if isinstance(drawing, pcbnew.PCB_TEXT) and drawing.GetText() == "MOTO GPS REV A EVT":
            drawing.SetText("MOTO GPS REV A1 EVT")


def copy_schematic_sidecars(schematic: Path, output_board: Path) -> None:
    """Copy the generated A1 schematic beside the PCB without breaking libs."""
    destination_schematic = output_board.with_suffix(".kicad_sch")
    destination_symbols = output_board.with_suffix(".kicad_sym")
    shutil.copy2(schematic, destination_schematic)
    source_symbols = schematic.with_suffix(".kicad_sym")
    if source_symbols.is_file():
        shutil.copy2(source_symbols, destination_symbols)

    source_sym_table = schematic.parent / "sym-lib-table"
    if source_sym_table.is_file():
        table_text = source_sym_table.read_text(encoding="utf-8")
        table_text = table_text.replace(source_symbols.name, destination_symbols.name)
        (output_board.parent / "sym-lib-table").write_text(table_text, encoding="utf-8")



def copy_board_libraries(source_board: Path, output_board: Path) -> None:
    """Keep project-local footprints resolvable in the standalone EVT folder."""
    source_fp_table = source_board.parent / "fp-lib-table"
    if source_fp_table.is_file():
        shutil.copy2(source_fp_table, output_board.parent / "fp-lib-table")
    source_pretty = source_board.parent / "MOTO_GPS.pretty"
    if source_pretty.is_dir():
        shutil.copytree(source_pretty, output_board.parent / "MOTO_GPS.pretty", dirs_exist_ok=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path, help="immutable routed R4 board")
    parser.add_argument("component_source", type=Path, help="fresh A1 board; retained for CLI compatibility")
    parser.add_argument("output", type=Path)
    parser.add_argument(
        "--netlist",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "schematic" / "moto-gps-rev-a.xml",
    )
    parser.add_argument(
        "--schematic",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "schematic" / "moto-gps-rev-a.kicad_sch",
    )
    args = parser.parse_args()

    if args.input.resolve() == args.output.resolve():
        raise RuntimeError("refusing to overwrite the immutable routed R4 board")
    if not args.component_source.is_file():
        raise FileNotFoundError(args.component_source)

    board = pcbnew.LoadBoard(str(args.input))
    common.finalize_schematic_metadata(board, args.netlist)
    common.clean_silkscreen(board)
    replace_rev_a1_outline(board)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.ZONE_FILLER(board).Fill(board.Zones())
    pcbnew.SaveBoard(str(args.output), board)
    common.copy_project_sidecars(args.input, args.output)
    copy_board_libraries(args.input, args.output)
    copy_schematic_sidecars(args.schematic, args.output)
    print(f"H0175 Rev-A1 conservative-3V3 EVT board written to {args.output}")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
