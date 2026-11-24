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
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.Theme;
import calc.ui.notebook.NotebookShortcuts.Command;
import calc.ui.theme.ThemeManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.accessibility.Accessible;
import javax.accessibility.AccessibleContext;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.ListModel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of E10: the command palette of the desktop window (Ctrl+K, Cmd+K on macOS), SPEC §4.2 (Découverte).
 *
 * <p>The new types are reached by reflection, so that this class compiles, and fails with a clear message, before they exist:
 * <ul>
 *   <li>{@code calc.ui.notebook.CommandPalette}, the pure model (no Swing, no AWT in its source), with the nested record
 *       {@code CommandPalette.Entry(String id, String label, String shortcut, boolean enabled)} (the shortcut is {@code ""}
 *       for an entry without one), the public constructor {@code CommandPalette(List<Entry> entries)} and
 *       {@code List<Entry> filter(String query)}, the entries that match, in their order;</li>
 *   <li>{@code NotebookShortcuts.Command.COMMAND_PALETTE}, the key K with the menu modifier;</li>
 *   <li>in the window: a component named {@code commandPalette}, a direct child of the {@link JLayeredPane} of the frame, shown
 *       while the palette is open; in it a text component {@code commandPaletteSearch} and a {@link JList}
 *       {@code commandPaletteList} whose model holds the {@code CommandPalette.Entry} now listed; with no entry, a showing label
 *       of the palette reads « Aucune commande »; the Help menu item {@code commandPaletteItem}.</li>
 * </ul>
 * The keys are real key events handed to the component that has the keyboard focus, so a test that needs the focus is skipped
 * when the system gives none to a new window (as in {@code NotebookRowsAcceptanceTest}). Paths are relative to the repository
 * root, the working directory of the tests.
 */
@Tag("acceptance")
@DisplayName("Palette de commandes du bureau (Ctrl+K)")
final class CommandPaletteAcceptanceTest {
    private static final String PALETTE = "calc.ui.notebook.CommandPalette";
    private static final String ENTRY = "calc.ui.notebook.CommandPalette$Entry";
    private static final Path PALETTE_SOURCE = Path.of("src", "main", "java", "calc", "ui", "notebook", "CommandPalette.java");
    private static final Path GUIDE = Path.of("docs", "GUIDE-UTILISATEUR.md");
    private static final String NO_COMMAND = "Aucune commande";
    private static final String SEARCH_NAME = "Rechercher une commande";
    private static final long PATIENCE_SECONDS = 60;
    private static final int WIDTH = 940;
    private static final int HEIGHT = 640;

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private final Map<NotebookFrame, NotebookPresenter> presenters = new LinkedHashMap<>();
    private final Set<String> gated = ConcurrentHashMap.newKeySet();
    private final CountDownLatch gateReached = new CountDownLatch(1);
    private final CountDownLatch gateReleased = new CountDownLatch(1);

    @AfterEach
    void cleanUp() throws Exception {
        gateReleased.countDown();
        if (!frames.isEmpty()) {
            SwingUtilities.invokeAndWait(() -> frames.forEach(NotebookFrame::dispose));
        }
        for (NotebookStore store : stores) {
            store.close();
        }
    }

    // ================================================================================================ the model

    @Test
    @DisplayName("CommandPalette est un modèle pur de calc.ui.notebook : ni Swing ni AWT")
    void thePaletteModelIsPureJava() throws Exception {
        Class<?> palette = type(PALETTE);
        assertEquals("calc.ui.notebook", palette.getPackageName());
        Class<?> entry = type(ENTRY);
        assertTrue(entry.isRecord(), "CommandPalette.Entry is a record");
        assertEquals(List.of("id", "label", "shortcut", "enabled"),
                Arrays.stream(entry.getRecordComponents()).map(RecordComponent::getName).toList(),
                "an entry is {id, libellé, raccourci, activée}");
        assertTrue(Files.isRegularFile(PALETTE_SOURCE), PALETTE_SOURCE + " exists");
        String source = Files.readString(PALETTE_SOURCE, StandardCharsets.UTF_8);
        for (String forbidden : List.of("javax.swing", "java.awt")) {
            assertFalse(source.contains(forbidden), "CommandPalette.java uses no " + forbidden + ": it is a pure model");
        }
    }

