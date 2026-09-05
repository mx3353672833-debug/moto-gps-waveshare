# MOTO GPS Rev A PCB

Status: **ENGINEERING / NOT FOR FABRICATION**

The canonical board is generated from the native schematic and its exact
pad-to-net data; it is not a hand-written zero-item placeholder.

```sh
hardware/rev_a/board/generate_board.sh
hardware/rev_a/board/export_preview.sh
/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents/MacOS/kicad-cli \
  pcb drc --format json --all-track-errors \
  -o hardware/rev_a/board/build/drc.json \
  hardware/rev_a/board/moto-gps-rev-a.kicad_pcb
```

`generate_board.py` creates the four-layer, 1.0 mm, nominal Ø52 mm board with
the four V3 R2.70 screw scallops centred on R27.70 / PCD55.40. It places WROOM-1U left, LC76G/RF right,
IIS2MDC at 12 o'clock, QMI8658A near centre, and USB/power/display FPC at 6
o'clock. Seventeen test pads are on the back service ring. The placement report
is written to `build/placement-report.txt`.

The H0175 EVT A1 population uses QMI8658A `C3021082`, IIS2MDCTR `C2655002`
and Panasonic EVQP7C01P `C388883`. These are controlled EVT choices, not silent
substitutions: firmware identity/interrupt/axis tests, final-assembly compass
calibration, and the P-suffix `1.1 ± 0.1 mm` actuator/enclosure travel check are
release gates. J1's official MPN is `U262-161N-4BVC11`; the `16XN` spelling in
the existing footprint name is retained only for library compatibility.

H0175 EVT A1 follows the controlled display PDF page 7 and directly supplies
TP3.3/IOVCC/VCI/VBAT/VCI_EN from `3V3_DISPLAY`. The assembled board must pass
repeated cold-start, low-temperature and maximum-brightness tests before this
power choice is production-frozen.

The generator prefers the audited project footprints in `MOTO_GPS.pretty`.
When one is absent it embeds a conspicuously marked engineering envelope so the
board can still be density-checked, but the repository fabrication gate rejects
the result. Never infer a production land pattern from a generated envelope.

## Routing workflow

The routed Rev A0 R4 review source is frozen at
`build/release_check_r4/moto-gps-rev-a.kicad_pcb`.  It contains 1555 track
segments and 189 vias.  KiCad 10.0.6 was run with `--all-track-errors`,
`--schematic-parity`, all severities and zone refill; the result is DRC 0,
unconnected 0 and schematic-parity 0.  ERC is also 0.  Reports live in
`../validation/*release-check-r4.json` and the immutable review export lives in
`../manufacturing/engineering-candidates/rev-a0-20260903-r4/`.

`generate_board.py` deliberately remains the deterministic unrouted placement
generator.  The final routed candidate is reconstructed from the saved R15
placement/SES flow plus `repair_r3_route.py`; do not mistake a fresh generator
output for R4 or overwrite the frozen R4 directory.

## Blocking physical inputs

- Original AMOLED FPC contact side, pin 1, thickness, insertion direction,
  connector height and exact MPN.
- Exact USB-C shell/board-edge/Z datum, mating-plug and waterproof-port envelope.
- Second-person pin and land-pattern review for every project footprint.
- GNSS and Wi-Fi antenna/coax/RF-window measurements and controlled-impedance
  stack-up.
- Protected battery, lead, polarity, connector and swelling envelope.
- Charger/buck-boost power coupon, thermal, switchover and 1.5 A load-step tests.
- Display, battery, antenna, buttons and Garmin mount physical interference
  checks in the assembled V3 enclosure.
- QMI8658A firmware identity/interrupt/axis regression, IIS2MDC final-assembly
  calibration, EVQP7C01P button travel, and H0175 direct-3.3 V startup/highlight
  tests.

A clean DRC has now been achieved, but it does not remove these blockers. Fabrication export must go through
`scripts/hardware/fab_release.py`, which currently fails closed by design.
