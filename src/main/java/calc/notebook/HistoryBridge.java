package calc.notebook;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.expression.Evaluator;
import calc.expression.ExpressionException;
import calc.expression.ExpressionParser;
import calc.expression.Node;
import calc.expression.TableResolver;
import java.util.Objects;
import java.util.Optional;

/**
 * Turns an entry of the old history (an operation and its operands) into a notebook line of the same value
 * (docs/adr/0008-semantique-carnet.md, section 8): {@code 1,5 + 2,5}, {@code 2 ^ (-3)}, {@code (1/3) * 3}, {@code √(9)}.
 * Each operand is its canonical text, between parentheses when it is negative or a fraction, so that the line reads
 * back as the same operation on the same operands.
 */
public final class HistoryBridge {
    private HistoryBridge() {
    }

    /**
     * The line of {@code entry}, which evaluates to exactly the value of the entry (the same interval for a root). Empty
     * when no line can: an operand that is an approximate value has no literal in the grammar, and an operand may be
     * longer than a line. Never a line of another value: the line is evaluated and compared before it is returned.
     */
    public static Optional<String> toLine(CalculationRecord entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.hasApproximateOperand()) {
            return Optional.empty();
        }
        Operation operation = entry.getOperation();
        String line = operation.requiresSecondOperand()
                ? operand(entry.getFirstValue()) + " " + operation.getHistorySymbol() + " " + operand(entry.getSecondValue())
                : "√(" + entry.getFirstValue() + ")";
        return sameValue(line, entry) ? Optional.of(line) : Optional.empty();
    }

    private static String operand(String canonical) {
        return canonical.startsWith("-") || canonical.indexOf('/') >= 0 ? "(" + canonical + ")" : canonical;
    }

    private static boolean sameValue(String line, CalculationRecord entry) {
        try {
            Node node = ExpressionParser.parseLine(line).node();
            return node != null && Evaluator.evaluate(node, TableResolver.empty()).equals(entry.getResult());
        } catch (ExpressionException tooLongOrRefused) {
            return false;
        }
    }
}
