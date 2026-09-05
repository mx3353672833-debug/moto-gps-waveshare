#!/usr/bin/env python3
"""Fail-closed KiCad fabrication release gate for the MOTO GPS mainboard.

This is deliberately stricter than ERC/DRC: an empty KiCad project can pass both
checks, so source-level completeness checks run before KiCad is ever invoked.
"""

from __future__ import annotations

import argparse
import csv
import dataclasses
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from typing import Iterable, Iterator, Sequence
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SCHEMATIC = Path("hardware/rev_a/schematic/moto-gps-rev-a.kicad_sch")
DEFAULT_BOARD = Path("hardware/rev_a/board/moto-gps-rev-a.kicad_pcb")
DEFAULT_PROJECT = Path("hardware/rev_a/board/moto-gps-rev-a.kicad_pro")

# This is the KiCad build used to create the repository's Rev A0 source files.
# MOTO_GPS_KICAD_CLI allows CI or another workstation to provide the same build.
PINNED_KICAD_CLI = Path(
    "/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents/MacOS/kicad-cli"
)
PINNED_KICAD_VERSION = "10.0.6"

LCSC_FIELD_ALIASES = (
    "LCSC Part #",
    "LCSC Part",
    "LCSC",
    "JLCPCB Part #",
    "JLCPCB Part",
)
LCSC_CODE_RE = re.compile(r"^C[0-9]+$", re.IGNORECASE)
REFERENCE_RE = re.compile(r"^([A-Za-z#]+)([0-9]+)([A-Za-z]*)$")

FAB_BLOCK_MARKERS = (
    ("NOT_FOR_FAB", re.compile(r"\bnot\s+for\s+(?:fab|fabrication|production)\b", re.I)),
    ("DO_NOT_FAB", re.compile(r"\bdo\s+not\s+(?:fab|fabricate|manufacture|produce)\b", re.I)),
    ("MECHANICAL_ONLY", re.compile(r"\bmechanical\s+(?:envelope\s+)?only\b", re.I)),
    ("PLACEMENT_ENVELOPE", re.compile(r"\bplacement\s+envelope\b", re.I)),
    ("ARCHITECTURE_DRAFT", re.compile(r"\barchitecture\s+draft\b", re.I)),
    ("PLACEHOLDER", re.compile(r"\bplaceholder\b", re.I)),
    ("CHINESE_NO_FAB", re.compile(r"(?:禁止投产|不可投产|不能下单|仅供结构|仅供机械)")),
)


@dataclasses.dataclass(frozen=True)
class Thresholds:
    """Target-specific sanity floor, not an electrical design guarantee."""

    min_symbols: int = 20
    min_wires: int = 20
    min_footprints: int = 20
    min_pads: int = 50
    min_named_nets: int = 10
    min_tracks: int = 20
    min_vias: int = 1
    min_zones: int = 1
    min_copper_layers: int = 4
    min_edge_items: int = 1


@dataclasses.dataclass
class Component:
    reference: str = ""
    value: str = ""
    footprint: str = ""
    properties: dict[str, str] = dataclasses.field(default_factory=dict)
    attrs: set[str] = dataclasses.field(default_factory=set)
    flags: dict[str, str] = dataclasses.field(default_factory=dict)
    pad_count: int = 0
    smd_pad_count: int = 0

    @property
    def excluded_from_bom(self) -> bool:
        return (
            "exclude_from_bom" in self.attrs
            or self.flags.get("in_bom", "yes") == "no"
        )

    @property
    def excluded_from_board(self) -> bool:
        return (
            "exclude_from_board" in self.attrs
            or self.flags.get("on_board", "yes") == "no"
        )

    @property
    def dnp(self) -> bool:
        return "dnp" in self.attrs or self.flags.get("dnp", "no") == "yes"

    @property
    def assembly_smd(self) -> bool:
        return (
            not self.excluded_from_bom
            and not self.excluded_from_board
            and not self.dnp
            and "board_only" not in self.attrs
            and "exclude_from_pos_files" not in self.attrs
            and ("smd" in self.attrs or self.smd_pad_count > 0)
        )


@dataclasses.dataclass
class SchematicAnalysis:
    symbols: list[Component] = dataclasses.field(default_factory=list)
    wires: int = 0
    buses: int = 0
    junctions: int = 0
    markers: list[str] = dataclasses.field(default_factory=list)

    @property
    def production_symbols(self) -> list[Component]:
        return [
            symbol
            for symbol in self.symbols
            if symbol.reference
            and not symbol.reference.startswith("#")
            and not symbol.excluded_from_bom
            and not symbol.excluded_from_board
            and not symbol.dnp
        ]

    @property
    def on_board_symbols(self) -> list[Component]:
        """Every physical design item, including DNP and non-BOM test pads."""
        return [
            symbol
            for symbol in self.symbols
            if symbol.reference
            and not symbol.reference.startswith("#")
            and not symbol.excluded_from_board
        ]


