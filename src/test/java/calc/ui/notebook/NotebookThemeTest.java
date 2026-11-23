package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.ThemeChoice;
import calc.ui.ThemeStore;
import calc.ui.design.Theme;
import calc.ui.theme.SystemThemeDetector;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Affichage > Thème in the presenter, without Swing: the choice is remembered, the theme drawn follows it, and a change of
 * theme validates nothing, moves nothing and calculates nothing, from the first, the last and the blank last line alike.
 */
class NotebookThemeTest {
    @TempDir
    Path data;

    private NotebookStore store;
    private final RecordingThemeStore themes = new RecordingThemeStore();

    @AfterEach
    void stop() {
        if (store != null) {
            store.close();
        }
    }

    private NotebookDriver open(SystemThemeDetector systemTheme) {
        store = NotebookStore.open(data);
        return new NotebookDriver(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), themes, systemTheme,
                NotebookDialogs.cancelling(), NotebookPresenter.newRecalculationExecutor()));
    }

    @Test
    void aChangeOfThemeKeepsWhatIsTypedOnEveryLineAndRemembersTheChoice() {
        NotebookDriver d = open(SystemThemeDetector.none());
        d.line("a = 2").line("a * 3");
        assertEquals(ThemeChoice.LIGHT, d.view.theme());
        assertEquals(Theme.LIGHT, d.view.effectiveTheme());
        assertFalse(d.view.systemThemeAvailable());

        // The blank last line, something typed.
        d.type("1 +").theme(ThemeChoice.DARK);
        assertEquals(2, d.view.activeIndex(), "the blank last line stays active");
        assertEquals("1 +", d.view.activeDraft(), "what is typed is kept, not validated");
        assertEquals(List.of("a = 2", "a * 3", ""), texts(d));
        assertEquals(ThemeChoice.DARK, d.view.theme());
        assertEquals(Theme.DARK, d.view.effectiveTheme());
        assertEquals("Thème : Sombre.", d.view.statusMessage());
        assertEquals(List.of(ThemeChoice.DARK), themes.saves);

        // The first line, changed but not validated.
        d.type("").up().up().up();
        assertEquals(0, d.view.activeIndex());
        d.type("a = 5").theme(ThemeChoice.LIGHT);
        assertEquals(0, d.view.activeIndex());
        assertEquals("a = 5", d.view.activeDraft());
        assertEquals(List.of("a = 2", "a * 3", ""), texts(d), "nothing is validated");
        assertEquals(List.of("2", "6", ""), results(d), "nothing is calculated again");

        // The last line of the notebook.
        d.escape().down();
        assertEquals(1, d.view.activeIndex());
        d.type("a * 4").theme(ThemeChoice.DARK);
        assertEquals(1, d.view.activeIndex());
        assertEquals("a * 4", d.view.activeDraft());
        assertEquals(List.of("a = 2", "a * 3", ""), texts(d));
        assertEquals(List.of(ThemeChoice.DARK, ThemeChoice.LIGHT, ThemeChoice.DARK), themes.saves);

        // The same theme again is not a change.
        d.theme(ThemeChoice.DARK);
        assertEquals(3, themes.saves.size(), "the same choice is not written again");
    }

    @Test
    void systemIsOfferedOnlyWithADetectionAndFollowsIt() {
        NotebookDriver without = open(SystemThemeDetector.none());
        without.theme(ThemeChoice.SYSTEM);
        assertEquals(ThemeChoice.LIGHT, without.view.theme(), "Système is ignored where it is not offered");
        assertTrue(themes.saves.isEmpty(), "nothing is remembered");
        store.close();

        NotebookDriver with = open(detector(Theme.DARK));
        assertTrue(with.view.systemThemeAvailable());
        with.theme(ThemeChoice.SYSTEM);
        assertEquals(ThemeChoice.SYSTEM, with.view.theme());
        assertEquals(Theme.DARK, with.view.effectiveTheme(), "the theme drawn is the one of the system");
        assertEquals("Thème : Système.", with.view.statusMessage());
        assertEquals(List.of(ThemeChoice.SYSTEM), themes.saves);
    }

    @Test
    void theRememberedChoiceIsReadAtStart() {
        themes.stored = ThemeChoice.DARK;
        assertEquals(ThemeChoice.DARK, open(SystemThemeDetector.none()).view.theme());
        store.close();

        themes.stored = ThemeChoice.SYSTEM;
        NotebookDriver withoutDetection = open(SystemThemeDetector.none());
        assertEquals(ThemeChoice.LIGHT, withoutDetection.view.theme(), "Système without a detection gives Clair");
        assertEquals(Theme.LIGHT, withoutDetection.view.effectiveTheme());
        store.close();

        NotebookDriver withDetection = open(detector(Theme.LIGHT));
        assertEquals(ThemeChoice.SYSTEM, withDetection.view.theme());
        assertEquals(Theme.LIGHT, withDetection.view.effectiveTheme());
        assertTrue(themes.saves.isEmpty(), "reading the choice writes nothing");
    }

    @Test
    void theEffectiveThemeOfEachChoice() {
        assertEquals(Theme.LIGHT, NotebookPresenter.effectiveTheme(ThemeChoice.LIGHT, detector(Theme.DARK)));
        assertEquals(Theme.DARK, NotebookPresenter.effectiveTheme(ThemeChoice.DARK, detector(Theme.LIGHT)));
        assertEquals(Theme.DARK, NotebookPresenter.effectiveTheme(ThemeChoice.SYSTEM, detector(Theme.DARK)));
        assertEquals(Theme.LIGHT, NotebookPresenter.effectiveTheme(ThemeChoice.SYSTEM, SystemThemeDetector.none()));
    }

    private static List<String> texts(NotebookDriver d) {
        return d.view.rows().stream().map(NotebookRow::text).toList();
    }

    private static List<String> results(NotebookDriver d) {
        return d.view.rows().stream().map(NotebookRow::result).toList();
    }

    private static SystemThemeDetector detector(Theme theme) {
        return new SystemThemeDetector() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Optional<Theme> current() {
                return Optional.of(theme);
            }
        };
    }

    /** A theme store that remembers in memory what was saved. */
    private static final class RecordingThemeStore implements ThemeStore {
        ThemeChoice stored = ThemeChoice.LIGHT;
        final List<ThemeChoice> saves = new ArrayList<>();

        @Override
        public ThemeChoice load() {
            return stored;
        }

        @Override
        public void save(ThemeChoice choice) {
            stored = choice;
            saves.add(choice);
        }
    }
}
