from __future__ import annotations

import csv
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


SCRIPT_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

import fab_release  # noqa: E402


FUNCTIONAL_SCHEMATIC = """(kicad_sch
  (version 20250114)
  (lib_symbols (symbol "Device:R" (property "Reference" "R")))
  (wire (pts (xy 0 0) (xy 1 1)))
  (symbol
    (lib_id "Device:R")
    (in_bom yes)
    (on_board yes)
    (dnp no)
    (property "Reference" "R1")
    (property "Value" "10k")
    (property "Footprint" "Resistor_SMD:R_0402")
    (property "LCSC Part #" "C12345")
    (property "Procurement Status" "VERIFIED_CATALOG")
  )
)"""

FUNCTIONAL_BOARD = """(kicad_pcb
  (version 20241229)
  (layers
    (0 "F.Cu" signal)
    (2 "B.Cu" signal)
  )
  (net 0 "")
  (net 1 "GND")
  (footprint "Resistor_SMD:R_0402"
    (layer "F.Cu")
    (property "Reference" "R1")
    (property "Value" "10k")
    (attr smd)
    (pad "1" smd rect (net 1 "GND"))
  )
  (segment (start 0 0) (end 1 1) (net 1))
  (via (at 1 1) (net 1))
  (zone (net 1) (net_name "GND"))
  (gr_circle (center 0 0) (end 10 0) (layer "Edge.Cuts"))
)"""

KICAD10_NET_BOARD = """(kicad_pcb
  (version 20260206)
  (layers (0 "F.Cu" signal) (2 "B.Cu" signal))
  (footprint "R_0402"
    (layer "F.Cu")
    (property "Reference" "R1")
    (property "Value" "10k")
    (attr smd)
    (pad "1" smd rect (net "GND"))
    (pad "2" smd rect (net "I2C_SDA")))
  (zone (net "GND"))
  (gr_circle (center 0 0) (end 10 0) (layer "Edge.Cuts"))
)"""


