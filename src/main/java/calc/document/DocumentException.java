package calc.document;

/** An operation on a document failed; the message is French and meant for the user, the cause (if any) is for the log. */
public final class DocumentException extends Exception {
    private static final long serialVersionUID = 1L;

    /** What went wrong, for a presenter that reacts differently (for instance by offering another name). */
    public enum Kind {
        /** The file is not a notebook, is damaged, or is over a limit of the format. */
        INVALID_FILE,
        /** The file was written by a newer version of Calc and cannot be read at all. */
        UNREADABLE_NEWER_VERSION,
        /** The document comes from a newer version of Calc: it is never rewritten. */
        READ_ONLY_VERSION,
        /** The path is one of the files Calc keeps for itself in its data directory. */
        RESERVED_PATH,
        /** The disk refused to read or write. */
        INPUT_OUTPUT,
        /** The notebook is over a limit of the format and cannot be written. */
        TOO_LARGE_TO_SAVE
    }

    private final Kind kind;

    DocumentException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    DocumentException(Kind kind, String message) {
        this(kind, message, null);
    }

    public Kind kind() {
        return kind;
    }
}
