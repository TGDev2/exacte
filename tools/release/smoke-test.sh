#!/usr/bin/env bash
# Starts the packaged application from its archive and checks that it is still running a few seconds later.
# Usage: bash tools/release/smoke-test.sh <Calc-X.Y.Z-os-arch.zip|.tar.gz> [seconds]
# The archive is unpacked in a new temporary directory; the application is stopped at the end.
# On Linux a display is needed: xvfb-run -a bash tools/release/smoke-test.sh <archive>
set -euo pipefail

archive=${1:?usage: smoke-test.sh <archive> [seconds]}
seconds=${2:-8}

work=$(mktemp -d)
pid=""

cleanup() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null || true
    fi
    case "$(uname -s)" in
        MINGW* | MSYS* | CYGWIN*) taskkill //F //IM Calc.exe >/dev/null 2>&1 || true ;;
    esac
    rm -rf "$work"
}
trap cleanup EXIT

mkdir "$work/app" "$work/data"

case "$archive" in
    *.zip)
        powershell -NoProfile -Command "Expand-Archive -LiteralPath '$(cygpath -w "$archive")' -DestinationPath '$(cygpath -w "$work/app")'"
        launcher="$work/app/Calc/Calc.exe"
        ;;
    *.tar.gz)
        tar -xzf "$archive" -C "$work/app"
        if [ -d "$work/app/Calc.app" ]; then
            launcher="$work/app/Calc.app/Contents/MacOS/Calc"
        else
            launcher="$work/app/Calc/bin/Calc"
        fi
        ;;
    *)
        echo "Unknown archive type: $archive" >&2
        exit 2
        ;;
esac

if [ ! -f "$launcher" ]; then
    echo "Launcher not found in the archive: $launcher" >&2
    exit 1
fi

# Keep the user data of the machine out of the test where the system allows it (Windows, Linux).
export APPDATA="$work/data"
export XDG_DATA_HOME="$work/data"

"$launcher" &
pid=$!
sleep "$seconds"

if kill -0 "$pid" 2>/dev/null; then
    echo "OK: $(basename "$archive") is still running after ${seconds} s"
else
    echo "FAIL: the application stopped within ${seconds} s" >&2
    exit 1
fi
