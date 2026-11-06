package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/** The edges of the thresholds of {@link ValueFormatter}: the last value on one side and the first on the other. */
class ValueFormatterBoundaryTest {
    @Test
    void autoModeIsPositionalFrom1eMinus6UpTo1e15Excluded() throws NumericException {
        assertEquals("0,000001", text(power(-6), DisplayMode.AUTO));
        assertEquals("1 × 10⁻⁷", text(power(-7), DisplayMode.AUTO));
        assertEquals("100000000000000", text(power(14), DisplayMode.AUTO));
        assertEquals("1 × 10¹⁵", text(power(15), DisplayMode.AUTO));
        assertEquals("999999999999999", text(exact(BigInteger.TEN.pow(15).subtract(BigInteger.ONE), BigInteger.ONE), DisplayMode.AUTO));
    }

    @Test
    void decimalModeIsPositionalDownTo1eMinus15() throws NumericException {
        assertEquals("0,000000000000001", text(power(-15), DisplayMode.DECIMAL));
        assertFalse(ValueFormatter.format(power(-15), DisplayMode.DECIMAL).notationSwitched());
        assertEquals("1 × 10⁻¹⁶", text(power(-16), DisplayMode.DECIMAL));
        assertTrue(ValueFormatter.format(power(-16), DisplayMode.DECIMAL).notationSwitched());
    }

    @Test
    void truncationIsReportedInEveryNotation() throws NumericException {
        CertifiedValue third = exact(BigInteger.ONE, BigInteger.valueOf(3));
        CertifiedValue tinyThird = exact(BigInteger.ONE, BigInteger.valueOf(3).multiply(BigInteger.TEN.pow(20)));

        assertTrue(ValueFormatter.format(third, DisplayMode.DECIMAL).truncated());
        assertFalse(ValueFormatter.format(exact(BigInteger.valueOf(3), BigInteger.TEN), DisplayMode.DECIMAL).truncated());
        assertTrue(ValueFormatter.format(tinyThird, DisplayMode.DECIMAL).truncated());
        assertFalse(ValueFormatter.format(power(-20), DisplayMode.DECIMAL).truncated());
        assertTrue(ValueFormatter.format(third, DisplayMode.SCIENTIFIC).truncated());
        assertFalse(ValueFormatter.format(power(-20), DisplayMode.SCIENTIFIC).truncated());
        assertFalse(ValueFormatter.format(power(-20), DisplayMode.AUTO).truncated());
        assertTrue(ValueFormatter.format(tinyThird, DisplayMode.AUTO).truncated());
    }

    @Test
    void aFractionOf40CharactersIsShownAndOneOf41IsNot() throws NumericException {
        BigInteger denominator40 = BigInteger.TEN.pow(37).add(BigInteger.ONE);
        BigInteger denominator41 = BigInteger.TEN.pow(38).add(BigInteger.ONE);

        FormattedValue shown = ValueFormatter.format(exact(BigInteger.ONE, denominator40), DisplayMode.FRACTION);
        assertEquals("1/" + denominator40, shown.text());
        assertFalse(shown.notationSwitched());
        assertTrue(ValueFormatter.format(exact(BigInteger.ONE, denominator41), DisplayMode.FRACTION).notationSwitched());
    }

    @Test
    void theCentreOfAnApproximateValueIsShownAsAFractionUpTo40Characters() throws NumericException {
        FormattedValue shown = ValueFormatter.format(around(BigInteger.TEN.pow(37).add(BigInteger.ONE)), DisplayMode.FRACTION);
        assertEquals("≈ 1/" + BigInteger.TEN.pow(37).add(BigInteger.ONE), shown.text());
        assertFalse(shown.notationSwitched());
        assertTrue(shown.truncated());
        assertTrue(ValueFormatter.format(around(BigInteger.TEN.pow(38).add(BigInteger.ONE)), DisplayMode.FRACTION).notationSwitched());
    }

