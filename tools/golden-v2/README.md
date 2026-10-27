# tools/golden-v2 - reference outputs of the exact engine

`GoldenV2.java` is a standalone program (default package). It replays the 70 inputs of
`src/test/resources/golden/engine-v1.tsv` (version 1, kept for the record) on the current engine, and writes:

- `src/test/resources/golden/engine-v2.tsv`: same format and same inputs as v1, the reference that `GoldenOutputsTest`
  replays;
- `docs/changements-comportement-E06.md`: every line whose outcome, displayed text or reusable text differs from v1, with
  its category (expected improvement, new message, regression) and its reason. The categories are decided by the rules
  in `classify`; review the document line by line after a regeneration.

It is not part of the production code and is not built by Gradle.

## Regenerate

From the repository root, in PowerShell (the classes must be compiled for Java 21: use the JDK of the Gradle toolchain,
printed by `.\gradlew.bat -q printToolchainHome`):

```powershell
.\gradlew.bat classes
$jdk = (.\gradlew.bat -q printToolchainHome).Trim()
$out = Join-Path $env:TEMP "calc-golden-v2"
& "$jdk\bin\javac.exe" -encoding UTF-8 -cp build\classes\java\main -d $out tools\golden-v2\GoldenV2.java
& "$jdk\bin\java.exe" -cp "build\classes\java\main;$out" GoldenV2 .
```

It prints the number of cases, of changes and of regressions. The output is deterministic (no clock, no temporary path):
running it twice gives the same bytes.

## Format

The columns and the input notation (`<U+XXXX>`, `<rep:N:text>`) are those of v1 (`tools/baseline/README.md`). The
`display` column is the Auto text of the result (or the error message), `reusable` is the canonical text the old window
puts back in the first field. They are no longer the same string (docs/adr/0004-regles-affichage.md). A text longer than
100 characters is stored as `len=<characters> sha256=<hex>`.
