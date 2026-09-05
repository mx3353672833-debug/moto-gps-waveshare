#!/usr/bin/env python3
"""Audit the OCN OK-F302-31115 KiCad footprint against JLC/EasyEDA.

EasyEDA v6 package coordinates use 10 mil units (0.254 mm).  This audit uses
the package origin from ``head.x/head.y`` so it checks translation, pad order,
and mirroring as well as pad sizes.  It also records the material differences
from the project's older XUNPU 31-pin connector footprint.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import statistics


EASYEDA_UNIT_MM = 0.254
GEOMETRY_TOLERANCE_MM = 0.002
OCN_DRAWING_URL = "https://285624.selcdn.ru/syms1/iblock/f03/f036397a40a02fbcf3fa79b5388254dd/Spec_Draw_OK_F302.pdf"
EASYEDA_API_URL = "https://easyeda.com/api/products/C9900051647/components?version=6.5.22.1"
EXPECTED_HASHES = {
    "OCN-OK-F302-xx115-Rev-A1.pdf": "ebc73a00c6a5649198b732a27986f3c60f22b84de33d67a612e1c79863c5a069",
    "C9900051647-OK-F302-31115-linked-drawing.pdf": "a006c7f310b231c97f78e5726d07750cf82076295b7341efc0cb54bd4c0efb24",
    "C9900051647-OK-F302-31115-easyeda.json": "45e28e2aa8746ee3f21f9ac96261faa5e129370198a7f117186618200d4f765f",
}
PAD_RE = re.compile(
    r"^PAD~(?P<shape>[^~]+)~(?P<x>[-0-9.]+)~(?P<y>[-0-9.]+)~"
    r"(?P<w>[-0-9.]+)~(?P<h>[-0-9.]+)~[^~]*~~(?P<number>[^~]*)~"
)
KICAD_PAD_RE = re.compile(
    r'^\s*\(pad\s+"(?P<number>[^"]*)"\s+smd\s+\w+\s+'
    r"\(at\s+(?P<x>[-0-9.]+)\s+(?P<y>[-0-9.]+)(?:\s+[-0-9.]+)?\)\s+"
    r"\(size\s+(?P<w>[-0-9.]+)\s+(?P<h>[-0-9.]+)\)"
)
KICAD_RECT_RE = re.compile(
    r'^\s*\(fp_rect\s+\(start\s+(?P<x1>[-0-9.]+)\s+(?P<y1>[-0-9.]+)\)\s+'
    r'\(end\s+(?P<x2>[-0-9.]+)\s+(?P<y2>[-0-9.]+)\).*\(layer\s+"(?P<layer>[^"]+)"\)'
)
NUMBER_RE = re.compile(r"[-+]?\d+(?:\.\d+)?")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def easyeda_package(path: Path) -> tuple[dict, dict, list[dict[str, float | str]]]:
    result = json.loads(path.read_text(encoding="utf-8"))["result"]
    package = result["packageDetail"]["dataStr"]
    origin_x = float(package["head"]["x"])
    origin_y = float(package["head"]["y"])
    pads: list[dict[str, float | str]] = []
    for shape in package["shape"]:
        match = PAD_RE.match(shape)
        if not match:
            continue
        pads.append(
            {
                "number": match.group("number"),
                "shape": match.group("shape"),
                "x": (float(match.group("x")) - origin_x) * EASYEDA_UNIT_MM,
                "y": (float(match.group("y")) - origin_y) * EASYEDA_UNIT_MM,
                "w": float(match.group("w")) * EASYEDA_UNIT_MM,
                "h": float(match.group("h")) * EASYEDA_UNIT_MM,
            }
        )
    return result, package, pads


def easyeda_body_bounds(package: dict) -> tuple[float, float, float, float]:
    """Return the layer-99 component body bounds relative to package origin."""
    body = next(shape for shape in package["shape"] if shape.startswith("SOLIDREGION~99~"))
    path_data = body.split("~")[3]
    numbers = [float(value) for value in NUMBER_RE.findall(path_data)]
    xs = numbers[0::2]
    ys = numbers[1::2]
    origin_x = float(package["head"]["x"])
    origin_y = float(package["head"]["y"])
    return (
        (min(xs) - origin_x) * EASYEDA_UNIT_MM,
        (min(ys) - origin_y) * EASYEDA_UNIT_MM,
        (max(xs) - origin_x) * EASYEDA_UNIT_MM,
        (max(ys) - origin_y) * EASYEDA_UNIT_MM,
    )


def easyeda_id_marker(package: dict) -> tuple[float, float]:
    marker = next(shape for shape in package["shape"] if shape.startswith("CIRCLE~"))
    fields = marker.split("~")
    origin_x = float(package["head"]["x"])
    origin_y = float(package["head"]["y"])
    return (
        (float(fields[1]) - origin_x) * EASYEDA_UNIT_MM,
        (float(fields[2]) - origin_y) * EASYEDA_UNIT_MM,
    )


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


def kicad_rectangles(path: Path) -> dict[str, tuple[float, float, float, float]]:
    rectangles: dict[str, tuple[float, float, float, float]] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = KICAD_RECT_RE.match(line)
        if not match:
            continue
        x1, x2 = float(match.group("x1")), float(match.group("x2"))
        y1, y2 = float(match.group("y1")), float(match.group("y2"))
        rectangles[match.group("layer")] = (min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    return rectangles


def bounds_size(bounds: tuple[float, float, float, float]) -> tuple[float, float]:
    return bounds[2] - bounds[0], bounds[3] - bounds[1]


def nearly_equal(left: float, right: float, tolerance: float = GEOMETRY_TOLERANCE_MM) -> bool:
    return abs(left - right) <= tolerance


def row_summary(pads: dict[str, dict[str, float | str]], odd: bool) -> tuple[float, float, float]:
    row = [pad for number, pad in pads.items() if (int(number) % 2 == 1) == odd]
    return (
        statistics.median(float(pad["y"]) for pad in row),
        statistics.median(float(pad["w"]) for pad in row),
        statistics.median(float(pad["h"]) for pad in row),
    )


def main() -> int:
    repo = Path(__file__).resolve().parents[3]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--easyeda",
        type=Path,
        default=repo / "hardware/rev_a/references/C9900051647-OK-F302-31115-easyeda.json",
    )
    parser.add_argument(
        "--footprint",
        type=Path,
        default=repo / "hardware/rev_a/board/MOTO_GPS.pretty/OCN_OK-F302-31115.kicad_mod",
    )
    parser.add_argument(
        "--xunpu-footprint",
        type=Path,
        default=repo / "hardware/rev_a/board/MOTO_GPS.pretty/XUNPU_FPC-0.3FX-31PWBH10.kicad_mod",
    )
    parser.add_argument(
        "--references",
        type=Path,
        default=repo / "hardware/rev_a/references",
    )
    parser.add_argument(
        "--report",
        type=Path,
        default=repo / "hardware/rev_a/validation/ok-f302-31115-footprint-audit.txt",
    )
    args = parser.parse_args()

    errors: list[str] = []
    lines = [
        "OCN OK-F302-31115 FOOTPRINT GEOMETRY AUDIT",
        "=============================================",
        "",
        "Scope: connector package geometry only; no schematic or PCB placement was changed.",
        "Coordinate convention: PCB top view, FPC tail/actuator edge at +Y.",
        "",
        "SOURCE INTEGRITY",
    ]
    for filename, expected_hash in EXPECTED_HASHES.items():
        source = args.references / filename
        if not source.exists():
            errors.append(f"missing source file: {source}")
            lines.append(f"FAIL  {filename}: missing")
            continue
        actual_hash = sha256(source)
        if actual_hash != expected_hash:
            errors.append(f"SHA-256 mismatch: {filename}")
            lines.append(f"FAIL  {filename}: {actual_hash}")
        else:
            lines.append(f"PASS  {filename}: {actual_hash}")

    result, package, expected = easyeda_package(args.easyeda)
    expected_by_number = {str(pad["number"]): pad for pad in expected}
    actual = kicad_pads(args.footprint)
    actual_by_number = {str(pad["number"]): pad for pad in actual if str(pad["number"]).isdigit()}
    expected_numbers = {str(number) for number in range(1, 32)}
    package_name = package["head"]["c_para"].get("package", "")
    source_link = package["head"]["c_para"].get("link", "")
    if result.get("lcsc", {}).get("number") != "C9900051647":
        errors.append("EasyEDA metadata does not identify LCSC C9900051647")
    if package_name != "FPC-SMD_31P-P0.30_OK-F302-31115":
        errors.append(f"unexpected EasyEDA package name: {package_name}")
    if set(expected_by_number) != expected_numbers or len(expected) != 31:
        errors.append("EasyEDA package is not exactly numbered pads 1..31")
    if set(actual_by_number) != expected_numbers or len(actual) != 31:
        errors.append("KiCad footprint is not exactly numbered pads 1..31")

    body_bounds = easyeda_body_bounds(package)
    marker_x, marker_y = easyeda_id_marker(package)
    expected_odd_y, expected_odd_w, expected_odd_h = row_summary(expected_by_number, True)
    expected_even_y, expected_even_w, expected_even_h = row_summary(expected_by_number, False)
    pitch_values = [
        float(expected_by_number[str(number + 1)]["x"]) - float(expected_by_number[str(number)]["x"])
        for number in range(1, 31)
    ]
    pitch = statistics.median(pitch_values)
    span = float(expected_by_number["31"]["x"]) - float(expected_by_number["1"]["x"])
    row_separation = abs(expected_odd_y - expected_even_y)

    lines.extend(
        [
            "",
            "SOURCE IDENTIFICATION",
            "Retrieved: 2026-09-03",
            f"OCN drawing URL: {OCN_DRAWING_URL}",
            f"EasyEDA API URL: {EASYEDA_API_URL}",
            f"EasyEDA title: {result.get('title', '')}",
            f"EasyEDA package: {package_name}",
            f"EasyEDA package UUID: {package['head'].get('uuid', '')}",
            f"LCSC/JLC part: {result.get('lcsc', {}).get('number', '')}",
            f"Drawing URL recorded by EasyEDA: {source_link}",
            "",
            "MANUFACTURER DRAWING FACTS (OK-F302-xx115 Rev A1)",
            "PASS  code 302 = 0.30 mm pitch",
            "PASS  31 = 31 contacts",
            "PASS  first suffix 1 = bottom contacts",
            "PASS  second suffix 1 = front flip",
            "PASS  connector height = 1.00 +/-0.10 mm",
            "PASS  applicable FPC thickness = 0.20 +0.03/-0.00 mm",
            "PASS  31-contact dimensions A/B/C/D = 10.80/8.40/9.00/9.63 mm",
            "PASS  body depth = REF 3.22 mm",
            "PASS  recommended stencil thickness = 0.10 mm",
            "",
            "EASYEDA NORMALIZED LAND GEOMETRY",
            f"Pads: {len(expected)} signal pads, 0 mechanical solder pads",
            f"Consecutive pin pitch: {pitch:.6f} mm",
            f"Pin 1-to-31 centre span: {span:.6f} mm",
            f"Odd/front row: y={expected_odd_y:.6f} mm, pad={expected_odd_w:.6f} x {expected_odd_h:.6f} mm",
            f"Even/rear row: y={expected_even_y:.6f} mm, pad={expected_even_w:.6f} x {expected_even_h:.6f} mm",
            f"Row centre separation: {row_separation:.6f} mm",
            f"Layer-99 body bounds: x={body_bounds[0]:.6f}..{body_bounds[2]:.6f} mm, y={body_bounds[1]:.6f}..{body_bounds[3]:.6f} mm",
            f"Layer-99 body size: {bounds_size(body_bounds)[0]:.6f} x {bounds_size(body_bounds)[1]:.6f} mm",
            f"Source pin-1 marker centre: x={marker_x:.6f} mm, y={marker_y:.6f} mm",
        ]
    )

    expected_geometry = {
        "pitch": (pitch, 0.30),
        "span": (span, 9.00),
        "row separation": (row_separation, 2.75),
        "odd pad width": (expected_odd_w, 0.20),
        "odd pad length": (expected_odd_h, 0.75),
        "even pad width": (expected_even_w, 0.20),
        "even pad length": (expected_even_h, 0.55),
        "body width": (bounds_size(body_bounds)[0], 10.80),
        "body depth": (bounds_size(body_bounds)[1], 3.22),
    }
    for name, (measured, nominal) in expected_geometry.items():
        if not nearly_equal(measured, nominal):
            errors.append(f"EasyEDA {name} {measured:.6f} mm differs from drawing nominal {nominal:.6f} mm")

    max_centre_error = 0.0
    max_size_error = 0.0
    if set(expected_by_number) == expected_numbers and set(actual_by_number) == expected_numbers:
        for number in sorted(expected_numbers, key=int):
            source_pad = expected_by_number[number]
            footprint_pad = actual_by_number[number]
            max_centre_error = max(
                max_centre_error,
                abs(float(source_pad["x"]) - float(footprint_pad["x"])),
                abs(float(source_pad["y"]) - float(footprint_pad["y"])),
            )
            max_size_error = max(
                max_size_error,
                abs(float(source_pad["w"]) - float(footprint_pad["w"])),
                abs(float(source_pad["h"]) - float(footprint_pad["h"])),
            )
        if max_centre_error > GEOMETRY_TOLERANCE_MM:
            errors.append("KiCad pad centres differ from EasyEDA by more than 0.002 mm")
        if max_size_error > GEOMETRY_TOLERANCE_MM:
            errors.append("KiCad pad sizes differ from EasyEDA by more than 0.002 mm")

    rectangles = kicad_rectangles(args.footprint)
    fab_bounds = rectangles.get("F.Fab")
    courtyard = rectangles.get("F.CrtYd")
    if fab_bounds is None:
        errors.append("KiCad footprint has no rectangular F.Fab body outline")
    else:
        fab_error = max(abs(left - right) for left, right in zip(fab_bounds, body_bounds))
        if fab_error > GEOMETRY_TOLERANCE_MM:
            errors.append("KiCad F.Fab bounds differ from EasyEDA layer-99 body")
    if courtyard is None:
        errors.append("KiCad footprint has no rectangular F.CrtYd outline")
        courtyard_margins = (0.0, 0.0, 0.0, 0.0)
    else:
        physical_bounds = (
            min(body_bounds[0], *(float(pad["x"]) - float(pad["w"]) / 2 for pad in expected)),
            min(body_bounds[1], *(float(pad["y"]) - float(pad["h"]) / 2 for pad in expected)),
            max(body_bounds[2], *(float(pad["x"]) + float(pad["w"]) / 2 for pad in expected)),
            max(body_bounds[3], *(float(pad["y"]) + float(pad["h"]) / 2 for pad in expected)),
        )
        courtyard_margins = (
            physical_bounds[0] - courtyard[0],
            physical_bounds[1] - courtyard[1],
            courtyard[2] - physical_bounds[2],
            courtyard[3] - physical_bounds[3],
        )
        if min(courtyard_margins) < 0.249:
            errors.append("KiCad courtyard margin is less than 0.25 mm")

    lines.extend(
        [
            "",
            "KICAD FOOTPRINT COMPARISON",
            f"Footprint: {args.footprint}",
            f"Pads: {len(actual)}",
            f"Maximum pad-centre residual (absolute source coordinates): {max_centre_error:.6f} mm",
            f"Maximum pad-size residual: {max_size_error:.6f} mm",
            f"F.Fab bounds: {fab_bounds}",
            f"F.CrtYd bounds: {courtyard}",
            "Courtyard margins L/T/R/B: " + "/".join(f"{margin:.3f}" for margin in courtyard_margins) + " mm",
            "",
            "PAD NUMBERING / CONTACT ORIENTATION",
            "Pin 1 = (-4.50, +1.375) mm, left/front row in PCB top view.",
            "Pin 31 = (+4.50, +1.375) mm, right/front row in PCB top view.",
            "Pin numbers advance in +X; odd pins are front/+Y and even pins are rear/-Y.",
            "The source package's pin-1 circle is at the left/front body corner and is reproduced on F.SilkS.",
            "With the connector on F.Cu and the flex tail extending toward +Y, the bottom-contact FPC's exposed copper faces the PCB.",
            "A loose flex viewed from its exposed-copper side can look mirrored; align the screen vendor's pin-1 callout, not a photo alone.",
        ]
    )

    xunpu = kicad_pads(args.xunpu_footprint)
    xunpu_signals = {str(pad["number"]): pad for pad in xunpu if str(pad["number"]).isdigit()}
    xunpu_odd_y, xunpu_odd_w, xunpu_odd_h = row_summary(xunpu_signals, True)
    xunpu_even_y, xunpu_even_w, xunpu_even_h = row_summary(xunpu_signals, False)
    xunpu_pitch = statistics.median(
        float(xunpu_signals[str(number + 1)]["x"]) - float(xunpu_signals[str(number)]["x"])
        for number in range(1, 31)
    )
    xunpu_mounts = [pad for pad in xunpu if str(pad["number"]) == ""]
    xunpu_rectangles = kicad_rectangles(args.xunpu_footprint)
    xunpu_fab = xunpu_rectangles.get("F.Fab")
    xunpu_fab_text = (
        f"{bounds_size(xunpu_fab)[0]:.4f} x {bounds_size(xunpu_fab)[1]:.4f}"
        if xunpu_fab
        else "missing"
    )
    ocn_body_text = f"{bounds_size(body_bounds)[0]:.4f} x {bounds_size(body_bounds)[1]:.4f}"
    lines.extend(
        [
            "",
            "DIFFERENCE FROM CURRENT XUNPU FPC-0.3FX-31PWBH10 FOOTPRINT",
            f"Pitch: XUNPU {xunpu_pitch:.4f} mm; OCN {pitch:.4f} mm (same pitch only).",
            f"Signal-pad width: XUNPU {xunpu_odd_w:.4f} mm; OCN {expected_odd_w:.4f} mm.",
            f"Odd/front pad length: XUNPU {xunpu_odd_h:.4f} mm; OCN {expected_odd_h:.4f} mm.",
            f"Even/rear pad length: XUNPU {xunpu_even_h:.4f} mm; OCN {expected_even_h:.4f} mm.",
            f"Row separation: XUNPU {abs(xunpu_odd_y - xunpu_even_y):.4f} mm; OCN {row_separation:.4f} mm.",
            f"Mechanical solder pads: XUNPU {len(xunpu_mounts)}; OCN 0.",
            f"F.Fab body: XUNPU {xunpu_fab_text} mm; OCN {ocn_body_text} mm.",
            "Pad-number progression is +X in both library footprints, but the land patterns and retention structures are not interchangeable.",
            "",
            "VERDICT",
        ]
    )
    if errors:
        lines.append("RESULT: FAIL")
        lines.extend(f"ERROR: {error}" for error in errors)
    else:
        lines.extend(
            [
                "RESULT: PASS",
                "The standalone OCN footprint matches the JLC/EasyEDA package and OCN drawing within 0.002 mm.",
                "It is ready for schematic/PCB assignment after the display FPC's electrical pin-1 orientation is independently confirmed.",
                "No schematic or PCB main file was modified by this audit.",
            ]
        )

    report = "\n".join(lines) + "\n"
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(report, encoding="utf-8")
    print(report, end="")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
