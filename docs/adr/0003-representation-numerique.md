# 0003 - Numeric representation: exact rationals and certified intervals

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E05.

## Context (Contexte)

The engine computes in `BigDecimal` and rounds every non-exact quotient, root and negative power once, to 12 significant
digits with at least 12 decimals (`DecimalPolicy`). The Epic brief describes an older policy (12 absolute decimals, so
`1 / 3000000000000` shown as `0`); that symptom was already fixed by the significant-digit rule. What remains, measured
on the current engine (`calc.engine.CalculatorEngine`, called directly):

| Calculation | Current result |
|---|---|
| `1 / 3`, then the result `* 3` | `0,333333333333`, then `0,999999999999` |
| `√2`, then the result squared | `1,414213562373`, then `1,999999999999731161391129` |

A rounded result looks exactly like an exact one, its error is not known to anyone, and the rounding of each step is
carried into the next one. The goal of the project is a calculator that is never silently wrong: it must know what it
knows exactly and give a guaranteed error bound for what it approximates. E05 builds only the numeric core, as an
internal library with its own tests; E06 integrates it into the engine.

## Decision (Décision)

1. **Model.** A number is a `CertifiedValue`: a closed interval `[lower, upper]` of `Rational`s with `lower <= upper`.
   It is **exact** when `lower == upper`, otherwise **approximate**, and the true mathematical value is guaranteed to
   lie in the interval. Every operation keeps the **inclusion property**: if `x` is in `X` and `y` in `Y`, then
   `x op y` is in `X op Y`. An operation on exact values stays exact (and is the exact rational), except a square root
   that is not exact. `midpoint()` and `radius() = (upper - lower) / 2` give the centre and the guaranteed absolute error.
2. **`Rational` is implemented in the project** (package `calc.number`, about 350 lines with comments). It is
   immutable and always canonical: denominator `> 0`, `gcd(|p|, q) = 1`, zero is `0/1`, so `equals`, `hashCode` and
   `compareTo` agree. Its arithmetic (`add`, `subtract`, `multiply` with cross reduction, `divide`, `negate`, `abs`,
   `reciprocal`) is exact and not bounded: a result is at most about twice the size of the operands, and its cost
   (dominated by a `gcd`, quadratic in that size) is therefore bounded by them. The two operations
   whose cost is not bounded by their operands check the limits **before** computing: `pow(int)` (a `b`-bit component
   raised to `n` has at least `(b - 1) * n + 1` bits: refused at once when that is already too many, otherwise computed
   and checked exactly, so no representable power is refused) and `parse(String)`. Other services: `isInteger`,
   `bitLength` (the larger component, in bits), `toExactDecimal` (present exactly when the denominator is `2^a * 5^b`,
   with the smallest scale), `toString` (`p` or `p/q`, read back by `parse`).
3. **Literal grammar** of `Rational.parse`, without spaces: `[+-]? digits? ([.,] digits?)? ([eE] [+-]? digits)?` with at
   least one digit in the mantissa, ASCII digits only, or two such decimals separated by `/` (`-1,5/4e2`). The checks
   come in this order, each one on both members of a fraction before the next: length, grammar, scientific exponent,
   size (a digit count that already proves the size is
   refused before any power of ten is built; `significant / 10^s` with no trailing zero has a denominator of at least
   `2^s`), zero denominator. Spaces, thousands separators and other user conveniences stay in the engine (E06).
4. **Outward rounding.** The component limit applies to every bound of a `CertifiedValue`. Each bound of an
   approximate result of `+ - * /`, of a power and of a root is a binary number `m * 2^k` with `|m| < 2^128`
   (`BOUND_SIGNIFICANT_BITS`, about 38 decimal
   digits). An operation computes its candidate bounds exactly, then rounds the lower one **down** and the upper one
   **up** to the grid of step `2^(e + 1 - 128)`, where `2^e <= |bound| < 2^(e + 1)`: the step is relative to the
   magnitude, each rounding widens a bound by less than `2^-127` of its magnitude, and the inclusion property is kept.
   A bound already on the grid is left unchanged; an exact value is never rounded. The finest step is `2^-4095`, so a
   rounded denominator always fits the limit: an approximate bound below `2^-3968` keeps fewer significant bits (it may
   reach 0 from above) but stays on the right side. A bound whose magnitude reaches `2^4096`, before or after rounding, is refused
   (`SIZE_LIMIT_EXCEEDED`). Approximate powers are computed by square and multiply, every intermediate rounded in the
   direction of the bound it serves.
5. **Square root.** Exact when the value is exact and its reduced numerator and denominator are perfect squares
   (`√(9/4) = 3/2`). Otherwise, for a precision of `p` bits, each bound is computed with `BigInteger.sqrt` on the value
   scaled by `4^j` so that `2^(2p + 2) <= value * 4^j < 2^(2p + 4)`: lower bound `floor(sqrt(floor(T))) / 2^j`, upper
   bound `ceil(sqrt(ceil(T))) / 2^j`. Then `lower^2 <= x <= upper^2` and the width is at most `2^-(p + 1)` times the
   lower bound (the contract states `2^-p`). Default precision 80 bits (about 24 significant digits, enough to show 12
   guaranteed digits with margin), configurable from 1 to 120 bits.
