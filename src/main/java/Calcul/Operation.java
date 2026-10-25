package Calcul;

public enum Operation {
    ADDITION("Addition (+)", "+", true, "Utilisez des decimaux avec virgule ou point. Pas de notation scientifique."),
    SUBTRACTION("Soustraction (-)", "-", true, "Utilisez des decimaux avec virgule ou point. Pas de notation scientifique."),
    MULTIPLICATION("Multiplication (*)", "*", true, "Utilisez des decimaux avec virgule ou point. Pas de notation scientifique."),
    DIVISION("Division (/)", "/", true, "Les divisions non terminantes sont arrondies a 12 decimales."),
    POWER(
            "Puissance (^)",
            "^",
            true,
            "L'exposant doit etre entier entre -"
                    + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + " et "
                    + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + "."),
    ROOT("Racine carree (\u221A)", "\u221A", false, "Les racines non exactes sont arrondies a 12 decimales.");

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
