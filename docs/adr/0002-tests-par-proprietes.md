# 0002 - Property-based tests and the length of the canonical text

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E04.

## Context (Contexte)

The exact engine of E06 will replace the decimal arithmetic. Example-based tests pin the cases someone thought of; they
do not say that the promises of the engine, the history file and the presenter hold for every input. E04 needs a safety
net written on the public APIs, usable again by E05, E07, E09, E10 and E11.

Running the first properties showed two defects that the example tests had missed (see `docs/TESTS.md`). One of them
needs a decision: an operand of 1005 characters without integer digit (`,777...`) was accepted, its canonical text
`0,777...` has 1006 characters, so the history line written for it was refused as "too long" when the file was read
back, and the entry was lost.

## Decision (Décision)

1. **jqwik 1.10.2** is the property engine (latest stable version at the time of writing; built against JUnit Platform
   1.14.4, the version managed by the JUnit BOM already used). It runs next to JUnit Jupiter under `useJUnitPlatform()`,
   so `gradlew check` runs everything. The configuration lives in `src/test/resources/junit-platform.properties`: jqwik
   has ignored a `jqwik.properties` file since version 1.6.0.
2. **Two tags**, `property` and `robustness`, both `net.jqwik.api.Tag` (jqwik ignores the JUnit annotation of the same
   name, silently). A guard test fails when a jqwik class lacks one.
3. **Independent oracle.** Arithmetic is compared with `ArithmeticOracle`, which imports nothing from production code
   and has its own reading and display rules.
4. **Isolation of the rounding policy.** The only properties that depend on the current 12-decimal policy are in
   `RoundingPolicyProperties`, tagged `rounding-policy` and documented as "to be revised in E06".
5. **The canonical text must fit the input bound.** The engine now refuses an operand whose canonical text is longer
   than `CalculationLimits.MAX_INPUT_LENGTH`, in addition to the typed text. Only an unsigned or negative operand of
   exactly 1005 characters without integer digit is affected; the message is the existing "trop long" one.
6. A history header whose version does not fit in an `int` is a newer format (writes refused, file kept), not an error.

## Alternatives considered (Alternatives considérées)

- **Another property library.** Not compared in depth: jqwik is the engine the Epic asks for, runs as a JUnit Platform
  engine (no change to `check`), and has built-in stateful testing, edge cases and shrinking.
- **Raising the bound to 1006 for the stored form.** It moves the problem by one character and gives the loader a
  different bound from the keyboard.
- **Letting the loader accept 1006 characters.** It needs a second code path in the engine for a single caller.
- **Keeping an `@Example` only.** It would have fixed this input and left the rule ("what is saved can be read back")
  untested for the next engine.

## Consequences (Conséquences)

- `gradlew check` takes about 15 seconds longer (see `docs/TESTS.md` for the measure). Tags allow a faster subset.
- A fixed-seed or tag mistake can no longer hide a property: the guard test checks the tags.
- E06 must revisit `RoundingPolicyProperties`; the other properties must pass unchanged on the new engine.
- The rule of point 5 is now part of the public behaviour of the input and is pinned by `ErrorProperties`.
