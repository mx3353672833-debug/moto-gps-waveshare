#!/usr/bin/env python3
"""Apply deterministic, reviewed critical copper before general routing.

This script does not claim that the board is fabrication-ready.  It protects
the networks for which a generic maze router is specifically unsuitable:

* USB full-speed differential data;
* the LC76G RF match, bias and ESD chain;
* BQ25628E and TPS63070 switching/current loops.

The resulting board is the input to the bounded general-routing pass.  Every
item created here is locked so Specctra/Freerouting must route around it.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import shutil

import pcbnew


F_CU = pcbnew.F_Cu
IN2_CU = pcbnew.In2_Cu
B_CU = pcbnew.B_Cu


def mm(value: float) -> int:
    return pcbnew.FromMM(value)


def point(x: float, y: float) -> pcbnew.VECTOR2I:
    return pcbnew.VECTOR2I(mm(x), mm(y))


def find_net(board: pcbnew.BOARD, name: str):
    short = name.lstrip("/")
    return board.FindNet(name) or board.FindNet(short) or board.FindNet(f"/{short}")


def footprint(board: pcbnew.BOARD, reference: str) -> pcbnew.FOOTPRINT:
    for fp in board.GetFootprints():
        if fp.GetReference() == reference:
            return fp
    raise KeyError(f"missing footprint {reference}")


def pad_position(board: pcbnew.BOARD, reference: str, number: str) -> tuple[float, float]:
    pads = [pad for pad in footprint(board, reference).Pads() if pad.GetNumber() == str(number)]
    if not pads:
        raise KeyError(f"missing pad {reference}.{number}")
    # Repeated copper primitives with the same logical pad number are already
    # one net.  Their centroid is a stable connection target.
    x = sum(pcbnew.ToMM(pad.GetPosition().x) for pad in pads) / len(pads)
    y = sum(pcbnew.ToMM(pad.GetPosition().y) for pad in pads) / len(pads)
    return x, y


def add_path(
    board: pcbnew.BOARD,
    net_name: str,
    points: list[tuple[float, float]],
    width: float,
    layer: int = F_CU,
) -> None:
    net = find_net(board, net_name)
    if net is None:
        raise KeyError(f"missing net {net_name}")
    for start, end in zip(points, points[1:]):
        if start == end:
            continue
        track = pcbnew.PCB_TRACK(board)
        track.SetStart(point(*start))
        track.SetEnd(point(*end))
        track.SetWidth(mm(width))
        track.SetLayer(layer)
        track.SetNet(net)
        track.SetLocked(True)
        board.Add(track)


def add_via(
    board: pcbnew.BOARD,
    net_name: str,
    position: tuple[float, float],
    diameter: float = 0.50,
    drill: float = 0.25,
) -> None:
    net = find_net(board, net_name)
    if net is None:
        raise KeyError(f"missing net {net_name}")
    via = pcbnew.PCB_VIA(board)
    via.SetPosition(point(*position))
    via.SetWidth(mm(diameter))
    via.SetDrill(mm(drill))
    via.SetLayerPair(F_CU, B_CU)
    via.SetNet(net)
    via.SetLocked(True)
    board.Add(via)


def connect_pad_via(
    board: pcbnew.BOARD,
    net_name: str,
    reference: str,
    pad: str,
    via_at: tuple[float, float],
    width: float,
) -> tuple[float, float]:
    start = pad_position(board, reference, pad)
    add_path(board, net_name, [start, via_at], width, F_CU)
    add_via(board, net_name, via_at)
    return via_at


def route_usb(board: pcbnew.BOARD) -> None:
    # The four USB-C contacts alternate D-/D+/D-/D+ in one 0.5 mm-pitch row.
    # One mirrored pair therefore joins above the row and the other below it;
    # D- changes layer only after the two connector contacts have joined.  This
    # avoids the pad-to-pad crossover present in the first routing draft.
    dp_a = pad_position(board, "J1", "A6")
    dp_b = pad_position(board, "J1", "B6")
    dn_a = pad_position(board, "J1", "A7")
    dn_b = pad_position(board, "J1", "B7")
    esd_dp = pad_position(board, "U11", "1")
    esd_dn = pad_position(board, "U11", "3")
    r_dp_in = pad_position(board, "R13", "1")
    r_dn_in = pad_position(board, "R14", "1")

    add_path(board, "USB_D_P_CONN", [dp_a, (99.75, 117.45), (100.75, 117.45), dp_b], 0.18)
    add_path(board, "USB_D_N_CONN", [dn_b, (99.25, 119.25), (100.25, 119.25), dn_a], 0.18)

    dn_under = (100.25, 119.65)
    dn_escape = (103.50, 115.80)
    add_path(board, "USB_D_N_CONN", [dn_a, dn_under], 0.18)
    add_via(board, "USB_D_N_CONN", dn_under, 0.45, 0.20)
    add_path(board, "USB_D_N_CONN", [dn_under, (100.25, 116.25), dn_escape], 0.18, B_CU)
    add_via(board, "USB_D_N_CONN", dn_escape, 0.45, 0.20)
    add_path(board, "USB_D_N_CONN", [dn_escape, esd_dn], 0.18)

    add_path(board, "USB_D_P_CONN", [(100.75, 117.45), (103.60, 117.45), esd_dp], 0.18)
    add_path(board, "USB_D_N_CONN", [esd_dn, (105.80, 115.60), r_dn_in], 0.18)
    add_path(board, "USB_D_P_CONN", [esd_dp, (104.85, 117.45), (106.49, 117.45), r_dp_in], 0.18)

    # The long pair is on In2 directly adjacent to the uninterrupted In1 GND
    # reference plane.  The short F.Cu fan-outs only change layer once.
    dp_right = connect_pad_via(board, "USB_D_P_MCU", "R13", "2", (108.20, 117.00), 0.18)
    dn_right = connect_pad_via(board, "USB_D_N_MCU", "R14", "2", (108.20, 115.60), 0.18)
    dp_left = (79.60, 106.40)
    dn_left = (79.60, 105.13)
    add_via(board, "USB_D_P_MCU", dp_left, 0.45, 0.20)
    add_via(board, "USB_D_N_MCU", dn_left, 0.45, 0.20)
    add_path(board, "USB_D_P_MCU", [dp_right, (89.00, 114.10), (81.00, 106.40), dp_left], 0.18, IN2_CU)
    add_path(board, "USB_D_N_MCU", [dn_right, (89.00, 113.50), (81.00, 105.13), dn_left], 0.18, IN2_CU)
    add_path(board, "USB_D_P_MCU", [dp_left, pad_position(board, "U1", "14")], 0.18)
    add_path(board, "USB_D_N_MCU", [dn_left, pad_position(board, "U1", "13")], 0.18)


def route_gnss_rf(board: pcbnew.BOARD) -> None:
    # The match chain never changes layer.  Width is provisional until the
    # selected JLCPCB 1.0 mm four-layer stack is field-solver checked.
    rf_module = pad_position(board, "U10", "11")
    rf_series_in = pad_position(board, "R33", "2")
    rf_series_out = pad_position(board, "R33", "1")
    rf_shunt = pad_position(board, "C33", "1")
    dc_in = pad_position(board, "C31", "2")
    dc_out = pad_position(board, "C31", "1")
    match_shunt = pad_position(board, "C32", "1")
    antenna = pad_position(board, "J4", "1")
    antenna_esd = pad_position(board, "D2", "1")

    add_path(board, "GNSS_RF_IN", [rf_module, rf_series_in], 0.22)
    add_path(board, "GNSS_RF_IN", [(117.60, 98.30), (117.60, 99.20), rf_shunt], 0.22)
    add_path(board, "GNSS_RF_MATCH_IN", [rf_series_out, (119.16, 97.32), dc_in], 0.22)
    add_path(board, "GNSS_RF_MATCH_IN", [rf_series_out, (119.55, 99.22), match_shunt], 0.22)
    add_path(board, "GNSS_ANT_BIASED", [dc_out, (121.00, 98.28), (121.00, 95.00), antenna], 0.22)
    add_path(board, "GNSS_ANT_BIASED", [(121.00, 98.28), (121.00, 100.05), antenna_esd], 0.22)
    add_path(board, "GNSS_VDD_RF", [pad_position(board, "U10", "14"), pad_position(board, "L3", "1")], 0.30)
    add_path(board, "GNSS_ANT_BIASED", [pad_position(board, "L3", "2"), antenna], 0.22)

    # Shunt RF grounds get their own close return vias to the In1 plane.
    for reference, pad_number, via_at in (
        ("C33", "2", (119.13, 100.65)),
        ("C32", "2", (120.30, 101.05)),
        ("D2", "2", (123.00, 99.35)),
    ):
        connect_pad_via(board, "GND", reference, pad_number, via_at, 0.25)


def route_switching_cells(board: pcbnew.BOARD) -> None:
    # BQ25628E: TI routes SW and SYS on the second signal/power layer using
    # short via transitions because both pins are buried in the top-edge row.
    bq_sw = pad_position(board, "U2", "16")
    l1_sw = pad_position(board, "L1", "1")
    bq_sw_via = (110.175, 106.90)
    l1_sw_via = (112.85, 107.35)
    add_path(board, "BQ_SW", [bq_sw, bq_sw_via], 0.18)
    add_via(board, "BQ_SW", bq_sw_via, 0.45, 0.20)
    add_via(board, "BQ_SW", l1_sw_via, 0.45, 0.20)
    add_path(board, "BQ_SW", [bq_sw_via, l1_sw_via], 0.60, IN2_CU)
    add_path(board, "BQ_SW", [l1_sw_via, l1_sw], 0.45)

    bq_sys = pad_position(board, "U2", "9")
    l1_sys = pad_position(board, "L1", "2")
    sys_nodes = {
        "bq": bq_sys,
        "inductor": l1_sys,
        "c10": pad_position(board, "C10", "1"),
        "c11": pad_position(board, "C11", "1"),
        "c14": pad_position(board, "C14", "1"),
        "c15": pad_position(board, "C15", "1"),
        "u3_12": pad_position(board, "U3", "12"),
        "u3_13": pad_position(board, "U3", "13"),
    }
    bq_sys_via = (110.575, 111.05)
    l1_sys_via = (113.00, 110.75)
    tps_sys_via = (118.825, 106.55)
    add_path(board, "SYS", [sys_nodes["bq"], bq_sys_via], 0.18)
    add_via(board, "SYS", bq_sys_via, 0.45, 0.20)
    add_via(board, "SYS", l1_sys_via, 0.45, 0.20)
    add_path(board, "SYS", [l1_sys_via, sys_nodes["inductor"]], 0.45)
    add_path(board, "SYS", [sys_nodes["u3_13"], sys_nodes["u3_12"]], 0.30)
    add_path(board, "SYS", [(118.825, 107.50), tps_sys_via], 0.18)
    add_via(board, "SYS", tps_sys_via, 0.45, 0.20)
    add_path(board, "SYS", [bq_sys_via, l1_sys_via, (115.30, 110.00), tps_sys_via], 0.65, IN2_CU)

    # The input/output bulk capacitors have large pads; short F.Cu necks lead
    # to vias beside the pads to avoid solder-wicking via-in-pad fabrication.
    for ref, pad_no, via_at in (
        ("C11", "1", (108.50, 113.45)),
        ("C10", "1", (112.15, 113.45)),
        ("C14", "1", (117.00, 106.95)),
        ("C15", "1", (119.20, 106.95)),
    ):
        connect_pad_via(board, "SYS", ref, pad_no, via_at, 0.35)
        add_path(board, "SYS", [l1_sys_via, via_at], 0.65, IN2_CU)

    # Bootstrap capacitor is on B.Cu, directly behind the charger.
    btst_via = (108.80, 106.90)
    add_path(board, "BQ_BTST", [pad_position(board, "U2", "1"), btst_via], 0.18)
    add_via(board, "BQ_BTST", btst_via, 0.45, 0.20)
    add_path(board, "BQ_BTST", [btst_via, pad_position(board, "C7", "1")], 0.22, B_CU)
    add_path(board, "BQ_SW", [bq_sw_via, pad_position(board, "C7", "2")], 0.22, B_CU)

    # TPS63070 inductor loop remains entirely on F.Cu and is kept very short.
    l1_pin = pad_position(board, "U3", "11")
    l2_pin = pad_position(board, "U3", "9")
    add_path(board, "TPS63070_L1", [l1_pin, (120.35, 108.40)], 0.18)
    add_path(board, "TPS63070_L1", [(120.35, 108.40), pad_position(board, "L2", "1")], 0.55)
    add_path(board, "TPS63070_L2", [l2_pin, (120.35, 109.40)], 0.18)
    add_path(board, "TPS63070_L2", [(120.35, 109.40), pad_position(board, "L2", "2")], 0.55)

    # Converter output copper and local capacitors.
    vout_a = pad_position(board, "U3", "7")
    vout_b = pad_position(board, "U3", "8")
    c16 = pad_position(board, "C16", "1")
    c17 = pad_position(board, "C17", "1")
    add_path(board, "3V3", [vout_a, vout_b], 0.30)
    vout_via = (120.35, 110.75)
    add_path(board, "3V3", [vout_b, vout_via], 0.18)
    add_via(board, "3V3", vout_via, 0.45, 0.20)
    c16_via = (117.20, 115.00)
    c17_via = (119.50, 115.00)
    connect_pad_via(board, "3V3", "C16", "1", c16_via, 0.35)
    connect_pad_via(board, "3V3", "C17", "1", c17_via, 0.35)
    add_path(board, "3V3", [vout_via, (120.35, 113.60), c17_via, c16_via], 0.65, IN2_CU)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    board = pcbnew.LoadBoard(str(args.input))
    route_usb(board)
    route_gnss_rf(board)
    route_switching_cells(board)
    pcbnew.ZONE_FILLER(board).Fill(board.Zones())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.SaveBoard(str(args.output), board)
    for suffix in (".kicad_pro", ".kicad_dru"):
        source = args.input.with_suffix(suffix)
        destination = args.output.with_suffix(suffix)
        if source.is_file() and source.resolve() != destination.resolve():
            shutil.copyfile(source, destination)
    print(f"Reviewed critical-route board written to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
