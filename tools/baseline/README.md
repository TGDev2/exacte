# tools/baseline - reference behaviour capture

`Baseline.java` is a standalone program (default package, no dependency). It calls the **existing** engine, presenter and
history store as they are and writes:

- `testdata/golden/engine-v1.tsv` - one line per engine case (70 cases: 47 OK, 23 ERROR);
- `testdata/legacy-v1/*.tsv`, `testdata/legacy-v1/README.md` and `testdata/legacy-v1/.gitattributes`.

It is not part of the production code and is not built by Maven.

## Regenerate

From the repository root (JDK 17+), in PowerShell:

```powershell
$out = Join-Path $env:TEMP "calc-baseline"
javac -encoding UTF-8 -d $out (Get-ChildItem src\main\java\calc\engine, src\main\java\calc\ui -Filter *.java | ForEach-Object FullName) tools\baseline\Baseline.java
java -cp $out Baseline .
```

The only argument is the root under which `testdata/` is written. Run it twice (second time with another root) and compare
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
