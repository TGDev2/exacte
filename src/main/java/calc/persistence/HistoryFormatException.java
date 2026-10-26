package calc.persistence;

/**
 * A history file that cannot be read as a whole: the structure is wrong or a bound of {@link StorageLimits} is
 * exceeded. An entry that is wrong inside a readable file is not an exception: it is rejected and counted.
 */
public final class HistoryFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the file was refused. */
    public enum Reason {
        /** More than {@link StorageLimits#MAX_FILE_BYTES} bytes. */
        TOO_LARGE,
        /** More than {@link StorageLimits#MAX_ENTRIES} entries. */
        TOO_MANY_ENTRIES,
        /** The JSON is deeper than {@link StorageLimits#MAX_DEPTH} or has a string or number over its bound. */
        JSON_LIMIT,
        /** The bytes are not UTF-8. */
        NOT_UTF8,
        /** The text is not JSON. */
        MALFORMED_JSON,
        /** JSON, but not a history: no {@code "format": "calc-history"}. */
        NOT_A_HISTORY,
        /** No usable {@code version}: missing, not an integer, or below 2 (there never was a JSON version 1). */
        INVALID_VERSION,
        /** {@code entries} is missing or is not an array. */
        MALFORMED_STRUCTURE;

        /** Whether the file was refused because of a bound rather than because it is damaged. */
        public boolean isLimit() {
            return this == TOO_LARGE || this == TOO_MANY_ENTRIES || this == JSON_LIMIT;
        }
    }

    private final Reason reason;

    HistoryFormatException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    HistoryFormatException(Reason reason) {
        this(reason, null);
    }

    public Reason reason() {
        return reason;
    }
}
