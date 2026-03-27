package Calcul;

public enum Operation {
    ADDITION("Addition (+)", "+", true, "Cette operation utilise les deux nombres."),
    SUBTRACTION("Soustraction (-)", "-", true, "Cette operation utilise les deux nombres."),
    MULTIPLICATION("Multiplication (*)", "*", true, "Cette operation utilise les deux nombres."),
    DIVISION("Division (/)", "/", true, "Cette operation utilise les deux nombres."),
    POWER("Puissance (^)", "^", true, "Cette operation utilise les deux nombres."),
    ROOT("Racine carree (\u221A)", "\u221A", false, "La racine carree utilise uniquement le premier nombre.");

    private final String displayName;
    private final String historySymbol;
    private final boolean requiresSecondOperand;
    private final String inputHint;

    Operation(String displayName, String historySymbol, boolean requiresSecondOperand, String inputHint) {
        this.displayName = displayName;
        this.historySymbol = historySymbol;
        this.requiresSecondOperand = requiresSecondOperand;
        this.inputHint = inputHint;
    }

    public String getHistorySymbol() {
        return historySymbol;
    }

    public boolean requiresSecondOperand() {
        return requiresSecondOperand;
    }

    public String getInputHint() {
        return inputHint;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
