package Calcul;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Pattern;

final class DecimalPolicy {
    static final int MIN_APPROXIMATION_DECIMALS = 12;
    static final int MIN_SIGNIFICANT_DIGITS = 12;
    static final int MAX_ABSOLUTE_INTEGER_EXPONENT = 999;
    static final int MAX_DISPLAY_DIGITS = 1000;
    static final int MAX_INPUT_LENGTH = 1005;
    static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private static final Pattern SUPPORTED_DECIMAL_INPUT = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    private DecimalPolicy() {
    }

    static boolean isSupportedInput(String normalizedInput) {
        return normalizedInput != null && SUPPORTED_DECIMAL_INPUT.matcher(normalizedInput).matches();
    }

    static BigDecimal parseNormalizedInput(String normalizedInput) {
        return normalize(new BigDecimal(normalizedInput));
    }

    static BigDecimal normalize(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            return normalized.setScale(0);
        }
        return normalized;
    }

    static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        try {
            return normalize(dividend.divide(divisor));
        } catch (ArithmeticException exception) {
            // Truncate one digit beyond the final scale (the quotient's first digit is at most
            // at maxExponent): a truncated value rounds HALF_UP exactly like the true quotient.
            int maxExponent = firstDigitExponent(dividend) - firstDigitExponent(divisor);
            int precision = approximationScale(maxExponent) + maxExponent + 2;
            return roundApproximation(dividend.divide(divisor, new MathContext(precision, RoundingMode.DOWN)));
        }
    }

    static BigDecimal sqrt(BigDecimal value) {
        // With p + 2 * 12 + 4 correctly rounded digits, the root of a normalized p-digit value
        // can never reach a rounding midpoint of the final scale, so rounding it again is exact.
        // Do not go below p + 26: p + 16 already double-rounds sqrt(400000000.00000002).
        int precision = value.precision() + 2 * MIN_SIGNIFICANT_DIGITS + 4;
        BigDecimal root = value.sqrt(new MathContext(precision, ROUNDING_MODE));
        if (root.multiply(root).compareTo(value) == 0) {
            return normalize(root);
        }
        return roundApproximation(root);
    }

    static BigDecimal pow(BigDecimal base, int exponent) {
        if (exponent >= 0) {
            return normalize(base.pow(exponent));
        }
        return divide(BigDecimal.ONE, base.pow(-exponent));
    }

    static boolean exceedsDisplayDigits(BigDecimal value) {
        return Math.max(value.precision(), value.scale()) > MAX_DISPLAY_DIGITS;
    }

    static boolean isIntegerValue(BigDecimal value) {
        return normalize(value).scale() <= 0;
    }

    static boolean isSupportedExponent(int exponent) {
        return Math.abs(exponent) <= MAX_ABSOLUTE_INTEGER_EXPONENT;
    }

    static BigDecimal roundApproximation(BigDecimal value) {
        return normalize(value.setScale(approximationScale(firstDigitExponent(value)), ROUNDING_MODE));
    }

    private static int approximationScale(int firstDigitExponent) {
        return Math.max(MIN_APPROXIMATION_DECIMALS, MIN_SIGNIFICANT_DIGITS - 1 - firstDigitExponent);
    }

    private static int firstDigitExponent(BigDecimal value) {
        return value.precision() - value.scale() - 1;
    }

    static String serialize(BigDecimal value) {
        return normalize(value).toPlainString();
    }

    static String format(BigDecimal value) {
        return serialize(value).replace('.', ',');
    }
}
