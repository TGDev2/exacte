package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.Operation;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.NotebookEvaluator;
import calc.number.Rational;
import calc.persistence.NotebookStore;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import calc.testing.DecimalGenerators.FractionText;
import calc.testing.FractionOracle;
import calc.testing.NotebookGenerators;
import calc.testing.TempDirectories;
import calc.ui.DisplayModeStore;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Reusing a result never degrades it (docs/adr/0004-regles-affichage.md, section 6), in the notebook: a random chain of exact
 * operations, each one written on a line with {@code ans} (the previous result) as the first or the second operand, typed
 * through the presenter, ends exactly on the value an independent fraction oracle computes. This is the notebook form of the
 * property that the two-field form used to carry (the previous result put back in a field).
 */
@Tag("property")
class NotebookLosslessChainProperties {
    private static final int MAX_STEPS = 8;

    /** One step of the chain: an operation, its other operand, and whether the previous result is the second operand. */
    record Step(Operation operation, String operand, FractionOracle value, boolean ansIsSecond) {
        String line() {
            String symbol = switch (operation) {
                case ADDITION -> "+";
                case SUBTRACTION -> "-";
                case MULTIPLICATION -> "*";
                case DIVISION -> "/";
                default -> throw new IllegalArgumentException(operation.name());
            };
            // The operand is a whole in parentheses: "ans / 3/4" would not be "ans / (3/4)".
            return ansIsSecond ? "(" + operand + ") " + symbol + " ans" : "ans " + symbol + " (" + operand + ")";
        }

        @Override
        public String toString() {
            return line();
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
                .filter(step -> step.operation() != Operation.DIVISION || step.ansIsSecond() || step.value().signum() != 0);
        return steps.list().ofMinSize(1).ofMaxSize(MAX_STEPS);
    }

    @Property(tries = 150)
    void aChainOfExactOperationsOnAnsStaysExact(@ForAll("starts") FractionText start, @ForAll("chains") List<Step> chain)
            throws Exception {
        TempDirectories.with(directory -> {
            try (NotebookStore store = NotebookStore.open(directory)) {
                NotebookDriver d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop()));
                FractionOracle expected = start.value();
                List<String> texts = new ArrayList<>();
                texts.add("(" + start.text() + ")");
                d.line(texts.get(0));

                for (Step step : chain) {
                    if (step.ansIsSecond() && step.operation() == Operation.DIVISION && expected.signum() == 0) {
                        continue; // (operand) / 0 is refused: the chain skips the step, as the oracle cannot divide by zero either
                    }
                    d.line(step.line());
                    texts.add(step.line());
                    expected = step.ansIsSecond() ? apply(step.operation(), step.value(), expected)
                            : apply(step.operation(), expected, step.value());
                    NotebookRow row = d.row(texts.size());
                    assertEquals(NotebookRow.Status.EXACT, row.status(), () -> "Every result of the chain is exact: " + chain + " " + row);
                }

                // The value the window's lines mean, recalculated from the texts the presenter keeps.
                EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(NotebookGenerators.notebook(d.texts().subList(0, texts.size())));
                Rational value = evaluated.results().get(texts.size() - 1).value().lower();
                FractionOracle oracle = expected;
                assertTrue(oracle.hasValue(value.numerator(), value.denominator()),
                        () -> "Chain " + start + " " + chain + " ended on " + value + " instead of " + oracle);
            }
        });
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
