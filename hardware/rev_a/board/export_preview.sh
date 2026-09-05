#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BOARD=${1:-$SCRIPT_DIR/moto-gps-rev-a.kicad_pcb}
BUILD_DIR=$SCRIPT_DIR/build
KICAD_CLI_DEFAULT=/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents/MacOS/kicad-cli
KICAD_CLI=${KICAD_CLI:-$KICAD_CLI_DEFAULT}
SVG=$BUILD_DIR/moto-gps-rev-a-top.svg

mkdir -p "$BUILD_DIR"
"$KICAD_CLI" pcb export svg --mode-single --fit-page-to-board \
    --exclude-drawing-sheet --check-zones \
    --layers F.Cu,F.Mask,F.Silkscreen,Edge.Cuts,User.Drawings \
    --output "$SVG" "$BOARD"

# macOS Quick Look provides a deterministic local PNG thumbnail without adding
# a repository dependency. The SVG remains the source preview on other systems.
if command -v qlmanage >/dev/null 2>&1; then
    qlmanage -t -s 1600 -o "$BUILD_DIR" "$SVG" >/dev/null 2>&1
fi
echo "$SVG"
