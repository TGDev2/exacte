package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class OutwardRoundingTest {
    private static final BigInteger TWO = BigInteger.TWO;

    @Test
    void aBoundFitsTheBudgetWhenItIsADyadicNumberOf128SignificantBitsAtMost() throws NumericException {
        BigInteger largestMantissa = TWO.pow(128).subtract(BigInteger.ONE);

        assertTrue(OutwardRounding.fitsBudget(Rational.ZERO));
        assertTrue(OutwardRounding.fitsBudget(Rational.of(largestMantissa)));
        assertTrue(OutwardRounding.fitsBudget(Rational.of(largestMantissa.negate(), TWO.pow(500))));
        assertTrue(OutwardRounding.fitsBudget(Rational.of(TWO.pow(300))));
        assertTrue(OutwardRounding.fitsBudget(Rational.of(3, 1024)));
        assertFalse(OutwardRounding.fitsBudget(Rational.of(largestMantissa.add(TWO))));
        assertFalse(OutwardRounding.fitsBudget(Rational.of(TWO.pow(128).add(BigInteger.ONE), TWO.pow(10))));
        assertFalse(OutwardRounding.fitsBudget(Rational.of(1, 3)));
    }

    @Test
    void floorLog2IsTheExponentOfTheLargestPowerOfTwoNotAboveTheMagnitude() throws NumericException {
        long[][] cases = {
                // numerator, denominator, expected
                {1, 1, 0}, {2, 1, 1}, {3, 1, 1}, {4, 1, 2}, {5, 1, 2}, {7, 1, 2}, {8, 1, 3}, {-8, 1, 3}, {9, 1, 3},
                {3, 2, 0}, {5, 3, 0}, {4, 3, 0}, {7, 4, 0}, {9, 8, 0},
                {1, 2, -1}, {3, 4, -1}, {7, 8, -1}, {5, 8, -1}, {1, 3, -2}, {1, 4, -2}, {1, 8, -3}, {-1, 8, -3}, {3, 16, -3},
                {1, 5, -3}, {1, 7, -3}, {2, 3, -1}};
        for (long[] c : cases) {
            assertEquals(c[2], OutwardRounding.floorLog2(Rational.of(c[0], c[1])), c[0] + "/" + c[1]);
        }
    }

    @Test
    void floorLog2IsExactAroundHugePowersOfTwo() throws NumericException {
        BigInteger power = TWO.pow(3000);

        assertEquals(3000, OutwardRounding.floorLog2(Rational.of(power)));
        assertEquals(2999, OutwardRounding.floorLog2(Rational.of(power.subtract(BigInteger.ONE))));
        assertEquals(-3000, OutwardRounding.floorLog2(Rational.of(BigInteger.ONE, power)));
        assertEquals(-3001, OutwardRounding.floorLog2(Rational.of(BigInteger.ONE, power.add(BigInteger.ONE))));
    }

    @Test
    void aBoundWithinTheBudgetIsKeptAsItIs() throws NumericException {
        Rational dyadic = Rational.of(-5, 64);

        assertEquals(dyadic, OutwardRounding.down(dyadic));
        assertEquals(dyadic, OutwardRounding.up(dyadic));
        assertEquals(Rational.ZERO, OutwardRounding.up(Rational.ZERO));
    }

    @Test
    void aBoundBeyondTheBudgetIsRoundedOutwardOntoTheGrid() throws NumericException {
        for (Rational value : new Rational[] {Rational.of(1, 3), Rational.of(-1, 3), Rational.of(22, 7), Rational.of(-22, 7),
                Rational.of(TWO.pow(300).add(BigInteger.ONE), BigInteger.valueOf(3))}) {
            Rational down = OutwardRounding.down(value);
            Rational up = OutwardRounding.up(value);

            assertTrue(down.compareTo(value) < 0, "down " + value);
            assertTrue(up.compareTo(value) > 0, "up " + value);
            assertTrue(OutwardRounding.fitsBudget(down) && OutwardRounding.fitsBudget(up), "budget " + value);
            Rational width = up.subtract(down);
            assertTrue(width.compareTo(value.abs().multiply(Rational.of(BigInteger.ONE, TWO.pow(126)))) <= 0, "width " + value);
        }
    }

    @Test
    void theUpperRootOfAValueJustAboveASquareIsNotTheSquareRoot() throws NumericException {
        // 1 + 2^-200 is not a perfect square; floor(T) is one, whose root is exact, but the bound must stay above.
        Rational value = Rational.of(TWO.pow(200).add(BigInteger.ONE), TWO.pow(200));

        Rational upper = OutwardRounding.sqrtBound(value, 80, true);
        Rational lower = OutwardRounding.sqrtBound(value, 80, false);

        assertTrue(upper.multiply(upper).compareTo(value) >= 0);
        assertTrue(lower.multiply(lower).compareTo(value) <= 0);
        assertTrue(upper.compareTo(Rational.ONE) > 0);
    }

    @Test
    void rootsOfValuesOfAnyMagnitudeAreCertifiedAndTight() throws NumericException {
        BigInteger huge = TWO.pow(700).add(BigInteger.valueOf(3));
        Rational[] values = {Rational.of(2), Rational.of(3, 7), Rational.of(1, 1000003), Rational.of(huge),
                Rational.of(BigInteger.ONE, huge), Rational.of(huge, BigInteger.valueOf(11)),
                Rational.of(BigInteger.TEN.pow(100).multiply(TWO))};
        for (Rational value : values) {
            Rational lower = OutwardRounding.sqrtBound(value, 80, false);
            Rational upper = OutwardRounding.sqrtBound(value, 80, true);

            assertTrue(lower.multiply(lower).compareTo(value) <= 0, "lower " + value);
            assertTrue(upper.multiply(upper).compareTo(value) >= 0, "upper " + value);
            assertTrue(upper.subtract(lower).compareTo(lower.multiply(Rational.of(BigInteger.ONE, TWO.pow(80)))) <= 0, "width " + value);
        }
    }

    @Test
    void theRootOfAPerfectSquareIsExactOnBothSides() throws NumericException {
        Rational square = Rational.of(49, 9);

        assertEquals(Rational.of(7, 3), OutwardRounding.sqrtBound(square, 80, false));
        assertEquals(Rational.of(7, 3), OutwardRounding.sqrtBound(square, 80, true));
        assertEquals(Rational.ZERO, OutwardRounding.sqrtBound(Rational.ZERO, 80, true));
    }

    @Test
    void powerBoundsEncloseTheExactPower() throws NumericException {
        Rational third = Rational.of(1, 3);
        Rational exact = third.pow(9);

        assertTrue(OutwardRounding.powBound(third, 9, false).compareTo(exact) <= 0);
        assertTrue(OutwardRounding.powBound(third, 9, true).compareTo(exact) >= 0);
        assertEquals(Rational.ONE, OutwardRounding.powBound(Rational.ONE, 1, true));
    }
}
