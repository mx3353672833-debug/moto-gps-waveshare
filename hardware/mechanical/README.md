# Rev A mechanical work

The selected exterior direction is **V3**, using the continuous aluminium front ring with four functional diagonal fasteners. See:

- [`V3_MECHANICAL_SPEC.md`](V3_MECHANICAL_SPEC.md) for dimensions, materials, sealing, stack-up, fasteners, RF and validation constraints;
- [`V3_DESIGN.md`](V3_DESIGN.md) for the V3 CAD design decisions and calculated clearances;
- [`drawings/v3-rev-a0-general-arrangement.svg`](drawings/v3-rev-a0-general-arrangement.svg) and [`PDF`](drawings/v3-rev-a0-general-arrangement.pdf) for the preliminary two-dimensional general arrangement;
- [`renders/rev-a0-exterior-concept-v3-four-screws.png`](renders/rev-a0-exterior-concept-v3-four-screws.png) for exterior intent only.

The V3 render does not override measured dimensions, and its apparently hidden GNSS antenna is not yet an RF-validated structure.

`rev_a0_case.py` is a parametric build123d interference model. It establishes the current concept envelope:

- 60 mm main body;
- 52 mm PCB;
- 48.16 mm touch/cover envelope and 44.16 mm active display;
- 702530 battery envelope;
- aluminium front bezel plus a plastic RF rear shell;
- removable plastic RF pod for a reliable 10 × 10 mm GNSS antenna baseline;
- downward USB opening and a replaceable Garmin Edge-compatible quarter-turn
  rear male cleat.

Generate the concept files with a Python 3.12 environment containing `build123d`:

```sh
build/cad-venv312/bin/python hardware/mechanical/rev_a0_case.py
```

The script exports these manufacturable concept parts in both STEP and STL:

- `generated/front_bezel.*`
- `generated/rear_shell.*`
- `generated/rf_pod.*`
- `generated/mount_insert.*`

It also exports `generated/moto-gps-rev-a0-envelope.step` for packaging review. The display, PCB, battery and antenna volumes inside that compound are clearance envelopes, not parts to manufacture.

The generated isometric packaging preview can be opened directly in a browser:

[Rev A0 isometric SVG](generated/previews/rev-a0-isometric.svg)

![Rev A0 isometric packaging preview](generated/previews/rev-a0-isometric.svg)

The output is deliberately marked A0. It is suitable for volume/interference review only. It is not a waterproofing, CNC, injection-moulding or production-tolerance drawing until the exact screen, battery, antenna, USB connector, gasket and handlebar orientation are frozen.

## V3 selected four-screw direction

`rev_v3_case.py` preserves the A0 source and creates a separate V3 model for
the exterior selected after concept review. V3 Rev A1 is regenerated around
the H0175Y003AMT003 V1 screen: a 61 mm body, 48.96 mm cover, 44.16 mm touch
viewing area, 43.76 mm AMOLED active area, and a 49.26 mm rear glass pocket.
V3 changes the enclosure to:

- one continuous, unsegmented aluminium front ring;
- four functional diagonal M1.6 fasteners and four matching rear insert bosses;
- a continuous face seal inside the screw pattern;
- an internal 12-o'clock GNSS antenna/keepout behind the plastic rear shell;
- no external RF pod;
- the recessed, replaceable Garmin Edge-compatible rear quarter-turn cleat.

Generate and validate V3 with:

```sh
build/cad-venv312/bin/python hardware/mechanical/rev_v3_case.py
```

The isolated output is under `generated/v3/`. `v3-validation.json` records
OpenCascade validity, bounding boxes, screen-to-fastener radial clearance,
face-seal clearance and battery/GNSS packaging gaps.

Primary exchange files:

- [`generated/v3/moto-gps-v3-assembly.step`](generated/v3/moto-gps-v3-assembly.step) — enclosure and standard-hardware assembly;
- [`generated/v3/moto-gps-v3-envelope.step`](generated/v3/moto-gps-v3-envelope.step) — assembly plus screen, PCB, battery and antenna envelopes;
- [`generated/v3/v3_front_bezel.step`](generated/v3/v3_front_bezel.step) — stepped CNC front bezel;
- [`generated/v3/v3_rear_shell.step`](generated/v3/v3_rear_shell.step) — rear shell with seal land, continuous groove and blind insert bosses;
- [`generated/v3/v3-validation.json`](generated/v3/v3-validation.json) — regenerated geometry checks.

[V3 front view](generated/v3/previews/v3-front.svg)

![V3 front view](generated/v3/previews/v3-front.svg)

[V3 isometric packaging view](generated/v3/previews/v3-isometric.svg)

![V3 isometric packaging view](generated/v3/previews/v3-isometric.svg)
