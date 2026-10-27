package calc.notebook;

import java.util.Objects;

/**
 * One editing command of a notebook, as a value: the unit of undo and redo (docs/adr/0008-semantique-carnet.md,
 * section 6). Lines are designated by their identifier, so a command keeps its meaning whatever the numbering.
 */
public sealed interface NotebookCommand {
    /** The notebook after the command; {@code notebook} itself when the command changes nothing. */
    Notebook applyTo(Notebook notebook) throws NotebookException;

    /** Replaces the text of a line. */
    record Edit(LineId line, String text) implements NotebookCommand {
        public Edit {
            Objects.requireNonNull(line, "line");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Notebook applyTo(Notebook notebook) {
            return notebook.edit(line, text);
        }
    }

    /** Inserts a line just above {@code anchor}. */
    record InsertBefore(LineId anchor, String text) implements NotebookCommand {
        public InsertBefore {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Notebook applyTo(Notebook notebook) throws NotebookException {
            return notebook.insertBefore(anchor, text);
        }
    }

    /** Inserts a line just below {@code anchor}. */
    record InsertAfter(LineId anchor, String text) implements NotebookCommand {
        public InsertAfter {
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Notebook applyTo(Notebook notebook) throws NotebookException {
            return notebook.insertAfter(anchor, text);
        }
    }

    /** Adds a line at the end. */
    record Append(String text) implements NotebookCommand {
        public Append {
            Objects.requireNonNull(text, "text");
        }

        @Override
        public Notebook applyTo(Notebook notebook) throws NotebookException {
            return notebook.append(text);
        }
    }

    /** Deletes a line. */
    record Delete(LineId line) implements NotebookCommand {
        public Delete {
            Objects.requireNonNull(line, "line");
        }

        @Override
        public Notebook applyTo(Notebook notebook) {
            return notebook.delete(line);
        }
    }

    /** Moves a line so that its index, from 0, becomes {@code toIndex}. */
    record Move(LineId line, int toIndex) implements NotebookCommand {
        public Move {
            Objects.requireNonNull(line, "line");
        }

        @Override
        public Notebook applyTo(Notebook notebook) {
            return notebook.move(line, toIndex);
        }
    }

    /** Deletes every line. */
    record Clear() implements NotebookCommand {
        @Override
        public Notebook applyTo(Notebook notebook) {
            return notebook.clear();
        }
    }
}