@dataclasses.dataclass
class BoardAnalysis:
    footprints: list[Component] = dataclasses.field(default_factory=list)
    pads: int = 0
    named_nets: set[str] = dataclasses.field(default_factory=set)
    tracks: int = 0
    vias: int = 0
    zones: int = 0
    copper_layers: list[str] = dataclasses.field(default_factory=list)
    edge_items: int = 0
    markers: list[str] = dataclasses.field(default_factory=list)

    @property
    def production_footprints(self) -> list[Component]:
        return [
            footprint
            for footprint in self.footprints
            if footprint.reference
            and not footprint.reference.startswith("#")
            and not footprint.excluded_from_bom
            and not footprint.dnp
            and "board_only" not in footprint.attrs
        ]

    @property
    def design_footprints(self) -> list[Component]:
        """All physical PCB parts, including DNP and non-BOM test pads."""
        return [
            footprint
            for footprint in self.footprints
            if footprint.reference
            and not footprint.reference.startswith("#")
            and "board_only" not in footprint.attrs
        ]

    @property
    def assembly_refs(self) -> set[str]:
        return {
            footprint.reference
            for footprint in self.footprints
            if footprint.assembly_smd
        }


@dataclasses.dataclass(frozen=True)
class GateIssue:
    code: str
    message: str


@dataclasses.dataclass
class GateReport:
    schematic: SchematicAnalysis
    board: BoardAnalysis
    errors: list[GateIssue] = dataclasses.field(default_factory=list)
    warnings: list[GateIssue] = dataclasses.field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not self.errors

    def metrics(self) -> dict[str, int]:
        return {
            "symbols": len(self.schematic.production_symbols),
            "wires": self.schematic.wires,
            "footprints": len(self.board.production_footprints),
            "pads": self.board.pads,
            "named_nets": len(self.board.named_nets),
            "tracks": self.board.tracks,
            "vias": self.board.vias,
            "zones": self.board.zones,
            "copper_layers": len(self.board.copper_layers),
            "edge_items": self.board.edge_items,
            "smd_assembly_refs": len(self.board.assembly_refs),
        }

    def as_dict(self) -> dict[str, object]:
        return {
            "passed": self.passed,
            "metrics": self.metrics(),
            "errors": [dataclasses.asdict(issue) for issue in self.errors],
            "warnings": [dataclasses.asdict(issue) for issue in self.warnings],
        }


@dataclasses.dataclass
class _Frame:
    head: str | None = None
    atoms: list[str] = dataclasses.field(default_factory=list)
    properties: dict[str, str] = dataclasses.field(default_factory=dict)
    attrs: set[str] = dataclasses.field(default_factory=set)
    flags: dict[str, str] = dataclasses.field(default_factory=dict)
    pad_count: int = 0
    smd_pad_count: int = 0
    layer: str = ""
    net_id: str = ""


TOKEN_RE = re.compile(
    r"\s*(?:(?P<open>\()|(?P<close>\))|(?P<string>\"(?:\\.|[^\"\\])*\")|(?P<atom>[^\s()]+))",
    re.DOTALL,
)


def _decode_string(token: str) -> str:
    try:
        return json.loads(token)
    except json.JSONDecodeError:
        return token[1:-1].replace(r"\"", '"').replace(r"\\", "\\")


def _tokens(text: str) -> Iterator[str]:
    position = 0
    while position < len(text):
        match = TOKEN_RE.match(text, position)
        if not match:
            if text[position:].strip():
                raise ValueError(f"invalid KiCad S-expression near byte {position}")
            break
        position = match.end()
        if match.group("open"):
            yield "("
        elif match.group("close"):
            yield ")"
        elif match.group("string"):
            yield _decode_string(match.group("string"))
        elif match.group("atom"):
            yield match.group("atom")


def _nearest(stack: Sequence[_Frame], heads: set[str]) -> _Frame | None:
    for frame in reversed(stack):
        if frame.head in heads:
            return frame
    return None


def _marker_hits(text: str) -> list[str]:
    return [name for name, pattern in FAB_BLOCK_MARKERS if pattern.search(text)]


def analyze_schematic(path: Path) -> SchematicAnalysis:
    text = path.read_text(encoding="utf-8")
    analysis = SchematicAnalysis(markers=_marker_hits(text))
    stack: list[_Frame] = []

    for token in _tokens(text):
        if token == "(":
            stack.append(_Frame())
            continue
        if token != ")":
            if not stack:
                raise ValueError("atom outside KiCad root expression")
            if stack[-1].head is None:
                stack[-1].head = token
            else:
                stack[-1].atoms.append(token)
            continue

        if not stack:
            raise ValueError("unbalanced closing parenthesis in schematic")
        frame = stack.pop()
        parent = stack[-1] if stack else None

        if frame.head == "property" and len(frame.atoms) >= 2:
            owner = _nearest(stack, {"symbol"})
            if owner is not None:
                owner.properties[frame.atoms[0]] = frame.atoms[1]
        elif frame.head in {"in_bom", "on_board", "dnp"} and frame.atoms:
            owner = _nearest(stack, {"symbol"})
            if owner is not None:
                owner.flags[frame.head] = frame.atoms[0]
        elif frame.head == "symbol":
            in_library = any(ancestor.head == "lib_symbols" for ancestor in stack)
            reference = frame.properties.get("Reference", "")
            if not in_library and reference:
                analysis.symbols.append(
                    Component(
                        reference=reference,
                        value=frame.properties.get("Value", ""),
                        footprint=frame.properties.get("Footprint", ""),
                        properties=dict(frame.properties),
                        flags=dict(frame.flags),
                    )
                )
        elif parent is not None and parent.head == "kicad_sch":
            if frame.head == "wire":
                analysis.wires += 1
            elif frame.head == "bus":
                analysis.buses += 1
            elif frame.head == "junction":
                analysis.junctions += 1

    if stack:
        raise ValueError("unbalanced opening parenthesis in schematic")
    return analysis


