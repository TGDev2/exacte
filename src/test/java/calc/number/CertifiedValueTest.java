package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class CertifiedValueTest {
    private static final BigInteger TWO_POWER_4095 = BigInteger.ONE.shiftLeft(4095);

    @Test
    void oneThirdTimesThreeIsExactlyOne() throws NumericException {
        CertifiedValue result = value(1).divide(value(3)).multiply(value(3));

        assertTrue(result.isExact());
        assertEquals(Rational.ONE, result.lower());
        assertEquals(CertifiedValue.ONE, result);
    }

    @Test
    void theRootOfNineQuartersIsExactlyThreeHalves() throws NumericException {
        CertifiedValue root = CertifiedValue.exact(Rational.of(9, 4)).sqrt();

        assertTrue(root.isExact());
        assertEquals(Rational.of(3, 2), root.lower());
        assertEquals("3/2", root.toString());
    }

    @Test
    void theRootOfTwoIsCertifiedAtTheDefaultPrecision() throws NumericException {
        CertifiedValue root = value(2).sqrt();
        Rational lower = root.lower();
        Rational upper = root.upper();

        assertFalse(root.isExact());
        assertTrue(lower.multiply(lower).compareTo(Rational.of(2)) <= 0);
        assertTrue(upper.multiply(upper).compareTo(Rational.of(2)) >= 0);
        Rational allowedWidth = lower.multiply(Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(NumericLimits.DEFAULT_PRECISION_BITS)));
        assertTrue(upper.subtract(lower).compareTo(allowedWidth) <= 0);
        assertTrue(root.radius().signum() > 0);
        assertTrue(root.contains(root.midpoint()));
        assertTrue(OutwardRounding.fitsBudget(lower) && OutwardRounding.fitsBudget(upper));
    }

    @Test
    void aTinyQuotientIsExactAndNotZero() throws NumericException {
        CertifiedValue quotient = value(1).divide(value(3_000_000_000_000L));

        assertTrue(quotient.isExact());
        assertEquals(Rational.of(1, 3_000_000_000_000L), quotient.lower());
        assertTrue(quotient.lower().signum() > 0);
    }

    @Test
    void factoriesAndAccessorsDescribeTheInterval() throws NumericException {
        CertifiedValue interval = CertifiedValue.between(Rational.of(1), Rational.of(2));

        assertFalse(interval.isExact());
        assertEquals(Rational.of(3, 2), interval.midpoint());
        assertEquals(Rational.of(1, 2), interval.radius());
        assertTrue(interval.contains(Rational.of(1)) && interval.contains(Rational.of(2)) && interval.contains(Rational.of(7, 5)));
        assertFalse(interval.contains(Rational.of(5, 2)));
        assertEquals("[1, 2]", interval.toString());
        assertEquals(CertifiedValue.between(Rational.of(-2), Rational.of(-1)), interval.negate());
        assertTrue(CertifiedValue.between(Rational.of(5, 3), Rational.of(10, 6)).isExact());
        assertEquals(Rational.ZERO, value(7).radius());
        assertEquals(Rational.of(7), value(7).midpoint());
        assertThrows(IllegalArgumentException.class, () -> CertifiedValue.between(Rational.of(2), Rational.of(1)));
    }

    @Test
    void intervalArithmeticTakesTheExtremeBounds() throws NumericException {
        assertEquals(interval(-6, 8), interval(-1, 2).multiply(interval(-3, 4)));
        assertEquals(interval(2, 6), interval(1, 2).add(interval(1, 4)));
        assertEquals(interval(-3, 1), interval(1, 2).subtract(interval(1, 4)));
        assertEquals(CertifiedValue.between(Rational.of(1, 8), Rational.of(1, 2)), interval(1, 2).divide(interval(4, 8)));
        assertEquals(interval(-4, -2), interval(1, 2).multiply(value(-2)));
        assertEquals(CertifiedValue.ZERO, interval(-5, 7).multiply(CertifiedValue.ZERO));
    }

    @Test
    void approximateBoundsAreRoundedOutwardToTheBudget() throws NumericException {
        CertifiedValue result = interval(1, 2).divide(value(3));

        assertTrue(result.lower().compareTo(Rational.of(1, 3)) < 0);
        assertTrue(result.upper().compareTo(Rational.of(2, 3)) > 0);
        assertTrue(OutwardRounding.fitsBudget(result.lower()) && OutwardRounding.fitsBudget(result.upper()));
        Rational gap = Rational.of(1, 3).subtract(result.lower());
        assertTrue(gap.compareTo(Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(NumericLimits.BOUND_SIGNIFICANT_BITS))) < 0);
    }

    @Test
    void powersOfIntervalsFollowTheSignRules() throws NumericException {
        assertEquals(interval(0, 9), interval(-2, 3).pow(2));
        assertEquals(interval(0, 16), interval(-4, 3).pow(2));
        assertEquals(interval(4, 9), interval(-3, -2).pow(2));
        assertEquals(interval(-8, 27), interval(-2, 3).pow(3));
        assertEquals(interval(-27, -8), interval(-3, -2).pow(3));
        assertEquals(CertifiedValue.ONE, interval(-1, 1).pow(0));
        CertifiedValue inverse = interval(2, 3).pow(-1);
        assertTrue(inverse.contains(Rational.of(1, 3)) && inverse.contains(Rational.of(1, 2)));
        assertEquals(interval(1, 4), interval(-2, -1).pow(-2).pow(-1));
        assertEquals(CertifiedValue.exact(Rational.of(1, 8)), value(2).pow(value(-3)));
    }

    @Test
    void squareRootsOfIntervalsAreMonotone() throws NumericException {
        assertEquals(interval(2, 3), interval(4, 9).sqrt());
        assertEquals(interval(0, 3), interval(0, 9).sqrt());
        assertEquals(CertifiedValue.ZERO, CertifiedValue.ZERO.sqrt());
        // Found by CertifiedValueProperties.squareRootIsInclusive: the exact root 1/3 of a bound is beyond the budget.
        CertifiedValue roots = CertifiedValue.between(Rational.of(1, 9), Rational.of(1, 4)).sqrt();
        assertTrue(roots.contains(Rational.of(1, 3)) && roots.contains(Rational.of(1, 2)));
        assertTrue(OutwardRounding.fitsBudget(roots.lower()) && OutwardRounding.fitsBudget(roots.upper()));
        assertEquals(Rational.of(1, 2), roots.upper());
        assertThrows(IllegalArgumentException.class, () -> value(2).sqrt(0));
        assertThrows(IllegalArgumentException.class, () -> value(2).sqrt(NumericLimits.MAX_PRECISION_BITS + 1));
    }

    @Test
    void anExactResultBeyondTheLimitIsRefusedAndNeverRounded() throws NumericException {
        CertifiedValue huge = CertifiedValue.exact(Rational.of(TWO_POWER_4095));

        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> huge.multiply(value(2))).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> huge.add(huge)).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED,
                assertThrows(NumericException.class, () -> CertifiedValue.exact(Rational.of(TWO_POWER_4095.shiftLeft(1)))).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class,
                () -> CertifiedValue.between(Rational.ZERO, Rational.of(TWO_POWER_4095.shiftLeft(1)))).error());
    }

    @Test
    void anApproximateResultBeyondTheLimitIsRefused() throws NumericException {
        CertifiedValue huge = CertifiedValue.between(Rational.of(TWO_POWER_4095), Rational.of(TWO_POWER_4095.add(BigInteger.ONE)));

        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> huge.multiply(value(4))).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> huge.pow(2)).error());
    }

    @Test
    void aTinyApproximateProductStaysACorrectEnclosure() throws NumericException {
        Rational low = Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(4000));
        Rational high = Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(3999));
        CertifiedValue tiny = CertifiedValue.between(low, high);

        CertifiedValue square = tiny.multiply(tiny);

        assertTrue(square.contains(low.multiply(low)) && square.contains(high.multiply(high)));
        assertTrue(square.lower().bitLength() <= NumericLimits.MAX_COMPONENT_BITS);
        assertTrue(square.upper().bitLength() <= NumericLimits.MAX_COMPONENT_BITS);
        assertTrue(square.lower().signum() >= 0);
    }

    @Test
    void everyErrorCodeIsTriggered() throws NumericException {
        Map<NumericError, Executable> triggers = new EnumMap<>(NumericError.class);
        triggers.put(NumericError.DIVISION_BY_ZERO, () -> value(1).divide(CertifiedValue.ZERO));
        triggers.put(NumericError.DIVISOR_MAY_BE_ZERO, () -> value(1).divide(interval(-1, 1)));
        triggers.put(NumericError.ROOT_OF_NEGATIVE, () -> value(-1).sqrt());
        triggers.put(NumericError.ROOT_OF_POSSIBLY_NEGATIVE, () -> interval(-1, 1).sqrt());
        triggers.put(NumericError.EXPONENT_NOT_INTEGER, () -> value(2).pow(CertifiedValue.exact(Rational.of(1, 2))));
        triggers.put(NumericError.EXPONENT_NOT_EXACT, () -> value(2).pow(interval(1, 2)));
        triggers.put(NumericError.EXPONENT_OUT_OF_RANGE, () -> value(2).pow(value(NumericLimits.MAX_POWER_EXPONENT + 1)));
        triggers.put(NumericError.SIZE_LIMIT_EXCEEDED, () -> CertifiedValue.exact(Rational.of(TWO_POWER_4095)).pow(2));
        triggers.put(NumericError.MALFORMED_INPUT, () -> Rational.parse("1,2,3"));

        assertEquals(EnumSet.allOf(NumericError.class), triggers.keySet(), "every code needs a trigger");
        for (Map.Entry<NumericError, Executable> trigger : triggers.entrySet()) {
            assertEquals(trigger.getKey(), assertThrows(NumericException.class, trigger.getValue()).error());
        }
    }

    @Test
    void domainErrorsHaveTheirOwnCodesInEveryForm() throws NumericException {
        assertError(NumericError.DIVISION_BY_ZERO, () -> interval(1, 2).divide(CertifiedValue.ZERO));
        assertError(NumericError.DIVISION_BY_ZERO, () -> CertifiedValue.ZERO.pow(-1));
        assertError(NumericError.DIVISION_BY_ZERO, () -> CertifiedValue.ZERO.pow(value(-2)));
        assertError(NumericError.DIVISOR_MAY_BE_ZERO, () -> value(1).divide(interval(0, 1)));
        assertError(NumericError.DIVISOR_MAY_BE_ZERO, () -> value(1).divide(interval(-1, 0)));
        assertError(NumericError.DIVISOR_MAY_BE_ZERO, () -> interval(-1, 1).pow(-2));
        assertError(NumericError.DIVISOR_MAY_BE_ZERO, () -> interval(0, 1).pow(-1));
        assertError(NumericError.ROOT_OF_NEGATIVE, () -> interval(-3, -2).sqrt());
        assertError(NumericError.ROOT_OF_POSSIBLY_NEGATIVE, () -> interval(-1, 0).sqrt());
        assertError(NumericError.EXPONENT_OUT_OF_RANGE, () -> value(1).pow(value(-NumericLimits.MAX_POWER_EXPONENT - 1)));
        assertError(NumericError.EXPONENT_OUT_OF_RANGE,
                () -> value(1).pow(CertifiedValue.exact(Rational.of(BigInteger.TEN.pow(30)))));
        assertError(NumericError.EXPONENT_OUT_OF_RANGE, () -> interval(1, 2).pow(NumericLimits.MAX_POWER_EXPONENT + 1));
        assertError(NumericError.EXPONENT_OUT_OF_RANGE, () -> Rational.parse("1e10000"));
        assertError(NumericError.SIZE_LIMIT_EXCEEDED, () -> Rational.parse("9".repeat(NumericLimits.MAX_LITERAL_LENGTH + 1)));
        assertError(NumericError.EXPONENT_NOT_EXACT, () -> value(0).pow(interval(-1, 1)));
    }

    private static void assertError(NumericError expected, Executable executable) {
        assertEquals(expected, assertThrows(NumericException.class, executable).error());
    }

    private static CertifiedValue value(long number) throws NumericException {
        return CertifiedValue.exact(Rational.of(number));
    }

    private static CertifiedValue interval(long lower, long upper) throws NumericException {
        return CertifiedValue.between(Rational.of(lower), Rational.of(upper));
    }
}
