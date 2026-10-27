package calc.persistence;

/**
 * A notebook file that cannot be read as a whole, or a notebook that cannot be written as a file: the structure is wrong
 * or a bound of {@link StorageLimits} is exceeded. A line whose text the language refuses is not an exception: it is kept
 * and shows as an error in the notebook.
 */
public final class NotebookFormatException extends Exception {
    private static final long serialVersionUID = 1L;

    /** Why the file was refused, or the notebook not written. */
    public enum Reason {
        /** More than {@link StorageLimits#MAX_NOTEBOOK_FILE_BYTES} bytes. */
        TOO_LARGE("Ce fichier dépasse la taille maximale d'un carnet (16 Mio)."),
        /** More lines than {@code Notebook.MAX_LINES}. */
        TOO_MANY_LINES("Ce carnet a trop de lignes (5 000 au plus)."),
        /** A bound of the JSON reader: depth, length of a string or of a number, number of values. */
        JSON_LIMIT("Ce fichier dépasse les limites de lecture (imbrication, texte, nombre ou quantité de valeurs)."),
        /** A line longer than {@link StorageLimits#MAX_STRING_LENGTH} characters (writing only: reading refuses it as JSON_LIMIT). */
        LINE_TOO_LONG("Une ligne dépasse 16 384 caractères : ce carnet ne peut pas être enregistré."),
        /** The bytes are not UTF-8. */
        NOT_UTF8("Ce fichier n'est pas un texte UTF-8."),
        /** The text is not JSON. */
        MALFORMED_JSON("Ce fichier n'est pas un document JSON valide."),
        /** JSON, but not a notebook: no {@code "format": "calc-notebook"}. */
        NOT_A_NOTEBOOK("Ce fichier n'est pas un carnet Calc."),
        /** No usable {@code version}: missing, not an integer, or below 3. */
        INVALID_VERSION("Le numéro de version de ce carnet est absent ou invalide."),
        /** {@code lines} is missing or is not an array, or an element of it is not an object with a text {@code source}. */
        MALFORMED_STRUCTURE("La liste des lignes de ce carnet est absente ou mal formée."),
        /** The path is not a regular file (a directory, a device, a pipe). */
        NOT_A_FILE("Ce chemin n'est pas un fichier ordinaire.");

        private final String userMessage;

        Reason(String userMessage) {
            this.userMessage = userMessage;
        }

        /** The sentence to show the user, in French. */
        public String userMessage() {
            return userMessage;
        }

        /** Whether the file was refused because of a bound rather than because it is damaged. */
        public boolean isLimit() {
            return this == TOO_LARGE || this == TOO_MANY_LINES || this == JSON_LIMIT || this == LINE_TOO_LONG;
        }
    }

    private final Reason reason;

    NotebookFormatException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    NotebookFormatException(Reason reason) {
        this(reason, null);
    }

    public Reason reason() {
        return reason;
    }

    /** The sentence to show the user, in French. */
    public String userMessage() {
        return reason.userMessage();
    }
}
