package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The exact edges of the size and exponent limits of {@link Rational}: the last value accepted and the first refused. */
class RationalBoundaryTest {
    private static final int MAX_BITS = NumericLimits.MAX_COMPONENT_BITS;

    @Test
    void leadingZerosOfTheExponentAreIgnored() throws NumericException {
        assertEquals(Rational.ONE, Rational.parse("1e0"));
        assertEquals(Rational.ONE, Rational.parse("1e000"));
        assertEquals(Rational.ONE, Rational.parse("1e-0"));
        assertEquals(Rational.of(100_000), Rational.parse("1e00000000005"));
        assertEquals(Rational.of(1, 100_000), Rational.parse("1e-000000000005"));
    }

    @Test
    void theLargestScientificExponentIsAcceptedAndTheNextOneIsNot() throws NumericException {
        assertEquals(Rational.ZERO, Rational.parse("0e9999"));
        assertEquals(Rational.ZERO, Rational.parse("0e-9999"));
        assertEquals(NumericError.EXPONENT_OUT_OF_RANGE,
                assertThrows(NumericException.class, () -> Rational.parse("0e10000")).error());
        assertEquals(NumericError.EXPONENT_OUT_OF_RANGE,
                assertThrows(NumericException.class, () -> Rational.parse("0e-10000")).error());
    }

    @Test
    void aDecimalOfExactlyTheMaximumSizeIsAccepted() throws NumericException {
        Rational large = Rational.parse("1e1233");
        Rational small = Rational.parse("1e-1233");

        assertEquals(MAX_BITS, large.bitLength());
        assertEquals(MAX_BITS, small.bitLength());
        assertEquals(BigInteger.TEN.pow(1233), large.numerator());
        assertEquals(BigInteger.TEN.pow(1233), small.denominator());
    }

    @Test
    void aDecimalOneDigitBeyondTheMaximumSizeIsRefused() {
        for (String text : new String[] {"1e1234", "1e-1234", "10e1233", "5e-1234"}) {
            assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> Rational.parse(text)).error(), text);
        }
    }

    @Test
    void aMantissaWithSeveralDigitsKeepsTheLimitOnTheNegativePowerSide() throws NumericException {
        // 12 / 10^1233 reduces to 3 / (2^1231 * 5^1233): it fits, although 2 digits + 1233 exceeds the digit count of 1e1233.
        Rational value = Rational.parse("12e-1233");

        assertEquals(BigInteger.valueOf(3), value.numerator());
        assertEquals(BigInteger.TWO.pow(1231).multiply(BigInteger.valueOf(5).pow(1233)), value.denominator());
    }

    @Test
    void aPowerWhoseResultHasExactlyTheMaximumSizeIsComputed() throws NumericException {
        BigInteger base = BigInteger.ONE.shiftLeft(63);

        assertEquals(BigInteger.ONE.shiftLeft(4095), Rational.of(base).pow(65).numerator());
        assertEquals(BigInteger.ONE.shiftLeft(4095), Rational.of(BigInteger.ONE, base).pow(65).denominator());
        assertEquals(BigInteger.ONE.shiftLeft(4095), Rational.of(BigInteger.ONE, base).pow(-65).numerator());
        assertEquals(MAX_BITS, Rational.of(base.negate()).pow(65).bitLength());
    }

    @Test
    void aPowerWhoseResultNeedsOneBitMoreIsRefused() throws NumericException {
        BigInteger base = BigInteger.ONE.shiftLeft(63);

        for (Rational value : new Rational[] {Rational.of(base), Rational.of(BigInteger.ONE, base)}) {
            assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> value.pow(66)).error());
        }
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED,
                assertThrows(NumericException.class, () -> Rational.of(BigInteger.ONE.shiftLeft(64)).pow(64)).error());
    }

    @Test
    void theLargestPowerExponentIsAcceptedForTheUnitBases() throws NumericException {
        int max = NumericLimits.MAX_POWER_EXPONENT;

        assertEquals(Rational.ONE, Rational.ONE.pow(max));
        assertEquals(Rational.ONE, Rational.of(-1).pow(max));
        assertEquals(Rational.ONE, Rational.of(-1).pow(-max));
        assertEquals(Rational.ZERO, Rational.ZERO.pow(max));
        assertEquals(NumericError.EXPONENT_OUT_OF_RANGE, assertThrows(NumericException.class, () -> Rational.ONE.pow(max + 1)).error());
        assertEquals(NumericError.EXPONENT_OUT_OF_RANGE, assertThrows(NumericException.class, () -> Rational.ONE.pow(-max - 1)).error());
    }

    @Test
    void equalValuesFormASingleHashSetEntry() throws NumericException {
        Set<Rational> values = new HashSet<>();
        values.add(Rational.of(2, 4));
        values.add(Rational.of(1, 2));
        values.add(Rational.parse("0,5"));
        values.add(Rational.of(1, 3));

        assertEquals(2, values.size());
    }
}
