package Calcul;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Pattern;

final class DecimalPolicy {
    static final int APPROXIMATION_SCALE = 12;
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
            return roundApproximation(dividend.divide(divisor, APPROXIMATION_SCALE, ROUNDING_MODE));
        }
    }

    static BigDecimal sqrt(BigDecimal value) {
        int precision = Math.max(value.precision(), 1) + APPROXIMATION_SCALE + 4;
        MathContext mathContext = new MathContext(precision, ROUNDING_MODE);
        return roundApproximation(value.sqrt(mathContext));
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
        return normalize(value.setScale(APPROXIMATION_SCALE, ROUNDING_MODE));
    }

    static String serialize(BigDecimal value) {
        return normalize(value).toPlainString();
    }

    static String format(BigDecimal value) {
        return serialize(value).replace('.', ',');
    }
}
