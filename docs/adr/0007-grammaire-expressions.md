# 0007 - Expression language: grammar, errors and evaluation

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E09.

## Context (Contexte)

The calculator becomes a calculation notebook (E10 to E12): every line of the notebook is an expression, an assignment
or a comment, and may refer to other lines (`ans`, `$3`) and to variables (`taux`). E09 builds the language as a library
without any interface: lexer, parser, immutable syntax tree, evaluator, canonical printer and a rewriting of the line
references that E10 needs when lines are inserted, moved or deleted.

What already exists and must be reused, not duplicated (ADR 0003, ADR 0004):

- the exact numeric core `calc.number` (`Rational`, `CertifiedValue`): every operation, its domain errors
  (`NumericError`) and its limits (`NumericLimits`);
- the literal grammar of the engine (decimal with a comma or a point, scientific `e` exponent bounded by ±9999, no-break
  spaces U+00A0 and U+202F grouping thousands), and the French messages of the engine for the core's error codes.

A user types a line in a text field, so the language must be predictable for a French user, refuse hostile input
quickly (no stack overflow, no unbounded work), and say *where* the line is wrong.

## Decision (Décision)

### 1. Grammar (EBNF)

The notation is ISO EBNF-like: `|` alternative, `[ ]` optional, `{ }` zero or more, `' '` a literal character.

```
ligne          = vide | commentaire | affectation [ commentaire ] | expression [ commentaire ] ;
vide           = { blanc } ;
affectation    = IDENT '=' expression ;
expression     = additive ;
additive       = multiplicative { ( '+' | '-' | '−' ) multiplicative } ;
multiplicative = unaire { ( '*' | '×' | '/' | '÷' ) unaire } ;
unaire         = ( '+' | '-' | '−' ) unaire | puissance ;
puissance      = primaire [ '^' unaire ] ;          (* right associative: 2^3^2 = 2^(3^2) ; -2^2 = -(2^2) ; 2^-1 valid *)
primaire       = NOMBRE | IDENT | 'ans' | REF | '(' expression ')'
               | ( 'sqrt' | 'racine' ) '(' expression ')' | '√' primaire ;
REF            = '$' ENTIER_POSITIF ;
commentaire    = '#' { tout caractère } ;          (* up to the end of the line *)

NOMBRE         = mantisse [ ( 'e' | 'E' ) [ '+' | '-' ] chiffres ] ;
mantisse       = groupes [ ( ',' | '.' ) { chiffre } ] | ( ',' | '.' ) chiffres ;
groupes        = chiffres { séparateur chiffres } ;  (* séparateur only between groups of three digits *)
séparateur     = U+00A0 | U+202F ;
chiffres       = chiffre { chiffre } ;
chiffre        = '0' | '1' | ... | '9' ;
ENTIER_POSITIF = chiffres ;                          (* value from 1 to 2 147 483 647 *)
IDENT          = ( lettre | '_' ) { lettre | chiffre_unicode | marque_combinante | '_' } ;  (* not a reserved word *)
blanc          = ' ' | tab | U+00A0 | U+202F ;
```

The `( '+' | '-' )` before the exponent belongs to the number: `2e-3` is one number, `2e` followed by a letter or by
nothing is the number `2` followed by the identifier `e` (an error, see the missing operator below).

