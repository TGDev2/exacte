package calc.testing;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Reads back the number a display text shows, independently of the production code: the « ≈ » prefix and the « … »
 * marker are dropped, {@code m × 10ⁿ} becomes {@code m * 10^n}, a decimal comma is a point, {@code p/q} is a fraction.
 */
public final class DisplayTexts {
    private static final String APPROXIMATE = "≈ ";
    private static final String ELLIPSIS = "…";
    private static final String TIMES_TEN = " × 10";
    private static final String SUPERSCRIPT_DIGITS = "⁰¹²³⁴⁵⁶⁷⁸⁹";
    private static final char SUPERSCRIPT_MINUS = '⁻';

    private DisplayTexts() {
    }

    /** The value shown by a display text. */
    public static FractionOracle value(String text) {
        String shown = withoutMarkers(text);
        int bar = shown.indexOf('/');
        if (bar >= 0) {
            return new FractionOracle(new BigInteger(shown.substring(0, bar)), new BigInteger(shown.substring(bar + 1)));
        }
        return FractionOracle.of(decimal(shown));
    }

    /**
     * The decimal shown by a positional or scientific text, with its digits as written: its scale is the position of
     * the last digit shown ({@code 1,50 × 10⁻³} gives {@code 0.00150}).
     */
    public static BigDecimal decimal(String text) {
        String shown = withoutMarkers(text);
        int times = shown.indexOf(TIMES_TEN);
        if (times < 0) {
            return new BigDecimal(shown.replace(',', '.'));
        }
        BigDecimal mantissa = new BigDecimal(shown.substring(0, times).replace(',', '.'));
        return mantissa.scaleByPowerOfTen(exponent(shown.substring(times + TIMES_TEN.length())));
    }

    /** Whether the text is shown as approximate. */
    public static boolean isApproximate(String text) {
        return text.startsWith(APPROXIMATE);
    }

    private static String withoutMarkers(String text) {
        String shown = text.startsWith(APPROXIMATE) ? text.substring(APPROXIMATE.length()) : text;
        return shown.replace(ELLIPSIS, "");
    }

    private static int exponent(String superscript) {
        int value = 0;
        boolean negative = false;
        for (char character : superscript.toCharArray()) {
            if (character == SUPERSCRIPT_MINUS) {
                negative = true;
            } else {
                int digit = SUPERSCRIPT_DIGITS.indexOf(character);
                if (digit < 0) {
                    throw new IllegalArgumentException("not a superscript exponent: " + superscript);
                }
                value = value * 10 + digit;
            }
        }
        return negative ? -value : value;
    }
}
