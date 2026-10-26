package calc.engine;

import java.math.BigDecimal;

public class CalculatorEngine {
    private static final BigDecimal MAX_ABSOLUTE_EXPONENT = BigDecimal.valueOf(DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT);
    private static final String TOO_MANY_DIGITS_MESSAGE =
            "Le résultat dépasse " + DecimalPolicy.MAX_DISPLAY_DIGITS + " chiffres (réduisez les nombres saisis).";
    private static final String TOO_MANY_DIGITS_POWER_MESSAGE =
            "Le résultat dépasse " + DecimalPolicy.MAX_DISPLAY_DIGITS + " chiffres (réduisez l'exposant ou la base).";
    private static final String UNEXPECTED_ARITHMETIC_MESSAGE =
            "Le calcul n'a pas pu être effectué sur des nombres de cette taille.";

    public CalculationRecord calculate(Operation operation, String firstInput, String secondInput) throws CalculationException {
        if (operation == null) {
            throw new CalculationException("Choisissez une opération.", InputField.NONE);
        }

        BigDecimal firstValue = parseNumber(
                firstInput,
                "Saisissez le premier nombre.",
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                "Le premier nombre est trop long (" + DecimalPolicy.MAX_INPUT_LENGTH + " caractères maximum).",
                InputField.FIRST);

        BigDecimal secondValue = BigDecimal.ZERO;
        if (operation.requiresSecondOperand()) {
            secondValue = parseNumber(
                    secondInput,
                    "Saisissez le second nombre.",
                    "Le second nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                    "Le second nombre est trop long (" + DecimalPolicy.MAX_INPUT_LENGTH + " caractères maximum).",
                    InputField.SECOND);
        }

        BigDecimal resultValue = apply(operation, firstValue, secondValue);
        String firstDisplay = DecimalPolicy.format(firstValue);
        String secondDisplay = operation.requiresSecondOperand() ? DecimalPolicy.format(secondValue) : "";
        return new CalculationRecord(operation, firstDisplay, secondDisplay, resultValue);
    }

    private BigDecimal apply(Operation operation, BigDecimal firstValue, BigDecimal secondValue) throws CalculationException {
        BigDecimal result;
        try {
            result = compute(operation, firstValue, secondValue);
        } catch (ArithmeticException exception) {
            throw new CalculationException(UNEXPECTED_ARITHMETIC_MESSAGE, InputField.NONE);
        }
        if (DecimalPolicy.exceedsDisplayDigits(result)) {
            throw new CalculationException(TOO_MANY_DIGITS_MESSAGE, InputField.NONE);
        }
        return result;
    }

    private BigDecimal compute(Operation operation, BigDecimal firstValue, BigDecimal secondValue) throws CalculationException {
        switch (operation) {
            case ADDITION:
                return DecimalPolicy.normalize(firstValue.add(secondValue));
            case SUBTRACTION:
                return DecimalPolicy.normalize(firstValue.subtract(secondValue));
            case MULTIPLICATION:
                return DecimalPolicy.normalize(firstValue.multiply(secondValue));
            case DIVISION:
                if (secondValue.compareTo(BigDecimal.ZERO) == 0) {
                    throw new CalculationException("Le second nombre doit être différent de 0 pour une division.", InputField.SECOND);
                }
                return DecimalPolicy.divide(firstValue, secondValue);
            case POWER:
                return applyPower(firstValue, secondValue);
            case ROOT:
                if (firstValue.signum() < 0) {
                    throw new CalculationException("La racine carrée d'un nombre négatif n'est pas définie en réel.", InputField.FIRST);
                }
                return DecimalPolicy.sqrt(firstValue);
            default:
                throw new CalculationException("Opération non prise en charge.", InputField.NONE);
        }
    }

    private BigDecimal applyPower(BigDecimal firstValue, BigDecimal secondValue) throws CalculationException {
        if (!DecimalPolicy.isIntegerValue(secondValue)) {
            throw new CalculationException("L'exposant doit être un entier.", InputField.SECOND);
        }
        if (secondValue.abs().compareTo(MAX_ABSOLUTE_EXPONENT) > 0) {
            throw new CalculationException(
                    "L'exposant doit être compris entre -"
                            + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                            + " et "
                            + DecimalPolicy.MAX_ABSOLUTE_INTEGER_EXPONENT
                            + ".",
                    InputField.SECOND);
        }

        int exponent = secondValue.intValueExact();
        BigDecimal base = DecimalPolicy.normalize(firstValue);
        if (base.signum() == 0 && exponent < 0) {
            throw new CalculationException("0 ne peut pas être élevé à une puissance négative.", InputField.FIRST);
        }
        long estimatedDigits = (long) Math.max(base.precision(), base.scale()) * Math.abs(exponent);
        if (estimatedDigits > DecimalPolicy.MAX_DISPLAY_DIGITS) {
            throw new CalculationException(TOO_MANY_DIGITS_POWER_MESSAGE, InputField.SECOND);
        }
        return DecimalPolicy.pow(base, exponent);
    }

    private BigDecimal parseNumber(String input, String missingMessage, String invalidMessage, String tooLongMessage, InputField field)
            throws CalculationException {
        if (input == null || input.trim().isEmpty()) {
            throw new CalculationException(missingMessage, field);
        }

        String trimmedInput = input.trim();
        if (trimmedInput.length() > DecimalPolicy.MAX_INPUT_LENGTH) {
            throw new CalculationException(tooLongMessage, field);
        }

        String compactInput = DecimalPolicy.removeThousandsSeparators(DecimalPolicy.stripEdgeSpaces(trimmedInput));
        if (compactInput.isEmpty()) {
            throw new CalculationException(missingMessage, field);
        }

        String normalizedInput = compactInput.replace(',', '.');
        if (!DecimalPolicy.isSupportedInput(normalizedInput)) {
            throw new CalculationException(invalidMessage, field);
        }

        try {
            return DecimalPolicy.parseNormalizedInput(normalizedInput);
        } catch (NumberFormatException exception) {
            throw new CalculationException(invalidMessage, field);
        }
    }
}
