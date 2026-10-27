package calc.notebook;

import calc.expression.ExpressionError;
import calc.expression.ExpressionException;
import calc.expression.Span;
import java.util.Objects;

/**
 * Why a line has no value: the code of the language, the span of the line that the user must fix and a French message.
 * The notebook words the failures of names and references itself (docs/adr/0008-semantique-carnet.md, sections 2 and 3).
 */
public record LineError(ExpressionError code, Span span, String message) {
    public LineError {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(span, "span");
        Objects.requireNonNull(message, "message");
    }

    static LineError of(ExpressionException exception) {
        return new LineError(exception.error(), exception.span(), exception.getMessage());
    }
}
