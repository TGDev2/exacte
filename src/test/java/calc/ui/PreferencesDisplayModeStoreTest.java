package calc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.format.DisplayMode;
import java.util.UUID;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreferencesDisplayModeStoreTest {
    private Preferences node;

    @BeforeEach
    void isolatedNode() {
        // A node of its own, never the one of the application, removed after each test.
        node = Preferences.userRoot().node("/calc-test-" + UUID.randomUUID());
    }

    @AfterEach
    void removeNode() throws BackingStoreException {
        node.removeNode();
        node.flush();
    }

    @Test
    void defaultsToAutoWhenNothingIsStored() {
        assertEquals(DisplayMode.AUTO, new PreferencesDisplayModeStore(node).load());
    }

    @Test
    void whatIsSavedIsReadBackByAnotherStoreOnTheSameNode() {
        for (DisplayMode mode : DisplayMode.values()) {
            new PreferencesDisplayModeStore(node).save(mode);
            assertEquals(mode, new PreferencesDisplayModeStore(node).load(), mode.name());
        }
    }

    @Test
    void anUnknownStoredValueMeansAuto() {
        node.put(PreferencesDisplayModeStore.KEY, "SIDEWAYS");
        assertEquals(DisplayMode.AUTO, new PreferencesDisplayModeStore(node).load());
    }

    @Test
    void aStoreWithoutNodeRemembersNothingAndDoesNotFail() {
        PreferencesDisplayModeStore store = new PreferencesDisplayModeStore(null);
        store.save(DisplayMode.FRACTION);
        assertEquals(DisplayMode.AUTO, store.load());
    }

    @Test
    void aFailingBackingStoreIsToleratedOnReadAndOnWrite() {
        PreferencesDisplayModeStore store = new PreferencesDisplayModeStore(new RestrictedPreferences());
        store.save(DisplayMode.SCIENTIFIC);
        assertEquals(DisplayMode.AUTO, store.load());
    }

    /** A node whose every access is refused, as in a restricted environment. */
    private static final class RestrictedPreferences extends AbstractPreferences {
        RestrictedPreferences() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            throw new SecurityException("restricted environment");
        }

        @Override
        protected String getSpi(String key) {
            throw new SecurityException("restricted environment");
        }

        @Override
        protected void removeSpi(String key) {
        }

        @Override
        protected void removeNodeSpi() {
        }

        @Override
        protected String[] keysSpi() {
            return new String[0];
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected AbstractPreferences childSpi(String name) {
            return this;
        }

        @Override
        protected void syncSpi() throws BackingStoreException {
            throw new BackingStoreException("restricted environment");
        }

        @Override
        protected void flushSpi() throws BackingStoreException {
            throw new BackingStoreException("restricted environment");
        }
    }
}
