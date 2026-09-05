"""MOTO GPS V3 four-screw enclosure packaging model.

V3 freezes the selected exterior direction: a continuous aluminium front ring,
four functional diagonal screws, a plastic RF-transparent rear shell and a
concealed Garmin Edge-compatible quarter-turn male cleat.  The GNSS antenna is
now inside the body at 12 o'clock; there is no external antenna pod.

This is a dimensional packaging model, not a released tolerance drawing.
"""

from __future__ import annotations

import json
import math
import shutil
import subprocess
from pathlib import Path

from build123d import (
    Align,
    Axis,
    Box,
    Compound,
    Cylinder,
    ExportSVG,
    LineType,
    Part,
    Pos,
    Unit,
    export_step,
    export_stl,
    fillet,
)


# All dimensions are millimetres. Z=0 is the front/rear housing interface.
BODY_OD = 61.0
PCB_OD = 52.0
PCB_THICKNESS = 1.0
PCB_TOP_Z = -1.5
REAR_DEPTH = 13.0
REAR_WALL = 2.4
REAR_FLOOR = 1.5
FRONT_BEZEL_HEIGHT = 3.0
SCREEN_COVER_OD = 48.96
SCREEN_COVER_OD_TOLERANCE = 0.05
SCREEN_TOUCH_VISIBLE_OD = 44.16
SCREEN_ACTIVE_OD = 43.76
SCREEN_COVER_THICKNESS = 1.1
SCREEN_TOTAL_THICKNESS = 2.07
SCREEN_TOTAL_THICKNESS_TOLERANCE = 0.15
SCREEN_LCM_X = 45.93
SCREEN_LCM_Y = 46.35
SCREEN_LCM_THICKNESS = 0.8
SCREEN_FACE_Z = 2.75
SCREEN_VISIBLE_OPENING_OD = 45.0
SCREEN_POCKET_OD = 49.26
SCREEN_POCKET_DEPTH = 2.75

# Four M1.6 x 6 screws sit at 45/135/225/315 degrees. M2 heads consume too
# much of the radial band between the H0175 cover and the body OD.
SCREW_NOMINAL = 1.6
SCREW_CLEARANCE_OD = 1.8
SCREW_HEAD_OD = 3.0
SCREW_COUNTERBORE_OD = 3.3
SCREW_COUNTERBORE_DEPTH = 1.5
SCREW_HEAD_HEIGHT = 1.5
SCREW_RADIUS = 27.7
SCREW_ANGLES_DEG = (45.0, 135.0, 225.0, 315.0)
INSERT_POCKET_OD = 2.65
INSERT_POCKET_DEPTH = 3.2
SCREW_TIP_CLEARANCE_OD = 1.9
SCREW_TIP_CLEARANCE_DEPTH = 4.8
BOSS_OD = 4.8
BOSS_HEIGHT = REAR_DEPTH - REAR_FLOOR

# The face seal is wholly inside the screw bosses so a continuous gasket path
# is preserved. Final groove width/depth must be matched to the chosen gasket.
FACE_SEAL_CENTER_RADIUS = 25.5
FACE_SEAL_GROOVE_WIDTH = 1.2
FACE_SEAL_INNER_OD = 2 * (FACE_SEAL_CENTER_RADIUS - FACE_SEAL_GROOVE_WIDTH / 2)
FACE_SEAL_OUTER_OD = 2 * (FACE_SEAL_CENTER_RADIUS + FACE_SEAL_GROOVE_WIDTH / 2)
FACE_SEAL_DEPTH = 0.75
FACE_SEAL_LAND_INNER_OD = 47.5
FACE_SEAL_LAND_HEIGHT = 1.5

BATTERY_X = 27.0
BATTERY_Y = 32.0
BATTERY_Z = 7.0
BATTERY_CENTER_Y = -5.0
BATTERY_CENTER_Z = -6.4

