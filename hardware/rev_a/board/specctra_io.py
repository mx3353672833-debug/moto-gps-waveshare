#!/usr/bin/env python3
"""KiCad 10 DSN/SES bridge used by the bounded routing experiment.

The imported board remains an engineering artifact.  Freerouting may be used to
probe routing density after reviewed critical nets are locked, but it is never a
substitute for power-loop, USB impedance or GNSS RF review.
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path
import shutil

import pcbnew


def main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    export = sub.add_parser("export")
    export.add_argument("board", type=Path)
    export.add_argument("dsn", type=Path)
    import_ = sub.add_parser("import")
    import_.add_argument("board", type=Path)
    import_.add_argument("ses", type=Path)
    import_.add_argument("output", type=Path)
    args = parser.parse_args()

    board = pcbnew.LoadBoard(str(args.board))
    if args.command == "export":
        # Copper pours are restored from the canonical board after SES import.
        # Exporting them as conduction areas makes Freerouting 2.3.0 crash in
        # its search tree and also tempts it to treat the reference plane as a
        # general signal layer.
        for zone in list(board.Zones()):
            board.Remove(zone)
        args.dsn.parent.mkdir(parents=True, exist_ok=True)
        if not pcbnew.ExportSpecctraDSN(board, str(args.dsn)):
            raise SystemExit("KiCad failed to export Specctra DSN")
        dsn_text = args.dsn.read_text(encoding="utf-8")
        dsn_text = dsn_text.replace(
            "(layer In1.Cu\n      (type signal)",
            "(layer In1.Cu\n      (type power)",
            1,
        )
        # Match the committed 0.15/0.15 mm manufacturing floor instead of
        # KiCad's conservative 0.20/0.20 mm DSN defaults.  A 0.45/0.20 mm
        # through via is within the board rules and materially improves escape
        # from the 0.3/0.4/0.5 mm-pitch packages.
        dsn_text = dsn_text.replace("Via[0-3]_600:300_um", "Via[0-3]_450:200_um")
        for layer in ("F.Cu", "In1.Cu", "In2.Cu", "B.Cu"):
            dsn_text = dsn_text.replace(
                f"(shape (circle {layer} 600))",
                f"(shape (circle {layer} 450))",
            )
        dsn_text = dsn_text.replace("(width 200)", "(width 150)")
        dsn_text = dsn_text.replace("(clearance 200)", "(clearance 150)")
        # Freerouting's optimizer can spend minutes ripping up an already
        # fully-routed board and, in some runs, regress from zero unrouted
        # connections.  The deterministic review pass needs the completed
        # autorouter result; critical nets are reviewed/locked separately.
        autoroute_settings = """  (autoroute_settings
    (fanout on)
    (autoroute on)
    (postroute off)
    (vias on)
  )
"""
        head, closing = dsn_text.rsplit("\n)", 1)
        dsn_text = head + "\n" + autoroute_settings + ")" + closing
        args.dsn.write_text(dsn_text, encoding="utf-8")
        # KiCad 10.0.6's SWIG wrapper can double-destroy a removed ZONE during
        # interpreter shutdown on macOS.  The DSN is already flushed; bypass
        # SWIG finalizers so the deterministic export returns success.
        os._exit(0)

    if not pcbnew.ImportSpecctraSES(board, str(args.ses)):
        raise SystemExit("KiCad failed to import Specctra SES")

    # KiCad 10's SES importer occasionally emits sub-micron stitch fragments
    # at 0.1124 mm even though the DSN route rule is 0.15 mm.  They are routing
    # artefacts rather than intentional neck-downs, so normalize every copper
    # segment to the committed manufacturing floor.  Vias keep their imported
    # 0.45/0.20 mm geometry.
    min_track = pcbnew.FromMM(0.15)
    for item in board.GetTracks():
        if isinstance(item, pcbnew.PCB_VIA):
            continue
        if item.GetWidth() < min_track:
            item.SetWidth(min_track)

    # Restore and fill the reference/shield pours from the canonical board so
    # the review DRC sees real plane connections instead of false dangling-via
    # warnings from an unfilled import.
    pcbnew.ZONE_FILLER(board).Fill(board.Zones())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    pcbnew.SaveBoard(str(args.output), board)

    # Keep the review board paired with the same project-level netclass and
    # custom-rule files.  KiCad resolves those by basename beside the PCB; if
    # omitted, a build-directory candidate silently falls back to 0.20 mm and
    # produces hundreds of misleading clearance errors.
    for suffix in (".kicad_pro", ".kicad_dru"):
        source = args.board.with_suffix(suffix)
        destination = args.output.with_suffix(suffix)
        if source.is_file() and source.resolve() != destination.resolve():
            shutil.copyfile(source, destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
