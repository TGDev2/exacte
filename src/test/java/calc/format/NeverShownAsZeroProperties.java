package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import calc.number.Rational;
import calc.testing.DisplayTexts;
import calc.testing.FractionOracle;
import java.math.BigInteger;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;

/**
 * The random cases of {@link NeverShownAsZeroAcceptanceTest} (a jqwik class carries its own tag, see {@code PropertyTagsTest}):
 * an interval on one side of 0, however close to it, never reads as 0 in any mode; an interval that contains 0 is read
 * « |x| ≤ b » with the true interval inside [-b ; b]; outward rounding either keeps the sign of both bounds or refuses.
 */
@Tag("property")
class NeverShownAsZeroProperties {
    // 2^-4095: the finest grid step of the core, read here from the 4096-bit limit of its components.
    private static final int FINEST_BITS = 4095;
    private static final FractionOracle FINEST = new FractionOracle(BigInteger.ONE, BigInteger.TWO.pow(FINEST_BITS));

    // ------------------------------------------------------------------------------------------------ generators

    private static Arbitrary<BigInteger> mantissas(int bits) {
        return Arbitraries.bigIntegers().between(BigInteger.ONE, BigInteger.ONE.shiftLeft(bits));
    }

    /** {@code m / 2^k} and {@code m / 10^j}, from 2^-4095 upward, as well as whole numbers far from 0. */
    private static Arbitrary<Rational> magnitudes() {
        Arbitrary<Rational> dyadic = Combinators.combine(mantissas(64), Arbitraries.integers().between(0, FINEST_BITS))
                .as((mantissa, bits) -> rational(mantissa, BigInteger.TWO.pow(bits)));
        Arbitrary<Rational> decimal = Combinators.combine(mantissas(64), Arbitraries.integers().between(0, 1200))
                .as((mantissa, digits) -> rational(mantissa, BigInteger.TEN.pow(digits)));
        Arbitrary<Rational> closest = mantissas(4).map(mantissa -> rational(mantissa, BigInteger.TWO.pow(FINEST_BITS)));
        Arbitrary<Rational> whole = mantissas(4000).map(Rational::of);
        return Arbitraries.frequencyOf(
                Tuple.of(4, dyadic),
                Tuple.of(2, decimal),
                Tuple.of(2, closest),
                Tuple.of(1, whole));
    }

    @Provide
    Arbitrary<CertifiedValue> oneSided() {
        Arbitrary<CertifiedValue> narrow = magnitudes().map(lower -> between(lower, lower.add(lower)));
        Arbitrary<CertifiedValue> wide = Combinators.combine(magnitudes(), magnitudes()).as((first, second) -> first.compareTo(second) == 0
                ? between(first, first.add(first))
                : between(first.compareTo(second) < 0 ? first : second, first.compareTo(second) < 0 ? second : first));
        Arbitrary<CertifiedValue> positive = Arbitraries.oneOf(narrow, wide);
        return Combinators.combine(positive, Arbitraries.of(false, true)).as((value, negative) -> negative ? value.negate() : value);
    }

    @Provide
    Arbitrary<CertifiedValue> aroundZero() {
        Arbitrary<Rational> sideOrZero = Arbitraries.frequencyOf(Tuple.of(1, Arbitraries.just(Rational.ZERO)), Tuple.of(6, magnitudes()));
        return Combinators.combine(sideOrZero, sideOrZero).as((below, above) -> between(below.negate(), above)).filter(value -> !value.isExact());
    }

    /** Positive intervals whose products fall on both sides of 2^-4095. */
    @Provide
    Arbitrary<CertifiedValue> tinyFactors() {
        return Combinators.combine(mantissas(16), mantissas(16), Arbitraries.integers().between(1900, 2200))
                .as((mantissa, width, bits) -> between(rational(mantissa, BigInteger.TWO.pow(bits)), rational(mantissa.add(width), BigInteger.TWO.pow(bits))));
    }

    /** Positive intervals at a scale where a power goes from a plain value to below 2^-4095. */
    @Provide
    Arbitrary<CertifiedValue> powerBases() {
        return Combinators.combine(mantissas(16), Arbitraries.integers().between(1, 40))
                .as((mantissa, bits) -> between(rational(mantissa, BigInteger.TWO.pow(bits)), rational(mantissa.add(BigInteger.ONE), BigInteger.TWO.pow(bits))));
    }

