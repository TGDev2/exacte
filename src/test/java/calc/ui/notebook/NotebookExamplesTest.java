package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The examples of the empty notebook and of the syntax help, and the presenter command that inserts them. The window side
 * (buttons, help window) is ExamplesAndHelpAcceptanceTest.
 */
class NotebookExamplesTest {
    @TempDir
    Path data;

    private NotebookStore store;
    private NotebookDriver d;

    @BeforeEach
    void start() {
        store = NotebookStore.open(data);
        d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop()));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    @Test
    void everyExampleStandsAloneAndIsWithoutError() throws Exception {
        for (NotebookExamples example : NotebookExamples.values()) {
            assertFalse(example.title().isBlank(), example.name());
            assertNoError(example.name(), example.lines());
        }
        for (NotebookHelp.Insertable example : NotebookHelp.INSERTABLE) {
            assertFalse(example.title().isBlank());
            assertNoError(example.title(), example.lines());
            assertFalse(example.lines().stream().anyMatch(line -> line.contains("$")),
                    example.title() + " uses no $n: it would designate a line of the notebook it lands in");
        }
    }

    private static void assertNoError(String name, List<String> lines) throws Exception {
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(Notebook.of(lines));
        for (LineResult result : evaluated.results()) {
            assertFalse(result.isError(), name + ": « " + result.text() + " »");
        }
    }

    @Test
    void anEmptyNotebookShowsTheExamplesAndAWrittenLineHidesThem() {
        assertTrue(d.view.examplesVisible());

        d.line("1");

        assertFalse(d.view.examplesVisible());
    }

    @Test
    void insertedInAnEmptyNotebookTheLinesAreTheNotebookAndTheFirstIsActive() throws Exception {
        insert(NotebookExamples.RACINE.lines(), "");

        List<String> lines = NotebookExamples.RACINE.lines();
        assertEquals(lines, d.texts().subList(0, lines.size()));
        assertEquals("", d.row(lines.size() + 1).text(), "the new line stays at the end");
        assertEquals(1, d.active());
        assertFalse(d.view.examplesVisible());
        assertTrue(d.view.canUndo());
    }

    @Test
    void insertedBelowTheActiveLineTheLinesFollowTheTextNowInItsField() throws Exception {
        d.line("a = 1").line("a + 1").up();
        assertEquals(2, d.active());

        insert(List.of("7", "8"), "a + 2");

        assertEquals(List.of("a = 1", "a + 2", "7", "8", ""), d.texts(), "the typed text is kept, the lines go below it");
        assertEquals(3, d.active(), "the first inserted line is active");
        undo();
        assertEquals(List.of("a = 1", "a + 1", ""), d.texts(), "one undo takes back the insertion and the typed text");
    }

    @Test
    void insertedAtTheNewLineWithATypedTextTheTextBecomesALineAboveThem() throws Exception {
        d.line("a = 1");

        insert(List.of("7", "8"), "a * 3");

        assertEquals(List.of("a = 1", "a * 3", "7", "8", ""), d.texts());
        assertEquals(3, d.active());
    }

    @Test
    void anInsertionThatWouldExceedTheMaximumNumberOfLinesIsRefusedAsAWhole() throws Exception {
        d.presenter.onPaste(0, 0, 0, String.join("\n", Collections.nCopies(Notebook.MAX_LINES - 1, "1")));
        d.awaitIdle();

        insert(List.of("2", "3"), "");

        NotebookViewModel view = d.presenter.currentView();
        assertTrue(view.statusMessage().startsWith(NotebookPresenter.EXAMPLE_REFUSED), view.statusMessage());
        assertEquals(Notebook.MAX_LINES, view.rows().size(), "4 999 lines and the new line: nothing was added");
    }

    @Test
    void aLineLongerThanTheMaximumIsRefusedAsAWhole() throws Exception {
        insert(List.of("1", "9".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1)), "");

        NotebookViewModel view = d.presenter.currentView();
        assertTrue(view.statusMessage().startsWith(NotebookPresenter.EXAMPLE_REFUSED), view.statusMessage());
        assertEquals(1, view.rows().size(), "the empty notebook is unchanged");
    }

    @Test
    void anEmptyExampleChangesNothing() throws Exception {
        insert(List.of(), "");

        assertFalse(d.presenter.currentView().canUndo());
        assertEquals(1, d.presenter.currentView().rows().size());
    }

    // What the window does for a button: the intention on the event thread with the text of the active field.
    private void insert(List<String> lines, String pending) throws Exception {
        SwingUtilities.invokeAndWait(() -> d.presenter.onInsertExample(lines, pending));
        d.awaitIdle();
        SwingUtilities.invokeAndWait(() -> d.view = d.presenter.currentView());
    }

    // Ctrl+Z with what the active field now holds, which the driver does not know after an insertion.
    private void undo() throws Exception {
        String pending = d.view.activeDraft();
        SwingUtilities.invokeAndWait(() -> d.presenter.onUndo(pending));
        d.awaitIdle();
        SwingUtilities.invokeAndWait(() -> d.view = d.presenter.currentView());
    }
}