def analyze_board(path: Path) -> BoardAnalysis:
    text = path.read_text(encoding="utf-8")
    analysis = BoardAnalysis(markers=_marker_hits(text))
    stack: list[_Frame] = []
    graphic_heads = {"gr_line", "gr_arc", "gr_circle", "gr_rect", "gr_poly", "gr_curve"}

    for token in _tokens(text):
        if token == "(":
            stack.append(_Frame())
            continue
        if token != ")":
            if not stack:
                raise ValueError("atom outside KiCad root expression")
            if stack[-1].head is None:
                stack[-1].head = token
            else:
                stack[-1].atoms.append(token)
            continue

        if not stack:
            raise ValueError("unbalanced closing parenthesis in PCB")
        frame = stack.pop()
        parent = stack[-1] if stack else None

        if frame.head == "property" and len(frame.atoms) >= 2:
            owner = _nearest(stack, {"footprint"})
            if owner is not None:
                owner.properties[frame.atoms[0]] = frame.atoms[1]
        elif frame.head == "attr":
            owner = _nearest(stack, {"footprint"})
            if owner is not None:
                owner.attrs.update(frame.atoms)
        elif frame.head in {"dnp", "in_bom", "on_board"} and frame.atoms:
            owner = _nearest(stack, {"footprint"})
            if owner is not None:
                owner.flags[frame.head] = frame.atoms[0]
        elif frame.head == "net" and frame.atoms:
            route_owner = _nearest(stack, {"pad", "segment", "arc", "via", "zone"})
            if route_owner is not None:
                route_owner.net_id = frame.atoms[0]
                # KiCad 10 writes the net name directly inside pads/tracks/zones
                # and may omit the legacy top-level numeric net table entirely.
                # Older files put a numeric code here, so only accept non-numeric
                # atoms as names in this branch.
                candidate = frame.atoms[0]
                if (
                    candidate
                    and not candidate.isdigit()
                    and not candidate.lower().startswith("unconnected-")
                ):
                    analysis.named_nets.add(candidate)
            elif parent is not None and parent.head == "kicad_pcb" and len(frame.atoms) >= 2:
                net_id, name = frame.atoms[0], frame.atoms[1]
                if net_id != "0" and name and not name.lower().startswith("unconnected-"):
                    analysis.named_nets.add(name)
        elif frame.head == "layer" and frame.atoms:
            owner = _nearest(stack, graphic_heads)
            if owner is not None:
                owner.layer = frame.atoms[0]
        elif frame.head == "pad":
            owner = _nearest(stack, {"footprint"})
            if owner is not None:
                owner.pad_count += 1
                if len(frame.atoms) >= 2 and frame.atoms[1] == "smd":
                    owner.smd_pad_count += 1
            analysis.pads += 1
        elif frame.head == "footprint":
            reference = frame.properties.get("Reference", "")
            analysis.footprints.append(
                Component(
                    reference=reference,
                    value=frame.properties.get("Value", ""),
                    footprint=frame.atoms[0] if frame.atoms else "",
                    properties=dict(frame.properties),
                    attrs=set(frame.attrs),
                    flags=dict(frame.flags),
                    pad_count=frame.pad_count,
                    smd_pad_count=frame.smd_pad_count,
                )
            )
        elif parent is not None and parent.head == "layers" and frame.atoms:
            layer_name = frame.atoms[0]
            if layer_name.endswith(".Cu"):
                analysis.copper_layers.append(layer_name)
        elif parent is not None and parent.head == "kicad_pcb":
            if frame.head in {"segment", "arc"}:
                analysis.tracks += 1
            elif frame.head == "via":
                analysis.vias += 1
            elif frame.head == "zone":
                analysis.zones += 1
            elif frame.head in graphic_heads and frame.layer == "Edge.Cuts":
                analysis.edge_items += 1

    if stack:
        raise ValueError("unbalanced opening parenthesis in PCB")
    return analysis


def _add_minimum_error(
    report: GateReport, code: str, label: str, actual: int, minimum: int
) -> None:
    if actual < minimum:
        report.errors.append(
            GateIssue(code, f"{label}: found {actual}, release minimum is {minimum}")
        )