Decisions written into the grammar (the brief's proposal is kept unchanged, made precise on four points marked *):

- **Precedence**, from the loosest: `+ -`, then `* /` (left associative), then the unary sign, then `^` (right
  associative), then primaries. The exponent of `^` is a `unaire`, so `2^-1` is valid and `-2^2 = -(2^2) = -4` (the
  convention of mathematics; Excel gives 4, see the alternatives).
- **The comma is always a decimal separator.** No function takes several arguments, so a comma can never separate
  arguments and there is no ambiguity: `1,5` is one and a half everywhere. A future function with several arguments
  would need another separator (`;`, as in French spreadsheets).
- **No implicit multiplication.** Two adjacent operands (`2 3`, `2(3)`, `2x`, `(1)(2)`, `ans 2`) are the error
  « Opérateur manquant » on the second one. `2x` is not a number with a unit or a product: refusing it keeps `2e3`
  unambiguous and the rule easy to explain.
- **Identifiers**: a Unicode letter or `_`, then Unicode letters, digits, combining marks or `_`
  (`prix`, `taux_TVA`, `année2`, `_x`).
  *Names are case sensitive (`Prix` and `prix` are two variables), which never merges two names silently; reserved words
  are not: `ans`, `ANS`, `Sqrt` and `RACINE` are reserved.* A reserved word cannot be assigned.
- **Numbers** follow the engine exactly, through the same code (`calc.engine.NumberLiterals`): comma or point, bounded
  `e` exponent, no-break spaces U+00A0 and U+202F between groups of three digits. *A number token is the longest run of
  digits, `,` `.` and separators placed between two digits, followed by a well-formed exponent; it is then read as a
  whole, so `1,5,3` and `1.000.000` are one invalid number, not two numbers.* The ordinary space separates tokens, so
  `1 000` is two numbers: « Opérateur manquant ».
- **Fractions are not literals**: `1/3` is a division, whose exact result is the rational `1/3`.
- *`=` is only allowed right after a leading identifier.* Anything else (`2 = 3`, `x = y = 1`) is an error on the `=`.
- **References**: `ans` is the result of the previous line, `$3` the line 3. *`$?` is the explicit token of a reference to
  a deleted line, written only by the rewriting (section 6): reading it is the error « Référence vers une ligne
  supprimée ».* The meaning of `ans` and `$n` (which line, forward references) belongs to the caller (E10).
- **Comments** start at the first `#` and run to the end of the line; they may follow an expression or an assignment.
- **Not in this version, possible extensions**: the percent sign (`20 %`), constants (`π`, `e`), functions with several
  arguments, implicit multiplication, units. `e` and `pi` are ordinary variable names today; turning them into
  constants later would be a breaking change for a notebook that assigns them, so a new reserved word must come with a
  migration note.

### 2. Positions

Every token and every node carries a `Span`: start index included, end index excluded, counted in UTF-16 characters of
the line (Java `String` indexes), so `line.substring(start, end)` is the source text, and that text read alone gives the
same node. The span of a parenthesised operand includes its parentheses (`(2 - 2)` in `1 / (2 - 2)`), the span of an
operation runs from its first to its last character, and an assignment spans from its name to the end of its value.

### 3. Limits, checked explicitly

| Limit | Default | Checked | Error |
|---|---|---|---|
| line length | 1000 characters | before reading anything | « La ligne est trop longue (1000 caractères maximum). » |
| nesting depth | 100 | at each `(`, sign, `√`/`sqrt`/`racine` and exponent | « Trop d'imbrications (100 niveaux maximum). » |
| nodes of the tree | 500 | at each node created | « Expression trop longue (500 éléments maximum). » |

Each node consumes at least one character, so 1000 characters cannot make more than 1000 nodes; 500 still allows a sum
of 250 numbers. The parser recurses only through the nesting constructs (about five frames per level), so its stack
depth is bounded by the depth limit. A caller may choose other limits with `ExpressionLimits`, but never a depth above
100: measured in interpreted mode, a 256 KB thread stack (a quarter of the default of 64-bit JVMs) overflows near 150
levels, and `ExpressionRobustnessTest` parses at depth 100 on such a stack. The lexer is a loop, and the evaluator and the
printer walk the tree with an explicit stack, so a tree of any depth (100 000 levels built by hand in the tests) cannot
exhaust the stack; the node limit only bounds their work. The tests use a parser without a length limit to show that
10 000 nested parentheses are refused by the depth check itself, and not only because such a line is too long. Numbers
keep the bounds of the core (4096 bits per component, exponent of a power within ±4096), checked by the core.

### 4. Errors

One checked exception, `ExpressionException`, carries a code (`ExpressionError`), the stage of the code (lexical,
syntax, evaluation), a span and a French message; a refusal of the core also carries its `NumericError`. The stages
run in order on the whole line: it is first cut into tokens, so a lexical error is reported before any syntax error, even
one further left (`) 1,5,3` reports the invalid number, not the `)`); within a stage the first error from the left is
reported, and evaluation goes from the left operand to the right one. Positions point at what the user must fix:

| Error | Span | Message |
|---|---|---|
| unexpected character | the character | « Caractère inattendu « @ ». » |
| invalid number | the number | « Nombre invalide « 1,5,3 ». » |
| missing operand | the operator | « Opérande manquant après « × ». » (« avant » for a leading binary operator) |
| missing operator | the first token of the second operand | « Opérateur manquant. » |
| missing closing parenthesis | the unmatched `(` | « Parenthèse fermante manquante. » |
| `)` without `(` | the `)` | « Parenthèse fermante « ) » sans parenthèse ouvrante. » |
| `sqrt` without `(` | the word | « Parenthèse ouvrante attendue après « sqrt ». » |
| deleted reference | `$?` | « Référence vers une ligne supprimée. » |
| unknown name | the name, `ans` or `$n` | « Variable inconnue « prix ». », « La ligne 3 n'existe pas. » ... |
| division by zero | the divisor | « Division par zéro : le diviseur vaut 0. » |
| square root of a negative | the operand | the engine's message |
| exponent not an integer / out of range | the exponent | the engine's message |
| result too large | the operation | the engine's message |

The full list, one test per message with its exact position, is in `ExpressionErrorsTest`.

### 5. Evaluation and resolution

