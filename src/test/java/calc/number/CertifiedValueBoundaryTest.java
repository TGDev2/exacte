package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CertifiedValueBoundaryTest {
    @Test
    void theSmallestAndTheLargestPrecisionAreAccepted() throws NumericException {
        CertifiedValue coarse = CertifiedValue.exact(Rational.of(2)).sqrt(1);
        CertifiedValue fine = CertifiedValue.exact(Rational.of(2)).sqrt(NumericLimits.MAX_PRECISION_BITS);

        assertTrue(coarse.lower().multiply(coarse.lower()).compareTo(Rational.of(2)) <= 0);
        assertTrue(coarse.upper().multiply(coarse.upper()).compareTo(Rational.of(2)) >= 0);
        assertTrue(fine.lower().multiply(fine.lower()).compareTo(Rational.of(2)) <= 0);
        assertTrue(fine.upper().multiply(fine.upper()).compareTo(Rational.of(2)) >= 0);
        assertTrue(fine.upper().subtract(fine.lower()).compareTo(coarse.upper().subtract(coarse.lower())) < 0);
    }

    @Test
    void valuesWithDifferentBoundsAreNotEqual() throws NumericException {
        CertifiedValue interval = CertifiedValue.between(Rational.ZERO, Rational.ONE);

        assertNotEquals(interval, CertifiedValue.between(Rational.ZERO, Rational.of(2)));
        assertNotEquals(interval, CertifiedValue.between(Rational.of(-1), Rational.ONE));
        assertNotEquals(interval, CertifiedValue.ONE);
        assertNotEquals(interval, "[0, 1]");
        assertNotEquals(interval, null);
        assertEquals(interval, CertifiedValue.between(Rational.ZERO, Rational.ONE));
    }

    @Test
    void equalValuesHaveTheSameHashCode() throws NumericException {
        assertEquals(CertifiedValue.exact(Rational.of(1, 2)).hashCode(), CertifiedValue.exact(Rational.of(2, 4)).hashCode());
        assertEquals(CertifiedValue.between(Rational.ZERO, Rational.ONE).hashCode(),
                CertifiedValue.between(Rational.of(0, 5), Rational.of(3, 3)).hashCode());
    }

    @Test
    void anApproximateValueDividedByAnExactOneStaysAnInterval() throws NumericException {
        CertifiedValue interval = CertifiedValue.between(Rational.of(2), Rational.of(4));

        assertEquals(CertifiedValue.between(Rational.ONE, Rational.of(2)), interval.divide(CertifiedValue.exact(Rational.of(2))));
        assertEquals(CertifiedValue.between(Rational.of(1, 4), Rational.of(1, 2)), CertifiedValue.ONE.divide(interval));
    }
}
