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
    private static final String TOO_LARGE_MESSAGE =
            "Le résultat dépasse la taille maximale d'un calcul exact (réduisez les nombres saisis).";
    private static final String TOO_LARGE_POWER_MESSAGE =
            "Le résultat dépasse la taille maximale d'un calcul exact (réduisez l'exposant ou la base).";
    private static final String UNEXPECTED_ARITHMETIC_MESSAGE =
            "Le calcul n'a pas pu être effectué sur des nombres de cette taille.";

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
            throw new CalculationException(UNEXPECTED_ARITHMETIC_MESSAGE, InputField.NONE);
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
                    ? new CalculationException("0 ne peut pas être élevé à une puissance négative.", InputField.FIRST)
                    : new CalculationException("Le second nombre doit être différent de 0 pour une division.", InputField.SECOND);
            case DIVISOR_MAY_BE_ZERO -> power
                    ? new CalculationException("Le premier nombre est une valeur approchée qui peut valoir 0 : il ne peut pas"
                            + " être élevé à une puissance négative.", InputField.FIRST)
                    : new CalculationException("Le second nombre est une valeur approchée qui peut valoir 0 : la division"
                            + " est impossible.", InputField.SECOND);
            case ROOT_OF_NEGATIVE -> new CalculationException(
                    "La racine carrée d'un nombre négatif n'est pas définie en réel.", InputField.FIRST);
            case ROOT_OF_POSSIBLY_NEGATIVE -> new CalculationException(
                    "Le premier nombre est une valeur approchée qui peut être négative : sa racine carrée n'est pas définie.",
                    InputField.FIRST);
            case EXPONENT_NOT_INTEGER -> new CalculationException("L'exposant doit être un entier.", InputField.SECOND);
            case EXPONENT_NOT_EXACT -> new CalculationException(
                    "L'exposant est une valeur approchée : il doit être un entier exact.", InputField.SECOND);
            case EXPONENT_OUT_OF_RANGE -> new CalculationException(
                    "L'exposant doit être compris entre -" + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT
                            + " et " + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT + ".",
                    InputField.SECOND);
            case SIZE_LIMIT_EXCEEDED -> power
                    ? new CalculationException(TOO_LARGE_POWER_MESSAGE, InputField.SECOND)
                    : new CalculationException(TOO_LARGE_MESSAGE, InputField.NONE);
            // Only parsing raises it, and operands are parsed before computing.
            case MALFORMED_INPUT -> new CalculationException(UNEXPECTED_ARITHMETIC_MESSAGE, InputField.NONE);
        };
    }
}
