# Baseline assessment (État des lieux) - Calc

Section titles follow the Epic E00 brief; the French wording is given in parentheses.

## 1. Context and date (Contexte et date)

- Date of the assessment: 2026-10-26. Branch `autopilot/e00`, created by the pilot from `main` at commit `f9be548`.
- Scope of E00: no production or test file is modified. Only files under `docs/`, `testdata/`, `tools/` (and the local, git-ignored
  `tasks/`) are added.
- **The repository is much more advanced than the description in the Epic brief.** Everything below was checked on the code, not
  taken from the brief:

| Brief says | Reality (verified) |
|---|---|
| Sources in `Calcul/`, `Graphique/`, `Tests/`, `Main.java` at the root | Maven layout: `src/main/java/calc/{engine,ui}/`, `src/main/java/calc/Main.java`, `src/test/java/calc/{engine,ui}/` |
| Home-made tests run by `main`, 4 classes | JUnit 5 (5.14.4), 11 test classes, 116 tests |
| `javac` only, no build tool, no CI | Maven wrapper (`mvnw`, `mvnw.cmd`, `pom.xml`), GitHub Actions `.github/workflows/ci.yml` |
| `NumberFormatter` exists | Removed (commit `96fd73b`); formatting is `DecimalPolicy.format` |
| TSV has 4 columns, no header | 4 columns **plus** a first line `#calc-history v1` written by the current code; headerless files are still read |
| Brief targets Gradle | The project already builds with Maven; Gradle is not installed |

## 2. Git inventory (Inventaire git)

- Current branch: `autopilot/e00` (identical to `main`, `git log main..HEAD` was empty before E00). 37 commits in total.
- Tags: `autopilot/E6`, `autopilot/E7`, `autopilot/E8`, `autopilot/E9`, `autopilot/E10`.
- Branches: local `epic/e6-durable-format`, `epic/e7-input-parsing`, `epic/e8-ui-polish`, `epic/e9-structure-cleanup`,
  `epic/e10-final-docs`, `main`; remote `origin/main`, `origin/epic/e1-build-foundation`, `origin/epic/e2-history-safety`,
  `origin/epic/e3-typed-errors`. Remote: `https://github.com/TGDev2/Calculatrice.git`.
- Last 20 commits (`git log --oneline -20`):

```
f9be548 docs: align README with final behaviour and known limits
b402a65 build: fix lint warnings and enable -Werror
96fd73b refactor(engine): remove NumberFormatter and getReusableValue
96177b8 refactor(ui): make the view model a record
904d636 refactor(ui): move operation texts out of the engine enum
fb8256f refactor: move code to lowercase calc packages
48536e5 feat(ui): add mnemonics, accessible names and truncated result
25ef35e feat(ui): apply the system look and feel at startup
62f232d feat(ui): restore French accents in engine and status messages
66c89bc docs: state that the point is a decimal separator
0499bcb feat(calcul): accept thousands separators and edge no-break spaces
8d6bd65 feat(calcul): write history atomically with a versioned header
d1ca685 Merge pull request #7 from TGDev2/epic/e5-rounding-policy
2088134 test(calcul): pin square roots next to a rounding midpoint
7906689 test(calcul): cover the significant-digit rounding policy
6b221c2 docs: describe the new rounding rule in hints and README
025ddd0 fix(calcul): round approximations to 12 significant digits
e1b2d7e Merge pull request #6 from TGDev2/epic/e4-size-limits
09784e1 test(calcul): cover size limits and chained powers
08bd146 feat(calcul): bound operand and result sizes
```

- 41 tracked files before E00: `.gitattributes`, `.github/workflows/ci.yml`, `.gitignore`, `.mvn/wrapper/maven-wrapper.properties`, `README.md`,
  `check.ps1`, `run.ps1`, `package.ps1`, `mvnw`, `mvnw.cmd`, `pom.xml`, 18 main sources, 11 test sources.
