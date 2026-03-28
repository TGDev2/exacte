package Calcul;

public class CalculationRecord {
    private final Operation operation;
    private final String firstValue;
    private final String secondValue;
    private final double resultValue;
    private final String formattedResult;
    private final String historyEntry;

    public CalculationRecord(Operation operation, String firstValue, String secondValue, double resultValue) {
        this.operation = operation;
        this.firstValue = firstValue;
        this.secondValue = secondValue;
        this.resultValue = resultValue;
        this.formattedResult = NumberFormatter.format(resultValue);
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

    public double getResultValue() {
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
