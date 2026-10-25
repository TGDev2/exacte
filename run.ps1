$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$jarPath = Join-Path $projectRoot "target\calc.jar"

& (Join-Path $projectRoot "mvnw.cmd") -B -q package -DskipTests
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}

& java -jar $jarPath
exit $LASTEXITCODE
