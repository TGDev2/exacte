# 0004 - Display rules, canonical text and the exact engine

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E06.

## Context (Contexte)

E05 built an exact numeric core (`calc.number`, ADR 0003): a `CertifiedValue` is an exact rational or an interval that
certainly contains the true value. E06 plugs it into the engine, which until now computed in `BigDecimal` and rounded
every inexact result once (`DecimalPolicy`: 12 significant digits, at least 12 decimals, `HALF_UP`).

Where a value used to go through text, and therefore could lose precision (analysis of the code before E06):

| Path | Before E06 |
|---|---|
| Result of a calculation | rounded `BigDecimal` (`DecimalPolicy.divide`, `sqrt`, `pow`): `1 / 3` is stored as `0,333333333333` |
| Result placed in the first field after a calculation | the rounded display text, parsed again at the next calculation |
| Reuse of the last or of a selected history result | the same display text, parsed again |
| History file, result column | `DecimalPolicy.serialize` of the rounded value |
| History file, operand columns | the display text of the parsed operand (exact for decimals) |
| History reload | the operands are parsed and recomputed, the stored result is only checked |

So `1 / 3`, reused and multiplied by 3, gave `0,999999999999`, and every reuse of a root added its rounding error.

This ADR fixes how a value is shown, how it is written so that it can be read back, what the input accepts, and how the
version 1 history is still read. The mode selector and the badge are E08; the new file format is E07.

## Decision (Décision)

### 1. One formatting service

`calc.format.ValueFormatter.format(value, mode)` returns a `FormattedValue`: the main text, the exact fraction (exact
non-integer values), the exact flag, the truncated flag (the text does not show the whole value), the notation-switch
flag (the requested notation could not be used), the error bound (approximate values) and the **canonical text**. It is
the only code that turns a value into text; the engine, the history and the presenter go through it.

### 2. Digits shown

- **12 significant digits** (not 12 decimals), rounded `HALF_UP` on the magnitude, as the previous policy did, but the
  rounding is decided exactly on the rational value. A value that has more digits than shown ends with « … ».
- **Approximate value**: « ≈ » followed by the digits on which the whole interval agrees: the largest `k <= 12` such
  that the lower and the upper bound round to the same number at that decimal position. Since rounding at a fixed
  position is monotone, every point of the interval, the true value included, has that same rounding: the digits shown
  are the correct rounding of the true value. If no digit is common to both bounds (very wide interval), the centre is
  shown with the fewest significant digits that place it inside the interval (section 4). An approximate text always ends
  with « … » (`≈ 1,41421356237…`).
- An approximate value whose interval contains 0 has no established sign: it may really be 0 (`√2 - √2` computed on
  intervals), and it may not (`√2 - √2 + 1e-40`). It reads `|x| ≤ b`, the same text in every mode, where `b` is the
  largest absolute value of its two bounds, rounded up and written like a `±` bound (section 4): `|x| ≤ 4,2 × 10⁻²⁵`.
  Added by E18 (docs/RECETTE.md, defects D1 and D7), which also removes the former text of these values.
- Every other value is non-zero for sure, and **its text never reads as 0**: significant digits are counted from the
  first non-zero digit, and a positional text that would need too many leading zeros switches to the scientific
  notation. Only the exact zero reads `0`.
- A value too small for its bound to stay non-zero is refused. When the finest grid of the outward rounding (`2^-4095`,
  ADR 0003) cannot bound a non-zero value without touching 0, the operation fails with `SIZE_LIMIT_EXCEEDED`, so with the
  same message as its exact twin: `(sqrt(2) / 16) ^ 4096` is refused like `(1/16) ^ 4096`.
- The integer part of a positional text is never cut: an exact integer below 10¹⁵ is shown in full, and an approximate
  value whose guaranteed digits do not reach the units is shown in scientific notation.
- Decimal comma, no thousands grouping, ASCII minus sign, as before.

### 3. Modes

| Mode | Rule |
|---|---|
| Auto | Positional for `10⁻⁶ <= \|x\| < 10¹⁵`, scientific otherwise. Exact and short: the whole decimal. Exact with an infinite or long expansion: 12 significant digits and « … », the fraction in secondary information. Approximate: see above, with the error bound. |
| Décimal | As Auto but always positional, except below `10⁻¹⁵` (more than 15 zeros after the comma: once shortened by the display it would read as 0) and for an approximate value whose units are not guaranteed: scientific, notation-switch flag set. |
| Fraction | `p/q` reduced, `p` when `q = 1`. Longer than 40 characters: the Auto text, notation-switch flag set. Approximate: « ≈ » and the centre as a fraction, under the same length rule (in practice the Auto text, since the centre is a binary number with a long denominator). |
| Scientifique | `m × 10ⁿ`, mantissa with a decimal comma, exponent in superscript digits (`3,33333333333… × 10⁻¹³`); `0` for zero. |

