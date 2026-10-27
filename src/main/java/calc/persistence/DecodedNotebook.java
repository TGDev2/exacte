package calc.persistence;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import java.util.Objects;

/**
 * What {@link NotebookCodec#decode(byte[])} found in a notebook file.
 *
 * @param version        the {@code version} field of the file
 * @param notebook       the lines of the file, kept as written (empty when {@code linesRead} is false)
 * @param displayMode    the display mode of the file, {@link DisplayMode#AUTO} when it gives none or an unknown one
 * @param linesRead      false when the file is from a newer version and its lines could not be read at all
 * @param regeneratedIds how many line identifiers were absent, malformed or duplicated and were given a new one
 * @param splitBreaks    how many line texts held a line break, each replaced with a space
 */
public record DecodedNotebook(int version, Notebook notebook, DisplayMode displayMode, boolean linesRead, int regeneratedIds,
                              int splitBreaks) {
    public DecodedNotebook {
        Objects.requireNonNull(notebook, "notebook");
        Objects.requireNonNull(displayMode, "displayMode");
    }

    /** Whether the file was written by a version of the format this program does not know. */
    public boolean isNewerVersion() {
        return version > NotebookCodec.VERSION;
    }
}
