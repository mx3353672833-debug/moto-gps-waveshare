#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
KICAD_ROOT_DEFAULT=/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents
KICAD_ROOT=${KICAD_10_ROOT:-$KICAD_ROOT_DEFAULT}
KICAD_PYTHON=${KICAD_PYTHON:-$KICAD_ROOT/Frameworks/Python.framework/Versions/3.9/bin/python3.9}

if [ ! -x "$KICAD_PYTHON" ]; then
    echo "KiCad Python not found: $KICAD_PYTHON" >&2
    exit 2
fi

exec "$KICAD_PYTHON" "$SCRIPT_DIR/generate_board.py" "$@"
