package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.Action;
import javax.swing.ButtonGroup;
import javax.swing.ButtonModel;
import javax.swing.DefaultButtonModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of E05: the themes Clair, Sombre and Système (Windows only), the remembered choice, the detection of the Windows
 * theme, the title of the window, the header without the label « Carnet de calcul », the segmented display mode control, the
 * dark screenshots and the documentation.
 *
 * <p>The new types are reached by reflection, so that this class compiles, and fails with a clear message, before they exist:
 * <ul>
 *   <li>{@code calc.ui.ThemeChoice}, the enum {@code LIGHT}, {@code DARK}, {@code SYSTEM};</li>
 *   <li>{@code calc.ui.ThemeStore}, an interface with {@code ThemeChoice load()}, {@code void save(ThemeChoice)} and the
 *       static {@code noop()};</li>
 *   <li>{@code calc.ui.PreferencesThemeStore}, built on a {@code java.util.prefs.Preferences} node (null: remembers nothing),
 *       with the public constant {@code NODE_PATH} {@code "/calc/display"}, writing the key {@code theme};</li>
 *   <li>{@code calc.ui.theme.SystemThemeDetector}, an interface with {@code boolean available()} and
 *       {@code Optional<Theme> current()};</li>
 *   <li>{@code calc.ui.theme.WindowsSystemThemeDetector}, which implements it, built from the {@code os.name} it must use
 *       ({@code new WindowsSystemThemeDetector(String osName)}), with the static {@code parse(String output)} that reads the
 *       output of {@code reg query} and returns an {@code Optional<Theme>} (empty: no theme);</li>
 *   <li>{@link NotebookPresenter}: a static method or a constructor that returns a presenter and takes a {@code ThemeStore}
 *       and a {@code SystemThemeDetector}, its other parameters among {@link NotebookStore}, {@link LoadedNotebook},
 *       {@link DisplayModeStore}, {@link NotebookDialogs}, {@link Executor} and {@code DocumentService}; the
 *       {@link NotebookViewModel} has exactly one component of type {@code ThemeChoice}, the choice in force.</li>
 * </ul>
 * The window is {@code new NotebookFrame(presenter)}. Paths are relative to the repository root, the working directory of the
 * tests.
 */
@Tag("acceptance")
@DisplayName("Thèmes Clair, Sombre et Système, titre de la fenêtre, contrôle segmenté des modes, captures sombres")
final class ThemeAndWindowAcceptanceTest {

    private static final String THEME_CHOICE = "calc.ui.ThemeChoice";
    private static final String THEME_STORE = "calc.ui.ThemeStore";
    private static final String PREFERENCES_THEME_STORE = "calc.ui.PreferencesThemeStore";
    private static final String DETECTOR = "calc.ui.theme.SystemThemeDetector";
    private static final String WINDOWS_DETECTOR = "calc.ui.theme.WindowsSystemThemeDetector";
    private static final String DARK_LAF = "calc.ui.theme.CalcDarkLaf";
    private static final String LIGHT_LAF = "calc.ui.theme.CalcLightLaf";
    private static final String DOCUMENT_SERVICE = "calc.document.DocumentService";

    private static final String PERSONALIZE = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String VALUE = "AppsUseLightTheme";

    private static final Path GUIDE = Path.of("docs", "GUIDE-UTILISATEUR.md");
    private static final Path RECETTE = Path.of("docs", "RECETTE.md");

