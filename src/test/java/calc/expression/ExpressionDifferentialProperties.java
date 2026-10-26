package calc.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.statistics.Statistics;

/**
 * Differential: evaluating a random tree of numbers gives what the operations of the numeric core give when they are
 * applied directly, value for value and refusal for refusal, whether the tree is evaluated as built or after being
 * printed and parsed again. The evaluator adds nothing to the arithmetic of the core.
 */
@Tag("property")
class ExpressionDifferentialProperties {
    private static final ExpressionParser PARSER = new ExpressionParser(new ExpressionLimits(100_000, 100, 500));

    @Provide
    Arbitrary<Node> numericTrees() {
        return ExpressionGenerators.trees(
                ExpressionGenerators.smallLiteralValues().map(value -> new Node.NumberLiteral(value, Span.NONE)), 4);
    }

    @Property
    void theEvaluatorMatchesTheCoreAppliedDirectly(@ForAll("numericTrees") Node tree) throws ExpressionException {
        Optional<CertifiedValue> expected;
        NumericException refusal = null;
        try {
            expected = Optional.of(direct(tree));
        } catch (NumericException exception) {
            expected = Optional.empty();
            refusal = exception;
        }
        Statistics.label("outcome").collect(expected.isPresent() ? (expected.get().isExact() ? "exact" : "approximate")
                : "refused");
        Statistics.label("outcome").coverage(coverage -> {
            coverage.check("exact").percentage(percent -> percent > 20);
            coverage.check("approximate").percentage(percent -> percent > 2);
            coverage.check("refused").percentage(percent -> percent > 5);
        });
        Node reparsed = PARSER.parse(CanonicalPrinter.print(tree)).tree().orElseThrow();

        for (Node evaluated : new Node[] {tree, reparsed}) {
            if (expected.isPresent()) {
                assertEquals(expected.get(), Evaluator.evaluate(evaluated, TableResolver.empty()));
            } else {
                ExpressionException refused = assertThrows(ExpressionException.class,
                        () -> Evaluator.evaluate(evaluated, TableResolver.empty()));
                assertEquals(ExpressionError.NUMERIC, refused.error());
                assertEquals(Optional.of(refusal.error()), refused.numericError());
            }
        }
    }

    // The oracle: each node is the operation of the core with the same name, applied directly.
    private static CertifiedValue direct(Node node) throws NumericException {
        return switch (node) {
            case Node.NumberLiteral number -> CertifiedValue.exact(number.value());
            case Node.Unary unary -> unary.sign() == Node.Sign.MINUS ? direct(unary.operand()).negate()
                    : direct(unary.operand());
            case Node.Binary binary -> {
                CertifiedValue left = direct(binary.left());
                CertifiedValue right = direct(binary.right());
                yield switch (binary.operator()) {
                    case ADD -> left.add(right);
                    case SUBTRACT -> left.subtract(right);
                    case MULTIPLY -> left.multiply(right);
                    case DIVIDE -> left.divide(right);
                    case POWER -> left.pow(right);
                };
            }
            case Node.Sqrt sqrt -> direct(sqrt.operand()).sqrt();
            default -> throw new IllegalArgumentException("not a numeric tree: " + node);
        };
    }
}
