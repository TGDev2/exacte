package calc.number;

import java.util.Objects;

/**
 * A numeric operation was refused for a domain or size reason. The message is the code itself: it is meant for logs,
 * never for the user.
 */
public final class NumericException extends Exception {
    private static final long serialVersionUID = 1L;

    private final NumericError error;

    NumericException(NumericError error) {
        super(Objects.requireNonNull(error, "error").name());
        this.error = error;
    }

    public NumericError error() {
        return error;
    }
}