- Present on disk: `CLAUDE.md`, `BACKLOG.md`, `tasks/` (all excluded through `.git/info/exclude`, so untracked by design),
  `.github/`. Absent before E00: `docs/`, `testdata/`, `tools/`.
- `.gitignore`: `out/`, `dist/`, `target/`. `.gitattributes`: `* text=auto`, `mvnw` LF, `*.cmd` and `*.ps1` CRLF.

## 3. Tooled environment (Environnement outillé)

| Tool | Version | Present |
|---|---|---|
| OS / shell | Windows 11 Pro 10.0.26200; PowerShell 5.1.26100.9711 (Git Bash also available) | yes |
| `java` | OpenJDK 17.0.15 (Temurin-17.0.15+6) | yes |
| `javac` | 17.0.15 | yes |
| `jar` | 17.0.15 | yes |
| `jpackage` | 17.0.15 | yes |
| JDK >= 21 | not installed (only JDK 17) | **no** |
| `gradle` | - | **no** |
| `mvn` | on PATH through scoop (`...\scoop\apps\maven\current\bin\mvn.cmd`); the repository uses its own wrapper `mvnw.cmd` | yes |
| `git` | 2.47.1.windows.2 | yes |
| `gh` | 2.103.2 (2026-10-23); `gh auth status`: logged in to github.com as `TGDev2` (keyring), scopes `gist, read:org, repo, workflow` | yes, authenticated |

The JDK is 17, below 21: later Epics that target Java 21 through a toolchain need a JDK 21 (download or install) because none is available locally.
The CI matrix already runs Java 17 and 21 on Linux and 17 on Windows.

## 4. Current architecture (Architecture actuelle)

Packages and classes (`src/main/java/calc`):

- `calc.Main` - starts Swing on the EDT, applies the system look and feel, wires `CalculatorEngine` +
  `FileCalculationHistoryStore.forCurrentUser()` -> `CalculatorPresenter` -> `CalculatorFrame`.
- `calc.engine` (no Swing):
  - `CalculatorEngine` - parses both inputs, validates, computes, returns a `CalculationRecord`; throws `CalculationException`
    (message + `InputField` FIRST/SECOND/NONE).
  - `DecimalPolicy` (package-private) - single place for parsing (comma or point, optional thousands spaces), normalisation,
    `divide`, `sqrt`, `pow`, rounding (`HALF_UP`, 12 significant digits with a floor of 12 decimals), limits (input 1005 characters,
    1000 digits, exponent +-999), `format` (comma) and `serialize` (point).
  - `CalculationLimits` - public view of three constants. `Operation` (ADDITION, SUBTRACTION, MULTIPLICATION, DIVISION, POWER,
    ROOT) with its history symbol and arity. `InputField`. `CalculationRecord` (operation, operand texts, normalised result,
    formatted result, history text).
  - `CalculationHistoryStore` (interface + `noop()`), `FileCalculationHistoryStore` (file `%APPDATA%\Calc\history.tsv`, otherwise
    `~/.calc/history.tsv`), `HistoryLoadResult` (records, skipped lines, backup path), `CalculationException`,
    `CalculationHistoryException`.
- `calc.ui`: `CalculatorPresenter` (all interaction logic, testable without a UI), `CalculatorViewModel` (immutable record),
  `CalculatorFrame` (Swing view only), `OperationText`, enums `FocusTarget`, `ReuseTarget`, `StatusTone`.

Flow of a calculation: the user types in `CalculatorFrame` -> the frame calls `CalculatorPresenter.onCalculate(first, second)` ->
`CalculatorEngine.calculate` (parse, thousands separators removed, validation, computation, size check) -> a `CalculationRecord`
is appended to the presenter history and the formatted result becomes the first field -> `FileCalculationHistoryStore.saveHistory`
writes the header plus one line per record (`OPERATION\tfirst\tsecond\tresult`, result in plain notation with a point) to
`history.tsv.tmp`, forces it to disk and moves it over `history.tsv` (atomic move when supported) -> the presenter builds a
`CalculatorViewModel` handed back to the frame. At start-up the store reads the file (UTF-8), drops a BOM, checks the version header,
and **recomputes every line through the engine** instead of trusting the stored result.

