package calc.engine;

import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.number.CertifiedValue;
import java.util.Objects;

/**
 * One calculation: the operation, the canonical texts of its operands and the result as a value of the numeric core.
 * The result is shown in Auto mode; its reusable value is the canonical text, never the display.
 */
public class CalculationRecord {
    private static final String APPROXIMATE_PREFIX = "≈ ";

    private final Operation operation;
    private final String firstValue;
    private final String secondValue;
    private final CertifiedValue result;
    private final FormattedValue formatted;
    private final boolean approximateOperand;
    private final String historyEntry;

    /** {@code secondOperand} is ignored (and may be null) for a unary operation. */
    public CalculationRecord(Operation operation, CertifiedValue firstOperand, CertifiedValue secondOperand,
                             CertifiedValue result) {
        this.operation = Objects.requireNonNull(operation);
        this.firstValue = ValueFormatter.canonical(Objects.requireNonNull(firstOperand));
        this.secondValue = operation.requiresSecondOperand() ? ValueFormatter.canonical(Objects.requireNonNull(secondOperand)) : "";
        this.result = Objects.requireNonNull(result);
        this.approximateOperand = !firstOperand.isExact() || operation.requiresSecondOperand() && !secondOperand.isExact();
        this.formatted = ValueFormatter.format(result, DisplayMode.AUTO);
        this.historyEntry = buildHistoryEntry();
    }

    /** The result as shown (Auto mode). */
    public String getFormattedResult() {
        return formatted.text();
    }

    /** The result as shown in Auto mode, with its exactness, fraction and error bound. */
    public FormattedValue getFormatted() {
        return formatted;
    }

    /** The canonical text of the result: read back by the input, it gives back an exact result exactly. */
    public String getReusableValue() {
        return formatted.canonical();
    }

    public CertifiedValue getResult() {
        return result;
    }

    public boolean isExact() {
        return result.isExact();
    }

    /**
     * Whether an operand was an approximate value (a remembered root): its canonical text is only a point of its
     * interval, so the result recalculated from the operand texts differs from {@link #getResult()}.
     */
    public boolean hasApproximateOperand() {
        return approximateOperand;
    }

    public Operation getOperation() {
        return operation;
    }

    /** Canonical text of the first operand. */
    public String getFirstValue() {
        return firstValue;
    }

    /** Canonical text of the second operand, empty for a unary operation. */
    public String getSecondValue() {
        return secondValue;
    }

    @Override
    public String toString() {
        return historyEntry;
    }

    private String buildHistoryEntry() {
        String text = formatted.text();
        // "√(2) ≈ 1,41421356237…" rather than "√(2) = ≈ 1,41421356237…".
        String equality = text.startsWith(APPROXIMATE_PREFIX) ? " ≈ " + text.substring(APPROXIMATE_PREFIX.length()) : " = " + text;
        if (operation.requiresSecondOperand()) {
            return firstValue + " " + operation.getHistorySymbol() + " " + secondValue + equality;
        }
        return operation.getHistorySymbol() + "(" + firstValue + ")" + equality;
    }
}
