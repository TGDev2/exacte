package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.format.DisplayMode;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookShortcuts.Command;
import calc.ui.widget.ExactnessBadge;
import java.awt.Component;
import java.awt.Container;
import java.awt.FocusTraversalPolicy;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
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
 * The notebook window with a real Swing hierarchy (a display is needed): the keyboard path with the menu modifier of the
 * system, the badge and tooltip, the errors in words, the accessible names, the focus order and the menus.
 */
@Tag("ui")
@DisplayName("Fenêtre du carnet (nécessite un affichage)")
class NotebookFrameTest {
    // Read in a method, not a constant: without a display the toolkit throws, and that must skip the tests, not fail the class.
    private static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    @TempDir
    Path data;

    private NotebookStore store;
    private NotebookFrame frame;

    @BeforeEach
    void start() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
        store = NotebookStore.open(data);
        frame = open(store, new NotebookShortcuts(menuMask()));
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

    private static NotebookFrame open(NotebookStore store, NotebookShortcuts shortcuts) throws Exception {
        NotebookFrame[] holder = new NotebookFrame[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = new NotebookFrame(
                NotebookPresenter.open(store, DisplayModeStore.noop()), shortcuts));
        return holder[0];
    }

    // ------------------------------------------------------------------------------------------------ the keyboard path

    @Test
    @DisplayName("parcours clavier complet : saisie, Entrée, ↑ ↓, Échap, annuler, rétablir, déplacement")
    void theWholeKeyboardPath() throws Exception {
        typeLine("a = 2");
        typeLine("a * 3");
        typeLine("ans + 1");
        assertEquals(List.of("2", "6", "7", ""), results());
        assertEquals(3, frame.shown().activeIndex(), "Enter on the last line goes to the new line");

        press(KeyEvent.VK_UP, 0);
        press(KeyEvent.VK_UP, 0);
        press(KeyEvent.VK_UP, 0);
        assertEquals(0, frame.shown().activeIndex());
        press(KeyEvent.VK_UP, 0);
        assertEquals(0, frame.shown().activeIndex(), "↑ stops on the first line");

        setField("a = 10");
        press(KeyEvent.VK_ENTER, 0);
        assertEquals(List.of("10", "30", "31", ""), results(), "validating the first line recalculates the others, visibly");
        assertEquals(1, frame.shown().activeIndex());

        setField("a * 4");
        press(KeyEvent.VK_ESCAPE, 0);
        assertEquals("a * 3", field(1).getText(), "Escape drops what was typed since the line was validated");
        assertEquals("30", frame.row(1).resultLabel.getText());

        press(KeyEvent.VK_Z, menuMask());
        assertEquals(List.of("2", "6", "7", ""), results(), "undo reverts the last validated command");
        press(KeyEvent.VK_Y, menuMask());
        assertEquals(List.of("10", "30", "31", ""), results(), "redo applies it again");
        press(KeyEvent.VK_Z, menuMask());
        press(KeyEvent.VK_Z, menuMask() | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("10", "30", "31", ""), results(), "Ctrl+Shift+Z redoes as well");

        press(KeyEvent.VK_DOWN, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("a = 10", "ans + 1", "a * 3", ""), texts(), "Ctrl+Shift+↓ moves the active line down");
        assertEquals(2, frame.shown().activeIndex(), "the focus follows the line");
        press(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("a = 10", "a * 3", "ans + 1", ""), texts());

        press(KeyEvent.VK_ENTER, menuMask());
        assertEquals(List.of("a = 10", "a * 3", "", "ans + 1", ""), texts(), "Ctrl+Enter inserts a line below");
        press(KeyEvent.VK_ENTER, menuMask() | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("a = 10", "a * 3", "", "", "ans + 1", ""), texts(), "Ctrl+Shift+Enter inserts a line above");
        press(KeyEvent.VK_K, menuMask() | InputEvent.SHIFT_DOWN_MASK);
        press(KeyEvent.VK_K, menuMask() | InputEvent.SHIFT_DOWN_MASK);
        assertEquals(List.of("a = 10", "a * 3", "ans + 1", ""), texts(), "Ctrl+Shift+K deletes the line");
        assertEquals(List.of("10", "30", "31", ""), results(), "ans still reads the closest line with a value");
    }