def _find_lcsc_field(symbols: Iterable[Component]) -> str:
    scores = {
        field: sum(bool(symbol.properties.get(field, "").strip()) for symbol in symbols)
        for field in LCSC_FIELD_ALIASES
    }
    return max(scores, key=scores.get) if any(scores.values()) else LCSC_FIELD_ALIASES[0]


def run_static_gate(
    schematic_path: Path,
    board_path: Path,
    thresholds: Thresholds = Thresholds(),
) -> GateReport:
    schematic = analyze_schematic(schematic_path)
    board = analyze_board(board_path)
    report = GateReport(schematic=schematic, board=board)

    for source, markers in (("schematic", schematic.markers), ("PCB", board.markers)):
        if markers:
            report.errors.append(
                GateIssue(
                    "FAB_BLOCK_MARKER",
                    f"{source} contains fabrication-blocking marker(s): {', '.join(markers)}",
                )
            )

    _add_minimum_error(
        report, "MIN_SYMBOLS", "production schematic symbols", len(schematic.production_symbols), thresholds.min_symbols
    )
    _add_minimum_error(report, "MIN_WIRES", "schematic wires", schematic.wires, thresholds.min_wires)
    _add_minimum_error(
        report, "MIN_FOOTPRINTS", "production PCB footprints", len(board.production_footprints), thresholds.min_footprints
    )
    _add_minimum_error(report, "MIN_PADS", "PCB pads", board.pads, thresholds.min_pads)
    _add_minimum_error(
        report, "MIN_NETS", "connected named PCB nets", len(board.named_nets), thresholds.min_named_nets
    )
    _add_minimum_error(report, "MIN_TRACKS", "routed PCB tracks", board.tracks, thresholds.min_tracks)
    _add_minimum_error(report, "MIN_VIAS", "PCB vias", board.vias, thresholds.min_vias)
    _add_minimum_error(report, "MIN_ZONES", "PCB copper zones", board.zones, thresholds.min_zones)
    _add_minimum_error(
        report, "MIN_COPPER_LAYERS", "PCB copper layers", len(board.copper_layers), thresholds.min_copper_layers
    )
    _add_minimum_error(report, "MIN_EDGE_ITEMS", "Edge.Cuts primitives", board.edge_items, thresholds.min_edge_items)

    production_symbols = schematic.production_symbols
    missing_footprint = sorted(
        symbol.reference for symbol in production_symbols if not symbol.footprint.strip()
    )
    if missing_footprint:
        report.errors.append(
            GateIssue(
                "SCHEMATIC_FOOTPRINT_MISSING",
                "production symbols without assigned footprints: " + ", ".join(missing_footprint),
            )
        )

    lcsc_field = _find_lcsc_field(production_symbols)
    missing_lcsc = sorted(
        symbol.reference
        for symbol in production_symbols
        if not LCSC_CODE_RE.fullmatch(symbol.properties.get(lcsc_field, "").strip())
    )
    if missing_lcsc:
        report.errors.append(
            GateIssue(
                "LCSC_PART_MISSING",
                f"production symbols without a valid {lcsc_field!r} C-number: "
                + ", ".join(missing_lcsc),
            )
        )

    # A catalogue match is not the same thing as a released production choice.
    # Anything awaiting stock, source, RF, thermal or physical-sample work must
    # keep the fail-closed fabrication gate shut even when its C-number parses.
    non_released_procurement = sorted(
        (
            symbol.reference,
            symbol.properties.get("Procurement Status", "MISSING").strip() or "MISSING",
        )
        for symbol in production_symbols
        if symbol.properties.get("Procurement Status", "").strip() != "VERIFIED_CATALOG"
    )
    if non_released_procurement:
        report.errors.append(
            GateIssue(
                "PROCUREMENT_STATUS_BLOCKED",
                "production symbols not procurement-released: "
                + ", ".join(f"{ref}={status}" for ref, status in non_released_procurement),
            )
        )

    # Parity is a physical-design comparison, so DNP tuning pads and non-BOM
    # test points must still exist on both sides even though neither belongs in
    # the production assembly count.
    schematic_refs = {symbol.reference for symbol in schematic.on_board_symbols}
    board_refs = {footprint.reference for footprint in board.design_footprints}
    missing_on_board = sorted(schematic_refs - board_refs)
    missing_in_schematic = sorted(board_refs - schematic_refs)
    if missing_on_board:
        report.errors.append(
            GateIssue(
                "SCHEMATIC_BOARD_MISMATCH",
                "schematic production refs absent from PCB: " + ", ".join(missing_on_board),
            )
        )
    if missing_in_schematic:
        report.errors.append(
            GateIssue(
                "BOARD_SCHEMATIC_MISMATCH",
                "PCB production refs absent from schematic: " + ", ".join(missing_in_schematic),
            )
        )

    unresolved_refs = sorted(
        footprint.reference or "<blank>"
        for footprint in board.footprints
        if not footprint.reference or "**" in footprint.reference
    )
    if unresolved_refs:
        report.errors.append(
            GateIssue(
                "UNRESOLVED_REFERENCE",
                "PCB contains unresolved reference designators: " + ", ".join(unresolved_refs),
            )
        )

    if production_symbols and not board.assembly_refs:
        report.errors.append(
            GateIssue("NO_SMD_ASSEMBLY", "no populated SMD footprints were found for CPL export")
        )
    return report


