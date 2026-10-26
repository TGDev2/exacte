package calc.ui.widget;

import calc.format.DisplayMode;

/** The French name of each {@link DisplayMode}. */
public final class DisplayModeText {
    private DisplayModeText() {
    }

    public static String label(DisplayMode mode) {
        return switch (mode) {
            case AUTO -> "Auto";
            case DECIMAL -> "Décimal";
            case FRACTION -> "Fraction";
            case SCIENTIFIC -> "Scientifique";
        };
    }
}
