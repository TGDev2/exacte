package calc.engine;

import java.math.BigDecimal;

final class NumberFormatter {
    private NumberFormatter() {
    }

    static String format(BigDecimal value) {
        return DecimalPolicy.format(value);
    }

    static String serialize(BigDecimal value) {
        return DecimalPolicy.serialize(value);
    }
}
