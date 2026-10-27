package calc.notebook;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * The editing session of a notebook, for a presenter: applies commands, keeps the evaluated notebook up to date and
 * offers undo and redo (docs/adr/0008-semantique-carnet.md, section 6). The granularity is one command that changes the
 * notebook; the undo stack keeps the last {@value #MAX_UNDO} notebooks and a new command empties the redo stack.
 * Notebooks are immutable, so undo and redo restore them exactly; their results are recalculated from the first line
 * that differs. Not thread safe: one thread (the Swing event thread) drives it.
 */
public final class NotebookEditor {
    /** The number of commands that can be undone. */
    public static final int MAX_UNDO = 200;

    private final Deque<Notebook> undo = new ArrayDeque<>();
    private final Deque<Notebook> redo = new ArrayDeque<>();
    private EvaluatedNotebook current;

    /** A session on an empty notebook. */
    public NotebookEditor() {
        this(Notebook.empty());
    }

    /** A session on {@code notebook} (for instance a notebook read back from a file), with nothing to undo. */
    public NotebookEditor(Notebook notebook) {
        current = NotebookEvaluator.evaluate(Objects.requireNonNull(notebook, "notebook"));
    }

    /** The notebook and the result of each line. */
    public EvaluatedNotebook current() {
        return current;
    }

    public Notebook notebook() {
        return current.notebook();
    }

    public List<LineResult> results() {
        return current.results();
    }

    /**
     * Applies {@code command} and returns the evaluated notebook. A command that changes nothing is not recorded; a refused
     * command changes nothing either.
     */
    public EvaluatedNotebook apply(NotebookCommand command) throws NotebookException {
        Notebook next = Objects.requireNonNull(command, "command").applyTo(current.notebook());
        if (next.equals(current.notebook())) {
            return current;
        }
        undo.push(current.notebook());
        if (undo.size() > MAX_UNDO) {
            undo.removeLast();
        }
        redo.clear();
        current = NotebookEvaluator.reevaluate(current, next);
        return current;
    }

    public boolean canUndo() {
        return !undo.isEmpty();
    }

    public boolean canRedo() {
        return !redo.isEmpty();
    }

    /** Restores the notebook before the last command. */
    public EvaluatedNotebook undo() {
        if (undo.isEmpty()) {
            throw new IllegalStateException("nothing to undo");
        }
        redo.push(current.notebook());
        current = NotebookEvaluator.reevaluate(current, undo.pop().continuingAfter(current.notebook()));
        return current;
    }

    /** Applies again the last command undone. */
    public EvaluatedNotebook redo() {
        if (redo.isEmpty()) {
            throw new IllegalStateException("nothing to redo");
        }
        undo.push(current.notebook());
        current = NotebookEvaluator.reevaluate(current, redo.pop().continuingAfter(current.notebook()));
        return current;
    }
}
