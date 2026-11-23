package calc.ui.theme;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * The dark theme of the calculator. FlatLaf reads {@code CalcDarkLaf.properties} of this package, generated from the design
 * tokens (ADR 0016), on top of the defaults of {@link FlatDarkLaf} (ADR 0017).
 */
public class CalcDarkLaf extends FlatDarkLaf {
    private static final long serialVersionUID = 1L;

    /** The name shown for this theme. */
    public static final String NAME = "Calc sombre";

    @Override
    public String getName() {
        return NAME;
    }
}
