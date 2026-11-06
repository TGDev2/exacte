# 0014 - The notebook is calculated off the event thread, and can be interrupted

## Status (Statut)

Accepted. Date: 2026-11-06. Task E17, defect B1 of [docs/RECETTE.md](../RECETTE.md).

## Context (Contexte)

The recalculation ran on the Swing event thread, as an ordinary part of each command. It is bounded per line (1000 characters,
100 levels, 500 elements, 4096 bits) and has no global bound. The acceptance found a notebook that stays within every limit and
freezes the window: 5000 valid lines of the form `sqrt(2^4000+1)+…`, 974 characters each, took about 3 minutes to open, and each
Enter took about 11.5 s at 300 lines. No data was lost, but the window answered nothing, not even its close button.

The owner decided the question the report left open: calculate off the event thread, with cancellation.

## Decision (Décision)

We will keep the model on the event thread and move only the evaluation.

1. **What stays on the event thread.** The texts, the commands, undo and redo, the choice of files, the writing of files. Files
   that only hold texts (save, save as, the automatic save) therefore do not wait for anything. `NotebookEditor` and
   `DocumentService` no longer evaluate as part of a command; `current()` remains for the code and the tests that are not on the
   event thread, and the presenter never calls it.
2. **What moves.** `NotebookRecalculation` gives the evaluation to an injected `Executor`: one daemon thread named
   `notebook-recalc` in the application (`Main`). The evaluator itself does not change: `NotebookEvaluator.lineByLine` is the loop
   of `evaluate`, one step at a time, so the results are those of the synchronous calculation and ADR 0008 still holds. Results
   above the first line that changed are kept, as before.
3. **What comes back.** Each request opens a numbered generation. Its results return by `SwingUtilities.invokeLater` and a
   generation that is no longer the current one is thrown away, so an old value cannot reach the screen or a file. Lines finished
   while the event thread is busy merge into one event; no timer delays them.
4. **Replacing.** Every command that changes the notebook, the display mode, Open and New replace the calculation under way. The
   old task stops at the end of the line it is evaluating: the check is between two lines, and an operation on huge numbers in
   progress is never interrupted.
5. **What the user sees.** A line not calculated yet says « Calcul en cours… », never an old value and never an empty result that
   could pass for 0. The status bar says « Calcul en cours : n / N lignes », then the usual message. The sentence of a file just
   opened waits for the end, because it counts the lines in error. Two errors stay visible over the count: a refusal of the last
   intention, because a key that did nothing must say why, and the failure of the automatic save, because the user must act on it.
   The other lasting warnings (a read-only data directory) wait for the end of the calculation.
6. **Escape.** With a line being typed it drops the input, as before. With nothing typed it interrupts: the lines left say « Non
   calculée : calcul interrompu » with the tone of an error, and the next command calculates again. Moving between lines is not a
   command, so that an interrupted notebook can be read; Enter on a line is one, even without a change.
7. **Exports.** They need the results, so a file asked for during a calculation waits for its end, without blocking the event
   thread, and then holds the notebook as it is at that moment: one whole state, never a mix. A later edit of the same notebook
   only moves that state forward. Escape, Open and New abandon the waiting export, and the status bar says so.
8. **Closing.** A close that goes on stops the calculation; the thread is a daemon and never holds the process back. While an
   export waits, closing is refused with a sentence that says what to do: a file the user asked for is not lost in silence. This
   is the one exception to "closing cancels", chosen as the cautious reading.
9. **A failure of the calculation** (an unexpected exception, a stack overflow, memory) ends like an interruption, with its own
   sentence and a line in the log. On the event thread such a failure left the window as it was; here, without this, the lines
   would say « Calcul en cours… » for ever.

## Alternatives considered (Alternatives considérées)

- **A budget global de calcul**, which puts the following lines in error once it is spent. Set aside: measured in time, its result
  depends on the machine, and even on its load, so the same notebook would give different lines on two computers and a file could
  not be trusted to mean the same thing everywhere. Measured in operations, it would refuse notebooks that are valid today.
- **Calculating only the visible lines.** Set aside: a line depends on those above it, exports and the count of errors need them all,
  and the rows of the window are not virtualised.
- **Keeping the synchronous path for small notebooks.** Set aside: two paths to test, and a threshold that the same costly line
  defeats. One line can be slow; the size of the notebook says nothing about it.
- **Interrupting the thread during a line.** Set aside: `BigInteger` does not answer interruption, and a stopped operation would
  leave nothing to keep. A line of the measured hostile notebook takes about 37 ms, so the check between two lines is enough.
- **Continuing the same calculation through a change of display mode.** It would save the line in progress, but the mode is one of
  the commands that must replace the calculation, and one rule is simpler to prove than two.

## Consequences (Conséquences)

- Easier: the window answers during any calculation (the median round trip of the event thread is measured by
  `BackgroundRecalculationAcceptanceTest` on the real application), and the user can stop what they started.
- Harder: a result is not there when a command returns. The tests read it through `NotebookDriver`, which plays each intention on
  the event thread and waits for the end. Every other new reader of the results must go through the presenter's state as well.
- A file can be saved while its results are not known, since it holds only texts. The status bar shows the confirmation at the end of
  the calculation; the title loses its modified marker at once.
- Escape only acts from a line, where the focus goes after each command: Ctrl+M must stay the only key of the whole window
  (ADR 0006), so it is not bound at the level of the window. With the focus on the selector of the modes, it does nothing.
- Still on the event thread at the first start: the migration of an old history checks each entry with one evaluation of a single
  operation, while the store loads (`HistoryBridge`). It runs before the window exists and only once, and no command reaches it.
- Still on the event thread: reading and writing files, parsing a large file at Open, and formatting. Formatting costs at most
  0.44 ms per hostile value, measured once: a change of display mode on 5000 calculated hostile lines holds the window for about
  2 s. It is now done once per mode instead of at every command.
- Still costly: the calculation itself. This task does not optimise the numeric engine, so the hostile notebook takes the same
  3 minutes, in the background.
