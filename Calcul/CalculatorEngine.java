package Calcul;

public class CalculatorEngine {
    public CalculationRecord calculate(Operation operation, String firstInput, String secondInput) throws CalculationException {
        if (operation == null) {
            throw new CalculationException("Choisissez une operation.");
        }

        double firstValue = parseNumber(
                firstInput,
                "Saisissez le premier nombre.",
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.");

        double secondValue = 0;
        if (operation.requiresSecondOperand()) {
            secondValue = parseNumber(
                    secondInput,
                    "Saisissez le second nombre.",
                    "Le second nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.");
        }

        double resultValue = apply(operation, firstValue, secondValue);
        String firstDisplay = NumberFormatter.format(firstValue);
        String secondDisplay = operation.requiresSecondOperand() ? NumberFormatter.format(secondValue) : "";
        return new CalculationRecord(operation, firstDisplay, secondDisplay, resultValue);
    }

    private double apply(Operation operation, double firstValue, double secondValue) throws CalculationException {
        switch (operation) {
            case ADDITION:
                return firstValue + secondValue;
            case SUBTRACTION:
                return firstValue - secondValue;
            case MULTIPLICATION:
                return firstValue * secondValue;
            case DIVISION:
                if (secondValue == 0) {
                    throw new CalculationException("Le second nombre doit etre different de 0 pour une division.");
                }
                return firstValue / secondValue;
            case POWER:
                return Math.pow(firstValue, secondValue);
            case ROOT:
                if (firstValue < 0) {
                    throw new CalculationException("La racine carree d'un nombre negatif n'est pas definie en reel.");
                }
                return Math.sqrt(firstValue);
            default:
                throw new CalculationException("Operation non prise en charge.");
        }
    }

    private double parseNumber(String input, String missingMessage, String invalidMessage) throws CalculationException {
        if (input == null || input.trim().isEmpty()) {
            throw new CalculationException(missingMessage);
        }

        String normalizedInput = input.trim().replace(',', '.');
        try {
            double parsedValue = Double.parseDouble(normalizedInput);
            if (Double.isNaN(parsedValue) || Double.isInfinite(parsedValue)) {
                throw new NumberFormatException("unsupported value");
            }
            return parsedValue;
        } catch (NumberFormatException exception) {
            throw new CalculationException(invalidMessage);
        }
    }
}
