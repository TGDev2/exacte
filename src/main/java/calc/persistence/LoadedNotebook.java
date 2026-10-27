package calc.persistence;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link NotebookStore#load()} restored: the current notebook, how the store stands afterwards and the sentence to show
 * the user, if any.
 *
 * @param notebook       the notebook, to be evaluated by the caller (results are never stored)
 * @param displayMode    the display mode saved with it
 * @param condition      what the user has to be told about the store itself
 * @param status         the French sentence for the status bar, {@code null} when there is nothing to say
 * @param migratedLines  the lines of the notebook made from the old history (0 unless {@code condition} is MIGRATED)
 * @param commentedEntries how many of them are comments because no line can express the entry
 * @param omittedEntries how many of the oldest history entries did not fit in a notebook (they stay in the backup)
 * @param backup         the copy made of the history (MIGRATED) or of a damaged file (UNREADABLE), {@code null} otherwise
 */
public record LoadedNotebook(Notebook notebook, DisplayMode displayMode, Condition condition, String status, int migratedLines,
                             int commentedEntries, int omittedEntries, Path backup) {
    /** What the user has to be told about the store itself, besides the notebook. */
    public enum Condition {
        /** Nothing special: the current notebook was read, or there was nothing yet. */
        NORMAL,
        /** The history was converted to a notebook, which is now the current notebook. */
        MIGRATED,
        /** The history exists but was not converted (unreadable, newer version, or a copy could not be made): it is untouched. */
        HISTORY_NOT_CONVERTED,
        /** Another instance of Calc holds the data directory: this one loads but never writes. */
        IN_USE_READ_ONLY,
        /** The notebook comes from a newer version of Calc: its lines are shown and the file is never written. */
        NEWER_VERSION_READ_ONLY,
        /** The notebook comes from a newer version of Calc and could not be read: nothing is shown or written. */
        NEWER_VERSION_NOT_LOADED,
        /** The current file cannot be read as a notebook: an empty notebook is shown, and the file is copied or protected. */
        UNREADABLE
    }

    public LoadedNotebook {
        Objects.requireNonNull(notebook, "notebook");
        Objects.requireNonNull(displayMode, "displayMode");
        Objects.requireNonNull(condition, "condition");
    }

    public Optional<String> statusMessage() {
        return Optional.ofNullable(status);
    }
}
