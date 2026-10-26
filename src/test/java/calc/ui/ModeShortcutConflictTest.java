package calc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.UnsupportedLookAndFeelException;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import org.junit.jupiter.api.Test;

/**
 * Ctrl+M, the shortcut of the next display mode, is not bound by any component of the look and feels installed in the
 * Java runtime. The look and feels of other systems (Aqua, GTK) are only checked when the test runs on them: the CI
 * runs it on Windows, Linux and macOS, where the system look and feel is part of the installed ones.
 */
class ModeShortcutConflictTest {
    private static final KeyStroke NEXT_MODE = KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK);

    @Test
    void noInstalledLookAndFeelBindsCtrlMInItsComponents() throws Exception {
        LookAndFeel original = UIManager.getLookAndFeel();
        try {
            checkAll();
        } finally {
            UIManager.setLookAndFeel(original);
        }
    }

    private static void checkAll() throws Exception {
        List<String> checked = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (UIManager.LookAndFeelInfo info : UIManager.getInstalledLookAndFeels()) {
            try {
                UIManager.setLookAndFeel(info.getClassName());
            } catch (UnsupportedLookAndFeelException unsupported) {
                continue;
            }
            UIDefaults defaults = UIManager.getLookAndFeelDefaults();
            int maps = 0;
            for (Object key : new ArrayList<>(defaults.keySet())) {
                if (key instanceof String name && name.endsWith("InputMap") && defaults.get(name) instanceof InputMap map) {
                    maps++;
                    for (InputMap level = map; level != null; level = level.getParent()) {
                        if (level.get(NEXT_MODE) != null) {
                            conflicts.add(info.getName() + ": " + name);
                        }
                    }
                }
            }
            assertTrue(maps > 0, info.getName() + " should expose input maps.");
            checked.add(info.getName() + " (" + maps + " maps)");
        }
        System.out.println("Ctrl+M checked in: " + checked);
        assertTrue(checked.size() >= 2, "At least two look and feels should be checked: " + checked);
        assertEquals(List.of(), conflicts);
    }
}
