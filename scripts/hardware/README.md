# Hardware fabrication release gate

`fab_release.py` prevents a mechanically valid but electrically empty KiCad file
from being mistaken for a production motherboard. It fails closed before invoking
KiCad when it finds a `NOT FOR FABRICATION`/draft marker, too few real circuit
objects, missing footprints or LCSC numbers, unresolved references, or schematic
and PCB reference mismatches.

Check the current design without writing files:

```sh
python3 scripts/hardware/fab_release.py check
```

The current Rev A engineering schematic/PCB is the default input and is expected
to return exit code `2` (`REJECTED`) while it carries physical-sample blockers,
engineering placeholder footprints, incomplete routing, or a `NOT FOR
FABRICATION` marker. A zero-violation ERC/DRC report does not override the
completeness gate. Explicit `--schematic`, `--board` and `--project` arguments
remain available for fixtures and future revisions.

After the real schematic and routed board pass review, create a new, atomic release
directory (the command refuses to overwrite an existing release):

```sh
python3 scripts/hardware/fab_release.py export \
  --output hardware/manufacturing/releases/rev-a1-evt1
```

The repository is pinned to KiCad `10.0.6` at
`/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents/MacOS/kicad-cli`.
On another machine, set `MOTO_GPS_KICAD_CLI` to that build's **absolute** path.
Successful export runs ERC and DRC with errors/warnings fatal, produces Gerber and
Excellon files, JLCPCB BOM/CPL, schematic and assembly PDFs, reconciles all fitted
SMD references, and records hashes in `SHA256SUMS` and `release-manifest.json`.

Run the standard-library tests:

```sh
python3 -m unittest discover -s scripts/hardware/tests -v
```

The numeric minima are only a sanity floor. Passing this tool does not replace
power, RF, thermal, antenna, footprint, DFM, or physical-sample validation.

## Review-only engineering package

While the physical gates remain open, `engineering_candidate.py` can export the
real routed board for inspection without weakening the production gate:

```sh
python3 scripts/hardware/engineering_candidate.py \
  --output hardware/manufacturing/engineering-candidates/rev-a0-20260903
```

It requires the `NOT FOR FABRICATION` markers to be present, writes a prominent
Chinese/English warning, and deliberately does not create a Gerber-only order ZIP.