The thresholds `10¹⁵` and `10⁻⁶` are the ones of the brief; `10⁻¹⁵` for Décimal mirrors `10¹⁵` and keeps a positional
text under 30 characters. The text `|x| ≤ b` of an interval that contains 0 is outside this table: it is the same in the
four modes and does not set the notation-switch flag.

### 4. Error bound

For an approximate value, `± r` with `r` the radius of the interval around its centre (the true value is in
`[centre - r, centre + r]`), rounded **up** to two significant digits so that it stays a bound, in scientific notation
(`± 2,1 × 10⁻²⁵` for `√2`).

Corrected by the final acceptance (docs/RECETTE.md): a text that does not round the whole interval, the rough centre of a very
wide interval, is not exactly the centre, and the pair « text ± r » could then exclude the true value. For this text, `r` runs
from the text to the farther bound (`≈ 1,5… ± 6,0 × 10⁻²` for `[1,45 ; 1,56]`, whose radius is only `0,055`). For an interval that
contains 0, `r` runs from 0 to the farther bound: it is the `b` of `|x| ≤ b`.

Corrected after the acceptance (defect D6): the rough centre used to have one significant digit whatever the interval, so it could
lie outside it (`≈ 2…` for `[1,41728 ; 1,58272]`, with only the bound `± 5,9 × 10⁻¹` still true). It now has the fewest significant
digits that place it between the bounds, an equal bound being inside: `≈ 1,5… ± 8,3 × 10⁻²` here, and still `≈ 2…` for
`[1,5 ; 2,5]`. The loop has no limit of 12 digits: the centre is inside an interval of non-zero width, so a fine enough rounding
lands there, in practice by two digits. Digits common to the whole interval do not change.

### 5. Canonical text (the reusable text)

The canonical text is read back by the input grammar and gives back **exactly** the value when it is exact:

- exact integer: its digits; exact value with a finite decimal expansion of at most 40 characters: that decimal
  (`0,3`, `0,125`);
- other exact values: the shortest of the positional decimal (if finite), the exact scientific notation (`1e1000`, if
  finite) and the fraction (`1/3`); on a tie the decimal, then the scientific notation;
- approximate value: the decimal with the fewest significant digits that lies inside the interval (`√2` gives
  `1,414213562373095048801689`), positional up to 40 characters, scientific beyond; `0` when the interval contains 0
  (a point of the interval to read back, never the text of a result; it can only be seen as an operand, in the comment of a
  migrated history entry).

Every canonical text of a value within the core limits fits the input bound (4100 characters): the longest is a
fraction of two 4096-bit components, about 2470 characters.

### 6. Lossless reuse

- A `CalculationRecord` carries the `CertifiedValue`; its reusable value is the canonical text, never the display.
- The presenter remembers, for each input field, the value it put there and the text it wrote. While the field still
  holds exactly that text, the calculation uses the remembered value, interval included, instead of parsing the text;
  as soon as the user changes the text, the link is dropped and the text is parsed.
- The engine has an overload `calculate(Operation, Operand, Operand)`; an `Operand` is either a typed text or a value
  already known. The text overload is a shortcut for typed operands.

### 7. Input grammar

After trimming (ASCII spaces and controls, plus no-break spaces and tabs at the edges, as before): at most 4100
characters, then the grammar of `Rational.parse`: decimal with a comma or a point, optional scientific exponent `e`/`E`
(`1,5e-7`, exponent between -9999 and 9999), or a fraction `p/q` of two such decimals. In the integer part of each
member, groups of three digits may be separated by a no-break space (U+00A0) or a narrow no-break space (U+202F):
`1 000 000,5`. **An ordinary space inside a number is refused**, with a dedicated message when the text would be valid
with no-break spaces: in the coming expression language (E09) a space separates tokens, and `1 000` would be ambiguous.
This is a deliberate change: version 1 accepted `1 000`.

### 8. Limits

The ad hoc bounds of E03 are replaced by those of the core (`NumericLimits`), still checked before any costly work:

| Bound | Before | Now |
|---|---|---|
| Operand length | 1005 characters | 4100 characters (`MAX_LITERAL_LENGTH`), checked before parsing |
| Result size | 1000 digits | 4096 bits per numerator or denominator (about 1233 digits) |
| Power exponent | -999 to 999 | -4096 to 4096 (`MAX_POWER_EXPONENT`), size refused before computing |
| Scientific exponent | not accepted | -9999 to 9999, checked before any power of ten |

Each `NumericError` is translated by the engine into a French message for the operand concerned, with its
`InputField`. The existing messages are kept for the existing cases (`L'exposant doit être un entier.`, division by
zero, root of a negative, `0` to a negative power); the bounds messages carry the new values; the new cases get new
messages (approximate exponent, approximate divisor that may be 0, approximate radicand that may be negative,
fraction with a zero denominator, scientific exponent out of range, number too large or too precise, ordinary space).

