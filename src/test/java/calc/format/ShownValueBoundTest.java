package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A text that does not round the whole interval, « |x| ≤ b » or a rough centre, is read with its « ± r »: the whole interval must be
 * within r of it. Digits common to the whole interval are its correct rounding and keep the radius (ADR 0004, section 4).
 */
class ShownValueBoundTest {
    private static Optional<String> bound(Rational lower, Rational upper) throws NumericException {
        return ValueFormatter.format(CertifiedValue.between(lower, upper), DisplayMode.AUTO).errorBound();
    }

    private static String text(Rational lower, Rational upper) throws NumericException {
        return ValueFormatter.format(CertifiedValue.between(lower, upper), DisplayMode.AUTO).text();
    }

    @Test
    void aRoughCentreStaysInsideTheIntervalAndItsBoundHoldsTheInterval() throws NumericException {
        Rational lower = Rational.of(141728, 100000);
        Rational upper = Rational.of(158272, 100000);

        assertEquals("≈ 1,5…", text(lower, upper));
        assertEquals(Optional.of("± 8,3 × 10⁻²"), bound(lower, upper), "1,5 - 1,41728 = 0,08272");
    }

    @Test
    void aRoughCentreOffTheMiddleHasABoundToItsFartherSide() throws NumericException {
        Rational lower = Rational.of(145, 100);
        Rational upper = Rational.of(156, 100);

        assertEquals("≈ 1,5…", text(lower, upper));
        assertEquals(Optional.of("± 6,0 × 10⁻²"), bound(lower, upper), "1,56 - 1,5 = 0,06, not the radius 0,055");
    }

    @Test
    void anIntervalAroundZeroHasABoundFromZero() throws NumericException {
        assertEquals("|x| ≤ 1,1 × 10¹", text(Rational.of(-1), Rational.of(11)));
        assertEquals(Optional.of("± 1,1 × 10¹"), bound(Rational.of(-1), Rational.of(11)), "11 is 11 away from 0");
        assertEquals(Optional.of("± 1,2 × 10¹"), bound(Rational.ZERO, Rational.of(12)));
    }

    @Test
    void digitsCommonToTheWholeIntervalKeepTheRadius() throws NumericException {
        Rational lower = Rational.of(141421, 100000);
        Rational upper = Rational.of(141423, 100000);

        assertEquals("≈ 1,4142…", text(lower, upper));
        assertEquals(Optional.of("± 1,0 × 10⁻⁵"), bound(lower, upper));
    }
}
