package calc.testing;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.testing.DecimalGenerators.DecimalText;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators of calculations (an operation and two typed operands) and of the history entries the engine makes from
 * them. Records are always produced by the public engine, so they are as valid as the application can make them.
 */
public final class RecordGenerators {
    private static final int MAX_POWER_EXPONENT = 12;

    /** An operation with the operand texts as typed. The second text is meaningless for the square root. */
    public record Expression(Operation operation, String first, String second) {
        @Override
        public String toString() {
            return operation + " [" + shorten(first) + "] [" + shorten(second) + "]";
        }

        private static String shorten(String text) {
            return text.length() <= 60 ? text : text.substring(0, 30) + "..." + "(" + text.length() + " characters)";
        }
    }

    private RecordGenerators() {
    }

    public static Arbitrary<Operation> operations() {
        return Arbitraries.of(Operation.class);
    }

    /**
     * Calculations of moderate size on all six operations. Most succeed; some are refused by the engine (a result that
     * is too long, a root of a negative number...), which is part of what the properties must cope with.
     */
    public static Arbitrary<Expression> expressions() {
        Arbitrary<DecimalText> operand = DecimalGenerators.operands();
        Arbitrary<DecimalText> nonZero = DecimalGenerators.nonZero(operand);
        Arbitrary<DecimalText> exponent = DecimalGenerators.integers(2)
                .filter(decimal -> decimal.value().abs().intValueExact() <= MAX_POWER_EXPONENT);
        Arbitrary<DecimalText> nonNegative = operand.filter(decimal -> decimal.value().signum() >= 0);
        return Arbitraries.frequencyOf(
                Tuple.of(2, expression(Operation.ADDITION, operand, operand)),
                Tuple.of(2, expression(Operation.SUBTRACTION, operand, operand)),
                Tuple.of(2, expression(Operation.MULTIPLICATION, operand, operand)),
                Tuple.of(2, expression(Operation.DIVISION, operand, nonZero)),
                Tuple.of(2, expression(Operation.POWER, operand, exponent)),
                Tuple.of(2, expression(Operation.ROOT, nonNegative, operand)),
                Tuple.of(1, fractionExpression(Operation.DIVISION)),
                Tuple.of(1, fractionExpression(Operation.MULTIPLICATION)));
    }

    /**
     * Calculations whose operands go up to the input bound: the results are often refused for being too large.
     */
    public static Arbitrary<Expression> longExpressions() {
        Arbitrary<DecimalText> operand = Arbitraries.frequencyOf(
                Tuple.of(9, DecimalGenerators.decimals()), Tuple.of(1, DecimalGenerators.atTheBound()));
        Arbitrary<DecimalText> nonZero = DecimalGenerators.nonZero(operand);
        Arbitrary<DecimalText> nonNegative = operand.filter(decimal -> decimal.value().signum() >= 0);
        return Arbitraries.frequencyOf(
                Tuple.of(2, expression(Operation.ADDITION, operand, operand)),
                Tuple.of(2, expression(Operation.SUBTRACTION, operand, operand)),
                Tuple.of(2, expression(Operation.MULTIPLICATION, operand, operand)),
                Tuple.of(2, expression(Operation.DIVISION, operand, nonZero)),
                Tuple.of(2, expression(Operation.ROOT, nonNegative, operand)));
    }

    /** Entries of a history: calculations of moderate size that the engine accepted. */
    public static Arbitrary<CalculationRecord> records() {
        return expressions().map(RecordGenerators::tryCalculate).filter(Optional::isPresent).map(Optional::get);
    }

    /** Entries of a history, operands up to the input bound included. */
    public static Arbitrary<CalculationRecord> anySizeRecords() {
        return Arbitraries.frequencyOf(Tuple.of(4, records()), Tuple.of(1, longExpressions()
                        .map(RecordGenerators::tryCalculate).filter(Optional::isPresent).map(Optional::get)));
    }

    /**
     * Entries calculated from a remembered approximate value, as the presenter makes them: the square root of an operand,
     * kept as a value, then used as the first or the second operand of another operation (powers up to 40, so that the
     * exact recalculation from its canonical point stays within the core limits).
     */
    public static Arbitrary<CalculationRecord> rememberedRootRecords() {
        Arbitrary<DecimalText> radicands = DecimalGenerators.nonZero(DecimalGenerators.decimals(20))
                .map(decimal -> new DecimalText(decimal.text().replaceFirst("^[+-]", ""), decimal.value().abs()));
        Arbitrary<String> others = Arbitraries.oneOf(DecimalGenerators.decimals(20).map(DecimalText::text),
                DecimalGenerators.integers(2).map(DecimalText::text), Arbitraries.of("123456789012345", "1e15", "1e-30"));
        Arbitrary<Operation> operations = Arbitraries.of(Operation.class);
        return Combinators.combine(radicands, operations, others, Arbitraries.of(true, false), Arbitraries.integers().between(-40, 40))
                .as((radicand, operation, other, rootFirst, exponent) -> {
                    try {
                        CalculatorEngine engine = new CalculatorEngine();
                        Operand root = Operand.known(engine.calculate(Operation.ROOT, radicand.text(), "").getResult());
                        if (operation == Operation.POWER) {
                            return Optional.of(engine.calculate(operation, root, Operand.typed(String.valueOf(exponent))));
                        }
                        return Optional.of(rootFirst
                                ? engine.calculate(operation, root, Operand.typed(other))
                                : engine.calculate(operation, Operand.typed(other), root));
                    } catch (CalculationException refused) {
                        return Optional.<CalculationRecord>empty();
                    }
                })
                .filter(Optional::isPresent).map(Optional::get);
    }

    /** The record the public engine makes of a calculation that it must accept. */
    public static CalculationRecord calculated(Operation operation, String first, String second) {
        return tryCalculate(new Expression(operation, first, second))
                .orElseThrow(() -> new AssertionError("The engine should accept " + operation + " " + first + " " + second));
    }

    /** The record the public engine makes of an expression, empty when the engine refuses it. */
    public static Optional<CalculationRecord> tryCalculate(Expression expression) {
        try {
            return Optional.of(new CalculatorEngine().calculate(expression.operation(), expression.first(), expression.second()));
        } catch (CalculationException exception) {
            return Optional.empty();
        }
    }

    private static Arbitrary<Expression> expression(Operation operation, Arbitrary<DecimalText> first, Arbitrary<DecimalText> second) {
        return Combinators.combine(first, second).as((a, b) -> new Expression(operation, a.text(), b.text()));
    }

    // Fractions typed as such: their results are exact values with an infinite decimal expansion (1/3), shown with « … ».
    private static Arbitrary<Expression> fractionExpression(Operation operation) {
        return Combinators.combine(DecimalGenerators.fractions(), DecimalGenerators.fractions())
                .as((a, b) -> new Expression(operation, a.text(), b.text()));
    }
}
