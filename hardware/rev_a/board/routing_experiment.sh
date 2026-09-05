#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR=$SCRIPT_DIR/build
BOARD=${1:-$SCRIPT_DIR/moto-gps-rev-a.kicad_pcb}
ROUTED=${2:-$BUILD_DIR/moto-gps-rev-a-autoroute-experiment.kicad_pcb}
KICAD_ROOT_DEFAULT=/opt/homebrew/Caskroom/kicad/10.0.6/KiCad/KiCad.app/Contents
KICAD_ROOT=${KICAD_10_ROOT:-$KICAD_ROOT_DEFAULT}
KICAD_PYTHON=${KICAD_PYTHON:-$KICAD_ROOT/Frameworks/Python.framework/Versions/3.9/bin/python3.9}
FREEROUTING_JAR=${FREEROUTING_JAR:-/tmp/moto-gps-tools/freerouting-2.3.0.jar}
JAVA=${JAVA:-/opt/homebrew/opt/openjdk/bin/java}
DSN=$BUILD_DIR/moto-gps-rev-a.dsn
SES=$BUILD_DIR/moto-gps-rev-a.ses

if [ ! -x "$KICAD_PYTHON" ] || [ ! -x "$JAVA" ] || [ ! -f "$FREEROUTING_JAR" ]; then
    echo "Missing KiCad Python, Java, or freerouting jar" >&2
    exit 2
fi

mkdir -p "$BUILD_DIR"
"$KICAD_PYTHON" "$SCRIPT_DIR/specctra_io.py" export "$BOARD" "$DSN"

# Deliberately bounded. The result goes to build/ and is never the canonical PCB.
"$JAVA" -jar "$FREEROUTING_JAR" -de "$DSN" -do "$SES" -mp 20 -mt 1 -us hybrid -is prioritized
"$KICAD_PYTHON" "$SCRIPT_DIR/specctra_io.py" import "$BOARD" "$SES" "$ROUTED"

echo "Autoroute experiment written to: $ROUTED"
echo "Do not copy it over the canonical PCB without reviewed critical-net routing and DRC."
