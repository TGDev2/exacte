package calc.engine;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DecimalPolicy {
    static final int MIN_APPROXIMATION_DECIMALS = CalculationLimits.MIN_APPROXIMATION_DECIMALS;
    static final int MIN_SIGNIFICANT_DIGITS = CalculationLimits.MIN_SIGNIFICANT_DIGITS;
    static final int MAX_ABSOLUTE_INTEGER_EXPONENT = CalculationLimits.MAX_ABSOLUTE_INTEGER_EXPONENT;
    static final int MAX_DISPLAY_DIGITS = CalculationLimits.MAX_RESULT_DIGITS;
    static final int MAX_INPUT_LENGTH = CalculationLimits.MAX_INPUT_LENGTH;
    static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private static final Pattern SUPPORTED_DECIMAL_INPUT = Pattern.compile("[+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)");

    private static final String EDGE_SPACES = " \t  ";
    private static final Pattern GROUPED_INTEGER_INPUT =
            Pattern.compile("([+-]?\\d{1,3}(?:[   ]\\d{3})+)([.,]\\d*)?");
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile("[   ]");

    private DecimalPolicy() {
    }

    static String stripEdgeSpaces(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && EDGE_SPACES.indexOf(input.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE_SPACES.indexOf(input.charAt(end - 1)) >= 0) {
            end--;
        }
        return input.substring(start, end);
    }

    // Spaces are only valid as \d{1,3}( \d{3})+ in the integer part; any other input is left for the regex to refuse.
    static String removeThousandsSeparators(String input) {
        Matcher matcher = GROUPED_INTEGER_INPUT.matcher(input);
        if (!matcher.matches()) {
            return input;
        }
        String integerPart = THOUSANDS_SEPARATOR.matcher(matcher.group(1)).replaceAll("");
        return matcher.group(2) == null ? integerPart : integerPart + matcher.group(2);
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
