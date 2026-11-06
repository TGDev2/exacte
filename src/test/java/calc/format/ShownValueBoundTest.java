package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** A text and its « ± r » are read together: whatever the digits shown, the whole interval must be within r of them. */
class ShownValueBoundTest {
    private static Optional<String> bound(Rational lower, Rational upper) throws NumericException {
        return ValueFormatter.format(CertifiedValue.between(lower, upper), DisplayMode.AUTO).errorBound();
    }

    private static String text(Rational lower, Rational upper) throws NumericException {
        return ValueFormatter.format(CertifiedValue.between(lower, upper), DisplayMode.AUTO).text();
    }

    @Test
    void aRoughCentreRoundedAwayFromTheIntervalHasABoundThatStillHoldsTheInterval() throws NumericException {
        Rational lower = Rational.of(141728, 100000);
        Rational upper = Rational.of(158272, 100000);

        assertEquals("≈ 2…", text(lower, upper));
        assertEquals(Optional.of("± 5,9 × 10⁻¹"), bound(lower, upper), "2 - 1,41728 = 0,58272, not the radius 0,083");
    }

    @Test
    void anIntervalShownAsZeroHasABoundFromZero() throws NumericException {
        assertEquals("≈ 0", text(Rational.of(-1), Rational.of(11)));
        assertEquals(Optional.of("± 1,1 × 10¹"), bound(Rational.of(-1), Rational.of(11)), "11 is 11 away from the 0 shown");
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