## 5. Current build and test commands (Commandes de build et de test)

| Command | Effect |
|---|---|
| `.\check.ps1` | `mvnw.cmd -B verify` (compile with `-Xlint:all -Werror`, JUnit 5 tests, jar) |
| `.\run.ps1` | `mvnw.cmd -B -q package -DskipTests`, then `java -jar target\calc.jar` |
| `.\package.ps1` | `mvnw.cmd -B verify`, then `jpackage` app-image `dist\Calc\Calc.exe` and `dist\Calc-portable.zip` |
| CI | `.github/workflows/ci.yml`: `mvnw -B verify -Dcalc.ui.required=true` on Ubuntu (Java 17 and 21, under `xvfb-run`) and Windows (Java 17) |

`package.ps1` was not run in E00 (it deletes and rebuilds `dist\`, which is outside the scope of an assessment).

## 6. Verification results (Résultats de la vérification)

`.\mvnw.cmd -B clean verify` (run before any file was added, and again at the end of E00): **BUILD SUCCESS, 116 tests, 0 failures,
0 errors, 0 skipped**, exit code 0, about 6 s.

| Test class | Tests | Static count of assert/fail calls |
|---|---|---|
| `calc.engine.CalculatorEngineTest` | 18 | 48 |
| `calc.engine.FileCalculationHistoryFormatTest` | 6 | 24 |
| `calc.engine.FileCalculationHistoryStoreTest` | 10 | 25 |
| `calc.ui.CalculatorFrameTest` | 6 | 47 |
| `calc.ui.CalculatorHistoryBackupTest` | 8 | 34 |
| `calc.ui.CalculatorHistoryLoggingTest` | 4 | 11 |
| `calc.ui.CalculatorHistoryPersistenceTest` | 4 | 17 |
| `calc.ui.CalculatorHistoryStatusTest` | 7 | 15 |
| `calc.ui.CalculatorPresenterTest` | 14 | 41 |
| `calc.ui.CalculatorRoundingPolicyTest` | 17 | 19 |
| `calc.ui.CalculatorSizeLimitsTest` | 22 | 62 |

JUnit does not report assertion counts, so the last column is a source count (`assert*(` / `fail(` occurrences; a parameterised test
counts once). `CalculatorFrameTest` needs a display: it ran here (6 tests, 1.4 s, none skipped) because this session has one. Without a
display it skips (`assumeFalse`) unless `-Dcalc.ui.required=true`, in which case it fails (`CalculatorFrameTest.java:34-43`).
The `AVERTISSEMENT` lines in the Maven log are expected `java.util.logging` output of tests that load invalid histories.

## 7. Audit findings A1-A8 (Statut des constats)

Proofs come from the code, from the existing tests, from the golden file `testdata/golden/engine-v1.tsv` and
`testdata/legacy-v1/README.md`, and from a throwaway program (kept outside the repository) run on temporary files.

**A1 - one invalid line discards the whole history and is overwritten: partially fixed.**
- A corrupted line no longer discards the other lines: `FileCalculationHistoryStore.java:101-118` skips only the bad line
  (`skippedLines++`) and restores the rest. Observed (`one-corrupted-line.tsv`): 2 entries restored, 1 skipped, status
  "Historique restauré partiellement : 1 entrée ignorée. Une copie du fichier d'origine a été conservée."
- A copy `history.tsv.<timestamp>.bak` is made before anything is rewritten (`:118`, `:177-200`). Throwaway run: after one more
  calculation the main file holds the 2 valid lines plus the new one, and the `.bak` still contains the `GARBAGE` line. No data is
  lost, but the bad line leaves the main file at the next save.
- A UTF-8 BOM no longer triggers the case (`:84`, `:105`): `bom.tsv` restores 2 entries, 0 skipped.
- If the only line is invalid (`inconsistent.tsv`) the history is shown empty with an error status; the file is backed up the same way.
- An unsupported version header (`future-version.tsv`) refuses all later writes (`writeProtected`), so a newer file is never overwritten.

**A2 - no size bound, calculations on the Swing thread: partially fixed.**
- Bounds exist: input at most 1005 characters (`DecimalPolicy.java:14`), result at most 1000 digits (`:13`, `:101`), exponent +-999
  (`:12`), and a digit estimate is made before `pow` (`CalculatorEngine.java:99-102`). Golden: `2 ^ 99999999999` gives the exponent
  range error; `12345678901 ^ 100` gives "Le résultat dépasse 1000 chiffres"; 1006-character operands give "trop long".
- Chained powers can no longer grow: a result is always at most 1000 digits and the next operand at most 1005 characters.
- Throwaway timings of the heaviest accepted cases (single run, JDK 17): square root of a 1000-digit number 21 ms, `2 ^ -999` 1 ms,
  500x500-digit product under 1 ms, `1 / 7` under 1 ms. No freeze.
- Not fixed: the calculation still runs on the Swing thread (`CalculatorFrame` has no `SwingWorker` or executor; only `invokeLater`,
  `CalculatorFrame.java:342`, `Main.java:16`). It is now bounded by the limits above, not moved off the EDT.

**A3 - absolute 12-decimal rounding shows non-zero results as 0: fixed.**
`DecimalPolicy.roundApproximation` / `approximationScale` (`:112-118`) keep 12 significant digits. Golden: `1 / 3000000000000` =
`0,000000000000333333333333`; `3 ^ -30` = `0,00000000000000485693574962`; `10 ^ -13` = `0,0000000000001`;
`√0,00000000000000000000000001` = `0,0000000000001`. `CalculatorRoundingPolicyTest` (17 tests) pins the rule.

**A4 - focus deduced from the message text, wrong exponent message: fixed.**
`CalculationException` carries an `InputField`, and the presenter uses it, not the text (`CalculatorPresenter.java:233-238`).
Throwaway run: `2 ^ 1,5` -> SECOND_NUMBER, `0 ^ -1` -> FIRST_NUMBER, `8 / 0` -> SECOND_NUMBER, `√-4` -> FIRST_NUMBER.
`2 ^ 99999999999` now says "L'exposant doit être un entier compris entre -999 et 999." (the integer message is kept for `2 ^ 1,5`).

**A5 - inconsistent entries accepted, causes lost, nothing logged: fixed (the cause is logged, not shown in the UI).**
Each line is re-evaluated by the engine (`FileCalculationHistoryStore.java:202-213`). `ADDITION\tabc\t2\t5` is skipped
(`inconsistent.tsv`: 0 restored, 1 skipped); `ADDITION\t1\t2\t5` is restored as `1 + 2 = 3` (`wrong-result.tsv`); `legacy-float.tsv`
comes back as `0,1 + 0,2 = 0,3`. Each skipped line is logged with its line number and cause (`:115`, covered by
`CalculatorHistoryLoggingTest`). The user only sees the count.

**A6 - non-atomic write, two instances overwrite each other: partially fixed.**
Writes go to `history.tsv.tmp`, are forced to disk and moved with `ATOMIC_MOVE` (`:153-175`); a crash cannot leave a half-written file.
Not fixed: there is no lock and no merge. Throwaway run with two presenters on one file, each adding one calculation: the file finally
contains only the second instance's entry (`ADDITION 2 2 4`); the first one is lost.

**A7 - tooling (tests in the JAR, UI test fails headless, no build tool, no CI): fixed locally; remote CI observed, not re-run.**
Maven plus wrapper (`pom.xml`). Tests live in `src/test`, so `jar tf target/calc.jar | grep -ci test` prints `0`. The UI test skips
headless (`CalculatorFrameTest.java:43`) unless `calc.ui.required=true`. CI exists (`ci.yml`); `gh run list` shows the latest `main` run
(`docs: align README with final behaviour and known limits`, 2026-10-26) as `completed success`.

**A8 - unaccented UI texts, no-break spaces refused: fixed.**
French accents are back in the engine messages and in the UI (`CalculatorFrame.java` and `OperationText.java` contain accented strings;
golden messages such as "Le résultat dépasse 1000 chiffres"). `DecimalPolicy.removeThousandsSeparators` (`:40-46`) accepts space,
no-break space and narrow no-break space as thousands separators in the integer part; edge spaces are stripped. Golden: `1 000 + 1` =
`1001`, `1<U+00A0>000 + 1` = `1001`, `1<U+202F>000<U+202F>000,5 + 0` = `1000000,5`, while `1,000.5` and `1e3` are refused.

## 8. Reference samples produced (Échantillons de référence)

Produced by `tools/baseline/Baseline.java` (how to regenerate: `tools/baseline/README.md`).

- `testdata/golden/engine-v1.tsv`: 70 cases, columns `operation, first, second, outcome, display, reusable`; 47 OK and 23 ERROR. All six
  operations. Every mandatory case is present (`0,1 + 0,2`, `1 / 3`, `2 / 3`, `-2 / 3`, `1 / 3000000000000`, `√2`,
  `√0,00000000000000000000000001`, `3 ^ -30`, `10 ^ -13`, `0 ^ 0`, `0 ^ -1`, `2 ^ 1000`, `2 ^ 1,5`, `2 ^ 99999999999`, `8 / 0`, `√-4`,
  `abc`, `1e3`, `1 000`, `1<U+00A0>000`, `1,000.5`). `2 ^ 999` is stored as length 301 plus SHA-256.
- Messages covered: no operation, missing first/second, invalid first/second, first/second too long, division by zero, non-integer
  exponent, exponent out of range, `0` to a negative power, root of a negative, result over 1000 digits (product and power variants).
  The remaining message ("Le calcul n'a pas pu être effectué sur des nombres de cette taille.", `CalculatorEngine.java:11-12`) is a
  defensive catch of `ArithmeticException`; none of the extreme inputs tried (1000-digit and 1000-decimal operands in every operation)
  triggered it, so it has no golden line.
- Differences from the brief's expectations, recorded as observed: `2 ^ 1000` is now an **error** (exponent limit 999); `1 000` with an
  ordinary space is now **accepted** (= 1001).
- `testdata/legacy-v1/`: 11 history files (`nominal`, `rounded`, `legacy-float`, `crlf`, `bom`, `one-corrupted-line`, `inconsistent`,
  `empty`, plus `wrong-result`, `bom-header`, `future-version`), a `README.md` with the observed load behaviour of each, and a
  `.gitattributes` (`* -text`) that stops git from rewriting the CRLF and BOM bytes.
- Determinism: the program was run twice into two different roots; `diff -r` reported no difference.

## 9. Risks and prerequisites for what follows (Risques et prérequis)

- **JDK 21: not available locally** (JDK 17.0.15 only). Epics that require Java 21 need it installed or downloaded first.
- **Gradle: not available**, and not needed so far: the project is already on Maven (wrapper, `pom.xml`, JUnit 5, CI). A Gradle
  migration as described in the brief would be a real decision (an ADR), not a first step.
- **`gh`: authenticated** (`TGDev2`, `repo` and `workflow` scopes); CI runs are readable.
- The Epic brief, `CLAUDE.md` and `BACKLOG.md` partly describe an older state (`Calcul/`, `Graphique/`, `check.ps1` via `javac`, no CI).
  `CLAUDE.md` is untracked (excluded locally) and should be refreshed by its owner.
- Remaining behaviour gaps from section 7: calculations on the Swing thread (bounded), two simultaneous instances overwrite each other,
  skipped-line causes only in the log.
- The golden files pin today's behaviour, including the limits (exponent 999, 1000 digits, 1005 characters) and the French messages with
  accents; any wording change must go through an ADR and a regeneration.
- `testdata/` is marked `-text`: do not run line-ending normalisation over it.
- Not verified here: the visual rendering of the UI, `package.ps1` and the packaged `Calc.exe`.
