package calc.ui.theme;

import com.formdev.flatlaf.FlatLightLaf;

/**
 * The light theme of the calculator. FlatLaf reads {@code CalcLightLaf.properties} of this package, generated from the design
 * tokens (ADR 0016), on top of the defaults of {@link FlatLightLaf} (ADR 0017).
 */
public class CalcLightLaf extends FlatLightLaf {
    private static final long serialVersionUID = 1L;

    /** The name shown for this theme. */
    public static final String NAME = "Calc clair";

    @Override
    public String getName() {
        return NAME;
    }
}
