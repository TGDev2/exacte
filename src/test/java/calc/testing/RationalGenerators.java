package calc.testing;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigDecimal;
import java.math.BigInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators for the numeric core ({@code calc.number}): raw fractions, rationals, intervals that contain a known point,
 * and decimal literals read by {@link BigDecimal} as an independent parser.
 */
public final class RationalGenerators {
    private RationalGenerators() {
    }

    /** A point and an interval known to contain it. */
    public record Enclosure(Rational point, CertifiedValue interval) {
    }

    /** A literal and the value {@link BigDecimal} reads in it (comma turned into a point). */
    public record DecimalLiteral(String text, BigDecimal value) {
    }

    /**
     * Signed integers whose bit length is spread evenly up to {@code maxBits}, with a share of tiny values (-3 to 3) so
     * that zero, one and minus one come often.
     */
    public static Arbitrary<BigInteger> integers(int maxBits) {
        Arbitrary<BigInteger> wide = Arbitraries.integers().between(1, maxBits).flatMap(bits -> Arbitraries.bigIntegers()
                .between(BigInteger.ONE.shiftLeft(bits).negate(), BigInteger.ONE.shiftLeft(bits)));
        return Arbitraries.frequencyOf(
                Tuple.of(1, Arbitraries.bigIntegers().between(BigInteger.valueOf(-3), BigInteger.valueOf(3))),
                Tuple.of(5, wide));
    }

    /**
     * Raw fractions, never reduced: the sign may be on either component and both may share a factor, so that the
     * reduction and the sign rule of {@link Rational} are exercised.
     */
    public static Arbitrary<FractionOracle> fractions(int maxBits) {
        Arbitrary<BigInteger> denominators = integers(maxBits).filter(value -> value.signum() != 0);
        Arbitrary<FractionOracle> plain = Combinators.combine(integers(maxBits), denominators).as(FractionOracle::new);
        Arbitrary<BigInteger> factors = integers(64).filter(value -> value.signum() != 0);
        return Arbitraries.frequencyOf(
                Tuple.of(3, plain),
                Tuple.of(1, Combinators.combine(plain, factors).as((fraction, factor) ->
                        new FractionOracle(fraction.numerator().multiply(factor), fraction.denominator().multiply(factor)))));
    }

    public static Arbitrary<Rational> rationals(int maxBits) {
        return fractions(maxBits).map(RationalGenerators::toRational);
    }

    public static Arbitrary<Rational> nonNegativeRationals(int maxBits) {
        return rationals(maxBits).map(Rational::abs);
    }

    /**
     * Intervals around a point: exact (one in five), or widened below and above by an offset that is zero, a rational
     * of up to {@code maxBits} bits or a tiny power of two (down to {@code 2^-300}), so that one-sided, narrow, wide and
     * zero-crossing intervals all appear.
     */
    public static Arbitrary<Enclosure> enclosures(int maxBits) {
        return enclosures(rationals(maxBits), maxBits);
    }

    /** {@link #enclosures(int)} around the given points. */
    public static Arbitrary<Enclosure> enclosures(Arbitrary<Rational> points, int maxBits) {
        Arbitrary<Rational> offsets = Arbitraries.frequencyOf(
                Tuple.of(2, Arbitraries.just(Rational.ZERO)),
                Tuple.of(5, nonNegativeRationals(maxBits)),
                Tuple.of(2, Arbitraries.integers().between(1, 300).map(RationalGenerators::inversePowerOfTwo)));
        Arbitrary<Enclosure> exact = points.map(point -> new Enclosure(point, between(point, point)));
        Arbitrary<Enclosure> widened = Combinators.combine(points, offsets, offsets)
                .as((point, below, above) -> new Enclosure(point, between(point.subtract(below), point.add(above))));
        return Arbitraries.frequencyOf(Tuple.of(1, exact), Tuple.of(4, widened));
    }

    /**
     * Decimal literals of the accepted grammar: optional sign, digits with leading and trailing zeros, point or comma,
     * optional exponent ({@code e} or {@code E}, signed, with leading zeros, up to {@code |60|}).
     */
    public static Arbitrary<DecimalLiteral> decimalLiterals() {
        Arbitrary<String> signs = Arbitraries.of("", "+", "-");
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofMaxLength(30);
        Arbitrary<String> separators = Arbitraries.of(".", ",");
        Arbitrary<String> exponents = Arbitraries.oneOf(Arbitraries.just(""),
                Combinators.combine(Arbitraries.of("e", "E"), Arbitraries.of("", "+", "-"), Arbitraries.integers().between(0, 60),
                        Arbitraries.of("", "0", "00")).as((letter, sign, value, zeros) -> letter + sign + zeros + value));
        Arbitrary<String> withPoint = Combinators.combine(digits, separators, digits)
                .as((integer, separator, fraction) -> integer + separator + fraction)
                .filter(text -> text.length() > 1);
        Arbitrary<String> mantissas = Arbitraries.oneOf(digits.filter(text -> !text.isEmpty()), withPoint);
        return Combinators.combine(signs, mantissas, exponents)
                .as((sign, mantissa, exponent) -> sign + mantissa + exponent)
                .map(text -> new DecimalLiteral(text, new BigDecimal(text.replace(',', '.'))));
    }

    public static Rational toRational(FractionOracle fraction) {
        try {
            return Rational.of(fraction.numerator(), fraction.denominator());
        } catch (NumericException impossible) {
            throw new IllegalStateException("generated a zero denominator", impossible);
        }
    }

    public static Rational inversePowerOfTwo(int exponent) {
        return toRational(new FractionOracle(BigInteger.ONE, BigInteger.ONE.shiftLeft(exponent)));
    }

    private static CertifiedValue between(Rational lower, Rational upper) {
        try {
            return CertifiedValue.between(lower, upper);
        } catch (NumericException tooLarge) {
            throw new IllegalStateException("generated a bound beyond the size limit", tooLarge);
        }
    }
}
