package Calcul;

import java.math.BigDecimal;
import java.util.Objects;

public class CalculationRecord {
    private final Operation operation;
    private final String firstValue;
    private final String secondValue;
    private final BigDecimal resultValue;
    private final String formattedResult;
    private final String historyEntry;

    public CalculationRecord(Operation operation, String firstValue, String secondValue, BigDecimal resultValue) {
        this.operation = Objects.requireNonNull(operation);
        this.firstValue = Objects.requireNonNull(firstValue);
        this.secondValue = Objects.requireNonNull(secondValue);
        this.resultValue = DecimalPolicy.normalize(Objects.requireNonNull(resultValue));
        this.formattedResult = NumberFormatter.format(this.resultValue);
        this.historyEntry = buildHistoryEntry();
    }

    public String getFormattedResult() {
        return formattedResult;
    }

    public Operation getOperation() {
        return operation;
    }

    public String getFirstValue() {
        return firstValue;
    }

    public String getSecondValue() {
        return secondValue;
    }

    public String getReusableValue() {
        return formattedResult;
    }

    public BigDecimal getResultValue() {
        return resultValue;
    }

    @Override
    public String toString() {
        return historyEntry;
    }

    private String buildHistoryEntry() {
        if (operation.requiresSecondOperand()) {
            return firstValue + " " + operation.getHistorySymbol() + " " + secondValue + " = " + formattedResult;
        }
        return operation.getHistorySymbol() + "(" + firstValue + ") = " + formattedResult;
    }
}
