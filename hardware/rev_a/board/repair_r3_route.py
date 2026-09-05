#!/usr/bin/env python3
"""Apply deterministic local repairs to the reviewed Rev-A R3 route.

The input must be the R3 route generated with R15 fixed at
``(115.8, 116.9), 90 deg, F.Cu``. Every touched item is matched by net,
layer and coordinates; the script aborts if the routing session differs.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import sys

import pcbnew

import cleanup_autoroute as common


F_CU = pcbnew.F_Cu


REMOVE_SEGMENTS = (
    # Freerouting duplicated the locked 0.22 mm GNSS feed with a 0.15 mm stub.
    ("GNSS_RF_IN", F_CU, (118.14, 98.30), (117.60, 98.30)),
    # Lift U6's 3V3 bridge by 0.02 mm to clear the adjacent ground fanout.
    ("3V3", F_CU, (99.2375, 79.45), (100.0878, 79.45)),
    ("3V3", F_CU, (100.0878, 79.45), (100.5878, 78.95)),
    ("3V3", F_CU, (100.5878, 78.95), (100.5998, 78.95)),
    ("3V3", F_CU, (100.5998, 78.95), (100.7625, 78.95)),
    # Replace the Q1 ground branch with a lower dogleg that clears pad 3.
    ("GND", F_CU, (103.15, 89.2375), (102.8318, 89.2375)),
    ("GND", F_CU, (102.8318, 89.2375), (101.9565, 88.3622)),
    ("GND", F_CU, (101.9565, 88.3622), (101.8685, 88.2742)),
    ("GND", F_CU, (101.8685, 88.2742), (101.8427, 88.2742)),
    # Collapse a near-zero-length LCD_RESET_N detour that crosses 3V3.
    ("LCD_RESET_N", F_CU, (97.0363, 94.1146), (97.7525, 94.1146)),
    ("LCD_RESET_N", F_CU, (97.7525, 94.1146), (97.8543, 94.2164)),
    ("LCD_RESET_N", F_CU, (97.8543, 94.2164), (97.8543, 94.2165)),
    ("LCD_RESET_N", F_CU, (97.8543, 94.2165), (97.8234, 94.2474)),
    ("LCD_RESET_N", F_CU, (97.8234, 94.2474), (97.0126, 94.2474)),
)


def select_reviewed_segments(board: pcbnew.BOARD) -> list[common.TrackRecord]:
    records = common.collect_track_records(board)
    selected: list[common.TrackRecord] = []
    for net_name, layer, start, end in REMOVE_SEGMENTS:
        matches = [
            record
            for record in records
            if common.segment_matches(record, net_name, layer, start, end)
        ]
        if len(matches) != 1:
            raise RuntimeError(
                f"expected one {net_name} segment {start}->{end}, found {len(matches)}"
            )
        selected.append(matches[0])
    return selected


def move_via_and_connected_ends(
    board: pcbnew.BOARD,
    net_name: str,
    old: tuple[float, float],
    new: tuple[float, float],
    expected_segments: int,
) -> None:
    short_name = common.short_net_name(net_name)
    via_matches = []
    segment_ends: list[tuple[pcbnew.PCB_TRACK, str]] = []
    for item in board.GetTracks():
        if common.short_net_name(item.GetNetname()) != short_name:
            continue
        if isinstance(item, pcbnew.PCB_VIA):
            if common.close(common.xy(item.GetPosition()), old):
                via_matches.append(item)
            continue
        if common.close(common.xy(item.GetStart()), old):
            segment_ends.append((item, "start"))
        if common.close(common.xy(item.GetEnd()), old):
            segment_ends.append((item, "end"))
    if len(via_matches) != 1:
        raise RuntimeError(f"expected one {net_name} via at {old}, found {len(via_matches)}")
    if len(segment_ends) != expected_segments:
        raise RuntimeError(
            f"expected {expected_segments} {net_name} segment ends at {old}, "
            f"found {len(segment_ends)}"
        )
    via_matches[0].SetPosition(common.point(*new))
    for segment, which in segment_ends:
        if which == "start":
            segment.SetStart(common.point(*new))
        else:
            segment.SetEnd(common.point(*new))


def add_reviewed_paths(board: pcbnew.BOARD) -> None:
    common.add_path(
        board,
        "3V3",
        [
            (99.2375, 79.45),
            (100.0878, 79.43),
            (100.5878, 78.93),
            (100.7625, 78.95),
        ],
    )
    common.add_path(
        board,
        "GND",
        [
            (103.15, 89.2375),
            (102.80, 89.2375),
            (102.10, 88.55),
            (101.84, 88.55),
            (101.8427, 88.2742),
        ],
    )
    common.add_path(
        board,
        "LCD_RESET_N",
        [(97.0363, 94.1146), (97.0126, 94.2474)],
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    board = pcbnew.LoadBoard(str(args.input))
    common.finalize_schematic_metadata(board)
    common.clean_silkscreen(board)
    common.resize_esp32_thermal_vias(board)
    selected = select_reviewed_segments(board)

    # Shift two vias by only a few tens of microns and retain all connected
    # segment endpoints. This removes marginal clearances without changing
    # net topology or adding a fabrication-rule exception.
    move_via_and_connected_ends(
        board,
        "USB_VBUS",
        (110.2929, 120.4017),
        (110.2929, 120.3600),
        expected_segments=2,
    )
    move_via_and_connected_ends(
        board,
        "FUNC_KEY_N",
        (101.8632, 94.7729),
        (101.8632, 94.7300),
        expected_segments=3,
    )
    add_reviewed_paths(board)

    # pcbnew invalidates SWIG wrappers after BOARD_CONNECTED_ITEM removal, so
    # removal is the final board operation before serialization.
    for record in selected:
        board.Remove(record.item)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.SaveBoard(str(args.output), board)
    common.copy_project_sidecars(args.input, args.output)
    print(f"R3 repaired board written to {args.output}")
    sys.stdout.flush()
    os._exit(0)


if __name__ == "__main__":
    main()
