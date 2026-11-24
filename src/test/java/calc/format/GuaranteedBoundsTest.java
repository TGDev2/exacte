package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** {@link ValueFormatter#guaranteedBounds}: each bound rounded outwards to 12 significant digits, written as the Auto mode. */
class GuaranteedBoundsTest {

    @Test
    void eachBoundIsRoundedOutwardsToTwelveSignificantDigits() throws NumericException {
        assertEquals(new GuaranteedBounds("0,333333333333", "0,666666666667"), bounds(fraction(1, 3), fraction(2, 3)));
        assertEquals(new GuaranteedBounds("0,333333333333", "0,333333333334"), bounds(fraction(1, 3), fraction(1, 3).add(tiny())));
        assertEquals(new GuaranteedBounds("1,41421356237", "1,41421356238"),
                bounds(Rational.parse("1,414213562373095"), Rational.parse("1,414213562373096")));
    }

    @Test
    void aNegativeBoundIsRoundedAwayFromTheValueToo() throws NumericException {
        assertEquals(new GuaranteedBounds("-0,666666666667", "-0,333333333333"), bounds(fraction(-2, 3), fraction(-1, 3)));
        assertEquals(new GuaranteedBounds("-0,333333333334", "0,333333333334"), bounds(fraction(-1, 3), fraction(1, 3).add(tiny())));
    }

    @Test
    void aZeroBoundReadsZero() throws NumericException {
        assertEquals(new GuaranteedBounds("0", "0,333333333334"), bounds(Rational.ZERO, fraction(1, 3).add(tiny())));
        assertEquals(new GuaranteedBounds("-2", "0"), bounds(Rational.of(-2), Rational.ZERO));
    }

    @Test
    void aBoundThatNeedsNoRoundingIsWrittenWithoutTrailingZeros() throws NumericException {
        assertEquals(new GuaranteedBounds("2,5", "2,5"), ValueFormatter.guaranteedBounds(CertifiedValue.exact(fraction(5, 2))));
        assertEquals(new GuaranteedBounds("1", "3"), bounds(Rational.ONE, Rational.of(3)));
    }

    @Test
    void aCarryTakesTheNextPowerOfTen() throws NumericException {
        assertEquals(new GuaranteedBounds("9,99999999999", "10"),
                bounds(Rational.parse("9,9999999999995"), Rational.parse("9,9999999999999")));
        // 999 999 999 999 999,5 has 15 integer digits, its upper bound 16: the notation follows the rounded value.
        assertEquals(new GuaranteedBounds("999999999999000", "1 × 10¹⁵"),
                bounds(Rational.parse("999999999999999"), Rational.parse("999999999999999,5")));
    }

    @Test
    void positionalFromTenToTheMinusSixToTenToTheFifteenExcluded() throws NumericException {
        assertEquals(new GuaranteedBounds("0,000001", "0,000003"), bounds(fraction(1, 1_000_000), fraction(3, 1_000_000)));
        assertEquals(new GuaranteedBounds("100000000000000", "100000000001000"),
                bounds(Rational.parse("100000000000000"), Rational.parse("100000000000001")));
    }

    @Test
    void scientificOutsideThePositionalRange() throws NumericException {
        assertEquals(new GuaranteedBounds("1 × 10⁻⁷", "2 × 10⁻⁷"), bounds(fraction(1, 10_000_000), fraction(2, 10_000_000)));
        assertEquals(new GuaranteedBounds("1,23456789012 × 10¹⁷", "1,23456789013 × 10¹⁷"),
                bounds(Rational.parse("123456789012345678"), Rational.parse("123456789012345679")));
        assertEquals(new GuaranteedBounds("-1,23456789013 × 10¹⁷", "-1,23456789012 × 10¹⁷"),
                bounds(Rational.parse("-123456789012345679"), Rational.parse("-123456789012345678")));
        assertEquals(new GuaranteedBounds("1 × 10¹⁵", "2 × 10¹⁵"), bounds(Rational.parse("1e15"), Rational.parse("2e15")));
    }

    @Test
    void aValueAndItsBoundsAreRequired() {
        assertThrows(NullPointerException.class, () -> ValueFormatter.guaranteedBounds(null));
        assertThrows(NullPointerException.class, () -> new GuaranteedBounds(null, "1"));
        assertThrows(NullPointerException.class, () -> new GuaranteedBounds("1", null));
    }

    private static GuaranteedBounds bounds(Rational lower, Rational upper) throws NumericException {
        return ValueFormatter.guaranteedBounds(CertifiedValue.between(lower, upper));
    }

    private static Rational fraction(long numerator, long denominator) throws NumericException {
        return Rational.of(numerator, denominator);
    }

    // 10⁻³⁰: far below the twelfth digit, it still moves a bound rounded upwards.
    private static Rational tiny() throws NumericException {
        return Rational.of(BigInteger.ONE, BigInteger.TEN.pow(30));
    }
}
