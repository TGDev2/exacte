$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$outputDirectory = Join-Path $projectRoot "out"

if (Test-Path -LiteralPath $outputDirectory) {
    $resolvedOutputDirectory = (Resolve-Path -LiteralPath $outputDirectory).Path
    if (-not $resolvedOutputDirectory.StartsWith($projectRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Le dossier de sortie n'est pas dans le projet."
    }
    Get-ChildItem -LiteralPath $resolvedOutputDirectory -Force | Remove-Item -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $outputDirectory | Out-Null
}

$sourceFiles = Get-ChildItem -Path $projectRoot -Recurse -Filter *.java | ForEach-Object { $_.FullName }

if ($sourceFiles.Count -eq 0) {
    throw "Aucun fichier Java trouve."
}

& javac -encoding UTF-8 -d $outputDirectory $sourceFiles
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java -cp $outputDirectory Main
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
