package calc.number;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An exact rational number {@code p/q} of {@link BigInteger}, always in canonical form: {@code q > 0},
 * {@code gcd(|p|, q) = 1} and zero is {@code 0/1}. Two equal values therefore have the same numerator and denominator,
 * so {@link #equals(Object)}, {@link #hashCode()} and {@link #compareTo(Rational)} agree.
 *
 * <p>Addition, subtraction, multiplication and division are exact and not bounded: their cost and the size of their
 * result are at most about twice those of their operands, and {@link CertifiedValue} checks every result it keeps
 * against {@link NumericLimits#MAX_COMPONENT_BITS}. The two operations whose cost does not depend on the size of their
 * operands only, {@link #pow(int)} and {@link #parse(String)}, check the limits before computing.
 */
public final class Rational implements Comparable<Rational> {
    public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    public static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private static final BigInteger FIVE = BigInteger.valueOf(5);
    // A value slightly below log2(10): turns a count of decimal digits into a number of bits that is never too high.
    private static final double LOG2_TEN_LOWER = 3.3219;
    private static final double LOG2_FIVE = 2.321928094887362;
    private static final int MAX_EXPONENT_DIGITS = 9;
    private static final Pattern DECIMAL = Pattern.compile("([+-]?)([0-9]*)(?:[.,]([0-9]*))?(?:[eE]([+-]?)([0-9]+))?");

    private final BigInteger numerator;
    private final BigInteger denominator;

    // Callers guarantee the canonical form.
    private Rational(BigInteger numerator, BigInteger denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
    }

    public static Rational of(long value) {
        return of(BigInteger.valueOf(value));
    }

    public static Rational of(BigInteger value) {
        Objects.requireNonNull(value, "value");
        return value.signum() == 0 ? ZERO : new Rational(value, BigInteger.ONE);
    }

    public static Rational of(long numerator, long denominator) throws NumericException {
        return of(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    /** {@code numerator / denominator}, reduced. A zero denominator is {@link NumericError#DIVISION_BY_ZERO}. */
    public static Rational of(BigInteger numerator, BigInteger denominator) throws NumericException {
        Objects.requireNonNull(numerator, "numerator");
        if (denominator.signum() == 0) {
            throw new NumericException(NumericError.DIVISION_BY_ZERO);
        }
        return reduced(numerator, denominator);
    }

    private static Rational reduced(BigInteger numerator, BigInteger denominator) {
        if (numerator.signum() == 0) {
            return ZERO;
        }
        BigInteger top = numerator;
        BigInteger bottom = denominator;
        if (bottom.signum() < 0) {
            top = top.negate();
            bottom = bottom.negate();
        }
        BigInteger gcd = top.gcd(bottom);
        if (!gcd.equals(BigInteger.ONE)) {
            top = top.divide(gcd);
            bottom = bottom.divide(gcd);
        }
        return new Rational(top, bottom);
    }

    /** {@code mantissa * 2^exponent}. Only powers of two can be shared, so no gcd is needed. */
    static Rational dyadic(BigInteger mantissa, int exponent) {
        if (mantissa.signum() == 0) {
            return ZERO;
        }
        if (exponent >= 0) {
            return new Rational(mantissa.shiftLeft(exponent), BigInteger.ONE);
        }
        int shared = Math.min(mantissa.getLowestSetBit(), -exponent);
        return new Rational(mantissa.shiftRight(shared), BigInteger.ONE.shiftLeft(-exponent - shared));
    }

    /** The value itself, or {@link NumericError#SIZE_LIMIT_EXCEEDED} if a component exceeds the limit. */
    static Rational withinLimits(Rational value) throws NumericException {
        if (value.bitLength() > NumericLimits.MAX_COMPONENT_BITS) {
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
        return value;
    }

    /**
     * Reads a decimal or a fraction. Grammar, without any space: {@code [+-]? digits? ([.,] digits?)? ([eE] [+-]? digits)?}
     * with at least one digit before the exponent ({@code 12}, {@code -0,5}, {@code .5}, {@code 3.}, {@code 1.5E-3}), or
     * two such decimals separated by {@code /} ({@code -1,5/4e2}). Only ASCII digits are accepted.
     *
     * <p>Errors, in this order: {@link NumericError#SIZE_LIMIT_EXCEEDED} for a text longer than
     * {@link NumericLimits#MAX_LITERAL_LENGTH} (checked first); {@link NumericError#MALFORMED_INPUT};
     * {@link NumericError#EXPONENT_OUT_OF_RANGE} for a scientific exponent beyond
     * {@link NumericLimits#MAX_SCIENTIFIC_EXPONENT}; {@link NumericError#SIZE_LIMIT_EXCEEDED} when a member or the
     * result needs more than {@link NumericLimits#MAX_COMPONENT_BITS} bits (detected before building a power of ten
     * when the digit count already proves it); {@link NumericError#DIVISION_BY_ZERO} for a zero denominator.
     */
    public static Rational parse(String text) throws NumericException {
        Objects.requireNonNull(text, "text");
        if (text.length() > NumericLimits.MAX_LITERAL_LENGTH) {
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
        int slash = text.indexOf('/');
        if (slash < 0) {
            return parseDecimal(text);
        }
        Rational top = parseDecimal(text.substring(0, slash));
        Rational bottom = parseDecimal(text.substring(slash + 1));
        if (bottom.signum() == 0) {
            throw new NumericException(NumericError.DIVISION_BY_ZERO);
        }
        return withinLimits(top.multiply(bottom.reciprocal()));
    }

    private static Rational parseDecimal(String text) throws NumericException {
        Matcher matcher = DECIMAL.matcher(text);
        if (!matcher.matches()) {
            throw new NumericException(NumericError.MALFORMED_INPUT);
        }
        String integerDigits = matcher.group(2);
        String fractionDigits = matcher.group(3) == null ? "" : matcher.group(3);
        if (integerDigits.isEmpty() && fractionDigits.isEmpty()) {
            throw new NumericException(NumericError.MALFORMED_INPUT);
        }
        int exponent = matcher.group(5) == null ? 0 : parseExponent("-".equals(matcher.group(4)), matcher.group(5));

        String digits = integerDigits + fractionDigits;
        int first = 0;
        while (first < digits.length() && digits.charAt(first) == '0') {
            first++;
        }
        if (first == digits.length()) {
            return ZERO;
        }
        int end = digits.length();
        while (digits.charAt(end - 1) == '0') {
            end--;
        }
        String significant = digits.substring(first, end);
        // value = significant * 10^power, where significant has no leading or trailing zero.
        int power = exponent - fractionDigits.length() + (digits.length() - end);

        // The value is at least 10^(digits - 1 + power): too many bits for the numerator, whatever the denominator.
        long magnitude = significant.length() - 1L + power;
        if (magnitude > 0 && (long) (magnitude * LOG2_TEN_LOWER) + 1 > NumericLimits.MAX_COMPONENT_BITS) {
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
        // significant is not a multiple of 10, so the reduced denominator of significant / 10^s is at least 2^s.
        if (power < 0 && -(long) power + 1 > NumericLimits.MAX_COMPONENT_BITS) {
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }

        BigInteger mantissa = new BigInteger(significant);
        if ("-".equals(matcher.group(1))) {
            mantissa = mantissa.negate();
        }
        Rational value = power >= 0
                ? of(mantissa.multiply(BigInteger.TEN.pow(power)))
                : reduced(mantissa, BigInteger.TEN.pow(-power));
        return withinLimits(value);
    }

    private static int parseExponent(boolean negative, String digits) throws NumericException {
        int first = 0;
        while (first < digits.length() - 1 && digits.charAt(first) == '0') {
            first++;
        }
        String value = digits.substring(first);
        if (value.length() > MAX_EXPONENT_DIGITS || Integer.parseInt(value) > NumericLimits.MAX_SCIENTIFIC_EXPONENT) {
            throw new NumericException(NumericError.EXPONENT_OUT_OF_RANGE);
        }
        return negative ? -Integer.parseInt(value) : Integer.parseInt(value);
    }

    public BigInteger numerator() {
        return numerator;
    }

    /** Always positive. */
    public BigInteger denominator() {
        return denominator;
    }

    public int signum() {
        return numerator.signum();
    }

    public boolean isInteger() {
        return denominator.equals(BigInteger.ONE);
    }

    /** Size in bits: the larger of the bit lengths of {@code |numerator|} and of the denominator. */
    public int bitLength() {
        return Math.max(numerator.abs().bitLength(), denominator.bitLength());
    }

    public Rational add(Rational other) {
        if (other.signum() == 0) {
            return this;
        }
        if (signum() == 0) {
            return other;
        }
        if (denominator.equals(other.denominator)) {
            return reduced(numerator.add(other.numerator), denominator);
        }
        return reduced(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public Rational subtract(Rational other) {
        return add(other.negate());
    }

    public Rational multiply(Rational other) {
        if (signum() == 0 || other.signum() == 0) {
            return ZERO;
        }
        // Cross reduction: the result is canonical without a gcd of the full products.
        BigInteger firstGcd = numerator.gcd(other.denominator);
        BigInteger secondGcd = other.numerator.gcd(denominator);
        return new Rational(numerator.divide(firstGcd).multiply(other.numerator.divide(secondGcd)),
                denominator.divide(secondGcd).multiply(other.denominator.divide(firstGcd)));
    }

    /** Exact quotient. A zero divisor is {@link NumericError#DIVISION_BY_ZERO}. */
    public Rational divide(Rational divisor) throws NumericException {
        return multiply(divisor.reciprocal());
    }

    /** {@code 1 / this}. Zero is {@link NumericError#DIVISION_BY_ZERO}. */
    public Rational reciprocal() throws NumericException {
        if (signum() == 0) {
            throw new NumericException(NumericError.DIVISION_BY_ZERO);
        }
        return signum() > 0 ? new Rational(denominator, numerator) : new Rational(denominator.negate(), numerator.negate());
    }

    public Rational negate() {
        return signum() == 0 ? this : new Rational(numerator.negate(), denominator);
    }

    public Rational abs() {
        return signum() >= 0 ? this : negate();
    }

    /**
     * Exact integer power; {@code x^0 = 1}, including {@code 0^0}. Errors: {@link NumericError#EXPONENT_OUT_OF_RANGE}
     * beyond {@link NumericLimits#MAX_POWER_EXPONENT}, {@link NumericError#DIVISION_BY_ZERO} for zero raised to a
     * negative power, and {@link NumericError#SIZE_LIMIT_EXCEEDED} when a component of the result would exceed
     * {@link NumericLimits#MAX_COMPONENT_BITS}. The size is checked before computing: a {@code b}-bit component raised
     * to {@code n} has at least {@code (b - 1) * n + 1} bits, so the power is refused at once when that is already too
     * many; otherwise the result has at most {@code MAX_COMPONENT_BITS + n} bits, which is cheap to compute and check.
     */
    public Rational pow(int exponent) throws NumericException {
        if (exponent > NumericLimits.MAX_POWER_EXPONENT || exponent < -NumericLimits.MAX_POWER_EXPONENT) {
            throw new NumericException(NumericError.EXPONENT_OUT_OF_RANGE);
        }
        if (exponent == 0) {
            return ONE;
        }
        Rational base = exponent < 0 ? reciprocal() : this;
        int times = Math.abs(exponent);
        requirePowerFits(base.numerator.abs(), times);
        requirePowerFits(base.denominator, times);
        return withinLimits(new Rational(base.numerator.pow(times), base.denominator.pow(times)));
    }

    private static void requirePowerFits(BigInteger magnitude, int times) throws NumericException {
        long fewestBits = (magnitude.bitLength() - 1L) * times + 1;
        if (fewestBits > NumericLimits.MAX_COMPONENT_BITS) {
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
    }

    /**
     * The exact decimal value when it is finite, that is when the denominator is {@code 2^a * 5^b}; empty otherwise.
     * The scale is the smallest possible: {@code 3/4} gives {@code 0.75}, {@code 100} gives {@code 100}.
     */
    public Optional<BigDecimal> toExactDecimal() {
        int twos = denominator.getLowestSetBit();
        int fives = powerOfFive(denominator.shiftRight(twos));
        if (fives < 0) {
            return Optional.empty();
        }
        int scale = Math.max(twos, fives);
        BigInteger unscaled = numerator.shiftLeft(scale - twos).multiply(FIVE.pow(scale - fives));
        return Optional.of(new BigDecimal(unscaled, scale));
    }

    // The b such that value = 5^b, or -1. 5^b has floor(b * log2(5)) + 1 bits, which gives b up to rounding.
    private static int powerOfFive(BigInteger value) {
        if (value.equals(BigInteger.ONE)) {
            return 0;
        }
        int estimate = (int) Math.round((value.bitLength() - 1) / LOG2_FIVE);
        for (int candidate = Math.max(1, estimate - 1); candidate <= estimate + 1; candidate++) {
            if (FIVE.pow(candidate).equals(value)) {
                return candidate;
            }
        }
        return -1;
    }

    @Override
    public int compareTo(Rational other) {
        int sign = signum();
        int otherSign = other.signum();
        if (sign != otherSign) {
            return Integer.compare(sign, otherSign);
        }
        if (denominator.equals(other.denominator)) {
            return numerator.compareTo(other.numerator);
        }
        return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Rational rational
                && numerator.equals(rational.numerator)
                && denominator.equals(rational.denominator);
    }

    @Override
    public int hashCode() {
        return 31 * numerator.hashCode() + denominator.hashCode();
    }

    /** {@code p} for an integer, {@code p/q} otherwise ({@code -3/4}); {@link #parse(String)} reads it back. */
    @Override
    public String toString() {
        return isInteger() ? numerator.toString() : numerator + "/" + denominator;
    }
}
