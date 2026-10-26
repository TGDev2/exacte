# 0001 - Hardening: lossless history, atomic writes, bounds, typed errors

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E03. Decisions 1 (tolerated deviations) and 5 (bounds) are superseded by 0004 (exact engine, E06).

## Context (Contexte)

The baseline audit (`docs/ETAT-DES-LIEUX.md`, section 7) left five findings open or partly open on the current
decimal engine: A1 (an invalid line loses the history), A2 (no size bound), A4 (focus guessed from the message text),
A5 (inconsistent entries accepted, causes lost) and A6 (non-atomic write). Earlier Epics had already fixed a large part
of them; E03 completes what was missing. The exact engine of E06 will replace the arithmetic later, so every guarantee
is expressed on the public APIs (engine, store, presenter), not on internals.

## Decision (Décision)

1. **Rejection rule.** A history line is restored only if the engine can recalculate it **and** the stored result
   agrees with the recalculation. Two deviations are tolerated: the result rounded under the current policy
   (`DecimalPolicy.roundApproximation`) and the floating-point drift of the oldest versions (`0.30000000000000004`),
   which rounds to the right value under that same policy. Anything else is rejected and counted.
2. **Structured load result.** `HistoryLoadResult` carries the valid records, one `RejectedLine` (line number, typed
   `Cause`, detail without the content of the line) per rejection, and the backup path. An exception is only thrown when
   the file cannot be read at all.
3. **Backup before any overwrite.** If at least one line is rejected or the file is unreadable or newer than the
   supported version, the original is copied to `history.tsv.AAAAMMJJ-HHMMSS.bak` (numbered suffix if the name exists,
   never overwritten) **at load time**, before any write is possible, so the presenter can show the real path.
4. **Atomic write utility.** `AtomicFileWriter` writes `<name>.tmp` next to the target, forces it to disk, then moves it
   with `ATOMIC_MOVE | REPLACE_EXISTING`; if the file system cannot, it falls back to `REPLACE_EXISTING` and logs it. On
   error the temporary file is deleted and the target is untouched. It knows nothing about the history and is meant to
   be reused by later formats.
5. **Bounds, centralised in `CalculationLimits`** and kept at their current values: operand 1005 characters, result
   1000 digits (a power is estimated before computing as `max(precision, scale) * |exponent|`), exponent between -999
   and 999. The values are justified by measurement (below), not by the round numbers suggested in the brief.
6. **Typed errors.** `CalculationException` carries an `InputField` (`FIRST`, `SECOND`, `NONE`) from the engine
   package; the presenter maps it to a `FocusTarget` and no longer reads the message. An integer exponent outside the
   range reports « L'exposant doit être compris entre -999 et 999. ».

### Measurements (Mesures)

Machine: Windows 11, Temurin 21.0.7, single thread, `calc.engine.CalculatorEngine` called directly. "cold" is the first
call in a fresh JVM, "max" the slowest of 15 further calls. Budget: 50 ms.

| Case | cold (ms) | max warm (ms) | Outcome |
|---|---|---|---|
| square root of a 1000-digit number | 8.8 | 5.7 | accepted (501 digits) |
| square root of a 1005-character number | 1.7 | 2.3 | accepted |
| 500-digit x 500-digit product | 1.5 | 1.0 | accepted (1000 digits) |
| 1000-digit / 999-digit division | 2.7 | 0.9 | accepted |
| 1005-digit / 1005-digit, non-terminating | 0.6 | 0.6 | accepted (14 digits) |
| 1000 + 1000 digits | 1.6 | 0.7 | refused (1001 digits) |
| `9 ^ 999` | 0.1 | 0.1 | accepted (954 digits) |
| `7 ^ -999` | 0.4 | 0.2 | accepted (858 digits) |
| `99 ^ 500` | 0.1 | 0.1 | accepted (998 digits) |
| `2 ^ 999` then `^ 999` | 0.05 | 0.07 | refused before computing |
| 60 000-digit input | 0.02 | 0.001 | refused before parsing |

Random hunt: 6 000 random operations with operands of 1 to 1005 characters, seed 20261026: 4 240 accepted, 1 760
refused, slowest 1.9 ms. The slowest structured case (square root of 1000 digits) is 5.7 ms warm and 8.8 ms cold, about
six times under the budget, so the limits stay as they are. A permanent test
(`CalculatorSizeLimitsTest.testWorstCaseBoundedCalculationsFinishUnderFiftyMilliseconds`) keeps the 50 ms budget honest
with a best-of-8 measure.

## Alternatives considered (Alternatives considérées)

- **Round-number limits (100 characters, 10 000 digits).** 100 characters would break the golden reference data and
  real usage for no gain; 10 000 digits would be roughly 100 times slower on a square root (quadratic cost). The measured
  values are both safer and already pinned by the reference outputs.
- **Back up lazily on the first write.** It would avoid a new `.bak` per launch for a file that stays corrupted, but the
  status could not show a copy that does not exist yet. The copy at load time is the stronger guarantee; a retention
  policy is left for the persistence Epic (E07).
- **Trust the stored result when it is readable.** Simpler, but it hides corrupted or hand-edited files, which is the
  silent data problem this Epic removes.
- **Unique temporary file names.** Not needed without concurrent writers (the lock is E07); the fixed `<name>.tmp` keeps
  a failed write easy to simulate and to clean.

## Consequences (Conséquences)

- Entries written by versions older than the 12-significant-digit policy whose small results were rounded to 12 absolute
  decimals now differ from the recalculation and are rejected (with a backup) instead of being silently recomputed. The
  v1 to v2 migration (E07) is the place to revisit them.
- `wrong-result.tsv` in `legacy/v1` used to restore 1 entry; it now restores 0 and counts 1 rejection. The restore status
  messages changed (counts per cause and the backup path); the reference README was regenerated.
- The exponent range message lost the word « entier » (the non-integer case keeps its own message); four golden lines
  changed accordingly.
- Calculations still run on the Swing thread; the bounds above are what keeps them short.
