#!/usr/bin/env python3
"""Export a review-only Rev A engineering package without weakening fab_release.

This command exists so routed copper, drill data, BOM and placement can be
inspected while the physical-sample gates are still open.  It deliberately
requires NOT-FOR-FAB markers and does not create a Gerber-only order ZIP.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
from pathlib import Path
import shutil
import tempfile

from fab_release import (
    PINNED_KICAD_VERSION,
    REPO_ROOT,
    _convert_kicad_cpl,
    _resolve_cli,
    _run,
    _sha256,
    run_static_gate,
)


DEFAULT_SOURCE = Path("hardware/rev_a/board/build/release_check_r4")
ALLOWED_GATE_ERRORS = {
    "FAB_BLOCK_MARKER",
    "PROCUREMENT_STATUS_BLOCKED",
}


def _resolve_design_file(source: Path, suffix: str) -> Path:
    preferred = source / f"moto-gps-rev-a1{suffix}"
    if preferred.is_file():
        return preferred
    legacy = source / f"moto-gps-rev-a{suffix}"
    if legacy.is_file():
        return legacy
    matches = sorted(source.glob(f"*{suffix}"))
    if len(matches) != 1:
        raise RuntimeError(f"expected one {suffix} file in {source}, found {len(matches)}")
    return matches[0]


def _copy_source(source: Path, destination: Path, schematic: Path, board: Path) -> None:
    destination.mkdir(parents=True)
    for item in (
        schematic,
        board,
        board.with_suffix(".kicad_pro"),
        board.with_suffix(".kicad_dru"),
        board.with_suffix(".kicad_prl"),
        source / "fp-lib-table",
        source / "sym-lib-table",
        schematic.with_suffix(".kicad_sym"),
    ):
        if item.is_file():
            shutil.copy2(item, destination / item.name)
    library = source / "MOTO_GPS.pretty"
    if library.is_dir():
        shutil.copytree(library, destination / library.name)


def _write_readme(path: Path) -> None:
    path.write_text(
        "# DO NOT ORDER / 禁止直接下单\n\n"
        "这是 MOTO GPS Rev A 的工程审查包，用于确认真实铜线、钻孔、贴装坐标和结构密度，"
        "不是嘉立创投板放行包。ERC、DRC、未连接及原理图/PCB 一致性可以为零，但以下物理项"
        "尚未完成：\n\n"
        "- 原屏 FPC 接触面、插入方向、厚度和 Pin 1 实物确认；\n"
        "- USB-C 与外壳开口、PCB Z 高、插头包络确认；\n"
        "- 所有自建封装第二人逐脚签核；\n"
        "- 充电/升降压电源券和密封温升测试；\n"
        "- GNSS/Wi-Fi 天线、阻抗叠层和整机 RF 测试；\n"
        "- 电池、屏幕、按键、螺钉及佳明卡口整机 3D/实物干涉检查。\n\n"
        "`review_gerbers_DO_NOT_ORDER/` 只供 CAM/DFM 预审。本目录故意不生成可直接上传的"
        "Gerber ZIP；正式文件只能由 `fab_release.py export` 在全部门槛关闭后生成。\n",
        encoding="utf-8",
    )


def export_candidate(source: Path, output: Path, cli_argument: str | None) -> None:
    schematic = _resolve_design_file(source, ".kicad_sch")
    board = _resolve_design_file(source, ".kicad_pcb")
    report = run_static_gate(schematic, board)
    unexpected = [issue for issue in report.errors if issue.code not in ALLOWED_GATE_ERRORS]
    if unexpected:
        raise RuntimeError(
            "engineering source failed non-waivable completeness checks: "
            + "; ".join(f"{issue.code}: {issue.message}" for issue in unexpected)
        )
    if not report.schematic.markers or not report.board.markers:
        raise RuntimeError("candidate exporter requires fabrication-blocking markers in both sources")
    if output.exists():
        raise RuntimeError(f"output already exists (refusing to overwrite): {output}")

    cli = _resolve_cli(cli_argument)
    version = _run([str(cli), "--version"])
    if version != PINNED_KICAD_VERSION:
        raise RuntimeError(f"KiCad version mismatch: got {version}, expected {PINNED_KICAD_VERSION}")

    output.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".moto-gps-engineering-", dir=output.parent))
    try:
        source_out = stage / "source"
        reports = stage / "reports"
        documents = stage / "documents"
        previews = stage / "previews"
        fabrication = stage / "review_gerbers_DO_NOT_ORDER"
        references = stage / "references"
        for directory in (reports, documents, previews, fabrication, references):
            directory.mkdir(parents=True, exist_ok=True)
        _copy_source(source, source_out, schematic, board)

        candidate_schematic = source_out / schematic.name
        candidate_board = source_out / board.name
        _run(
            [
                str(cli), "sch", "erc", str(candidate_schematic),
                "--output", str(reports / "erc.json"), "--format", "json",
                "--severity-all", "--exit-code-violations",
            ],
            cwd=source_out,
        )
        _run(
            [
                str(cli), "pcb", "drc", str(candidate_board),
                "--output", str(reports / "drc.json"), "--format", "json",
                "--all-track-errors", "--schematic-parity", "--severity-all",
                "--exit-code-violations", "--refill-zones", "--save-board",
            ],
            cwd=source_out,
        )

        layers = report.board.copper_layers + [
            "F.Paste", "B.Paste", "F.SilkS", "B.SilkS", "F.Mask", "B.Mask", "Edge.Cuts"
        ]
        _run(
            [
                str(cli), "pcb", "export", "gerbers", str(candidate_board),
                "--output", str(fabrication), "--layers", ",".join(layers),
                "--precision", "6", "--check-zones",
            ],
            cwd=source_out,
        )
        _run(
            [
                str(cli), "pcb", "export", "drill", str(candidate_board),
                "--output", str(fabrication), "--format", "excellon",
                "--excellon-units", "mm", "--excellon-separate-th",
                "--generate-map", "--map-format", "pdf", "--generate-report",
                "--report-path", str(reports / "drill-report.txt"),
            ],
            cwd=source_out,
        )
        _run(
            [
                str(cli), "sch", "export", "bom", str(candidate_schematic),
                "--output", str(documents / "engineering-bom-review.csv"),
                "--fields", "Value,Reference,Footprint,LCSC Part #,Manufacturer,Manufacturer Part Number,Procurement Status,QUANTITY,DNP",
                "--labels", "Comment,Designator,Footprint,LCSC Part #,Manufacturer,Manufacturer Part Number,Procurement Status,Quantity,DNP",
                "--group-by", "Value,Footprint,LCSC Part #,Manufacturer,Manufacturer Part Number,Procurement Status,DNP", "--sort-field", "Reference",
                "--exclude-dnp", "--ref-range-delimiter", "",
            ],
            cwd=source_out,
        )
        raw_position = documents / "kicad-position-raw.csv"
        _run(
            [
                str(cli), "pcb", "export", "pos", str(candidate_board),
                "--output", str(raw_position), "--format", "csv", "--units", "mm",
                "--side", "both", "--smd-only", "--exclude-dnp",
            ],
            cwd=source_out,
        )
        _convert_kicad_cpl(raw_position, documents / "engineering-cpl-review.csv")

        _run(
            [str(cli), "sch", "export", "pdf", str(candidate_schematic), "--output", str(documents / "schematic.pdf")],
            cwd=source_out,
        )
        for side, layers_arg, mirror in (
            ("front", "F.Fab,F.SilkS,Edge.Cuts", False),
            ("back", "B.Fab,B.SilkS,Edge.Cuts", True),
        ):
            command = [
                str(cli), "pcb", "export", "pdf", str(candidate_board),
                "--output", str(documents / f"pcb-{side}-assembly.pdf"),
                "--layers", layers_arg, "--mode-single", "--black-and-white",
                "--sketch-pads-on-fab-layers", "--check-zones",
            ]
            if mirror:
                command.append("--mirror")
            _run(command, cwd=source_out)

        for side, layers_arg, mirror in (
            ("front", "F.Cu,F.Mask,F.SilkS,Edge.Cuts", False),
            ("back", "B.Cu,B.Mask,B.SilkS,Edge.Cuts", True),
        ):
            command = [
                str(cli), "pcb", "export", "svg", str(candidate_board),
                "--output", str(previews / f"pcb-{side}-copper.svg"),
                "--layers", layers_arg, "--mode-single", "--fit-page-to-board",
                "--exclude-drawing-sheet", "--check-zones",
            ]
            if mirror:
                command.append("--mirror")
            _run(command, cwd=source_out)
        for side in ("top", "bottom"):
            _run(
                [
                    str(cli), "pcb", "render", str(candidate_board),
                    "--output", str(previews / f"pcb-{side}-3d.png"),
                    "--side", side, "--width", "1800", "--height", "1800",
                    "--quality", "high", "--background", "transparent", "--zoom", "1.1",
                ],
                cwd=source_out,
            )

        reference_root = REPO_ROOT / "hardware/rev_a"
        for item in (
            reference_root / "references/ESP32-S3-Touch-AMOLED-1.75C-schematic.pdf",
            reference_root / "references/XUNPU-FPC-0.3FX-31PWBH10-C5343228.pdf",
            reference_root / "references/H0175Y003AMT003_V1.pdf",
            reference_root / "validation/fpc-footprint-audit.txt",
            reference_root / "validation/drc-h0175-a1-parity.json",
            reference_root / "validation/erc-h0175-a1-standalone.json",
            reference_root / "schematic/schematic-components.csv",
        ):
            if item.is_file():
                shutil.copy2(item, references / item.name)

        _write_readme(stage / "README_DO_NOT_ORDER.md")
        files = sorted(path for path in stage.rglob("*") if path.is_file())
        manifest = {
            "schema": "moto-gps-engineering-candidate/v1",
            "status": "NOT_FOR_FABRICATION",
            "created_utc": dt.datetime.now(dt.timezone.utc).isoformat(),
            "kicad_version": version,
            "metrics": report.metrics(),
            "allowed_static_gate_errors": [
                {"code": issue.code, "message": issue.message} for issue in report.errors
            ],
            "files": {str(path.relative_to(stage)): _sha256(path) for path in files},
        }
        (stage / "engineering-manifest.json").write_text(
            json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        checksummed = sorted(path for path in stage.rglob("*") if path.is_file())
        (stage / "SHA256SUMS").write_text(
            "".join(f"{_sha256(path)}  {path.relative_to(stage)}\n" for path in checksummed),
            encoding="utf-8",
        )
        stage.rename(output)
    except Exception:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project-root", type=Path, default=REPO_ROOT)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--kicad-cli")
    args = parser.parse_args()
    root = args.project_root.resolve()
    source = args.source if args.source.is_absolute() else root / args.source
    output = args.output if args.output.is_absolute() else root / args.output
    try:
        export_candidate(source.resolve(), output.resolve(), args.kicad_cli)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"ENGINEERING EXPORT ERROR: {error}")
        return 3
    print(f"ENGINEERING CANDIDATE CREATED: {output.resolve()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