    @Test
    @DisplayName("Le filtre ignore la casse et les accents et garde l'ordre ; vide, il garde tout")
    void theFilterIgnoresCaseAndAccentsAndKeepsTheOrder() throws Exception {
        List<Object> entries = List.of(
                entry("new", "Nouveau", "Ctrl+N", true),
                entry("save", "Enregistrer", "Ctrl+S", true),
                entry("saveAs", "Enregistrer sous…", "Ctrl+Maj+S", true),
                entry("exportCsv", "Exporter en CSV…", "", true),
                entry("undo", "Annuler", "Ctrl+Z", false),
                entry("modeDECIMAL", "Mode Décimal", "", true),
                entry("nextMode", "Mode suivant", "Ctrl+M", true),
                entry("themeLIGHT", "Thème : Clair", "", true),
                entry("themeDARK", "Thème : Sombre", "", true));
        Object palette = palette(entries);

        assertEquals(List.of("Thème : Sombre"), labels(filter(palette, "sombre")));
        assertEquals(List.of("Thème : Sombre"), labels(filter(palette, "SOMBRE")));
        assertEquals(List.of("Mode Décimal"), labels(filter(palette, "decimal")));
        assertEquals(List.of("Mode Décimal"), labels(filter(palette, "DÉCIMAL")));
        assertEquals(List.of("Enregistrer", "Enregistrer sous…"), labels(filter(palette, "enreg")));
        assertEquals(List.of("Thème : Clair", "Thème : Sombre"), labels(filter(palette, "theme")));
        assertEquals(List.of("Mode Décimal", "Mode suivant"), labels(filter(palette, "Mode")));
        assertEquals(labels(entries), labels(filter(palette, "")), "an empty search lists every entry, in order");
        assertEquals(List.of(), labels(filter(palette, "zzz")), "nothing matches");
        assertEquals(List.of(false), filter(palette, "annuler").stream().map(each -> (Boolean) component(each, "enabled")).toList(),
                "the filter keeps the state of an entry");
    }

    // ================================================================================================ the key

