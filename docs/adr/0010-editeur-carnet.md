# 0010 - The notebook editor: a stack of rows, one validated command at a time

## Status (Statut)

Accepted. Date: 2026-10-27. Epic E12.

## Context (Contexte)

E10 gave Calc a notebook model (ADR 0008) and E11 a way to keep it (ADR 0009: current notebook autosaved, migration of the
old history, documents as services). The window the user sees was still the two-field form of version 1. E12 makes the
notebook the main window: one expression per line, its formatted result with the exactness badge of E08, keyboard first,
the lines below recalculated visibly when a line changes, undo and redo, insert, delete and move, the display mode, and
the autosave and the migration at start. Opening, saving and exporting files from the window, and removing the form, are E13.

## Decision (Décision)

### 1. The component of the list: a stack of composed rows, not a table

Each line is a **row panel** (`NotebookRowView`): number, a real `JTextField`, the result on the right with the badge of E08,
and under the field the message of an error. The rows are stacked in a `JScrollPane` (`BoxLayout`, width tracks the
viewport). Criteria, in order:

1. **Keyboard and focus.** A row owns its text field, so the caret, the selection, the clipboard and the input methods are
   those of a text field, and the keys of the notebook are bound in the input map of that field (which wins over the look
   and feel's own bindings, section 4). In a `JTable`, a cell editor is started and stopped by the table (a first key
   either starts the editor or is lost, Enter and the arrows are the table's own, Escape cancels the editor but the table
   keeps its selection model): every key needs a workaround and the focus is split between the table and the editor.
   A `JList` does not edit. A custom canvas would have to reimplement text editing.
2. **Accessibility.** Each field is a real accessible text component whose name is « Ligne 3 » and whose description holds
   the result (« Résultat : 37,5, exact »); the Tab order is the reading order. A table cell exposes less.
3. **Performance up to the limit of the model.** A row costs a few Swing components, and nothing is virtualised. Measured
   (docs/TESTS.md, E12): in a window of 500 lines with references, validating line 1 updates the 501 rows in a median 19 ms
   (41 ms with layout and an off-screen paint); at the 5000 lines of the model, 85 ms (115 ms with layout and paint) after an
   opening of 1,5 s. The budget of the brief (100 ms) is met at 500 lines; at 5000 it is only approached, and the cost of
   opening grows with the number of rows. A virtualised list would be needed beyond that: not justified now.

The view only draws a `NotebookViewModel` and forwards intentions; every decision is in `NotebookPresenter`, testable
without Swing (a test reads the sources and refuses `javax.swing` / `java.awt` there).

### 2. The contract between the view and the presenter

- Every intention is a method that takes **the text now in the active field** (`pending`) and returns a new immutable
  `NotebookViewModel`: rows (number, text, status exact / approximate / error / comment / empty, formatted result, detail
  for the tooltip, error message and faulty span, defined variable, accessible description), the active row, the draft,
  the focus request, `canUndo` / `canRedo`, the display mode, the status message and its tone, the watermark.
- The text typed in the active field is its **draft**. It becomes a command of the model only when it is *validated*: Enter,
  moving to another line with the arrows or the mouse, inserting or moving a line, closing the window. Escape drops it. The
  view never keeps a state of its own: it shows what the view model says, and a field whose text is already right is left
  alone (the caret does not jump).
- **The new line at the end** is a row that is always shown after the last line (number n + 1) and is not in the model until
  it is validated with a text. Enter on the last line moves to it; Enter on it with nothing typed does nothing. This is
  how « Enter creates a line at the end » is kept without storing blank lines the user never asked for, and why undoing a
  typed line is one undo, not three.
- A line longer than 16 384 characters (the longest the file reads back, ADR 0009) is refused with a message and kept as a
  draft; the field itself stops accepting text one character beyond, so a huge paste cannot fill the memory. A line break in
  a pasted text becomes a space.

### 3. Granularity of undo and redo

Undo and redo act on **validated commands**, one per validated edit, insertion, deletion, move or clear (ADR 0008,
section 6). The line being typed is **not** part of the history; Escape drops it. To keep Ctrl+Z honest in a text field:

- **Undo with a pending draft** drops the draft first (like Escape) and undoes nothing; the next undo reverts the last
  command. Nothing the user typed is lost by surprise, and nothing is undone behind their back.
- **Redo with a pending draft** is refused with a sentence (validate or cancel first): it would overwrite the typing.
- After an undo or a redo the active line is the same line if it still exists, otherwise the line at the same place.

### 4. Keys

The modifier of the menu shortcuts is `Toolkit.getMenuShortcutKeyMaskEx()` (Ctrl on Windows and Linux, Cmd on macOS); the
window takes it as a parameter, and tests build it with each value.

| Key | Action |
|---|---|
| `Entrée` | validate the line, go to the next one (the new line after the last) |
| `↑` / `↓` | previous / next line (the line left is validated) |
| `Échap` | drop what was typed since the line was validated |
| `Ctrl+Z` / `Cmd+Z` | undo (drops a pending draft first) |
| `Ctrl+Y` / `Cmd+Y`, `Ctrl+Maj+Z` / `Cmd+Maj+Z` | redo |
| `Ctrl+Entrée` / `Cmd+Entrée` | insert a line below |
| `Ctrl+Maj+Entrée` / `Cmd+Maj+Entrée` | insert a line above |
| `Ctrl+Maj+K` / `Cmd+Maj+K` | delete the line |
| `Ctrl+Maj+↑` / `Ctrl+Maj+↓` (always Ctrl) | move the line up / down |
| `Ctrl+M` (always Ctrl) | next display mode (ADR 0006) |

Why these keys are free:

