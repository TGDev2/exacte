package calc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.number.Rational;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import calc.testing.DecimalGenerators.FractionText;
import calc.testing.FractionOracle;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Reusing a result never degrades it (docs/adr/0004-regles-affichage.md, section 6): a random chain of exact operations,
 * each one starting from the previous result put back in the first field by the presenter, or reused from the history
 * into the second field, ends exactly on the value an independent fraction oracle computes.
 */
@Tag("property")
class LosslessReuseProperties {
    private static final int MAX_STEPS = 8;

    /** One step of the chain: an operation, its other operand, and how the previous result is reused. */
    record Step(Operation operation, String operand, FractionOracle value, boolean reuseIntoSecondField) {
        @Override
        public String toString() {
            return operation + " " + operand + (reuseIntoSecondField ? " (previous result as second operand)" : "");
        }
    }

    @Provide
    Arbitrary<FractionText> starts() {
        return DecimalGenerators.fractions();
    }

    @Provide
    Arbitrary<List<Step>> chains() {
        Arbitrary<DecimalText> decimals = DecimalGenerators.decimals(12);
        Arbitrary<FractionText> operands = Arbitraries.oneOf(
                DecimalGenerators.fractions(),
                decimals.map(decimal -> new FractionText(decimal.text(), FractionOracle.of(decimal.value()))));
        Arbitrary<Step> steps = Combinators.combine(
                        Arbitraries.of(Operation.ADDITION, Operation.SUBTRACTION, Operation.MULTIPLICATION, Operation.DIVISION),
                        operands,
                        Arbitraries.of(false, false, true))
                .as((operation, operand, swap) -> new Step(operation, operand.text(), operand.value(), swap))
                .filter(step -> step.operation() != Operation.DIVISION || step.reuseIntoSecondField() || step.value().signum() != 0);
        return steps.list().ofMinSize(1).ofMaxSize(MAX_STEPS);
    }

    @Property(tries = 500)
    void aChainOfExactOperationsByReuseStaysExact(@ForAll("starts") FractionText start, @ForAll("chains") List<Step> chain) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.ADDITION, "", "");
        CalculatorViewModel view = presenter.onCalculate(start.text(), "0");
        FractionOracle expected = start.value();

        for (Step step : chain) {
            if (step.reuseIntoSecondField()) {
                // The other operand goes first; the last result is taken from the history into the second field.
                if (step.operation() == Operation.DIVISION && expected.signum() == 0) {
                    continue;
                }
                presenter.onOperationChanged(step.operation(), step.operand(), "");
                presenter.onHistorySelectionChanged(view.historyEntries().size() - 1);
                CalculatorViewModel reused = presenter.onReuseHistoryResult(ReuseTarget.SECOND_NUMBER, step.operand(), "");
                view = presenter.onCalculate(reused.firstInput(), reused.secondInput());
                expected = apply(step.operation(), step.value(), expected);
            } else {
                presenter.onOperationChanged(step.operation(), view.firstInput(), "");
                view = presenter.onCalculate(view.firstInput(), step.operand());
                expected = apply(step.operation(), expected, step.value());
            }
            assertEquals(StatusTone.SUCCESS, view.statusTone(), () -> "Refused: " + chain);
            assertTrue(view.resultExact(), () -> "Every result of the chain is exact: " + chain);
        }

        CalculationRecord last = view.historyEntries().get(view.historyEntries().size() - 1);
        Rational value = last.getResult().lower();
        FractionOracle oracle = expected;
        assertTrue(last.isExact() && oracle.hasValue(value.numerator(), value.denominator()),
                () -> "Chain " + start + " " + chain + " ended on " + last.getResult() + " instead of " + oracle);
    }

    private static FractionOracle apply(Operation operation, FractionOracle first, FractionOracle second) {
        return switch (operation) {
            case ADDITION -> first.add(second);
            case SUBTRACTION -> first.subtract(second);
            case MULTIPLICATION -> first.multiply(second);
            case DIVISION -> first.divide(second);
            default -> throw new IllegalArgumentException("not an exact binary operation: " + operation);
        };
    }
}
