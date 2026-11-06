package calc.notebook;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * The editing session of a notebook, for a presenter: applies commands and offers undo and redo
 * (docs/adr/0008-semantique-carnet.md, section 6). The granularity is one command that changes the notebook; the undo stack
 * keeps the last {@value #MAX_UNDO} notebooks and a new command empties the redo stack. Notebooks are immutable, so undo and
 * redo restore them exactly.
 *
 * <p>No command evaluates anything: the window calculates off its event thread (docs/adr/0014-calcul-hors-interface.md).
 * {@link #current()} evaluates, on the thread that calls it, from the first line that differs from its last answer. Not thread
 * safe: one thread (the Swing event thread) drives it.
 */
public final class NotebookEditor {
    /** The number of commands that can be undone. */
    public static final int MAX_UNDO = 200;

    private final Deque<Notebook> undo = new ArrayDeque<>();
    private final Deque<Notebook> redo = new ArrayDeque<>();
    private Notebook notebook;
    // The last answer of current(), for the notebook of that moment; null before the first one.
    private EvaluatedNotebook evaluated;

    /** A session on an empty notebook. */
    public NotebookEditor() {
        this(Notebook.empty());
    }

    /** A session on {@code notebook} (for instance a notebook read back from a file), with nothing to undo. */
    public NotebookEditor(Notebook notebook) {
        this.notebook = Objects.requireNonNull(notebook, "notebook");
    }

    /** The notebook and the result of each line, evaluated now on the calling thread if that is still to do. */
    public EvaluatedNotebook current() {
        if (evaluated == null) {
            evaluated = NotebookEvaluator.evaluate(notebook);
        } else if (evaluated.notebook() != notebook) {
            evaluated = NotebookEvaluator.reevaluate(evaluated, notebook);
        }
        return evaluated;
    }

    public Notebook notebook() {
        return notebook;
    }

    /**
     * Applies {@code command} and returns the notebook. A command that changes nothing is not recorded; a refused command
     * changes nothing either.
     */
    public Notebook apply(NotebookCommand command) throws NotebookException {
        Notebook next = Objects.requireNonNull(command, "command").applyTo(notebook);
        if (next.equals(notebook)) {
            return notebook;
        }
        undo.push(notebook);
        if (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
        redo.clear();
        notebook = next;
        return notebook;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Restores the notebook before the last command. */
    public void undo() {
        if (undo.isEmpty()) {
            throw new IllegalStateException("nothing to undo");
        }
        redo.push(notebook);
        notebook = undo.pop().continuingAfter(notebook);
    }

    /** Applies again the last command undone. */
    public void redo() {
        if (redo.isEmpty()) {
            throw new IllegalStateException("nothing to redo");
        }
        undo.push(notebook);
        notebook = redo.pop().continuingAfter(notebook);
    }
}
