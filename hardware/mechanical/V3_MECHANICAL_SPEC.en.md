> **Language:** English · [中文](V3_MECHANICAL_SPEC.md)

> English edition of the Chinese document. The Chinese file is authoritative if the two differ.

# MOTO GPS V3 Mechanical Specification (Rev A0)

Status: **appearance direction chosen, mechanical envelope draft, volume production is prohibited**  
Baseline unit: mm  
Related appearance: `renders/rev-a0-exterior-concept-v3-four-screws.png`

What V3 freezes is the front design language: a one-piece continuous aluminium ring, a round black cover glass, four exposed hex-socket screws in a diagonal layout, the button on the right and a narrow amber index mark at the 6 o'clock position. The render is not a dimension reference; this document and the 2D drawings are the source of the mechanical constraints for Rev A0.

## 1. Rev A0 overall envelope

| Item | Rev A0 nominal | Status | Notes |
| --- | ---: | --- | --- |
| Body outer diameter | `Ø61.0` | V1 screen fit value | Derived jointly from the Ø48.96 cover glass, the separate seal groove and the radial allowance for four screws; still requires first-article DFM |
| Aluminium front bezel height | `3.0` | Provisional | Includes the front chamfer, excludes the glass |
| Plastic rear shell depth | `13.0` | Provisional | The front/rear shell mating face is Z=0 and the rear shell extends downwards |
| Assembled body thickness | `16.0` | Provisional | Excludes the Garmin mount; front bezel 3.0 + rear shell 13.0 |
| Garmin mount protrusion | `3.0` | Provisional | Target maximum thickness of body plus mount `19.0`; device-side male tabs |
| Screen cover glass envelope | `Ø48.96 ±0.05 × 1.10` | V1 factory drawing value | Model H0175Y003AMT003 V1; re-measure on arrival |
| Touch visible area | `Ø44.16` | V1 factory drawing value | Reference for masking the front opening |
| AMOLED active display area | `Ø43.76` | V1 factory drawing value | Reference for the 466 × 466 image |
| LCM outline | `45.93 ±0.15 × 46.35 ±0.15 × 0.80 ±0.10` | V1 factory drawing value | A single round glass must not be modelled in place of the whole module |
| Total thickness of full lamination | `2.07 ±0.15` | V1 factory drawing value | CG 1.10 + OCA 0.18 + LCM / polariser etc. |
| PCB | `Ø52.0 × 1.0` | Layout target | Four-layer board; target assembly clearance from board edge to enclosure ≥0.30 per side |
| Battery placeholder | `27 × 32 × 7` | To be frozen against physical parts | Currently the 702530 envelope; must include the protection board, wiring and swelling allowance |
| GNSS antenna reference placeholder | `10 × 10 × 6` | To be frozen after part selection | The antenna and the coaxial bend radius determine the final shape of the rear shell RF area |
| External RF pod fallback envelope | `18 × 14 × 8` | Test fallback | About 69.5 long in total when used; enabled only if the hidden-antenna vehicle test does not pass |

Coordinate convention: viewed from the front, 12 o'clock is `+Y` and 3 o'clock is `+X`; the front/rear shell mating face is `Z=0` and the display face is `+Z`.

## 2. Aluminium front bezel

