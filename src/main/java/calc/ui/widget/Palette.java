package calc.ui.widget;

import java.awt.Color;

/**
 * The colours of the status messages and of the exactness marks. The colour only completes a text (« Exact »,
 * « ≈ Approché », the status sentence), it never carries the information alone. Every foreground reaches
 * {@link Contrast#MINIMUM_TEXT_RATIO} on its background; the status colours are checked against the usual panel
 * backgrounds of the system look and feels (white, light grey), see PaletteContrastTest.
 */
public final class Palette {
    public static final Color SUCCESS = new Color(18, 93, 56);
    public static final Color ERROR = new Color(163, 44, 44);
    public static final Color INFO = new Color(60, 60, 60);

    public static final Color EXACT_FOREGROUND = new Color(10, 70, 40);
    public static final Color EXACT_BACKGROUND = new Color(214, 240, 224);
    public static final Color APPROXIMATE_FOREGROUND = new Color(102, 56, 0);
    public static final Color APPROXIMATE_BACKGROUND = new Color(255, 235, 190);

    private Palette() {
    }
}
