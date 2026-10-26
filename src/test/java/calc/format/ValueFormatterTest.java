package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigInteger;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The four modes and the fields of {@link FormattedValue} on chosen values (docs/adr/0004-regles-affichage.md). */
class ValueFormatterTest {
    @Test
    void oneThirdInEveryMode() throws NumericException {
        CertifiedValue third = exact(1, 3);
        assertEquals("0,333333333333…", text(third, DisplayMode.AUTO));
        assertEquals("0,333333333333…", text(third, DisplayMode.DECIMAL));
        assertEquals("1/3", text(third, DisplayMode.FRACTION));
        assertEquals("3,33333333333… × 10⁻¹", text(third, DisplayMode.SCIENTIFIC));

        FormattedValue auto = ValueFormatter.format(third, DisplayMode.AUTO);
        assertTrue(auto.exact());
        assertTrue(auto.truncated(), "Digits are left out.");
        assertFalse(auto.notationSwitched());
        assertEquals(Optional.of("1/3"), auto.exactFraction());
        assertEquals(Optional.empty(), auto.errorBound());
        assertEquals("1/3", auto.canonical());
        assertFalse(ValueFormatter.format(third, DisplayMode.FRACTION).truncated(), "The fraction is the whole value.");
    }

    @Test
    void aShortDecimalIsShownAndReusedInFull() throws NumericException {
        FormattedValue value = ValueFormatter.format(exact(3, 10), DisplayMode.AUTO);
        assertEquals("0,3", value.text());
        assertFalse(value.truncated());
        assertEquals("0,3", value.canonical());
        assertEquals(Optional.of("3/10"), value.exactFraction());
        assertEquals(Optional.empty(), ValueFormatter.format(exact(5, 1), DisplayMode.AUTO).exactFraction(), "An integer has no fraction.");
        assertEquals("-0,666666666667…", text(exact(-2, 3), DisplayMode.AUTO));
    }

    @Test
    void decimalModeSwitchesWhenTheTextWouldReadAsZero() throws NumericException {
        CertifiedValue tiny = CertifiedValue.exact(Rational.of(BigInteger.ONE, BigInteger.TEN.pow(20)));
        FormattedValue decimal = ValueFormatter.format(tiny, DisplayMode.DECIMAL);
        assertEquals("1 × 10⁻²⁰", decimal.text());
        assertTrue(decimal.notationSwitched(), "Below 10^-15 the positional text would read as 0.");
        assertFalse(ValueFormatter.format(tiny, DisplayMode.AUTO).notationSwitched(), "Auto chooses the notation freely.");
        assertEquals("0,000000000000333333333333…", text(exact(1, 3_000_000_000_000L), DisplayMode.DECIMAL));
        assertFalse(ValueFormatter.format(exact(1, 3_000_000_000_000L), DisplayMode.DECIMAL).notationSwitched());
    }

    @Test
    void fractionModeFallsBackToAutoWhenTheFractionIsTooLong() throws NumericException {
        CertifiedValue small = CertifiedValue.exact(Rational.of(BigInteger.ONE, BigInteger.TWO.pow(200)));
        FormattedValue fraction = ValueFormatter.format(small, DisplayMode.FRACTION);
        assertTrue(fraction.notationSwitched(), "A fraction of 63 characters is replaced by the Auto text.");
        assertEquals(ValueFormatter.format(small, DisplayMode.AUTO).text(), fraction.text());
        assertEquals("1e1000", ValueFormatter.canonical(CertifiedValue.exact(Rational.of(BigInteger.TEN.pow(1000)))), "Shortest exact text.");
    }

    @Test
    void anApproximateValueShowsOnlyItsGuaranteedDigitsAndItsBound() throws NumericException {
        CertifiedValue root = exact(2, 1).sqrt();
        FormattedValue auto = ValueFormatter.format(root, DisplayMode.AUTO);
        assertEquals("≈ 1,41421356237…", auto.text());
        assertFalse(auto.exact());
        assertTrue(auto.truncated());
        assertEquals(Optional.of("± 2,1 × 10⁻²⁵"), auto.errorBound());
        assertEquals("1,414213562373095048801689", auto.canonical());
        assertEquals("≈ 1,41421356237… × 10⁰", text(root, DisplayMode.SCIENTIFIC));
        assertTrue(ValueFormatter.format(root, DisplayMode.FRACTION).notationSwitched(), "The centre is a long binary fraction.");

        CertifiedValue maybeZero = root.subtract(root);
        assertEquals("≈ 0", text(maybeZero, DisplayMode.AUTO), "An interval around 0 may really be 0.");
        assertEquals("0", ValueFormatter.canonical(maybeZero));
    }

    @Test
    void zeroIsZeroInEveryMode() {
        for (DisplayMode mode : DisplayMode.values()) {
            assertEquals("0", text(CertifiedValue.ZERO, mode), mode.name());
        }
    }

    private static String text(CertifiedValue value, DisplayMode mode) {
        return ValueFormatter.format(value, mode).text();
    }

    private static CertifiedValue exact(long numerator, long denominator) throws NumericException {
        return CertifiedValue.exact(Rational.of(numerator, denominator));
    }
}
