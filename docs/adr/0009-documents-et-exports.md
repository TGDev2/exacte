# 0009 - Notebook persistence (format v3), documents and exports

## Status (Statut)

Accepted. Date: 2026-10-27.

## Context (Contexte)

E10 gave Calc a notebook model (ordered lines `{id, text}`, results always recalculated, undo and redo, a bridge from the
old history). E07 gave it a versioned JSON history (version 2) with atomic writes, an instance lock, read limits and one data
directory per operating system. Nothing yet keeps a notebook between two runs, lets the user keep several of them, receive
one from someone else, or share one as a table. The interface (E12, E13) will plug onto services; this ADR decides what the
services do. Several decisions are hard to undo once files exist in the wild: the file format, the name of the extension, the
shape of the exports.

## Decision (Décision)

### 1. Format version 3: sources only

`{ "format": "calc-notebook", "version": 3, "displayMode": "auto", "lines": [ { "id": "...", "source": "..." } ] }`.
Only the **source text** of each line is persisted, never a result, a value, an error or a line number. A result is a function
of the texts (ADR 0008, section 7), so a stored result could only be redundant or, worse, stale: the file is the truth
and the notebook is recalculated in full when it is loaded. This is the policy « the source is the truth ». The same
property makes the file diffable and hand-editable, and lets a later version of the evaluator improve a result without a
migration.

- `format` is a different value from the history (`calc-history`): a v2 history is refused as a notebook (« ce fichier n'est
  pas un carnet Calc ») and a notebook is refused as a history. The version counter is shared (3 is the next after 2) so that a
  file never needs both fields to be read.
- `displayMode` is optional (`auto`, `decimal`, `fraction`, `scientific`); an absent or unknown value means `auto`.
- Unknown fields are ignored at every level and are not kept when the file is written again, as in version 2.
- A line `id` is a text of at most 18 digits (the stable identifier of E10). An absent, malformed or duplicated identifier is
  **regenerated deterministically**: the first holder of an identifier keeps it; every other line, in file order, gets the
  next number after the largest valid identifier. The count is logged.
- A source that holds a line break (impossible in the model, possible in a foreign file) gets each break replaced with one
  space and is logged: the line is kept, never split, so the line numbers (`$n`) of the file keep their meaning.

### 2. Bad lines are kept

Whatever its text, a line of a readable file is **kept as it is** and shows as an error in the notebook after the recalculation.
Deleting a line the user wrote because Calc cannot read it would be silent data loss. Only a file whose JSON structure is wrong
(not JSON, not UTF-8, no `lines` array, a bound exceeded) is refused, with a clear message.

### 3. The current notebook is untitled and autosaved

The notebook the user types in without having chosen a file is the **current notebook**: `carnet-courant.calc.json` in the data
directory of E07. It is written atomically (the E07 writer) after every validated command (a command that changes nothing is
not a command), under the instance lock of E07: `NotebookStore` and `JsonHistoryStore` **share** the one `history.lock`, so a
second instance, whichever format it uses, loads but never writes. A failed write is reported, never thrown at the user in the
middle of an edit, and the next successful write repairs it.

A notebook written by a newer version of the format is loaded when its lines can be read and the store becomes read only for the
rest of the session: it is never written again.

### 4. A document associated with a file is saved explicitly

A document is either **untitled** (the current notebook, autosaved) or **associated with a path** (opened or saved as). The
second kind is **never autosaved**: the user chose a file, so the file only changes when the user says « Enregistrer ». The
service exposes a **modified** indicator: the document differs from the notebook last read from or written to the file (so
undoing back to the saved state clears it). The untitled document is never « modified » in this sense: it is always saved.

The path is a **parameter** (no dialog in this Epic); the extension is **`.calc.json`**, added when the name does not already
end with it (case-insensitively). Everything else is accepted when opening: the extension is a convention, not a guard.

- **Open** leaves the autosaved draft untouched: nothing the user typed is lost by opening a file. The draft is then off the
  screen, and a later **New** would replace it, so `wouldLoseWork()` stays true until it has been replaced or saved.
- **Save as** from the untitled document **promotes** the draft: once the file is written, the draft file is reset to an empty
  notebook, so the next run does not resurrect a second copy of what the user has just saved.
- **New** replaces the current document with an empty untitled one and writes it to the draft file. The service reports whether
  work would be discarded (`isModified`, or an untitled notebook that is not empty) so that the presenter can ask first.
- A file written by a newer version is opened **read only** and **never rewritten**, not even by Save to the same path.

### 5. Limits (read and written)

| Limit | Value | Why |
|---|---|---|
| file size | 16 MiB, checked before the file is read | 5000 lines of 1000 characters of 3 bytes each is 15 MB |
| lines | 5000 (`Notebook.MAX_LINES`) | the limit of the model; checked on the array before any line is read |
| source length | 16 384 characters (`StorageLimits.MAX_STRING_LENGTH`) | the language keeps 1000 characters (ADR 0007) and reports a longer line as an error; the file accepts more so that such a line can be kept |
| depth, string, number | those of E07 (32, 16 384, 32) | unchanged |
| JSON values | 100 000 | new: bounds the memory of a hostile file made of millions of tiny values |

