package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.export.NotebookExporter;
import calc.format.DisplayMode;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs.ExportFormat;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What docs/adr/0014-calcul-hors-interface.md decides beyond the acceptance tests of the task: a calculation that fails says so,
 * and an export waiting for its results is never lost in silence.
 */
class NotebookRecalculationTest {
    private static final String BLOCKED_LINE = "a + 2";
    private static final int EDT_LINES = 5000;
    private static final String HOSTILE = String.join("+", Collections.nCopies(65, "sqrt(2^4000+1)"));
    private static final long EDT_BUDGET_MILLIS = 300;

    @TempDir
    Path root;

    private NotebookStore store;
    private final FakeDialogs dialogs = new FakeDialogs();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch reached = new CountDownLatch(1);

    @BeforeEach
    void openTheStore() throws Exception {
        store = NotebookStore.open(Files.createDirectories(root.resolve("data")));
        store.saveCurrent(Notebook.of(List.of("a = 1", "a + 1", BLOCKED_LINE, "a + 3")), DisplayMode.AUTO);
    }

    @AfterEach
    void closeTheStore() {
        released.countDown();
        store.close();
    }

    // Stops the calculating thread on the blocked line, until the test lets it go.
    private LineResult blockingOnOneLine(String text, Supplier<LineResult> evaluation) {
        if (text.equals(BLOCKED_LINE)) {
            reached.countDown();
            try {
                assertTrue(released.await(30, TimeUnit.SECONDS), "nobody released the line");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        return evaluation.get();
    }

    private NotebookPresenter openBlocked() throws Exception {
        NotebookPresenter presenter = onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                NotebookPresenter.newRecalculationExecutor(), this::blockingOnOneLine));
        assertTrue(reached.await(30, TimeUnit.SECONDS), "the calculation never reached the blocked line");
        onEdt(() -> null); // what it posted before that line has arrived
        return presenter;
    }

    private void awaitTheEnd(NotebookPresenter presenter) {
        released.countDown();
        while (onEdt(presenter::isCalculating)) {
            Thread.onSpinWait();
        }
    }