    private static Rational rational(BigInteger numerator, BigInteger denominator) {
        try {
            return Rational.of(numerator, denominator);
        } catch (NumericException exception) {
            throw new AssertionError(exception);
        }
    }

    private static CertifiedValue between(Rational lower, Rational upper) {
        try {
            return CertifiedValue.between(lower, upper);
        } catch (NumericException exception) {
            throw new AssertionError(lower + " ; " + upper, exception);
        }
    }

    // ------------------------------------------------------------------------------------------------ the display

    @Property
    void anIntervalOnOneSideOfZeroNeverReadsAsZero(@ForAll("oneSided") CertifiedValue value, @ForAll DisplayMode mode) {
        String shown = ValueFormatter.format(value, mode).text();

        assertTrue(DisplayTexts.isApproximate(shown), () -> mode + " does not mark as approximate: " + shown);
        assertEquals(value.lower().signum(), DisplayTexts.value(shown).signum(), () -> mode + " shows " + shown + " for " + value);
    }

    @Property
    void anIntervalThatContainsZeroIsInsideTheBoundReadFromItsText(@ForAll("aroundZero") CertifiedValue value, @ForAll DisplayMode mode) {
        String shown = ValueFormatter.format(value, mode).text();
        FractionOracle bound = NeverShownAsZeroAcceptanceTest.boundShown(shown);

        assertTrue(bound.negate().compareTo(NeverShownAsZeroAcceptanceTest.oracle(value.lower())) <= 0, () -> shown + " does not hold " + value);
        assertTrue(bound.compareTo(NeverShownAsZeroAcceptanceTest.oracle(value.upper())) >= 0, () -> shown + " does not hold " + value);
        NeverShownAsZeroAcceptanceTest.assertShownAsBound(value);
    }

    // ------------------------------------------------------------------------------------------------ outward rounding

    @Property
    void aProductKeepsTheSignOfItsBoundsAndIsRefusedOnlyWhenTheFinestGridCannotBoundIt(
            @ForAll("tinyFactors") CertifiedValue first, @ForAll("tinyFactors") CertifiedValue second, @ForAll boolean negative) {
        FractionOracle smallest = NeverShownAsZeroAcceptanceTest.oracle(first.lower()).multiply(NeverShownAsZeroAcceptanceTest.oracle(second.lower()));
        FractionOracle largest = NeverShownAsZeroAcceptanceTest.oracle(first.upper()).multiply(NeverShownAsZeroAcceptanceTest.oracle(second.upper()));
        boolean bounded = smallest.compareTo(FINEST) >= 0;
        int sign = negative ? -1 : 1;
        CertifiedValue result;
        try {
            result = (negative ? first.negate() : first).multiply(second);
        } catch (NumericException refused) {
            assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, refused.error());
            assertFalse(bounded, () -> first + " * " + second + " could be bounded on the finest grid");
            return;
        }
        CertifiedValue magnitude = negative ? result.negate() : result;
        assertTrue(bounded, () -> first + " * " + second + " should be refused, not " + result);
        assertEquals(sign, result.lower().signum(), () -> "the lower bound of " + result);
        assertEquals(sign, result.upper().signum(), () -> "the upper bound of " + result);
        assertTrue(NeverShownAsZeroAcceptanceTest.oracle(magnitude.lower()).compareTo(smallest) <= 0, "the interval still holds the true value");
        assertTrue(NeverShownAsZeroAcceptanceTest.oracle(magnitude.upper()).compareTo(largest) >= 0, "the interval still holds the true value");
    }

    @Property
    void aPowerKeepsTheSignOfItsBoundsOrIsRefused(@ForAll("powerBases") CertifiedValue base, @ForAll @IntRange(min = 1, max = 4096) int exponent, @ForAll boolean negative) {
        int sign = negative && exponent % 2 != 0 ? -1 : 1;
        CertifiedValue result;
        try {
            result = (negative ? base.negate() : base).pow(exponent);
        } catch (NumericException refused) {
            assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, refused.error());
            return;
        }
        assertEquals(sign, result.lower().signum(), () -> "the lower bound of " + base + " ^ " + exponent + ": " + result);
        assertEquals(sign, result.upper().signum(), () -> "the upper bound of " + base + " ^ " + exponent + ": " + result);
    }
}
