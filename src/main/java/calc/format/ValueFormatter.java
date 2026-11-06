package calc.format;

import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * The single place where a value becomes text. The rules are decided in docs/adr/0004-regles-affichage.md:
 *
 * <ul>
 *   <li>at most {@link #SIGNIFICANT_DIGITS} significant digits, rounded {@code HALF_UP} on the magnitude and decided
 *       exactly; « … » when the value has more digits than shown;</li>
 *   <li>an approximate value shows « ≈ » and only the digits on which its whole interval agrees, so they are the
 *       correct rounding of the true value; « ≈ 0 » only when the interval contains 0;</li>
 *   <li>a value that is not zero is never shown as a text that reads 0;</li>
 *   <li>the canonical text is read back by the input grammar and gives back an exact value exactly.</li>
 * </ul>
 */
public final class ValueFormatter {
    /** Precision of the display. */
    public static final int SIGNIFICANT_DIGITS = 12;

    // Auto mode: positional notation for 10^-6 <= |x| < 10^15.
    private static final int AUTO_SMALLEST_EXPONENT = -6;
    private static final int AUTO_LARGEST_EXPONENT = 14;
    // Decimal mode: below 10^-15 the positional text starts with more than 15 zeros and reads as 0 once shortened.
    private static final int DECIMAL_SMALLEST_EXPONENT = -15;
    private static final int MAX_FRACTION_LENGTH = 40;
    private static final int MAX_SHORT_DECIMAL_LENGTH = 40;
    private static final int ERROR_BOUND_DIGITS = 2;

    private static final String APPROXIMATE_PREFIX = "≈ ";
    private static final String ELLIPSIS = "…";
    private static final String TIMES_TEN = " × 10";
    private static final String SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹";
    private static final char SUPERSCRIPT_MINUS = '⁻';

    private ValueFormatter() {
    }

    /** The value shown in {@code mode}, with its exact fraction, error bound and canonical text. */
    public static FormattedValue format(CertifiedValue value, DisplayMode mode) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(mode, "mode");
        Shown shown = switch (mode) {
            case AUTO -> auto(value, false);
            case DECIMAL -> decimal(value);
            case FRACTION -> fraction(value);
            case SCIENTIFIC -> scientific(value);
        };
        boolean exact = value.isExact();
        Optional<String> exactFraction = exact && !value.lower().isInteger()
                ? Optional.of(value.lower().toString())
                : Optional.empty();
        Optional<String> errorBound = exact ? Optional.empty() : Optional.of("± " + errorBound(errorFromText(value)));
        return new FormattedValue(shown.text(), exactFraction, exact, shown.truncated(), shown.switched(), errorBound,
                canonical(value));
    }

    /**
     * The text to put back in an input field: an exact value is written so that it reads back exactly (short finite
     * decimal, otherwise the shortest of decimal, exact scientific notation and fraction); an approximate value is
     * written as the decimal with the fewest significant digits inside its interval ({@code 0} if it contains 0).
     */
    public static String canonical(CertifiedValue value) {
        Objects.requireNonNull(value, "value");
        if (value.isExact()) {
            return canonicalExact(value.lower());
        }
        if (containsZero(value)) {
            return "0";
        }
        Rational centre = value.midpoint();
        int exponent = exponent(centre);
        for (int digits = 1; ; digits++) {
            BigDecimal candidate = round(centre, digits - 1 - exponent);
            if (compare(candidate, value.lower()) >= 0 && compare(candidate, value.upper()) <= 0) {
                String plain = plain(candidate.stripTrailingZeros());
                return plain.length() <= MAX_SHORT_DECIMAL_LENGTH ? plain : exactScientific(candidate);
            }
        }
    }

    private static String canonicalExact(Rational value) {
        String fraction = value.toString();
        Optional<BigDecimal> decimal = value.toExactDecimal();
        if (decimal.isEmpty()) {
            return fraction;
        }
        String plain = plain(decimal.get());
        if (plain.length() <= MAX_SHORT_DECIMAL_LENGTH) {
            return plain;
        }
        String scientific = exactScientific(decimal.get());
        String shortest = scientific.length() < plain.length() ? scientific : plain;
        return fraction.length() < shortest.length() ? fraction : shortest;
    }

    private record Shown(String text, boolean truncated, boolean switched) {
        Shown switchedNotation() {
            return new Shown(text, truncated, true);
        }
    }

    // The digits to show: {@code rounded} carries the sign and its scale is the position of the last digit shown.
    private record Digits(BigDecimal rounded, boolean complete, boolean approximate) {
    }

    private static Shown auto(CertifiedValue value, boolean switched) {
        if (isZeroText(value)) {
            return zero(value, switched);
        }
        int exponent = magnitudeExponent(value);
        if (exponent >= AUTO_SMALLEST_EXPONENT && exponent <= AUTO_LARGEST_EXPONENT) {
            Digits digits = positionalDigits(value);
            if (digits != null) {
                return new Shown(positional(digits), !digits.complete(), switched);
            }
        }
        Digits digits = scientificDigits(value);
        return new Shown(scientific(digits), !digits.complete(), switched);
    }

    private static Shown decimal(CertifiedValue value) {
        if (isZeroText(value)) {
            return zero(value, false);
        }
        if (magnitudeExponent(value) >= DECIMAL_SMALLEST_EXPONENT) {
            Digits digits = positionalDigits(value);
            if (digits != null) {
                return new Shown(positional(digits), !digits.complete(), false);
            }
        }
        Digits digits = scientificDigits(value);
        return new Shown(scientific(digits), !digits.complete(), true);
    }

    private static Shown fraction(CertifiedValue value) {
        if (value.isExact()) {
            String fraction = value.lower().toString();
            return fraction.length() <= MAX_FRACTION_LENGTH ? new Shown(fraction, false, false) : auto(value, true);
        }
        String centre = value.midpoint().toString();
        return centre.length() <= MAX_FRACTION_LENGTH
                ? new Shown(APPROXIMATE_PREFIX + centre, true, false)
                : auto(value, true);
    }

    private static Shown scientific(CertifiedValue value) {
        if (isZeroText(value)) {
            return zero(value, false);
        }
        Digits digits = scientificDigits(value);
        return new Shown(scientific(digits), !digits.complete(), false);
    }

    // Exact zero, or an approximate value that may be zero: the only values shown as 0.
    private static boolean isZeroText(CertifiedValue value) {
        return value.isExact() ? value.lower().signum() == 0 : containsZero(value);
    }

    private static Shown zero(CertifiedValue value, boolean switched) {
        return value.isExact() ? new Shown("0", false, switched) : new Shown(APPROXIMATE_PREFIX + "0", true, switched);
    }

    private static boolean containsZero(CertifiedValue value) {
        return value.lower().signum() <= 0 && value.upper().signum() >= 0;
    }

    // Exponent of the first significant digit of the value, or of the bound of largest magnitude.
    private static int magnitudeExponent(CertifiedValue value) {
        Rational largest = value.lower().abs().compareTo(value.upper().abs()) >= 0 ? value.lower() : value.upper();
        return exponent(largest);
    }

    // Positional notation never cuts the integer part: null when an approximate value does not guarantee the units.
    private static Digits positionalDigits(CertifiedValue value) {
        if (value.isExact()) {
            Rational exact = value.lower();
            int exponent = exponent(exact);
            // The whole integer part, or 12 significant digits.
            int digits = Math.max(SIGNIFICANT_DIGITS, exponent + 1);
            return exactDigits(exact, digits - 1 - exponent, digits);
        }
        Digits digits = approximateDigits(value);
        return digits.rounded().scale() >= 0 ? digits : null;
    }

    private static Digits scientificDigits(CertifiedValue value) {
        if (value.isExact()) {
            Rational exact = value.lower();
            return exactDigits(exact, SIGNIFICANT_DIGITS - 1 - exponent(exact), SIGNIFICANT_DIGITS);
        }
        return approximateDigits(value);
    }

    private static Digits exactDigits(Rational value, int scale, int digits) {
        BigDecimal rounded = round(value, scale);
        boolean complete = compare(rounded, value) == 0;
        return new Digits(complete ? rounded : withoutCarryDigit(rounded, digits), complete, false);
    }

    /**
     * The largest number of digits (at most 12) at which both bounds round to the same number: since rounding at a
     * fixed position is monotone, every point of the interval rounds to it. When no digit is common, the centre with
     * one significant digit.
     */
    private static Digits approximateDigits(CertifiedValue value) {
        int digits = commonDigits(value);
        if (digits > 0) {
            return new Digits(withoutCarryDigit(round(value.lower(), digits - 1 - magnitudeExponent(value)), digits), false, true);
        }
        return new Digits(withoutCarryDigit(roughCentre(value), 1), false, true);
    }

    // The number of significant digits on which the whole interval agrees, 0 if there is none.
    private static int commonDigits(CertifiedValue value) {
        int exponent = magnitudeExponent(value);
        for (int digits = SIGNIFICANT_DIGITS; digits >= 1; digits--) {
            int scale = digits - 1 - exponent;
            if (round(value.lower(), scale).compareTo(round(value.upper(), scale)) == 0) {
                return digits;
            }
        }
        return 0;
    }

    private static BigDecimal roughCentre(CertifiedValue value) {
        Rational centre = value.midpoint();
        return round(centre, -exponent(centre));
    }

    /**
     * What « ± r » has to cover. Digits common to the whole interval are its correct rounding and r is its radius; but a
     * text that reads 0 or a rough centre may lie far from the centre, and r then runs from that text to the farther bound.
     */
    private static Rational errorFromText(CertifiedValue value) {
        if (!containsZero(value) && commonDigits(value) > 0) {
            return value.radius();
        }
        Rational shown = containsZero(value) ? Rational.ZERO : exactly(roughCentre(value));
        Rational toLower = shown.subtract(value.lower()).abs();
        Rational toUpper = value.upper().subtract(shown).abs();
        return toLower.compareTo(toUpper) >= 0 ? toLower : toUpper;
    }

    private static Rational exactly(BigDecimal decimal) {
        try {
            return Rational.parse(decimal.toPlainString());
        } catch (NumericException exception) {
            throw new IllegalStateException("A rounded value of the formatter cannot be read back: " + decimal, exception);
        }
    }

    // 9,9996 rounded to four digits is 10,000: one digit too many, and the last one is a 0 that can be dropped exactly
    // (the scale may become negative: 9999,6 rounded to four digits is 1,000 x 10^4).
    private static BigDecimal withoutCarryDigit(BigDecimal rounded, int digits) {
        if (rounded.precision() > digits) {
            return rounded.setScale(rounded.scale() - 1, RoundingMode.UNNECESSARY);
        }
        return rounded;
    }

    private static String positional(Digits digits) {
        BigDecimal shown = digits.complete() ? digits.rounded().stripTrailingZeros() : digits.rounded();
        return prefix(digits) + plain(shown) + (digits.complete() ? "" : ELLIPSIS);
    }

    private static String scientific(Digits digits) {
        BigDecimal rounded = digits.rounded();
        int exponent = rounded.precision() - rounded.scale() - 1;
        BigDecimal mantissa = rounded.movePointLeft(exponent);
        if (digits.complete()) {
            mantissa = mantissa.stripTrailingZeros();
        }
        return prefix(digits) + plain(mantissa) + (digits.complete() ? "" : ELLIPSIS) + TIMES_TEN + superscript(exponent);
    }

    private static String prefix(Digits digits) {
        return digits.approximate() ? APPROXIMATE_PREFIX : "";
    }

    // The radius rounded up to two significant digits, so that it is still a bound.
    private static String errorBound(Rational radius) {
        BigDecimal rounded = withoutCarryDigit(
                new BigDecimal(radius.numerator()).divide(new BigDecimal(radius.denominator()),
                        ERROR_BOUND_DIGITS - 1 - exponent(radius), RoundingMode.UP),
                ERROR_BOUND_DIGITS);
        int exponent = rounded.precision() - rounded.scale() - 1;
        return plain(rounded.movePointLeft(exponent)) + TIMES_TEN + superscript(exponent);
    }

    private static String exactScientific(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        String digits = stripped.unscaledValue().abs().toString();
        int exponent = stripped.precision() - stripped.scale() - 1;
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "," + digits.substring(1);
        return (stripped.signum() < 0 ? "-" : "") + mantissa + "e" + exponent;
    }

    private static String plain(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }

    private static String superscript(int exponent) {
        StringBuilder text = new StringBuilder();
        for (char digit : Integer.toString(exponent).toCharArray()) {
            text.append(digit == '-' ? SUPERSCRIPT_MINUS : SUPERSCRIPT_DIGITS.charAt(digit - '0'));
        }
        return text.toString();
    }

    /** {@code value} rounded {@code HALF_UP} (on the magnitude) to {@code scale} decimals, exactly. */
    private static BigDecimal round(Rational value, int scale) {
        return new BigDecimal(value.numerator()).divide(new BigDecimal(value.denominator()), scale, RoundingMode.HALF_UP);
    }

    /** {@code floor(log10(|value|))} for a value that is not zero. */
    private static int exponent(Rational value) {
        // Truncation never crosses a power of ten, so three digits are enough to place the first one.
        BigDecimal leading = new BigDecimal(value.numerator().abs())
                .divide(new BigDecimal(value.denominator()), new MathContext(3, RoundingMode.DOWN));
        return leading.precision() - leading.scale() - 1;
    }

    // Sign of decimal - value, exactly (the denominator is positive).
    private static int compare(BigDecimal decimal, Rational value) {
        return decimal.multiply(new BigDecimal(value.denominator())).compareTo(new BigDecimal(value.numerator()));
    }
}
