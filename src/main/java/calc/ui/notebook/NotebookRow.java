package calc.ui.notebook;

import java.util.Objects;

/**
 * One line of the notebook as the window shows it: every text is ready, the view only draws it.
 *
 * @param number      the number shown, from 1
 * @param text        the source text of the line
 * @param status      what the line is: empty, comment, exact, approximate or in error
 * @param result      the result in the display mode in force, with its « ≈ » or « … » marker; empty without a value
 * @param detail      the sentence of the exactness tooltip (error bound, exact value behind a cut text), empty if none
 * @param errorMessage the message of a line in error, empty otherwise
 * @param errorStart  the start of the faulty part of {@code text}, counted in characters (-1 without an error)
 * @param errorEnd    the end of the faulty part (excluded, -1 without an error)
 * @param definedName the variable that the line defines, empty if it defines none
 * @param description what a screen reader says after the name of the line, for instance {@code Résultat : 37,5, exact}
 */
public record NotebookRow(int number, String text, Status status, String result, String detail, String errorMessage,
                          int errorStart, int errorEnd, String definedName, String description) {
    /** What a line is. */
    public enum Status {
        EMPTY,
        COMMENT,
        EXACT,
        APPROXIMATE,
        ERROR
    }

    public NotebookRow {
        if (number < 1) {
            throw new IllegalArgumentException("line numbers start at 1: " + number);
        }
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(detail, "detail");
        Objects.requireNonNull(errorMessage, "errorMessage");
        Objects.requireNonNull(definedName, "definedName");
        Objects.requireNonNull(description, "description");
    }

    /** The accessible name of the line: « Ligne 3 ». */
    public String name() {
        return "Ligne " + number;
    }

    /** Whether the line has a value to show with an exactness badge. */
    public boolean hasValue() {
        return status == Status.EXACT || status == Status.APPROXIMATE;
    }

    public boolean definesVariable() {
        return !definedName.isEmpty();
    }
}
