package calc.testing;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * An independent reference for the arithmetic of the calculator.
 *
 * <p>It is written from the specification only: it imports nothing from the production packages, has its own reading of
 * the typed text and its own display rule, and computes with {@link BigDecimal} at 200 significant digits. A property
 * that uses it must keep its operands short enough for the 200 digits to be exact (see {@link #isExact}).
 */
public final class ArithmeticOracle {
    /** 200 significant digits: more than any exact sum or product of the "operands" generator needs. */
    public static final MathContext CONTEXT = new MathContext(200, RoundingMode.HALF_EVEN);

    private ArithmeticOracle() {
    }

    /**
     * Reads a typed operand: edge spaces and thousands separators are dropped, a comma is a point. The text must
     * already be well formed; no validation is made here on purpose (the generators build only valid texts).
     */
    public static BigDecimal parse(String text) {
        StringBuilder digits = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == ' ' || character == '\t' || character == 0x00A0 || character == 0x202F) {
                continue;
            }
            digits.append(character == ',' ? '.' : character);
        }
        return new BigDecimal(digits.toString());
    }

    public static BigDecimal add(BigDecimal first, BigDecimal second) {
        return first.add(second, CONTEXT);
    }

    public static BigDecimal subtract(BigDecimal first, BigDecimal second) {
        return first.subtract(second, CONTEXT);
    }

    public static BigDecimal multiply(BigDecimal first, BigDecimal second) {
        return first.multiply(second, CONTEXT);
    }

    /** An exact integer power (no rounding): the caller keeps the exponent small enough for the result to stay short. */
    public static BigDecimal power(BigDecimal base, int exponent) {
        return base.pow(exponent);
    }

    /**
     * The canonical text of a value: no sign for zero, no trailing zero, no exponent, a comma as the decimal separator.
     * This is the text the calculator displays, reuses and writes in its history.
     */
    public static String display(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** The canonical text with a decimal point, as stored in the history file. */
    public static String stored(BigDecimal value) {
        return display(value).replace(',', '.');
    }

    /** The number of digits the calculator bounds: the larger of the precision and the scale of the canonical value. */
    public static int digits(BigDecimal value) {
        if (value.signum() == 0) {
            return 1;
        }
        BigDecimal canonical = value.stripTrailingZeros();
        if (canonical.scale() < 0) {
            canonical = canonical.setScale(0);
        }
        return Math.max(canonical.precision(), canonical.scale());
    }
}