- Material: 6061-T6; first article CNC, then fine bead blasting and anodising. The colour targets the dark grey of the V3 render, but the colour sample is signed off separately.
- The outline stays a continuous ring; no four-segment armour, fake parting lines or decorative lugs are added.
- Outer diameter provisional `Ø61.0 ±0.05`; concentricity of the screen aperture with the body outer circle `0.05`.
- The front bezel uses a two-level opening: the front visible aperture is provisionally `Ø45.00` and the rear glass pocket is provisionally `Ø49.26`. Against the cover glass maximum material of `Ø49.01` this keeps a nominal 0.125 assembly / adhesive gap per side and forms a support / adhesive shoulder about 2.13 wide. No machining drawing may be issued before the screen sample has been measured.
- The front face uses two shallow chamfers. Rev A0 suggests `0.5 × 45°` on the outer edge and `0.3 × 45°` on the glass side; the first article must be checked for strong-light reflection and glove-touch edges.
- The four screws are load-bearing parts, not decoration. The screw axes lie on `PCD Ø55.40` (R27.70) at angles `45° / 135° / 225° / 315°`; the hole coordinates relative to the body centre are `(±19.587, ±19.587)`, with a position tolerance of `Ø0.10`.
- Screw holes Rev A0: `Ø1.80 +0.10/0` through hole; front `Ø3.30 +0.10/0 × 1.50±0.05` flat-bottom counterbore so that M1.6 low-head hex-socket screws sit nearly flush. The counterbore depth must be revised with the actual screw head height.
- Fastener recommendation: M1.6×6, TC4/Grade 5 titanium, black finish, four from the same batch; the inserts are M1.6 brass blind-bottom heat-set / moulded-in inserts, candidate outer diameter 2.5–2.7 and length 3.0. After fitting to the bike a compass A/B test is still needed to confirm magnetic interference.
- Rear shell screw bosses provisional `Ø4.80`, effective height `≥4.5`, root fillet R0.8–1.0; the PCB reserves R2.70 crescent clearances at the four screw axes.
- Recommended assembly torque `0.12~0.16 N·m`, with the four screws tightened diagonally in two rounds, using a low-strength removable threadlocker. The final value is determined by insert pull-out and thermal cycling tests.

> **Structural risk:** M1.6 has replaced M2 because M2 would squeeze the screen shoulder, the seal and the PCB at the same time. On the Ø61 part the counterbore to the outer edge is nominally 1.15 and the counterbore to the screen cover glass maximum material is about 1.55; the seal groove to the insert hole is still only about 0.275, so first-article DFM must be done. If the minimum rib thickness is insufficient, enlarge the body further; the continuous sealing path must not be cut.

## 3. Screen and touch cover glass

- The screen assembly is fitted from the rear onto the locating shoulder of the aluminium bezel; the front face of the cover glass sits `0.20~0.35` below the highest protective edge, so that the glass is not rubbed directly when laid flat. Taking the 3.0 mm front bezel as the datum, CAD currently places the glass face at Z=2.75 (0.25 lower).
- Keep a `0.10~0.15` per-side thermal expansion / adhesive gap between the glass and the aluminium bezel; the glass must not bear hard against the aluminium bezel.
- Use a continuous die-cut pressure-sensitive waterproof adhesive ring; the Rev A prototype can start from the 3M 9495LE / equivalent 300LSE adhesive family, and the adhesive thickness, overlap width and surface preparation must be verified by immersion and thermal cycling.
- The screen FPC enters the PCB connector area at the 6 o'clock position with a target bend radius of `≥1.0`, and must not be trapped by the battery or the rear cover.
- CAD must represent at the same time the Ø48.96 cover glass, the Ø44.16 touch visible area, the Ø43.76 AMOLED A.A., the 45.93 × 46.35 LCM and the 2.07±0.15 total thickness; a single cylinder must no longer stand in for them.
- The V1 factory drawing gives a local 0.6/1.5 mm component clearance requirement on the rear face, but does not give coordinates for the complete component area; CAD lowers the PCB top face to Z=-1.50, leaving about 0.53 mm nominal height for the worst-case 2.22 mm module and the 1.50 mm local component area, and the complete XY clearance must still wait for the physical part / DXF before sign-off.
- The final length, exit angle, bend radius, Pin 1 and connector contact-face orientation of the main FPC are still listed as physical-part freeze items.

## 4. Rear shell and internal stack-up

