# V3 four-screw enclosure freeze

V3 is the selected exterior direction. The front is a single machined aluminium
ring with four exposed functional screws at 45, 135, 225 and 315 degrees. The
rear shell remains RF-transparent plastic and carries a concealed/recessed,
replaceable Garmin Edge-compatible quarter-turn male cleat. The device is
intended to rotate directly into an existing Garmin Edge bicycle mount.

## Fastener decision

The H0175Y003AMT003 V1 cover is `Ø48.96 ±0.05`, so the earlier Ø60 body no
longer preserved the independent glass and housing seals. Rev A1 expands the
body to Ø61 and moves the screws/seal outward. M1.6 remains preferable
to M2 so the screw stays functional without crowding the touch cover.

- fastener: M1.6 × 6 mm, black non-magnetic stainless or titanium;
- nominal head envelope: 3.0 mm;
- front clearance hole: 1.80 mm;
- flat-bottom visible head seat: 3.30 mm × 1.50 mm deep;
- screw pitch radius: 27.70 mm (`PCD 55.40 mm`);
- rear boss: 4.80 mm OD with a provisional 2.65 mm brass-insert pocket;
- PCB consequence: four R2.70 mm edge scallops are required.

The generated Rev A1 validation gives about 1.55 mm between the maximum
Ø49.01 screen cover and the screw head seat and 1.15 mm between the head seat
and the Ø61 body edge. The continuous seal groove retains 0.275 mm to the
blind insert pocket; that small value remains a DFM checkpoint, not a released
production tolerance.

## Screen retention and body seal

The front bezel is a two-level opening rather than a simple ring:

- front visible aperture: 45.00 mm;
- rear glass pocket: 49.26 mm;
- cover glass: 48.96 ±0.05 mm;
- touch visible area: 44.16 mm;
- AMOLED active area: 43.76 mm;
- radial support/adhesive shoulder: 2.13 mm.

The rear shell includes a real split-plane annular land. A provisional
1.20 mm wide × 0.75 mm deep continuous groove is centred at R25.50 mm, inside
the blind screw inserts. The screen adhesive seal and housing seal remain two
independent paths.

The glass face is nominally 0.25 mm below the 3.00 mm protective rim. The PCB
top is lowered to Z=-1.50 mm so the display drawing's worst-case 1.50 mm rear
component zone does not touch the board envelope. Screen FPC and local board
keepouts remain subject to the physical-sample overlay.

## Internal GNSS packaging

The external A0 RF pod has been removed. V3 reserves a 12 × 12 × 7.5 mm RF
keepout at 12 o'clock for a 10 × 10 × 6 mm active GNSS antenna. The battery is
shifted 5 mm toward 6 o'clock, leaving 1.8 mm of nominal Y clearance to the RF
keepout. The antenna sits behind a 1.5 mm plastic rear floor; no copper, battery
foil, magnet, speaker steel or ferromagnetic screw is permitted in the keepout.

The all-internal antenna arrangement must still pass a real cold-start and
tracking test on the motorcycle. If it fails, the mechanical fallback is a
flush plastic RF window or a larger internal antenna—not a return to the A0
external pod without another exterior review.

## Garmin Edge mount decision

V3 no longer uses the generic four-arm quick-release placeholder. The rear
insert is now the device-side male half of the Garmin Edge quarter-turn system:

- guide hub: 24.9 mm OD × 3.0 mm;
- two opposed tabs: 28.6 mm maximum rotational envelope, 11.0 mm strip width,
  1.5 mm thick;
- 90-degree insertion/locking motion;
- replaceable polymer cleat retained independently from the rear housing;
- OEM acceptance gauge: Garmin quarter-turn bike mount P/N 010-11430-00.

Garmin publishes compatibility and installation instructions, but not a full
manufacturing tolerance drawing. The dimensions above are an A0
reverse-engineered envelope. Locking grooves, lead-in radii and moulding shrink
compensation must be frozen by physical gauge fitting to an original Garmin
mount before any production drawing is released. A lanyard remains mandatory
for road testing.

## Output and status

The files under `generated/v3/` are separate from A0:

- `v3_front_bezel.step/.stl`;
- `v3_rear_shell.step/.stl`;
- `v3_mount_insert.step/.stl`;
- `v3_screw_set.step/.stl` (standard-hardware envelope only);
- `moto-gps-v3-assembly.step`;
- `moto-gps-v3-envelope.step`;
- `previews/v3-front.svg/.png`;
- `previews/v3-isometric.svg/.png`;
- `v3-validation.json`.

This is a valid packaging model, not a released waterproof production drawing.
Insert supplier tolerances, gasket compression, USB plug geometry, screen FPC
measurements and CNC tool radii must be frozen before ordering enclosure parts.
