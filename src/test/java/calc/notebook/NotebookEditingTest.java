package calc.notebook;

import static calc.notebook.NotebookTestSupport.assertError;
import static calc.notebook.NotebookTestSupport.assertValue;
import static calc.notebook.NotebookTestSupport.notebook;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.ExpressionError;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Commands, stability of references, recalculation, undo and redo, limits (docs/adr/0008-semantique-carnet.md,
 * sections 4 to 7).
 */
class NotebookEditingTest {
    @Test
    void editingAHighLineRecalculatesTheLinesThatDependOnIt() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("2", "$1 * 10", "ans + 1", "x = $2", "# fin", "x / 4"));
        LineId first = editor.notebook().line(1).id();
        editor.apply(new NotebookCommand.Edit(first, "3"));
        EvaluatedNotebook after = editor.current();
        assertValue("3", after.result(1));
        assertValue("30", after.result(2));
        assertValue("31", after.result(3));
        assertValue("30", after.result(4));
        assertValue("7,5", after.result(6));
        assertEquals(NotebookEvaluator.evaluate(after.notebook()), after);
    }

    @Test
    void insertingAboveAReferencedLineRewritesTheReferenceAndKeepsTheValue() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("1", "2", "5", "$3 * 2  # double de $3"));
        assertValue("10", editor.current().result(4));
        LineId target = editor.notebook().line(3).id();

        editor.apply(new NotebookCommand.InsertBefore(editor.notebook().line(1).id(), "100"));

        EvaluatedNotebook after = editor.current();

        assertEquals("$4 * 2  # double de $3", after.result(5).text());
        assertValue("10", after.result(5));
        assertEquals(List.of(target), after.result(5).references());
        assertEquals(List.of("100", "1", "2", "5", "$4 * 2  # double de $3"), after.notebook().texts());
    }

    @Test
    void insertingAfterOrAtTheEndRenumbersOnlyTheLinesBelow() throws NotebookException {
        Notebook notebook = notebook("1", "$1 + 1", "$2 + $1");
        LineId second = notebook.line(2).id();
        Notebook after = notebook.insertAfter(second, "7").append("$4 * 2");
        assertEquals(List.of("1", "$1 + 1", "7", "$2 + $1", "$4 * 2"), after.texts());
        assertEquals(List.of("1", "$1 + 1", "$2 + $1"), notebook.texts(), "the notebook itself never changes");
        assertValue("6", NotebookEvaluator.evaluate(after).result(5));
    }

    @Test
    void deletingAReferencedLineGivesAnExplicitErrorAndNeverAnotherLine() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("1", "2", "$2 + 1", "$3 * $1"));
        editor.apply(new NotebookCommand.Delete(editor.notebook().line(2).id()));
        EvaluatedNotebook after = editor.current();

        assertEquals(List.of("1", "$? + 1", "$2 * $1"), after.notebook().texts());
        assertError(ExpressionError.DELETED_REFERENCE, 0, 2, "Référence vers une ligne supprimée.", after.result(2));
        assertError(ExpressionError.REFERENCE_IN_ERROR, 0, 2, "Dépend de la ligne 2, qui est en erreur.", after.result(3));
    }

    @Test
    void movingTheTargetBelowMakesTheReferenceAnErrorWithoutChangingItsTarget() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("1", "2", "$1 + $2"));
        LineId first = editor.notebook().line(1).id();

        editor.apply(new NotebookCommand.Move(first, 2));

        EvaluatedNotebook moved = editor.current();
        assertEquals(List.of("2", "$3 + $1", "1"), moved.notebook().texts());
        assertError(ExpressionError.INVALID_USE, 0, 2, "Référence vers une ligne qui n'est pas au-dessus (ligne 3).",
                moved.result(2));

        editor.apply(new NotebookCommand.Move(first, 0));

        EvaluatedNotebook back = editor.current();
        assertEquals(List.of("1", "2", "$1 + $2"), back.notebook().texts());
        assertValue("3", back.result(3));
    }

    @Test
    void movingALineKeepsEveryReferenceOnItsLine() throws NotebookException {
        Notebook notebook = notebook("a = 1", "b = 2", "$1 + $2", "$3 * 10", "c = 4");
        Notebook moved = notebook.move(notebook.line(5).id(), 0);
        assertEquals(List.of("c = 4", "a = 1", "b = 2", "$2 + $3", "$4 * 10"), moved.texts());
        assertValue("30", NotebookEvaluator.evaluate(moved).result(5));
        assertSame(notebook, notebook.move(notebook.line(2).id(), 1), "a move to the same place changes nothing");
    }

    @Test
    void aReferencePastTheLastLineStaysPastIt() throws NotebookException {
        Notebook notebook = notebook("$5 + 1", "2", "3");
        LineId first = notebook.line(1).id();
        Notebook grown = notebook.append("4").insertBefore(first, "0").insertBefore(first, "0");
        assertEquals("$8 + 1", grown.line(3).text());
        assertTrue(8 > grown.size());
        Notebook shrunk = grown.delete(grown.line(1).id());
        assertEquals("$7 + 1", shrunk.line(2).text());
        assertError(ExpressionError.INVALID_USE, 0, 2, "Référence vers une ligne qui n'est pas au-dessus (ligne 7).",
                NotebookEvaluator.evaluate(shrunk).result(2));
        Notebook referenceAtTheMaximum = notebook("$2147483647").append("1");
        assertEquals("$2147483647", referenceAtTheMaximum.line(1).text());
    }

    @Test
    void clearingThenUndoingRestoresTheNotebook() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("x = 2", "x * 3", "$2 + 1"));
        EvaluatedNotebook before = editor.current();

        editor.apply(new NotebookCommand.Clear());

        EvaluatedNotebook cleared = editor.current();
        assertTrue(cleared.notebook().isEmpty());
        assertTrue(editor.canUndo());

        editor.undo();

        assertEquals(before, editor.current());
        assertFalse(editor.canUndo());
        assertTrue(editor.canRedo());
        editor.redo();
        assertEquals(cleared, editor.current());
    }

    @Test
    void undoAndRedoFollowTheCommands() throws NotebookException {
        NotebookEditor editor = new NotebookEditor();
        assertFalse(editor.canUndo() || editor.canRedo());
        editor.apply(new NotebookCommand.Append("1"));
        editor.apply(new NotebookCommand.Append("$1 + 1"));
        LineId second = editor.notebook().line(2).id();
        editor.apply(new NotebookCommand.Edit(second, "$1 + 2"));
        assertValue("3", editor.current().result(2));

        editor.undo();
        assertValue("2", editor.current().result(2));
        editor.undo();
        assertEquals(1, editor.notebook().size());
        editor.redo();
        assertValue("2", editor.current().result(2));

        editor.apply(new NotebookCommand.Edit(second, "$1 + 5"));
        assertFalse(editor.canRedo(), "a new command empties the redo stack");
        assertThrows(IllegalStateException.class, editor::redo);
        editor.undo();
        editor.undo();
        editor.undo();
        assertFalse(editor.canUndo());
        assertEquals(Notebook.empty(), editor.notebook());
        assertThrows(IllegalStateException.class, editor::undo);
    }

    @Test
    void aCommandThatChangesNothingIsNotRecorded() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("1", "2"));
        LineId first = editor.notebook().line(1).id();
        editor.apply(new NotebookCommand.Edit(first, "3"));
        editor.undo();
        editor.apply(new NotebookCommand.Edit(first, "1"));
        EvaluatedNotebook same = editor.current();
        assertSame(editor.current(), same);
        assertFalse(editor.canUndo());
        assertTrue(editor.canRedo(), "a command that changes nothing keeps the redo stack");
        editor.apply(new NotebookCommand.Move(first, 0));
        assertFalse(editor.canUndo());
        NotebookEditor empty = new NotebookEditor();
        empty.apply(new NotebookCommand.Clear());
        assertFalse(empty.canUndo());
    }

    @Test
    void theUndoStackKeepsTheLastTwoHundredCommands() throws NotebookException {
        NotebookEditor editor = new NotebookEditor(notebook("0"));
        LineId line = editor.notebook().line(1).id();
        for (int value = 1; value <= NotebookEditor.MAX_UNDO + 1; value++) {
            editor.apply(new NotebookCommand.Edit(line, String.valueOf(value)));
        }
        int undone = 0;
        while (editor.canUndo()) {
            editor.undo();
            undone++;
        }
        assertEquals(NotebookEditor.MAX_UNDO, undone);
        assertEquals("1", editor.notebook().line(1).text(), "the oldest command is forgotten");
    }

    @Test
    void theNumberOfLinesIsBounded() throws NotebookException {
        Notebook full = Notebook.of(Collections.nCopies(Notebook.MAX_LINES, "1"));
        assertEquals(5000, full.size());
        NotebookException refused = assertThrows(NotebookException.class, () -> full.append("2"));
        assertEquals("Le carnet ne peut pas dépasser 5000 lignes.", refused.getMessage());
        assertThrows(NotebookException.class, () -> full.insertBefore(full.line(1).id(), "2"));
        assertThrows(NotebookException.class, () -> Notebook.of(Collections.nCopies(5001, "")));

        NotebookEditor editor = new NotebookEditor(full);
        EvaluatedNotebook before = editor.current();
        assertThrows(NotebookException.class, () -> editor.apply(new NotebookCommand.Append("2")));
        assertSame(before, editor.current());
        assertFalse(editor.canUndo(), "a refused command is not recorded");
        editor.apply(new NotebookCommand.Delete(full.line(1).id()));
        editor.apply(new NotebookCommand.Append("2"));
        assertEquals(5000, editor.notebook().size());
    }

    @Test
    void identifiersAreStableAndNeverReused() throws NotebookException {
        Notebook notebook = notebook("a", "b");
        LineId a = notebook.line(1).id();
        LineId b = notebook.line(2).id();
        Notebook changed = notebook.edit(a, "1").move(b, 0).delete(b).append("c");
        assertEquals(a, changed.line(1).id());
        LineId c = changed.line(2).id();
        assertNotEquals(a, c);
        assertNotEquals(b, c);
        Notebook cleared = changed.clear().append("d");
        List<LineId> seen = new ArrayList<>(List.of(a, b, c));
        assertFalse(seen.contains(cleared.line(1).id()));
        assertEquals(2, changed.numberOf(c).orElseThrow());
        assertTrue(changed.numberOf(b).isEmpty());
    }

    @Test
    void anUndoNeverLetsANewLineTakeTheIdentifierOfAnotherOne() throws NotebookException {
        NotebookEditor editor = new NotebookEditor();
        LineId a = editor.apply(new NotebookCommand.Append("a")).line(1).id();
        editor.undo();
        LineId b = editor.apply(new NotebookCommand.Append("b")).line(1).id();
        assertNotEquals(a, b);
        editor.undo();
        editor.redo();
        LineId c = editor.apply(new NotebookCommand.Append("c")).line(2).id();
        assertFalse(List.of(a, b).contains(c));
        assertEquals(Notebook.empty(), new NotebookEditor(Notebook.empty()).notebook(), "equality ignores the counter");
    }

    @Test
    void invalidCommandsAreProgrammingErrors() {
        Notebook notebook = notebook("1", "2");
        LineId unknown = new LineId(99);
        assertThrows(IllegalArgumentException.class, () -> notebook.edit(unknown, "1"));
        assertThrows(IllegalArgumentException.class, () -> notebook.delete(unknown));
        assertThrows(IllegalArgumentException.class, () -> notebook.insertAfter(unknown, "1"));
        assertThrows(IndexOutOfBoundsException.class, () -> notebook.move(notebook.line(1).id(), 2));
        assertThrows(IndexOutOfBoundsException.class, () -> notebook.move(notebook.line(1).id(), -1));
        assertThrows(IllegalArgumentException.class, () -> notebook.edit(notebook.line(1).id(), "1\n2"));
        assertThrows(IllegalArgumentException.class, () -> notebook.append("1\r"));
        assertThrows(IndexOutOfBoundsException.class, () -> notebook.line(3));
    }

    @Test
    void anEvaluationIsDeterministicAndTheIncrementalOneEqualsAFullOne() throws NotebookException {
        Notebook notebook = notebook("x = √2", "y = x * 3", "$2 / x", "ans + $1", "z = 1 / 0", "z", "x");
        assertEquals(NotebookEvaluator.evaluate(notebook), NotebookEvaluator.evaluate(notebook));
        EvaluatedNotebook previous = NotebookEvaluator.evaluate(notebook);
        Notebook edited = notebook.edit(notebook.line(5).id(), "z = 2").delete(notebook.line(2).id());
        assertEquals(NotebookEvaluator.evaluate(edited), NotebookEvaluator.reevaluate(previous, edited));
    }
}