    @Test
    void anIntervalWithZeroAsABoundMayBeZero() throws NumericException {
        CertifiedValue positive = between(Rational.ZERO, Rational.of(1, 3));
        CertifiedValue negative = between(Rational.of(-1, 3), Rational.ZERO);

        for (CertifiedValue value : new CertifiedValue[] {positive, negative}) {
            assertEquals("≈ 0", text(value, DisplayMode.AUTO));
            assertEquals("≈ 0", text(value, DisplayMode.DECIMAL));
            assertEquals("≈ 0", text(value, DisplayMode.SCIENTIFIC));
            assertEquals("0", ValueFormatter.canonical(value));
        }
    }

    @Test
    void theDigitsComeFromTheBoundOfLargestMagnitude() throws NumericException {
        // The units are not guaranteed ([9, 11] holds both 9 and 11), so one digit at the scale of the 11 is shown.
        assertEquals("≈ 1… × 10¹", text(between(Rational.of(9), Rational.of(11)), DisplayMode.AUTO));
        assertEquals("≈ -1… × 10¹", text(between(Rational.of(-11), Rational.of(-9)), DisplayMode.AUTO));
    }

    @Test
    void anApproximateValueWhoseUnitsAreGuaranteedIsPositional() throws NumericException {
        assertEquals("≈ 1235…", text(between(Rational.of(12345, 10), Rational.of(12349, 10)), DisplayMode.AUTO));
        assertEquals("≈ 1235…", text(between(Rational.of(12345, 10), Rational.of(12349, 10)), DisplayMode.DECIMAL));
    }

    @Test
    void theScaleOfTheDigitsFollowsTheLargestBoundWhenTheBoundsHaveDifferentMagnitudes() throws NumericException {
        // 9,96 and 10,04 agree on 10,0: the exponent is the one of 10,04, not the one of 9,96.
        assertEquals("≈ 10,0…", text(between(Rational.of(996, 100), Rational.of(1004, 100)), DisplayMode.AUTO));
    }

    @Test
    void whenNoDigitIsCommonTheCentreIsShownWithOneSignificantDigitAtItsOwnScale() throws NumericException {
        assertEquals("≈ 2… × 10¹", text(between(Rational.of(10), Rational.of(30)), DisplayMode.AUTO));
        assertEquals("≈ 0,02…", text(between(Rational.of(1, 100), Rational.of(3, 100)), DisplayMode.AUTO));
    }

    @Test
    void whenNoDigitIsCommonTheCentreIsShownWithOneDigit() throws NumericException {
        assertEquals("≈ 2…", text(between(Rational.of(1), Rational.of(3)), DisplayMode.AUTO));
        assertEquals("2", ValueFormatter.canonical(between(Rational.of(1), Rational.of(3))));
    }

    @Test
    void aRoundingThatCarriesDropsTheExtraDigit() throws NumericException {
        CertifiedValue nearOne = exact(BigInteger.TEN.pow(13).subtract(BigInteger.valueOf(4)), BigInteger.TEN.pow(13));

        assertEquals("1,00000000000…", text(nearOne, DisplayMode.AUTO));
        assertEquals("1,00000000000… × 10⁰", text(nearOne, DisplayMode.SCIENTIFIC));
        assertEquals("≈ 1,00000000000…", text(around(nearOne.lower()), DisplayMode.AUTO));
    }

    @Test
    void theCanonicalTextOfAnApproximateValueIsTheShortestDecimalInsideItsBounds() throws NumericException {
        // The shortest decimal may equal a bound: the bounds are included.
        assertEquals("1", ValueFormatter.canonical(between(Rational.ONE, Rational.of(7, 5))));
        assertEquals("2", ValueFormatter.canonical(between(Rational.of(8, 5), Rational.of(2))));
        assertEquals("10", ValueFormatter.canonical(between(Rational.of(10), Rational.of(14))));
        assertEquals("0,01", ValueFormatter.canonical(between(Rational.of(1, 100), Rational.of(14, 1000))));
        assertEquals("1,2", ValueFormatter.canonical(between(Rational.of(115, 100), Rational.of(125, 100))));
    }

