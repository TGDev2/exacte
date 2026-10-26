package calc.testing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * An independent reference for the arithmetic of the calculator.
 *
 * <p>It is written from the specification only: it imports nothing from the production packages, has its own reading of
 * the typed text and its own display rules (written from docs/adr/0004-regles-affichage.md), and computes with {@link BigDecimal} at 200 significant digits. A property
 * that uses it must keep its operands short enough for the 200 digits to be exact (see {@link #isExact}).
 */
public final class ArithmeticOracle {
    /** 200 significant digits: more than any exact sum or product of the "operands" generator needs. */
    public static final MathContext CONTEXT = new MathContext(200, RoundingMode.HALF_EVEN);
    private static final MathContext TWELVE_DIGITS = new MathContext(12, RoundingMode.HALF_UP);
    private static final int SHORT_TEXT = 40;

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
     * The canonical text of an exact decimal (docs/adr/0004-regles-affichage.md, section 5): its plain decimal with a
     * comma when it has at most 40 characters, otherwise the shortest of the plain decimal, the exact scientific
     * notation ({@code 1e1000}) and the reduced fraction, in that order on a tie. It is what the calculator reuses and
     * writes in its history.
     */
    public static String canonical(BigDecimal value) {
        String plain = plain(value);
        if (plain.length() <= SHORT_TEXT) {
            return plain;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        String digits = stripped.unscaledValue().abs().toString();
        String scientific = (stripped.signum() < 0 ? "-" : "") + digits.charAt(0)
                + (digits.length() > 1 ? "," + digits.substring(1) : "") + "e" + exponent(stripped);
        BigInteger[] fraction = reduced(stripped);
        String fractionText = fraction[1].equals(BigInteger.ONE) ? fraction[0].toString() : fraction[0] + "/" + fraction[1];
        String shortest = scientific.length() < plain.length() ? scientific : plain;
        return fractionText.length() < shortest.length() ? fractionText : shortest;
    }

    /**
     * The Auto text of an exact decimal (docs/adr/0004-regles-affichage.md, sections 2 and 3): positional for
     * {@code 10^-6 <= |x| < 10^15}, scientific otherwise, 12 significant digits rounded half up, the integer part of a
     * positional text never cut, « … » when digits are left out.
     */
    public static String auto(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        int exponent = exponent(value);
        if (exponent >= -6 && exponent <= 14) {
            BigDecimal rounded = exponent >= 12 ? value.setScale(0, RoundingMode.HALF_UP) : value.round(TWELVE_DIGITS);
            return rounded.compareTo(value) == 0 ? plain(value) : rounded.toPlainString().replace('.', ',') + "…";
        }
        BigDecimal rounded = value.round(TWELVE_DIGITS);
        boolean complete = rounded.compareTo(value) == 0;
        int shift = exponent(rounded);
        BigDecimal mantissa = rounded.movePointLeft(shift);
        String mantissaText = (complete ? mantissa.stripTrailingZeros().toPlainString() : mantissa.toPlainString()).replace('.', ',');
        return mantissaText + (complete ? "" : "…") + " × 10" + superscript(shift);
    }

    /** Whether the reduced fraction of the value has at most 4096 bits in its numerator and its denominator. */
    public static boolean fitsTheCore(BigDecimal value) {
        BigInteger[] fraction = reduced(value.stripTrailingZeros());
        return fraction[0].abs().bitLength() <= 4096 && fraction[1].bitLength() <= 4096;
    }

    private static String plain(BigDecimal value) {
        if (value.signum() == 0) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    // floor(log10 |value|) of a value that is not zero.
    private static int exponent(BigDecimal value) {
        return value.precision() - value.scale() - 1;
    }

    private static BigInteger[] reduced(BigDecimal value) {
        BigInteger numerator = value.scale() >= 0 ? value.unscaledValue() : value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale()));
        BigInteger denominator = value.scale() >= 0 ? BigInteger.TEN.pow(value.scale()) : BigInteger.ONE;
        BigInteger gcd = numerator.gcd(denominator);
        return gcd.signum() == 0 ? new BigInteger[] {BigInteger.ZERO, BigInteger.ONE}
                : new BigInteger[] {numerator.divide(gcd), denominator.divide(gcd)};
    }

    private static String superscript(int exponent) {
        String digits = "⁰¹²³⁴⁵⁶⁷⁸⁹";
        StringBuilder text = new StringBuilder();
        for (char character : Integer.toString(exponent).toCharArray()) {
            text.append(character == '-' ? '⁻' : digits.charAt(character - '0'));
        }
        return text.toString();
    }
}
