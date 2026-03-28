$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDirectory = Join-Path $projectRoot "out"
$distributionDirectory = Join-Path $projectRoot "dist"
$packageInputDirectory = Join-Path $distributionDirectory "input"
$jarPath = Join-Path $packageInputDirectory "Calc.jar"
$appImageDirectory = Join-Path $distributionDirectory "Calc"
$portableArchivePath = Join-Path $distributionDirectory "Calc-portable.zip"

foreach ($tool in @("jar", "jpackage")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "L'outil requis '$tool' est introuvable dans le PATH."
    }
}

& powershell -ExecutionPolicy Bypass -File (Join-Path $projectRoot "check.ps1")
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
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

& jar --create --file $jarPath --main-class Main -C $outputDirectory .
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& jpackage `
    --type app-image `
    --name Calc `
    --dest $distributionDirectory `
    --input $packageInputDirectory `
    --main-jar Calc.jar `
    --main-class Main `
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
