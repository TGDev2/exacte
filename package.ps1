param(
    # Local build only: official releases are built by the release workflow from a vX.Y.Z tag (docs/RELEASE.md).
    [string]$Version = "1.0.0"
)

$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# The packaging itself (jdeps, jpackage of the toolchain, archive) is the Gradle task packageApp, the same on every system.
# Result: build\release\Calc-<version>-windows-<arch>.zip, containing Calc\Calc.exe.
& (Join-Path $projectRoot "gradlew.bat") check packageApp "-PappVersion=$Version" @args
exit $LASTEXITCODE
