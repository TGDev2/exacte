package calc.ui;

import calc.format.DisplayMode;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * The display mode in a {@code java.util.prefs} node of its own. A restricted environment (no registry or home access,
 * a security manager) makes the preferences fail: the failure is logged, the application starts in Auto mode and goes
 * on without remembering.
 */
public final class PreferencesDisplayModeStore implements DisplayModeStore {
    /** The node of the application under the user root. */
    public static final String NODE_PATH = "/calc/display";
    static final String KEY = "mode";

    private static final Logger LOGGER = Logger.getLogger(PreferencesDisplayModeStore.class.getName());

    private final Preferences node;

    /** Uses the node of the application. */
    public static PreferencesDisplayModeStore forCurrentUser() {
        try {
            return new PreferencesDisplayModeStore(Preferences.userRoot().node(NODE_PATH));
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot open the preferences, the display mode will not be remembered", exception);
            return new PreferencesDisplayModeStore(null);
        }
    }

    /** {@code node} may be null: the store then remembers nothing. */
    public PreferencesDisplayModeStore(Preferences node) {
        this.node = node;
    }

    @Override
    public DisplayMode load() {
        if (node == null) {
            return DisplayMode.AUTO;
        }
        try {
            String stored = node.get(KEY, null);
            if (stored == null) {
                return DisplayMode.AUTO;
            }
            return DisplayMode.valueOf(stored);
        } catch (IllegalArgumentException exception) {
            LOGGER.log(Level.WARNING, "Unknown display mode in the preferences, using Auto", exception);
            return DisplayMode.AUTO;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot read the preferences, using the Auto display mode", exception);
            return DisplayMode.AUTO;
        }
    }

    @Override
    public void save(DisplayMode mode) {
        Objects.requireNonNull(mode);
        if (node == null) {
            return;
        }
        try {
            node.put(KEY, mode.name());
            node.flush();
        } catch (BackingStoreException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot write the preferences, the display mode will not be remembered", exception);
        }
    }
}
