#!/usr/bin/env bash
# Unpacks a packaged application, checks the modules embedded in its runtime, starts it and checks that the process is
# still running a few seconds later and that its output shows no Java error. It does not look for the window.
# Usage: bash tools/release/smoke-test.sh <Calc-X.Y.Z-os-arch.zip|.tar.gz> [seconds]
# The archive goes to a new temporary directory; only the process started here is stopped.
# Made for CI runners. On a personal machine, note that on macOS the application writes its data to the real
# ~/Library/Application Support/Calc (only APPDATA and XDG_DATA_HOME, used on Windows and Linux, are redirected).
# On Linux a display is needed: xvfb-run -a bash tools/release/smoke-test.sh <archive>
set -euo pipefail

archive=${1:?usage: smoke-test.sh <archive> [seconds]}
seconds=${2:-8}

work=$(mktemp -d)
pid=""

cleanup() {
    if [ -n "$pid" ]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        sleep 2
    fi
    # A failed removal of the temporary folder (a file still locked on Windows) must not change the verdict.
    rm -rf "$work" 2>/dev/null || true
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

# The runtime image lists its modules in the file `release` (its place depends on the system).
runtime_release=$(find "$work/app" -type f -name release -path '*runtime*' | head -n 1)
if [ -z "$runtime_release" ]; then
    echo "FAIL: no runtime image in the archive" >&2
    exit 1
fi
for module in java.desktop java.prefs jdk.localedata; do
    if ! grep '^MODULES=' "$runtime_release" | grep -qw "$module"; then
        echo "FAIL: the module $module is not embedded ($(grep '^MODULES=' "$runtime_release"))" >&2
        exit 1
    fi
done

# Keep the user data of the machine out of the test where the system allows it (Windows, Linux).
export APPDATA="$work/data"
export XDG_DATA_HOME="$work/data"

"$launcher" >"$work/launcher.log" 2>&1 &
pid=$!
sleep "$seconds"

if ! kill -0 "$pid" 2>/dev/null; then
    echo "FAIL: the application stopped within ${seconds} s" >&2
    cat "$work/launcher.log" >&2
    exit 1
fi
if grep -Eq 'Exception|Error occurred|Could not create the Java Virtual Machine' "$work/launcher.log"; then
    echo "FAIL: the application printed a Java error" >&2
    cat "$work/launcher.log" >&2
    exit 1
fi
echo "OK: $(basename "$archive") embeds the expected modules and is still running after ${seconds} s"
