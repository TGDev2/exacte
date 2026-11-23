package calc.ui.theme;

import calc.ui.design.Theme;
import java.util.Optional;

/**
 * Reads whether the system asks for a light or a dark theme. Only Windows is read (ADR 0017): elsewhere the detection is
 * unavailable and the choice « Système » is not offered.
 */
public interface SystemThemeDetector {
    /** Whether the theme of the system could be read. */
    boolean available();

    /** The theme of the system, empty when it is not {@link #available()}. */
    Optional<Theme> current();

    /** No detection: the choice « Système » is not offered. */
    static SystemThemeDetector none() {
        return new SystemThemeDetector() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public Optional<Theme> current() {
                return Optional.empty();
            }
        };
    }
}
