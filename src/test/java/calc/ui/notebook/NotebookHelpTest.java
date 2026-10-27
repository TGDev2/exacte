package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.Notebook;
import calc.notebook.NotebookEditor;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The help of the window says what the application does: its examples are evaluated, its shortcut list is the real one. */
class NotebookHelpTest {
    @Test
    void everyExampleOfTheSyntaxSummaryGivesTheResultItAnnounces() throws Exception {
        List<String> expressions = new ArrayList<>();
        NotebookHelp.EXAMPLES.forEach(example -> expressions.add(example.expression()));
        EvaluatedNotebook evaluated = new NotebookEditor(Notebook.of(expressions)).current();

        for (int i = 0; i < expressions.size(); i++) {
            assertTrue(evaluated.results().get(i).hasValue(), expressions.get(i));
            assertEquals(NotebookHelp.EXAMPLES.get(i).result(),
                    ValueFormatter.format(evaluated.results().get(i).value(), DisplayMode.AUTO).text(), expressions.get(i));
        }
        String syntax = NotebookHelp.syntax();
        for (NotebookHelp.Example example : NotebookHelp.EXAMPLES) {
            assertTrue(syntax.contains(example.expression() + "   →   " + example.result()), example.expression());
        }
        for (String keyword : List.of("ans", "$3", "prix = 12,5", "# Devis cuisine", "√")) {
            assertTrue(syntax.contains(keyword), keyword);
        }
    }

    @Test
    void theShortcutHelpListsEveryKeyWithTheModifierOfTheSystem() {
        NotebookShortcuts windows = new NotebookShortcuts(InputEvent.CTRL_DOWN_MASK);
        NotebookShortcuts mac = new NotebookShortcuts(InputEvent.META_DOWN_MASK);

        String help = NotebookHelp.shortcuts(windows);
        for (NotebookShortcuts.Command command : NotebookShortcuts.Command.values()) {
            assertTrue(help.contains(windows.text(command)) && help.contains(command.description()), command.name());
        }
        assertEquals("Ctrl+Z", windows.text(NotebookShortcuts.Command.UNDO));
        assertEquals("Ctrl+Maj+Z", windows.text(NotebookShortcuts.Command.REDO_ALTERNATE));
        assertEquals("Cmd+Z", mac.text(NotebookShortcuts.Command.UNDO));
        assertEquals("Cmd+Maj+K", mac.text(NotebookShortcuts.Command.DELETE_LINE));
        assertEquals("Alt+↑", windows.text(NotebookShortcuts.Command.MOVE_UP));
        assertEquals("Ctrl+M", mac.text(NotebookShortcuts.Command.NEXT_MODE), "the display mode key is Ctrl on every system");
        assertEquals("Entrée", windows.text(NotebookShortcuts.Command.ENTER));
        assertEquals("Échap", windows.text(NotebookShortcuts.Command.CANCEL_EDIT));
        assertEquals(NotebookShortcuts.Command.values().length, windows.helpLines().size());
    }
}
