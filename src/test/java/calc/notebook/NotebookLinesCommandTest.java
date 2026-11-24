package calc.notebook;

import static calc.notebook.NotebookTestSupport.assertValue;
import static calc.notebook.NotebookTestSupport.notebook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The commands of a paste of several lines: {@link Notebook#replaceWithLines} and {@link Notebook#appendLines}, and their
 * commands {@link NotebookCommand.ReplaceWithLines} and {@link NotebookCommand.AppendLines}, one entry of undo each.
 */
class NotebookLinesCommandTest {
    @Test
    void theLineIsReplacedInPlaceAndTheOtherLinesComeJustBelowIt() throws NotebookException {
        Notebook before = notebook("1", "x", "5");
        LineId anchor = before.line(2).id();

        Notebook after = before.replaceWithLines(anchor, List.of("a", "b", "c"));

        assertEquals(List.of("1", "a", "b", "c", "5"), after.texts());
        assertEquals(anchor, after.line(2).id(), "the first line keeps the identifier of the line");
        assertEquals(before.line(1).id(), after.line(1).id());
        assertEquals(before.line(3).id(), after.line(5).id());
        assertEquals(List.of("1", "x", "5"), before.texts(), "the notebook itself never changes");
    }

    @Test
    void theNewLinesHaveNewIdentifiersInTheirOrder() throws NotebookException {
        Notebook before = notebook("1", "2");
        Set<LineId> known = new HashSet<>(ids(before));

        Notebook after = before.replaceWithLines(before.line(1).id(), List.of("a", "b", "c"));

        LineId second = after.line(2).id();
        LineId third = after.line(3).id();
        assertFalse(known.contains(second));
        assertFalse(known.contains(third));
        assertEquals(second.value() + 1, third.value(), "the identifiers follow the order of the lines");
        assertEquals(5, new HashSet<>(ids(after.append("d"))).size(), "a later line gets yet another identifier");
    }

    @Test
    void referencesToTheOtherLinesStillDesignateThemAndTheTypedTextsAreKeptAsTyped() throws NotebookException {
        Notebook before = notebook("10", "x", "$1 + $2 + $3", "$3 * 2");
        LineId anchor = before.line(2).id();

        Notebook after = before.replaceWithLines(anchor, List.of("$1 * 2", "$3", "4"));

        assertEquals(List.of("10", "$1 * 2", "$3", "4", "$1 + $2 + $5", "$5 * 2"), after.texts(),
                "the line replaced keeps its number, the lines below move down by two; what is pasted is not rewritten");
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(after);
        assertValue("20", evaluated.result(2));
        assertValue("4", evaluated.result(4));
    }

    @Test
    void aReferencePastTheLastLineStaysPastIt() throws NotebookException {
        Notebook before = notebook("1", "$7");

        assertEquals(List.of("a", "b", "c", "$9"), before.replaceWithLines(before.line(1).id(), List.of("a", "b", "c")).texts());
        assertEquals(List.of("1", "$9", "a", "b"), before.appendLines(List.of("a", "b")).texts());
    }

    @Test
    void oneTextIsAnEditOfTheLine() throws NotebookException {
        Notebook before = notebook("1", "$1 + 1", "$2 * 3");
        LineId anchor = before.line(2).id();

        assertEquals(before.edit(anchor, "$1 + 2"), before.replaceWithLines(anchor, List.of("$1 + 2")));
    }

    @Test
    void appendedLinesComeAtTheEndInTheirOrderAsTyped() throws NotebookException {
        Notebook before = notebook("1", "$1 + 1");
        Set<LineId> known = new HashSet<>(ids(before));

        Notebook after = before.appendLines(List.of("$2 * 2", "", "$3"));

        assertEquals(List.of("1", "$1 + 1", "$2 * 2", "", "$3"), after.texts());
        assertEquals(ids(before), ids(after).subList(0, 2));
        assertEquals(5, new HashSet<>(ids(after)).size());
        assertFalse(known.contains(after.line(3).id()));
        assertEquals(after.line(3).id().value() + 1, after.line(4).id().value());
        assertEquals(List.of("a", "b"), Notebook.empty().appendLines(List.of("a", "b")).texts());
    }

    @Test
    void theNumberOfLinesIsBoundedAndARefusalChangesNothing() throws NotebookException {
        Notebook almostFull = Notebook.of(Collections.nCopies(Notebook.MAX_LINES - 1, "1"));
        LineId first = almostFull.line(1).id();

        assertEquals(Notebook.MAX_LINES, almostFull.replaceWithLines(first, List.of("a", "b")).size());
        NotebookException tooMany = assertThrows(NotebookException.class,
                () -> almostFull.replaceWithLines(first, List.of("a", "b", "c")));
        assertEquals("Le carnet ne peut pas dépasser 5000 lignes.", tooMany.getMessage());
        assertEquals(Notebook.MAX_LINES, almostFull.appendLines(List.of("a")).size());
        assertThrows(NotebookException.class, () -> almostFull.appendLines(List.of("a", "b")));
        assertEquals(Notebook.MAX_LINES - 1, almostFull.size());
        assertEquals("1", almostFull.line(1).text());
    }

    @Test
    void invalidArgumentsAreProgrammingErrors() throws NotebookException {
        Notebook notebook = notebook("1");
        LineId line = notebook.line(1).id();
        LineId unknown = notebook("1", "2").line(2).id();

        assertThrows(IllegalArgumentException.class, () -> notebook.replaceWithLines(line, List.of()));
        assertThrows(IllegalArgumentException.class, () -> notebook.appendLines(List.of()));
        assertThrows(IllegalArgumentException.class, () -> notebook.replaceWithLines(unknown, List.of("a")));
        List<String> withNull = new ArrayList<>(List.of("a"));
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> notebook.replaceWithLines(line, withNull));
        assertThrows(NullPointerException.class, () -> notebook.appendLines(withNull));
        assertThrows(NullPointerException.class, () -> notebook.replaceWithLines(null, List.of("a")));
        assertThrows(NullPointerException.class, () -> new NotebookCommand.ReplaceWithLines(null, List.of("a")));
        assertThrows(NullPointerException.class, () -> new NotebookCommand.ReplaceWithLines(line, null));
        assertThrows(NullPointerException.class, () -> new NotebookCommand.AppendLines(null));
    }

    @Test
    void theCommandsKeepTheirOwnCopyOfTheTexts() throws NotebookException {
        Notebook notebook = notebook("1");
        List<String> texts = new ArrayList<>(List.of("a", "b"));
        NotebookCommand replace = new NotebookCommand.ReplaceWithLines(notebook.line(1).id(), texts);
        NotebookCommand append = new NotebookCommand.AppendLines(texts);

        texts.add("c");

        assertEquals(List.of("a", "b"), replace.applyTo(notebook).texts());
        assertEquals(List.of("1", "a", "b"), append.applyTo(notebook).texts());
    }

    @Test
    void oneUndoTakesTheWholeReplacementBackAndRedoGivesItAgain() throws NotebookException {
        Notebook before = notebook("2", "x", "$1 * 3");
        NotebookEditor editor = new NotebookEditor(before);

        editor.apply(new NotebookCommand.ReplaceWithLines(before.line(2).id(), List.of("a = 1", "b = 2", "a + b")));
        Notebook pasted = editor.notebook();
        assertEquals(List.of("2", "a = 1", "b = 2", "a + b", "$1 * 3"), pasted.texts());
        assertValue("3", editor.current().result(4));
        assertValue("6", editor.current().result(5));

        editor.undo();
        assertEquals(before, editor.notebook(), "exactly the notebook of before, identifiers included");
        assertFalse(editor.canUndo(), "the paste was one command");

        editor.redo();
        assertEquals(pasted, editor.notebook());
        assertTrue(editor.canUndo());
        assertFalse(editor.canRedo());
    }

    @Test
    void oneUndoTakesTheAppendedLinesBack() throws NotebookException {
        Notebook before = notebook("1");
        NotebookEditor editor = new NotebookEditor(before);

        editor.apply(new NotebookCommand.AppendLines(List.of("2", "3")));
        assertEquals(List.of("1", "2", "3"), editor.notebook().texts());

        editor.undo();
        assertEquals(before, editor.notebook());
        assertFalse(editor.canUndo());
        editor.redo();
        assertEquals(List.of("1", "2", "3"), editor.notebook().texts());
    }

    @Test
    void anUndoNeverLetsAPastedLineTakeTheIdentifierOfAnotherOne() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("1"));
        editor.apply(new NotebookCommand.AppendLines(List.of("2", "3")));
        Set<LineId> seen = new HashSet<>(ids(editor.notebook()));
        editor.undo();

        editor.apply(new NotebookCommand.ReplaceWithLines(editor.notebook().line(1).id(), List.of("4", "5", "6")));

        Notebook after = editor.notebook();
        assertNotEquals(after.line(2).id(), after.line(3).id());
        assertFalse(seen.contains(after.line(2).id()));
        assertFalse(seen.contains(after.line(3).id()));
    }

    private static List<LineId> ids(Notebook notebook) {
        return notebook.lines().stream().map(Line::id).toList();
    }
}
