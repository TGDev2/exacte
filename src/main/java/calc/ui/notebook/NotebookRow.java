package calc.ui.notebook;

import java.util.Objects;

/**
 * One line of the notebook as the window shows it: every text is ready, the view only draws it.
 *
 * @param number      the number shown, from 1
 * @param text        the source text of the line
 * @param status      what the line is: empty, comment, exact, approximate, in error, or not calculated yet
 * @param result      the result in the display mode in force, with its « ≈ » or « … » marker; empty without a value
 * @param detail      the sentence of the exactness tooltip (error bound, exact value behind a cut text), empty if none
 * @param errorMessage the message of a line in error, empty otherwise
 * @param errorStart  the start of the faulty part of {@code text}, counted in characters (-1 without an error)
 * @param errorEnd    the end of the faulty part (excluded, -1 without an error)
 * @param definedName the variable that the line defines, empty if it defines none
 * @param description the line in one sentence, for instance {@code Résultat : 37,5, exact}; the window gives a screen reader
 *                    {@link #accessibleDescription()}, which spells the exactness out
 * @param source      whether the line is a source of the active line: its margin shows {@link #SOURCE_MARK}
 * @param preview     on the active line only, the result it would have with the text being typed, in the display mode in
 *                    force; empty when there is none. It is never a result: nothing is validated, reused or saved
 */
public record NotebookRow(int number, String text, Status status, String result, String detail, String errorMessage,
                          int errorStart, int errorEnd, String definedName, String description, boolean source, String preview) {
    /** The mark of the margin of a source of the active line. */
    public static final String SOURCE_MARK = "◆";

    /** What a line is. */
    public enum Status {
        EMPTY,
        COMMENT,
        EXACT,
        APPROXIMATE,
        ERROR,
        PENDING
    }

    /** How the line is drawn: a title (its text starts with « # »), another comment, an assignment, a calculation, nothing. */
    public enum Kind {
        TITLE,
        COMMENT,
        ASSIGNMENT,
        CALCULATION,
        EMPTY
    }

    /** The sign of the margin, and what it says in words: the colour is never the only bearer of the meaning. */
    public enum Sign {
        EXACT("=", "valeur exacte"),
        APPROXIMATE("≈", "valeur approchée"),
        ERROR("!", "erreur"),
        NONE("", "");

        private final String text;
        private final String spoken;

        Sign(String text, String spoken) {
            this.text = text;
            this.spoken = spoken;
        }

        /** « = », « ≈ », « ! », or nothing for a comment, an empty line or a line still to calculate. */
        public String text() {
            return text;
        }

        /** The same in words, for a screen reader. */
        public String spoken() {
            return spoken;
        }
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
        Objects.requireNonNull(preview, "preview");
    }

    /** A line that is not a source of the active line. */
    public NotebookRow(int number, String text, Status status, String result, String detail, String errorMessage,
                       int errorStart, int errorEnd, String definedName, String description) {
        this(number, text, status, result, detail, errorMessage, errorStart, errorEnd, definedName, description, false);
    }

    /** A line without a preview. */
    public NotebookRow(int number, String text, Status status, String result, String detail, String errorMessage,
                       int errorStart, int errorEnd, String definedName, String description, boolean source) {
        this(number, text, status, result, detail, errorMessage, errorStart, errorEnd, definedName, description, source, "");
    }

    /** The same line, a source of the active line or not. */
    public NotebookRow withSource(boolean isSource) {
        return isSource == source ? this
                : new NotebookRow(number, text, status, result, detail, errorMessage, errorStart, errorEnd, definedName, description,
                        isSource, preview);
    }

    /** The same line with {@code shown} as its preview (empty for none). */
    public NotebookRow withPreview(String shown) {
        return shown.equals(preview) ? this
                : new NotebookRow(number, text, status, result, detail, errorMessage, errorStart, errorEnd, definedName, description,
                        source, shown);
    }

    /** Whether the line shows a preview of the text being typed. */
    public boolean hasPreview() {
        return !preview.isEmpty();
    }

    /** « ◆ » for a source of the active line, nothing otherwise. */
    public String sourceMark() {
        return source ? SOURCE_MARK : "";
    }

    /** The accessible name of the line: « Ligne 3 ». */
    public String name() {
        return "Ligne " + number;
    }

    /** Whether the line has a value, exact or approximate. */
    public boolean hasValue() {
        return status == Status.EXACT || status == Status.APPROXIMATE;
    }

    public boolean definesVariable() {
        return !definedName.isEmpty();
    }

    /** How the line is drawn. */
    public Kind kind() {
        if (text.startsWith("#")) {
            return Kind.TITLE;
        }
        return switch (status) {
            case COMMENT -> Kind.COMMENT;
            case EMPTY -> Kind.EMPTY;
            default -> definesVariable() ? Kind.ASSIGNMENT : Kind.CALCULATION;
        };
    }

    /**
     * The sign of the margin, from the status; none while the line shows a preview (docs/design/DESIGN.md): the value shown is
     * not validated, and the sign of the validated one would speak of another value.
     */
    public Sign sign() {
        if (hasPreview()) {
            return Sign.NONE;
        }
        return switch (status) {
            case EXACT -> Sign.EXACT;
            case APPROXIMATE -> Sign.APPROXIMATE;
            case ERROR -> Sign.ERROR;
            case EMPTY, COMMENT, PENDING -> Sign.NONE;
        };
    }

    /**
     * What a screen reader says after the name of the line. For a value it spells the exactness the sign of the margin shows
     * ({@code Résultat : 5 167,5, valeur exacte, définit la variable total}); otherwise it is the {@link #description}. A source of
     * the active line adds « , source de la ligne active ». A preview is said first, as what the result zone shows:
     * {@code Aperçu : 5, non validé}.
     */
    public String accessibleDescription() {
        if (hasPreview()) {
            return "Aperçu : " + preview + ", non validé";
        }
        String sourceOfActive = source ? ", source de la ligne active" : "";
        if (!hasValue()) {
            return description + sourceOfActive;
        }
        return "Résultat : " + result + ", " + sign().spoken() + (definesVariable() ? ", définit la variable " + definedName : "")
                + sourceOfActive;
    }

    /**
     * The result up to its first decimal comma, excluded: the part drawn left of the column of the commas. The whole result
     * when it has no comma (an integer, a fraction, a sentence).
     */
    public String resultIntegerPart() {
        return integerPart(result);
    }

    /** The preview up to its first decimal comma, excluded, as {@link #resultIntegerPart()} for the result. */
    public String previewIntegerPart() {
        return integerPart(preview);
    }

    private static String integerPart(String shown) {
        int comma = shown.indexOf(',');
        return comma < 0 ? shown : shown.substring(0, comma);
    }

    /** The result from its first decimal comma, included: the part drawn right of the column of the commas. Empty without one. */
    public String resultDecimalPart() {
        int comma = result.indexOf(',');
        return comma < 0 ? "" : result.substring(comma);
    }
}
