package calc.ui;

import calc.engine.CalculationLimits;
import calc.engine.Operation;

final class OperationText {
    private static final String DECIMAL_INPUT_HINT =
            "Virgule ou point. Le point est un séparateur décimal. Pas de notation scientifique.";
    private static final String APPROXIMATION_HINT_SUFFIX =
            " arrondie à " + CalculationLimits.MIN_APPROXIMATION_DECIMALS + " décimales et "
                    + CalculationLimits.MIN_SIGNIFICANT_DIGITS + " chiffres significatifs minimum.";

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
            case DIVISION -> "Division non terminante :" + APPROXIMATION_HINT_SUFFIX;
            case POWER -> "L'exposant doit être entier entre -" + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT
                    + " et " + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT + ".";
            case ROOT -> "Racine non exacte :" + APPROXIMATION_HINT_SUFFIX;
        };
    }
}
