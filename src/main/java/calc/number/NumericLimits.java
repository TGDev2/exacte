package calc.number;

/**
 * The single place where the bounds of the numeric core are defined.
 *
 * <p>Every bound is checked before the work it protects, so that any single operation within the limits ends in well
 * under 10 ms. The values come from measurements (see docs/adr/0003-representation-numerique.md and
 * tools/numeric-limits): the slowest operation at these limits, reading a decimal of 4098 characters, takes about
 * 1.7 ms (5.4 ms on its first call in a fresh JVM); the sum of two rationals whose four components have 4096 bits
 * takes about 0.8 ms, against 2.7 ms at 8192 bits and 9.8 ms at 16384 bits.
 */
public final class NumericLimits {
    /**
     * Largest size, in bits, of the numerator (in absolute value) and of the denominator of a bound of a
     * {@link CertifiedValue}, and of a result of {@link Rational#parse(String)} or {@link Rational#pow(int)}. 4096 bits
     * hold any integer of 1233 decimal digits, so every result of the current engine (at most 1000 digits) fits.
     */
    public static final int MAX_COMPONENT_BITS = 4096;

    /**
     * Longest literal accepted by {@link Rational#parse(String)}, in characters, checked before anything else. The
     * longest text the core writes for a value within the limits is the exact decimal of a rational whose denominator
     * is a power of two of 4095 bits, with its sign: 4099 characters. Every text written by the core can therefore be
     * read back.
     */
    public static final int MAX_LITERAL_LENGTH = MAX_COMPONENT_BITS + 4;

    /**
     * Largest absolute value of the exponent of the scientific notation ({@code 1,5e-7}). A value within the limits
     * never needs more than 1234 + {@link #MAX_LITERAL_LENGTH} in absolute value, so this bound refuses no
     * representable literal; it keeps the exponent arithmetic small and is checked before any power of ten is built.
     */
    public static final int MAX_SCIENTIFIC_EXPONENT = 9_999;

    /**
     * Largest absolute value of the exponent of a power. Beyond it, {@code |p|^n} or {@code q^n} exceeds
     * {@link #MAX_COMPONENT_BITS} for every base {@code p/q} except 0, 1 and -1, so the bound refuses nothing else that
     * could be computed. It also bounds the number of squarings of an approximate power.
     */
    public static final int MAX_POWER_EXPONENT = MAX_COMPONENT_BITS;

    /**
     * Size budget of an approximate bound, in significant bits. Every bound of an approximate result is a binary number
     * {@code m * 2^k} with {@code |m| < 2^128}; a bound that needs more is rounded outward (the lower one down, the upper
     * one up) to that grid, whose step is relative to the magnitude of the bound. 128 bits are about 38 decimal digits:
     * each rounding widens a bound by less than {@code 2^-127} of its magnitude.
     */
    public static final int BOUND_SIGNIFICANT_BITS = 128;

    /**
     * Default precision of a non-exact square root: the width of the interval is at most {@code 2^-80} times its lower
     * bound, about 24 significant decimal digits, enough to show 12 guaranteed digits with a wide margin.
     */
    public static final int DEFAULT_PRECISION_BITS = 80;

    /** Largest precision accepted by {@link CertifiedValue#sqrt(int)}; it keeps the root bounds within the budget. */
    public static final int MAX_PRECISION_BITS = BOUND_SIGNIFICANT_BITS - 8;

    private NumericLimits() {
    }
}