    @Test
    void anApproximateCanonicalTextIsPlainUpTo40CharactersAndScientificBeyond() throws NumericException {
        BigInteger thirds38 = BigInteger.TEN.pow(38).divide(BigInteger.valueOf(3));
        BigInteger thirds39 = BigInteger.TEN.pow(39).divide(BigInteger.valueOf(3));

        assertEquals("0," + "3".repeat(38), ValueFormatter.canonical(narrow(thirds38, 38)));
        assertEquals("3," + "3".repeat(38) + "e-1", ValueFormatter.canonical(narrow(thirds39, 39)));
    }

    @Test
    void anExactCanonicalTextIsPlainUpTo40CharactersAndThenTheShortestOfScientificAndFraction() throws NumericException {
        assertEquals("0," + "0".repeat(37) + "1", ValueFormatter.canonical(exact(BigInteger.ONE, BigInteger.TEN.pow(38))));
        assertEquals("1e-39", ValueFormatter.canonical(exact(BigInteger.ONE, BigInteger.TEN.pow(39))));
    }

    @Test
    void whenScientificAndPlainTextsAreAsLongThePlainOneIsKept() throws NumericException {
        // 40 significant digits followed by 4 zeros: 44 characters in plain notation, 44 in scientific notation.
        BigInteger significant = new BigInteger("1234567890123456789012345678901234567891");
        BigInteger value = significant.multiply(BigInteger.TEN.pow(4));

        assertEquals(value.toString(), ValueFormatter.canonical(exact(value, BigInteger.ONE)));
    }

    @Test
    void aNegativeExactValueKeepsItsSignInScientificCanonicalText() throws NumericException {
        assertEquals("-1e-39", ValueFormatter.canonical(exact(BigInteger.ONE.negate(), BigInteger.TEN.pow(39))));
        assertEquals("-1,5e-39", ValueFormatter.canonical(exact(BigInteger.valueOf(-15), BigInteger.TEN.pow(40))));
    }

    private static String text(CertifiedValue value, DisplayMode mode) {
        return ValueFormatter.format(value, mode).text();
    }

    private static CertifiedValue exact(BigInteger numerator, BigInteger denominator) throws NumericException {
        return CertifiedValue.exact(Rational.of(numerator, denominator));
    }

    private static CertifiedValue between(Rational lower, Rational upper) throws NumericException {
        return CertifiedValue.between(lower, upper);
    }

    // 10^exponent, exactly.
    private static CertifiedValue power(int exponent) throws NumericException {
        return exponent >= 0
                ? exact(BigInteger.TEN.pow(exponent), BigInteger.ONE)
                : exact(BigInteger.ONE, BigInteger.TEN.pow(-exponent));
    }

    // An interval whose midpoint is 1 / denominator, one thousandth wide on each side.
    private static CertifiedValue around(BigInteger denominator) throws NumericException {
        return around(Rational.of(BigInteger.ONE, denominator));
    }

    private static CertifiedValue around(Rational centre) throws NumericException {
        Rational delta = centre.abs().multiply(Rational.of(1, 1_000_000_000_000_000L));
        return between(centre.subtract(delta), centre.add(delta));
    }

    // [mantissa / 10^scale, mantissa / 10^scale + 10^-(scale + 2)]: the shortest decimal inside has exactly `scale` decimals.
    private static CertifiedValue narrow(BigInteger mantissa, int scale) throws NumericException {
        Rational lower = Rational.of(mantissa, BigInteger.TEN.pow(scale));
        return between(lower, lower.add(Rational.of(BigInteger.ONE, BigInteger.TEN.pow(scale + 2))));
    }
}