    private static final List<String> LINES = List.of("a = 2", "a * 3");
    private static final KeyStroke NEXT_MODE = KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK);

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private LookAndFeel original;

    @BeforeEach
    void rememberTheLookAndFeel() {
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
        if (!frames.isEmpty() || !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeAndWait(() -> {
                frames.forEach(NotebookFrame::dispose);
                if (original != null && UIManager.getLookAndFeel() != original) {
                    try {
                        UIManager.setLookAndFeel(original);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
        }
        for (NotebookStore store : stores) {
            store.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ the menu Thème

    @Test
    @Tag("ui")
    @DisplayName("Affichage > Thème : « Clair » et « Sombre », et « Système » seulement si le détecteur injecté est disponible")
    void theThemeMenuOffersSystemOnlyWhenTheDetectorIsAvailable() throws Exception {
        requireDisplay();
        NotebookFrame without = window(LINES, themeStore(choice("LIGHT"), new ArrayList<>()), detector(false, null));
        Map<String, JRadioButtonMenuItem> items = themeItems(without);
        assertEquals(List.of("themeItemLIGHT", "themeItemDARK"), List.copyOf(items.keySet()),
                "without a system detection the menu Thème holds Clair and Sombre only");
        assertEquals("Clair", items.get("themeItemLIGHT").getText());
        assertEquals("Sombre", items.get("themeItemDARK").getText());
        assertTrue(items.get("themeItemLIGHT").isSelected(), "Clair is the theme in force");

        NotebookFrame with = window(LINES, themeStore(choice("LIGHT"), new ArrayList<>()), detector(true, Theme.DARK));
        Map<String, JRadioButtonMenuItem> all = themeItems(with);
        assertEquals(List.of("themeItemLIGHT", "themeItemDARK", "themeItemSYSTEM"), List.copyOf(all.keySet()),
                "with an available detection the menu Thème holds Clair, Sombre and Système, in that order");
        assertEquals("Clair", all.get("themeItemLIGHT").getText());
        assertEquals("Sombre", all.get("themeItemDARK").getText());
        assertEquals("Système", all.get("themeItemSYSTEM").getText());
        assertEquals(1, all.values().stream().filter(JMenuItem::isSelected).count(), "exactly one theme is selected");
        assertTrue(all.get("themeItemLIGHT").isSelected(), "the remembered Clair is selected");
    }

    // ------------------------------------------------------------------------------------------------ switching

    @Test
    @Tag("ui")
    @DisplayName("Sombre : CalcDarkLaf sur la fenêtre ouverte, même fenêtre, textes conservés, fond des lignes surface.base sombre")
    void choosingDarkRestylesTheOpenWindowWithoutRebuildingIt() throws Exception {
        requireDisplay();
        List<Object> saved = new ArrayList<>();
        NotebookFrame frame = window(LINES, themeStore(choice("LIGHT"), saved), detector(false, null));
        NotebookPresenter presenter = presenterOf(frame);
        JPanel rowsPanel = onEventThread(() -> (JPanel) named(frame, "rowsPanel"));
        DesignTokens tokens = DesignTokens.load();
        assertEquals(hex(tokens.color(Theme.LIGHT, "surface.base")), onEventThread(() -> hex(rowsPanel.getBackground())),
                "the rows start on the light surface.base");

        JRadioButtonMenuItem dark = themeItems(frame).get("themeItemDARK");
        onEventThread(() -> {
            dark.doClick(0);
            return null;
        });
        flushEvents();

        Object laf = onEventThread(UIManager::getLookAndFeel);
        assertTrue(type(DARK_LAF).isInstance(laf), "the look and feel is " + DARK_LAF + ", is " + laf.getClass().getName());
        assertTrue(frame.isDisplayable(), "the same window is still open");
        assertSame(presenter, presenterOf(frame), "the window keeps its presenter: it was not rebuilt");
        assertTrue(java.util.Arrays.asList(java.awt.Window.getWindows()).contains(frame), "the same NotebookFrame instance");
        assertEquals(LINES, onEventThread(() -> lineTexts(frame)), "the texts of the lines are kept");
        assertTrue(dark.isSelected(), "Sombre is selected in the menu");
        assertEquals(choice("DARK"), chosenTheme(frame.shown()), "the view model holds the choice Sombre");
        assertEquals(List.of(choice("DARK")), saved, "the choice is remembered by the ThemeStore");

        String darkBase = hex(tokens.color(Theme.DARK, "surface.base"));
        assertEquals(darkBase, onEventThread(() -> hex(rowsPanel.getBackground())), "the panel of the rows is the dark surface.base");
        List<String> rowColours = onEventThread(() -> rows(rowsPanel).stream().map(row -> hex(row.getBackground())).toList());
        assertFalse(rowColours.isEmpty(), "the window shows rows");
        assertTrue(rowColours.contains(darkBase), "a line that is not active has the dark surface.base: " + rowColours);
        List<String> darkSurfaces = List.of(darkBase, hex(tokens.color(Theme.DARK, "surface.active")),
                hex(tokens.color(Theme.DARK, "surface.hover")));
        for (String colour : rowColours) {
            assertTrue(darkSurfaces.contains(colour), "every row has a dark surface, " + colour + " is not one of " + darkSurfaces);
        }

        JRadioButtonMenuItem light = themeItems(frame).get("themeItemLIGHT");
        onEventThread(() -> {
            light.doClick(0);
            return null;
        });
        flushEvents();
        Object back = onEventThread(UIManager::getLookAndFeel);
        assertTrue(type(LIGHT_LAF).isInstance(back), "Clair applies " + LIGHT_LAF + " again, is " + back.getClass().getName());
        assertEquals(hex(tokens.color(Theme.LIGHT, "surface.base")), onEventThread(() -> hex(rowsPanel.getBackground())),
                "the rows go back to the light surface.base");
        assertEquals(List.of(choice("DARK"), choice("LIGHT")), saved, "each choice is remembered");
    }

    @Test
    @Tag("ui")
    @DisplayName("Système : le thème effectif suit le détecteur (sombre si le détecteur répond sombre, clair s'il répond clair)")
    void systemFollowsTheDetector() throws Exception {
        requireDisplay();
        DesignTokens tokens = DesignTokens.load();
        List<Object> saved = new ArrayList<>();
        NotebookFrame frame = window(LINES, themeStore(choice("LIGHT"), saved), detector(true, Theme.DARK));
        JPanel rowsPanel = onEventThread(() -> (JPanel) named(frame, "rowsPanel"));
        JRadioButtonMenuItem system = themeItems(frame).get("themeItemSYSTEM");
        assertNotNull(system, "Système is offered with an available detector");
        onEventThread(() -> {
            system.doClick(0);
            return null;
        });
        flushEvents();
        Object laf = onEventThread(UIManager::getLookAndFeel);
        assertTrue(type(DARK_LAF).isInstance(laf), "Système with a detector answering dark applies " + DARK_LAF + ", is "
                + laf.getClass().getName());
        assertEquals(hex(tokens.color(Theme.DARK, "surface.base")), onEventThread(() -> hex(rowsPanel.getBackground())));
        assertTrue(system.isSelected(), "Système is selected");
        assertEquals(choice("SYSTEM"), chosenTheme(frame.shown()), "the choice in force is Système, not the theme it gave");
        assertEquals(List.of(choice("SYSTEM")), saved, "Système itself is remembered");

        NotebookFrame second = window(LINES, themeStore(choice("DARK"), new ArrayList<>()), detector(true, Theme.LIGHT));
        JRadioButtonMenuItem secondSystem = themeItems(second).get("themeItemSYSTEM");
        onEventThread(() -> {
            secondSystem.doClick(0);
            return null;
        });
        flushEvents();
        Object light = onEventThread(UIManager::getLookAndFeel);
        assertTrue(type(LIGHT_LAF).isInstance(light), "Système with a detector answering light applies " + LIGHT_LAF + ", is "
                + light.getClass().getName());
    }

    // ------------------------------------------------------------------------------------------------ remembering

    @Test
    @DisplayName("ThemeChoice : LIGHT, DARK, SYSTEM ; ThemeStore.noop() ne retient rien et donne Clair")
    void theChoiceAndTheNoopStore() throws Exception {
        Class<?> choice = type(THEME_CHOICE);
        assertTrue(choice.isEnum(), THEME_CHOICE + " is an enum");
        assertEquals(List.of("LIGHT", "DARK", "SYSTEM"),
                Arrays.stream(choice.getEnumConstants()).map(each -> ((Enum<?>) each).name()).toList());
        Class<?> store = type(THEME_STORE);
        assertTrue(store.isInterface(), THEME_STORE + " is an interface");
        Method noop = store.getMethod("noop");
        assertTrue(Modifier.isStatic(noop.getModifiers()), "ThemeStore.noop() is static");
        Object none = noop.invoke(null);
        assertNotNull(none, "ThemeStore.noop() returns a store");
        assertEquals(choice("LIGHT"), load(none), "the noop store gives Clair");
        save(none, choice("DARK"));
        assertEquals(choice("LIGHT"), load(none), "the noop store remembers nothing");
    }

    @Test
    @DisplayName("PreferencesThemeStore : nœud /calc/display, clé theme ; relu par un autre magasin ; illisible ou absent : Clair")
    void preferencesRememberTheChoiceUnderTheKeyTheme() throws Exception {
        Class<?> type = type(PREFERENCES_THEME_STORE);
        assertTrue(type(THEME_STORE).isAssignableFrom(type), PREFERENCES_THEME_STORE + " implements ThemeStore");
        Field nodePath = type.getField("NODE_PATH");
        assertTrue(Modifier.isStatic(nodePath.getModifiers()), "NODE_PATH is a constant");
        assertEquals("/calc/display", nodePath.get(null), "the node of the display mode, shared with it");
        assertTrue(Modifier.isStatic(type.getMethod("forCurrentUser").getModifiers()), "forCurrentUser() opens the node of the user");
        Constructor<?> constructor = type.getConstructor(Preferences.class);

        // A node of its own, never the one of the application, removed after the test.
        Preferences node = Preferences.userRoot().node("/calc-test-" + UUID.randomUUID());
        try {
            assertEquals(choice("LIGHT"), load(constructor.newInstance(node)), "nothing stored gives Clair");
            for (String name : List.of("LIGHT", "DARK", "SYSTEM")) {
                save(constructor.newInstance(node), choice(name));
                assertEquals(name, node.get("theme", null), "the key theme holds " + name);
                assertEquals(choice(name), load(constructor.newInstance(node)), "another store on the node reads " + name + " back");
            }
            node.put("theme", "VIOLET");
            assertEquals(choice("LIGHT"), load(constructor.newInstance(node)), "an unreadable value gives Clair");
            node.put("theme", "dark");
            assertEquals(choice("LIGHT"), load(constructor.newInstance(node)), "a value in another case is unreadable: Clair");
        } finally {
            node.removeNode();
            node.flush();
        }
        Object nowhere = constructor.newInstance((Object) null);
        save(nowhere, choice("DARK"));
        assertEquals(choice("LIGHT"), load(nowhere), "a store without node remembers nothing and does not fail");
    }

    @Test
    @Tag("ui")
    @DisplayName("Au démarrage suivant, le présentateur relit le choix ; Système sans détection disponible donne Clair")
    void theNextStartReadsTheChoiceBack() throws Exception {
        requireDisplay();
        NotebookFrame dark = window(LINES, themeStore(choice("DARK"), new ArrayList<>()), detector(false, null));
        assertEquals(choice("DARK"), chosenTheme(presenterOf(dark).currentView()), "the remembered Sombre is read back");
        assertTrue(themeItems(dark).get("themeItemDARK").isSelected(), "Sombre is selected in the menu at start");

        NotebookFrame system = window(LINES, themeStore(choice("SYSTEM"), new ArrayList<>()), detector(true, Theme.DARK));
        assertEquals(choice("SYSTEM"), chosenTheme(presenterOf(system).currentView()), "Système is read back with a detection");
        assertTrue(themeItems(system).get("themeItemSYSTEM").isSelected(), "Système is selected in the menu at start");

        NotebookFrame noDetection = window(LINES, themeStore(choice("SYSTEM"), new ArrayList<>()), detector(false, null));
        assertEquals(choice("LIGHT"), chosenTheme(presenterOf(noDetection).currentView()),
                "Système remembered where no detection is available gives Clair");
        Map<String, JRadioButtonMenuItem> items = themeItems(noDetection);
        assertFalse(items.containsKey("themeItemSYSTEM"), "Système is not offered");
        assertTrue(items.get("themeItemLIGHT").isSelected(), "Clair is selected");
    }

    // ------------------------------------------------------------------------------------------------ Windows detection

    @Test
    @DisplayName("WindowsSystemThemeDetector.parse : 0x0 sombre, 0x1 clair, sans la valeur ou en erreur : rien")
    void parseReadsTheOutputOfRegQuery() throws Exception {
        String header = "\r\nHKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize\r\n";
        assertEquals(Optional.of(Theme.DARK), parse(header + "    AppsUseLightTheme    REG_DWORD    0x0\r\n\r\n"));
        assertEquals(Optional.of(Theme.LIGHT), parse(header + "    AppsUseLightTheme    REG_DWORD    0x1\r\n\r\n"));
        assertEquals(Optional.of(Theme.DARK), parse(header.replace("\r\n", "\n") + "    AppsUseLightTheme    REG_DWORD    0x0\n"),
                "line endings do not matter");
        assertEquals(Optional.of(Theme.LIGHT), parse(header.replace("\r\n", "\n") + "    AppsUseLightTheme    REG_DWORD    0x1\n"));
        assertEquals(Optional.empty(), parse(header + "    SystemUsesLightTheme    REG_DWORD    0x0\r\n\r\n"),
                "an output without the value AppsUseLightTheme gives nothing");
        assertEquals(Optional.empty(), parse(header), "the key alone gives nothing");
        assertEquals(Optional.empty(), parse("ERROR: The system was unable to find the specified registry key or value.\r\n"),
                "an error gives nothing");
        assertEquals(Optional.empty(), parse("ERREUR : le système n'a pas trouvé la clé ou la valeur de registre spécifiée.\r\n"),
                "an error in French gives nothing");
        assertEquals(Optional.empty(), parse(""), "an empty output gives nothing");
    }

    @Test
    @DisplayName("WindowsSystemThemeDetector hors Windows (os.name ne commence pas par Windows) : indisponible, aucun thème")
    void theDetectorIsUnavailableOutsideWindows() throws Exception {
        Class<?> type = type(WINDOWS_DETECTOR);
        assertTrue(type(DETECTOR).isInterface(), DETECTOR + " is an interface");
        assertTrue(type(DETECTOR).isAssignableFrom(type), WINDOWS_DETECTOR + " implements " + DETECTOR);
        for (String os : List.of("Linux", "Mac OS X", "FreeBSD", "SunOS")) {
            Object detector = windowsDetector(os);
            assertFalse(available(detector), "unavailable under " + os);
            assertEquals(Optional.empty(), current(detector), "no theme under " + os);
        }
    }

    @Test
    @DisplayName("WindowsSystemThemeDetector réel : quand AppsUseLightTheme existe dans le registre, disponible et du même thème")
    void theRealDetectorReadsTheRegistry() throws Exception {
        Optional<Integer> value = registryValue();
        assumeTrue(value.isPresent(), "the value " + VALUE + " is not in the registry of this machine (or it has no registry)");
        Theme expected = value.get() == 0 ? Theme.DARK : Theme.LIGHT;
        Object detector = windowsDetector(System.getProperty("os.name"));
        assertTrue(available(detector), "the detector is available where " + VALUE + " exists");
        assertEquals(Optional.of(expected), current(detector), VALUE + " = " + value.get());
    }

    // ------------------------------------------------------------------------------------------------ title

    @Test
    @DisplayName("Titre : « Carnet sans titre — Calc », « Devis.calc.json — Calc », « (modifié) », « (lecture seule) », sans « • »")
    void theTitleOfTheWindow() throws Exception {
        NotebookStore store = store("titles");
        FakeDialogs dialogs = new FakeDialogs();
        NotebookPresenter presenter = presenter(store, store.load(), themeStore(choice("LIGHT"), new ArrayList<>()),
                detector(false, null), dialogs);
        assertEquals("Carnet sans titre — Calc", presenter.currentView().title());

        Path documents = Files.createDirectories(temp.resolve("documents"));
        presenter.onEnter("a = 2");
        dialogs.willSaveTo(documents.resolve("Devis.calc.json"));
        NotebookViewModel saved = presenter.onSaveAs(presenter.currentView().activeDraft());
        assertEquals("Devis.calc.json — Calc", saved.title(), "a saved document");

        NotebookViewModel modified = presenter.onEnter("a * 3");
        assertEquals("Devis.calc.json (modifié) — Calc", modified.title(), "after a change not saved");
        assertFalse(modified.title().contains("•"), "no bullet any more");

        Path future = documents.resolve("future").resolve("Devis.calc.json");
        Files.createDirectories(future.getParent());
        Files.writeString(future,
                "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\",\"later\":true}]}",
                StandardCharsets.UTF_8);
        dialogs.willOpen(future).willAnswer(NotebookDialogs.Unsaved.DISCARD);
        NotebookViewModel readOnly = presenter.onOpen(presenter.currentView().activeDraft());
        assertEquals("Devis.calc.json (lecture seule) — Calc", readOnly.title(), "a document from a newer version");
        for (String title : List.of(saved.title(), modified.title(), readOnly.title())) {
            assertFalse(title.contains("•"), "no bullet in " + title);
        }
    }

    @Test
    @Tag("ui")
    @DisplayName("Fenêtre : titre « Carnet sans titre — Calc » et aucun JLabel « Carnet de calcul »")
    void theWindowHasNoLabelCarnetDeCalcul() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(LINES, themeStore(choice("LIGHT"), new ArrayList<>()), detector(false, null));
        assertEquals("Carnet sans titre — Calc", onEventThread(frame::getTitle));
        List<String> labels = onEventThread(() -> descendants(frame.getRootPane()).stream()
                .filter(JLabel.class::isInstance)
                .map(each -> ((JLabel) each).getText())
                .filter(text -> text != null && text.strip().equals("Carnet de calcul"))
                .toList());
        assertEquals(List.of(), labels, "no JLabel reads « Carnet de calcul » any more");
    }

    // ------------------------------------------------------------------------------------------------ segmented control

    @Test
    @Tag("ui")
    @DisplayName("modeSelector : un conteneur « Affichage » de 4 JToggleButton dans un ButtonGroup, synchronisé avec le menu et Ctrl+M")
    void theModeSelectorIsASegmentedControl() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(LINES, themeStore(choice("LIGHT"), new ArrayList<>()), detector(false, null));
        Component selector = onEventThread(() -> named(frame, "modeSelector"));
        assertTrue(selector instanceof JPanel, "modeSelector is a JPanel, is " + selector.getClass().getName());
        assertEquals("Affichage", onEventThread(() -> selector.getAccessibleContext().getAccessibleName()),
                "the accessible name of the container");
        List<JToggleButton> buttons = onEventThread(() -> descendants((Container) selector).stream()
                .filter(JToggleButton.class::isInstance).map(JToggleButton.class::cast).toList());
        assertEquals(List.of("modeButtonAUTO", "modeButtonDECIMAL", "modeButtonFRACTION", "modeButtonSCIENTIFIC"),
                buttons.stream().map(Component::getName).toList(), "four toggle buttons, in the order of the modes");
        assertEquals(List.of("Auto", "Décimal", "Fraction", "Scientifique"), buttons.stream().map(JToggleButton::getText).toList());
        ButtonGroup group = groupOf(buttons.get(0));
        assertNotNull(group, "the buttons are in a ButtonGroup");
        for (JToggleButton button : buttons) {
            assertSame(group, groupOf(button), button.getName() + " is in the same ButtonGroup");
        }
        assertEquals(4, group.getButtonCount(), "the group holds the four buttons only");
        assertSelectedMode(frame, buttons, DisplayMode.AUTO);

        onEventThread(() -> {
            buttons.get(2).doClick(0);
            return null;
        });
        flushEvents();
        assertEquals(DisplayMode.FRACTION, frame.shown().displayMode(), "clicking Fraction changes the mode");
        assertSelectedMode(frame, buttons, DisplayMode.FRACTION);

        fireOnActiveLine(frame, NEXT_MODE);
        flushEvents();
        assertEquals(DisplayMode.SCIENTIFIC, frame.shown().displayMode(), "Ctrl+M goes to the next mode");
        assertSelectedMode(frame, buttons, DisplayMode.SCIENTIFIC);

        JMenuItem decimal = menuItem(frame, "modeItemDECIMAL");
        onEventThread(() -> {
            decimal.doClick(0);
            return null;
        });
        flushEvents();
        assertEquals(DisplayMode.DECIMAL, frame.shown().displayMode(), "the menu Affichage changes the mode");
        assertSelectedMode(frame, buttons, DisplayMode.DECIMAL);
    }

    // ------------------------------------------------------------------------------------------------ screenshots

    @Test
    @Tag("ui")
    @DisplayName("Captures : « tous » écrit les versions -clair et -sombre, qui diffèrent ; un thème seul garde les noms sans suffixe")
    void screenshotsInBothThemes() throws Exception {
        requireDisplay();
        Path all = temp.resolve("tous");
        ScreenshotGenerator.main(all.toString(), "tous", "1");
        List<String> written;
        try (var files = Files.list(all)) {
            written = files.map(file -> file.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("carnet-devis-clair.png", "carnet-devis-sombre.png", "carnet-vide-clair.png", "carnet-vide-sombre.png"),
                written, "« tous » writes the four suffixed pictures, and no picture without suffix");
        for (String name : List.of("carnet-devis", "carnet-vide")) {
            BufferedImage light = png(all.resolve(name + "-clair.png"));
            BufferedImage dark = png(all.resolve(name + "-sombre.png"));
            assertEquals(1100, dark.getWidth(), name);
            assertEquals(720, dark.getHeight(), name);
            assertTrue(differs(light, dark), "the light and dark pictures of " + name + " differ");
            assertTrue(meanLuminance(dark) < meanLuminance(light), "the dark picture of " + name + " is darker than the light one");
        }

        Path dark = temp.resolve("sombre");
        ScreenshotGenerator.main(dark.toString(), "sombre", "1");
        List<String> darkFiles;
        try (var files = Files.list(dark)) {
            darkFiles = files.map(file -> file.getFileName().toString()).sorted().toList();
        }
        assertEquals(List.of("carnet-devis.png", "carnet-vide.png"), darkFiles, "a single theme keeps the names without suffix");
        assertSamePixels(png(all.resolve("carnet-devis-sombre.png")), png(dark.resolve("carnet-devis.png")),
                "« sombre » paints what « tous » paints in dark");

        Path light = temp.resolve("clair");
        ScreenshotGenerator.main(light.toString(), "clair", "1");
        assertTrue(Files.isRegularFile(light.resolve("carnet-devis.png")), "« clair » still writes carnet-devis.png");
        assertTrue(Files.isRegularFile(light.resolve("carnet-vide.png")), "« clair » still writes carnet-vide.png");
        assertFalse(Files.exists(light.resolve("carnet-devis-clair.png")), "only « tous » suffixes the names");
        assertSamePixels(png(all.resolve("carnet-devis-clair.png")), png(light.resolve("carnet-devis.png")),
                "« clair » paints what « tous » paints in light");
    }

    // ------------------------------------------------------------------------------------------------ documentation

    @Test
    @DisplayName("GUIDE-UTILISATEUR.md : Affichage > Thème (Clair, Sombre, Système sous Windows) et le contrôle segmenté")
    void theGuideDescribesTheThemesAndTheSegmentedControl() throws IOException {
        String guide = read(GUIDE);
        List<String> sections = sections(guide);
        boolean described = sections.stream().anyMatch(section -> section.contains("Affichage") && section.contains("Thème")
                && section.contains("Clair") && section.contains("Sombre") && section.contains("Système")
                && section.contains("Windows"));
        assertTrue(described, "one section of the guide describes Affichage > Thème with Clair, Sombre, and Système under Windows");
        assertTrue(guide.toLowerCase(Locale.ROOT).contains("segment"), "the guide describes the segmented control of the modes");
    }

    @Test
    @DisplayName("RECETTE.md : section 8 avec une case non cochée sur le thème et Cmd+K sous macOS, sans section ## 10")
    void theRecetteHasAnUncheckedBoxForMacOs() throws IOException {
        String recette = read(RECETTE);
        assertFalse(Pattern.compile("(?m)^## 10\\b").matcher(recette).find(), "no section ## 10");
        Matcher section = Pattern.compile("(?ms)^## 8\\..*?(?=^## )").matcher(recette);
        assertTrue(section.find(), "RECETTE.md keeps its section ## 8.");
        boolean box = section.group().lines().anyMatch(line -> line.startsWith("- [ ] ")
                && line.toLowerCase(Locale.ROOT).contains("thème") && line.contains("Cmd+K") && line.contains("macOS"));
        assertTrue(box, "section 8 has an unchecked box about the theme and Cmd+K under macOS");
    }

    // ------------------------------------------------------------------------------------------------ helpers: window

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    private NotebookStore store(String name) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + name)));
        stores.add(store);
        return store;
    }

    /** A window as Main builds it, on the light theme, with a notebook of {@code lines} loaded in one piece. */
    private NotebookFrame window(List<String> lines, Object themeStore, Object detector) throws Exception {
        NotebookStore store = store(UUID.randomUUID().toString());
        if (!lines.isEmpty()) {
            store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        }
        LoadedNotebook loaded = store.load();
        NotebookPresenter presenter = presenter(store, loaded, themeStore, detector, NotebookDialogs.cancelling());
        NotebookFrame frame = onEventThread(() -> {
            ThemeManager.apply(Theme.LIGHT);
            return new NotebookFrame(presenter);
        });
        frames.add(frame);
        flushEvents();
        return frame;
    }

    /**
     * The presenter built by the factory or constructor of {@link NotebookPresenter} that takes a {@code ThemeStore} and a
     * {@code SystemThemeDetector}; the largest one whose other parameters can all be supplied.
     */
    private static NotebookPresenter presenter(NotebookStore store, LoadedNotebook loaded, Object themeStore, Object detector,
                                               NotebookDialogs dialogs) throws Exception {
        Class<?> storeType = type(THEME_STORE);
        Class<?> detectorType = type(DETECTOR);
        Map<Class<?>, Callable<Object>> supply = new HashMap<>();
        supply.put(NotebookStore.class, () -> store);
        supply.put(LoadedNotebook.class, () -> loaded);
        supply.put(DisplayModeStore.class, DisplayModeStore::noop);
        supply.put(NotebookDialogs.class, () -> dialogs);
        supply.put(Executor.class, NotebookPresenter::newRecalculationExecutor);
        supply.put(storeType, () -> themeStore);
        supply.put(detectorType, () -> detector);
        supply.put(type(DOCUMENT_SERVICE), () -> type(DOCUMENT_SERVICE)
                .getConstructor(NotebookStore.class, LoadedNotebook.class).newInstance(store, loaded));

        List<Executable> candidates = new ArrayList<>();
        for (Method method : NotebookPresenter.class.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == NotebookPresenter.class) {
                candidates.add(method);
            }
        }
        candidates.addAll(List.of(NotebookPresenter.class.getDeclaredConstructors()));
        Executable best = null;
        for (Executable candidate : candidates) {
            List<Class<?>> parameters = List.of(candidate.getParameterTypes());
            boolean themed = parameters.contains(storeType) && parameters.contains(detectorType);
            if (!themed || !supply.keySet().containsAll(parameters)
                    || parameters.size() != parameters.stream().distinct().count()) {
                continue;
            }
            if (best == null || score(candidate) > score(best)) {
                best = candidate;
            }
        }
        assertNotNull(best, "NotebookPresenter has a static factory or a constructor taking a ThemeStore and a SystemThemeDetector, "
                + "its other parameters among NotebookStore, LoadedNotebook, DisplayModeStore, NotebookDialogs, Executor, DocumentService");
        Object[] arguments = new Object[best.getParameterCount()];
        Class<?>[] types = best.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            arguments[i] = supply.get(types[i]).call();
        }
        best.setAccessible(true);
        try {
            return best instanceof Method method
                    ? (NotebookPresenter) method.invoke(null, arguments)
                    : (NotebookPresenter) ((Constructor<?>) best).newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    // Prefers the factory of the application (store and loaded notebook given, dialogs and executor too).
    private static int score(Executable candidate) {
        List<Class<?>> parameters = List.of(candidate.getParameterTypes());
        int score = parameters.size();
        if (parameters.contains(NotebookStore.class)) {
            score += 10;
        }
        if (parameters.contains(NotebookDialogs.class)) {
            score += 5;
        }
        return score;
    }

    private static NotebookPresenter presenterOf(NotebookFrame frame) throws Exception {
        for (Class<?> type = frame.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == NotebookPresenter.class && !Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    return (NotebookPresenter) field.get(frame);
                }
            }
        }
        throw new AssertionError("NotebookFrame holds its NotebookPresenter in a field");
    }

    private static Object chosenTheme(NotebookViewModel view) throws Exception {
        Class<?> choice = type(THEME_CHOICE);
        List<RecordComponent> components = Arrays.stream(NotebookViewModel.class.getRecordComponents())
                .filter(component -> component.getType() == choice).toList();
        assertEquals(1, components.size(), "NotebookViewModel has exactly one component of type ThemeChoice, the choice in force");
        return components.get(0).getAccessor().invoke(view);
    }

    private static Map<String, JRadioButtonMenuItem> themeItems(NotebookFrame frame) throws Exception {
        return onEventThread(() -> {
            JMenuBar bar = frame.getJMenuBar();
            assertNotNull(bar, "the window has a menu bar");
            JMenu display = null;
            for (int i = 0; i < bar.getMenuCount(); i++) {
                if (bar.getMenu(i) != null && "Affichage".equals(bar.getMenu(i).getText())) {
                    display = bar.getMenu(i);
                }
            }
            assertNotNull(display, "the menu Affichage");
            JMenu theme = null;
            for (Component each : display.getMenuComponents()) {
                if (each instanceof JMenu sub && "Thème".equals(sub.getText())) {
                    theme = sub;
                }
            }
            assertNotNull(theme, "the menu Affichage holds a sub-menu « Thème »");
            Map<String, JRadioButtonMenuItem> items = new java.util.LinkedHashMap<>();
            for (Component each : theme.getMenuComponents()) {
                if (each instanceof JMenuItem item) {
                    assertTrue(item instanceof JRadioButtonMenuItem, "« " + item.getText() + " » is a radio item");
                    items.put(item.getName(), (JRadioButtonMenuItem) item);
                }
            }
            for (Component each : allMenuItems(bar)) {
                if ("themeItemSYSTEM".equals(each.getName())) {
                    assertTrue(items.containsKey("themeItemSYSTEM"), "themeItemSYSTEM appears in Affichage > Thème only");
                }
            }
            return items;
        });
    }

    private static List<Component> allMenuItems(JMenuBar bar) {
        List<Component> all = new ArrayList<>();
        List<Component> pending = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            if (bar.getMenu(i) != null) {
                pending.add(bar.getMenu(i));
            }
        }
        while (!pending.isEmpty()) {
            Component each = pending.remove(0);
            all.add(each);
            if (each instanceof JMenu menu) {
                pending.addAll(List.of(menu.getMenuComponents()));
            }
        }
        return all;
    }

    private static JMenuItem menuItem(NotebookFrame frame, String name) throws Exception {
        return onEventThread(() -> allMenuItems(frame.getJMenuBar()).stream()
                .filter(each -> each instanceof JMenuItem && name.equals(each.getName()))
                .map(JMenuItem.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no menu item " + name)));
    }

    private static void assertSelectedMode(NotebookFrame frame, List<JToggleButton> buttons, DisplayMode mode) throws Exception {
        List<String> selected = onEventThread(() -> buttons.stream().filter(JToggleButton::isSelected).map(Component::getName).toList());
        assertEquals(List.of("modeButton" + mode.name()), selected, "the button of " + mode + " alone is selected");
        assertTrue(onEventThread(() -> menuItem(frame, "modeItem" + mode.name()).isSelected()),
                "the radio item of " + mode + " in the menu Affichage follows");
    }

    private static ButtonGroup groupOf(JToggleButton button) {
        ButtonModel model = button.getModel();
        return model instanceof DefaultButtonModel shared ? shared.getGroup() : null;
    }

    private static void fireOnActiveLine(NotebookFrame frame, KeyStroke stroke) throws Exception {
        JTextField field = onEventThread(() -> {
            List<JTextField> fields = descendants(frame.getContentPane()).stream()
                    .filter(each -> each instanceof JTextField && "lineField".equals(each.getName()))
                    .map(JTextField.class::cast).toList();
            return fields.get(frame.shown().activeIndex());
        });
        onEventThread(() -> {
            for (Component each = field; each != null; each = each.getParent()) {
                if (!(each instanceof JComponent component)) {
                    continue;
                }
                for (int condition : new int[] {JComponent.WHEN_FOCUSED, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT,
                        JComponent.WHEN_IN_FOCUSED_WINDOW}) {
                    if (component != field && condition == JComponent.WHEN_FOCUSED) {
                        continue;
                    }
                    Object key = component.getInputMap(condition).get(stroke);
                    Action action = key == null ? null : component.getActionMap().get(key);
                    if (action != null) {
                        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, key.toString()));
                        return null;
                    }
                }
            }
            throw new AssertionError("nothing is bound to " + stroke + " for the active line");
        });
    }

    private static List<String> lineTexts(NotebookFrame frame) {
        return descendants(frame.getContentPane()).stream()
                .filter(each -> each instanceof JTextField && "lineField".equals(each.getName()))
                .map(each -> ((JTextField) each).getText())
                .filter(text -> !text.isEmpty())
                .toList();
    }

    private static List<Component> rows(JPanel rowsPanel) {
        List<Component> named = descendants(rowsPanel).stream().filter(each -> "row".equals(each.getName())).toList();
        return named.isEmpty() ? List.of(rowsPanel.getComponents()) : named;
    }

    private static Component named(Container root, String name) {
        return descendants(root).stream().filter(each -> name.equals(each.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("no component named " + name));
    }

    private static List<Component> descendants(Container root) {
        List<Component> all = new ArrayList<>();
        List<Component> pending = new ArrayList<>(List.of(root.getComponents()));
        while (!pending.isEmpty()) {
            Component each = pending.remove(0);
            all.add(each);
            if (each instanceof Container container) {
                pending.addAll(List.of(container.getComponents()));
            }
        }
        return all;
    }

    private static <T> T onEventThread(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Error> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception exception) {
                failure.set(exception);
            } catch (Error thrown) {
                error.set(thrown);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    // Lets what the event thread queued after a click run before the test reads the window.
    private static void flushEvents() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers: types

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError(name + " does not exist yet", missing);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object choice(String name) {
        return Enum.valueOf((Class) type(THEME_CHOICE), name);
    }

    private static Object load(Object store) throws Exception {
        return invoke(type(THEME_STORE).getMethod("load"), store);
    }

    private static void save(Object store, Object choice) throws Exception {
        invoke(type(THEME_STORE).getMethod("save", type(THEME_CHOICE)), store, choice);
    }

    private static boolean available(Object detector) throws Exception {
        return (Boolean) invoke(type(DETECTOR).getMethod("available"), detector);
    }

    private static Optional<?> current(Object detector) throws Exception {
        Object current = invoke(type(DETECTOR).getMethod("current"), detector);
        assertTrue(current instanceof Optional<?>, "SystemThemeDetector.current() returns an Optional<Theme>");
        return (Optional<?>) current;
    }

    private static Optional<?> parse(String output) throws Exception {
        Method parse = type(WINDOWS_DETECTOR).getDeclaredMethod("parse", String.class);
        assertTrue(Modifier.isStatic(parse.getModifiers()), "WindowsSystemThemeDetector.parse(String) is static");
        parse.setAccessible(true);
        Object parsed = invoke(parse, null, output);
        assertTrue(parsed instanceof Optional<?>, "parse returns an Optional<Theme>");
        return (Optional<?>) parsed;
    }

    private static Object windowsDetector(String osName) throws Exception {
        Constructor<?> constructor = type(WINDOWS_DETECTOR).getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        try {
            return constructor.newInstance(osName);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    /** A ThemeStore that starts on {@code initial} and adds every saved choice to {@code saved}. */
    private static Object themeStore(Object initial, List<Object> saved) {
        Class<?> type = type(THEME_STORE);
        AtomicReference<Object> current = new AtomicReference<>(initial);
        return fake(type, (proxy, method, arguments) -> switch (method.getName()) {
            case "load" -> current.get();
            case "save" -> {
                saved.add(arguments[0]);
                current.set(arguments[0]);
                yield null;
            }
            default -> unexpected(proxy, method, arguments);
        });
    }

    /** A SystemThemeDetector that is {@code available} or not and answers {@code theme} when it is. */
    private static Object detector(boolean available, Theme theme) {
        return fake(type(DETECTOR), (proxy, method, arguments) -> switch (method.getName()) {
            case "available" -> available;
            case "current" -> available ? Optional.ofNullable(theme) : Optional.empty();
            default -> unexpected(proxy, method, arguments);
        });
    }

    private static Object fake(Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object unexpected(Object proxy, Method method, Object[] arguments) throws Throwable {
        switch (method.getName()) {
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == arguments[0];
            case "toString":
                return "fake " + proxy.getClass().getInterfaces()[0].getSimpleName();
            default:
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, arguments);
                }
                throw new UnsupportedOperationException(method.toString());
        }
    }

    /** The value AppsUseLightTheme as {@code reg query} finds it on this machine; empty without it or without a registry. */
    private static Optional<Integer> registryValue() {
        try {
            Process process = new ProcessBuilder("reg", "query", PERSONALIZE, "/v", VALUE).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                return Optional.empty();
            }
            Matcher value = Pattern.compile(VALUE + "\\s+REG_DWORD\\s+0x([0-9a-fA-F]+)").matcher(output);
            return value.find() ? Optional.of(Integer.parseInt(value.group(1), 16)) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers: files

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from each heading ## to the next one. */
    private static List<String> sections(String markdown) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            if (line.startsWith("## ") && current.length() > 0) {
                sections.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        sections.add(current.toString());
        return sections;
    }

    private static BufferedImage png(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        BufferedImage image = ImageIO.read(file.toFile());
        assertNotNull(image, file + " must be a readable PNG");
        return image;
    }

    private static boolean differs(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return true;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual, String message) {
        assertEquals(expected.getWidth(), actual.getWidth(), message);
        assertEquals(expected.getHeight(), actual.getHeight(), message);
        assertFalse(differs(expected, actual), message);
    }

    private static double meanLuminance(BufferedImage image) {
        double sum = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                sum += 0.2126 * ((rgb >> 16) & 0xFF) + 0.7152 * ((rgb >> 8) & 0xFF) + 0.0722 * (rgb & 0xFF);
            }
        }
        return sum / ((double) image.getWidth() * image.getHeight());
    }

    private static String hex(Color colour) {
        assertNotNull(colour, "a colour");
        return String.format(Locale.ROOT, "#%06X", colour.getRGB() & 0xFFFFFF);
    }
}