    private static <T> T onEdt(Supplier<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable thrown) {
                result.completeExceptionally(thrown);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            throw new AssertionError(failed.getCause());
        } catch (Exception other) {
            throw new AssertionError(other);
        }
    }

    // ------------------------------------------------------------------------------------------------ a failure

    @Test
    void aCalculationThatFailsSaysSoAndTheNextCommandCalculatesAgain() {
        boolean[] failOnce = {true};
        BiFunction<String, Supplier<LineResult>, LineResult> failing = (text, evaluation) -> {
            if (text.equals(BLOCKED_LINE) && failOnce[0]) {
                failOnce[0] = false;
                throw new IllegalStateException("simulated failure of the calculation");
            }
            return evaluation.get();
        };
        NotebookDriver d = new NotebookDriver(onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                NotebookPresenter.newRecalculationExecutor(), failing)));

        assertEquals(List.of("1", "2", "", "", ""), d.results(), "what was known stays, nothing waits for ever");
        assertEquals(NotebookRow.Status.ERROR, d.row(3).status());
        assertEquals(NotebookPresenter.INTERRUPTED_LINE, d.row(3).errorMessage());
        assertTrue(d.view.statusMessage().contains("erreur inattendue"), d.view.statusMessage());
        assertEquals(StatusTone.ERROR, d.view.statusTone());

        d.down();
        assertEquals(NotebookRow.Status.ERROR, d.row(3).status(), "moving to another line is not a command");
        d.enter();
        assertEquals(List.of("1", "2", "3", "4", ""), d.results(), "Enter is one: it calculates again");
        assertEquals(NotebookPresenter.DEFAULT_STATUS, d.view.statusMessage());
    }

    /** Whatever ends the task, the event thread hears of it: the lines never say « Calcul en cours… » for ever. */
    @Test
    void anErrorOfAnyKindAlsoEndsTheCalculation() {
        BiFunction<String, Supplier<LineResult>, LineResult> dying = (text, evaluation) -> {
            if (text.equals(BLOCKED_LINE)) {
                throw new NoClassDefFoundError("simulated failure of the calculation, its stack trace is expected");
            }
            return evaluation.get();
        };

        NotebookDriver d = new NotebookDriver(onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                NotebookPresenter.newRecalculationExecutor(), dying)));

        assertEquals(NotebookPresenter.INTERRUPTED_LINE, d.row(3).errorMessage());
        assertTrue(d.view.statusMessage().contains("erreur inattendue"), d.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ the status bar

    @Test
    void aFailedAutomaticSaveIsSaidDuringTheCalculationAsWell() throws Exception {
        store.close();
        store = NotebookStore.open(root.resolve("data"), null, Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            throw new IOException("simulated full disk");
        });
        NotebookPresenter presenter = openBlocked();
        try {
            NotebookViewModel view = onEdt(() -> presenter.onEnter("a = 2"));

            assertTrue(view.statusMessage().startsWith("Calcul en cours : 0 / 4 lignes. Enregistrement automatique impossible"), view.statusMessage());
            assertEquals(StatusTone.ERROR, view.statusTone());
        } finally {
            awaitTheEnd(presenter);
        }
    }

    @Test
    void aRefusedLineIsSaidNextToTheCount() throws Exception {
        NotebookPresenter presenter = openBlocked();

        NotebookViewModel view = onEdt(() -> presenter.onEnter("1".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1)));

        assertTrue(view.statusMessage().startsWith("Calcul en cours : 2 / 4 lignes. La ligne dépasse"), view.statusMessage());
        assertEquals(StatusTone.ERROR, view.statusTone());
        awaitTheEnd(presenter);
    }

    // ------------------------------------------------------------------------------------------------ what is off the event thread

    /** Read from the source: the migration of an old history evaluates, and nothing else can tell which thread runs Main. */
    @Test
    void theApplicationLoadsTheStoreBeforeItGoesToTheEventThread() throws Exception {
        String main = Files.readString(Path.of("src", "main", "java", "calc", "Main.java"), StandardCharsets.UTF_8);
        int load = main.indexOf("store.load()");
        int eventThread = main.indexOf("SwingUtilities.invokeLater");

        assertTrue(load > 0 && eventThread > load, "the store is loaded on the main thread, before the event thread takes over");
    }

    // ------------------------------------------------------------------------------------------------ an export that waits

    @Test
    void anExportThatACommandCompletesIsConfirmedOverTheSentenceOfTheCommand() throws Exception {
        NotebookPresenter presenter = openBlocked();
        Path export = root.resolve("carnet.csv");
        dialogs.willExportTo(export);
        onEdt(() -> presenter.onExport(ExportFormat.CSV, "a = 1"));

        // The two lines from the blocked one down go: what is left is known already, and the export can be written at once.
        onEdt(() -> presenter.onActivate(3, "a = 1"));
        onEdt(() -> presenter.onDelete("a + 3"));
        NotebookViewModel view = onEdt(() -> presenter.onDelete(BLOCKED_LINE));
        awaitTheEnd(presenter);

        assertEquals("Exporté vers " + export.toAbsolutePath().normalize(), view.statusMessage(), "the user learns the file was written");
        assertArrayEquals(NotebookExporter.csv(NotebookEvaluator.evaluate(Notebook.of(List.of("a = 1", "a + 1"))), DisplayMode.AUTO), Files.readAllBytes(export));
    }


    @Test
    void closingWhileAnExportWaitsAsksAndEscapeAbandonsIt() throws Exception {
        NotebookPresenter presenter = openBlocked();
        Path export = root.resolve("carnet.csv");
        dialogs.willExportTo(export);
        NotebookViewModel asked = onEdt(() -> presenter.onExport(ExportFormat.CSV, "a = 1"));
        assertTrue(asked.statusMessage().startsWith("Calcul en cours"), asked.statusMessage());

        dialogs.willConfirmAbandonExport(false);
        NotebookViewModel refused = onEdt(() -> presenter.onQuit("a = 1"));
        assertFalse(refused.closeWindow(), "a file the user asked for is not lost by closing, unless they say so");
        assertEquals(List.of("export CSV", "abandon export"), dialogs.asked);
        assertTrue(onEdt(presenter::isCalculating), "the calculation goes on");

        NotebookViewModel interrupted = onEdt(() -> presenter.onCancelEdit("a = 1"));
        assertTrue(interrupted.statusMessage().contains("n'a pas été écrit"), interrupted.statusMessage());
        assertTrue(onEdt(() -> presenter.onQuit("a = 1")).closeWindow(), "nothing waits any more");
        awaitTheEnd(presenter);
        assertFalse(Files.exists(export));
    }

    @Test
    void closingAndAgreeingAbandonsAWaitingExportAndStopsTheCalculation() throws Exception {
        NotebookPresenter presenter = openBlocked();
        Path export = root.resolve("carnet.csv");
        dialogs.willExportTo(export).willConfirmAbandonExport(true);
        onEdt(() -> presenter.onExport(ExportFormat.CSV, "a = 1"));

        assertTrue(onEdt(() -> presenter.onQuit("a = 1")).closeWindow());
        assertFalse(onEdt(presenter::isCalculating), "closing cancels the calculation");
        released.countDown();
        assertFalse(Files.exists(export));
    }

    /** After an interruption, a command that changes nothing still calculates again, and what it said can be read at the end. */
    @Test
    void anUndoWithNothingToUndoCalculatesAgainAfterAnInterruption() throws Exception {
        NotebookPresenter presenter = openBlocked();
        onEdt(() -> presenter.onCancelEdit("a = 1"));
        released.countDown();

        NotebookViewModel view = onEdt(() -> presenter.onUndo("a = 1"));
        assertTrue(view.statusMessage().startsWith("Calcul en cours"), view.statusMessage());
        awaitTheEnd(presenter);

        NotebookViewModel end = onEdt(presenter::currentView);
        assertEquals(List.of("1", "2", "3", "4", ""), end.rows().stream().map(NotebookRow::result).toList());
        assertEquals("Il n'y a rien à annuler.", end.statusMessage());
    }

    @Test
    void aRedoWithNothingToRedoCalculatesAgainAfterAnInterruption() throws Exception {
        NotebookPresenter presenter = openBlocked();
        onEdt(() -> presenter.onCancelEdit("a = 1"));
        released.countDown();

        onEdt(() -> presenter.onRedo("a = 1"));
        awaitTheEnd(presenter);

        NotebookViewModel end = onEdt(presenter::currentView);
        assertEquals(List.of("1", "2", "3", "4", ""), end.rows().stream().map(NotebookRow::result).toList());
        assertEquals("Il n'y a rien à rétablir.", end.statusMessage());
    }

    @Test
    void aMoveThatMovesNothingCalculatesAgainAfterAnInterruption() throws Exception {
        NotebookPresenter presenter = openBlocked();
        onEdt(() -> presenter.onCancelEdit("a = 1"));
        released.countDown();

        onEdt(() -> presenter.onMoveLineUp("a = 1"));
        awaitTheEnd(presenter);

        NotebookViewModel end = onEdt(presenter::currentView);
        assertEquals(List.of("1", "2", "3", "4", ""), end.rows().stream().map(NotebookRow::result).toList());
        assertEquals("La ligne est déjà la première.", end.statusMessage());
    }

    @Test
    void choosingTheModeInForceCalculatesAgainAfterAnInterruption() throws Exception {
        NotebookPresenter presenter = openBlocked();
        onEdt(() -> presenter.onCancelEdit("a = 1"));
        released.countDown();

        onEdt(() -> presenter.onDisplayModeChanged(DisplayMode.AUTO, "a = 1"));
        awaitTheEnd(presenter);

        NotebookViewModel end = onEdt(presenter::currentView);
        assertEquals(List.of("1", "2", "3", "4", ""), end.rows().stream().map(NotebookRow::result).toList());
        assertEquals(NotebookPresenter.DEFAULT_STATUS, end.statusMessage());
    }

    @Test
    void aSaveAfterAnInterruptionSaysItWasWrittenWhileTheLinesStayInterrupted() throws Exception {
        NotebookPresenter presenter = openBlocked();
        onEdt(() -> presenter.onCancelEdit("a = 1"));
        released.countDown();
        Path file = root.resolve("garde.calc.json");
        dialogs.willSaveTo(file);

        NotebookViewModel saved = onEdt(() -> presenter.onSaveAs("a = 1"));

        assertEquals("Carnet enregistré dans " + file.toAbsolutePath().normalize() + ".", saved.statusMessage());
        assertEquals(NotebookPresenter.INTERRUPTED_LINE, saved.rows().get(3).errorMessage(), "saving is not one of the commands that calculate");
        assertFalse(onEdt(presenter::isCalculating));
    }

    @Test
    void openingAnotherFileAbandonsAWaitingExportAndSaysSo() throws Exception {
        Path other = root.resolve("autre.calc.json");
        Files.write(other, NotebookCodec.encode(Notebook.of(List.of("9")), DisplayMode.AUTO).bytes());
        NotebookPresenter presenter = openBlocked();
        Path export = root.resolve("carnet.csv");
        dialogs.willExportTo(export).willOpen(other);
        onEdt(() -> presenter.onExport(ExportFormat.CSV, "a = 1"));

        onEdt(() -> presenter.onOpen("a = 1"));
        awaitTheEnd(presenter);

        assertEquals("« autre.calc.json » ouvert (1 ligne). L'export demandé n'a pas été écrit.", onEdt(presenter::currentView).statusMessage());
        assertFalse(Files.exists(export), "the lines of another document never go to that file");
    }

    @Test
    void aNewNotebookAbandonsAWaitingExportAndSaysSo() throws Exception {
        NotebookPresenter presenter = openBlocked();
        Path export = root.resolve("carnet.csv");
        dialogs.willExportTo(export).willAnswer(NotebookDialogs.Unsaved.DISCARD);
        onEdt(() -> presenter.onExport(ExportFormat.CSV, "a = 1"));

        onEdt(() -> presenter.onNew("a = 1"));
        awaitTheEnd(presenter);

        assertEquals("Nouveau carnet. L'export demandé n'a pas été écrit.", onEdt(presenter::currentView).statusMessage());
        assertFalse(Files.exists(export));
    }

    // ------------------------------------------------------------------------------------------------ during a dialog

    /** A real dialog runs the events of the window while the intention waits: what arrives then is about the notebook as it is. */
    @Test
    @Tag("ui")
    void aCalculationThatEndsDuringADialogShowsTheNotebookOfThatMoment() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        NotebookFrame[] frame = new NotebookFrame[1];
        List<String>[] duringTheDialog = newListHolder();
        NotebookDialogs modal = new NotebookDialogs() {
            @Override
            public Optional<Path> chooseFileToOpen(Optional<Path> current) {
                SecondaryLoop loop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
                Thread waiter = new Thread(() -> {
                    NotebookDriver.awaitWindows();
                    loop.exit();
                }, "dialog-of-the-test");
                waiter.setDaemon(true);
                waiter.start();
                assertTrue(loop.enter(), "the events of the window run during the dialog");
                duringTheDialog[0] = List.of(frame[0].row(4).field.getText(), frame[0].row(4).resultLabel.getText(), frame[0].row(5).field.getText());
                return Optional.empty();
            }

            @Override
            public Optional<Path> chooseFileToSave(Optional<Path> current) {
                return Optional.empty();
            }

            @Override
            public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
                return Optional.empty();
            }

            @Override
            public Unsaved askToSave(String name, Action action) {
                return Unsaved.CANCEL;
            }

            @Override
            public boolean confirmReplaceDraft() {
                return false;
            }

            @Override
            public boolean confirmAbandonExport() {
                return false;
            }

            @Override
            public boolean confirmOverwrite(Path file) {
                return false;
            }
        };
        EventQueue.invokeAndWait(() -> frame[0] = new NotebookFrame(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), modal,
                NotebookPresenter.newRecalculationExecutor())));
        try {
            NotebookDriver.awaitWindows();
            EventQueue.invokeAndWait(() -> {
                // Down to the new line at the end. Ctrl+O validates what it holds first, and only then shows the dialog.
                for (int line = 0; line < 4; line++) {
                    frame[0].row(line).field.getActionMap().get("notebook-NEXT").actionPerformed(new ActionEvent(frame[0], 0, ""));
                }
                frame[0].row(4).field.setText("a + 9");
                frame[0].row(4).field.getActionMap().get("notebook-OPEN_DOCUMENT").actionPerformed(new ActionEvent(frame[0], 0, ""));
            });
            assertEquals(List.of("a + 9", "10", ""), duringTheDialog[0], "the line just validated, its result and the new line");
            assertEquals(6, frame[0].rowCount());
        } finally {
            EventQueue.invokeAndWait(frame[0]::dispose);
        }
    }

    /** With the focus on the selector of the modes: what Swing does with the key, from the focused component up. */
    @Test
    @Tag("ui")
    void escapeInterruptsTheCalculationFromTheSelectorOfTheModes() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        NotebookPresenter presenter = openBlocked();
        NotebookFrame[] frame = new NotebookFrame[1];
        EventQueue.invokeAndWait(() -> frame[0] = new NotebookFrame(presenter));
        try {
            EventQueue.invokeAndWait(() -> {
                KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
                JComponent selector = frame[0].modeSelector();
                boolean done = false;
                for (Container each = selector; each instanceof JComponent component && !done; each = each.getParent()) {
                    Object key = component.getInputMap(each == selector ? JComponent.WHEN_FOCUSED : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape);
                    if (key == null && each == selector) {
                        key = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(escape);
                    }
                    Action action = key == null ? null : component.getActionMap().get(key);
                    if (action != null && action.isEnabled()) {
                        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, "escape"));
                        done = true;
                    }
                }
                assertTrue(done, "nothing takes Escape from the selector");
                assertEquals(NotebookPresenter.INTERRUPTED_LINE, frame[0].row(3).errorLabel.getText(), "the sentence, by itself");
                assertTrue(frame[0].statusLabel().getText().contains("interrompu"), frame[0].statusLabel().getText());
            });
        } finally {
            awaitTheEnd(presenter);
            EventQueue.invokeAndWait(frame[0]::dispose);
        }
    }

    /**
     * Defect B1 in the real window, what the frozen test measures in the presenter only: File > Open on 5000 hostile lines, on an
     * empty window and on a full one, gives the event thread back within the budget, and it keeps answering while the rows appear.
     */
    @Test
    @Tag("ui")
    void openingTheFileOfB1InTheRealWindowKeepsTheEventThreadAnswering() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        String hostile = HOSTILE;
        Path first = root.resolve("b1.calc.json");
        Path second = root.resolve("b1-bis.calc.json");
        Files.write(first, NotebookCodec.encode(Notebook.of(Collections.nCopies(EDT_LINES, hostile)), DisplayMode.AUTO).bytes());
        Files.write(second, NotebookCodec.encode(Notebook.of(Collections.nCopies(EDT_LINES, hostile + "+1")), DisplayMode.AUTO).bytes());
        store.saveCurrent(Notebook.empty(), DisplayMode.AUTO);

        NotebookFrame[] frame = new NotebookFrame[1];
        EventQueue.invokeAndWait(() -> {
            frame[0] = new NotebookFrame(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), dialogs, NotebookPresenter.newRecalculationExecutor()));
            frame[0].setVisible(true);
        });
        try {
            long bestEmpty = Long.MAX_VALUE;
            long bestFull = Long.MAX_VALUE;
            List<Long> answers = new ArrayList<>();
            // Twice from a window of one row, twice from a full one, after a first run that warms the code up.
            for (int run = 0; run < 5; run++) {
                boolean fromFull = run >= 3;
                Path file = fromFull && run == 3 ? second : first;
                if (!fromFull) {
                    dialogs.willAnswer(NotebookDialogs.Unsaved.DISCARD).willConfirmReplaceDraft(true);
                    EventQueue.invokeAndWait(() -> frame[0].row(0).field.getActionMap().get("notebook-NEW_DOCUMENT").actionPerformed(new ActionEvent(frame[0], 0, "")));
                }
                dialogs.reset();
                dialogs.willOpen(file);
                long elapsed = openAndSample(frame[0], answers);
                if (run > 0) {
                    bestEmpty = fromFull ? bestEmpty : Math.min(bestEmpty, elapsed);
                    bestFull = fromFull ? Math.min(bestFull, elapsed) : bestFull;
                }
            }
            List<Long> sorted = answers.stream().sorted().toList();
            long worst = sorted.get(sorted.size() - 1);
            long percentile = sorted.get(sorted.size() * 99 / 100);
            System.out.printf("[acceptance] File > Open of %d hostile lines in the window: best %d ms from an empty window, best %d ms from a full one;"
                    + " while the rows appear the event thread answers in %d ms at the 99th percentile, worst %d ms%n", EDT_LINES, bestEmpty, bestFull, percentile, worst);
            TimingBudget.assertWithin("File > Open from an empty window", bestEmpty, EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            TimingBudget.assertWithin("File > Open from a full window", bestFull, EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            TimingBudget.assertWithin("the event thread answers, 99th percentile", percentile, EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            assertTrue(sorted.size() > 10, "the wait was sampled: " + sorted.size());

            EventQueue.invokeAndWait(() -> {
                assertEquals(EDT_LINES + 1, frame[0].rowCount());
                assertEquals(hostile, frame[0].row(EDT_LINES - 1).field.getText(), "every text is there");
                assertEquals(NotebookPresenter.PENDING_RESULT, frame[0].row(EDT_LINES - 1).resultLabel.getText());
            });
        } finally {
            dialogs.reset();
            dialogs.willAnswer(NotebookDialogs.Unsaved.DISCARD);
            EventQueue.invokeAndWait(() -> frame[0].dispatchEvent(new WindowEvent(frame[0], WindowEvent.WINDOW_CLOSING)));
            EventQueue.invokeAndWait(frame[0]::dispose);
        }
    }

    /**
     * Opening another document removes the rows below the first ones, here the very row that holds the focus. The focus event that
     * follows must not be taken for the user going to another line: the document opens on its first line, and the caret goes there.
     */
    @Test
    @Tag("ui")
    void openingAnotherDocumentFromARowThatGoesAwayKeepsTheFirstLineActive() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        List<String> before = new ArrayList<>();
        List<String> after = new ArrayList<>();
        for (int line = 1; line <= 300; line++) {
            before.add("1 + " + line);
            after.add("2 + " + line);
        }
        store.saveCurrent(Notebook.of(before), DisplayMode.AUTO);
        Path other = root.resolve("autre.calc.json");
        Files.write(other, NotebookCodec.encode(Notebook.of(after), DisplayMode.AUTO).bytes());

        NotebookFrame[] frame = new NotebookFrame[1];
        EventQueue.invokeAndWait(() -> {
            frame[0] = new NotebookFrame(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), dialogs, NotebookPresenter.newRecalculationExecutor()));
            frame[0].setVisible(true);
            frame[0].toFront();
        });
        try {
            NotebookDriver.awaitWindows();
            int row = 120;
            EventQueue.invokeAndWait(() -> frame[0].row(row).field.requestFocusInWindow());
            assumeTrue(awaitOnEdt(() -> frame[0].row(row).field.isFocusOwner() && frame[0].shown().activeIndex() == row),
                    "a system without a window manager gives no focus: nothing here can be observed");

            dialogs.willOpen(other);
            EventQueue.invokeAndWait(() -> frame[0].row(row).field.getActionMap().get("notebook-OPEN_DOCUMENT").actionPerformed(new ActionEvent(frame[0], 0, "")));
            NotebookDriver.awaitWindows();

            assertTrue(awaitOnEdt(() -> frame[0].row(0).field.isFocusOwner()), "the caret goes to the first line");
            NotebookDriver.awaitWindows();
            EventQueue.invokeAndWait(() -> {
                assertEquals(0, frame[0].shown().activeIndex(), "the focus event of the row that went away moved nothing");
                assertEquals(301, frame[0].rowCount());
                assertEquals("2 + 1", frame[0].row(0).field.getText());
                assertEquals("2 + 300", frame[0].row(299).field.getText());
                assertEquals("302", frame[0].row(299).resultLabel.getText());
                assertEquals("« autre.calc.json » ouvert (300 lignes).", frame[0].statusLabel().getText());
            });
        } finally {
            EventQueue.invokeAndWait(frame[0]::dispose);
        }
    }

    /** On a tall screen the first rows do not fill the view: a click under them, while the others appear, is not on the last line. */
    @Test
    @Tag("ui")
    void aClickUnderTheRowsWhileTheyAppearActivatesNoLine() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        List<String> lines = new ArrayList<>();
        for (int line = 1; line <= 2000; line++) {
            lines.add("1 + " + line);
        }
        Path file = root.resolve("grand.calc.json");
        Files.write(file, NotebookCodec.encode(Notebook.of(lines), DisplayMode.AUTO).bytes());
        store.saveCurrent(Notebook.empty(), DisplayMode.AUTO);

        NotebookFrame[] frame = new NotebookFrame[1];
        EventQueue.invokeAndWait(() -> {
            frame[0] = new NotebookFrame(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), dialogs, NotebookPresenter.newRecalculationExecutor()));
            frame[0].setVisible(true);
            frame[0].toFront();
        });
        try {
            assumeTrue(awaitOnEdt(() -> frame[0].row(0).field.isFocusOwner()), "a system without a window manager gives no focus: nothing here can be observed");
            dialogs.willOpen(file);
            boolean[] clicked = new boolean[1];
            EventQueue.invokeAndWait(() -> {
                frame[0].row(0).field.getActionMap().get("notebook-OPEN_DOCUMENT").actionPerformed(new ActionEvent(frame[0], 0, ""));
                assertTrue(frame[0].rowCount() < 2001, "the rows are still appearing");
                JViewport viewport = frame[0].scrollViewport();
                MouseEvent click = new MouseEvent(viewport, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 5, 5, 1, false);
                for (MouseListener listener : viewport.getMouseListeners()) {
                    listener.mousePressed(click);
                    clicked[0] = true;
                }
            });
            assertTrue(clicked[0], "the view listens to the mouse");
            NotebookDriver.awaitWindows();
            assertTrue(awaitOnEdt(() -> frame[0].rowCount() == 2001));
            NotebookDriver.awaitWindows();
            EventQueue.invokeAndWait(() -> assertEquals(0, frame[0].shown().activeIndex(), "the first line stays active"));

            // Once they are all there, the click goes to the new line at the end, as it always did.
            EventQueue.invokeAndWait(() -> {
                JViewport viewport = frame[0].scrollViewport();
                MouseEvent click = new MouseEvent(viewport, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 5, 5, 1, false);
                for (MouseListener listener : viewport.getMouseListeners()) {
                    listener.mousePressed(click);
                }
            });
            assertTrue(awaitOnEdt(() -> frame[0].shown().activeIndex() == 2000), "the new line at the end");
        } finally {
            EventQueue.invokeAndWait(frame[0]::dispose);
        }
    }

    // Until the condition holds on the event thread, or the deadline: a focus change arrives by its own events.
    private static boolean awaitOnEdt(Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (onEdt(condition)) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
    }

    /**
     * The same budget at its hardest: the first File > Open of a process, before anything is compiled. In a fresh JVM, the event
     * thread comes back from the command, and answers at every moment until the last row is there, within the budget. As in the
     * frozen tests, the best of three runs, so that a pause of the machine does not count as a defect of the window.
     */
    @Test
    @Tag("ui")
    void theFirstOpenOfAProcessStaysWithinTheBudget() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        Path file = root.resolve("b1.calc.json");
        Files.write(file, NotebookCodec.encode(Notebook.of(Collections.nCopies(EDT_LINES, HOSTILE)), DisplayMode.AUTO).bytes());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        long best = Long.MAX_VALUE;
        List<String> runs = new ArrayList<>();
        for (int run = 0; run < 3 && best >= TimingBudget.scaled(EDT_BUDGET_MILLIS); run++) {
            Path data = Files.createDirectories(root.resolve("cold-" + run));
            Path output = root.resolve("cold-" + run + ".txt");
            Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ColdOpen.class.getName(), file.toString(), data.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "the process of the measure did not end");
            } finally {
                process.destroyForcibly();
            }
            String result = Files.readAllLines(output, StandardCharsets.UTF_8).stream().filter(line -> line.startsWith("COLD ")).findFirst()
                    .orElseThrow(() -> new AssertionError("no measure: " + readQuietly(output)));
            runs.add(result);
            String[] parts = result.split(" ");
            best = Math.min(best, Math.max(Long.parseLong(parts[1]), Long.parseLong(parts[2])));
        }
        System.out.printf("[acceptance] first File > Open of a process on %d hostile lines (command, worst wait of the event thread, in ms): %s%n", EDT_LINES, runs);
        TimingBudget.assertWithin("the first File > Open of a process, best of three: " + runs, best, EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            return unreadable.toString();
        }
    }

    /** What runs in the process of the test above: one window, one File > Open, and a look at the event thread from outside. */
    static final class ColdOpen {
        private ColdOpen() {
        }

        public static void main(String[] args) throws Exception {
            Path file = Path.of(args[0]);
            NotebookStore store = NotebookStore.open(Path.of(args[1]));
            NotebookDialogs dialogs = NotebookDialogs.cancelling();
            NotebookDialogs choosing = new NotebookDialogs() {
                @Override
                public Optional<Path> chooseFileToOpen(Optional<Path> current) {
                    return Optional.of(file);
                }

                @Override
                public Optional<Path> chooseFileToSave(Optional<Path> current) {
                    return dialogs.chooseFileToSave(current);
                }

                @Override
                public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
                    return dialogs.chooseExportFile(format, suggested);
                }

                @Override
                public Unsaved askToSave(String name, Action action) {
                    return dialogs.askToSave(name, action);
                }

                @Override
                public boolean confirmReplaceDraft() {
                    return dialogs.confirmReplaceDraft();
                }

                @Override
                public boolean confirmOverwrite(Path target) {
                    return dialogs.confirmOverwrite(target);
                }

                @Override
                public boolean confirmAbandonExport() {
                    return dialogs.confirmAbandonExport();
                }
            };
            NotebookFrame[] frame = new NotebookFrame[1];
            EventQueue.invokeAndWait(() -> {
                frame[0] = new NotebookFrame(NotebookPresenter.open(store, store.load(), DisplayModeStore.noop(), choosing, NotebookPresenter.newRecalculationExecutor()));
                frame[0].setVisible(true);
            });
            // The command is posted first, so that the wait below sees all of it, like any event of the window waiting behind it.
            long[] command = new long[1];
            long posted = System.nanoTime();
            EventQueue.invokeLater(() -> {
                long start = System.nanoTime();
                frame[0].row(0).field.getActionMap().get("notebook-OPEN_DOCUMENT").actionPerformed(new ActionEvent(frame[0], 0, ""));
                command[0] = (System.nanoTime() - start) / 1_000_000;
            });
            long worst = (System.nanoTime() - posted) / 1_000_000;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            int[] rows = new int[1];
            while (rows[0] <= EDT_LINES && System.nanoTime() < deadline) {
                long start = System.nanoTime();
                EventQueue.invokeAndWait(() -> rows[0] = frame[0].rowCount());
                worst = Math.max(worst, (System.nanoTime() - start) / 1_000_000);
            }
            System.out.println("COLD " + command[0] + " " + worst + " rows=" + rows[0]);
            System.exit(rows[0] > EDT_LINES ? 0 : 1);
        }
    }

    // Runs File > Open on the event thread and returns how long it held it; round trips are sampled until all the rows are there.
    private static long openAndSample(NotebookFrame frame, List<Long> answers) throws Exception {
        long[] elapsed = new long[1];
        EventQueue.invokeAndWait(() -> {
            long start = System.nanoTime();
            frame.row(0).field.getActionMap().get("notebook-OPEN_DOCUMENT").actionPerformed(new ActionEvent(frame, 0, ""));
            elapsed[0] = (System.nanoTime() - start) / 1_000_000;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        int[] rows = new int[1];
        while (rows[0] <= EDT_LINES) {
            assertTrue(System.nanoTime() < deadline, "the rows never all appeared: " + rows[0]);
            long start = System.nanoTime();
            EventQueue.invokeAndWait(() -> rows[0] = frame.rowCount());
            answers.add((System.nanoTime() - start) / 1_000_000);
        }
        return elapsed[0];
    }

    @SuppressWarnings("unchecked")
    private static List<String>[] newListHolder() {
        return (List<String>[]) new List<?>[1];
    }
}