6. **Domain rules and typed errors.** `NumericException` (checked, like `CalculationException`) carries a `NumericError`
   code and no text for the user; its message is the code name. Mistakes of the caller (`lower > upper`, precision out
   of range, `null`) are `IllegalArgumentException` / `NullPointerException`.

   | Code | Raised when |
   |---|---|
   | `DIVISION_BY_ZERO` | divisor exactly 0, exact 0 to a negative power, `p/0` literal |
   | `DIVISOR_MAY_BE_ZERO` | approximate divisor whose interval contains 0; such a base to a negative power |
   | `ROOT_OF_NEGATIVE` | square root with `upper < 0` |
   | `ROOT_OF_POSSIBLY_NEGATIVE` | square root with `lower < 0 <= upper` |
   | `EXPONENT_NOT_INTEGER` | exact exponent that is not an integer |
   | `EXPONENT_NOT_EXACT` | approximate exponent (whether it is an integer is unknown) |
   | `EXPONENT_OUT_OF_RANGE` | power exponent beyond `±4096`, scientific exponent beyond `±9999` |
   | `SIZE_LIMIT_EXCEEDED` | literal longer than 4100 characters; a member, a result or a bound beyond 4096 bits |
   | `MALFORMED_INPUT` | text outside the literal grammar |

   Other rules: `x^0 = 1` for every `x`, including 0 and intervals around 0; an odd power is monotone; an even power of
   an interval that contains 0 is `[0, max(lower^n, upper^n)]`; a division by an interval that does not contain 0 is a
   multiplication by `[1 / upper, 1 / lower]`; a product is the smallest and largest of the four products of the bounds.
7. **Limits, centralised in `NumericLimits`** and justified by the measurements below.

   | Limit | Value | Reason |
   |---|---|---|
   | `MAX_COMPONENT_BITS` | 4096 | largest numerator or denominator; holds every integer of 1233 digits, so every result of the current engine (at most 1000 digits). The `gcd` of a sum grows quadratically: 0.8 ms at 4096 bits, 2.7 ms at 8192, 9.8 ms at 16384 |
   | `MAX_LITERAL_LENGTH` | 4100 | the longest text the core writes for a value within the limits is the exact decimal of `-(2^4096 - 1) / 2^4095`: 4098 characters; every text of the core can be read back |
   | `MAX_SCIENTIFIC_EXPONENT` | 9999 | a value within the limits never needs more than 1234 + 4100 in a literal, so no representable literal is refused; the exponent stays small and is checked before any power of ten |
   | `MAX_POWER_EXPONENT` | 4096 | beyond it, `\|p\|^n` or `q^n` exceeds 4096 bits for every base except 0, 1 and -1; also bounds an approximate power to 12 squarings |
   | `BOUND_SIGNIFICANT_BITS` | 128 | size budget of an approximate bound: about 38 digits, far above the 24 of a root and the 12 shown |
   | `DEFAULT_PRECISION_BITS` / `MAX_PRECISION_BITS` | 80 / 120 | about 24 significant digits by default; a root bound has at most `p + 3` significant bits, so 120 keeps it within the budget |

### Measurements (Mesures)

Machine: Windows 11, Temurin 21.0.7, single thread, `tools/numeric-limits/NumericLimitsBenchmark.java` (fixed seed
20261026, two runs, the larger value kept). "first" is the first call in a fresh JVM, "best" the best of 30 calls.
Budget: 10 ms per unit operation.

Raw cost of `p/q + r/s` (three products, then the `gcd` of `2N`-bit numbers), best of 30:

| Component bits | 2048 | 4096 | 8192 | 16384 | 32768 |
|---|---|---|---|---|---|
| ms | 0.25 | 0.84 | 2.66 | 9.78 | 38.26 |

Numeric core at the limits (components of 4096 bits, odd and random):