- **No clash with the editing of a text field.** `NotebookShortcutConflictTest` walks the text field input maps of every
  look and feel installed in the Java runtime (Metal, Nimbus, Motif, Windows, Windows Classic here; the CI adds the system
  look and feel of Linux and macOS): none of the keys with a modifier is bound. The plain keys are bound by the text fields
  of every look and feel (Enter, Up, Down, Escape), so the window binds the keys of the notebook in the **input map of each
  field**, which has priority over the look and feel; a test checks that every key resolves to an always-enabled action there.
- **Ctrl+Z in a field.** A Swing text field has no undo of its own, so the key is ours; it is documented that it reverts
  the last validated command, not the typing.
- **Delete is `Ctrl+Maj+K`**, not `Ctrl+Suppr`: Ctrl+Delete is « delete the next word » in a text field. Moving a
  line is `Ctrl+Maj+↑` / `↓` on every system, not `Alt+↑` / `↓`: the Aqua text field binds Alt+Up and Alt+Down (found by
  the macOS run of `NotebookShortcutConflictTest`), and on Linux some window managers use Alt+arrows for workspaces.
- **`Ctrl+M` stays `Ctrl`** on macOS because `Cmd+M` minimises a window.
- Not checked on a real macOS keyboard here; the Aqua look and feel is not part of the Java runtime of this machine.

The menus (Édition, Affichage, Aide) show the same keys as accelerators. A menu action is disabled when it cannot run
(nothing to undo); the key actions of the field are always enabled so that Ctrl+Z can still drop a pending draft.

### 5. Evaluation on validation

The validation applies the command to the model through `DocumentService`, which recalculates the lines from the first one
that changed and saves the current notebook (ADR 0009); the view model then shows the recalculated rows. The result of a
line depends only on the lines above, so the window shows the cascade at once. A failed save is reported in the status bar
(« Enregistrement automatique impossible : ... Le carnet reste disponible dans cette fenêtre. ») and the next successful
save clears it; the state in memory is never lost.

### 6. No preview while typing

The brief makes it optional on the condition that a pause of about 150 ms in the typing keeps it fluid on 500 lines. Measured
(`NotebookResponsivenessTest`): recalculating the 499 lines below line 1 and formatting the rows takes a median of 11 ms
including the save, which `DocumentService` does on every command (best 9,6 ms, worst 15 ms); the full validation, with the save, takes a median of 14 ms. A preview would
be affordable. It is **not added**, on purpose:

- while the user types `2 +` the preview would show an error for every pause, and the result of a text that is not
  validated is a number that cannot be reused, undone or found in the saved file: three views of one line;
- the draft / validated line distinction (section 2) is what keeps undo and the file simple, and a preview would put a third
  state in the presenter and a timer in the view;
- Enter already shows the whole cascade in about 20 ms.

It can be added later without changing the model: a presenter method would evaluate a copy of the notebook with the draft.

### 7. Start, status, accessibility

- `Main` opens the store of the current user (it takes the lock that makes a second instance read only), the presenter
  loads the current notebook (migrating the old history on the first start, ADR 0009) and shows the first sentence: « Votre
  historique a été converti en carnet (n lignes). » in the status bar; it gives way to the next intention. When another instance
  holds the data directory the warning stays for the whole session, whatever the user does. The system look and feel is applied
  as in E08. The old form is no longer launched; it stays in the code until E13.
- The display mode starts from the mode saved with the notebook, or the remembered preference (E08) when the notebook says
  Auto; a change is remembered and saved with the notebook at its next write.
- An empty notebook shows a watermark in its first field: « Une expression par ligne, par exemple : prix = 12,5 · prix * 3 · ans / 2 »,
  painted, never text, never saved.
- A line in error shows `Erreur : <message>` in words under its field and the faulty part is underlined (a red line, which
  only repeats what the message says in words). A line that defines a variable has a tinted number cell and a tooltip.
  Every text colour reaches 4,5:1 on the row backgrounds (`NotebookPaletteContrastTest` prints the ratios: error 7,11 on
  white and 6,07 on the active row, number 8,06 and 6,89, variable 10,63, watermark 6,29, badges 9,06 and 8,39).
- The syntax help is a summary built into the application (the complete guide, `docs/GUIDE-EXPRESSIONS.md`, is not in the
  packaged application, and a built-in summary works everywhere); its examples are evaluated by a test so that it cannot drift.

## Alternatives considered (Alternatives considérées)

- **`JTable` with an editable column**: rejected for the keyboard and focus reasons above; the real gain, virtualisation,
  is not needed up to 5000 lines.
- **`JList` with one overlay editor**: one text field moved over the selected cell. Cheaper in components, but the field must
  be positioned, scrolled and synchronised with the list and the accessibility tree shows one field for all lines.
- **A blank line created in the model by Enter**: simpler presenter, but every Enter at the end writes a command, an
  autosave and an undo step, and a notebook ends with stray blank lines.
- **Undo that also undoes typing** (a separate undo manager of the text): two undo stacks with an order the user cannot see.
- **Showing the results of the draft** (section 6).

## Consequences (Conséquences)

- Each line costs a few Swing components: opening 500 lines takes about 0,85 s and 5000 lines about 1,5 s, and nothing is
  virtualised.
- The keys are documented in the Help menu (`NotebookHelp`), the README and here; the list lives in `NotebookShortcuts`
  only, which feeds the bindings, the menus and the help (`NotebookHelpTest` and `NotebookFrameTest` compare them).
- E13 adds the Fichier menu and the confirmation on close on top of `DocumentService`, and removes the old form; the
  presenter already validates a pending draft when the window closes.
- A line longer than 16 384 characters cannot be validated; a pasted block of lines is joined into one line (a smarter
  paste that splits it into lines is a follow-up).
