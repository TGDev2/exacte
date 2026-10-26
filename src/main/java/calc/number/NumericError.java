package calc.number;

/**
 * Why a numeric operation was refused. The numeric core never produces text for the user: the engine translates these
 * codes into messages.
 */
public enum NumericError {
    /** The divisor is exactly zero, including a zero base raised to a negative power and a {@code p/0} literal. */
    DIVISION_BY_ZERO,
    /**
     * The divisor is an approximate value whose interval contains zero, including such a base raised to a negative
     * power: the true divisor may be zero.
     */
    DIVISOR_MAY_BE_ZERO,
    /** Square root of a value that is certainly negative (its upper bound is below zero). */
    ROOT_OF_NEGATIVE,
    /** Square root of an approximate value whose interval contains negative numbers and zero or positive numbers. */
    ROOT_OF_POSSIBLY_NEGATIVE,
    /** The exponent of a power is exact but is not an integer. */
    EXPONENT_NOT_INTEGER,
    /** The exponent of a power is approximate, so it cannot be known to be an integer. */
    EXPONENT_NOT_EXACT,
    /** A power exponent or a scientific-notation exponent is outside its bound ({@link NumericLimits}). */
    EXPONENT_OUT_OF_RANGE,
    /** A literal is too long or a result needs more bits than {@link NumericLimits#MAX_COMPONENT_BITS}. */
    SIZE_LIMIT_EXCEEDED,
    /** A text is not a number of the accepted grammar (see {@link Rational#parse(String)}). */
    MALFORMED_INPUT
}
