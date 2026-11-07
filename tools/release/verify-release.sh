#!/usr/bin/env bash
# Checks what a release really published: downloads its files from GitHub, reads its status and its notes, starts the Windows
# application with a temporary APPDATA, then runs the Gradle task verifyReleaseFiles, which writes the evidence.
# Usage, from the root of the repository: bash tools/release/verify-release.sh <tag> <run-id> <evidence-file>
# The tag is test-vX.Y.Z-N or vX.Y.Z; the run id is the one of the workflow run that built the release.
# A failed check still writes the evidence, with result failed, and the script ends in error. GitHub is only read here: this
# script removes neither a release nor a tag. The download goes to a new temporary folder.
set -euo pipefail

usage="usage: verify-release.sh <tag> <run-id> <evidence-file>"
tag=${1:?$usage}
run=${2:?$usage}
evidence=${3:?$usage}

if [[ "$tag" =~ ^test-v([0-9]+\.[0-9]+\.[0-9]+)-[0-9]+$ ]]; then
    version=${BASH_REMATCH[1]}
    expected_prerelease=true
elif [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
    version=${BASH_REMATCH[1]}
    expected_prerelease=false
else
    echo "The tag must be test-vX.Y.Z-N or vX.Y.Z: $tag" >&2
    exit 2
fi

if [ ! -f tools/release/smoke-test.sh ]; then
    echo "Run this script from the root of the repository." >&2
    exit 2
fi

# gh and Gradle are native programs under Windows: they need Windows paths there.
native_path() {
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -m "$1"
    else
        printf '%s\n' "$1"
    fi
}

downloads=$(mktemp -d)
trap 'rm -rf "$downloads" 2>/dev/null || true' EXIT

gh release download "$tag" --dir "$(native_path "$downloads")"

prerelease_check=false
if [ "$(gh release view "$tag" --json isPrerelease,body --jq .isPrerelease)" = "$expected_prerelease" ]; then
    prerelease_check=true
fi

notes_check=false
case "$(gh release view "$tag" --json isPrerelease,body --jq .body)" in
    *docs/INSTALLATION.md*) notes_check=true ;;
esac

commit=$(gh run view "$run" --json headSha --jq .headSha)

windows_started=false
case "$(uname -s)" in
    MINGW* | MSYS* | CYGWIN*)
        gradle=./gradlew.bat
        if bash tools/release/smoke-test.sh "$downloads/Calc-$version-windows-x64.zip" 5; then
            windows_started=true
        fi
        ;;
    *)
        gradle=./gradlew
        # Without this start the trial is not proved: the evidence says so and the check below ends in error.
        echo "The Windows application can only be started under Windows: this trial will end in error." >&2
        ;;
esac

"$gradle" verifyReleaseFiles \
    "-PappVersion=$version" \
    "-PreleaseDir=$(native_path "$downloads")" \
    "-PreleaseTag=$tag" \
    "-PreleaseRun=$run" \
    "-PreleaseCommit=$commit" \
    "-PreleasePrerelease=$prerelease_check" \
    "-PreleaseNotesLink=$notes_check" \
    "-PwindowsStarted=$windows_started" \
    "-PevidenceFile=$(native_path "$evidence")"
