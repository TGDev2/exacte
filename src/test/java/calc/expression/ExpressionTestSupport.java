package calc.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;

/** Shared helpers of the expression tests. */
final class ExpressionTestSupport {
    static final String TIMES = String.valueOf((char) 0x00D7);
    static final String DIVIDED = String.valueOf((char) 0x00F7);
    static final String MINUS = String.valueOf((char) 0x2212);
    static final String ROOT = String.valueOf((char) 0x221A);
    static final String NBSP = String.valueOf((char) 0x00A0);
    static final String NNBSP = String.valueOf((char) 0x202F);

    private ExpressionTestSupport() {
    }

    /**
     * A line written with ASCII placeholders, so that the sources hold no ambiguous character: {@code {x}} ×,
     * {@code {:}} ÷, {@code {-}} the minus sign, {@code {v}} √, {@code {nb}} no-break space, {@code {nnb}} narrow
     * no-break space.
     */
    static String line(String template) {
        return template.replace("{x}", TIMES).replace("{:}", DIVIDED).replace("{-}", MINUS).replace("{v}", ROOT)
                .replace("{nnb}", NNBSP).replace("{nb}", NBSP);
    }

    static CertifiedValue exact(String rational) {
        try {
            return CertifiedValue.exact(Rational.parse(rational));
        } catch (NumericException exception) {
            throw new IllegalArgumentException(rational, exception);
        }
    }

    static CertifiedValue evaluate(String template, Resolver resolver) throws ExpressionException {
        ParsedLine parsed = ExpressionParser.parseLine(line(template));
        return Evaluator.evaluate(parsed.tree().orElseThrow(), resolver);
    }

    /** Parses and evaluates the line, and expects the refusal with its exact code, span and message. */
    static ExpressionException refusal(String template, Resolver resolver) {
        return assertThrows(ExpressionException.class, () -> evaluate(template, resolver), template);
    }

    static void assertRefusal(ExpressionException refusal, ExpressionError error, int start, int end, String message) {
        assertEquals(error, refusal.error(), refusal.getMessage());
        assertEquals(new Span(start, end), refusal.span(), refusal.getMessage());
        assertEquals(message, refusal.getMessage());
    }

    /** The same tree with every span replaced by {@link Span#NONE}: trees compared without their positions. */
    static Node withoutPositions(Node node) {
        return switch (node) {
            case Node.NumberLiteral number -> new Node.NumberLiteral(number.value(), Span.NONE);
            case Node.Variable variable -> new Node.Variable(variable.name(), Span.NONE);
            case Node.Ans ans -> new Node.Ans(Span.NONE);
            case Node.LineRef reference -> new Node.LineRef(reference.line(), Span.NONE);
            case Node.Unary unary -> new Node.Unary(unary.sign(), withoutPositions(unary.operand()), Span.NONE);
            case Node.Binary binary -> new Node.Binary(binary.operator(), withoutPositions(binary.left()),
                    withoutPositions(binary.right()), Span.NONE);
            case Node.Sqrt sqrt -> new Node.Sqrt(withoutPositions(sqrt.operand()), Span.NONE);
            case Node.Assignment assignment -> new Node.Assignment(assignment.name(), withoutPositions(assignment.value()),
                    Span.NONE);
        };
    }
}
