package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.notebook.NotebookShortcuts.Command;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import org.junit.jupiter.api.Test;

/**
 * The keys of the notebook that carry a modifier (undo, redo, insert, delete, move) do not clash with the editing keys that
 * the look and feels bind in a text field: Ctrl+Z in a field must not be an undo of the text of the field, and Ctrl+Enter
 * must not be a field action. The plain keys (Enter, arrows, Escape) may be bound by a look and feel (Enter validates a
 * field, for instance): the window binds them in the input map of the field itself, which wins, and the test lists them.
 * Like the test of Ctrl+M, it checks the look and feels installed in the Java runtime; the CI runs it on Windows, Linux and
 * macOS, where the system look and feel is one of them.
 */
class NotebookShortcutConflictTest {
    @Test
    void noModifiedKeyOfTheNotebookIsBoundByATextFieldOfTheInstalledLookAndFeels() throws Exception {
        LookAndFeel original = UIManager.getLookAndFeel();
        try {
            check();
        } finally {
            UIManager.setLookAndFeel(original);
        }
    }

    private static void check() throws Exception {
        List<KeyStroke> modified = new ArrayList<>();
        List<KeyStroke> plain = new ArrayList<>();
        // Both modifiers of the menu shortcuts: Ctrl (Windows, Linux) and Cmd (macOS).
        for (int mask : new int[] {InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            NotebookShortcuts shortcuts = new NotebookShortcuts(mask);
            for (Command command : Command.values()) {
                KeyStroke stroke = shortcuts.stroke(command);
                List<KeyStroke> target = stroke.getModifiers() == 0 ? plain : modified;
                if (!target.contains(stroke)) {
                    target.add(stroke);
                }
            }
        }
        assertTrue(modified.size() >= 12, "both modifiers are covered: " + modified);

        List<String> checked = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        List<String> overridden = new ArrayList<>();
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            try {
                UIManager.setLookAndFeel(info.getClassName());
            } catch (UnsupportedLookAndFeelException unsupported) {
                continue;
            }
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            int maps = 0;
            for (Object key : new ArrayList<>(defaults.keySet())) {
                if (key instanceof String name && name.contains("TextField") && name.endsWith("InputMap")
                        && defaults.get(name) instanceof InputMap map) {
                    maps++;
                    for (InputMap level = map; level != null; level = level.getParent()) {
                        for (KeyStroke stroke : modified) {
                            if (level.get(stroke) != null) {
                                conflicts.add(info.getName() + ": " + name + " binds " + stroke);
                            }
                        }
                        for (KeyStroke stroke : plain) {
                            if (level.get(stroke) != null && !overridden.contains(info.getName() + " " + stroke)) {
                                overridden.add(info.getName() + " " + stroke);
                            }
                        }
                    }
                }
            }
            assertTrue(maps > 0, info.getName() + " should expose text field input maps.");
            checked.add(info.getName() + " (" + maps + " maps)");
        }
        System.out.println("Notebook keys with a modifier checked in text fields of: " + checked);
        System.out.println("Plain keys that a text field binds and the notebook overrides in its own field: " + overridden);
        assertTrue(checked.size() >= 2, "At least two look and feels should be checked: " + checked);
        assertEquals(List.of(), conflicts);
    }
}
