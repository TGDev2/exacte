package calc.number;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Directed rounding of the bounds of approximate values. Every function returns a value on the requested side of the
 * exact one, so an interval built from a rounded-down lower bound and a rounded-up upper bound still contains the true
 * value. The results are within {@link NumericLimits#MAX_COMPONENT_BITS}, or the function throws
 * {@link NumericError#SIZE_LIMIT_EXCEEDED}. A bound that is not zero stays not zero, with the same sign: when the finest
 * grid cannot bound it without touching 0, the function throws the same error.
 */
final class OutwardRounding {
    private static final int BUDGET = NumericLimits.BOUND_SIGNIFICANT_BITS;
    // The finest grid step, 2^-(MAX_COMPONENT_BITS - 1): a rounded denominator always fits the component limit. Below
    // 2^(BUDGET - MAX_COMPONENT_BITS) a bound keeps fewer significant bits, but stays on the right side and never
    // reaches 0.
    private static final int FINEST_EXPONENT = 1 - NumericLimits.MAX_COMPONENT_BITS;

    private OutwardRounding() {
    }

    /** True when {@code value = m * 2^k} with {@code |m| < 2^BOUND_SIGNIFICANT_BITS}: a bound within the budget. */
    static boolean fitsBudget(Rational value) {
        if (value.denominator().bitCount() != 1) {
            return false;
        }
        BigInteger magnitude = value.numerator().abs();
        return magnitude.signum() == 0 || magnitude.bitLength() - magnitude.getLowestSetBit() <= BUDGET;
    }

    /** The largest grid value not above {@code value}; {@code value} itself when it is within the budget. */
    static Rational down(Rational value) throws NumericException {
        return toGrid(value, false);
    }

    /** The smallest grid value not below {@code value}; {@code value} itself when it is within the budget. */
    static Rational up(Rational value) throws NumericException {
        return toGrid(value, true);
    }

    // The grid around a value of magnitude 2^e has the step 2^(e + 1 - BUDGET): BUDGET significant bits.
    private static Rational toGrid(Rational value, boolean up) throws NumericException {
        if (fitsBudget(value) && value.denominator().bitLength() <= NumericLimits.MAX_COMPONENT_BITS) {
            return Rational.withinLimits(value);
        }
        long magnitude = floorLog2(value);
        if (magnitude >= NumericLimits.MAX_COMPONENT_BITS) {
            // |value| >= 2^MAX_COMPONENT_BITS: no bound on either side fits the limit.
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
        int exponent = (int) Math.max(magnitude + 1 - BUDGET, FINEST_EXPONENT);
        BigInteger numerator = value.numerator();
        BigInteger scaledNumerator = exponent < 0 ? numerator.shiftLeft(-exponent) : numerator;
        BigInteger scaledDenominator = exponent > 0 ? value.denominator().shiftLeft(exponent) : value.denominator();
        BigInteger[] quotient = scaledNumerator.divideAndRemainder(scaledDenominator);
        BigInteger mantissa = quotient[0];
        // divideAndRemainder truncates toward zero: one step away from zero is needed going up from a positive value
        // or down from a negative one.
        if (quotient[1].signum() != 0 && up == (numerator.signum() > 0)) {
            mantissa = mantissa.add(BigInteger.valueOf(numerator.signum()));
        }
        if (mantissa.signum() == 0) {
            // 0 < |value| < 2^FINEST_EXPONENT, rounded toward 0: a value that is not zero would read as 0.
            throw new NumericException(NumericError.SIZE_LIMIT_EXCEEDED);
        }
        return Rational.withinLimits(Rational.dyadic(mantissa, exponent));
    }

    /** The integer {@code e} with {@code 2^e <= |value| < 2^(e + 1)}, for a non-zero value. */
    static long floorLog2(Rational value) {
        BigInteger magnitude = value.numerator().abs();
        BigInteger denominator = value.denominator();
        long estimate = (long) magnitude.bitLength() - denominator.bitLength();
        // |value| / 2^estimate is in (1/2, 2): the answer is estimate when magnitude >= denominator * 2^estimate.
        boolean reached = estimate >= 0
                ? magnitude.compareTo(denominator.shiftLeft((int) estimate)) >= 0
                : magnitude.shiftLeft((int) -estimate).compareTo(denominator) >= 0;
        return reached ? estimate : estimate - 1;
    }

    /**
     * A lower ({@code up == false}) or upper bound of {@code value^times}, for {@code value >= 0} and {@code times >= 1}.
     * Square and multiply, every intermediate rounded in the same direction: all factors are non-negative, so a product
     * of lower bounds rounded down is a lower bound, and the same holds upward.
     */
    static Rational powBound(Rational value, int times, boolean up) throws NumericException {
        Rational result = Rational.ONE;
        Rational square = toGrid(value, up);
        int remaining = times;
        while (true) {
            if ((remaining & 1) != 0) {
                result = toGrid(result.multiply(square), up);
            }
            remaining >>>= 1;
            if (remaining == 0) {
                return result;
            }
            // For value >= 1 this square is at most value^times, so it only overflows when the result would.
            square = toGrid(square.multiply(square), up);
        }
    }

    /**
     * A lower ({@code up == false}) or upper bound of the square root of {@code value >= 0}: the exact root when the
     * numerator and the denominator are perfect squares, otherwise {@code a / 2^j} with {@code a} an integer root.
     *
     * <p>With {@code 2^(2p + 2) <= T = value * 4^j < 2^(2p + 4)}, the lower bound is {@code a / 2^j} with
     * {@code a = floor(sqrt(floor(T)))} and the upper bound {@code b / 2^j} with {@code b = ceil(sqrt(ceil(T)))}.
     * Since {@code floor(T) + 1 <= (a + 1)^2}, {@code b <= a + 1}; and {@code a >= 2^(p + 1)}, so the width is at most
     * {@code 2^-(p + 1)} times the lower bound. Both numerators have at most {@code p + 3} bits, within the budget.
     */
    static Rational sqrtBound(Rational value, int precisionBits, boolean up) throws NumericException {
        if (value.signum() == 0) {
            return Rational.ZERO;
        }
        Optional<Rational> exact = exactSqrt(value);
        if (exact.isPresent()) {
            return exact.get();
        }
        long magnitude = floorLog2(value);
        long half = Math.floorDiv(2L * precisionBits + 3 - magnitude, 2);
        BigInteger scaledNumerator = half > 0 ? value.numerator().shiftLeft((int) (2 * half)) : value.numerator();
        BigInteger scaledDenominator = half < 0 ? value.denominator().shiftLeft((int) (-2 * half)) : value.denominator();
        BigInteger[] quotient = scaledNumerator.divideAndRemainder(scaledDenominator);
        BigInteger root;
        if (up) {
            BigInteger ceiling = quotient[1].signum() == 0 ? quotient[0] : quotient[0].add(BigInteger.ONE);
            BigInteger[] integerRoot = ceiling.sqrtAndRemainder();
            root = integerRoot[1].signum() == 0 ? integerRoot[0] : integerRoot[0].add(BigInteger.ONE);
        } else {
            root = quotient[0].sqrt();
        }
        return Rational.withinLimits(Rational.dyadic(root, (int) -half));
    }

    private static Optional<Rational> exactSqrt(Rational value) throws NumericException {
        BigInteger[] numeratorRoot = value.numerator().sqrtAndRemainder();
        if (numeratorRoot[1].signum() != 0) {
            return Optional.empty();
        }
        BigInteger[] denominatorRoot = value.denominator().sqrtAndRemainder();
        if (denominatorRoot[1].signum() != 0) {
            return Optional.empty();
        }
        return Optional.of(Rational.of(numeratorRoot[0], denominatorRoot[0]));
    }
}
