package calc.ui.theme;

import calc.ui.design.Theme;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import java.awt.Window;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Installs the embedded fonts and the look and feel of a theme (ADR 0017). Both run on the event thread, before the window is
 * built; {@link #apply(Theme)} also redraws the windows that already exist.
 */
public final class ThemeManager {
    private static final Logger LOGGER = Logger.getLogger(ThemeManager.class.getName());

    private ThemeManager() {
    }

    /**
     * Registers Inter and JetBrains Mono with the graphics environment and makes them the preferred families of FlatLaf:
     * Inter for the interface, JetBrains Mono for what the user types and the results. Takes effect at the next
     * {@link #apply(Theme)}.
     */
    public static void installFonts() {
        FlatInterFont.install();
        FlatJetBrainsMonoFont.install();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
    }

    /**
     * Sets up {@link CalcLightLaf} or {@link CalcDarkLaf}, from which the palettes read the active theme, and redraws the
     * windows already open. Call it on the event thread.
     */
    public static void apply(Theme theme) {
        FlatLaf laf = theme == Theme.DARK ? new CalcDarkLaf() : new CalcLightLaf();
        if (!FlatLaf.setup(laf)) {
            LOGGER.log(Level.WARNING, "Cannot apply the look and feel {0}, keeping the current one", laf.getName());
            return;
        }
        if (Window.getWindows().length > 0) {
            FlatLaf.updateUI();
        }
    }
}
