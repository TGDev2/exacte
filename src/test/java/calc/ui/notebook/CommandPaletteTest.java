package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.ui.notebook.NotebookShortcuts.Command;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.KeyStroke;
import org.junit.jupiter.api.Test;

/** The model of the command palette and the text of the keys it shows. */
class CommandPaletteTest {
    private static final CommandPalette.Entry SAVE = new CommandPalette.Entry("saveItem", "Enregistrer", "Ctrl+S", true);
    private static final CommandPalette.Entry DECIMAL = new CommandPalette.Entry("modeItemDECIMAL", "Mode Décimal", "", true);

    @Test
    void theLabelRuleNamesModesAndThemes() {
        assertEquals("Mode Décimal", CommandPalette.modeLabel("Décimal"));
        assertEquals("Thème : Sombre", CommandPalette.themeLabel("Sombre"));
    }

    @Test
    void theSearchIgnoresCaseAccentsAndTheSpacesAround() {
        CommandPalette palette = new CommandPalette(List.of(SAVE, DECIMAL));
        assertEquals(List.of(DECIMAL), palette.filter("  DÉCI "));
        assertEquals(List.of(DECIMAL), palette.filter("mode decimal"));
        assertEquals(List.of(), palette.filter("mode  decimal"), "the search is a part of the label, spaces included");
        assertSame(palette.entries(), palette.filter("   "), "a blank search keeps every entry");
        assertEquals("ecole", CommandPalette.normalized(" Éçôlé ".replace("ç", "c").replace("ô", "o")));
    }

    @Test
    void theEntriesAreACopyAndAnEntryHasNoNullPart() {
        List<CommandPalette.Entry> given = new ArrayList<>(List.of(SAVE));
        CommandPalette palette = new CommandPalette(given);
        given.clear();
        assertEquals(List.of(SAVE), palette.entries());
        assertThrows(NullPointerException.class, () -> new CommandPalette.Entry(null, "a", "", true));
        assertThrows(NullPointerException.class, () -> new CommandPalette.Entry("a", null, "", true));
        assertThrows(NullPointerException.class, () -> new CommandPalette.Entry("a", "a", null, true));
    }

    @Test
    void theAccessibleNameIsTheLabelThenTheKey() {
        assertEquals("Enregistrer, Ctrl+S", SAVE.accessibleName());
        assertEquals("Mode Décimal", DECIMAL.accessibleName());
    }

    @Test
    void theKeysReadWithTheSymbolsOfMacOsUnderTheCommandModifier() {
        NotebookShortcuts mac = new NotebookShortcuts(InputEvent.META_DOWN_MASK);
        NotebookShortcuts windows = new NotebookShortcuts(InputEvent.CTRL_DOWN_MASK);
        assertEquals("⌘S", mac.commandText(mac.stroke(Command.SAVE)));
        assertEquals("⇧⌘S", mac.commandText(mac.stroke(Command.SAVE_AS)));
        assertEquals("⌃M", mac.commandText(mac.stroke(Command.NEXT_MODE)));
        assertEquals("⌃⇧↑", mac.commandText(mac.stroke(Command.MOVE_UP)));
        assertEquals("⌃⇧↓", mac.commandText(mac.stroke(Command.MOVE_DOWN)));
        assertEquals("⌘↩", mac.commandText(mac.stroke(Command.INSERT_BELOW)));
        assertEquals("⎋", mac.commandText(mac.stroke(Command.CANCEL_EDIT)));
        assertEquals("⌥K", mac.commandText(KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.ALT_DOWN_MASK)));
        assertEquals("⌘K", mac.commandText(mac.stroke(Command.COMMAND_PALETTE)));
        assertEquals("Cmd+K", mac.text(Command.COMMAND_PALETTE), "the help keeps its words");
        assertEquals("Ctrl+Maj+S", windows.commandText(windows.stroke(Command.SAVE_AS)));
        assertEquals("Ctrl+K", windows.commandText(windows.stroke(Command.COMMAND_PALETTE)));
    }
}
