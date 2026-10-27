# tools/baseline - reference behaviour capture (archive)

`Baseline.java` captured the behaviour of the version 1 engine, two-field window and history store: the 70 cases of
`src/test/resources/golden/engine-v1.tsv` and the 11 history files of `src/test/resources/legacy/v1`. It could no longer be
built after E07 (`FileCalculationHistoryStore` is gone, `docs/adr/0005-format-persistance.md`) and it called the two-field
presenter that E13 removed (`docs/adr/0011-fichiers-dans-l-interface.md`), so the program was deleted in E13. It stays in the
git history (`git log -- tools/baseline/Baseline.java`). The files it wrote are frozen: `engine-v1.tsv` is the input of
`tools/golden-v2`, and `legacy/v1` is the input of the migration tests (`LegacyMigrationTest`, `NotebookMigrationTest`).

## Golden file format

Columns: `operation` (enum constant, or `(none)` for a null operation), `first`, `second`, `outcome` (`OK`/`ERROR`),
`display` (formatted result, or the exact error message), `reusable` (value the canonical text of the result that the old window put back in
the first field when the last result was reused; empty on error).

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
