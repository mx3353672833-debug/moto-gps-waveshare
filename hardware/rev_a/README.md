# Rev A integrated board

This directory contains the replacement for the old mechanical-only board skeleton.

- `ARCHITECTURE_FREEZE.md` records the electrical and mechanical decisions.
- `schematic/` contains the electrically complete KiCad schematic and project-local
  symbols/footprints.
- `board/` contains the four-layer Ø52 mm PCB and deterministic generation helpers.
- `validation/` receives ERC, DRC and semantic audit reports.
- `manufacturing/` is generated only after the release gates pass.

The latest frozen electrical review source is
`board/build/release_check_r4/`.  Its KiCad 10.0.6 evidence is
`validation/erc-release-check-r4.json` and
`validation/drc-release-check-r4.json`: ERC 0, DRC 0, unconnected 0 and
schematic-parity 0.  The immutable review-only manufacturing bundle is
`../manufacturing/engineering-candidates/rev-a0-20260903-r4/`.  It contains real
routed copper and manufacturing outputs, but remains **NOT FOR FABRICATION**
until the physical, power, RF and procurement gates are closed.

The legacy files under `hardware/pcb/` remain as a record of the envelope study; they
are not a fabrication source.

The later H0175 EVT A1 candidate is in
[`../manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/`](../manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/).
See [H0175_EVT_A1_STATUS.md](H0175_EVT_A1_STATUS.md) for the screen and component changes.
Both frozen input projects are retained under `board/build/`; intermediate autorouting experiments are omitted.
