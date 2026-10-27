# tools/baseline - reference behaviour capture

`Baseline.java` is a standalone program (default package, no dependency). It calls the **existing** engine, presenter and
history store as they are and writes:

- `src/test/resources/golden/engine-v1.tsv` and `.gitattributes` - one line per engine case (70 cases: 47 OK, 23 ERROR);
- `src/test/resources/legacy/v1/*.tsv`, `src/test/resources/legacy/v1/README.md` and `src/test/resources/legacy/v1/.gitattributes`.

> **Since E07** the legacy-file part of this tool needs `FileCalculationHistoryStore`, which was removed when the history became JSON (`docs/adr/0005-format-persistance.md`). Run it on a commit before E07 to regenerate the version 1 observations; the files in `src/test/resources/legacy/v1` are unchanged and are now the input of the migration tests (`LegacyMigrationTest`).

It is not part of the production code and is not built by Gradle.

## Regenerate

From the repository root (JDK 17+), in PowerShell:

```powershell
$out = Join-Path $env:TEMP "calc-baseline"
javac -encoding UTF-8 -d $out (Get-ChildItem src\main\java\calc\engine, src\main\java\calc\ui -Filter *.java | ForEach-Object FullName) tools\baseline\Baseline.java
java -cp $out Baseline .
```

The only argument is the root under which `src/test/resources/` is written. Run it twice (second time with another root) and compare
with `diff -r` to check determinism: the output does not depend on the clock, the temp folder or the console encoding
(everything is written as UTF-8 bytes, LF line endings except `crlf.tsv`).

## Golden file format

Columns: `operation` (enum constant, or `(none)` for a null operation), `first`, `second`, `outcome` (`OK`/`ERROR`),
`display` (formatted result, or the exact error message), `reusable` (value the presenter puts back in the first field
when the last result is reused; empty on error).

Non-printable or very long inputs are written with a notation that the program expands before calling the engine:

- `<U+XXXX>` is one UTF-16 character (`<U+00A0>` no-break space, `<U+202F>` narrow no-break space);
- `<rep:N:text>` is `text` repeated N times.

Results of `2 ^ 999` and `2 ^ -999` are stored as `len=<characters> sha256=<hex>` of the displayed text.

## Why these files must not change

They are the reference of what version 1 of the application does. Later Epics turn them into non-regression tests, so a
diff on them means the observable behaviour changed. Change them only through a documented decision (an ADR in
`docs/adr/`) that names the intended behaviour change, regenerate with this program, and review the diff line by line.

## After E06

`Baseline.java` captured version 1 and must not be run on the exact engine of E06: it would overwrite `engine-v1.tsv` and
the observed behaviours of `legacy/v1/README.md` with the new outputs. The reference outputs of the exact engine are
`engine-v2.tsv`, produced by `tools/golden-v2` from the same inputs. The `legacy/v1` files themselves are unchanged and
still load with the same number of entries (`CalculatorHistoryLegacyFilesTest`).

> **Since E13** this tool also calls the two-field `CalculatorPresenter`, which was removed with the old interface (`docs/adr/0011-fichiers-dans-l-interface.md`). Run it on a commit before E13 (and before E07 for the legacy files); the golden file it wrote is now checked by `calc.engine.GoldenOutputsTest`.
