# 0008 - Notebook semantics: lines, references, variables, recalculation and undo

## Status (Statut)

Accepted. Date: 2026-10-27. Epic E10.

## Context (Contexte)

The calculator becomes a calculation notebook: an ordered document of editable lines, each one empty, a comment, an
expression or an assignment in the language of ADR 0007. E09 left to the notebook what a line *means* in its document:
which line `ans` and `$n` designate, which definition of a variable applies, what happens to the lines below when a line
changes, and what happens to a reference when lines are inserted, deleted or moved. E10 builds that model as a library
(`calc.notebook`), without any interface (E12) or persistence (E11), so that both can be plugged onto it.

Forces:

- A user edits lines in any order, inserts and deletes lines, and expects every result to be up to date at once.
- A reference must never start to designate another line without the user knowing: a wrong total that looks right is the
  worst outcome of a calculation tool.
- E11 will persist the notebook; what is persisted must be enough to rebuild exactly the same notebook.
- The language already gives a parser, an evaluator that asks a `Resolver` for names and references, and a
  `ReferenceRewriter` that renumbers the `$n` tokens of the original text (`$?` for a deleted line).

## Decision (Décision)

### 1. Lines and numbers

A notebook is an ordered sequence of lines `{identifier, source text}`. Lines are numbered from 1 in the order shown;
`$n` and every message use these numbers. Each line also has a **stable identifier** (`LineId`), internal and never shown,
which survives every edit and every move of its line; a new line gets a new identifier, and an identifier is never
reused within a notebook, not even after an undo (a restored notebook keeps counting from the latest identifier).
The source text is the only data to persist: results are always recalculated. A line text
holds no line break (`\n`, `\r`): a caller splits a pasted block into lines.

### 2. References go backwards only

A line may only use what is **above** it: `ans` and `$n` designate a line above, and a variable is visible below its
definition only. The dependency graph is therefore acyclic by construction, a cycle cannot be written, and the
recalculation is a plain top-down evaluation (section 7).

- **`ans`** is the value of the closest line above that **produced a value** (an expression or an assignment that was
  evaluated without error). Empty lines, comments and lines in error are skipped, like the `Ans` key of a calculator,
  which keeps the last successful result. Without such a line: « Aucun résultat précédent : « ans » n'a pas de valeur. »
- **`$n`** is the value of line `n`, with `1 ≤ n <` the number of the current line. Otherwise (`n` equal to or above the
  current number, including a number past the last line): « Référence vers une ligne qui n'est pas au-dessus (ligne n). »
  Line `n` empty: « La ligne n est vide : elle n'a pas de valeur. » Line `n` a comment: « La ligne n est un commentaire :
  elle n'a pas de valeur. » Line `n` in error: the propagation message below. `$0` and `$?` keep the messages of the
  language (« Référence vers une ligne supprimée. » for `$?`).
- **Variables**: a name is resolved by the closest assignment **above** the line. Redefining a name is allowed and masks
  the previous definition from that line down (`x = x + 1` reads the `x` above it). A name with no assignment above:
  « Variable inconnue « x ». » Assigning a reserved word (`ans = 3`) is the language's error « « ans » est un mot
  réservé ... ». An assignment **in error still defines its name**: the lines below that use the name are in error
  (propagation), they never fall back silently on an older definition. A line in error counts as an assignment when it
  starts with `name =` where `name` alone reads as a variable (`x = 2 +`, `x = $?`).

### 3. Propagation of errors

A line that needs the value of a line in error is in error, with the message « Dépend de la ligne n, qui est en erreur. »,
where `n` is the number of that line: through `$n`, or through a variable whose closest definition above is in error.
`ans` never propagates, since it skips the lines in error by definition (section 2). The error keeps the position of the
reference or of the name in the line, as every evaluation error of the language.

### 4. Stability of references

Each reference `$n` is bound to the **identifier** of line `n` at the moment the line is typed or edited. The binding is
kept **in the text** itself: after every insertion, deletion or move, the text of every line is rewritten with the
language's `ReferenceRewriter` so that each reference designates the **same line** under its new number. The invariant
that holds after every command is: *the number written in a reference is the current number of the line it was bound to*.
Consequences:

- Inserting a line above line 3 turns `$3` into `$4` and the value is unchanged.
- A reference to a deleted line becomes `$?`, which is an explicit error (« Référence vers une ligne supprimée. »). A
  reference is **never redirected silently** to another line.