- Rear shell material: unfilled PA12 or PC/ABS for prototypes; PC/ABS preferred for volume production. Carbon-fibre, highly glass-filled materials and metallic coatings are prohibited near the GNSS/Wi-Fi antennas.
- Rear shell outer diameter target `Ø61.0`, with no more than `0.15` circumferential mismatch with the front bezel. General dimensional tolerance for prototype prints is managed at `±0.20`, with a `±0.10` target for the production mould.
- Recommended inner diameter of the PCB mounting shoulder `Ø52.6`, leaving a 0.30 per-side clearance for the Ø52 PCB. The PCB is fixed with three insulating M1.6 fastening points or locating posts; the exact angles are frozen with the final component keep-out area.
- The battery must not be clamped between the PCB and the rear shell under assembly preload. Target clearance all around `≥0.5`, soft foam preload `0.2~0.5` in the thickness direction, and allowance for normal cell swelling.
- The battery, PMIC inductor, USB, titanium screws, Garmin mount and handlebar all need to go into the LIS2MDL full-unit soft-iron / hard-iron calibration. The magnetometer is still placed at the 12 o'clock outer edge of the PCB, and steel parts are prohibited in that area of the rear shell.
- The button at 3 o'clock on the right uses a separate silicone cap or membrane; a metal button shaft must not pass straight through the sealed cavity.
- USB-C is at the 6 o'clock position with a provisional opening envelope of `12 × 7`; volume production uses a tethered silicone plug. The opening may only be machined after the physical connector and the plug insertion space are frozen.

## 5. Front/rear shell sealing and the four screws

- The screen adhesive ring and the front/rear shell seal are two independent continuous sealing paths.
- For the front/rear shells prefer a 50A silicone small-cross-section moulded ring of about `ID49.0~49.5 × CS1.0`; target compression `20%~30%`, with the four screws tightened diagonally in two rounds in turn. The exact inner diameter must be recalculated from the physical ring cross-section and the groove fill ratio.
- The Rev A1 groove centre is provisional at `R25.50`, width `1.20±0.05`, depth `0.75±0.03`, cut into the solid annular sealing land of the plastic rear shell; between its inner edge R24.90 and the Ø49.26 pocket a nominal radial 0.27 is kept. The corresponding sealing face on the aluminium front bezel has a flatness of 0.05 and Ra≤1.6.
- As a rule the screw holes lie outside the main sealing path; the rear shell uses blind-bottom threaded insert seats so that the screw holes do not open directly into the electronics cavity. If DFM cannot achieve a blind bottom, a separate seal must be added for each screw; threadlocker must not be relied on for water resistance.
- The rear shell threads use brass heat-set or moulded-in inserts with a minimum effective engagement length of `≥3.0`; the insert outer diameter and hole diameter are redrawn from the actual supplier data.
- The Rev A water-resistance target is rain / spray verification; no IP rating is claimed on the first-article drawings. The rating is defined after immersion, thermal cycling and on-bike vibration testing.

## 6. GNSS and wireless antennas

- The V3 front face has no protruding antenna decoration; this only freezes the appearance and does not mean the GNSS antenna can already be hidden under the aluminium bezel.
- The Rev A PCB keeps a U.FL external active GNSS antenna as the performance baseline. The rear shell reserves a metal-free RF area and a `10 × 10 × 6` antenna placeholder at the 12 o'clock position.
- The hidden solution must be tested separately for cold-start TTFF, C/N0 while riding, off-route reroute continuity and handlebar mounting angle. Until it reaches the external baseline, the `18 × 14 × 8` external plastic RF pod fallback is not dropped.
- The ESP32 2.4 GHz FPC antenna is stuck to the inside of the plastic rear cover, keeping clearance from the battery, the PCB ground copper and the GNSS antenna per their respective datasheets; the exact position is frozen with RF measurements.
- No closed metal enclosure may be formed between the aluminium front bezel and the antenna. No metal nameplate, magnet, steel screw or metallic-effect coating may be added in the rear shell RF area.

## 7. Garmin Edge quarter-turn mount

