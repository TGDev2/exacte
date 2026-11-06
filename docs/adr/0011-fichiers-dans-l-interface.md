# 0011 - Files in the window: the File menu, confirmations, and the removal of the two-field form

## Status (Statut)

Accepted. Date: 2026-10-27. Epic E13.

## Context (Contexte)

E11 gave the notebook documents and exports as services (ADR 0009): no dialog, the path is a parameter. E12 made the notebook the
main window (ADR 0010) and left the two-field form in the code, no longer launched. E13 plugs the services into the window and
removes the form once everything it did is proven in the notebook.

## Decision (Décision)

### 1. Dialogs are an injected interface

`NotebookDialogs` (package `calc.ui.notebook`, no Swing import) lists every question the window can ask: the file to open, the file
to save, the file to export (Markdown or CSV), « Enregistrer / Ne pas enregistrer / Annuler », the replacement of the autosaved
notebook that an opened file left off screen, the replacement of an existing file. `SwingNotebookDialogs` is the real one
(`JFileChooser` filtered on `.calc.json`, `.md` or `.csv`; `JOptionPane`; closing the question with Escape is « Annuler »). The tests
use `FakeDialogs`, a script of answers that **fails the test on a question nobody queued**: no test opens a dialog, and a question the
presenter was not meant to ask is a failure. The presenter decides when to ask and what to do; the window only forwards the intention and
shows the `NotebookViewModel` (new fields: `title`, `canSave`, `closeWindow`).

### 2. The File menu and its keys

Nouveau `Ctrl/Cmd+N`, Ouvrir… `+O`, Enregistrer `+S`, Enregistrer sous… `+Maj+S`, Exporter en Markdown…, Exporter en CSV…, Quitter. The
modifier is `Toolkit.getMenuShortcutKeyMaskEx()`, as for undo. The four keys are `NotebookShortcuts.Command` entries, so they are bound
in the field of each line like every other key (they win over the look and feel), listed in the Help menu, and covered by the same
conflict tests; none clashes with a key of the notebook, and none is bound by a text field of the installed look and feels
(`NotebookShortcutConflictTest`). The two exports and Quitter have no key (Quitter: Cmd+Q belongs to the system on macOS, Alt+F4 on Windows).

### 3. Title and modified marker

`Carnet sans titre — Calc`, or `<fichier> — Calc` for a document with a file, with `• ` in front when it is modified and
` (lecture seule)` after the name for a file from a newer version. « Modified » is the definition of ADR 0009 (the notebook differs from
the file; undoing back to the saved state clears it). The untitled notebook is autosaved and never marked.

### 4. Confirmations

Nouveau, Ouvrir and Quitter (the close button, Cmd+Q and the Quitter item take the same path: `DO_NOTHING_ON_CLOSE`, the presenter says
whether the window closes) ask « Enregistrer / Ne pas enregistrer / Annuler » when a document with a file is modified. « Enregistrer »
that fails or is cancelled (no name given) cancels the action. The line being typed is validated first, so it counts as a change.

« Autosaved » is a fact, not a name: an untitled notebook that has lines **and is not actually being saved** (another instance holds
the data directory, the file is from a newer version, or the last automatic save failed) is unsaved work for Open and Quit too, and
the same three choices are asked; « Enregistrer » goes to « Enregistrer sous » (found by the independent review of E13).

Two points where the brief and ADR 0009 pull apart, and the careful reading was kept:

- **Nouveau on an untitled notebook that has lines asks too.** The untitled notebook is autosaved, but a new document **replaces** the
  autosaved file with an empty one (ADR 0009, section 4: « an untitled notebook that is not empty » is work that would be discarded).
  Opening a file leaves the autosaved draft alone, and quitting keeps it for the next start, so neither asks.
- **Nouveau after an opened file asks before replacing the autosaved draft the file took off the screen** (`confirmReplaceDraft`;
  `DocumentService.isDraftOffScreen()`, added). Saving it is not offered: it is not on screen.

The file is chosen **before** the question about unsaved changes when opening, so cancelling the chooser costs nothing; the question is
asked before the file is read. If the file then cannot be opened, the document is still there with its changes (« Ne pas enregistrer »
only means the user accepts losing them **if the opening succeeds**).

### 5. Opening: messages

