package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.format.ValueFormatter;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The preview of one line, off the event thread: the executor is held by the test, which runs each task when it wants, as
 * NotebookRecalculationTest does for the calculation.
 */
class NotebookPreviewTest {
    private final Deque<Runnable> tasks = new ArrayDeque<>();
    private final List<String> evaluated = new CopyOnWriteArrayList<>();
    private final List<String> received = new CopyOnWriteArrayList<>();
    private NotebookPreview<String> preview;
    private Notebook notebook;
    private List<LineResult> known;
    // The line after which the evaluator cancels the preview, as a keystroke on the event thread would.
    private String cancelAfter;

    @BeforeEach
    void build() throws Exception {
        preview = new NotebookPreview<>(tasks::add, this::recording, (key, result) -> received.add(key + " -> " + shown(result)));
        notebook = Notebook.of(List.of("a = 2", "a * 10", "a + 1"));
        known = NotebookEvaluator.evaluate(notebook).results();
    }

    private LineResult recording(String text, Supplier<LineResult> evaluation) {
        evaluated.add(text);
        LineResult result = evaluation.get();
        if (text.equals(cancelAfter)) {
            preview.cancel();
        }
        return result;
    }

    private static String shown(LineResult result) {
        if (result == null) {
            return "failed";
        }
        return result.hasValue() ? ValueFormatter.format(result.value(), DisplayMode.AUTO).text() : "no value";
    }

    // Runs the next task on this thread, as the preview thread would, then lets what it posted arrive.
    private void runNext() throws Exception {
        tasks.removeFirst().run();
        SwingUtilities.invokeAndWait(() -> { });
    }

    private Notebook second(String text) {
        return notebook.edit(notebook.line(2).id(), text);
    }

    @Test
    void onlyTheLineAskedForIsEvaluatedOnTheResultsKeptAboveIt() throws Exception {
        preview.request(second("a * 7"), 1, known.subList(0, 1), "second");
        runNext();

        assertEquals(List.of("a * 7"), evaluated, "the line above is kept, the line below is never evaluated");
        assertEquals(List.of("second -> 14"), received);
        assertTrue(tasks.isEmpty());
    }

    @Test
    void theLinesAboveThatAreNotKnownAreEvaluatedFirst() throws Exception {
        preview.request(second("a * 7"), 1, List.of(), "second");
        runNext();

        assertEquals(List.of("a = 2", "a * 7"), evaluated);
        assertEquals(List.of("second -> 14"), received);
    }

    @Test
    void theNewLineAtTheEndIsPreviewedOnTheWholeNotebook() throws Exception {
        preview.request(notebook.append("ans * 2"), 3, known, "new");
        runNext();

        assertEquals(List.of("ans * 2"), evaluated);
        assertEquals(List.of("new -> 6"), received);
    }

    @Test
    void aRequestReplacedBeforeItRunsEvaluatesNothing() throws Exception {
        preview.request(second("a * 7"), 1, known.subList(0, 1), "first");
        preview.request(second("a * 5"), 1, known.subList(0, 1), "second");
        runNext();
        assertEquals(List.of(), evaluated, "the first request is over before it starts");
        runNext();

        assertEquals(List.of("a * 5"), evaluated);
        assertEquals(List.of("second -> 10"), received);
    }

    @Test
    void anAnswerThatArrivesAfterANewRequestIsThrownAway() throws Exception {
        preview.request(second("a * 7"), 1, known.subList(0, 1), "first");
        Runnable first = tasks.removeFirst();
        // The answer of the first request is posted; the second request comes before the event thread takes it in.
        SwingUtilities.invokeAndWait(() -> {
            first.run();
            preview.request(second("a * 5"), 1, known.subList(0, 1), "second");
        });
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(List.of(), received, "the stale answer is dropped");
        runNext();

        assertEquals(List.of("a * 7", "a * 5"), evaluated);
        assertEquals(List.of("second -> 10"), received);
    }

    @Test
    void cancellingStopsBeforeTheNextLineAndNothingArrives() throws Exception {
        preview.request(second("a * 7"), 1, List.of(), "second");
        cancelAfter = "a = 2";
        runNext();

        assertEquals(List.of("a = 2"), evaluated, "the line under way ends, the next one never starts");
        assertEquals(List.of(), received);

        preview.request(second("a * 7"), 1, known.subList(0, 1), "again");
        preview.cancel();
        runNext();
        assertEquals(List.of("a = 2"), evaluated, "a request cancelled before it starts evaluates nothing");
        assertEquals(List.of(), received);
    }

    @Test
    void aPreviewThatFailsEndsWithoutAResult() throws Exception {
        NotebookPreview<String> failing = new NotebookPreview<>(tasks::add, (text, evaluation) -> {
            throw new IllegalStateException("probe");
        }, (key, result) -> received.add(key + " -> " + shown(result)));
        Logger logger = Logger.getLogger(NotebookPreview.class.getName());
        Level level = logger.getLevel();
        logger.setLevel(Level.OFF);
        try {
            failing.request(second("a * 7"), 1, known.subList(0, 1), "second");
            runNext();
        } finally {
            logger.setLevel(level);
        }

        assertEquals(List.of("second -> failed"), received, "the owner hears that the request has ended");
    }

    @Test
    void aLineOutsideTheNotebookOrBelowTheResultsKeptIsRefused() {
        Notebook edited = second("a * 7");
        assertThrows(IllegalArgumentException.class, () -> preview.request(edited, -1, List.of(), "key"));
        assertThrows(IllegalArgumentException.class, () -> preview.request(edited, 3, List.of(), "key"));
        assertThrows(IllegalArgumentException.class, () -> preview.request(edited, 1, known.subList(0, 2), "key"));
        assertTrue(tasks.isEmpty());
    }

    @Test
    void theApplicationPreviewsOnItsOwnDaemonThread() throws Exception {
        Executor executor = NotebookPreview.newBackgroundThread();
        CompletableFuture<Thread> thread = new CompletableFuture<>();
        executor.execute(() -> thread.complete(Thread.currentThread()));

        Thread used = thread.get(10, TimeUnit.SECONDS);
        assertEquals(NotebookPreview.THREAD_NAME, used.getName());
        assertTrue(used.isDaemon(), "the preview never keeps the process alive");
    }
}