- The rear of the unit changes to **device-side male tabs**, with the goal of twisting directly into a Garmin Edge standard quarter-turn bike mount; locking is done by aligning the notch, pressing lightly and turning `90°`. A dedicated handlebar clamp is no longer developed separately as the basic solution.
- The mount is still made as a replaceable engineering-plastic part rather than moulded in one piece with the rear shell. When it wears, is damaged in a drop or needs correction for production moulding shrinkage, only the mount part is replaced and not the whole rear shell.
- Rev A0 geometric envelope: central guide post `Ø24.9 × 3.0`, maximum swing diameter of the two side tabs `Ø28.6`, tab band width `11.0`, tab thickness `1.5`, rear shell locating recess `Ø25.4 × 1.2`. These figures are for the geometric model and the first round of prototypes, not Garmin official tolerances.
- The fit reference part is designated as the Garmin original Quarter-turn Bike Mount, P/N `010-11430-00`. Garmin does not publish a complete production tolerance drawing, so the production locking slope, the anti-rotation recess and the injection-moulding shrinkage must be frozen with a physical gauge made from the original mount.
- For the first round of mounts, PA12/MJF or tough engineering-plastic printing is recommended; PLA, brittle resin or metal tabs must not be fitted directly to the bike. The locking area must have a rotation stop, elastic preload and a safety-tether hole, and must not rely on static friction alone.
- Fit verification must cover at least: the original rubber-ring handlebar mount, the original out-front mount and one common third-party mount; complete 20 assembly/removal cycles, static pull-out, torque, on-bike vibration and post-rain re-checks of play.
- If a third-party "Garmin-compatible" mount has tolerances that differ from the original, the original `010-11430-00` is the primary datum and the third-party compatibility range is listed separately; the main mount must not be loosened in reverse to suit it.

## 8. General tolerances and inspection datums

Unless otherwise marked, Rev A0 suggests:

| Category | Suggested value |
| --- | --- |
| CNC linear dimensions ≤60 | `±0.10` |
| CNC critical diameters / hole positions | `±0.05` |
| 3D-printed prototype | `±0.20` |
| Injection-moulded target dimensions | `±0.10~0.15`, recalculated per material shrinkage |
| Front/rear shell circumferential mismatch | `≤0.15` |
| Glass concentricity relative to the outer circle | `≤0.10` |
| Position tolerance of the four screws | `Ø0.10` |
| Minimum CNC rib thickness | `0.60`, target `≥0.80` in load-bearing sealing areas |
| Non-RF plastic wall thickness | `1.5~2.4`, as uniform as possible |

Suggested datums:

- `A`: front/rear shell mating plane;
- `B`: axis of the body Ø61 outer circle;
- `C`: radial plane through the centre of the 6 o'clock USB/FPC.

## 9. Dimensions that must be frozen against physical parts before ordering

If any one of the following is unmeasured, no CNC / mould production drawing may be issued:

1. The screen cover glass's true outer diameter, total thickness, visible area, metal back-plate outline and front/rear tolerances;
2. The FPC exit position, length, bend limits, Pin 1 and connector contact direction;
3. The maximum length, width and thickness of the 702530 battery including the protection board, tape and wire exit, and the permitted swelling;
4. The USB-C receptacle centre height, shell length and common plug envelope;
5. The actual M1.6 titanium screw head diameter / head height and the recommended pilot hole of the threaded insert;
6. The material, thickness and target compression of the screen adhesive and the front/rear shell seal / flat gasket;
7. The physical envelope and minimum bend radius of the GNSS antenna, the U.FL plug and the coaxial cable;
8. The travel and preload of the right-side button, the silicone cap and the internal switch;
9. The locking surface, anti-rotation recess and material shrinkage of the Garmin male tabs, and physical fit verification against the original `010-11430-00` mount;
10. The compass interference, GNSS performance, enclosure temperature, vibration and rain test results after assembly.

## 10. Rev A mechanical acceptance

- The number, angles and visual positions of the four front screws match V3, and serviceable assembly and removal is possible;
- The glass is not hard-pressed, the FPC is not folded to a crease, and the battery takes no structural load;
- After 20 assembly/removal cycles the threaded inserts do not come loose and the sealing faces show no permanent warping;
- After on-bike vibration the unit shows no visible rotation and the Garmin mount does not back out, and the safety tether can carry the load on its own;
- After spray testing there is no water ingress in the electronics cavity, the USB plug or the button area;
- Enclosure temperature at full charging load, battery temperature, GNSS and compass meet the limits of the hardware test plan.

The accompanying 2D engineering drawing frame is in [`drawings/v3-rev-a0-general-arrangement.svg`](drawings/v3-rev-a0-general-arrangement.svg). Dimensions in red in the drawing are all items to be frozen against physical parts.