def _normalise_header(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", value.lower())


def _column(fieldnames: Sequence[str], *aliases: str) -> str | None:
    by_normalised = {_normalise_header(name): name for name in fieldnames}
    for alias in aliases:
        match = by_normalised.get(_normalise_header(alias))
        if match:
            return match
    return None


def _expand_designator_token(token: str) -> set[str]:
    token = token.strip()
    if not token:
        return set()
    if "-" not in token:
        return {token}
    start, end = token.split("-", 1)
    start_match = REFERENCE_RE.fullmatch(start.strip())
    end_match = REFERENCE_RE.fullmatch(end.strip())
    if not start_match or not end_match:
        return {start.strip(), end.strip()}
    start_prefix, start_number, start_suffix = start_match.groups()
    end_prefix, end_number, end_suffix = end_match.groups()
    if start_prefix != end_prefix or start_suffix != end_suffix:
        return {start.strip(), end.strip()}
    first, last = int(start_number), int(end_number)
    if last < first or last - first > 10000:
        return {start.strip(), end.strip()}
    return {f"{start_prefix}{number}{start_suffix}" for number in range(first, last + 1)}


def parse_designators(value: str) -> set[str]:
    refs: set[str] = set()
    for token in re.split(r"[,;\s]+", value.strip()):
        refs.update(_expand_designator_token(token))
    return {reference for reference in refs if reference}


def validate_bom_cpl(
    bom_path: Path, cpl_path: Path, expected_smd_refs: set[str]
) -> list[GateIssue]:
    issues: list[GateIssue] = []
    with bom_path.open(newline="", encoding="utf-8-sig") as handle:
        bom_reader = csv.DictReader(handle)
        bom_fields = bom_reader.fieldnames or []
        designator_col = _column(bom_fields, "Designator", "Reference", "Refs")
        lcsc_col = _column(bom_fields, "LCSC Part #", "LCSC Part", "LCSC")
        quantity_col = _column(bom_fields, "Quantity", "Qty")
        if not designator_col or not lcsc_col or not quantity_col:
            return [
                GateIssue(
                    "BOM_COLUMNS",
                    "BOM must contain Designator, Quantity and LCSC Part # columns",
                )
            ]
        bom_refs: set[str] = set()
        duplicate_bom_refs: set[str] = set()
        invalid_lcsc_refs: set[str] = set()
        quantity_mismatch_rows: list[str] = []
        for row_number, row in enumerate(bom_reader, start=2):
            row_refs = parse_designators(row.get(designator_col, ""))
            duplicate_bom_refs.update(bom_refs & row_refs)
            bom_refs.update(row_refs)
            quantity = row.get(quantity_col, "").strip()
            if not quantity.isdecimal() or int(quantity) != len(row_refs):
                quantity_mismatch_rows.append(str(row_number))
            if row_refs & expected_smd_refs and not LCSC_CODE_RE.fullmatch(
                row.get(lcsc_col, "").strip()
            ):
                invalid_lcsc_refs.update(row_refs & expected_smd_refs)

    with cpl_path.open(newline="", encoding="utf-8-sig") as handle:
        cpl_reader = csv.DictReader(handle)
        cpl_fields = cpl_reader.fieldnames or []
        cpl_designator_col = _column(cpl_fields, "Designator", "Reference", "Ref")
        x_col = _column(cpl_fields, "Mid X", "PosX", "X")
        y_col = _column(cpl_fields, "Mid Y", "PosY", "Y")
        layer_col = _column(cpl_fields, "Layer", "Side")
        rotation_col = _column(cpl_fields, "Rotation", "Rot")
        if not all((cpl_designator_col, x_col, y_col, layer_col, rotation_col)):
            return issues + [
                GateIssue(
                    "CPL_COLUMNS",
                    "CPL must contain Designator, Mid X, Mid Y, Layer and Rotation columns",
                )
            ]
        cpl_refs: set[str] = set()
        malformed_rows: list[str] = []
        for row_number, row in enumerate(cpl_reader, start=2):
            reference = row.get(cpl_designator_col, "").strip()
            if reference:
                cpl_refs.add(reference)
            try:
                float(row.get(x_col, ""))
                float(row.get(y_col, ""))
                float(row.get(rotation_col, ""))
            except (TypeError, ValueError):
                malformed_rows.append(str(row_number))
            if row.get(layer_col, "").strip().lower() not in {"top", "bottom", "front", "back"}:
                malformed_rows.append(str(row_number))

    if invalid_lcsc_refs:
        issues.append(
            GateIssue(
                "BOM_LCSC_INVALID",
                "BOM rows missing valid LCSC C-numbers: " + ", ".join(sorted(invalid_lcsc_refs)),
            )
        )
    if expected_smd_refs - bom_refs:
        issues.append(
            GateIssue(
                "BOM_REFS_MISSING",
                "populated SMD refs absent from BOM: "
                + ", ".join(sorted(expected_smd_refs - bom_refs)),
            )
        )
    if bom_refs - expected_smd_refs:
        issues.append(
            GateIssue(
                "BOM_REFS_EXTRA",
                "BOM contains refs not marked for SMD assembly: "
                + ", ".join(sorted(bom_refs - expected_smd_refs)),
            )
        )
    if duplicate_bom_refs:
        issues.append(
            GateIssue(
                "BOM_REFS_DUPLICATE",
                "BOM contains refs repeated across multiple rows: "
                + ", ".join(sorted(duplicate_bom_refs)),
            )
        )
    if quantity_mismatch_rows:
        issues.append(
            GateIssue(
                "BOM_QUANTITY_MISMATCH",
                "BOM Quantity does not match expanded Designator count on row(s): "
                + ", ".join(sorted(quantity_mismatch_rows, key=int)),
            )
        )
    if expected_smd_refs - cpl_refs:
        issues.append(
            GateIssue(
                "CPL_REFS_MISSING",
                "populated SMD refs absent from CPL: "
                + ", ".join(sorted(expected_smd_refs - cpl_refs)),
            )
        )
    if cpl_refs - expected_smd_refs:
        issues.append(
            GateIssue(
                "CPL_REFS_EXTRA",
                "CPL contains refs not marked for SMD assembly: "
                + ", ".join(sorted(cpl_refs - expected_smd_refs)),
            )
        )
    if malformed_rows:
        issues.append(
            GateIssue(
                "CPL_ROWS_MALFORMED",
                "CPL has invalid coordinate/layer/rotation data on row(s): "
                + ", ".join(sorted(set(malformed_rows), key=int)),
            )
        )
    return issues


def _resolve_cli(argument: str | None) -> Path:
    configured = argument or os.environ.get("MOTO_GPS_KICAD_CLI")
    path = Path(configured).expanduser() if configured else PINNED_KICAD_CLI
    if not path.is_absolute():
        raise RuntimeError("KiCad CLI path must be absolute")
    if not path.is_file() or not os.access(path, os.X_OK):
        raise RuntimeError(
            f"KiCad CLI not executable at {path}; set MOTO_GPS_KICAD_CLI to an absolute path"
        )
    return path


def _run(command: Sequence[str], cwd: Path | None = None) -> str:
    printable = " ".join(json.dumps(part) if " " in part else part for part in command)
    print(f"$ {printable}")
    result = subprocess.run(
        list(command),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
    )
    if result.returncode:
        if result.stdout:
            print(result.stdout.rstrip(), file=sys.stderr)
        raise RuntimeError(f"command failed with exit code {result.returncode}: {command[1:3]}")
    return result.stdout.strip()


def _convert_kicad_cpl(raw_path: Path, destination: Path) -> None:
    with raw_path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        fields = reader.fieldnames or []
        ref_col = _column(fields, "Ref", "Reference", "Designator")
        x_col = _column(fields, "PosX", "Mid X", "X")
        y_col = _column(fields, "PosY", "Mid Y", "Y")
        rot_col = _column(fields, "Rot", "Rotation")
        side_col = _column(fields, "Side", "Layer")
        if not all((ref_col, x_col, y_col, rot_col, side_col)):
            raise RuntimeError(f"unexpected KiCad position-file columns: {fields}")
        rows = list(reader)
    if not rows:
        raise RuntimeError("KiCad produced an empty position file")

    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=["Designator", "Mid X", "Mid Y", "Layer", "Rotation"]
        )
        writer.writeheader()
        for row in rows:
            side = row[side_col].strip().lower()
            layer = "Top" if side in {"top", "front"} else "Bottom"
            writer.writerow(
                {
                    "Designator": row[ref_col].strip(),
                    "Mid X": row[x_col].strip(),
                    "Mid Y": row[y_col].strip(),
                    "Layer": layer,
                    "Rotation": row[rot_col].strip(),
                }
            )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _require_outputs(paths: Iterable[Path]) -> None:
    missing = [str(path) for path in paths if not path.is_file() or path.stat().st_size == 0]
    if missing:
        raise RuntimeError("release export missing or empty output(s): " + ", ".join(missing))


