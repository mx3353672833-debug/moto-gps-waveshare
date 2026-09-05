#!/usr/bin/env python3
"""Compare the project J3 footprint with JLCPCB's EasyEDA source geometry.

The EasyEDA package uses 10 mil coordinate units.  Translation is intentionally
ignored; pad size and every pairwise centre offset are compared after converting
to millimetres.  Signal pads 1..31 and the two unnumbered mounting pads must all
match the manufacturer/JLC package geometry.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import statistics


EASYEDA_UNIT_MM = 0.254
PAD_RE = re.compile(
    r'^PAD~(?P<shape>[^~]+)~(?P<x>[-0-9.]+)~(?P<y>[-0-9.]+)~'
    r'(?P<w>[-0-9.]+)~(?P<h>[-0-9.]+)~[^~]*~~(?P<number>[^~]*)~'
)
KICAD_PAD_RE = re.compile(
    r'^\s*\(pad\s+"(?P<number>[^"]*)"\s+smd\s+\w+\s+'
    r'\(at\s+(?P<x>[-0-9.]+)\s+(?P<y>[-0-9.]+)(?:\s+[-0-9.]+)?\)\s+'
    r'\(size\s+(?P<w>[-0-9.]+)\s+(?P<h>[-0-9.]+)\)'
)


def easyeda_pads(path: Path) -> list[dict[str, float | str]]:
    package = json.loads(path.read_text(encoding="utf-8"))["result"]["packageDetail"]["dataStr"]
    pads: list[dict[str, float | str]] = []
    for shape in package["shape"]:
        match = PAD_RE.match(shape)
        if not match:
            continue
        pads.append(
            {
                "number": match.group("number"),
                "x": float(match.group("x")) * EASYEDA_UNIT_MM,
                "y": float(match.group("y")) * EASYEDA_UNIT_MM,
                "w": float(match.group("w")) * EASYEDA_UNIT_MM,
                "h": float(match.group("h")) * EASYEDA_UNIT_MM,
            }
        )
    return pads


def kicad_pads(path: Path) -> list[dict[str, float | str]]:
    pads: list[dict[str, float | str]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        match = KICAD_PAD_RE.match(line)
        if match:
            pads.append(
                {
                    "number": match.group("number"),
                    "x": float(match.group("x")),
                    "y": float(match.group("y")),
                    "w": float(match.group("w")),
                    "h": float(match.group("h")),
                }
            )
    return pads


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("easyeda_json", type=Path)
    parser.add_argument("kicad_footprint", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    expected = easyeda_pads(args.easyeda_json)
    actual = kicad_pads(args.kicad_footprint)
    lines = [
        "MOTO GPS J3 footprint geometry audit",
        f"EasyEDA source: {args.easyeda_json}",
        f"KiCad footprint: {args.kicad_footprint}",
        f"EasyEDA pads: {len(expected)}",
        f"KiCad pads: {len(actual)}",
    ]
    errors: list[str] = []
    if len(expected) != 33 or len(actual) != 33:
        errors.append("expected exactly 31 signal pads plus 2 mounting pads")

    expected_signals = {str(p["number"]): p for p in expected if str(p["number"]).isdigit() and int(str(p["number"])) <= 31}
    actual_signals = {str(p["number"]): p for p in actual if str(p["number"]).isdigit()}
    if set(expected_signals) != set(actual_signals):
        errors.append("signal pad-number sets differ")
    else:
        dx = statistics.median(float(expected_signals[n]["x"]) - float(actual_signals[n]["x"]) for n in expected_signals)
        dy = statistics.median(float(expected_signals[n]["y"]) - float(actual_signals[n]["y"]) for n in expected_signals)
        max_centre_error = 0.0
        max_size_error = 0.0
        for number in sorted(expected_signals, key=int):
            e = expected_signals[number]
            a = actual_signals[number]
            max_centre_error = max(
                max_centre_error,
                abs((float(e["x"]) - dx) - float(a["x"])),
                abs((float(e["y"]) - dy) - float(a["y"])),
            )
            max_size_error = max(
                max_size_error,
                abs(float(e["w"]) - float(a["w"])),
                abs(float(e["h"]) - float(a["h"])),
            )
        lines.extend(
            [
                f"Translation removed: dx={dx:.6f} mm, dy={dy:.6f} mm",
                f"Maximum signal-pad centre residual: {max_centre_error:.6f} mm",
                f"Maximum signal-pad size residual: {max_size_error:.6f} mm",
            ]
        )
        if max_centre_error > 0.002 or max_size_error > 0.002:
            errors.append("signal pad geometry exceeds 0.002 mm comparison tolerance")

    expected_mounts = sorted(
        (round(float(p["w"]), 4), round(float(p["h"]), 4))
        for p in expected
        if str(p["number"]) in {"32", "33"}
    )
    actual_mounts = sorted(
        (round(float(p["w"]), 4), round(float(p["h"]), 4))
        for p in actual
        if str(p["number"]) == ""
    )
    lines.append(f"EasyEDA mounting-pad sizes: {expected_mounts}")
    lines.append(f"KiCad unnumbered mounting-pad sizes: {actual_mounts}")
    if expected_mounts != actual_mounts:
        errors.append("mounting-pad sizes differ")

    lines.append("RESULT: FAIL" if errors else "RESULT: PASS")
    lines.extend(f"ERROR: {error}" for error in errors)
    report = "\n".join(lines) + "\n"
    print(report, end="")
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(report, encoding="utf-8")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
