package calc.expression;

import calc.number.NumericError;
import java.util.Objects;
import java.util.Optional;

/**
 * A line was refused while it was read or evaluated. Carries a code, the span of the line that the user must fix and a
 * French message meant for the user; a refusal of the numeric core also carries its {@link NumericError}.
 */
public final class ExpressionException extends Exception {
    private static final long serialVersionUID = 1L;

    private final ExpressionError error;
    private final Span span;
    private final NumericError numericError;

    ExpressionException(ExpressionError error, Span span, String message) {
        this(error, span, message, null);
    }

    ExpressionException(ExpressionError error, Span span, String message, NumericError numericError) {
        super(Objects.requireNonNull(message, "message"));
        this.error = Objects.requireNonNull(error, "error");
        this.span = Objects.requireNonNull(span, "span");
        this.numericError = numericError;
    }

    public ExpressionError error() {
        return error;
    }

    public Span span() {
        return span;
    }

    /**
     * The code of the numeric core: present for {@link ExpressionError#NUMERIC}, except for the safety net of an
     * unexpected {@link ArithmeticException}; absent for every other error.
     */
    public Optional<NumericError> numericError() {
        return Optional.ofNullable(numericError);
    }
}