def _write_manifest(
    stage: Path,
    cli_version: str,
    report: GateReport,
    source_paths: Sequence[Path],
) -> None:
    generated_files = sorted(
        path for path in stage.rglob("*") if path.is_file() and path.name != "SHA256SUMS"
    )
    manifest = {
        "schema": "moto-gps-fabrication-release/v1",
        "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
        "kicad_version": cli_version,
        "gate": report.as_dict(),
        "source_sha256": {path.name: _sha256(path) for path in source_paths},
        "files": {
            str(path.relative_to(stage)): _sha256(path) for path in generated_files
        },
    }
    manifest_path = stage / "release-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    all_files = sorted(path for path in stage.rglob("*") if path.is_file() and path.name != "SHA256SUMS")
    checksums = "".join(
        f"{_sha256(path)}  {path.relative_to(stage)}\n" for path in all_files
    )
    (stage / "SHA256SUMS").write_text(checksums, encoding="utf-8")


def export_release(
    schematic_path: Path,
    board_path: Path,
    project_path: Path,
    output_path: Path,
    report: GateReport,
    cli_argument: str | None,
) -> None:
    if not report.passed:
        raise RuntimeError("static release gate failed; KiCad export was not invoked")
    if output_path.exists():
        raise RuntimeError(f"release output already exists (refusing to overwrite): {output_path}")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    cli = _resolve_cli(cli_argument)
    cli_version = _run([str(cli), "--version"])
    required_version = os.environ.get("MOTO_GPS_KICAD_VERSION", PINNED_KICAD_VERSION)
    if cli_version != required_version:
        raise RuntimeError(
            f"KiCad version mismatch: got {cli_version}, release requires {required_version}"
        )

    stage = Path(tempfile.mkdtemp(prefix=".moto-gps-fab-", dir=output_path.parent))
    try:
        reports_dir = stage / "reports"
        fabrication_dir = stage / "fabrication"
        docs_dir = stage / "documents"
        source_dir = stage / "source"
        internal_dir = stage / ".internal"
        for directory in (reports_dir, fabrication_dir, docs_dir, source_dir, internal_dir):
            directory.mkdir(parents=True, exist_ok=True)

        erc_path = reports_dir / "erc.json"
        drc_path = reports_dir / "drc.json"
        _run(
            [
                str(cli), "sch", "erc", str(schematic_path),
                "--output", str(erc_path), "--format", "json",
                "--severity-error", "--severity-warning", "--exit-code-violations",
            ],
            cwd=schematic_path.parent,
        )
        _run(
            [
                str(cli), "pcb", "drc", str(board_path),
                "--output", str(drc_path), "--format", "json", "--schematic-parity",
                "--severity-error", "--severity-warning", "--exit-code-violations",
            ],
            cwd=board_path.parent,
        )

        copper_layers = report.board.copper_layers
        gerber_layers = copper_layers + [
            "F.Paste", "B.Paste", "F.SilkS", "B.SilkS", "F.Mask", "B.Mask", "Edge.Cuts"
        ]
        _run(
            [
                str(cli), "pcb", "export", "gerbers", str(board_path),
                "--output", str(fabrication_dir), "--layers", ",".join(gerber_layers),
                "--precision", "6", "--check-zones",
            ],
            cwd=board_path.parent,
        )
        # Capture this set before drill/map files are added. KiCad uses mixed
        # Protel extensions (.gtl, .g1, .gm1, ...), so extension globbing is
        # not a reliable way to identify all generated Gerber layers.
        gerbers = {
            path
            for path in fabrication_dir.iterdir()
            if path.is_file() and path.suffix.lower() != ".gbrjob"
        }
        drill_report = reports_dir / "drill-report.txt"
        _run(
            [
                str(cli), "pcb", "export", "drill", str(board_path),
                "--output", str(fabrication_dir), "--format", "excellon",
                "--excellon-units", "mm", "--excellon-separate-th", "--generate-map",
                "--map-format", "pdf", "--generate-report", "--report-path", str(drill_report),
            ],
            cwd=board_path.parent,
        )

        lcsc_field = _find_lcsc_field(report.schematic.production_symbols)
        bom_path = fabrication_dir / "jlcpcb-bom.csv"
        _run(
            [
                str(cli), "sch", "export", "bom", str(schematic_path),
                "--output", str(bom_path),
                "--fields", f"Value,Reference,Footprint,QUANTITY,{lcsc_field}",
                "--labels", "Comment,Designator,Footprint,Quantity,LCSC Part #",
                "--group-by", f"Value,Footprint,{lcsc_field}",
                "--sort-field", "Reference", "--exclude-dnp", "--ref-range-delimiter", "",
            ],
            cwd=schematic_path.parent,
        )
        raw_cpl = internal_dir / "kicad-position.csv"
        cpl_path = fabrication_dir / "jlcpcb-cpl.csv"
        _run(
            [
                str(cli), "pcb", "export", "pos", str(board_path),
                "--output", str(raw_cpl), "--format", "csv", "--units", "mm",
                "--side", "both", "--smd-only", "--exclude-dnp",
            ],
            cwd=board_path.parent,
        )
        _convert_kicad_cpl(raw_cpl, cpl_path)

        schematic_pdf = docs_dir / "schematic.pdf"
        front_pdf = docs_dir / "pcb-front-assembly.pdf"
        back_pdf = docs_dir / "pcb-back-assembly.pdf"
        _run(
            [str(cli), "sch", "export", "pdf", str(schematic_path), "--output", str(schematic_pdf)],
            cwd=schematic_path.parent,
        )
        _run(
            [
                str(cli), "pcb", "export", "pdf", str(board_path), "--output", str(front_pdf),
                "--layers", "F.Fab,F.SilkS,Edge.Cuts", "--mode-single", "--black-and-white",
                "--sketch-pads-on-fab-layers", "--check-zones",
            ],
            cwd=board_path.parent,
        )
        _run(
            [
                str(cli), "pcb", "export", "pdf", str(board_path), "--output", str(back_pdf),
                "--layers", "B.Fab,B.SilkS,Edge.Cuts", "--mode-single", "--black-and-white",
                "--sketch-pads-on-fab-layers", "--mirror", "--check-zones",
            ],
            cwd=board_path.parent,
        )

        report.errors.extend(validate_bom_cpl(bom_path, cpl_path, report.board.assembly_refs))
        if not report.passed:
            raise RuntimeError(
                "BOM/CPL reconciliation failed: "
                + "; ".join(issue.message for issue in report.errors)
            )

        drills = list(fabrication_dir.glob("*.drl"))
        if len(gerbers) < len(gerber_layers):
            raise RuntimeError(
                f"expected at least {len(gerber_layers)} Gerber files, found {len(gerbers)}"
            )
        if not drills:
            raise RuntimeError("no Excellon drill file was generated")
        _require_outputs(
            [erc_path, drc_path, drill_report, bom_path, cpl_path, schematic_pdf, front_pdf, back_pdf]
            + sorted(gerbers)
            + drills
        )

        source_paths = [schematic_path, board_path]
        if project_path.is_file():
            source_paths.append(project_path)
        for source in source_paths:
            shutil.copy2(source, source_dir / source.name)

        archive_path = stage / "moto-gps-jlcpcb-fabrication.zip"
        with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for file_path in sorted(fabrication_dir.iterdir()):
                if file_path.is_file():
                    archive.write(file_path, arcname=file_path.name)

        shutil.rmtree(internal_dir)
        _write_manifest(stage, cli_version, report, source_paths)
        stage.rename(output_path)
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def _thresholds_from_args(args: argparse.Namespace) -> Thresholds:
    # Deliberately not configurable from the release CLI: a command-line typo
    # must not be able to turn the completeness gate off. Unit tests call the
    # pure gate function with smaller fixture-specific thresholds.
    del args
    return Thresholds()


