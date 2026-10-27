package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import calc.ui.notebook.NotebookShortcuts.Command;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The File menu of the real window (a display is needed): the items and their keys with the menu modifier of each system,
 * the title and its modified marker through the keyboard, the closing of the window through the confirmation. The dialogs
 * are {@link FakeDialogs}: no real dialog is ever opened.
 */
@Tag("ui")
@DisplayName("Menu Fichier de la fenêtre du carnet (nécessite un affichage)")
class NotebookFrameFilesTest {
    private static final int MENU_MASK = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

    @TempDir
    Path root;

    private Path documents;
    private NotebookStore store;
    private FakeDialogs dialogs;
    private NotebookFrame frame;

    @BeforeEach
    void start() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
        documents = Files.createDirectories(root.resolve("documents"));
        store = NotebookStore.open(Files.createDirectories(root.resolve("data")));
        dialogs = new FakeDialogs();
        frame = open(MENU_MASK);
    }

    @AfterEach
    void stop() throws Exception {
        if (frame != null) {
            NotebookFrame toDispose = frame;
            SwingUtilities.invokeAndWait(toDispose::dispose);
        }
        if (store != null) {
            store.close();
        }
    }

    private NotebookFrame open(int mask) throws Exception {
        NotebookFrame[] holder = new NotebookFrame[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new NotebookFrame(
                NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs), new NotebookShortcuts(mask)));
        return holder[0];
    }

    // ------------------------------------------------------------------------------------------------ the menu

    @Test
    @DisplayName("le menu Fichier est le premier : Nouveau, Ouvrir, Enregistrer, Enregistrer sous, exports, Quitter")
    void theFileMenuIsFirstAndComplete() {
        JMenu file = frame.getJMenuBar().getMenu(0);
        assertEquals("Fichier", file.getText());
        assertEquals(KeyEvent.VK_F, file.getMnemonic());
        List<String> items = new ArrayList<>();
        for (java.awt.Component component : file.getMenuComponents()) {
            if (component instanceof JMenuItem item) {
                items.add(item.getText());
            }
        }
        assertEquals(List.of("Nouveau", "Ouvrir…", "Enregistrer", "Enregistrer sous…", "Exporter en Markdown…", "Exporter en CSV…",
                "Quitter"), items);
        assertNull(item("exportMarkdownItem", frame).getAccelerator());
        assertNull(item("quitItem", frame).getAccelerator());
    }

    @Test
    @DisplayName("les raccourcis du menu suivent le modificateur de chaque système : Ctrl ou Cmd, jamais les deux")
    void theFileShortcutsHonourTheMenuModifierOfEachSystem() throws Exception {
        runOnEdt(frame::dispose);
        frame = null;
        int shift = InputEvent.SHIFT_DOWN_MASK;
        for (int mask : new int[] {InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            int other = mask == InputEvent.CTRL_DOWN_MASK ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            NotebookFrame candidate = open(mask);
            try {
                assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_N, mask), item("newItem", candidate).getAccelerator());
                assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), item("openItem", candidate).getAccelerator());
                assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask), item("saveItem", candidate).getAccelerator());
                assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask | shift), item("saveAsItem", candidate).getAccelerator());
                // And the same keys work from the line being typed, whatever the look and feel binds in a text field.
                JComponent field = candidate.row(0).field;
                for (int key : new int[] {KeyEvent.VK_N, KeyEvent.VK_O, KeyEvent.VK_S}) {
                    assertNotNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, mask)), KeyEvent.getKeyText(key));
                    assertNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, other)), KeyEvent.getKeyText(key));
                }
                assertNotNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_S, mask | shift)));
            } finally {
                runOnEdt(candidate::dispose);
            }
        }
    }

    @Test
    @DisplayName("aucune touche du menu Fichier n'entre en conflit avec une touche du carnet")
    void noFileKeyClashesWithAnotherKeyOfTheNotebook() {
        for (int mask : new int[] {InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            NotebookShortcuts shortcuts = new NotebookShortcuts(mask);
            List<KeyStroke> seen = new ArrayList<>();
            for (Command command : Command.values()) {
                KeyStroke stroke = shortcuts.stroke(command);
                assertFalse(seen.contains(stroke), command + " reuses " + stroke);
                seen.add(stroke);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ title

    @Test
    @DisplayName("parcours : enregistrer sous, modifier (titre marqué), enregistrer (marqueur retiré)")
    void saveAsThenEditThenSaveMovesTheMarkerOfTheTitle() throws Exception {
        assertEquals("Carnet sans titre — Calc", frame.getTitle());
        typeLine("1 + 1");
        assertEquals("Carnet sans titre — Calc", frame.getTitle(), "the untitled notebook is autosaved: never marked");

        dialogs.willSaveTo(documents.resolve("devis"));
        press(KeyEvent.VK_S, MENU_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals("devis.calc.json — Calc", frame.getTitle());
        assertTrue(Files.exists(documents.resolve("devis.calc.json")));

        typeLine("ans * 2");
        assertEquals("• devis.calc.json — Calc", frame.getTitle());

        press(KeyEvent.VK_S, MENU_MASK);
        assertEquals("devis.calc.json — Calc", frame.getTitle());
        assertEquals(List.of("1 + 1", "ans * 2"), NotebookCodec.read(documents.resolve("devis.calc.json")).notebook().texts());
        dialogs.assertAllUsed();
    }

    @Test
    @DisplayName("les éléments du menu agissent : Nouveau avec confirmation, Exporter avec confirmation d'écrasement")
    void theMenuItemsRunTheirActions() throws Exception {
        typeLine("2 + 2");
        Path target = documents.resolve("sortie.csv");
        Files.writeString(target, "ancien");

        dialogs.willExportTo(target).willConfirmOverwrite(true);
        runOnEdt(item("exportCsvItem", frame)::doClick);
        assertTrue(Files.readString(target).contains("2 + 2;4;4;exact"));
        assertEquals("Exporté vers " + target.toAbsolutePath().normalize(), frame.statusLabel().getText());

        dialogs.willAnswer(Unsaved.DISCARD);
        runOnEdt(item("newItem", frame)::doClick);
        assertEquals(List.of(""), texts());
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ closing

    @Test
    @DisplayName("la fermeture passe par la confirmation : Annuler garde la fenêtre, Ne pas enregistrer et Quitter la ferment")
    void closingTheWindowAsksAboutUnsavedChanges() throws Exception {
        assertEquals(javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE, frame.getDefaultCloseOperation(),
                "closing is decided by the presenter, not by EXIT_ON_CLOSE or DISPOSE_ON_CLOSE");
        typeLine("1 + 1");
        dialogs.willSaveTo(documents.resolve("work"));
        press(KeyEvent.VK_S, MENU_MASK);
        typeLine("ans + 1");
        assertEquals("• work.calc.json — Calc", frame.getTitle());

        dialogs.willAnswer(Unsaved.CANCEL);
        close();
        assertTrue(frame.isDisplayable(), "cancel keeps the window");
        assertEquals("• work.calc.json — Calc", frame.getTitle());

        dialogs.willAnswer(Unsaved.DISCARD);
        close();
        assertFalse(frame.isDisplayable(), "do not save closes it");
        assertEquals(List.of("1 + 1"), NotebookCodec.read(documents.resolve("work.calc.json")).notebook().texts(),
                "and the file is what was saved");
        frame = null;
    }

    @Test
    @DisplayName("Quitter du menu suit le même chemin que la fermeture, avec Enregistrer")
    void theQuitItemSavesThenCloses() throws Exception {
        typeLine("1 + 1");
        dialogs.willSaveTo(documents.resolve("work"));
        press(KeyEvent.VK_S, MENU_MASK);
        typeLine("ans + 1");

        dialogs.willAnswer(Unsaved.SAVE);
        runOnEdt(item("quitItem", frame)::doClick);

        assertFalse(frame.isDisplayable());
        assertEquals(List.of("1 + 1", "ans + 1"), NotebookCodec.read(documents.resolve("work.calc.json")).notebook().texts());
        frame = null;
    }

    @Test
    @DisplayName("un carnet sans titre se ferme sans question")
    void anUntitledNotebookClosesWithoutAQuestion() throws Exception {
        typeLine("1 + 1");

        close();

        assertFalse(frame.isDisplayable());
        assertEquals(List.of(), dialogs.asked);
        frame = null;
    }

    @Test
    @DisplayName("version plus récente : Enregistrer est désactivé, Enregistrer sous reste possible, le titre le dit")
    void aNewerVersionFileDisablesSaveButNotSaveAs() throws Exception {
        Path future = documents.resolve("future.calc.json");
        Files.writeString(future, "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\"}]}");

        dialogs.willOpen(future);
        press(KeyEvent.VK_O, MENU_MASK);

        assertEquals("future.calc.json (lecture seule) — Calc", frame.getTitle());
        assertFalse(item("saveItem", frame).isEnabled());
        assertTrue(item("saveAsItem", frame).isEnabled());
        assertEquals(NotebookPresenter.READ_ONLY_FILE_STATUS, frame.statusLabel().getText());
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void close() throws Exception {
        runOnEdt(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
    }

    private void typeLine(String text) throws Exception {
        runOnEdt(() -> frame.row(frame.shown().activeIndex()).field.setText(text));
        press(KeyEvent.VK_ENTER, 0);
    }

    private List<String> texts() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < frame.rowCount(); i++) {
            texts.add(frame.row(i).field.getText());
        }
        return texts;
    }

    // Presses a key as Swing does: the binding of the active field, through its input map.
    private void press(int keyCode, int modifiers) throws Exception {
        JComponent field = frame.row(frame.shown().activeIndex()).field;
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, modifiers);
        Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
        assertNotNull(key, "nothing is bound to " + stroke);
        Action action = field.getActionMap().get(key);
        assertNotNull(action, "no action " + key);
        runOnEdt(() -> action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key.toString())));
    }

    private static JMenuItem item(String name, NotebookFrame window) {
        List<MenuElement> pending = new ArrayList<>(List.of(window.getJMenuBar().getSubElements()));
        while (!pending.isEmpty()) {
            MenuElement element = pending.remove(0);
            if (element instanceof JMenuItem item && name.equals(item.getName())) {
                return item;
            }
            pending.addAll(List.of(element.getSubElements()));
        }
        throw new AssertionError("no menu item " + name);
    }

    private static void runOnEdt(Runnable runnable) throws InterruptedException, InvocationTargetException {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            SwingUtilities.invokeAndWait(runnable);
        }
        SwingUtilities.invokeAndWait(() -> { });
    }
}