The application never writes a file it would refuse to read: a notebook over one of these limits is **not written** and the
caller is told why (nothing is dropped, unlike the history, whose oldest entries are expendable and a notebook's are not).

### 6. Opening a file from someone else

The same limits, the same strict reader, and nothing in the file is a path, a command, a macro or a link: the only things read
are the texts of the lines, which the language evaluates under its own bounds (1000 characters, 100 levels, 500 nodes, 4096-bit
numbers). The file is read through a bounded stream (never more than the limit plus one byte, whatever the size on disk or a
file that grows), and only a regular file is read. A hostile file is refused in milliseconds or loaded as plain text whose
recalculation is bounded; the measures are in `docs/TESTS.md`.

### 7. Migration from the history

At start, in the data directory, under the lock:

1. If `carnet-courant.calc.json` exists, it is loaded and nothing else is touched (this is what makes the migration idempotent).
2. Otherwise the history is loaded by `JsonHistoryStore`, which already converts a version 1 `history.tsv` to `history.json`
   (E07). If it holds at least one entry or if `history.json` exists, each entry becomes a line through the bridge of E10, in order.
   An entry that no line can express (an approximate operand, an operand longer than a line) becomes a **comment line** that names the operation and
   the values (`# Entrée de l'historique non convertie : ...`), so that nothing disappears silently; the count is reported.
3. A copy `history.json.v2.bak` (`.v2.bak.1`... when the name is taken, never overwritten) is made **first**; if it fails the
   migration stops and every source stays as it is. Then the notebook is written atomically.
4. The status is « Votre historique a été converti en carnet (n lignes). ».

`history.json` is not renamed or deleted: the current interface (until E13) still reads it, and the copy is the safety net.
A migration that fails at any step leaves every source untouched and is tried again at the next start, as long as no current
notebook has been written in between (the first autosave of a notebook typed after the failure ends the migration for good;
`history.json` and its copy stay). A history of more than 5000 entries (a notebook holds 5000 lines, the history up to 10 000)
converts its newest 5000 and says where the others are (the copy). A second instance (read only) converts in memory and writes nothing.

### 8. Exports

The result of each line is formatted with the display mode **passed as a parameter** (`ValueFormatter`, E06). Empty lines are
left out; the numbers of the other lines are those of the notebook, so `$n` references stay readable.

- **Markdown**: a title, then a table `| n° | Expression | Résultat |`. Every cell is escaped: the characters that Markdown
  or a renderer could read (`\ ` * _ [ ] < > | ~ & $`, the last one because lines contain `$n` and some renderers read `$...$` as
  maths) get a backslash, a line break becomes `<br>`. An approximate value shows « ≈ » and its error bound
  (`≈ 1,41421356237… (± 3,4 × 10⁻¹³)`), a line in error shows `Erreur : message`, a comment shows its text without the `#`.
- **CSV**: separator `;`, UTF-8 **with a byte order mark** and **CRLF** line ends (what French Excel opens correctly), fields
  quoted when they hold `;`, `"`, CR or LF and quotes doubled (RFC 4180). Columns: `n°`, `Expression`, `Résultat affiché`,
  `Valeur exacte`, `Statut` (`exact`, `approché`, `erreur`, empty for a comment). The exact value is the canonical text of an
  exact result and empty for the others.
- **JSON** is the native v3 file: there is no other JSON export.

### 9. Neutralising formula injection in CSV

A spreadsheet runs a cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return as a formula, and a notebook shared by a third
party is exactly where such a cell could come from (a comment `# =HYPERLINK(...)` is a CSV cell). Every cell that starts with one of these
characters is **prefixed with an apostrophe** before quoting. The visible effect is stated, not hidden: the apostrophe is part of the cell
once the file is read as text, and a negative number (`-5`) is also prefixed and so becomes text in a spreadsheet.
This is the cost of a rule with no exception; the exact value column is meant to be read by a program, not summed in Excel, and
the displayed result is the one for people.

## Alternatives considered (Alternatives considérées)

- **Persist the results too**: faster to open, but stale after any change of the evaluator, a second source of truth, and a
  hostile file could contain a result that disagrees with its text. The recalculation of 1000 lines takes about 10 ms.
- **Reuse `calc-history` with a bigger `entries`**: the old readers would load a notebook as a history and drop lines.
  A new `format` makes them refuse it.
- **Drop the lines that do not parse**: simpler loader, but it destroys user data on a typo or a newer syntax.
- **Autosave every document**: the user would lose control of a file they chose (a shared one, for instance).
- **Rename `history.json` after the migration**: the current interface would see an empty history until E13.
- **Escape only `|` in Markdown**: a foreign comment such as `<script>` or `[x](javascript:...)` would reach a renderer.
- **Quote the CSV injection with a tab or a space instead of an apostrophe**: both are invisible and less widely recommended.
- **Write a separate lock for the notebook**: two locks on two files in one process make a second store of the same directory
  « busy » (an overlapping lock) and let two formats be written by two instances at once.

## Consequences (Conséquences)

- A notebook file is small, readable and diffable; a lost or damaged line is the user's, not ours.
- The model can hold a line longer than the file accepts (16 384 characters) or a notebook larger than 16 MiB; the write
  is then refused and reported. The interface (E12) must cap the length of a line it accepts.
- Evaluating a hostile but within-limits file is bounded per line, not per file; the measures are in `docs/TESTS.md`.
- `history.json` stays after the migration. Removing the history is a decision for E13.
- The exports are text only (no PDF), and the neutralisation changes what a spreadsheet shows for some cells; both are documented.
