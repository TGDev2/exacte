# 0013 - Measured quality: coverage and mutation thresholds, and a generated conformity report

## Status (Statut)

Accepted. Date: 2026-10-27. Epic E15.

## Context (Contexte)

The project promises to be reliable. Until E14 that promise rested on the number of tests and on prose in `docs/TESTS.md`.
Nothing measured what the tests detect, nothing stopped a regression of the coverage, and nothing was published next to a release.

## Decision (Décision)

1. **JaCoCo** (0.8.16) measures line and branch coverage. `gradlew check` fails when a package is under its threshold
   (`jacocoTestCoverageVerification`, rules per package read from `config/quality-thresholds.properties`). The `calc.ui*` packages are
   reported but have no threshold: their coverage depends on a display being present for the `ui` tests.
2. **PIT** (1.30.1, Gradle plugin 1.19.0, JUnit 5 plugin 1.2.3) mutates the five critical packages: `calc.number`, `calc.format`,
   `calc.expression`, `calc.notebook`, `calc.persistence`. The tests run by PIT are the example tests: the `ui`, `property` and
   `robustness` tags are excluded, and so are the tests of speed and of huge files. Reason: a first run with every tag but `ui` did not
   finish in 10 minutes; the examples-only run takes about 6 minutes. This is a limit of the measure, written in `docs/TESTS.md`.
3. **Thresholds are measured values, set slightly below** (never invented), per package, in `config/quality-thresholds.properties`.
   PIT has one global threshold only, so the per-package mutation thresholds are checked by `conformityReport`.
4. **Strategy**: mutation takes several minutes, so it does not run on pull requests. It runs on `main`, on demand and in `release.yml`.
   A pull request runs `check`, which includes the coverage thresholds.
5. **`conformityReport`** (`gradle/conformity.gradle.kts`) reads the real results (JUnit XML, JaCoCo XML, PIT XML) and writes
   `build/reports/conformite/RAPPORT-CONFORMITE.md`. It fails, after writing the report, when a threshold is not met, and fails without
   a report when a source file is missing or empty. It does not run the tests itself (`gradlew conformity` does: check, pitest,
   conformityReport).
6. **Guarantees** are the table "Garanties du produit" of `docs/TESTS.md`: one row per promise with the test classes that prove it. The report
   fails for a guarantee without a test class or whose class produced no result, and flags a guarantee with a failed or skipped test.

## Alternatives considered (Alternatives)

- Mutation in `check`: rejected, it would add about 6 minutes to every pull request.
- Including the property tests in PIT: they kill more mutants but multiply the duration; kept out until a faster setup exists.
- A single global PIT threshold: rejected, a strong package would hide a weak one.
- Inventing round thresholds (80 %, 90 %): rejected, a threshold that is not a measure either blocks nothing or fails at once.

## Consequences (Conséquences)

- A regression of coverage in a gated package fails `check` on every system; a drop of the mutation score fails the `main` job and the release.
- The numbers move with the code: changing a threshold means measuring again and writing the measure in `docs/TESTS.md`.
- Not verified: the first run of these jobs on GitHub (the machine of development is not the CI).
