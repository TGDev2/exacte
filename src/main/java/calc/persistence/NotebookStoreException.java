package calc.persistence;

/** The current notebook could not be written; the message is French and meant for the user. */
public final class NotebookStoreException extends Exception {
    private static final long serialVersionUID = 1L;

    NotebookStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    NotebookStoreException(String message) {
        super(message);
    }
}
