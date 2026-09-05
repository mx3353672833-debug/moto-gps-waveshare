"""Parametric Rev A0 enclosure concept for MOTO GPS.

This script models four separately manufacturable parts: an aluminium front
bezel, a plastic rear shell, a plastic GNSS RF pod and a plastic quarter-turn
mount insert. It is an interference model, not a released production drawing.
"""

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
BODY_OD = 60.0
PCB_OD = 52.0
PCB_THICKNESS = 1.0
REAR_DEPTH = 13.0
REAR_WALL = 2.4
REAR_FLOOR = 1.5
FRONT_BEZEL_HEIGHT = 3.0
SCREEN_COVER_OD = 48.16
SCREEN_ACTIVE_OD = 44.16
SCREEN_COVER_THICKNESS = 1.1
BATTERY_X = 27.0
BATTERY_Y = 32.0
BATTERY_Z = 7.0
GNSS_ANT_X = 10.0
GNSS_ANT_Y = 10.0
GNSS_ANT_Z = 6.0
RF_POD_X = 18.0
RF_POD_Y = 14.0
RF_POD_Z = 8.0
RF_POD_CENTER_Y = 32.5
MOUNT_OD = 26.0


def z_cylinder(diameter: float, height: float, z_min: float) -> Part:
    """Create a Z-axis cylinder with an explicit lower Z face."""

    return Pos(0, 0, z_min) * Cylinder(
        diameter / 2,
        height,
        align=(Align.CENTER, Align.CENTER, Align.MIN),
    )


def annulus(outer_diameter: float, inner_diameter: float, height: float, z_min: float) -> Part:
    return z_cylinder(outer_diameter, height, z_min) - z_cylinder(inner_diameter, height, z_min)


def make_front_bezel() -> Part:
    """Aluminium front ring; screen radial clearance remains provisional."""

    bezel = annulus(BODY_OD, SCREEN_COVER_OD + 0.8, FRONT_BEZEL_HEIGHT, 0.0)
    # Circular edge tangents lie in XY, so Axis.Z filtering would select none.
    return fillet(bezel.edges(), radius=0.5)


def make_rear_shell() -> Part:
    """Plastic RF-transparent rear shell without the removable pod or mount."""

    outer = z_cylinder(BODY_OD, REAR_DEPTH, -REAR_DEPTH)
    # Extend the cavity to the split plane so the Boolean leaves a clean lip.
    cavity = z_cylinder(BODY_OD - 2 * REAR_WALL, REAR_DEPTH, -REAR_DEPTH + REAR_FLOOR)
    shell = outer - cavity

    # Provisional face-seal groove. Final geometry depends on the selected O-ring.
    shell -= annulus(55.4, 52.4, 0.75, -0.75)

    # Six-o'clock USB opening envelope; the silicone plug is a future part.
    shell -= Pos(0, -29.0, -5.0) * Box(12.0, 7.0, 6.0)

    # A shallow rear pocket locates the removable quarter-turn insert.
    shell -= z_cylinder(MOUNT_OD + 0.5, 1.2, -REAR_DEPTH - 0.01)
    return shell


def make_rf_pod() -> Part:
    """Removable plastic pod for a 10 x 10 x 6 mm active GNSS antenna."""

    outer = Pos(0, RF_POD_CENTER_Y, -RF_POD_Z / 2) * Box(RF_POD_X, RF_POD_Y, RF_POD_Z)
    outer = fillet(outer.edges().filter_by(Axis.Z), radius=3.0)
    cavity = Pos(0, RF_POD_CENTER_Y, -(RF_POD_Z / 2) - 0.4) * Box(
        RF_POD_X - 3.0,
        RF_POD_Y - 3.0,
        RF_POD_Z - 1.8,
    )
    cavity = fillet(cavity.edges().filter_by(Axis.Z), radius=2.0)
    # The tongue overlaps the shell outside the display stack. Fastening and
    # gasket details remain deferred until measured A1 geometry.
    tongue = Pos(0, RF_POD_CENTER_Y - 7.5, -3.0) * Box(14.0, 5.0, 2.4)
    return (outer + tongue) - cavity


def make_mount_insert() -> Part:
    """Provisional replaceable quarter-turn insert for the rear shell."""

    disc = z_cylinder(MOUNT_OD, 3.0, -REAR_DEPTH - 3.0)
    horizontal_lugs = Pos(0, 0, -REAR_DEPTH - 2.0) * Box(32.0, 6.0, 2.0)
    vertical_lugs = Pos(0, 0, -REAR_DEPTH - 2.0) * Box(6.0, 32.0, 2.0)
    insert = disc + horizontal_lugs + vertical_lugs
    return fillet(insert.edges().filter_by(Axis.Z), radius=0.8)


def make_envelopes() -> dict[str, Part]:
    """Non-manufactured solids used only to inspect internal packaging."""

    return {
        "screen_cover": z_cylinder(SCREEN_COVER_OD, SCREEN_COVER_THICKNESS, 1.0),
        "screen_active": z_cylinder(SCREEN_ACTIVE_OD, 0.25, 0.75),
        "pcb": z_cylinder(PCB_OD, PCB_THICKNESS, -2.2),
        "battery": Pos(0, 0, -8.2 + BATTERY_Z / 2) * Box(BATTERY_X, BATTERY_Y, BATTERY_Z),
        "gnss_antenna": Pos(0, RF_POD_CENTER_Y, -4.0) * Box(
            GNSS_ANT_X,
            GNSS_ANT_Y,
            GNSS_ANT_Z,
        ),
    }


def export_part(part: Part, output_stem: Path) -> None:
    export_step(part, output_stem.with_suffix(".step"))
    export_stl(
        part,
        output_stem.with_suffix(".stl"),
        tolerance=0.05,
        angular_tolerance=0.15,
    )


def export_preview(parts: list[Part], output_path: Path) -> None:
    """Export a dependency-free isometric technical preview as SVG line art."""

    assembly = Compound(children=parts)
    visible, hidden = assembly.project_to_viewport(
        viewport_origin=(105, -120, 85),
        viewport_up=(0, 0, 1),
        look_at=(0, 0, -5),
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


def export_all() -> None:
    out_dir = Path(__file__).resolve().parent / "generated"
    out_dir.mkdir(parents=True, exist_ok=True)

    manufactured = {
        "front_bezel": make_front_bezel(),
        "rear_shell": make_rear_shell(),
        "rf_pod": make_rf_pod(),
        "mount_insert": make_mount_insert(),
    }
    envelopes = make_envelopes()
    for name, part in manufactured.items():
        export_part(part, out_dir / name)

    # A STEP compound helps interference review without implying that its
    # display, PCB, battery and antenna envelopes are manufactured parts.
    envelope = Compound(children=[*manufactured.values(), *envelopes.values()])
    export_step(envelope, out_dir / "moto-gps-rev-a0-envelope.step")
    preview_dir = out_dir / "previews"
    preview_dir.mkdir(parents=True, exist_ok=True)
    export_preview(
        [*manufactured.values(), *envelopes.values()],
        preview_dir / "rev-a0-isometric.svg",
    )


if __name__ == "__main__":
    export_all()