def _add_source_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--project-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--schematic", type=Path, default=DEFAULT_SCHEMATIC)
    parser.add_argument("--board", type=Path, default=DEFAULT_BOARD)
    parser.add_argument("--project", type=Path, default=DEFAULT_PROJECT)


def _resolve_source(root: Path, path: Path) -> Path:
    return path if path.is_absolute() else root / path


def _print_report(report: GateReport, as_json: bool) -> None:
    if as_json:
        print(json.dumps(report.as_dict(), indent=2, ensure_ascii=False))
        return
    print("FAB RELEASE GATE: " + ("PASS" if report.passed else "REJECTED"))
    for name, value in report.metrics().items():
        print(f"  {name}: {value}")
    for issue in report.errors:
        print(f"  ERROR [{issue.code}] {issue.message}")
    for issue in report.warnings:
        print(f"  WARN  [{issue.code}] {issue.message}")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check_parser = subparsers.add_parser("check", help="run source completeness gates only")
    _add_source_arguments(check_parser)
    check_parser.add_argument("--bom", type=Path, help="optional generated BOM to reconcile")
    check_parser.add_argument("--cpl", type=Path, help="optional generated CPL to reconcile")
    check_parser.add_argument("--json", action="store_true", help="print machine-readable result")

    export_parser = subparsers.add_parser(
        "export", help="gate, run KiCad ERC/DRC, and atomically create a fabrication release"
    )
    _add_source_arguments(export_parser)
    export_parser.add_argument("--output", type=Path, required=True)
    export_parser.add_argument(
        "--kicad-cli",
        help="absolute KiCad CLI path; otherwise use MOTO_GPS_KICAD_CLI or the pinned local path",
    )
    export_parser.add_argument("--json", action="store_true", help="print the preflight result as JSON")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    root = args.project_root.resolve()
    schematic_path = _resolve_source(root, args.schematic).resolve()
    board_path = _resolve_source(root, args.board).resolve()
    project_path = _resolve_source(root, args.project).resolve()
    try:
        report = run_static_gate(schematic_path, board_path, _thresholds_from_args(args))
        if args.command == "check" and bool(args.bom) != bool(args.cpl):
            report.errors.append(
                GateIssue("BOM_CPL_PAIR_REQUIRED", "--bom and --cpl must be provided together")
            )
        elif args.command == "check" and args.bom and args.cpl:
            bom_path = _resolve_source(root, args.bom).resolve()
            cpl_path = _resolve_source(root, args.cpl).resolve()
            report.errors.extend(validate_bom_cpl(bom_path, cpl_path, report.board.assembly_refs))
        _print_report(report, args.json)
        if not report.passed:
            if args.command == "export":
                print("KiCad was not invoked and no release directory was created.", file=sys.stderr)
            return 2
        if args.command == "export":
            output_path = _resolve_source(root, args.output).resolve()
            export_release(
                schematic_path,
                board_path,
                project_path,
                output_path,
                report,
                args.kicad_cli,
            )
            print(f"FABRICATION RELEASE CREATED: {output_path}")
        return 0
    except (OSError, ValueError, RuntimeError) as error:
        print(f"FAB RELEASE ERROR: {error}", file=sys.stderr)
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
