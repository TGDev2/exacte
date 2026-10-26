$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$builtJarPath = Join-Path $projectRoot "target\calc.jar"
$distributionDirectory = Join-Path $projectRoot "dist"
$packageInputDirectory = Join-Path $distributionDirectory "input"
$jarPath = Join-Path $packageInputDirectory "calc.jar"
$appImageDirectory = Join-Path $distributionDirectory "Calc"
$portableArchivePath = Join-Path $distributionDirectory "Calc-portable.zip"

foreach ($tool in @("java", "jpackage")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "L'outil requis '$tool' est introuvable dans le PATH."
    }
}

& (Join-Path $projectRoot "mvnw.cmd") -B verify
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

Copy-Item -LiteralPath $builtJarPath -Destination $jarPath

& jpackage `
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
