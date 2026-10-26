package calc.persistence;

/**
 * A text that is not JSON the reader accepts. The message gives the reason and the character offset, never the content.
 */
public final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the text was refused. The last five are limits of {@link StorageLimits}; the others are errors of syntax. */
    public enum Reason {
        SYNTAX,
        DUPLICATE_KEY,
        TOO_DEEP,
        STRING_TOO_LONG,
        NUMBER_TOO_LONG;

        /** Whether the text was refused because of a bound rather than because it is malformed. */
        public boolean isLimit() {
            return this == TOO_DEEP || this == STRING_TOO_LONG || this == NUMBER_TOO_LONG;
        }
    }

    private final Reason reason;

    JsonException(Reason reason, int offset) {
        super(reason + " at character " + offset);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
