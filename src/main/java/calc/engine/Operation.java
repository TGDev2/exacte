package calc.engine;

public enum Operation {
    ADDITION("+", true),
    SUBTRACTION("-", true),
    MULTIPLICATION("*", true),
    DIVISION("/", true),
    POWER("^", true),
    ROOT("√", false);

    private final String historySymbol;
    private final boolean requiresSecondOperand;

    Operation(String historySymbol, boolean requiresSecondOperand) {
        this.historySymbol = historySymbol;
        this.requiresSecondOperand = requiresSecondOperand;
    }

    public String getHistorySymbol() {
        return historySymbol;
    }

    public boolean requiresSecondOperand() {
        return requiresSecondOperand;
    }
}
