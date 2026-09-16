> **Language:** English · [中文](README.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS in-house circuit board and enclosure

This directory keeps the completed main-board design, manufacturing review files, enclosure models
and verification records. The current software prototype uses the Waveshare production board; the
in-house main board and enclosure are archived / paused. The complete material is published for
reading, research and engineering review.

## Where to start

| Goal | Recommended file | Notes |
| --- | --- | --- |
| View the product parameters | [Parameter comparison](../docs/PRODUCT_SPECIFICATIONS.en.md) | Waveshare prototype and in-house A1 listed separately |
| Open the latest in-house PCB | [A1 KiCad project](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/source/moto-gps-rev-a1.kicad_pro) | Download the whole source directory and keep the symbol and footprint libraries |
| Read the A1 electrical state | [A1 status sheet](rev_a/H0175_EVT_A1_STATUS.en.md) | Components, interfaces, ERC/DRC and items still to be verified |
| View the BOM, CPL and assembly drawings | [A1 documents](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/documents/) | Schematic PDF, assembly PDF, component list and coordinates |
| View Gerber / drill files | [A1 review manufacturing files](manufacturing/engineering-candidates/rev-a1-h0175-evt1-20260903/review_gerbers_DO_NOT_ORDER/) | Keeps the engineering review marking; not yet released for production |
| Compare with the previous PCB revision | [Rev A0 R4](manufacturing/engineering-candidates/rev-a0-20260903-r4/) | The frozen routing baseline for A1 |
| Edit/print the enclosure prototype | [V3 STEP and STL](mechanical/generated/v3/) | Includes front bezel, rear shell, Garmin mount and assembly model |
| Check the mechanical dimensions | [V3 mechanical specification](mechanical/V3_MECHANICAL_SPEC.en.md), [2D drawings](mechanical/drawings/) | Main body Ø61 × 16 mm, about 19 mm including the mount; both are design values |
| Read the complete proposal | [Technical proposal PDF / Word](../docs/technical-proposal/README.en.md) | Complete material for both A1 and A0 |

The complete candidate packages for A1 and R4 both contain `SHA256SUMS`; you can verify them by
running `shasum -a 256 -c SHA256SUMS` in each candidate package directory. The footprint, routing and
rule-check records are provided with the package.
Prefer these complete candidate packages; the `pcb/` directory kept below belongs to earlier
board-outline research.

## Early directories and design records

This directory carries the MOTO GPS in-house all-in-one main board, manufacturing files and enclosure
CAD. For the design baseline see the
[in-house all-in-one hardware and enclosure execution plan](../docs/CUSTOM_HARDWARE_PLAN.en.md).

Planned structure:

```text
hardware/
  pcb/
    moto-gps-rev-a.kicad_pro
    moto-gps-rev-a.kicad_sch
    moto-gps-rev-a.kicad_pcb
  libraries/       reviewed in-house symbols, footprints and 3D models
  mechanical/      parametric enclosure, STEP, STL and 2D fabrication drawings
  manufacturing/   Gerber, drill files, BOM, CPL and assembly instructions
  validation/      power-up, RF, GNSS, compass, power consumption and environmental test records
```

Until the screen FPC, the battery, the GNSS antenna and the handlebar mount are all four frozen, any
file in `manufacturing/` can only be marked as a draft and must not be ordered at volume directly.

The CAD tool chain is KiCad 10.0.6. The early files under `pcb/` are the 52 mm round-board mechanical
envelope and blank schematic skeletons.
The later `rev_a/` and the manufacturing candidate packages already contain real routing and a
complete electrical design; the zero-violation reports of the two kinds of file do not mean the same
thing, so check both the version and the engineering content when you look at them.

Key interfaces frozen / still to freeze:

- [Screen and touch FPC interface](pcb/DISPLAY_INTERFACE.en.md): the electrical mapping is organised, but the connector contact faces, Pin 1 and the FPC thickness still have to be confirmed on a physical original screen.
- [LC76G and GNSS RF interface](pcb/GNSS_INTERFACE.en.md): bare module, low-noise supply, UART/PPS, U.FL active-antenna baseline and the plastic RF window constraint.
- [Power architecture freeze conditions](pcb/POWER_DECISION.en.md): the main solution has changed to BQ25628E + TPS63070 + TUSB320LAI, and an independent power test coupon must pass first before a complete main board.
- [Rev A GPIO budget](pcb/REV_A_PIN_BUDGET.en.md).
- [Rev A0 parametric enclosure](mechanical/README.md): aluminium front bezel, plastic rear shell, a separate RF bay and a replaceable handlebar mount concept part.
