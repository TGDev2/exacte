package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class RationalTest {
    private static final int MAX_BITS = NumericLimits.MAX_COMPONENT_BITS;

    @Test
    void factoriesReduceAndPutTheSignOnTheNumerator() throws NumericException {
        Rational value = Rational.of(6, -4);

        assertEquals(BigInteger.valueOf(-3), value.numerator());
        assertEquals(BigInteger.TWO, value.denominator());
        assertEquals("-3/2", value.toString());
        assertEquals(Rational.of(-3, 2), Rational.of(-6, 4));
        assertEquals(Rational.of(-3, 2), Rational.of(3, -2));
    }

    @Test
    void zeroIsAlwaysZeroOverOne() throws NumericException {
        for (Rational zero : new Rational[] {Rational.of(0, -5), Rational.of(0), Rational.of(3, 7).subtract(Rational.of(3, 7)),
                Rational.of(2, 3).multiply(Rational.ZERO), Rational.of(-1, 2).add(Rational.of(1, 2))}) {
            assertEquals(BigInteger.ZERO, zero.numerator());
            assertEquals(BigInteger.ONE, zero.denominator());
            assertEquals(Rational.ZERO, zero);
            assertEquals(0, zero.signum());
        }
    }

    @Test
    void aZeroDenominatorIsADivisionByZero() {
        assertEquals(NumericError.DIVISION_BY_ZERO, assertThrows(NumericException.class, () -> Rational.of(1, 0)).error());
        assertEquals(NumericError.DIVISION_BY_ZERO,
                assertThrows(NumericException.class, () -> Rational.of(3).divide(Rational.ZERO)).error());
        assertEquals(NumericError.DIVISION_BY_ZERO, assertThrows(NumericException.class, () -> Rational.ZERO.reciprocal()).error());
    }

    @Test
    void arithmeticIsExact() throws NumericException {
        Rational third = Rational.of(1, 3);

        assertEquals(Rational.ONE, third.add(third).add(third));
        assertEquals(Rational.ONE, Rational.ONE.divide(Rational.of(3)).multiply(Rational.of(3)));
        assertEquals(Rational.of(1, 6), Rational.of(1, 2).subtract(third));
        assertEquals(Rational.of(-3, 2), Rational.of(-2, 3).reciprocal());
        assertEquals(Rational.of(1, 3000000000000L), Rational.ONE.divide(Rational.of(3000000000000L)));
        assertEquals(Rational.of(7, 2), Rational.of(-7, 2).abs());
        assertEquals(Rational.of(7, 2), Rational.of(-7, 2).negate());
    }

    @Test
    void comparisonFollowsTheValue() throws NumericException {
        assertTrue(Rational.of(-1, 2).compareTo(Rational.of(1, 3)) < 0);
        assertTrue(Rational.of(2, 3).compareTo(Rational.of(3, 5)) > 0);
        assertTrue(Rational.of(-2, 3).compareTo(Rational.of(-3, 5)) < 0);
        assertEquals(0, Rational.of(2, 4).compareTo(Rational.of(1, 2)));
        assertEquals(Rational.of(2, 4).hashCode(), Rational.of(1, 2).hashCode());
        assertNotEquals(Rational.of(1, 2), Rational.of(-1, 2));
    }

    @Test
    void sizeIsTheLargerComponentInBits() throws NumericException {
        assertEquals(4, Rational.of(-8).bitLength());
        assertEquals(11, Rational.of(1, 1024).bitLength());
        assertEquals(0, Rational.ZERO.numerator().bitLength());
        assertEquals(1, Rational.ZERO.bitLength());
    }

    @Test
    void integersAreRecognised() throws NumericException {
        assertTrue(Rational.of(-12).isInteger());
        assertTrue(Rational.of(10, 5).isInteger());
        assertTrue(Rational.ZERO.isInteger());
        assertFalse(Rational.of(1, 2).isInteger());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "3/4; 0.75", "100; 100", "-7/40; -0.175", "1/1048576; 0.00000095367431640625", "1/125; 0.008", "0; 0",
        "-5; -5", "13/20; 0.65"
    })
    void terminatingDecimalsAreConvertedExactly(String fraction, String decimal) throws NumericException {
        Optional<BigDecimal> converted = Rational.parse(fraction).toExactDecimal();

        assertTrue(converted.isPresent());
        assertEquals(decimal, converted.get().toPlainString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/3", "2/7", "-1/6", "1/15", "7/1024000000001"})
    void otherDenominatorsHaveNoFiniteDecimal(String fraction) throws NumericException {
        assertEquals(Optional.empty(), Rational.parse(fraction).toExactDecimal());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
        "12; 12", "-0,5; -1/2", "+.5; 1/2", "3.; 3", "1.5E-3; 3/2000", "1,5e3; 1500", "-1,5/4e2; -3/800", "1/-2; -1/2",
        "007; 7", "0e9999; 0", "-0; 0", "1e+2; 100", "2/4; 1/2", "-,25; -1/4", "0.000; 0", "1E0005; 100000",
        "12.50e-1; 5/4", "6/0,4; 15", "-2/-3; 2/3"
    })
    void decimalsAndFractionsAreRead(String text, String expected) throws NumericException {
        assertEquals(expected, Rational.parse(text).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " 1", "1 ", "abc", ".", ",", "e5", "1e", "1.2.3", "1,2.3", "--1", "+-1", "1/2/3", "/2", "1/",
        "1_000", "١", "0x10", "1e2.5", "1e-", "NaN", "Infinity", "1/ 2", "1 000", " 1", "1e+-2", "+", "-"})
    void malformedTextsAreRefused(String text) {
        assertEquals(NumericError.MALFORMED_INPUT, assertThrows(NumericException.class, () -> Rational.parse(text)).error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1e10000", "1e-10000", "0e10000", "5,5E99999999999999999999", "1/1e10000"})
    void scientificExponentsBeyondTheBoundAreRefused(String text) {
        assertEquals(NumericError.EXPONENT_OUT_OF_RANGE, assertThrows(NumericException.class, () -> Rational.parse(text)).error());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1/0", "1/0,000", "5/-0e3", "0/0"})
    void aZeroDenominatorInALiteralIsADivisionByZero(String text) {
        assertEquals(NumericError.DIVISION_BY_ZERO, assertThrows(NumericException.class, () -> Rational.parse(text)).error());
    }

    @Test
    void literalsThatDoNotFitTheComponentLimitAreRefused() throws NumericException {
        // 10^1233 has 4096 bits, 10^1234 has 4100.
        assertEquals(MAX_BITS, Rational.parse("1e1233").bitLength());
        assertSize(() -> Rational.parse("1e1234"));
        assertSize(() -> Rational.parse("1e-4095"));
        assertSize(() -> Rational.parse("5e-4096"));
        assertSize(() -> Rational.parse("1e1234/1e1234"));
        assertSize(() -> Rational.parse("1".repeat(NumericLimits.MAX_LITERAL_LENGTH + 1)));
        assertSize(() -> Rational.parse("1".repeat(1300)));
        // Both members fit, so does 2^-4095 (4096-bit denominator); 2^4095 / 0,5 = 2^4096 needs 4097 bits.
        assertEquals(MAX_BITS, Rational.parse("1/" + BigInteger.ONE.shiftLeft(4095)).bitLength());
        assertSize(() -> Rational.parse(BigInteger.ONE.shiftLeft(4095) + "/0,5"));
    }

    @Test
    void powersAreExact() throws NumericException {
        assertEquals(Rational.of(8, 27), Rational.of(2, 3).pow(3));
        assertEquals(Rational.of(-8), Rational.of(-2).pow(3));
        assertEquals(Rational.of(16), Rational.of(-2).pow(4));
        assertEquals(Rational.of(9, 4), Rational.of(2, 3).pow(-2));
        assertEquals(Rational.of(-27, 8), Rational.of(-2, 3).pow(-3));
        assertEquals(Rational.ONE, Rational.ZERO.pow(0));
        assertEquals(Rational.ONE, Rational.of(5, 7).pow(0));
        assertEquals(Rational.ZERO, Rational.ZERO.pow(5));
        assertEquals(Rational.ONE, Rational.ONE.pow(NumericLimits.MAX_POWER_EXPONENT));
        assertEquals(Rational.of(-1), Rational.of(-1).pow(NumericLimits.MAX_POWER_EXPONENT - 1));
        assertEquals(NumericError.DIVISION_BY_ZERO, assertThrows(NumericException.class, () -> Rational.ZERO.pow(-1)).error());
    }

    @Test
    void powerExponentsBeyondTheBoundAreRefused() {
        for (int exponent : new int[] {NumericLimits.MAX_POWER_EXPONENT + 1, -NumericLimits.MAX_POWER_EXPONENT - 1,
                Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertEquals(NumericError.EXPONENT_OUT_OF_RANGE,
                    assertThrows(NumericException.class, () -> Rational.ONE.pow(exponent)).error());
        }
    }

    @Test
    void powersAreRefusedExactlyWhenTheResultExceedsTheLimit() throws NumericException {
        // 2^4095 has 4096 bits; 2^4096 is refused before computing ((2 - 1) * 4096 + 1 bits at least).
        assertEquals(MAX_BITS, Rational.of(2).pow(4095).bitLength());
        assertSize(() -> Rational.of(2).pow(4096));
        assertSize(() -> Rational.of(1, 2).pow(4096));
        // 3^2584 has 4096 bits and 3^2585 has 4098: the estimate cannot decide, the exact size does.
        assertEquals(MAX_BITS, Rational.of(3).pow(2584).bitLength());
        assertSize(() -> Rational.of(3).pow(2585));
        assertSize(() -> Rational.of(2, 3).pow(-2585));
    }

    @Test
    void theCanonicalTextIsReadBack() throws NumericException {
        for (String text : new String[] {"0", "-7", "22/7", "-1/3", "123456789012345678901234567891/1024"}) {
            Rational value = Rational.parse(text);
            assertEquals(text, value.toString());
            assertEquals(value, Rational.parse(value.toString()));
        }
    }

    @Test
    void aNullTextIsAProgrammingError() {
        assertThrows(NullPointerException.class, () -> Rational.parse(null));
    }

    @Test
    void sharedConstantsAreReturnedForTrivialOperations() throws NumericException {
        Rational value = Rational.of(5, 3);

        assertSame(value, value.add(Rational.ZERO));
        assertSame(value, Rational.ZERO.add(value));
        assertSame(Rational.ZERO, Rational.ZERO.negate());
        assertSame(Rational.ZERO, Rational.parse("-0,000e5"));
    }

    private static void assertSize(org.junit.jupiter.api.function.Executable executable) {
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, executable).error());
    }
}