### 9. Version 1 history

The format is unchanged until E07. On loading, each line is recomputed exactly from its operand texts, and the stored
result is accepted when it is, as a number:

1. inside the recomputed value (the exact value, or for a root the canonical point of its interval);
2. the recomputed value rounded under an old policy: 12 significant digits with at least 12 decimals (the last
   version 1 policy), or 12 decimals (the policy before it), both `HALF_UP`; for an interval, both bounds must round
   to the same number;
3. a value that rounds under the last version 1 policy to the same number as the recomputed value: the floating-point
   drift of the oldest versions (`0.30000000000000004`).

On writing, the result column holds the canonical text of the result (comma, `1/3`, `1e1000`). Version 1 cannot store an
interval: when an operand was a remembered approximate value, its column holds the canonical point of that interval, and
the result column then holds the canonical text of the exact recalculation of the line from its stored operands, the only
result a reload can verify (rule 1). The entry in memory keeps its interval; the restored entry is that exact
recalculation, a point of the interval that was shown. If the recalculation from the stored point is itself refused (a
power of a remembered root above about 48, whose exact value exceeds 4096 bits), the line cannot be represented in
version 1: it is written as is and rejected, with a backup, at the next load.

`DecimalPolicy` is removed. What remains of it is `LegacyRounding`, used only by rules 2 and 3.

## Alternatives considered (Alternatives considérées)

- **Truncating instead of rounding the shown digits.** « … » would then mean "the expansion continues with these
  digits". Rejected: rounding is what users expect from a calculator, keeps the version 1 digits of `2 / 3`
  (`0,666666666667`) and gives the same guarantee for approximate values (the rounding of every point is the same).
- **The radius as the only guarantee (show the centre with 12 digits).** Simpler, but it shows digits the interval does
  not support; the brief asks for guaranteed digits only.
- **Canonical text always a fraction.** Exact and simple, but `0,3` would come back as `3/10` in the field after a
  reuse. A short finite decimal is just as exact and easier to read.
- **Canonical text of an approximate value = its centre, written exactly.** The centre is a binary number: up to about
  130 digits. The shortest decimal inside the interval is as safe (it is a point of the interval) and about 25 digits.
- **Keeping the ordinary space as a thousands separator.** It conflicts with the expression language and the brief
  requires it to stay refused; the no-break spaces keep the feature.
- **Keeping 1005 characters for the input.** The canonical text of an exact result can be a fraction of up to about
  2470 characters; a shorter input bound would refuse to read back a reused result.
- **Accepting the stored point of an approximate result by a tolerance.** A first version relied on rule 3 for the lines
  built from a remembered approximate value; the review of E06 showed that two points of the result interval can differ
  at the 12th decimal once the result is above about 10^11 (`√2` remembered, then `× 123456789012345`): the store rejected
  its own lines. No tolerance is sound without the width of the interval (a subtraction with cancellation makes it as
  wide as one likes relative to the value), and a loose one would hide corrupted results. Writing the exact
  recalculation keeps rule 1 exact.

## Consequences (Conséquences)

- `1 / 3` reused and multiplied by 3 gives exactly `1`; `1 / 3000000000000` is `3,33333333333… × 10⁻¹³`; `√2` is
  `≈ 1,41421356237…` with `± 2,1 × 10⁻²⁵`; `√(9/4)` is exactly `1,5`.
- Many displayed texts change (« … », « ≈ », scientific notation, larger bounds); every change against the version 1
  golden file is listed and classified in `docs/changements-comportement-E06.md`, and `golden/engine-v2.tsv` is the new
  reference.
- The display text and the reusable text are no longer the same string: the golden test checks both columns.
- E18 refuses results that were accepted before, those whose bound was brought to 0 by the rounding. A notebook keeps such a
  line and shows it in error. An entry of the version 2 history whose recalculation is now refused is set aside and counted at
  the next load, like any entry that cannot be recalculated. The file formats do not change: a notebook keeps its sources and a
  history its bounds. The new text reaches a file only as text the user asked for or already had: the exports, alone since it
  is already the bound, and the comment of a migrated history entry.
- A version 1 file written by E06 cannot carry an interval: a line built from a remembered approximate value comes back
  as the exact result of its canonical operand (a point of the original interval), and a power of such a value above
  about 48 cannot be stored at all (rejected with a backup at the next load). E07 stores the intervals.
- It supersedes decisions 1 and 5 of ADR 0001 (tolerated deviations, bounds) and decisions 4 and 5 of ADR 0002: the
  `rounding-policy` properties are replaced by the exactness, formatting and reuse properties (`docs/TESTS.md`), and every
  canonical text now fits the input bound by construction, so the extra length check of the input is gone.
- The 12-digit rule of the input hints and the README changed; the mode selector, the badge and the display of the
  bound in the window are E08 (the window only shows the Auto text and puts the bound or the fraction in its tooltip).
