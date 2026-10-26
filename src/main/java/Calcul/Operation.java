package Calcul;

public enum Operation {
    ADDITION("Addition (+)", "+", true, "Virgule ou point. Le point est un séparateur décimal. Pas de notation scientifique."),
    SUBTRACTION("Soustraction (-)", "-", true, "Virgule ou point. Le point est un séparateur décimal. Pas de notation scientifique."),
    MULTIPLICATION("Multiplication (*)", "*", true, "Virgule ou point. Le point est un séparateur décimal. Pas de notation scientifique."),
    DIVISION(
            "Division (/)",
            "/",
            true,
            "Division non terminante : arrondie à 12 décimales et 12 chiffres significatifs minimum."),
    POWER(
            "Puissance (^)",
            "^",
            true,
            "L'exposant doit être entier entre -"
                    + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + " et "
                    + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + "."),
    ROOT(
            "Racine carrée (\u221A)",
            "\u221A",
            false,
            "Racine non exacte : arrondie à 12 décimales et 12 chiffres significatifs minimum.");

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
