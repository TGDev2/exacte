package calc.ui.widget;

import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;

/**
 * The colours of the status messages and of the exactness marks, read from the design tokens of one theme (ADR 0016). The
 * colour only completes a text (« Exact », « ≈ Approché », the status sentence), it never carries the information alone.
 * Every foreground reaches {@link Contrast#MINIMUM_TEXT_RATIO} on its background in both themes; the status colours of the
 * light theme are also checked against the usual panel backgrounds of the system look and feels, see PaletteContrastTest.
 */
public final class Palette {
    private final Theme theme;

    private Palette(Theme theme) {
        this.theme = theme;
    }

    /**
     * The theme the window is drawn in, read from the installed look and feel: dark when ThemeManager installed the dark
     * FlatLaf theme, light otherwise.
     */
    public static Theme activeTheme() {
        return FlatLaf.isLafDark() ? Theme.DARK : Theme.LIGHT;
    }

    /** The palette of the active theme. */
    public static Palette active() {
        return of(activeTheme());
    }

    public static Palette of(Theme theme) {
        return new Palette(theme);
    }

    public Theme theme() {
        return theme;
    }

    public Color success() {
        return role("exact.sign");
    }

    public Color error() {
        return role("error.text");
    }

    public Color info() {
        return role("text.secondary");
    }

    public Color exactForeground() {
        return role("exact.sign");
    }

    public Color exactBackground() {
        return role("exact.surface");
    }

    public Color approximateForeground() {
        return role("approximate.sign");
    }

    public Color approximateBackground() {
        return role("approximate.surface");
    }

    private Color role(String role) {
        return DesignTokens.load().color(theme, role);
    }
}
