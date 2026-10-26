package calc.ui;

import calc.engine.CalculationLimits;
import calc.engine.Operation;

final class OperationText {
    private static final String DECIMAL_INPUT_HINT = "Virgule ou point, fraction 3/4 ou notation 1,5e-7.";

    private OperationText() {
    }

    static String displayName(Operation operation) {
        return switch (operation) {
            case ADDITION -> "Addition (+)";
            case SUBTRACTION -> "Soustraction (-)";
            case MULTIPLICATION -> "Multiplication (*)";
            case DIVISION -> "Division (/)";
            case POWER -> "Puissance (^)";
            case ROOT -> "Racine carrée (√)";
        };
    }

    static String inputHint(Operation operation) {
        return switch (operation) {
            case ADDITION, SUBTRACTION, MULTIPLICATION -> DECIMAL_INPUT_HINT;
            case DIVISION -> "Résultat exact ; « … » : décimales coupées à l'affichage.";
            case POWER -> "L'exposant doit être entier entre -" + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + " et " + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT + ".";
            case ROOT -> "Racine non exacte : « ≈ » et une borne d'erreur garantie.";
        };
    }
}
