package calc.notebook;

/** A command was refused by a limit of the notebook; the message is French and meant for the user. */
public final class NotebookException extends Exception {
    private static final long serialVersionUID = 1L;

    NotebookException(String message) {
        super(message);
    }
}
