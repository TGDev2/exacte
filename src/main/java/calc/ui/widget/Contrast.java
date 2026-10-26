package calc.ui.widget;

import java.awt.Color;

/** WCAG 2.x contrast ratio between two opaque colours, from 1 (identical) to 21 (black on white). */
public final class Contrast {
    /** The minimum ratio of WCAG AA for normal-size text. */
    public static final double MINIMUM_TEXT_RATIO = 4.5;

    private Contrast() {
    }

    public static double ratio(Color first, Color second) {
        double firstLuminance = luminance(first);
        double secondLuminance = luminance(second);
        double lighter = Math.max(firstLuminance, secondLuminance);
        double darker = Math.min(firstLuminance, secondLuminance);
        return (lighter + 0.05) / (darker + 0.05);
    }

    private static double luminance(Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