The evaluator walks the tree and calls `CertifiedValue` for every operation (`add`, `subtract`, `multiply`, `divide`,
`pow`, `sqrt`, `negate`): exact values stay exact, a root that is not exact gives a certified interval, and the core's
limits apply. It never computes anything itself. Names and references are resolved by the caller through the interface
`Resolver` (`variable(name)`, `ans()`, `line(n)`), which answers a `Resolution`: a value, or a failure `UNKNOWN`,
`IN_ERROR` or `INVALID` (for instance a forward reference, which E10 forbids). `TableResolver` is a simple immutable
implementation based on maps, for the tests and for simple callers.

The core's error codes are translated with the engine's messages when they do not name an operand field
(`calc.engine.NumericMessages`, extracted from `CalculatorEngine` without changing a character of them); the messages
that name a field of the form (« Le second nombre ... ») are worded for expressions (« le diviseur »).

### 6. Analysis result, printer and rewriting

- `ParsedLine`: kind (empty, comment, expression, assignment with its name), the tree, the comment, the line references
  in source order, the variables used and whether `ans` is used. E10 builds its dependency graph from these.
- `CanonicalPrinter`: one text per tree with the fewest parentheses that parse back to the same tree; ASCII operators
  `+ - * / ^` with spaces around `+ - * /`, `√` before a primary and `√(...)` otherwise, numbers with a comma (plain up
  to 30 characters, otherwise `d,ddde±n`).
- `ReferenceRewriter`: replaces, in the **original text**, every `$n` token outside the comment according to a
  `LineRenumbering` (for instance 3 → 4, 5 → deleted), and leaves every other character untouched (spaces, number
  notation, comment). A reference to a deleted line becomes `$?`. It works on lines that do not parse, since a notebook
  must keep renumbering a line while the user is still fixing it. Renumbering may lengthen a line (`$9` becomes
  `$10`) beyond the length limit; the line is then refused as too long, like any other.
- The canonical text can be longer than its source (plain digits up to 30 characters, spaces around operators), so the
  text of a line close to 1000 characters may exceed the length limit: the round trip is guaranteed for a parser whose
  length limit admits the printed text (the tests use one).

## Alternatives considered (Alternatives considérées)

- **A parser generator (ANTLR, JavaCC).** A build plugin and a runtime dependency for twelve rules; its error messages
  and recovery would still have to be rewritten in French with positions, and its recursion depth is not bounded.
- **The shunting-yard algorithm.** No recursion at all, but right associativity of `^`, the unary minus below `^` and
  precise « opérande manquant » / « opérateur manquant » messages need as many special cases as the descent itself;
  recursive descent mirrors the EBNF one rule per method, and the explicit depth counter removes the stack risk.
- **Precedence of the unary minus above `^`** (`-2^2 = 4`, as Excel computes it). Rejected: mathematics, Python, and
  most calculators give -4, which is what the brief asks for.
- **Implicit multiplication** (`2x`, `2(3)`). Ambiguous with the `e` notation (`2e3`) and with future units; easy to
  add later as an extension, impossible to remove once notebooks rely on it.
- **A comma as an argument separator.** No function needs two arguments; French users write decimals with a comma.
- **Case-insensitive variable names.** Friendlier, but `Prix` and `prix` would silently be the same cell; case
  insensitivity is kept for the reserved words only, so that `SQRT(2)` still works.
- **One exception class per stage.** Callers (E10) need one catch and one code; the stage is a property of the code.
- **A recursive evaluator and printer.** Simpler to read, but a tree built by hand (or a future caller with other
  limits) could exhaust the stack: measured, fewer than 300 levels fit a 256 KB stack before the JIT compiles the
  method. An explicit stack costs a few lines and removes the question.
- **Rewriting through the token list.** It would make the rewriting impossible on a line with a lexical error; the
  rewriting uses the lexer's rule for references (`$` followed by digits, outside the comment) on the raw text instead.

## Consequences (Conséquences)

- E10 evaluates each line with `ExpressionParser` and `Evaluator`, implements `Resolver` with its semantics (previous
  line for `ans`, backward references only, variable shadowing) and calls `ReferenceRewriter` on insertions, moves and
  deletions.
- The engine shares its literal reading (`NumberLiterals`) and its field-independent messages (`NumericMessages`) with
  the language; the golden test of the engine's outputs shows that nothing changed.
- Interval arithmetic still widens when a value appears twice (`√2 - √2` is a small interval around 0, not 0): see
  ADR 0003.
- Adding the percent sign or constants later is a grammar change: it needs a new ADR, and the reserved words must be
  checked against existing notebooks.
- Positions are UTF-16 indexes: a character outside the Basic Multilingual Plane (an emoji in a comment, a rare letter in
  a name) counts for two. A UI that shows a caret must use the same unit (Swing does).
