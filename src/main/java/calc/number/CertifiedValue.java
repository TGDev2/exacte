package calc.number;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A number known exactly or within a certified interval: the closed interval {@code [lower, upper]} of rationals, with
 * {@code lower <= upper}. The value is <em>exact</em> when {@code lower == upper}; otherwise it is <em>approximate</em>
 * and the true mathematical value is guaranteed to lie in the interval.
 *
 * <p>Invariants:
 * <ul>
 *   <li><b>Inclusion.</b> Every operation contains the true result: if {@code x} is in {@code X} and {@code y} in
 *       {@code Y}, then {@code x op y} is in {@code X op Y}.</li>
 *   <li><b>Exactness.</b> An operation on exact values gives an exact value, except a square root that is not exact.
 *       Every exact result is the exact rational; nothing exact is ever rounded.</li>
 *   <li><b>Bounded size.</b> Every bound has at most {@link NumericLimits#MAX_COMPONENT_BITS} bits per component;
 *       an operation whose result would need more throws {@link NumericError#SIZE_LIMIT_EXCEEDED}. Each bound of an
 *       approximate result is a binary number with at most {@link NumericLimits#BOUND_SIGNIFICANT_BITS} significant
 *       bits: bounds that would need more are rounded outward, the lower one down and the upper one up, to a grid
 *       whose step is relative to their magnitude.</li>
 * </ul>
 *
 * <p>Domain errors are {@link NumericException}s carrying a {@link NumericError} code. A bound order mistake in
 * {@link #between(Rational, Rational)} is a programming error ({@link IllegalArgumentException}).
 */
public final class CertifiedValue {
    public static final CertifiedValue ZERO = new CertifiedValue(Rational.ZERO, Rational.ZERO);
    public static final CertifiedValue ONE = new CertifiedValue(Rational.ONE, Rational.ONE);

    private static final Rational HALF = Rational.dyadic(BigInteger.ONE, -1);

    private final Rational lower;
    private final Rational upper;

    private CertifiedValue(Rational lower, Rational upper) {
        this.lower = lower;
        this.upper = upper;
    }

    /** The exact value. {@link NumericError#SIZE_LIMIT_EXCEEDED} if a component exceeds the limit. */
    public static CertifiedValue exact(Rational value) throws NumericException {
        Rational.withinLimits(Objects.requireNonNull(value, "value"));
        return new CertifiedValue(value, value);
    }

    /**
     * The value known to lie in {@code [lower, upper]}, kept as given; exact when both bounds are equal.
     * {@link NumericError#SIZE_LIMIT_EXCEEDED} if a component exceeds the limit.
     *
     * @throws IllegalArgumentException if {@code lower > upper}
     */
    public static CertifiedValue between(Rational lower, Rational upper) throws NumericException {
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
        if (lower.compareTo(upper) > 0) {
            throw new IllegalArgumentException("lower bound above upper bound: " + lower + " > " + upper);
        }
        Rational.withinLimits(lower);
        Rational.withinLimits(upper);
        return new CertifiedValue(lower, upper);
    }

    // An approximate result from exact candidate bounds: the lower one rounded down, the upper one rounded up.
    private static CertifiedValue rounded(Rational lower, Rational upper) throws NumericException {
        return new CertifiedValue(OutwardRounding.down(lower), OutwardRounding.up(upper));
    }

    public boolean isExact() {
        return lower.equals(upper);
    }

    public Rational lower() {
        return lower;
    }

    public Rational upper() {
        return upper;
    }

    /** {@code (lower + upper) / 2}, the value itself when exact. It is not checked against the component limit. */
    public Rational midpoint() {
        return lower.add(upper).multiply(HALF);
    }

    /**
     * {@code (upper - lower) / 2}: the guaranteed absolute error bound around {@link #midpoint()}, zero when exact. It is
     * not checked against the component limit.
     */
    public Rational radius() {
        return upper.subtract(lower).multiply(HALF);
    }

    public boolean contains(Rational value) {
        return lower.compareTo(value) <= 0 && value.compareTo(upper) <= 0;
    }

    public CertifiedValue negate() {
        return new CertifiedValue(upper.negate(), lower.negate());
    }

    public CertifiedValue add(CertifiedValue other) throws NumericException {
        if (isExact() && other.isExact()) {
            return exact(lower.add(other.lower));
        }
        return rounded(lower.add(other.lower), upper.add(other.upper));
    }

    public CertifiedValue subtract(CertifiedValue other) throws NumericException {
        if (isExact() && other.isExact()) {
            return exact(lower.subtract(other.lower));
        }
        return rounded(lower.subtract(other.upper), upper.subtract(other.lower));
    }

    /** The smallest and the largest of the four products of the bounds. */
    public CertifiedValue multiply(CertifiedValue other) throws NumericException {
        if (isExact() && other.isExact()) {
            return exact(lower.multiply(other.lower));
        }
        Rational lowerLower = lower.multiply(other.lower);
        Rational lowerUpper = lower.multiply(other.upper);
        Rational upperLower = upper.multiply(other.lower);
        Rational upperUpper = upper.multiply(other.upper);
        return rounded(min(min(lowerLower, lowerUpper), min(upperLower, upperUpper)),
                max(max(lowerLower, lowerUpper), max(upperLower, upperUpper)));
    }

    /**
     * Errors: {@link NumericError#DIVISION_BY_ZERO} if the divisor is exactly zero,
     * {@link NumericError#DIVISOR_MAY_BE_ZERO} if it is approximate and its interval contains zero.
     */
    public CertifiedValue divide(CertifiedValue divisor) throws NumericException {
        if (divisor.isExact() && divisor.lower.signum() == 0) {
            throw new NumericException(NumericError.DIVISION_BY_ZERO);
        }
        if (divisor.containsZero()) {
            throw new NumericException(NumericError.DIVISOR_MAY_BE_ZERO);
        }
        if (isExact() && divisor.isExact()) {
            return exact(lower.divide(divisor.lower));
        }
        return multiply(divisor.reciprocal());
    }

    /**
     * Power by an exponent that must be an exact integer. Errors, in this order: {@link NumericError#EXPONENT_NOT_EXACT},
     * {@link NumericError#EXPONENT_NOT_INTEGER}, {@link NumericError#EXPONENT_OUT_OF_RANGE}, then those of
     * {@link #pow(int)}.
     */
    public CertifiedValue pow(CertifiedValue exponent) throws NumericException {
        if (!exponent.isExact()) {
            throw new NumericException(NumericError.EXPONENT_NOT_EXACT);
        }
        Rational value = exponent.lower;
        if (!value.isInteger()) {
            throw new NumericException(NumericError.EXPONENT_NOT_INTEGER);
        }
        if (value.abs().compareTo(Rational.of(NumericLimits.MAX_POWER_EXPONENT)) > 0) {
            throw new NumericException(NumericError.EXPONENT_OUT_OF_RANGE);
        }
        return pow(value.numerator().intValueExact());
    }

    /**
     * Integer power; {@code x^0 = 1}. An exact base gives the exact power ({@link Rational#pow(int)}). For an
     * approximate base, an odd power is monotone; an even power of an interval that contains zero is
     * {@code [0, max(lower^n, upper^n)]}; a negative power of an interval that contains zero is
     * {@link NumericError#DIVISOR_MAY_BE_ZERO} (an exact zero base is {@link NumericError#DIVISION_BY_ZERO}).
     * {@link NumericError#EXPONENT_OUT_OF_RANGE} beyond {@link NumericLimits#MAX_POWER_EXPONENT}.
     */
    public CertifiedValue pow(int exponent) throws NumericException {
        if (exponent > NumericLimits.MAX_POWER_EXPONENT || exponent < -NumericLimits.MAX_POWER_EXPONENT) {
            throw new NumericException(NumericError.EXPONENT_OUT_OF_RANGE);
        }
        if (isExact()) {
            return exact(lower.pow(exponent));
        }
        if (exponent == 0) {
            return ONE;
        }
        if (exponent < 0) {
            if (containsZero()) {
                throw new NumericException(NumericError.DIVISOR_MAY_BE_ZERO);
            }
            return reciprocal().pow(-exponent);
        }
        if (exponent % 2 != 0 || lower.signum() >= 0) {
            return new CertifiedValue(powDown(lower, exponent), powUp(upper, exponent));
        }
        if (upper.signum() <= 0) {
            return new CertifiedValue(powDown(upper, exponent), powUp(lower, exponent));
        }
        return new CertifiedValue(Rational.ZERO, max(powUp(lower, exponent), powUp(upper, exponent)));
    }

    /** {@link #sqrt(int)} at {@link NumericLimits#DEFAULT_PRECISION_BITS}. */
    public CertifiedValue sqrt() throws NumericException {
        return sqrt(NumericLimits.DEFAULT_PRECISION_BITS);
    }

    /**
     * Square root. Exact when the value is exact and its reduced numerator and denominator are perfect squares
     * ({@code sqrt(9/4) = 3/2}). Otherwise each bound of the result is certified ({@code lower^2 <= x <= upper^2} for an
     * exact {@code x}), and for an exact value the width is at most {@code 2^-precisionBits} times the lower bound.
     * Errors: {@link NumericError#ROOT_OF_NEGATIVE} if {@code upper < 0}, {@link NumericError#ROOT_OF_POSSIBLY_NEGATIVE}
     * if {@code lower < 0 <= upper}.
     *
     * @throws IllegalArgumentException if {@code precisionBits} is not between 1 and
     *         {@link NumericLimits#MAX_PRECISION_BITS}
     */
    public CertifiedValue sqrt(int precisionBits) throws NumericException {
        if (precisionBits < 1 || precisionBits > NumericLimits.MAX_PRECISION_BITS) {
            throw new IllegalArgumentException("precision out of range: " + precisionBits);
        }
        if (upper.signum() < 0) {
            throw new NumericException(NumericError.ROOT_OF_NEGATIVE);
        }
        if (lower.signum() < 0) {
            throw new NumericException(NumericError.ROOT_OF_POSSIBLY_NEGATIVE);
        }
        Rational lowerRoot = OutwardRounding.sqrtBound(lower, precisionBits, false);
        Rational upperRoot = OutwardRounding.sqrtBound(upper, precisionBits, true);
        if (lowerRoot.equals(upperRoot)) {
            return new CertifiedValue(lowerRoot, upperRoot);
        }
        // A bound that is a perfect square has an exact root such as 1/3, which may be beyond the budget.
        return rounded(lowerRoot, upperRoot);
    }

    private boolean containsZero() {
        return lower.signum() <= 0 && upper.signum() >= 0;
    }

    // [1 / upper, 1 / lower] for an interval that does not contain zero; the bounds keep their size.
    private CertifiedValue reciprocal() throws NumericException {
        return new CertifiedValue(upper.reciprocal(), lower.reciprocal());
    }

    // A lower bound of value^exponent, for any sign of value and exponent >= 1.
    private static Rational powDown(Rational value, int exponent) throws NumericException {
        if (value.signum() >= 0 || exponent % 2 == 0) {
            return OutwardRounding.powBound(value.abs(), exponent, false);
        }
        return OutwardRounding.powBound(value.abs(), exponent, true).negate();
    }

    // An upper bound of value^exponent, for any sign of value and exponent >= 1.
    private static Rational powUp(Rational value, int exponent) throws NumericException {
        if (value.signum() >= 0 || exponent % 2 == 0) {
            return OutwardRounding.powBound(value.abs(), exponent, true);
        }
        return OutwardRounding.powBound(value.abs(), exponent, false).negate();
    }

    private static Rational min(Rational first, Rational second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Rational max(Rational first, Rational second) {
        return first.compareTo(second) >= 0 ? first : second;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CertifiedValue value && lower.equals(value.lower) && upper.equals(value.upper);
    }

    @Override
    public int hashCode() {
        return 31 * lower.hashCode() + upper.hashCode();
    }

    /** The value itself when exact ({@code 3/2}), {@code [lower, upper]} otherwise. */
    @Override
    public String toString() {
        return isExact() ? lower.toString() : "[" + lower + ", " + upper + "]";
    }
}
