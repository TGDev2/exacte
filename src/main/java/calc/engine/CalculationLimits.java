package calc.engine;

import calc.number.NumericLimits;

/**
 * The bounds the engine enforces, as seen by its callers. They are those of the numeric core ({@link NumericLimits},
 * measured in docs/adr/0003-representation-numerique.md): every one is checked before the work it protects, so a huge
 * input or result is refused at once (docs/adr/0004-regles-affichage.md, section 8).
 */
public final class CalculationLimits {
    /** Longest operand, in characters after trimming, checked before parsing: every canonical text fits. */
    public static final int MAX_INPUT_LENGTH = NumericLimits.MAX_LITERAL_LENGTH;

    /** Largest absolute value of the exponent of a power. */
    public static final int MAX_ABSOLUTE_INTEGER_EXPONENT = NumericLimits.MAX_POWER_EXPONENT;

    /** Largest absolute value of the exponent of the scientific notation of an operand ({@code 1,5e-7}). */
    public static final int MAX_SCIENTIFIC_EXPONENT = NumericLimits.MAX_SCIENTIFIC_EXPONENT;

    private CalculationLimits() {
    }
}
