package calc.engine;

/**
 * The single place where the bounds of the calculator are defined.
 *
 * <p>Every bound exists so that a calculation always ends quickly on the Swing thread. They were chosen from
 * measurements (see docs/adr/0001-durcissement-historique-et-bornes.md): at these values the slowest accepted or
 * refused calculation (square root of a 1000-digit number) takes about 8 ms cold and 6 ms warm, against a budget
 * of 50 ms.
 */
public final class CalculationLimits {
    /**
     * Longest operand the user may type, in characters, after trimming. Longer inputs are refused before parsing. The
     * canonical text of the operand (the one shown and stored, with a "0" before a bare decimal point) must fit too.
     */
    public static final int MAX_INPUT_LENGTH = 1005;

    /**
     * Largest result of any operation, in digits (the larger of the precision and the scale). For a power it is
     * estimated before computing as {@code max(precision, scale) of the base * |exponent|}.
     */
    public static final int MAX_RESULT_DIGITS = 1000;

    /** Largest absolute value of the exponent of a power. */
    public static final int MAX_ABSOLUTE_INTEGER_EXPONENT = 999;

    public static final int MIN_APPROXIMATION_DECIMALS = 12;
    public static final int MIN_SIGNIFICANT_DIGITS = 12;

    private CalculationLimits() {
    }
}
