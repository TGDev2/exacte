package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationException;
import calc.engine.CalculationLimits;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import calc.testing.DisplayTexts;
import calc.testing.RationalGenerators;
import calc.testing.RationalGenerators.Enclosure;
import java.math.BigDecimal;
import java.math.RoundingMode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The promises of the display rules (docs/adr/0004-regles-affichage.md) for any value of the core: a value that is not
 * zero never reads as 0, whatever the mode; the canonical text of an exact value reads back to it exactly; the digits
 * shown for an approximate value are the rounding of every point of its interval.
 */
@Tag("property")
class FormatterProperties {
    // Values up to 4000 bits per component: from 2^-4000 to 2^4000, within the 4096-bit limit of the core. Intervals are
    // built around a point by adding and subtracting offsets, so their points have half as many bits.
    private static final int MAX_BITS = 4000;

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<CertifiedValue> nonZeroValues() {
        Arbitrary<CertifiedValue> exact = RationalGenerators.rationals(MAX_BITS)
                .filter(value -> value.signum() != 0)
                .map(FormatterProperties::exact);
        Arbitrary<CertifiedValue> approximate = approximateValues();
        return Arbitraries.oneOf(exact, approximate);
    }

    @Provide
    Arbitrary<CertifiedValue> approximateValues() {
        return RationalGenerators.enclosures(MAX_BITS / 2)
                .map(Enclosure::interval)
                .filter(value -> !value.isExact() && value.lower().signum() * value.upper().signum() > 0);
    }

    @Provide
    Arbitrary<Enclosure> approximateEnclosures() {
        return RationalGenerators.enclosures(400)
                .filter(enclosure -> !enclosure.interval().isExact()
                        && enclosure.interval().lower().signum() * enclosure.interval().upper().signum() > 0);
    }

    @Provide
    Arbitrary<Rational> exactValues() {
        return RationalGenerators.rationals(MAX_BITS);
    }

    @Property
    void aValueThatIsNotZeroIsNeverShownAsZero(@ForAll("nonZeroValues") CertifiedValue value, @ForAll DisplayMode mode) {
        FormattedValue formatted = ValueFormatter.format(value, mode);
        assertNotEquals(0, DisplayTexts.value(formatted.text()).signum(), () -> mode + " shows " + formatted.text() + " for " + value);
        assertEquals(value.isExact(), formatted.exact());
        assertEquals(!value.isExact(), DisplayTexts.isApproximate(formatted.text()), () -> "« ≈ » marks exactly the approximate values: " + formatted.text());
    }

    @Property
    void theCanonicalTextOfAnExactValueReadsBackExactly(@ForAll("exactValues") Rational value) throws CalculationException {
        String canonical = ValueFormatter.format(exact(value), DisplayMode.AUTO).canonical();
        assertTrue(canonical.length() <= CalculationLimits.MAX_INPUT_LENGTH, () -> "The canonical text fits the input: " + canonical.length());
        CertifiedValue readBack = engine.calculate(Operation.ADDITION, canonical, "0").getResult();
        assertEquals(exact(value), readBack, () -> canonical + " should read back to " + value);
    }

    @Property
    void theCanonicalTextOfAnApproximateValueLiesInItsInterval(@ForAll("approximateValues") CertifiedValue value) throws CalculationException {
        String canonical = ValueFormatter.canonical(value);
        CertifiedValue readBack = engine.calculate(Operation.ADDITION, canonical, "0").getResult();
        assertTrue(value.contains(readBack.lower()), () -> canonical + " should lie in " + value);
    }

    /**
     * Every point of the interval rounds to the digits shown, so they are the correct rounding of the true value; only
     * when the bounds share no digit at all may the text fall back to the centre, and that number stays inside the interval.
     */
    @Property
    void theDigitsOfAnApproximateValueAreGuaranteed(@ForAll("approximateEnclosures") Enclosure enclosure, @ForAll DisplayMode mode)
            throws NumericException {
        String text = ValueFormatter.format(enclosure.interval(), mode == DisplayMode.FRACTION ? DisplayMode.AUTO : mode).text();
        BigDecimal shown = DisplayTexts.decimal(text);
        int scale = shown.scale();
        BigDecimal lower = round(enclosure.interval().lower(), scale);
        BigDecimal upper = round(enclosure.interval().upper(), scale);
        if (lower.compareTo(upper) != 0) {
            assertTrue(enclosure.interval().contains(Rational.parse(shown.toPlainString())),
                    () -> "The bounds of " + enclosure.interval() + " disagree at the last digit of " + text + ", which is outside");
            return;
        }
        assertEquals(0, lower.compareTo(shown), () -> text + " for " + enclosure.interval());
        assertEquals(0, round(enclosure.point(), scale).compareTo(shown), () -> text + " for the point " + enclosure.point());
        assertTrue(shown.precision() <= ValueFormatter.SIGNIFICANT_DIGITS || shown.scale() <= 0, () -> "At most 12 digits: " + text);
    }

    private static BigDecimal round(Rational value, int scale) {
        return new BigDecimal(value.numerator()).divide(new BigDecimal(value.denominator()), scale, RoundingMode.HALF_UP);
    }

    private static CertifiedValue exact(Rational value) {
        try {
            return CertifiedValue.exact(value);
        } catch (NumericException tooLarge) {
            throw new IllegalStateException("generated a value beyond the core limits", tooLarge);
        }
    }
}
