package calc.persistence;

import calc.number.CertifiedValue;
import calc.number.Rational;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.function.Function;

/**
 * The rounding policies of version 1, kept only to check the results stored in a version 1 history file
 * (docs/adr/0004-regles-affichage.md, section 9). Nothing is computed or shown with them any more.
 */
final class LegacyRounding {
    private static final int DECIMALS = 12;
    private static final int SIGNIFICANT_DIGITS = 12;

    private LegacyRounding() {
    }

    /** Last version 1 policy: 12 significant digits with at least 12 decimals, {@code HALF_UP}. */
    static BigDecimal significant(Rational value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal leading = new BigDecimal(value.numerator().abs())
                .divide(new BigDecimal(value.denominator()), new MathContext(3, RoundingMode.DOWN));
        int exponent = leading.precision() - leading.scale() - 1;
        return round(value, Math.max(DECIMALS, SIGNIFICANT_DIGITS - 1 - exponent));
    }

    /** Policy before it: 12 decimals, {@code HALF_UP}. */
    static BigDecimal twelveDecimals(Rational value) {
        return round(value, DECIMALS);
    }

    /** The rounding of the value, if both bounds of an approximate value round to the same number. */
    static Optional<BigDecimal> of(CertifiedValue value, Function<Rational, BigDecimal> policy) {
        BigDecimal lower = policy.apply(value.lower());
        return lower.compareTo(policy.apply(value.upper())) == 0 ? Optional.of(lower) : Optional.empty();
    }

    /** Whether the decimal and the rational are the same number. */
    static boolean same(BigDecimal decimal, Rational value) {
        return decimal.multiply(new BigDecimal(value.denominator())).compareTo(new BigDecimal(value.numerator())) == 0;
    }

    private static BigDecimal round(Rational value, int scale) {
        return new BigDecimal(value.numerator()).divide(new BigDecimal(value.denominator()), scale, RoundingMode.HALF_UP);
    }
}
