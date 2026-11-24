package calc.ui;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The theme in the {@code java.util.prefs} node of the display, next to the display mode. A restricted environment makes the
 * preferences fail: the failure is logged, the application starts in the light theme and goes on without remembering.
 */
public final class PreferencesThemeStore implements ThemeStore {
    /** The node of the application under the user root, shared with the display mode. */
    public static final String NODE_PATH = PreferencesDisplayModeStore.NODE_PATH;
    static final String KEY = "theme";

    private static final Logger LOGGER = Logger.getLogger(PreferencesThemeStore.class.getName());

    private final Preferences node;

    /** Uses the node of the application. */
    public static PreferencesThemeStore forCurrentUser() {
        try {
            return new PreferencesThemeStore(Preferences.userRoot().node(NODE_PATH));
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot open the preferences, the theme will not be remembered", exception);
            return new PreferencesThemeStore(null);
        }
    }

    /** {@code node} may be null: the store then remembers nothing. */
    public PreferencesThemeStore(Preferences node) {
        this.node = node;
    }

    @Override
    public ThemeChoice load() {
        if (node == null) {
            return ThemeChoice.LIGHT;
        }
        try {
            String stored = node.get(KEY, null);
            return stored == null ? ThemeChoice.LIGHT : ThemeChoice.valueOf(stored);
        } catch (IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, "Unknown theme in the preferences, using the light theme", exception);
            return ThemeChoice.LIGHT;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot read the preferences, using the light theme", exception);
            return ThemeChoice.LIGHT;
        }
    }

    @Override
    public void save(ThemeChoice choice) {
        Objects.requireNonNull(choice);
        if (node == null) {
            return;
        }
        try {
            node.put(KEY, choice.name());
            node.flush();
        } catch (BackingStoreException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot write the preferences, the theme will not be remembered", exception);
        }
    }
}