# Internal active patch antenna at 12 o'clock. The larger keepout prohibits
# copper, battery metal, magnets and steel fasteners around the antenna.
GNSS_ANT_X = 10.0
GNSS_ANT_Y = 10.0
GNSS_ANT_Z = 6.0
GNSS_ANT_CENTER_Y = 18.8
GNSS_ANT_CENTER_Z = -8.0
GNSS_KEEPOUT_X = 12.0
GNSS_KEEPOUT_Y = 12.0
GNSS_KEEPOUT_Z = 7.5

# Garmin does not publish a production tolerance drawing for the Edge
# quarter-turn interface.  These A0 dimensions reproduce the established
# reverse-engineered envelope (24.9 mm hub, 28.6 mm tab diameter, 11 mm tab
# strip) and MUST be gauge-fitted to an original Garmin 010-11430-00 mount
# before any production release.  The cleat remains a replaceable part so the
# housing is not scrapped when the locking groove or shrink compensation is
# tuned.
GARMIN_HUB_OD = 24.9
GARMIN_HUB_HEIGHT = 3.0
GARMIN_TAB_OD = 28.6
GARMIN_TAB_WIDTH = 11.0
GARMIN_TAB_THICKNESS = 1.5
GARMIN_RECESS_OD = 25.4
GARMIN_RECESS_DEPTH = 1.2


def z_cylinder(diameter: float, height: float, z_min: float) -> Part:
    """Create a Z-axis cylinder with an explicit lower Z face."""

    return Pos(0, 0, z_min) * Cylinder(
        diameter / 2,
        height,
        align=(Align.CENTER, Align.CENTER, Align.MIN),
    )


def annulus(outer_diameter: float, inner_diameter: float, height: float, z_min: float) -> Part:
    return z_cylinder(outer_diameter, height, z_min) - z_cylinder(inner_diameter, height, z_min)


def screw_xy() -> list[tuple[float, float]]:
    return [
        (
            SCREW_RADIUS * math.cos(math.radians(angle)),
            SCREW_RADIUS * math.sin(math.radians(angle)),
        )
        for angle in SCREW_ANGLES_DEG
    ]


