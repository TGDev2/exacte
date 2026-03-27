package Calcul;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

final class NumberFormatter {
    private static final ThreadLocal<DecimalFormat> DISPLAY_FORMAT = ThreadLocal.withInitial(() -> {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.FRANCE);
        DecimalFormat format = new DecimalFormat("0.############", symbols);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format;
    });

    private NumberFormatter() {
    }

    static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        return DISPLAY_FORMAT.get().format(value);
    }
}