In the status bar, in words: `Impossible d'ouvrir « x » : <raison>` for a file that is not a notebook, is damaged, is over a limit
(« Ce fichier dépasse la taille maximale d'un carnet (16 Mio). ») or is a file Calc keeps for itself; the system message of a file that
cannot be read; `« x » ouvert (n lignes). 2 lignes contiennent une erreur.` The lines in error are kept (ADR 0009, section 2). A file
from a newer version opens read only: a permanent warning, `Enregistrer` disabled (the key says why), `Enregistrer sous` allowed (the
copy is an ordinary version 3 document). A file from a newer version whose lines cannot be read is refused. The display mode saved in
the file becomes the mode of the window (the remembered preference is for the untitled notebook only).

### 6. Saving and exporting

`Enregistrer sous` adds `.calc.json` (case-insensitively), refuses the files Calc keeps for itself, and asks before replacing an
existing file unless it is the document's own file.
A target that is a file of a newer version than the one Calc writes is refused by `Enregistrer sous`, whether or not it is the open document: same message as for the open file, checked before the question and before any write (defect D5 of the acceptance report, E19). A target that cannot be read or is not a notebook keeps the question.
The exports use the **display mode in force**, name the table after the document
(`Carnet` for the untitled one), add `.md` or `.csv`, ask before replacing a file, write atomically and say `Exporté vers <chemin>`.
The extension is forced, so an export can never hit a file Calc keeps (none ends in `.md` or `.csv`), and never the document's file.

### 7. Parity table and removal

Every function of the old form has its equivalent in the notebook and a green test there (the removal came after, in its own commit):

| # | Old function | In the notebook | Test |
|---|---|---|---|
| 1 | binary calculation | a line `a op b` | `NotebookParityTest#p1BinaryCalculation` |
| 2 | unary calculation (root) | `√x`, `sqrt`, `racine` | `#p2UnaryCalculation` |
| 3 | comma or point | same | `#p3CommaOrPoint` |
| 4 | chained decimals, no drift | `ans + b` | `#p4ChainedDecimalsDoNotDrift` |
| 5 | reuse the last result (Ctrl+R) | `ans` | `#p5ReuseOfTheLastResultIsAns`, `NotebookSemanticsTest#ansChainsThroughTheLinesThatHaveAValue` |
| 6 | reuse an entry as 1st / 2nd number | `$n` | `#p6ReuseOfAnEntryAsFirstOrSecondNumber` |
| 7 | 1/3 reused × 3 = 1; lossless chain | `ans * 3` | `#p7OneThirdReusedTimesThreeIsExactlyOne`, `NotebookLosslessChainProperties` |
| 8 | approximate value stays an interval | `ans * ans` | `#p8AnApproximateValueStaysAnInterval` |
| 9 | clear the fields | Escape | `#p9ClearFieldsIsEscape` |
| 10 | clear the history | Tout effacer, undoable | `#p10ClearHistoryIsClearAndUndoRestoresIt` |
| 11 | persistence | autosave | `#p11PersistenceAcrossSessions` |
| 12 | restore messages | migration, read-only, newer version, damaged file | `NotebookPresenterTest`, `NotebookMigrationTest#aHistoryFromANewerVersionIsNotConvertedAndNeverRewritten` |
| 13 | targeted errors | message under the line, faulty part underlined | `#p12TargetedErrors` |
| 14 | a failed calculation is not recorded | the line is kept in error, the others compute | `#p13AnErrorBlocksNothing` |
| 15 | size limits | engine and language limits | `#p14HugePowersAreRefusedWithoutFreezing`, `SizeLimitsTest` |
| 16 | display modes, Ctrl+M cycle | menu, selector, Ctrl+M | `#p15DisplayModes`, `NotebookFrameTest` |
| 17 | badge, detail, long result | badge per row | `#p16TheExactnessDetail`, `NotebookFrameTest` |
| 18 | Enter / Esc / Ctrl+R / Ctrl+M | Enter / Esc / `ans` / Ctrl+M | `NotebookFrameTest#theWholeKeyboardPath` |
| 19 | mnemonics, accessible names, tab order | same | `NotebookFrameTest` |
| 20 | stateful property of the presenter | stateful property of the notebook presenter | `NotebookPresenterStatefulProperties` |

Differences by design: no history list (the notebook is the history); no operation selector; no hint under the power field (the range
is given by the error message); a very long result keeps the window width but its tooltip no longer holds the complete value (the old
form also put that value in the next field, which the notebook has no use for: `ans` carries it).

Removed (one commit): `CalculatorFrame`, `CalculatorPresenter`, `CalculatorViewModel`, `FocusTarget`, `ReuseTarget`, `OperationText`,
`HistoryLine`, `HistoryLineRenderer`, `ResultDescription.secondaryFraction` (dead after the removal). Kept: the engine
(`CalculatorEngine`, the notebook depends on its parser and messages), the history store (the migration reads it), `StatusTone`,
`DisplayModeStore`, `ResultDescription.describe` and the widgets of E08 used by the notebook.

Tests removed, and what replaces them:

| Removed | Replaced by |
|---|---|
| `CalculatorPresenterTest` (15) | `CalculatorEngineTest` and `ErrorProperties` (engine messages); `NotebookParityTest` P1-P8 |
| `CalculatorDisplayModeTest` (9) | `NotebookPresenterTest`, `NotebookParityTest#p15`, `#p16`, `ValueFormatterTest` |
| `CalculatorErrorFocusTest` (5) | `CalculatorEngineTest#testErrorsCarryTheFaultyField`, `ErrorProperties`; focus on a field is obsolete (the line is marked instead: `#p12`) |
| `CalculatorFrameTest` (13, `ui`) | `NotebookFrameTest`, `NotebookFrameFilesTest` |
| `CalculatorHistoryStatusTest` (15) | `NotebookStoreTest`, `NotebookMigrationTest`, `NotebookPresenterTest` (the old texts about « entrées illisibles » no longer exist) |
| `CalculatorHistoryLegacyFilesTest` (2) | `NotebookMigrationTest#everyReferenceFileGoesThroughTheWholeChainWithTheSameValues`, `LegacyMigrationTest` |
| `CalculatorHistoryPersistenceTest` (5) | `NotebookParityTest#p11`, `HistoryRoundTripProperties`, `NotebookMigrationTest` |
| `LosslessReuseProperties` | `NotebookLosslessChainProperties` |
| `PresenterStatefulProperties` | `NotebookPresenterStatefulProperties` |
| `HistoryLineTest`, `WidgetsTest#theRendererDraws...` | none (the class is gone) |
| `PresenterActions`, `PresenterSession`, `MemoryHistoryStore` (helpers) | none (unused) |

Moved and trimmed (no assertion of the engine or of the store lost): `CalculatorDisplayRulesTest` → `calc.engine.DisplayRulesTest`
(minus the presenter case), `CalculatorSizeLimitsTest` → `calc.engine.SizeLimitsTest` (minus three presenter cases; the oversized
history line is checked on the store), `GoldenOutputsTest` → `calc.engine.GoldenOutputsTest` (reads the reusable text from the
record), `CalculatorHistoryBackupTest` → `calc.persistence.JsonHistoryBackupTest` and `CalculatorHistoryLoggingTest` →
`calc.persistence.PersistenceLoggingTest` (the log of a failed automatic save is now asserted on `NotebookStore`),
`JsonHistoryStoreTest#aFileFromANewerVersion...` (on the store, not the presenter).

## Alternatives considered (Alternatives considérées)

- **A subclass of the window overridden in tests** instead of an interface: the presenter would not know when to ask, and the order of
  questions (chooser, then confirmation, then reading) would live in Swing code, untestable without a display.
- **`JOptionPane` called from the presenter**: no test could avoid a real dialog.
- **Confirming only documents with a file** (the brief taken literally): New would silently erase the autosaved notebook.
- **Keeping the form behind a flag**: dead code that tests must still carry.
- **Autosaving documents with a file**: rejected by ADR 0009, section 4.

## Consequences (Conséquences)

- Closing, Nouveau and Ouvrir can ask a question; every answer is a test.
- `DocumentService` gained two read-only accessors (`isReservedPath`, `isDraftOffScreen`); its behaviour is unchanged.
- A line being typed that cannot be validated (more than 16 384 characters, or a notebook already at 5000 lines) keeps the window open
  with the reason, like New and Open: Escape drops it, then the window closes.
- A change of display mode marks a document with a file as modified at once (ADR 0009), so closing offers to save it. For the untitled
  notebook the mode still follows at the next command, so that a mode change alone never writes.
- A confirmation of a file operation (saved, exported) is shown even over the permanent read-only warnings; the warning comes back
  with the next command.
- A new notebook after an opened file uses the remembered preference, not the mode of the file that was open.
- `history.json` and its copy stay on disk; the old history code remains only as the source of the migration. Removing them is a
  decision for a later Epic.
- `tools/baseline/Baseline.java` (unbuildable since E07, it also called the removed presenter) was deleted and its README kept as an archive; `tools/golden-v2/GoldenV2.java` no longer needs the presenter and regenerates `engine-v2.tsv` byte for byte.
- Not checked here: a real macOS keyboard (Cmd) and the native file chooser of macOS and Linux.
