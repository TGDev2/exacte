package calc.engine;

/**
 * The French messages for refusals of the numeric core that do not depend on where the operands come from. Shared by
 * {@link CalculatorEngine} (two operand fields) and by the expression language ({@code calc.expression}); the messages
 * that name a field ("Le second nombre ...") stay in the engine.
 */
public final class NumericMessages {
    /** {@code SIZE_LIMIT_EXCEEDED} for any operation but a power. */
    public static final String RESULT_TOO_LARGE =
            "Le résultat dépasse la taille maximale d'un calcul exact (réduisez les nombres saisis).";
    /** {@code SIZE_LIMIT_EXCEEDED} for a power. */
    public static final String POWER_TOO_LARGE =
            "Le résultat dépasse la taille maximale d'un calcul exact (réduisez l'exposant ou la base).";
    /** An {@link ArithmeticException} or a code that an operation should never raise: a safety net. */
    public static final String UNEXPECTED_ARITHMETIC = "Le calcul n'a pas pu être effectué sur des nombres de cette taille.";
    /** {@code DIVISION_BY_ZERO} for a power: an exact 0 raised to a negative exponent. */
    public static final String ZERO_TO_NEGATIVE_POWER = "0 ne peut pas être élevé à une puissance négative.";
    /** {@code ROOT_OF_NEGATIVE}. */
    public static final String ROOT_OF_NEGATIVE = "La racine carrée d'un nombre négatif n'est pas définie en réel.";
    /** {@code EXPONENT_NOT_INTEGER}. */
    public static final String EXPONENT_NOT_INTEGER = "L'exposant doit être un entier.";
    /** {@code EXPONENT_NOT_EXACT}. */
    public static final String EXPONENT_NOT_EXACT = "L'exposant est une valeur approchée : il doit être un entier exact.";
    /** {@code EXPONENT_OUT_OF_RANGE} for a power. */
    public static final String EXPONENT_OUT_OF_RANGE = "L'exposant doit être compris entre -"
            + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT + " et " + CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT + ".";

    private NumericMessages() {
    }
}