def make_front_bezel() -> Part:
    """Continuous stepped aluminium bezel with a real glass support shoulder."""

    # The H0175Y003AMT003 V1 cover is inserted from the rear into a 49.26 mm
    # pocket. A smaller 45.00 mm front aperture leaves a 2.12 mm radial
    # support/adhesive shoulder. The face datum places the glass 0.30 mm below
    # the 3.00 mm protective rim.
    bezel = z_cylinder(BODY_OD, FRONT_BEZEL_HEIGHT, 0.0)
    bezel = fillet(bezel.edges(), radius=0.5)
    bezel -= z_cylinder(SCREEN_POCKET_OD, SCREEN_POCKET_DEPTH + 0.1, -0.1)
    bezel -= z_cylinder(
        SCREEN_VISIBLE_OPENING_OD,
        FRONT_BEZEL_HEIGHT - SCREEN_POCKET_DEPTH + 0.2,
        SCREEN_POCKET_DEPTH - 0.1,
    )
    for x, y in screw_xy():
        # Through clearance plus a flat-bottom head seat. The black head is
        # flush with the face but remains visually exposed as V3 requires.
        bezel -= Pos(x, y, -0.1) * Cylinder(
            SCREW_CLEARANCE_OD / 2,
            FRONT_BEZEL_HEIGHT + 0.2,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        bezel -= Pos(x, y, FRONT_BEZEL_HEIGHT - SCREW_COUNTERBORE_DEPTH) * Cylinder(
            SCREW_COUNTERBORE_OD / 2,
            SCREW_COUNTERBORE_DEPTH + 0.1,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
    return bezel


def make_rear_shell() -> Part:
    """Plastic rear shell with insert bosses and no external RF pod."""

    outer = z_cylinder(BODY_OD, REAR_DEPTH, -REAR_DEPTH)
    cavity = z_cylinder(BODY_OD - 2 * REAR_WALL, REAR_DEPTH, -REAR_DEPTH + REAR_FLOOR)
    shell = outer - cavity

    # Add an inward annular flange at the split plane. Without this land an
    # O-ring drawn near R25 would sit over the open electronics cavity rather
    # than on material. The PCB top face terminates at the underside of land.
    shell += annulus(
        BODY_OD,
        FACE_SEAL_LAND_INNER_OD,
        FACE_SEAL_LAND_HEIGHT,
        -FACE_SEAL_LAND_HEIGHT,
    )

    # Four reinforced posts merge into the perimeter wall. Blind pockets accept
    # M1.6 heat-set or moulded brass inserts from the split-plane side.
    for x, y in screw_xy():
        boss = Pos(x, y, -BOSS_HEIGHT) * Cylinder(
            BOSS_OD / 2,
            BOSS_HEIGHT,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        shell += boss
        shell -= Pos(x, y, -INSERT_POCKET_DEPTH) * Cylinder(
            INSERT_POCKET_OD / 2,
            INSERT_POCKET_DEPTH + 0.1,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        # A smaller blind continuation prevents the nominal M1.6x6 screw from
        # bottoming out below the 3 mm insert while retaining boss wall.
        shell -= Pos(x, y, -SCREW_TIP_CLEARANCE_DEPTH) * Cylinder(
            SCREW_TIP_CLEARANCE_OD / 2,
            SCREW_TIP_CLEARANCE_DEPTH - INSERT_POCKET_DEPTH + 0.1,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )

    # Cut the groove after adding the bosses so the seal path remains
    # continuous. It passes just inside the blind insert pockets.
    shell -= annulus(
        FACE_SEAL_OUTER_OD,
        FACE_SEAL_INNER_OD,
        FACE_SEAL_DEPTH,
        -FACE_SEAL_DEPTH,
    )

    # Six-o'clock USB opening envelope. A moulded silicone plug remains a
    # separate sealing part after connector dimensions are frozen.
    shell -= Pos(0, -31.0, -5.0) * Box(12.0, 7.0, 6.0)

    # Concealed/recessed Garmin Edge cleat seat; invisible from the face.  The
    # replaceable cleat is retained from inside after the physical Garmin gauge
    # fit freezes the screw pattern and locking-groove geometry.
    shell -= z_cylinder(
        GARMIN_RECESS_OD,
        GARMIN_RECESS_DEPTH,
        -REAR_DEPTH - 0.01,
    )
    return shell


def make_mount_insert() -> Part:
    """Replaceable Garmin Edge-compatible device-side male cleat envelope.

    The two opposed tabs enter the notches in a Garmin Edge bicycle mount and
    rotate one quarter turn under its retaining lip.  Locking detents and final
    moulding shrink compensation are deliberately release-gated by an OEM mount
    gauge test; this solid is a mechanically useful A0 envelope, not a claim of
    Garmin-certified geometry.
    """

    z_min = -REAR_DEPTH - GARMIN_HUB_HEIGHT
    hub = z_cylinder(GARMIN_HUB_OD, GARMIN_HUB_HEIGHT, z_min)
    tab_disc = z_cylinder(GARMIN_TAB_OD, GARMIN_TAB_THICKNESS, z_min)
    tab_strip = Pos(0, 0, z_min) * Box(
        GARMIN_TAB_OD + 2.0,
        GARMIN_TAB_WIDTH,
        GARMIN_TAB_THICKNESS,
        align=(Align.CENTER, Align.CENTER, Align.MIN),
    )
    tabs = tab_disc & tab_strip
    insert = hub + tabs
    return fillet(insert.edges().filter_by(Axis.Z), radius=0.45)


def make_screw_set() -> Compound:
    """Standard-hardware envelope for four M1.6 x 6 socket/button screws."""

    screws: list[Part] = []
    for x, y in screw_xy():
        shank = Pos(x, y, -4.5) * Cylinder(
            SCREW_NOMINAL / 2,
            6.0,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        head = Pos(x, y, FRONT_BEZEL_HEIGHT - SCREW_HEAD_HEIGHT) * Cylinder(
            SCREW_HEAD_OD / 2,
            SCREW_HEAD_HEIGHT,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )
        screws.append(shank + head)
    return Compound(children=screws)


def make_envelopes() -> dict[str, Part]:
    """Non-manufactured solids used only for packaging/interference checks."""

    pcb = z_cylinder(PCB_OD, PCB_THICKNESS, PCB_TOP_Z - PCB_THICKNESS)
    # The board needs four edge scallops around the rear insert bosses.
    for x, y in screw_xy():
        pcb -= Pos(x, y, PCB_TOP_Z - PCB_THICKNESS - 0.1) * Cylinder(
            5.4 / 2,
            PCB_THICKNESS + 0.2,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        )

    screen_back_z = SCREEN_FACE_Z - SCREEN_TOTAL_THICKNESS
    return {
        "screen_cover": z_cylinder(
            SCREEN_COVER_OD,
            SCREEN_COVER_THICKNESS,
            SCREEN_FACE_Z - SCREEN_COVER_THICKNESS,
        ),
        "screen_touch_visible": z_cylinder(
            SCREEN_TOUCH_VISIBLE_OD,
            0.05,
            SCREEN_FACE_Z,
        ),
        "screen_active": z_cylinder(
            SCREEN_ACTIVE_OD,
            0.05,
            SCREEN_FACE_Z + 0.01,
        ),
        "screen_lcm": Pos(0, 0, screen_back_z) * Box(
            SCREEN_LCM_X,
            SCREEN_LCM_Y,
            SCREEN_LCM_THICKNESS,
            align=(Align.CENTER, Align.CENTER, Align.MIN),
        ),
        "pcb_with_boss_notches": pcb,
        "battery": Pos(0, BATTERY_CENTER_Y, BATTERY_CENTER_Z) * Box(
            BATTERY_X,
            BATTERY_Y,
            BATTERY_Z,
        ),
        "gnss_antenna": Pos(0, GNSS_ANT_CENTER_Y, GNSS_ANT_CENTER_Z) * Box(
            GNSS_ANT_X,
            GNSS_ANT_Y,
            GNSS_ANT_Z,
        ),
        "gnss_rf_keepout": Pos(0, GNSS_ANT_CENTER_Y, GNSS_ANT_CENTER_Z) * Box(
            GNSS_KEEPOUT_X,
            GNSS_KEEPOUT_Y,
            GNSS_KEEPOUT_Z,
        ),
    }


def export_shape(shape: Part | Compound, output_stem: Path) -> None:
    export_step(shape, output_stem.with_suffix(".step"))
    export_stl(
        shape,
        output_stem.with_suffix(".stl"),
        tolerance=0.05,
        angular_tolerance=0.15,
    )


def export_preview(
    parts: list[Part | Compound],
    output_path: Path,
    viewport_origin: tuple[float, float, float] = (105, -120, 85),
    viewport_up: tuple[float, float, float] = (0, 0, 1),
    look_at: tuple[float, float, float] = (0, 0, -5),
) -> None:
    """Export an isometric line-art preview showing hidden packaging edges."""

    assembly = Compound(children=parts)
    visible, hidden = assembly.project_to_viewport(
        viewport_origin=viewport_origin,
        viewport_up=viewport_up,
        look_at=look_at,
    )
    drawing = ExportSVG(unit=Unit.MM, scale=4.0, margin=6.0, line_weight=0.3)
    drawing.add_layer(
        "hidden",
        line_color=(125, 141, 150),
        line_weight=0.18,
        line_type=LineType.DASHED,
    )
    drawing.add_layer(
        "visible",
        line_color=(25, 137, 196),
        line_weight=0.35,
        line_type=LineType.CONTINUOUS,
    )
    drawing.add_shape(hidden, layer="hidden")
    drawing.add_shape(visible, layer="visible")
    drawing.write(output_path)


def rasterize_svg(svg_path: Path, png_path: Path) -> bool:
    """Rasterize with CairoSVG, falling back to macOS Quick Look."""

    try:
        import cairosvg

        cairosvg.svg2png(
            url=str(svg_path),
            write_to=str(png_path),
            output_width=1400,
        )
        return True
    except (ImportError, OSError):
        pass

    qlmanage = shutil.which("qlmanage")
    if not qlmanage:
        return False
    subprocess.run(
        [qlmanage, "-t", "-s", "1400", "-o", str(svg_path.parent), str(svg_path)],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    generated = svg_path.with_suffix(svg_path.suffix + ".png")
    if generated.exists():
        generated.replace(png_path)
        return True
    return False


def bbox_record(shape: Part | Compound) -> dict[str, float | bool]:
    bb = shape.bounding_box()
    return {
        "valid": bool(shape.is_valid),
        "x_mm": round(bb.size.X, 3),
        "y_mm": round(bb.size.Y, 3),
        "z_mm": round(bb.size.Z, 3),
        "min_x_mm": round(bb.min.X, 3),
        "min_y_mm": round(bb.min.Y, 3),
        "min_z_mm": round(bb.min.Z, 3),
        "max_x_mm": round(bb.max.X, 3),
        "max_y_mm": round(bb.max.Y, 3),
        "max_z_mm": round(bb.max.Z, 3),
    }


def build_validation(parts: dict[str, Part | Compound]) -> dict[str, object]:
    head_inner_radius = SCREW_RADIUS - SCREW_COUNTERBORE_OD / 2
    head_outer_radius = SCREW_RADIUS + SCREW_COUNTERBORE_OD / 2
    insert_inner_radius = SCREW_RADIUS - INSERT_POCKET_OD / 2
    return {
        "revision": "V3",
        "parts": {name: bbox_record(shape) for name, shape in parts.items()},
        "radial_clearances_mm": {
            "screen_cover_max_to_screw_head_seat": round(
                head_inner_radius
                - (SCREEN_COVER_OD + SCREEN_COVER_OD_TOLERANCE) / 2,
                3,
            ),
            "screw_head_seat_to_body_edge": round(BODY_OD / 2 - head_outer_radius, 3),
            "glass_pocket_to_seal_groove": round(
                FACE_SEAL_INNER_OD / 2 - SCREEN_POCKET_OD / 2,
                3,
            ),
            "seal_groove_to_insert_pocket": round(
                insert_inner_radius - FACE_SEAL_OUTER_OD / 2,
                3,
            ),
            "glass_support_shoulder": round(
                (SCREEN_POCKET_OD - SCREEN_VISIBLE_OPENING_OD) / 2,
                3,
            ),
            "screw_tip_blind_clearance": round(
                SCREW_TIP_CLEARANCE_DEPTH - 4.5,
                3,
            ),
        },
        "packaging_gaps_mm": {
            "screen_worst_case_back_to_pcb_top": round(
                (SCREEN_FACE_Z - (
                    SCREEN_TOTAL_THICKNESS + SCREEN_TOTAL_THICKNESS_TOLERANCE
                )) - PCB_TOP_Z,
                3,
            ),
            "screen_1p5mm_rear_keepout_to_pcb_top": round(
                (SCREEN_FACE_Z - (
                    SCREEN_TOTAL_THICKNESS + SCREEN_TOTAL_THICKNESS_TOLERANCE
                ) - 1.5) - PCB_TOP_Z,
                3,
            ),
            "battery_to_gnss_keepout_y": round(
                (GNSS_ANT_CENTER_Y - GNSS_KEEPOUT_Y / 2)
                - (BATTERY_CENTER_Y + BATTERY_Y / 2),
                3,
            ),
            "gnss_keepout_to_inner_wall_at_12_oclock": round(
                (BODY_OD / 2 - REAR_WALL)
                - (GNSS_ANT_CENTER_Y + GNSS_KEEPOUT_Y / 2),
                3,
            ),
        },
        "checks": {
            "all_shapes_valid": all(bool(shape.is_valid) for shape in parts.values()),
            "screen_screw_clearance_positive": (
                head_inner_radius
                > (SCREEN_COVER_OD + SCREEN_COVER_OD_TOLERANCE) / 2
            ),
            "screw_head_inside_body": head_outer_radius < BODY_OD / 2,
            "continuous_seal_clear_of_insert_pockets": (
                insert_inner_radius > FACE_SEAL_OUTER_OD / 2
            ),
            "glass_has_positive_support_shoulder": (
                SCREEN_POCKET_OD > SCREEN_VISIBLE_OPENING_OD
            ),
            "battery_clear_of_gnss_keepout": (
                BATTERY_CENTER_Y + BATTERY_Y / 2
                < GNSS_ANT_CENTER_Y - GNSS_KEEPOUT_Y / 2
            ),
        },
        "notes": [
            "Rev A1 uses the H0175Y003AMT003 V1 controlled drawing: cover 48.96 +/- 0.05 mm, touch VA 44.16 mm, AMOLED AA 43.76 mm.",
            "M1.6 was selected because an M2 head leaves inadequate material in the radial face band.",
            "Front bezel uses a 45.00 mm visible aperture and 49.26 mm rear glass pocket.",
            "Screen face is 0.25 mm below the protective rim; worst-case total stack is 2.22 mm.",
            "PCB top is lowered to z=-1.50 mm so the drawing's 1.50 mm rear component zone retains nominal clearance.",
            "The drawing's local 0.6/1.5 mm rear component zones still require sample/DXF coordinate sign-off.",
            "PCB envelope includes four R2.7 mm edge scallops for insert-boss clearance.",
            "The split-plane seal runs in a 1.2 x 0.75 mm groove on a real annular land.",
            "Blind screw-tip clearance extends 4.8 mm below the split plane for M1.6x6 hardware.",
            "GNSS keepout is internal at 12 o'clock behind the RF-transparent plastic rear floor.",
            "No external RF pod is present in V3.",
            "Rear interface is a replaceable Garmin Edge-compatible male quarter-turn cleat envelope.",
            "Garmin interface dimensions and locking detents require gauge fitting to OEM mount 010-11430-00 before release.",
        ],
    }


def export_all() -> None:
    out_dir = Path(__file__).resolve().parent / "generated" / "v3"
    preview_dir = out_dir / "previews"
    out_dir.mkdir(parents=True, exist_ok=True)
    preview_dir.mkdir(parents=True, exist_ok=True)

    manufactured: dict[str, Part | Compound] = {
        "v3_front_bezel": make_front_bezel(),
        "v3_rear_shell": make_rear_shell(),
        "v3_mount_insert": make_mount_insert(),
        "v3_screw_set": make_screw_set(),
    }
    envelopes = make_envelopes()

    for name, shape in manufactured.items():
        export_shape(shape, out_dir / name)

    # Build fresh solids for each compound. build123d compounds take ownership
    # of child topology, so reusing the same objects would re-parent them and
    # make later bounding-box validation misleading.
    body_assembly = Compound(
        children=[
            make_front_bezel(),
            make_rear_shell(),
            make_mount_insert(),
            make_screw_set(),
        ]
    )
    export_step(body_assembly, out_dir / "moto-gps-v3-assembly.step")
    envelope_assembly = Compound(
        children=[
            make_front_bezel(),
            make_rear_shell(),
            make_mount_insert(),
            make_screw_set(),
            *make_envelopes().values(),
        ]
    )
    export_step(envelope_assembly, out_dir / "moto-gps-v3-envelope.step")

    svg_path = preview_dir / "v3-isometric.svg"
    preview_parts: list[Part | Compound] = [
        make_front_bezel(),
        make_rear_shell(),
        make_mount_insert(),
        make_screw_set(),
        *make_envelopes().values(),
    ]
    export_preview(preview_parts, svg_path)
    rasterize_svg(svg_path, preview_dir / "v3-isometric.png")

    front_svg_path = preview_dir / "v3-front.svg"
    export_preview(
        [
            make_front_bezel(),
            make_screw_set(),
            make_envelopes()["screen_cover"],
            make_envelopes()["screen_active"],
        ],
        front_svg_path,
        viewport_origin=(0, 0, 120),
        viewport_up=(0, 1, 0),
        look_at=(0, 0, 0),
    )
    rasterize_svg(front_svg_path, preview_dir / "v3-front.png")

    validation_parts = {**manufactured, **envelopes, "body_assembly": body_assembly}
    validation = build_validation(validation_parts)
    (out_dir / "v3-validation.json").write_text(
        json.dumps(validation, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(validation, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    export_all()
