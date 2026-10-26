$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$builtJarPath = Join-Path $projectRoot "build\libs\calc.jar"
$distributionDirectory = Join-Path $projectRoot "dist"
$packageInputDirectory = Join-Path $distributionDirectory "input"
$jarPath = Join-Path $packageInputDirectory "calc.jar"
$appImageDirectory = Join-Path $distributionDirectory "Calc"
$portableArchivePath = Join-Path $distributionDirectory "Calc-portable.zip"

$gradlew = Join-Path $projectRoot "gradlew.bat"

& $gradlew check jar
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

# jpackage must come from the JDK that compiled the jar (the Gradle toolchain), not from the PATH:
# the runtime image bundled in the app has to be able to run the classes.
$toolchainHome = (& $gradlew -q printToolchainHome | Where-Object { $_.Trim() } | Select-Object -Last 1)
if ($LASTEXITCODE -ne 0 -or -not $toolchainHome) {
    exit 1
}
$jpackage = Join-Path $toolchainHome.Trim() "bin\jpackage.exe"
if (-not (Test-Path -LiteralPath $jpackage)) {
    throw "L'outil requis 'jpackage' est introuvable dans le JDK $toolchainHome."
}

if (Test-Path -LiteralPath $distributionDirectory) {
    $resolvedDistributionDirectory = (Resolve-Path -LiteralPath $distributionDirectory).Path
    if (-not $resolvedDistributionDirectory.StartsWith($projectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Le dossier de distribution n'est pas dans le projet."
    }
    Get-ChildItem -LiteralPath $resolvedDistributionDirectory -Force | Remove-Item -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $distributionDirectory | Out-Null
}

New-Item -ItemType Directory -Path $packageInputDirectory -Force | Out-Null

Copy-Item -LiteralPath $builtJarPath -Destination $jarPath

& $jpackage `
    --type app-image `
    --name Calc `
    --dest $distributionDirectory `
    --input $packageInputDirectory `
    --main-jar calc.jar `
    --main-class calc.Main `
    --app-version 1.0.0 `
    --vendor "Calc" `
    --description "Calculatrice fiable Java Swing"
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

if (Test-Path -LiteralPath $portableArchivePath) {
    Remove-Item -LiteralPath $portableArchivePath -Force
}

Compress-Archive -Path $appImageDirectory -DestinationPath $portableArchivePath -Force

Write-Host "Package cree :"
Write-Host " - $appImageDirectory\\Calc.exe"
Write-Host " - $portableArchivePath"
