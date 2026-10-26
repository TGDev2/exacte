package calc.engine;

import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;

/**
 * Computes one operation with the exact numeric core ({@code calc.number}): every result is exact, or approximate with a
 * certified interval (a root that is not exact). Each refusal of the core is translated into a French message and the
 * {@link InputField} it concerns (docs/adr/0004-regles-affichage.md).
 */
public class CalculatorEngine {
    /** Both operands are typed texts. */
    public CalculationRecord calculate(Operation operation, String firstInput, String secondInput) throws CalculationException {
        return calculate(operation, Operand.typed(firstInput), Operand.typed(secondInput));
    }

    /**
     * The operands are typed texts or values already known. The first operand is checked before the second; the second
     * is not read for a unary operation.
     */
    public CalculationRecord calculate(Operation operation, Operand first, Operand second) throws CalculationException {
        if (operation == null) {
            throw new CalculationException("Choisissez une opération.", InputField.NONE);
        }
        CertifiedValue firstValue = first.resolve(InputField.FIRST);
        CertifiedValue secondValue = operation.requiresSecondOperand() ? second.resolve(InputField.SECOND) : null;
        try {
            return new CalculationRecord(operation, firstValue, secondValue, compute(operation, firstValue, secondValue));
        } catch (NumericException exception) {
            throw translate(operation, exception.error());
        } catch (ArithmeticException exception) {
            throw new CalculationException(NumericMessages.UNEXPECTED_ARITHMETIC, InputField.NONE);
        }
    }

    private static CertifiedValue compute(Operation operation, CertifiedValue first, CertifiedValue second)
            throws NumericException {
        return switch (operation) {
            case ADDITION -> first.add(second);
            case SUBTRACTION -> first.subtract(second);
            case MULTIPLICATION -> first.multiply(second);
            case DIVISION -> first.divide(second);
            case POWER -> first.pow(second);
            case ROOT -> first.sqrt();
        };
    }

    private static CalculationException translate(Operation operation, NumericError error) {
        boolean power = operation == Operation.POWER;
        return switch (error) {
            case DIVISION_BY_ZERO -> power
                    ? new CalculationException(NumericMessages.ZERO_TO_NEGATIVE_POWER, InputField.FIRST)
                    : new CalculationException("Le second nombre doit être différent de 0 pour une division.", InputField.SECOND);
            case DIVISOR_MAY_BE_ZERO -> power
                    ? new CalculationException("Le premier nombre est une valeur approchée qui peut valoir 0 : il ne peut pas"
                            + " être élevé à une puissance négative.", InputField.FIRST)
                    : new CalculationException("Le second nombre est une valeur approchée qui peut valoir 0 : la division"
                            + " est impossible.", InputField.SECOND);
            case ROOT_OF_NEGATIVE -> new CalculationException(NumericMessages.ROOT_OF_NEGATIVE, InputField.FIRST);
            case ROOT_OF_POSSIBLY_NEGATIVE -> new CalculationException(
                    "Le premier nombre est une valeur approchée qui peut être négative : sa racine carrée n'est pas définie.",
                    InputField.FIRST);
            case EXPONENT_NOT_INTEGER -> new CalculationException(NumericMessages.EXPONENT_NOT_INTEGER, InputField.SECOND);
            case EXPONENT_NOT_EXACT -> new CalculationException(NumericMessages.EXPONENT_NOT_EXACT, InputField.SECOND);
            case EXPONENT_OUT_OF_RANGE -> new CalculationException(NumericMessages.EXPONENT_OUT_OF_RANGE, InputField.SECOND);
            case SIZE_LIMIT_EXCEEDED -> power
                    ? new CalculationException(NumericMessages.POWER_TOO_LARGE, InputField.SECOND)
                    : new CalculationException(NumericMessages.RESULT_TOO_LARGE, InputField.NONE);
            // Only parsing raises it, and operands are parsed before computing.
            case MALFORMED_INPUT -> new CalculationException(NumericMessages.UNEXPECTED_ARITHMETIC, InputField.NONE);
        };
    }
}
