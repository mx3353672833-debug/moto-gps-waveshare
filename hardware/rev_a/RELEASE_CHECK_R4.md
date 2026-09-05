# Rev A0 R4 engineering release check

Date: 2026-09-03  
Status: **electrically complete review candidate / NOT FOR FABRICATION**

## Outcome

R4 is a real routed four-layer PCB, not the earlier placement-only skeleton.  The
frozen source is `board/build/release_check_r4/` and the immutable review bundle
is `manufacturing/engineering-candidates/rev-a0-20260903-r4/`.

KiCad 10.0.6 results:

- ERC violations: **0**
- DRC violations with `--all-track-errors`: **0**
- unconnected items: **0**
- schematic/PCB parity errors: **0**

Static design metrics:

| Metric | R4 |
| --- | ---: |
| Production schematic symbols / PCB footprints | 93 / 93 |
| Pads | 458 |
| Named nets | 74 |
| Track segments | 1555 |
| Vias | 189 |
| Copper layers | 4 |
| Zones | 2 |

The review export contains 57 grouped BOM rows and 93 CPL placements.  The
fail-closed BOM/CPL reconciliation found no missing, extra, duplicate,
quantity-mismatch or malformed references.  All 58 manifest-listed files in the
candidate package pass SHA-256 verification.

Formal evidence:

- `validation/erc-release-check-r4.json`
- `validation/drc-release-check-r4.json`
- candidate-local `reports/erc.json` and `reports/drc.json`
- candidate-local `engineering-manifest.json` and `SHA256SUMS`

## Why the package still says DO NOT ORDER

Electrical connectivity and CAD-rule closure do not prove that an unmeasured
connector mates, a power stage survives load transients, an RF path meets its
impedance target or a catalogue part is actually available for assembly.
`fab_release.py` therefore rejects R4 by design while these 28 production
references remain gated:

| Gate | Count | References |
| --- | ---: | --- |
| Physical sample / mechanical fit | 9 | J1, J2, J3, SW1–SW4, U1, U6 |
| RF validation | 7 | C31, C37, D2, J4, L3, R33, U10 |
| Electrical coupon / load validation | 5 | L1, L2, U2, U3, U7 |
| Stock recheck | 5 | C16, C17, D1, U4, U8 |
| Source or consign | 2 | R19, U5 |

The review Gerbers and drill files are deliberately not zipped into a
JLCPCB-order archive.  The fabrication-blocking markers must not be removed by
hand.

## Production-release sequence

1. Measure and sign off the purchased display/FPC contact side, Pin 1,
   stiffener thickness, insertion direction and connector height.
2. Freeze the USB-C part, board-edge datum, tongue Z-height, enclosure opening,
   mating-plug and waterproof-plug envelopes.
3. Verify battery connector polarity, NTC curve, protected-cell dimensions,
   lead exit and swelling allowance.
4. Second-person review every custom footprint against the exact manufacturer
   package drawing and the JLCPCB assembly capability.
5. Pass the BQ25628E/TPS63070/TUSB320 power coupon: cold start, USB/battery
   switchover, charge current, 1.5 A step load, protection and sealed-case heat.
6. Freeze the four-layer impedance stack, GNSS/2.4 GHz antenna/coax geometry and
   verify conducted impedance plus assembled C/N0, TTFF and Wi-Fi performance.
7. Recheck all 28 gated parts by exact MPN, manufacturer and package on the
   order date; source/consign QMI8658C and R19 or qualify controlled alternates.
8. Rerun the repository tests and `fab_release.py`.  Only a clean fail-closed
   gate may create the formal fabrication ZIP for the first five boards.