    @Test
    @DisplayName("Ctrl+K (Cmd+K sous macOS) est dans NotebookShortcuts, dans l'aide des raccourcis et dans le guide")
    void ctrlKIsAShortcutOfTheNotebookListedInTheHelpAndTheGuide() throws Exception {
        Command palette = paletteCommand();
        NotebookShortcuts windows = new NotebookShortcuts(InputEvent.CTRL_DOWN_MASK);
        NotebookShortcuts mac = new NotebookShortcuts(InputEvent.META_DOWN_MASK);
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), windows.stroke(palette));
        assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.META_DOWN_MASK), mac.stroke(palette),
                "the key follows the menu modifier: Cmd+K on macOS");
        assertEquals("Ctrl+K", windows.text(palette));
        assertFalse(palette.description().isBlank(), "the key says what it does");

        String help = NotebookHelp.shortcuts(windows);
        assertTrue(help.lines().anyMatch(line -> line.startsWith("Ctrl+K ") && line.contains(palette.description())),
                "Aide > Raccourcis clavier lists Ctrl+K: " + help);

        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String shortcuts = section(guide, "## Raccourcis");
        assertTrue(shortcuts.lines().anyMatch(line -> line.startsWith("|") && line.contains("`Ctrl+K`")),
                "the table of the keys of the guide has a row for `Ctrl+K`");
        assertTrue(guide.lines().anyMatch(line -> line.startsWith("#") && line.contains("Palette de commandes")),
                "the guide has a section Palette de commandes");
    }

    @Test
    @DisplayName("NotebookShortcutConflictTest reste vert avec Ctrl+K et Cmd+K")
    void theNewKeyClashesWithNoKeyOfATextField() throws Exception {
        paletteCommand();
        new NotebookShortcutConflictTest().noModifiedKeyOfTheNotebookIsBoundByATextFieldOfTheInstalledLookAndFeels();
    }

    // ================================================================================================ the entries

    @Test
    @Tag("ui")
    @DisplayName("Une entrée par élément des menus, dans leur ordre, libellé par une seule règle, raccourci et état du menu")
    void thePaletteListsEveryItemOfTheMenusWithItsShortcutAndState() throws Exception {
        requireDisplay();
        NotebookShortcuts shortcuts = new NotebookShortcuts(InputEvent.CTRL_DOWN_MASK);
        NotebookFrame frame = window(List.of("a = 2", "a * 3"), shortcuts);
        focusLine(frame, 1);
        openWithKey(frame, KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK);

        List<String[]> expected = onEdt(() -> expectedEntries(frame, shortcuts));
        assertTrue(expected.size() >= 25, "the four menus hold every command: " + expected.size());
        List<Object> listed = listedEntries(frame);
        assertEquals(expected.stream().map(each -> each[0]).toList(), labels(listed),
                "one entry for each item of Fichier, Édition, Affichage (Thème included) and Aide, in the order of the menus");
        assertEquals(expected.stream().map(each -> each[1]).toList(),
                listed.stream().map(each -> (String) component(each, "shortcut")).toList(),
                "each entry has the text of the key of its item, \"\" without one");
        assertEquals(expected.stream().map(each -> Boolean.valueOf(each[2])).toList(),
                listed.stream().map(each -> (Boolean) component(each, "enabled")).toList(),
                "each entry is enabled as its item is");

        Map<String, Object> byLabel = new LinkedHashMap<>();
        listed.forEach(each -> byLabel.put((String) component(each, "label"), each));
        for (String label : List.of("Mode Auto", "Mode Décimal", "Mode Fraction", "Mode Scientifique", "Mode suivant",
                "Thème : Clair", "Thème : Sombre", "Nouveau", "Ouvrir…", "Enregistrer", "Enregistrer sous…", "Exporter en CSV…",
                "Quitter", "Annuler", "Rétablir", "Insérer une ligne au-dessous", "Tout effacer", "Raccourcis clavier",
                "Palette de commandes…")) {
            assertTrue(byLabel.containsKey(label), "the palette lists « " + label + " »: " + byLabel.keySet());
        }
        assertEquals(onEdt(() -> menuItemNamed(frame, "themeItemSYSTEM") != null), byLabel.containsKey("Thème : Système"),
                "« Thème : Système » is listed exactly when the menu Thème offers Système");
        assertEquals("Ctrl+S", component(byLabel.get("Enregistrer"), "shortcut"));
        assertEquals("Ctrl+Maj+S", component(byLabel.get("Enregistrer sous…"), "shortcut"));
        assertEquals("Ctrl+M", component(byLabel.get("Mode suivant"), "shortcut"));
        assertEquals("Ctrl+Z", component(byLabel.get("Annuler"), "shortcut"));
        assertEquals("", component(byLabel.get("Mode Auto"), "shortcut"));
        assertEquals("", component(byLabel.get("Quitter"), "shortcut"));
        assertEquals(false, component(byLabel.get("Annuler"), "enabled"), "nothing to undo in a notebook just opened");
    }

    @Test
    @Tag("ui")
    @DisplayName("Sous macOS (masque Cmd), Cmd+K ouvre la palette et Enregistrer porte « ⌘S »")
    void withTheCommandModifierTheKeyIsCmdKAndTheShortcutsReadAsOnAMac() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2"), new NotebookShortcuts(InputEvent.META_DOWN_MASK));
        focusLine(frame, 0);
        openWithKey(frame, KeyEvent.VK_K, InputEvent.META_DOWN_MASK);

        Object save = listedEntries(frame).stream().filter(each -> "Enregistrer".equals(component(each, "label"))).findFirst()
                .orElseThrow(() -> new AssertionError("the palette lists Enregistrer"));
        assertEquals("⌘S", component(save, "shortcut"));
    }

    // ================================================================================================ the search

    @Test
    @Tag("ui")
    @DisplayName("La recherche de la fenêtre : « sombre », « decimal », « enreg », vide, et « Aucune commande »")
    void searchingInTheWindowFiltersTheListAndSaysWhenNothingMatches() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2", "a * 3"), defaultShortcuts());
        focusLine(frame, 1);
        openWithKey(frame, KeyEvent.VK_K, menuMask());
        List<String> all = labels(listedEntries(frame));

        search(frame, "sombre");
        assertEquals(List.of("Thème : Sombre"), labels(listedEntries(frame)));
        search(frame, "decimal");
        assertEquals(List.of("Mode Décimal"), labels(listedEntries(frame)));
        search(frame, "ENREG");
        assertEquals(List.of("Enregistrer", "Enregistrer sous…"), labels(listedEntries(frame)));
        search(frame, "");
        assertEquals(all, labels(listedEntries(frame)), "an empty search lists every entry");

        search(frame, "zzz");
        assertEquals(List.of(), labels(listedEntries(frame)), "nothing matches « zzz »");
        assertTrue(onEdt(() -> showingTexts(palette(frame)).contains(NO_COMMAND)),
                "the palette says « " + NO_COMMAND + " »: " + onEdt(() -> showingTexts(palette(frame))));
        search(frame, "enreg");
        assertFalse(onEdt(() -> showingTexts(palette(frame)).contains(NO_COMMAND)), "with entries, the message is gone");
    }

    // ================================================================================================ opening

    @Test
    @Tag("ui")
    @DisplayName("Ctrl+K ouvre la palette dans la fenêtre, sur le JLayeredPane, focus dans commandPaletteSearch, sans dialogue")
    void ctrlKOpensThePaletteInTheWindowWithTheFocusInTheSearch() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2", "a * 3"), defaultShortcuts());
        focusLine(frame, 1);
        assertFalse(onEdt(() -> isOpen(frame)), "the palette is closed at first");
        int dialogs = onEdt(CommandPaletteAcceptanceTest::showingDialogs);

        openWithKey(frame, KeyEvent.VK_K, menuMask());

        Component palette = onEdt(() -> palette(frame));
        assertSame(onEdt(frame::getLayeredPane), onEdt(palette::getParent), "the palette lies on the JLayeredPane of the window");
        assertEquals(dialogs, (int) onEdt(CommandPaletteAcceptanceTest::showingDialogs), "no dialog is opened");
        assertTrue(onEdt(() -> part(palette, "commandPaletteList") instanceof JList<?>), "commandPaletteList is a JList");
        assertTrue(onEdt(() -> part(palette, "commandPaletteSearch") instanceof JTextComponent),
                "commandPaletteSearch is a text field");
    }

    @Test
    @Tag("ui")
    @DisplayName("Aide > « Palette de commandes… » (commandPaletteItem) ouvre la palette")
    void theHelpMenuOpensThePalette() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2"), defaultShortcuts());
        JMenuItem item = onEdt(() -> {
            JMenu help = menu(frame, "Aide");
            for (Component each : help.getMenuComponents()) {
                if (each instanceof JMenuItem candidate && "commandPaletteItem".equals(candidate.getName())) {
                    return candidate;
                }
            }
            return null;
        });
        assertNotNull(item, "the menu Aide holds commandPaletteItem");
        assertEquals("Palette de commandes…", onEdt(item::getText));
        focusLine(frame, 0);
        int dialogs = onEdt(CommandPaletteAcceptanceTest::showingDialogs);

        onEdt(() -> {
            item.doClick();
            return null;
        });
        flushEvents();

        assertTrue(await(() -> isOpen(frame)), "the item opens the palette");
        assertEquals(dialogs, (int) onEdt(CommandPaletteAcceptanceTest::showingDialogs), "no dialog is opened");
        assertTrue(await(() -> focusOwner() == part(palette(frame), "commandPaletteSearch")), "the focus goes to the search");
    }

    // ================================================================================================ keys in the palette

    @Test
    @Tag("ui")
    @DisplayName("↑ et ↓ déplacent la sélection ; Entrée exécute l'entrée choisie comme son menu, ferme et rend le focus")
    void arrowsMoveTheSelectionAndEnterRunsItLikeTheMenuThenClosesAndGivesTheFocusBack() throws Exception {
        requireDisplay();
        List<String> lines = List.of("a = 1/4", "a * 3");
        NotebookFrame reference = window(lines, defaultShortcuts());
        NotebookFrame frame = window(lines, defaultShortcuts());
        focusLine(frame, 1);
        typeDraft(frame, 1, "a * 4");
        activateHidden(reference, 1);
        typeDraft(reference, 1, "a * 4");
        focusLine(frame, 1);

        openWithKey(frame, KeyEvent.VK_K, menuMask());
        search(frame, "mode");
        assertEquals(List.of("Mode Auto", "Mode Décimal", "Mode Fraction", "Mode Scientifique", "Mode suivant"),
                labels(listedEntries(frame)));
        JList<?> list = onEdt(() -> (JList<?>) part(palette(frame), "commandPaletteList"));
        int start = onEdt(list::getSelectedIndex);
        press(KeyEvent.VK_DOWN, 0);
        assertEquals(start + 1, (int) onEdt(list::getSelectedIndex), "↓ selects the next entry");
        press(KeyEvent.VK_DOWN, 0);
        assertEquals(start + 2, (int) onEdt(list::getSelectedIndex), "↓ again");
        press(KeyEvent.VK_UP, 0);
        assertEquals(start + 1, (int) onEdt(list::getSelectedIndex), "↑ selects the previous entry");
        selectWithArrows(frame, "Mode Scientifique");

        press(KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);

        assertTrue(await(() -> !isOpen(frame)), "Enter closes the palette");
        assertTrue(await(() -> focusOwner() == frame.row(1).field), "the focus goes back to the line the palette was opened from");
        onEdt(() -> {
            menuItemNamed(reference, "modeItemSCIENTIFIC").doClick();
            return null;
        });
        awaitSettled(reference);
        assertEquals(DisplayMode.SCIENTIFIC, onEdt(() -> frame.shown().displayMode()), "Enter ran the selected entry");
        assertTrue(onEdt(() -> menuItemNamed(frame, "modeItemSCIENTIFIC").isSelected()), "the menu follows");
        assertEquals("a * 4", onEdt(() -> frame.row(1).field.getText()), "the line keeps what is typed in it");
        assertSameState(reference, frame, "Mode Scientifique from the palette does what Affichage > Scientifique does");
    }

    @Test
    @Tag("ui")
    @DisplayName("Entrée sur « Insérer une ligne au-dessous » fait exactement ce que fait l'élément de menu")
    void enterRunsAnEntryExactlyAsItsMenuItem() throws Exception {
        requireDisplay();
        List<String> lines = List.of("a = 2", "a * 3");
        NotebookFrame reference = window(lines, defaultShortcuts());
        NotebookFrame frame = window(lines, defaultShortcuts());
        activateHidden(reference, 0);
        focusLine(frame, 0);

        openWithKey(frame, KeyEvent.VK_K, menuMask());
        search(frame, "dessous");
        assertEquals(List.of("Insérer une ligne au-dessous"), labels(listedEntries(frame)));
        selectWithArrows(frame, "Insérer une ligne au-dessous");
        press(KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);

        onEdt(() -> {
            menuItemNamed(reference, "insertBelowItem").doClick();
            return null;
        });
        awaitSettled(reference);
        assertTrue(await(() -> !isOpen(frame)), "Enter closes the palette");
        assertEquals(List.of("a = 2", "", "a * 3"), windowTexts(frame), "a line is inserted below line 1");
        assertSameState(reference, frame, "the entry does what Édition > Insérer une ligne au-dessous does");
    }

    @Test
    @Tag("ui")
    @DisplayName("Échap ferme la palette sans rien exécuter et sans restaurer le texte de la ligne")
    void escapeClosesThePaletteAndKeepsWhatIsTyped() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2", "a * 3"), defaultShortcuts());
        focusLine(frame, 1);
        typeDraft(frame, 1, "a * 4");
        NotebookViewModel before = onEdt(frame::shown);

        openWithKey(frame, KeyEvent.VK_K, menuMask());
        search(frame, "fraction");
        press(KeyEvent.VK_ESCAPE, 0);
        awaitSettled(frame);

        assertTrue(await(() -> !isOpen(frame)), "Escape closes the palette");
        assertEquals("a * 4", onEdt(() -> frame.row(1).field.getText()), "the line keeps what is typed in it");
        assertEquals("a * 4", onEdt(() -> frame.shown().activeDraft()), "the presenter keeps the draft");
        assertEquals(DisplayMode.AUTO, onEdt(() -> frame.shown().displayMode()), "nothing is run");
        assertEquals(before.rows().stream().map(NotebookRow::text).toList(),
                onEdt(() -> frame.shown().rows().stream().map(NotebookRow::text).toList()), "the notebook is unchanged");
        assertEquals(before.statusMessage(), onEdt(() -> frame.shown().statusMessage()), "Escape did not reach the line");
    }

    @Test
    @Tag("ui")
    @DisplayName("Échap dans la palette n'interrompt pas un calcul en cours")
    void escapeInThePaletteDoesNotInterruptACalculation() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2", "a * 3"), defaultShortcuts());
        focusLine(frame, 0);
        typeDraft(frame, 0, "a = 5");
        gated.add("a * 3");
        press(KeyEvent.VK_ENTER, 0);
        assertTrue(gateReached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the recalculation reaches « a * 3 »");
        assertTrue(await(() -> focusOwner() == frame.row(1).field), "Enter went to line 2");
        assertTrue(onEdt(frame::isCalculating), "a calculation is under way");

        openWithKey(frame, KeyEvent.VK_K, menuMask());
        press(KeyEvent.VK_ESCAPE, 0);
        assertTrue(await(() -> !isOpen(frame)), "Escape closes the palette");
        assertTrue(onEdt(frame::isCalculating), "the calculation goes on");
        assertFalse(onEdt(() -> frame.statusLabel().getText()).contains("interrompu"),
                "the calculation is not interrupted: " + onEdt(() -> frame.statusLabel().getText()));

        gateReleased.countDown();
        awaitSettled(frame);
        assertEquals("15", onEdt(() -> frame.shown().rows().get(1).result()), "the calculation ends");
        assertFalse(onEdt(() -> frame.shown().rows().stream().anyMatch(row -> NotebookPresenter.INTERRUPTED_LINE.equals(row.result()))),
                "no line says it was not calculated");
    }

    @Test
    @Tag("ui")
    @DisplayName("Annuler sans historique est affiché désactivé et Entrée ne l'exécute pas")
    void aDisabledEntryIsShownDisabledAndEnterDoesNotRunIt() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("1 + 1"), defaultShortcuts());
        focusLine(frame, 1);
        typeDraft(frame, 1, "2 + 2");
        assertFalse(onEdt(() -> menuItemNamed(frame, "undoItem").isEnabled()), "Édition > Annuler is disabled");
        String status = onEdt(() -> frame.shown().statusMessage());

        openWithKey(frame, KeyEvent.VK_K, menuMask());
        search(frame, "annuler");
        List<Object> listed = listedEntries(frame);
        assertEquals(List.of("Annuler"), labels(listed));
        assertEquals(false, component(listed.get(0), "enabled"), "the entry is disabled, as its menu item");
        JList<?> list = onEdt(() -> (JList<?>) part(palette(frame), "commandPaletteList"));
        assertFalse(onEdt(() -> rendered(list, 0).isEnabled()), "the entry is shown disabled");

        selectWithArrows(frame, "Annuler");
        press(KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);

        assertEquals("2 + 2", onEdt(() -> frame.row(1).field.getText()), "undo did not take the draft out");
        assertEquals("2 + 2", onEdt(() -> frame.shown().activeDraft()));
        assertEquals(status, onEdt(() -> frame.shown().statusMessage()), "undo was not run");
        assertEquals(List.of("1 + 1"), windowTexts(frame));
    }

    // ================================================================================================ accessibility

    @Test
    @Tag("ui")
    @DisplayName("Nom accessible « Rechercher une commande » ; chaque entrée : son libellé suivi de son raccourci")
    void theSearchAndEveryEntryHaveAnAccessibleName() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2"), new NotebookShortcuts(InputEvent.CTRL_DOWN_MASK));
        focusLine(frame, 0);
        openWithKey(frame, KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK);

        Component search = onEdt(() -> part(palette(frame), "commandPaletteSearch"));
        assertEquals(SEARCH_NAME, onEdt(() -> search.getAccessibleContext().getAccessibleName()));

        JList<?> list = onEdt(() -> (JList<?>) part(palette(frame), "commandPaletteList"));
        List<Object> listed = listedEntries(frame);
        List<String> names = onEdt(() -> {
            List<String> all = new ArrayList<>();
            AccessibleContext context = list.getAccessibleContext();
            for (int i = 0; i < listed.size(); i++) {
                Accessible child = context.getAccessibleChild(i);
                all.add(child == null ? null : child.getAccessibleContext().getAccessibleName());
            }
            return all;
        });
        boolean sawShortcut = false;
        for (int i = 0; i < listed.size(); i++) {
            String label = (String) component(listed.get(i), "label");
            String shortcut = (String) component(listed.get(i), "shortcut");
            String name = names.get(i);
            assertNotNull(name, "the entry « " + label + " » has an accessible name");
            if (shortcut.isEmpty()) {
                assertEquals(label, name, "an entry without a key is named by its label");
            } else {
                sawShortcut = true;
                assertTrue(name.startsWith(label) && name.endsWith(shortcut) && name.length() > label.length() + shortcut.length(),
                        "the entry « " + label + " » is named by its label followed by its key " + shortcut + ": « " + name + " »");
            }
        }
        assertTrue(sawShortcut, "some entries have a key");
        int save = labels(listed).indexOf("Enregistrer");
        assertTrue(save >= 0, "Enregistrer is listed");
        assertTrue(names.get(save).startsWith("Enregistrer") && names.get(save).endsWith("Ctrl+S"), names.get(save));
    }

    // ================================================================================================ the window, built and driven

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    private static NotebookShortcuts defaultShortcuts() {
        return new NotebookShortcuts(menuMask());
    }

    // The modifier of the menu shortcuts of this system: Ctrl on Windows and Linux, Cmd on macOS.
    private static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    // A window as Main builds it, in the light theme, on a store holding lines, laid out once every line is calculated.
    private NotebookFrame window(List<String> lines, NotebookShortcuts shortcuts) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        NotebookFrame frame = onEdt(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            NotebookPresenter presenter = NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling(),
                    NotebookPresenter.newRecalculationExecutor(), this::gate);
            NotebookFrame built;
            // The JDK lists a window as visible while its constructor runs; Window.getWindows() takes this lock.
            synchronized (Window.class) {
                built = new NotebookFrame(presenter, shortcuts);
            }
            frames.add(built);
            presenters.put(built, presenter);
            built.pack();
            Insets border = built.getInsets();
            built.setSize(WIDTH + border.left + border.right, HEIGHT + border.top + border.bottom);
            built.validate();
            return built;
        });
        awaitSettled(frame);
        return frame;
    }

    // Holds the line the test gated until it is released, even when the calculating thread is interrupted.
    private LineResult gate(String text, Supplier<LineResult> evaluation) {
        if (gated.remove(text)) {
            gateReached.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
            boolean interrupted = false;
            try {
                while (true) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new AssertionError("nobody released the gated line");
                    }
                    try {
                        if (gateReleased.await(left, TimeUnit.NANOSECONDS)) {
                            break;
                        }
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return evaluation.get();
    }

    // Shows the window and puts the keyboard focus in the field of the row (from 0), which makes its line active.
    private static void focusLine(NotebookFrame frame, int row) throws Exception {
        onEdt(() -> {
            if (!frame.isShowing()) {
                frame.setLocationRelativeTo(null);
                frame.setVisible(true);
            }
            frame.toFront();
            frame.row(row).field.requestFocusInWindow();
            return null;
        });
        assumeTrue(await(() -> frame.row(row).field.isFocusOwner()),
                "a system that gives no focus to a new window: the keys cannot be observed");
        awaitSettled(frame);
        assertEquals(row, (int) onEdt(() -> frame.shown().activeIndex()), "line " + (row + 1) + " is active");
    }

    // What the mouse does in a window that is not shown: a focus gained on the field of the row activates its line.
    private static void activateHidden(NotebookFrame frame, int row) throws Exception {
        onEdt(() -> {
            if (frame.shown().activeIndex() != row) {
                JTextComponent field = frame.row(row).field;
                for (FocusListener listener : field.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
                }
            }
            return null;
        });
        awaitSettled(frame);
        assertEquals(row, (int) onEdt(() -> frame.shown().activeIndex()), "line " + (row + 1) + " of the reference is active");
    }

    // Types text into the field of the row, as the keyboard does, without validating it; waits for its preview.
    private void typeDraft(NotebookFrame frame, int row, String text) throws Exception {
        onEdt(() -> {
            frame.row(row).field.setText(text);
            return null;
        });
        awaitIdle(frame);
        assertEquals(text, onEdt(() -> frame.shown().activeDraft()), "the presenter knows what is typed");
    }

    private void awaitIdle(NotebookFrame frame) throws Exception {
        NotebookPresenter presenter = presenters.get(frame);
        assertTrue(await(() -> !presenter.isCalculating() && !presenter.isPreviewing()), "the preview has arrived");
        awaitSettled(frame);
    }

    // The key of the menu modifier, pressed in the line that has the focus: the palette opens with the focus in its search.
    private static void openWithKey(NotebookFrame frame, int keyCode, int modifiers) throws Exception {
        press(keyCode, modifiers);
        assertTrue(await(() -> isOpen(frame)), "the key " + KeyStroke.getKeyStroke(keyCode, modifiers) + " opens the palette");
        assertTrue(await(() -> focusOwner() == part(palette(frame), "commandPaletteSearch")),
                "the focus is in commandPaletteSearch, not in " + onEdt(() -> describe(focusOwner())));
    }

    // A key pressed on the keyboard: the event goes to the component that has the focus, through its listeners and bindings.
    private static void press(int keyCode, int modifiers) throws Exception {
        onEdt(() -> {
            Component owner = focusOwner();
            assertNotNull(owner, "a component of the window has the keyboard focus");
            owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode,
                    KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        flushEvents();
    }

    // Types the search as the keyboard would: the text of commandPaletteSearch changes and the list follows.
    private static void search(NotebookFrame frame, String query) throws Exception {
        onEdt(() -> {
            ((JTextComponent) part(palette(frame), "commandPaletteSearch")).setText(query);
            return null;
        });
        flushEvents();
    }

    // Presses ↓ until the entry with this label is selected.
    private static void selectWithArrows(NotebookFrame frame, String label) throws Exception {
        JList<?> list = onEdt(() -> (JList<?>) part(palette(frame), "commandPaletteList"));
        int size = onEdt(() -> list.getModel().getSize());
        for (int i = 0; i <= size + 1; i++) {
            Object selected = onEdt(list::getSelectedValue);
            if (selected != null && label.equals(component(selected, "label"))) {
                return;
            }
            press(KeyEvent.VK_DOWN, 0);
        }
        fail("↓ never selects « " + label + " »");
    }

    private static Component focusOwner() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }

    private static String describe(Component component) {
        return component == null ? "nothing" : component.getClass().getSimpleName() + " " + component.getName();
    }

    private static int showingDialogs() {
        int count = 0;
        for (Window window : Window.getWindows()) {
            if (window instanceof Dialog && window.isShowing()) {
                count++;
            }
        }
        return count;
    }

    // ================================================================================================ reading the window

    private static boolean isOpen(NotebookFrame frame) {
        return descendants(frame.getLayeredPane()).stream()
                .anyMatch(each -> "commandPalette".equals(each.getName()) && each.isShowing());
    }

    // The palette now shown.
    private static Component palette(NotebookFrame frame) {
        List<Component> found = descendants(frame.getLayeredPane()).stream()
                .filter(each -> "commandPalette".equals(each.getName()) && each.isShowing()).toList();
        assertEquals(1, found.size(), "one shown component named commandPalette");
        return found.get(0);
    }

    // The entries commandPaletteList now holds.
    private static List<Object> listedEntries(NotebookFrame frame) throws Exception {
        Class<?> entry = type(ENTRY);
        return onEdt(() -> {
            JList<?> list = (JList<?>) part(palette(frame), "commandPaletteList");
            ListModel<?> model = list.getModel();
            List<Object> entries = new ArrayList<>();
            for (int i = 0; i < model.getSize(); i++) {
                Object each = model.getElementAt(i);
                assertTrue(entry.isInstance(each), "commandPaletteList holds CommandPalette.Entry, not " + each);
                entries.add(each);
            }
            return entries;
        });
    }

    // What the entries of the palette must be, read from the menu bar: label, text of the key, enabled.
    private static List<String[]> expectedEntries(NotebookFrame frame, NotebookShortcuts shortcuts) {
        JMenuBar bar = frame.getJMenuBar();
        List<String> menus = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            menus.add(bar.getMenu(i).getText());
        }
        assertEquals(List.of("Fichier", "Édition", "Affichage", "Aide"), menus, "the menus of the window");
        List<String[]> expected = new ArrayList<>();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            for (JMenuItem item : leafItems(bar.getMenu(i))) {
                String name = item.getName() == null ? "" : item.getName();
                String label = name.startsWith("modeItem") ? "Mode " + item.getText()
                        : name.startsWith("themeItem") ? "Thème : " + item.getText()
                        : item.getText();
                String key = "";
                if (item.getAccelerator() != null) {
                    Command command = Arrays.stream(Command.values())
                            .filter(each -> item.getAccelerator().equals(shortcuts.stroke(each))).findFirst()
                            .orElseThrow(() -> new AssertionError("the key of « " + item.getText() + " » is a key of NotebookShortcuts"));
                    key = shortcuts.text(command);
                }
                expected.add(new String[] {label, key, String.valueOf(item.isEnabled())});
            }
        }
        return expected;
    }

    // The items of a menu that run something, in order, those of its sub-menus in their place.
    private static List<JMenuItem> leafItems(JMenu menu) {
        List<JMenuItem> items = new ArrayList<>();
        for (Component each : menu.getMenuComponents()) {
            if (each instanceof JMenu sub) {
                items.addAll(leafItems(sub));
            } else if (each instanceof JMenuItem item) {
                items.add(item);
            }
        }
        return items;
    }

    private static JMenu menu(NotebookFrame frame, String text) {
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            if (text.equals(bar.getMenu(i).getText())) {
                return bar.getMenu(i);
            }
        }
        throw new AssertionError("no menu " + text);
    }

    private static JMenuItem menuItemNamed(NotebookFrame frame, String name) {
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            for (JMenuItem item : leafItems(bar.getMenu(i))) {
                if (name.equals(item.getName())) {
                    return item;
                }
            }
        }
        return null;
    }

    // The component a cell of the list is painted with.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Component rendered(JList list, int index) {
        return list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
    }

    // The texts the palette shows outside its search field.
    private static List<String> showingTexts(Component palette) {
        List<String> texts = new ArrayList<>();
        List<Component> all = new ArrayList<>(List.of(palette));
        if (palette instanceof Container container) {
            all.addAll(descendants(container));
        }
        for (Component each : all) {
            if (!each.isShowing() || "commandPaletteSearch".equals(each.getName())) {
                continue;
            }
            if (each instanceof JLabel label && label.getText() != null) {
                texts.add(label.getText().strip());
            } else if (each instanceof AbstractButton button && button.getText() != null) {
                texts.add(button.getText().strip());
            } else if (each instanceof JTextComponent text) {
                texts.add(text.getText().strip());
            }
        }
        return texts;
    }

    private static void assertSameState(NotebookFrame reference, NotebookFrame frame, String message) throws Exception {
        NotebookViewModel expected = onEdt(reference::shown);
        NotebookViewModel actual = onEdt(frame::shown);
        assertEquals(expected.rows().stream().map(NotebookRow::text).toList(), actual.rows().stream().map(NotebookRow::text).toList(), message);
        assertEquals(expected.rows().stream().map(NotebookRow::result).toList(), actual.rows().stream().map(NotebookRow::result).toList(), message);
        assertEquals(expected.activeIndex(), actual.activeIndex(), message);
        assertEquals(expected.activeDraft(), actual.activeDraft(), message);
        assertEquals(expected.displayMode(), actual.displayMode(), message);
        assertEquals(expected.statusMessage(), actual.statusMessage(), message);
        assertEquals(expected.canUndo(), actual.canUndo(), message);
        assertEquals(expected.canRedo(), actual.canRedo(), message);
    }

    // The lines of the notebook the window shows: every row but the new line at the end.
    private static List<String> windowTexts(NotebookFrame frame) throws Exception {
        List<String> rows = onEdt(() -> frame.shown().rows().stream().map(NotebookRow::text).toList());
        return rows.subList(0, rows.size() - 1);
    }

    // The one component under root with this name.
    private static Component part(Component root, String name) {
        List<Component> found = root instanceof Container container
                ? descendants(container).stream().filter(each -> name.equals(each.getName())).toList()
                : List.of();
        assertEquals(1, found.size(), "the palette has one component named " + name + ", has " + found.size());
        return found.get(0);
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

    // ================================================================================================ waiting

    // Waits until nothing is being calculated and every row is built, then lays the window out again.
    private static void awaitSettled(NotebookFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!onEdt(() -> !frame.isCalculating() && frame.rowCount() == frame.shown().rows().size())) {
            if (System.nanoTime() > deadline) {
                fail("the notebook is still being calculated after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(5);
        }
        flushEvents();
        onEdt(() -> {
            frame.validate();
            return null;
        });
    }

    // Whether the condition holds within 10 s, read on the event thread.
    private static boolean await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            flushEvents();
            if (onEdt(condition)) {
                return true;
            }
            Thread.sleep(5);
        }
        return onEdt(condition);
    }

    private static void flushEvents() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        Throwable thrown = failure.get();
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        return result.get();
    }

    // ================================================================================================ the new types, by reflection

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError(name + " does not exist yet (task E10, SPEC §4.2 Découverte)", missing);
        }
    }

    private static Command paletteCommand() {
        return Arrays.stream(Command.values()).filter(each -> each.name().equals("COMMAND_PALETTE")).findFirst()
                .orElseThrow(() -> new AssertionError("NotebookShortcuts.Command.COMMAND_PALETTE does not exist yet (task E10)"));
    }

    private static Object entry(String id, String label, String shortcut, boolean enabled) throws Exception {
        Constructor<?> constructor = type(ENTRY).getDeclaredConstructor(String.class, String.class, String.class, boolean.class);
        constructor.setAccessible(true);
        return construct(constructor, id, label, shortcut, enabled);
    }

    private static Object palette(List<Object> entries) throws Exception {
        Constructor<?> constructor = type(PALETTE).getConstructor(List.class);
        return construct(constructor, entries);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> filter(Object palette, String query) throws Exception {
        Method filter = type(PALETTE).getMethod("filter", String.class);
        try {
            return List.copyOf((List<Object>) filter.invoke(palette, query));
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    private static Object construct(Constructor<?> constructor, Object... arguments) throws Exception {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    // A component of a CommandPalette.Entry, by name.
    private static Object component(Object entry, String name) {
        for (RecordComponent each : entry.getClass().getRecordComponents()) {
            if (each.getName().equals(name)) {
                try {
                    Method accessor = each.getAccessor();
                    accessor.setAccessible(true);
                    return accessor.invoke(entry);
                } catch (ReflectiveOperationException failure) {
                    throw new AssertionError(failure);
                }
            }
        }
        throw new AssertionError("CommandPalette.Entry has no component " + name);
    }

    private static List<String> labels(List<Object> entries) {
        return entries.stream().map(each -> (String) component(each, "label")).toList();
    }

    // The text from a heading to the next heading of the same level.
    private static String section(String markdown, String heading) {
        int start = markdown.indexOf(heading + "\n");
        assertTrue(start >= 0, "the guide has a section « " + heading + " »");
        int end = markdown.indexOf("\n## ", start + heading.length());
        return end < 0 ? markdown.substring(start) : markdown.substring(start, end);
    }
}
