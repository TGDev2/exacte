package calc.ui.design;

import java.util.Locale;

/** The effective themes of the design tokens: the colours of {@code tokens.properties} exist once per theme. */
public enum Theme {
    LIGHT,
    DARK;

    /** The segment of the colour keys of this theme: {@code color.light.<role>}, {@code color.dark.<role>}. */
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