class FabReleaseTests(unittest.TestCase):
    def _write_sources(self, directory: Path) -> tuple[Path, Path]:
        schematic = directory / "fixture.kicad_sch"
        board = directory / "fixture.kicad_pcb"
        schematic.write_text(FUNCTIONAL_SCHEMATIC, encoding="utf-8")
        board.write_text(FUNCTIONAL_BOARD, encoding="utf-8")
        return schematic, board

    def test_current_a0_is_rejected_as_empty_and_not_for_fab(self) -> None:
        report = fab_release.run_static_gate(
            REPO_ROOT / "hardware/pcb/moto-gps-rev-a.kicad_sch",
            REPO_ROOT / "hardware/pcb/moto-gps-rev-a.kicad_pcb",
        )
        codes = {issue.code for issue in report.errors}
        self.assertFalse(report.passed)
        self.assertIn("FAB_BLOCK_MARKER", codes)
        self.assertIn("MIN_SYMBOLS", codes)
        self.assertIn("MIN_FOOTPRINTS", codes)
        self.assertIn("MIN_PADS", codes)
        self.assertIn("MIN_TRACKS", codes)

    def test_small_complete_fixture_passes_when_thresholds_are_explicit(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            schematic, board = self._write_sources(Path(temporary))
            report = fab_release.run_static_gate(
                schematic,
                board,
                fab_release.Thresholds(
                    min_symbols=1,
                    min_wires=1,
                    min_footprints=1,
                    min_pads=1,
                    min_named_nets=1,
                    min_tracks=1,
                    min_vias=1,
                    min_zones=1,
                    min_copper_layers=2,
                    min_edge_items=1,
                ),
            )
        self.assertTrue(report.passed, [issue.message for issue in report.errors])
        self.assertEqual(report.board.assembly_refs, {"R1"})

    def test_non_released_procurement_status_blocks_static_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            schematic, board = self._write_sources(Path(temporary))
            schematic.write_text(
                schematic.read_text(encoding="utf-8").replace(
                    '"VERIFIED_CATALOG"', '"PHYSICAL_SAMPLE_GATE"'
                ),
                encoding="utf-8",
            )
            report = fab_release.run_static_gate(
                schematic,
                board,
                fab_release.Thresholds(
                    min_symbols=1,
                    min_wires=1,
                    min_footprints=1,
                    min_pads=1,
                    min_named_nets=1,
                    min_tracks=1,
                    min_vias=1,
                    min_zones=1,
                    min_copper_layers=2,
                    min_edge_items=1,
                ),
            )
        self.assertIn("PROCUREMENT_STATUS_BLOCKED", {issue.code for issue in report.errors})

    def test_bom_cpl_reconciliation_catches_missing_cpl_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bom = root / "bom.csv"
            cpl = root / "cpl.csv"
            with bom.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(
                    ["Comment", "Designator", "Footprint", "Quantity", "LCSC Part #"]
                )
                writer.writerow(["10k", "R1,R2", "0402", "2", "C12345"])
            with cpl.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
                writer.writerow(["R1", "1.0", "2.0", "Top", "0"])
            issues = fab_release.validate_bom_cpl(bom, cpl, {"R1", "R2"})
        self.assertIn("CPL_REFS_MISSING", {issue.code for issue in issues})

    def test_bom_cpl_reconciliation_catches_extra_bom_reference(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bom = root / "bom.csv"
            cpl = root / "cpl.csv"
            with bom.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(
                    ["Comment", "Designator", "Footprint", "Quantity", "LCSC Part #"]
                )
                writer.writerow(["10k", "R1,R9", "0402", "2", "C12345"])
            with cpl.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
                writer.writerow(["R1", "1.0", "2.0", "Top", "0"])
            issues = fab_release.validate_bom_cpl(bom, cpl, {"R1"})
        self.assertIn("BOM_REFS_EXTRA", {issue.code for issue in issues})

    def test_bom_cpl_reconciliation_catches_reference_repeated_across_rows(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bom = root / "bom.csv"
            cpl = root / "cpl.csv"
            with bom.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(
                    ["Comment", "Designator", "Footprint", "Quantity", "LCSC Part #"]
                )
                writer.writerow(["10k", "R1", "0402", "1", "C12345"])
                writer.writerow(["10k alt", "R1", "0402", "1", "C67890"])
            with cpl.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
                writer.writerow(["R1", "1.0", "2.0", "Top", "0"])
            issues = fab_release.validate_bom_cpl(bom, cpl, {"R1"})
        self.assertIn("BOM_REFS_DUPLICATE", {issue.code for issue in issues})

    def test_bom_cpl_reconciliation_catches_quantity_mismatch(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            bom = root / "bom.csv"
            cpl = root / "cpl.csv"
            with bom.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(
                    ["Comment", "Designator", "Footprint", "Quantity", "LCSC Part #"]
                )
                writer.writerow(["10k", "R1-R2", "0402", "1", "C12345"])
            with cpl.open("w", newline="", encoding="utf-8") as handle:
                writer = csv.writer(handle)
                writer.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
                writer.writerow(["R1", "1.0", "2.0", "Top", "0"])
                writer.writerow(["R2", "3.0", "4.0", "Top", "0"])
            issues = fab_release.validate_bom_cpl(bom, cpl, {"R1", "R2"})
        self.assertIn("BOM_QUANTITY_MISMATCH", {issue.code for issue in issues})

    def test_kicad10_inline_net_names_are_counted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            board = Path(temporary) / "inline-nets.kicad_pcb"
            board.write_text(KICAD10_NET_BOARD, encoding="utf-8")
            analysis = fab_release.analyze_board(board)
        self.assertEqual(analysis.named_nets, {"GND", "I2C_SDA"})

    def test_export_never_invokes_kicad_when_static_gate_fails(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, mock.patch(
            "fab_release.subprocess.run"
        ) as run_mock:
            result = fab_release.main(
                [
                    "export",
                    "--project-root",
                    str(REPO_ROOT),
                    "--output",
                    str(Path(temporary) / "release"),
                ]
            )
        self.assertEqual(result, 2)
        run_mock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
