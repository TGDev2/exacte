package calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookFrame;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The start of the application on a data directory (what {@link Main} does, with a temporary directory): the notebook is the
 * main window, a version 1 history is migrated and shown as the notebook, a first command is saved, and a second start finds
 * the notebook where it was left and no longer migrates.
 */
@Tag("ui")
@DisplayName("Démarrage de l'application sur un répertoire de données (nécessite un affichage)")
class MainStartupTest {
    @TempDir
    Path data;

    private NotebookStore store;
    private NotebookFrame frame;

    @BeforeEach
    void requireGraphicalEnvironment() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    @AfterEach
    void stop() throws Exception {
        if (frame != null) {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
        if (store != null) {
            store.close();
        }
    }

    @Test
    void aVersionOneHistoryAppearsAsTheNotebookAndTheFirstCommandIsSaved() throws Exception {
        try (InputStream stream = MainStartupTest.class.getResourceAsStream("/legacy/v1/nominal.tsv")) {
            assertNotNull(stream);
            Files.write(data.resolve("history.tsv"), stream.readAllBytes());
        }

        start();

        assertEquals("Calc — Carnet de calcul", frame.getTitle(), "the notebook is the main window, not the two-field form");
        assertEquals(List.of("0,1 + 0,2", "10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)", ""), texts());
        assertEquals(List.of("0,3", "7,5", "6", "3,5", "1024", "4", ""), results());
        assertEquals("Votre historique a été converti en carnet (6 lignes).", status());
        assertTrue(Files.exists(data.resolve("carnet-courant.calc.json")), "the migration wrote the notebook");
        assertTrue(Files.exists(data.resolve("history.json.v2.bak")), "and kept a copy of the history");

        // The user clicks the new line at the end, types and validates: the notebook is saved at once.
        List<JTextField> fields = fields();
        click(fields.get(6));
        SwingUtilities.invokeAndWait(() -> fields.get(6).setText("ans * 2"));
        press(fields.get(6), KeyEvent.VK_ENTER);
        assertEquals("8", results().get(6), "ans is the result of the last line of the migrated history");
        assertEquals(List.of("0,1 + 0,2", "10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)", "ans * 2"),
                NotebookCodec.read(data.resolve("carnet-courant.calc.json")).notebook().texts());
        assertFalse(status().contains("converti"), status());

        // A second start reads the notebook where it was left; there is nothing to migrate any more.
        SwingUtilities.invokeAndWait(frame::dispose);
        store.close();
        start();
        assertEquals(List.of("0,3", "7,5", "6", "3,5", "1024", "4", "8", ""), results());
        assertFalse(status().contains("converti"), status());
    }

    @Test
    void anEmptyDataDirectoryStartsOnAnEmptyNotebook() throws Exception {
        start();

        assertEquals(List.of(""), texts());
        assertEquals(List.of(""), results());
        assertTrue(status().startsWith("Une expression par ligne"), status());
        assertFalse(Files.exists(data.resolve("carnet-courant.calc.json")), "nothing is written before the first command");
    }

    private void start() throws Exception {
        store = NotebookStore.open(data);
        NotebookFrame[] holder = new NotebookFrame[1];
        SwingUtilities.invokeAndWait(() -> holder[0] = Main.createWindow(store, DisplayModeStore.noop()));
        frame = holder[0];
    }

    private String status() {
        return ((JLabel) find(frame, "statusLabel").get(0)).getText();
    }

    private List<JTextField> fields() {
        List<JTextField> fields = new ArrayList<>();
        find(frame, "lineField").forEach(component -> fields.add((JTextField) component));
        return fields;
    }

    private List<String> texts() {
        return fields().stream().map(JTextField::getText).toList();
    }

    private List<String> results() {
        return find(frame, "resultLabel").stream().map(component -> ((JLabel) component).getText()).toList();
    }

    // What the mouse does: the field gains the focus, and the window activates its line.
    private static void click(JTextField field) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.event.FocusListener listener : field.getFocusListeners()) {
                listener.focusGained(new java.awt.event.FocusEvent(field, java.awt.event.FocusEvent.FOCUS_GAINED));
            }
        });
    }

    private static void press(JTextField field, int keyCode) throws Exception {
        Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(keyCode, 0));
        assertNotNull(key, "nothing is bound to the key");
        Action action = field.getActionMap().get(key);
        SwingUtilities.invokeAndWait(() -> action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, "key")));
    }

    // The components called name, in the order of the window (rows from the first to the last).
    private static List<Component> find(Container container, String name) {
        List<Component> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (name.equals(child.getName())) {
                found.add(child);
            }
            if (child instanceof Container inner) {
                found.addAll(find(inner, name));
            }
        }
        return found;
    }
}