| Operation | first (ms) | best (ms) | Outcome |
|---|---|---|---|
| parse of the longest decimal (4098 characters) | 5.37 | 1.69 | computed |
| approximate `[a, c] * [-b, b]` | 2.48 | 1.21 | computed |
| approximate `[-b, b] / [a, c]` | 1.41 | 1.27 | computed |
| exact `a + b` | 1.57 | 0.67 | refused, 8192 bits |
| `Rational` `a + b` | 0.93 | 0.78 | computed |
| square root of a 4096-bit non-square at 120 bits | 1.54 | 0.76 | computed |
| square root of `[a, c]` | 0.89 | 0.75 | computed |
| `Rational` `a * b` / `a / b` | 0.75 / 0.43 | 0.38 / 0.37 | computed |
| parse of a 4096-bit fraction (2468 characters) | 0.43 | 0.34 | computed |
| exact decimal of `-(2^4096 - 1) / 2^4095` | 0.35 | 0.17 | computed |
| `[1, 1 + 2^-100] ^ 4096` | 0.21 | 0.03 | computed |
| `3 ^ 2584` (4096 bits) | 0.03 | 0.01 | computed |
| `2^999 ^ 999` | 0.02 | 0.001 | refused before computing |
| `[2^999, 2^999 + 1] ^ 999` | 0.08 | 0.02 | refused at the third squaring |
| literal of 100 000 digits | 0.01 | 0.001 | refused on its length |
| literal of 4100 digits | 0.05 | 0.04 | refused on its digit count |
| `1e9999`, `0,(4095 zeros)1` | 0.03, 0.24 | 0.004, 0.04 | refused before any power of ten |
| exact `(2^4096 - 1)^2` | 0.04 | 0.01 | refused |

The slowest operation within the limits takes 1.7 ms (5.4 ms on the first call of a fresh JVM): about six times under
the budget, twice on a cold start. The huge inputs of the brief (`2^999` raised to 999, a literal of 100 000 digits) are
refused in about a microsecond once warm. `NumericLimitsTest` keeps both budgets as permanent tests (best of 8 after a
warm-up, below 10 ms).

## Alternatives considered (Alternatives considérées)

- **`BigDecimal` at a fixed precision (the current engine).** Exact for `+ - *` on decimals only; every quotient or root
  is rounded without any record of the error, and the rounding of one step feeds the next (`0,999999999999` above).
- **Binary floating point (`double`).** Not exact even for `0,1 + 0,2`, 53 bits only, and nothing tells the user how
  wrong a result is.
- **An arbitrary-precision floating-point library** (bindings to MPFR or Arb, or a pure-Java one). Either native code
  to ship on three operating systems or a large dependency in a project that has none; results such as `1/3` would still
  be approximations, and the exact cases (`(1 / 3) * 3 = 1`) are the ones the calculator must get right. Interval
  arithmetic on top of exact rationals covers what is needed in about 880 lines that the tests can check against an
  independent oracle.
- **Apache Commons Numbers `BigFraction`** (`commons-numbers-fraction` 1.3, released 2026-04, Apache License 2.0,
  actively maintained, depends on `commons-numbers-core`). Its source shows: no size limit at all (`pow` runs
  `BigInteger.pow` whatever the exponent), no square root, `parse` reads integers and `p / q` only and removes commas
  before reading (`"1,5"` becomes 15), negative denominators are kept as given (`toString` gives `1 / -2`), no
  `isInteger`. The core would need a wrapper for the limits, the parsing, the canonical sign and the root, about as long
  as the implementation itself, plus a first runtime dependency. Kept in mind if the numeric needs grow (continued
  fractions, other conversions).
- **A decimal grid for outward rounding.** It would give bounds that print nicely, but costs divisions by powers of ten
  at every operation; formatting belongs to E06 and works from any rational bounds.
- **Rounding only when a bound exceeds a size in bits per component.** Bounds of chained approximate operations would
  grow towards 4096 bits (about 1 ms per operation instead of microseconds), and the size after rounding would depend on
  the magnitude. The significant-bit budget keeps every approximate bound small and of the same shape.
- **8192 bits per component.** 2.7 ms for a sum already, so less margin on slow CI runners; 4096 bits already cover
  every result of the current engine.
- **Refusing tiny approximate values** instead of using the finest grid. Both are correct; refusing would fail
  calculations such as `√2 / 10^1200` that have a valid enclosure.
- **An unchecked exception.** Rejected for consistency with `CalculationException` and so that the compiler makes E06
  translate every code.

## Consequences (Conséquences)

- E06 can replace `DecimalPolicy`: exact results stay exact through reuse, approximate ones carry a proved error bound.
  It must translate each `NumericError` into a French message and an `InputField`, format a value from its interval
  (only the digits shared by both bounds are guaranteed), revisit `RoundingPolicyProperties`, and align
  `CalculationLimits` with `NumericLimits` (the input field may keep a shorter bound than the 4100 characters of a
  literal).
- Interval arithmetic overestimates when a value appears twice: `X - X` is `[-2r, 2r]`, not 0, and widths grow along a
  chain. The guarantee holds; the width is the honest price, and E06 may recompute with more precision when a result
  is too wide to show.
- An approximate value built with `between` keeps its bounds as given, and so does its `negate()`; the first
  arithmetic operation puts them on the binary grid, so `X + 0` may differ from `X` in its representation (never in its
  guarantee).
- `midpoint()` and `radius()` are not checked against the limit (at most twice it, plus two bits).
- Each unit operation is bounded (table above), not a whole calculation: the bounds of E09 (expression depth) and E10
  (document size) will bound the number of operations.
- Still at risk: the budgets are measured on one machine; a CI runner several times slower would still pass the 10 ms
  test with the measured margin of six, but not one fifty times slower.
