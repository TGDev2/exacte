package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.ExpressionError;
import calc.expression.Span;
import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.List;

/** Shared helpers of the notebook tests. */
final class NotebookTestSupport {
    private NotebookTestSupport() {
    }

    /** The exact value written {@code rational} ({@code 1,5}, {@code 1/3}). */
    static CertifiedValue exact(String rational) {
        try {
            return CertifiedValue.exact(Rational.parse(rational));
        } catch (NumericException exception) {
            throw new AssertionError(rational, exception);
        }
    }

    static Notebook notebook(String... texts) {
        try {
            return Notebook.of(List.of(texts));
        } catch (NotebookException exception) {
            throw new AssertionError(exception);
        }
    }

    static EvaluatedNotebook evaluated(String... texts) {
        return NotebookEvaluator.evaluate(notebook(texts));
    }

    static void assertValue(String expected, LineResult result) {
        assertTrue(result.hasValue(), () -> "line " + result.number() + " « " + result.text() + " » should have a value: "
                + result.failure());
        assertEquals(exact(expected), result.value(), () -> "line " + result.number() + " « " + result.text() + " »");
    }

    static void assertError(ExpressionError code, int start, int end, String message, LineResult result) {
        assertTrue(result.isError(), () -> "line " + result.number() + " « " + result.text() + " » should be in error: "
                + result.result());
        assertEquals(new LineError(code, new Span(start, end), message), result.error(),
                () -> "line " + result.number() + " « " + result.text() + " »");
    }
}