    @Test
    @DisplayName("un mode d'affichage se change au clavier (Ctrl+M), par le sélecteur et par le menu")
    void theDisplayModeChangesFromTheKeyboardTheSelectorAndTheMenu() throws Exception {
        typeLine("1 / 3");
        assertEquals("0,333333333333…", frame.row(0).resultLabel.getText());

        JComponentAction.fire(frame.getRootPane(), KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK));
        assertEquals(DisplayMode.DECIMAL, frame.shown().displayMode());
        assertEquals(DisplayMode.DECIMAL, frame.modeSelector().mode());

        runOnEdt(() -> frame.modeSelector().setSelectedItem(DisplayMode.FRACTION));
        assertEquals("1/3", frame.row(0).resultLabel.getText());

        JMenuItem scientific = findMenuItem("modeItemSCIENTIFIC");
        runOnEdt(scientific::doClick);
        assertEquals(DisplayMode.SCIENTIFIC, frame.shown().displayMode());
        assertTrue(frame.row(0).resultLabel.getText().contains("×"), frame.row(0).resultLabel.getText());
    }

    @Test
    @DisplayName("le modificateur de menu de chaque système : Ctrl ou Cmd, jamais les deux")
    void theMenuModifierOfEachSystemIsHonoured() throws Exception {
        runOnEdt(frame::dispose);
        frame = null;
        for (int mask : new int[] {InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            int other = mask == InputEvent.CTRL_DOWN_MASK ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            NotebookFrame candidate = open(store, new NotebookShortcuts(mask));
            try {
                JTextField field = candidate.row(0).field;
                for (int key : new int[] {KeyEvent.VK_Z, KeyEvent.VK_Y}) {
                    assertNotNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, mask)),
                            KeyEvent.getKeyText(key) + " with " + mask);
                    assertNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(key, other)),
                            KeyEvent.getKeyText(key) + " must not answer to the other system's modifier");
                }
                assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_Z, mask), findMenuItem(candidate, "undoItem").getAccelerator());
            } finally {
                runOnEdt(candidate::dispose);
            }
        }
    }

    @Test
    @DisplayName("les touches d'une ligne l'emportent sur celles du champ texte du système d'apparence")
    void everyKeyOfTheNotebookIsBoundInTheFieldItself() {
        NotebookShortcuts shortcuts = new NotebookShortcuts(menuMask());
        JTextField field = frame.row(0).field;
        for (Command command : Command.values()) {
            if (command == Command.NEXT_MODE) {
                continue;
            }
            Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(shortcuts.stroke(command));
            assertNotNull(key, command + " " + shortcuts.text(command));
            assertNotNull(field.getActionMap().get(key), command.name());
            assertTrue(field.getActionMap().get(key).isEnabled(), "the key actions are always enabled: " + command);
        }
    }

    // ------------------------------------------------------------------------------------------------ results

    @Test
    @DisplayName("badge et info-bulle d'exactitude sur √2, exact sur un décimal")
    void theBadgeAndItsTooltipTellExactFromApproximate() throws Exception {
        typeLine("√2");
        typeLine("12,5 * 3");
        typeLine("1 / 0");

        NotebookRowView root = frame.row(0);
        assertTrue(root.badge.isVisible());
        assertEquals(ExactnessBadge.APPROXIMATE_TEXT, root.badge.getText());
        assertTrue(root.resultLabel.getText().startsWith("≈ 1,41421356237"), root.resultLabel.getText());
        assertTrue(root.badge.getToolTipText().startsWith("Erreur maximale : "), root.badge.getToolTipText());
        assertTrue(root.resultLabel.getToolTipText().contains("Erreur maximale"), root.resultLabel.getToolTipText());
        assertEquals(javax.swing.SwingConstants.RIGHT, root.resultLabel.getHorizontalAlignment(), "the result is on the right");

        NotebookRowView exact = frame.row(1);
        assertTrue(exact.badge.isVisible());
        assertEquals(ExactnessBadge.EXACT_TEXT, exact.badge.getText());
        assertEquals("37,5", exact.resultLabel.getText());

        assertFalse(frame.row(2).badge.isVisible(), "a line in error has no exactness badge");
    }

    @Test
    @DisplayName("parité : un résultat très long n'élargit pas la fenêtre, un résultat exact et complet n'a pas d'info-bulle d'exactitude")
    void aLongResultDoesNotWidenTheWindowAndAnExactCompleteOneHasNoBadgeTooltip() throws Exception {
        int widthBefore = frame.getWidth();

        typeLine("2^999");
        typeLine("0,25");

        assertEquals("5,35754303593… × 10³⁰⁰", frame.row(0).resultLabel.getText());
        assertEquals(widthBefore, frame.getWidth(), "a long result must not widen the window");
        assertNotNull(frame.row(0).resultLabel.getToolTipText());
        assertEquals("0,25", frame.row(1).resultLabel.getText());
        assertNull(frame.row(1).badge.getToolTipText(), "exact and complete: nothing to add in the tooltip");
    }

    @Test
    @DisplayName("parité : Ctrl+M est la seule touche de la fenêtre entière, aucun composant ne la prend")
    void theOnlyWindowWideKeyIsNextModeAndNoComponentBindsIt() {
        KeyStroke nextMode = KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK);

        List<KeyStroke> windowWide = List.of(frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).keys());
        assertEquals(List.of(nextMode), windowWide, "the window-wide shortcuts are Ctrl+M only");
        for (int i = 0; i < frame.rowCount(); i++) {
            JTextField field = frame.row(i).field;
            assertNull(field.getInputMap(JComponent.WHEN_FOCUSED).get(nextMode), "the line fields leave Ctrl+M to the window");
        }
    }

    @Test
    @DisplayName("1 / 0 : le message d'erreur est affiché en texte, avec la partie fautive soulignée")
    void anErrorIsShownAsTextNotOnlyAsAColour() throws Exception {
        typeLine("1 / 0");

        NotebookRowView row = frame.row(0);
        assertTrue(row.errorLabel.isVisible());
        String message = frame.shown().rows().get(0).errorMessage();
        assertFalse(message.isBlank());
        assertEquals("Erreur : " + message, row.errorLabel.getText());
        assertEquals("", row.resultLabel.getText());
        assertTrue(row.field.hasErrorMark(), "the faulty part is underlined");
        assertFalse(frame.row(1).errorLabel.isVisible(), "the new line shows no error");

        runOnEdt(() -> row.field.setText("1 / 2"));
        assertFalse(row.field.hasErrorMark(), "the mark goes away as soon as the text changes");
    }

    @Test
    @DisplayName("les lignes qui définissent une variable sont repérées (infobulle, couleur, nom accessible)")
    void aVariableDefinitionIsHighlighted() throws Exception {
        typeLine("prix = 12,5");
        typeLine("prix * 3");

        NotebookRowView definition = frame.row(0);
        assertEquals(NotebookPalette.VARIABLE_BACKGROUND, definition.numberLabel.getBackground());
        assertEquals("Définit la variable « prix »", definition.numberLabel.getToolTipText());
        assertEquals("Résultat : 12,5, exact, définit la variable prix",
                definition.field.getAccessibleContext().getAccessibleDescription());
        assertFalse(NotebookPalette.VARIABLE_BACKGROUND.equals(frame.row(1).numberLabel.getBackground()));
        assertNull(frame.row(1).numberLabel.getToolTipText());
    }

    @Test
    @DisplayName("carnet vide : filigrane avec trois exemples, non enregistrés")
    void theEmptyNotebookShowsTheWatermark() throws Exception {
        LineField field = frame.row(0).field;
        assertEquals(1, frame.rowCount());
        assertTrue(field.placeholder().contains("prix = 12,5"), field.placeholder());
        assertTrue(field.placeholder().contains("prix * 3") && field.placeholder().contains("ans / 2"), field.placeholder());
        assertEquals("", field.getText(), "the help is not text");

        typeLine("1");
        assertEquals("", frame.row(0).field.placeholder(), "the watermark goes away once there is a line");
        assertEquals(List.of("1"), NotebookCodec.read(store.currentFile()).notebook().texts());
    }

    // ------------------------------------------------------------------------------------------------ accessibility

    @Test
    @DisplayName("noms accessibles : « Ligne 3 », description avec le résultat, ordre de focus naturel")
    void everyFieldHasAnAccessibleNameAndTheResultInItsDescription() throws Exception {
        typeLine("a = 2");
        typeLine("a * 3");
        typeLine("12,5 * 3");

        for (int i = 0; i < 4; i++) {
            assertEquals("Ligne " + (i + 1), field(i).getAccessibleContext().getAccessibleName());
        }
        assertEquals("Résultat : 37,5, exact", field(2).getAccessibleContext().getAccessibleDescription());
        assertEquals("Résultat : 6, exact", field(1).getAccessibleContext().getAccessibleDescription());
        assertEquals("Ligne vide", field(3).getAccessibleContext().getAccessibleDescription());

        typeLine("1 / 0");
        assertTrue(field(3).getAccessibleContext().getAccessibleDescription().startsWith("Erreur : "));

        // The policy orders the components that are on screen.
        runOnEdt(() -> frame.setVisible(true));
        runOnEdt(() -> frame.getContentPane().validate());
        FocusTraversalPolicy policy = frame.getFocusTraversalPolicy();
        Container root = frame;
        for (int i = 0; i + 1 < frame.rowCount(); i++) {
            Component after = policy.getComponentAfter(root, field(i));
            assertEquals(field(i + 1), after, "Tab goes from line " + (i + 1) + " to line " + (i + 2));
        }
        assertNotNull(frame.statusLabel().getAccessibleContext().getAccessibleName());
        assertNotNull(frame.modeSelector().getAccessibleContext().getAccessibleName());
    }

    // ------------------------------------------------------------------------------------------------ menus and status

    @Test
    @DisplayName("menus Fichier, Édition, Affichage et Aide ; Annuler et Rétablir suivent l'état")
    void theMenusExistAndFollowTheState() throws Exception {
        List<String> titles = new ArrayList<>();
        for (int i = 0; i < frame.getJMenuBar().getMenuCount(); i++) {
            titles.add(frame.getJMenuBar().getMenu(i).getText());
        }
        assertEquals(List.of("Fichier", "Édition", "Affichage", "Aide"), titles);
        JMenu edit = frame.getJMenuBar().getMenu(1);
        List<String> items = new ArrayList<>();
        for (java.awt.Component component : edit.getMenuComponents()) {
            if (component instanceof JMenuItem item) {
                items.add(item.getText());
            }
        }
        assertEquals(List.of("Annuler", "Rétablir", "Insérer une ligne au-dessus", "Insérer une ligne au-dessous",
                "Supprimer la ligne", "Monter la ligne", "Descendre la ligne", "Tout effacer"), items);

        JMenuItem undo = findMenuItem("undoItem");
        JMenuItem redo = findMenuItem("redoItem");
        assertFalse(undo.isEnabled());
        assertFalse(redo.isEnabled());
        typeLine("1 + 1");
        assertTrue(undo.isEnabled());
        runOnEdt(undo::doClick);
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
        assertEquals(List.of(""), texts());

        runOnEdt(redo::doClick);
        runOnEdt(findMenuItem("clearItem")::doClick);
        assertEquals(List.of(""), texts());

        for (String name : new String[] {"syntaxItem", "shortcutsItem"}) {
            assertNotNull(findMenuItem(name), name);
        }
    }

    @Test
    @DisplayName("la barre d'état annonce l'échec de l'enregistrement automatique sans perdre le carnet")
    void theStatusBarSaysWhenTheAutomaticSaveFails() throws Exception {
        runOnEdt(frame::dispose);
        store.close();
        Path elsewhere = java.nio.file.Files.createDirectories(data.resolve("failing"));
        store = NotebookStore.open(elsewhere, null, java.time.Clock.systemDefaultZone(), java.nio.file.Files::copy,
                (source, target, options) -> {
                    throw new java.io.IOException("simulated full disk");
                });
        frame = open(store, new NotebookShortcuts(menuMask()));

        typeLine("2 + 2");

        assertEquals("4", frame.row(0).resultLabel.getText());
        assertTrue(frame.statusLabel().getText().startsWith("Enregistrement automatique impossible"), frame.statusLabel().getText());
        assertEquals(calc.ui.widget.Palette.ERROR, frame.statusLabel().getForeground());
    }

    @Test
    @DisplayName("revue : mnémoniques réels, soulignement retrouvé, curseur laissé en place")
    void theMnemonicsWorkTheUnderlineComesBackAndTheCaretStays() throws Exception {
        assertEquals(KeyEvent.VK_F, frame.getJMenuBar().getMenu(0).getMnemonic());
        assertEquals(KeyEvent.VK_E, frame.getJMenuBar().getMenu(1).getMnemonic(), "É has no key code: Alt+E opens Édition");
        assertEquals(KeyEvent.VK_C, frame.getJMenuBar().getMenu(2).getMnemonic());
        assertEquals(KeyEvent.VK_I, frame.getJMenuBar().getMenu(3).getMnemonic());

        typeLine("1 / 0");
        typeLine("2");
        press(KeyEvent.VK_UP, 0);
        press(KeyEvent.VK_UP, 0);
        assertTrue(frame.row(0).field.hasErrorMark());
        runOnEdt(() -> field(0).setText("1 / 00"));
        runOnEdt(() -> field(0).setText("1 / 0"));
        assertFalse(frame.row(0).field.hasErrorMark(), "typing removes the mark");
        press(KeyEvent.VK_ESCAPE, 0);
        press(KeyEvent.VK_ENTER, 0);
        press(KeyEvent.VK_UP, 0);
        assertTrue(frame.row(0).field.hasErrorMark(), "the mark of a line in error comes back when its text is the validated text");

        runOnEdt(() -> field(0).setCaretPosition(2));
        press(KeyEvent.VK_Y, menuMask());
        assertEquals(2, field(0).getCaretPosition(), "a refused redo does not move the caret");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private JTextField field(int index) {
        return frame.row(index).field;
    }

    private void setField(String text) throws Exception {
        runOnEdt(() -> field(frame.shown().activeIndex()).setText(text));
    }

    private void typeLine(String text) throws Exception {
        setField(text);
        press(KeyEvent.VK_ENTER, 0);
    }

    // Presses a key: the binding of the active field, through its input map, must exist for this exact key.
    private void press(int keyCode, int modifiers) throws Exception {
        KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, modifiers);
        JComponentAction.fire(field(frame.shown().activeIndex()), stroke);
    }

    private List<String> results() {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < frame.rowCount(); i++) {
            results.add(frame.row(i).resultLabel.getText());
        }
        return results;
    }

    private List<String> texts() {
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < frame.rowCount(); i++) {
            texts.add(field(i).getText());
        }
        return texts;
    }

    private JMenuItem findMenuItem(String name) {
        return findMenuItem(frame, name);
    }

    private static JMenuItem findMenuItem(NotebookFrame window, String name) {
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

    /** Runs the action that a component binds to a key, as Swing does when the key is pressed in that component. */
    private static final class JComponentAction {
        private JComponentAction() {
        }

        static void fire(JComponent component, KeyStroke stroke) throws InterruptedException, InvocationTargetException {
            Object key = component.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            if (key == null) {
                key = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
            }
            assertNotNull(key, "nothing is bound to " + stroke);
            String name = key.toString();
            Action action = component.getActionMap().get(key);
            assertNotNull(action, "no action " + name);
            runOnEdt(() -> action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, name)));
        }

        static void fire(javax.swing.JRootPane root, KeyStroke stroke) throws InterruptedException, InvocationTargetException {
            fire((JComponent) root, stroke);
        }
    }
}