- If a move places the designated line below (or on) the line that refers to it, the reference keeps its target and gets
  its new number: it is then in error (« Référence vers une ligne qui n'est pas au-dessus »); it is not turned into `$?`
  and it is valid again if the line is moved back above.
- A reference to a line that did not exist when it was typed (`$9` in a notebook of 5 lines) is bound to no line. It stays
  **past the last line**: an insertion adds one to it, a deletion removes one, a move leaves it, so that it can never come
  to designate a line by accident. It is an error (not above) until the user edits it.
- `$0` and numbers beyond `int` are kept as written (lexical errors of the language).
- A `$n` written in a comment (after `#`) is text, not a reference: it is neither bound nor rewritten, as in the
  language's `ReferenceRewriter`. Only the references that are evaluated follow their line.
- Since the binding lives in the text, persisting the text is enough (E11): reading the text back binds every reference
  to the line of that number, which is the line it was bound to (checked after every step of the stateful test).

Variables and `ans` are not bound to lines: they are resolved by position at each evaluation (closest definition above,
closest value above), which is what a user reads on the screen.

### 5. Limits

| Limit | Value | Behaviour |
|---|---|---|
| lines | 5000 | an insertion beyond it is refused: « Le carnet ne peut pas dépasser 5000 lignes. » |
| line length | 1000 characters (language, ADR 0007) | the line is kept and is in error « La ligne est trop longue ... » |
| undo depth | 200 commands | the oldest command is forgotten |

A renumbered line may exceed the length limit (`$9` becomes `$10`), and then is in error like any other long line.

### 6. Commands, undo and redo

The commands are: edit the text of a line; insert a line before or after a line, or at the end; delete a line; move a
line to an index; clear everything. Every command produces a **new immutable notebook**: undo restores the previous
notebook, redo the next one, and both are exact by construction.

- Granularity: one validated command. A command that changes nothing (same text, move to the same place, clearing an
  empty notebook) is not recorded and does not clear the redo stack.
- The undo stack is bounded (200); a new command empties the redo stack.
- Undo and redo restore the document (identifiers included) and recalculate its results.

### 7. Recalculation

Evaluation is sequential, from top to bottom, with a `Resolver` built from the results already computed above. The
result of a line depends only on the lines above it and on itself (references past it are errors that name no line
count), so after a command the results are kept up to the first line whose identifier or text changed and recomputed
from there. No dependency graph is kept: it would only pay if the measured budget were not met, and it is (1000 lines
recomputed from the first one well under 200 ms, see `docs/TESTS.md`).

### 8. Bridge from the old history

An entry of the old history (an operation and its operands) becomes the line `a + b`, `a - b`, `a * b`, `a / b`, `a ^ b`
or `√(a)`, where each operand is its canonical text, between parentheses when it is negative or a fraction
(`2 ^ (-3)`, `(1/3) * 3`). The line evaluates to **exactly** the value of the entry, or to the same interval for a root.
When that is impossible the bridge gives no line rather than a line of another value: an operand that is an approximate
value (a remembered root) has no literal in the grammar, and an operand longer than a line (up to 4100 characters)
cannot fit in 1000 characters.

## Alternatives considered (Alternatives considérées)

- **Forward references and a dependency graph with cycle detection** (spreadsheet model). More expressive, but cycles,
  their messages and an evaluation order to maintain; a notebook is read from top to bottom, and the backward rule makes
  cycles impossible instead of detected.
- **A binding table stored beside the text** (reference occurrence -> identifier). Two sources of truth that can drift;
  the persisted text alone would not rebuild it. Rewriting the text keeps a single truth and makes persistence trivial.
- **Leaving a reference past the last line unchanged.** `$9` would silently start to designate whatever line 9 becomes
  after insertions, a line the user never designated: rejected by the rule « never redirect silently ».
- **`ans` = the line just above, even if it is in error** (and propagate its error). Rejected: the brief defines `ans` as
  the closest line that produced a value, which also matches the calculator key; a line in error does not erase `ans`.
- **An assignment in error defines nothing.** Then `x` below would silently take an older definition while the user is
  fixing the line: rejected, the same rule as above.
- **A mutable document with inverse commands for undo.** Each command needs a correct inverse (a delete must restore the
  rewritten texts of every other line); immutable snapshots make undo exact by construction, and the unchanged
  `Line` objects are shared between snapshots. Each snapshot still keeps its own list and index of lines (a few
  hundred kilobytes for 5000 lines), which bounds 200 snapshots to tens of megabytes; not measured further.
- **Incremental recalculation along the dependency graph.** Not needed: the measure meets the budget with a plain
  sequential pass from the first changed line.

## Consequences (Conséquences)

- E12 plugs a presenter on `NotebookEditor` (apply a command, read the evaluated lines, undo, redo); E11 persists the
  texts of the lines in order and rebuilds a `Notebook` from them.
- A move can turn a valid reference into an error (target now below); the user sees it at once and can move back or undo.
- A reference typed past the last line follows the size of the notebook (`$9` becomes `$10` after an insertion) until it
  is edited: surprising, but never wrong.
- History entries computed from a remembered root, or with an operand longer than a line, cannot become lines of the same
  value; E11 must decide what to do with them (for instance keep them as comments).
- Recalculating from the first changed line costs O(lines below) for each command; the dependency graph remains a
  possible optimisation if notebooks grow beyond the measured budget.
