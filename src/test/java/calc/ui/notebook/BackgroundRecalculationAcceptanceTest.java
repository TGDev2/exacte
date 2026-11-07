package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.Main;
import calc.export.NotebookExporter;
import calc.expression.Span;
import calc.expression.ParsedLine;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.persistence.DataDirectory;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import calc.ui.ResultDescription;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs.ExportFormat;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import calc.ui.notebook.NotebookRow.Status;
import calc.ui.widget.DisplayModeText;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The notebook is calculated off the Swing event thread, with cancellation (defect B1 of docs/RECETTE.md). The presenter is
 * driven on the real event thread, as the window does, with a piloted executor and an instrumented evaluator; nothing here
 * synchronises with a fixed wait.
 *
 * <p>What this test asks of the production code, on top of the existing intentions of {@link NotebookPresenter}:
 * <ul>
 *   <li>{@code static NotebookPresenter open(NotebookStore, DisplayModeStore, NotebookDialogs, Executor, BiFunction)}: the
 *   executor receives the recalculation; the function {@code (text of the line, real evaluation) -> result} is called on the
 *   calculating thread for each line really evaluated, and must return what the real evaluation gives.</li>
 *   <li>{@code void setViewListener(Consumer<NotebookViewModel>)}: called on the event thread with each new state a calculation
 *   produces (counter, results, end). When the calculating thread finishes a line, the count of the status bar ends up saying
 *   so: events may be merged on the event thread, but a line finished is never left out because of a timer.</li>
 *   <li>a row not calculated yet has {@code result() == "Calcul en cours…"}; a row left by an interruption has status
 *   {@link Status#ERROR} and {@code errorMessage() == "Non calculée : calcul interrompu"}.</li>
 * </ul>
 * These two seams are looked up by reflection so that this class compiles, and the rest of the suite runs, before they exist.
 */
@Tag("acceptance")
class BackgroundRecalculationAcceptanceTest {
    private static final String PENDING = "Calcul en cours…";
    private static final String INTERRUPTED = "Non calculée : calcul interrompu";
    private static final Pattern PROGRESS = Pattern.compile("Calcul en cours : (\\d+) / (\\d+) lignes");
    /** A line of the reproduction of B1: valid, under every limit, and about 37 ms of work. */
    private static final String HOSTILE_LINE = String.join("+", Collections.nCopies(65, "sqrt(2^4000+1)"));
    private static final int HOSTILE_LINES = 5000;
    private static final long EDT_BUDGET_MILLIS = 300;
    private static final long CLOSE_BUDGET_SECONDS = 2;
    private static final long EDT_PATIENCE_SECONDS = 10;
    private static final long GATE_PATIENCE_SECONDS = 30;
    private static final long QUIET_PATIENCE_SECONDS = 60;
    private static final String THE_LINE_THAT_BLOCKS = "a + 4";
    private static final String THE_LINE_AFTER_IT = "a + 5";

    @TempDir
    Path root;

    private final List<Lab> labs = new ArrayList<>();
    private int nextDirectory;

    @AfterEach
    void closeTheLabs() {
        labs.forEach(Lab::close);
    }

    // ------------------------------------------------------------------------------------------------ no evaluation on the EDT

    @Test
    void noExpressionIsEvaluatedOnTheEventThreadWhateverTheCommand() throws Exception {
        Path other = notebookFile("autre.calc.json", List.of("b = 5", "b + 1"));
        Lab lab = lab(false, List.of("a = 2", "a + 1", "a + 2"));
        lab.expectOffTheEventThread("the start", true);

        lab.edit(1, "a = 3");
        lab.expectOffTheEventThread("validating a line", true);
        lab.type("a + 3");
        lab.command((p, f) -> p.onInsertBelow(f));
        lab.expectOffTheEventThread("adding a line", true);
        lab.command((p, f) -> p.onDelete(f));
        lab.expectOffTheEventThread("deleting a line", true);
        lab.command((p, f) -> p.onUndo(f));
        lab.expectOffTheEventThread("undoing", true);
        lab.command((p, f) -> p.onRedo(f));
        lab.expectOffTheEventThread("redoing", true);
        lab.command((p, f) -> p.onDisplayModeChanged(DisplayMode.SCIENTIFIC, f));
        lab.expectOffTheEventThread("changing the mode", false);
        lab.dialogs.willOpen(other);
        lab.command((p, f) -> p.onOpen(f));
        lab.expectOffTheEventThread("opening a file", true);
        lab.dialogs.willConfirmReplaceDraft(true);
        lab.command((p, f) -> p.onNew(f));
        lab.expectOffTheEventThread("New", false);
        lab.line("1 + 1");
        lab.expectOffTheEventThread("the first line of a new notebook", true);
    }

    @Test
    void untilTheCalculatingThreadRunsNoCommandShowsACalculatedValue() throws Exception {
        Path other = notebookFile("autre.calc.json", List.of("b = 5", "b + 1"));
        Lab lab = lab(false, List.of("a = 2", "a + 1", "a + 2"));
        assertPending(lab.view, 1, 2, 3);

        lab.settle();
        assertEquals(List.of("2", "3", "4", ""), results(lab.view));

        lab.edit(1, "a = 10");
        assertPending(lab.view, 1, 2, 3);
        lab.settle();
        assertEquals(List.of("10", "11", "12", ""), results(lab.view));

        lab.edit(2, "a + 100");
        assertTrue(List.of("10", PENDING).contains(rowsOf(lab.view).get(0).result()), "the line above the change is right or waits");
        assertPending(lab.view, 2, 3);
        lab.settle();
        assertEquals(List.of("10", "110", "12", ""), results(lab.view));

        lab.command((p, f) -> p.onUndo(f));
        assertPending(lab.view, 2, 3);
        lab.settle();
        assertEquals(List.of("10", "11", "12", ""), results(lab.view));

        lab.command((p, f) -> p.onRedo(f));
        assertPending(lab.view, 2, 3);
        lab.settle();
        assertEquals(List.of("10", "110", "12", ""), results(lab.view));

        lab.dialogs.willOpen(other);
        lab.command((p, f) -> p.onOpen(f));
        assertEquals(List.of("b = 5", "b + 1"), texts(lab.view), "the texts are shown at once");
        assertPending(lab.view, 1, 2);
        lab.settle();
        assertEquals(List.of("5", "6", ""), results(lab.view));
    }

    // ------------------------------------------------------------------------------------------------ the reproduction of B1

    @Test
    @Tag("robustness")
    void openingTheFileOfB1GivesTheEventThreadBackWithinTheBudget() throws Exception {
        assertEquals(974, HOSTILE_LINE.length(), "the line of the reproduction");
        EvaluatedNotebook one = reference(List.of(HOSTILE_LINE));
        assertTrue(one.result(1).hasValue(), "the line is valid");
        String value = ValueFormatter.format(one.result(1).value(), DisplayMode.AUTO).text();
        Path file = notebookFile("b1.calc.json", Collections.nCopies(HOSTILE_LINES, HOSTILE_LINE));
        assertTrue(Files.size(file) > 4_800_000, "about 5 MB, like the file of the report: " + Files.size(file));

        Lab lab = lab(true, List.of());
        lab.settle();
        double best = Double.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            lab.dialogs.willOpen(file);
            long[] elapsed = new long[1];
            lab.command((p, f) -> {
                long start = System.nanoTime();
                NotebookViewModel next = p.onOpen(f);
                elapsed[0] = System.nanoTime() - start;
                return next;
            });
            best = Math.min(best, elapsed[0] / 1e6);

            List<NotebookRow> rows = rowsOf(lab.view);
            assertEquals(HOSTILE_LINES, rows.size());
            for (NotebookRow row : rows) {
                assertEquals(HOSTILE_LINE, row.text(), "the texts are shown at once");
                assertTrue(row.result().equals(PENDING) || row.result().equals(value), "line " + row.number() + ": " + row.result());
            }
            assertEquals(PENDING, rows.get(HOSTILE_LINES - 1).result(), "three minutes of work cannot be done yet");
            int[] count = progressOf(lab.view).orElseThrow(() -> new AssertionError("no count in: " + lab.view.statusMessage()));
            assertEquals(HOSTILE_LINES, count[1]);
        }
        System.out.printf("[acceptance] event thread back after opening %d hostile lines: best of three %.1f ms%n", HOSTILE_LINES, best);
        TimingBudget.assertWithin("the event thread back after opening, best of three", best, EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);

        lab.awaitView("the count of the status bar moves", view -> progressOf(view).filter(count -> count[0] >= 1).isPresent());
    }

    // ------------------------------------------------------------------------------------------------ the status bar

    @Test
    void theStatusBarCountsTheLinesAndThenSaysWhatItSaysToday() throws Exception {
        List<String> lines = List.of("x = 1", "x + 1", "x + 2", "x + 3", "x + 4", "1 / 0");
        Path file = notebookFile("budget.calc.json", lines);
        Lab lab = lab(true, List.of());
        lab.settle();
        Gate gate = lab.evaluator.gateAt("x + 3");
        lab.dialogs.willOpen(file);
        lab.command((p, f) -> p.onOpen(f));
        assertEquals(6, progressOf(lab.view).orElseThrow(() -> new AssertionError(lab.view.statusMessage()))[1]);

        gate.awaitReached();
        NotebookViewModel during = lab.awaitView("three lines are counted",
                view -> "Calcul en cours : 3 / 6 lignes".equals(view.statusMessage()));
        assertEquals(List.of("1", "2", "3", PENDING, PENDING, PENDING, ""), results(during));

        gate.release();
        lab.settle();
        assertEquals("« budget.calc.json » ouvert (6 lignes). 1 ligne contient une erreur.", lab.view.statusMessage());
        assertEquals(StatusTone.INFO, lab.view.statusTone());
        assertShows(lab.view, new Outcome(lines, DisplayMode.AUTO, lab.view.statusMessage()));

        int previous = -1;
        List<NotebookViewModel> views = lab.viewsSinceTheLastCommand();
        for (NotebookViewModel view : views) {
            Optional<int[]> count = progressOf(view);
            if (count.isPresent()) {
                assertEquals(6, count.get()[1], view.statusMessage());
                assertTrue(count.get()[0] >= previous && count.get()[0] <= 6, "the count goes up: " + view.statusMessage());
                previous = count.get()[0];
            } else {
                assertEquals(lab.view.statusMessage(), view.statusMessage(), "either the count or the final message");
            }
        }
        NotebookViewModel last = views.get(views.size() - 1);
        assertEquals(lab.view.rows(), last.rows(), "the window is told about the end without having to ask");
        assertEquals(lab.view.statusMessage(), last.statusMessage());
    }

    @Test
    void aLineRefusedDuringACalculationStillSaysWhy() throws Exception {
        Lab lab = lab(false, baseLines(111));
        lab.edit(1, "a = 222");
        lab.type("1".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1));
        lab.enter();
        lab.settle();
        assertTrue(lab.view.statusMessage().startsWith("La ligne dépasse"), lab.view.statusMessage());
        assertEquals(StatusTone.ERROR, lab.view.statusTone());
    }

    // ------------------------------------------------------------------------------------------------ a command replaces the calculation

    /** Every command the contract lists, from the same notebook with a calculation blocked on one line. */
    enum NewCommand { VALIDATE, ADD, DELETE, UNDO, REDO, MODE, OPEN, NEW }

    @ParameterizedTest
    @EnumSource(NewCommand.class)
    void aNewCommandReplacesTheCalculationAndItsResultsAreNeverSeen(NewCommand command) throws Exception {
        Path other = notebookFile("autre.calc.json", List.of("b = 5000", "b + 1", "b + 2"));
        Lab lab = lab(true, baseLines(111));
        Gate gate = lab.startACalculationBlockedOnALine(command);

        Outcome outcome = lab.apply(command, other);
        assertEveryViewShowsOnly(lab, outcome);
        gate.release();
        lab.settle();

        assertNotEquals(THE_LINE_AFTER_IT, firstOf(lab.evaluator.evaluatedAfter(gate)), "the old calculation stopped at the end of its line");
        assertEveryViewShowsOnly(lab, outcome);
        assertShows(lab.view, outcome);
        assertEquals(outcome.status(), lab.view.statusMessage());

        Path export = directory().resolve("after.csv");
        lab.dialogs.willExportTo(export);
        lab.command((p, f) -> p.onExport(ExportFormat.CSV, f));
        lab.settle();
        assertArrayEquals(NotebookExporter.csv(reference(outcome.lines()), outcome.mode()), Files.readAllBytes(export),
                "nothing of the old calculation is exported");
    }

    @Test
    void aResultAlreadyOnItsWayWhenACommandArrivesIsThrownAway() throws Exception {
        Lab lab = lab(true, baseLines(111));
        lab.settle();
        Gate first = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        Gate second = lab.evaluator.gateAt(THE_LINE_AFTER_IT);
        lab.edit(1, "a = 333");
        first.awaitReached();

        // While the event thread is busy, the old calculation finishes a line and posts it; the command comes first in the queue.
        lab.commandWhile(() -> {
            first.release();
            second.awaitReached();
        }, "a = 1000");
        Outcome outcome = new Outcome(baseLines(1000), DisplayMode.AUTO, NotebookPresenter.DEFAULT_STATUS);
        assertEveryViewShowsOnly(lab, outcome);

        second.release();
        lab.settle();
        assertEveryViewShowsOnly(lab, outcome);
        assertShows(lab.view, outcome);
    }

    // ------------------------------------------------------------------------------------------------ Escape

    @ParameterizedTest
    @EnumSource(NewCommand.class)
    void escapeWithNothingTypedInterruptsAndTheNextCommandCalculatesAgain(NewCommand command) throws Exception {
        Path other = notebookFile("autre.calc.json", List.of("b = 5000", "b + 1", "b + 2"));
        Lab lab = lab(true, baseLines(111));
        Gate gate = lab.startACalculationBlockedOnALine(command);
        lab.awaitView("four lines are counted", view -> "Calcul en cours : 4 / 8 lignes".equals(view.statusMessage()));

        lab.command((p, f) -> p.onCancelEdit(f));
        assertInterrupted(lab.view, 6, 7, 8);
        assertSaysInterrupted(lab.view);
        gate.release();
        lab.settle();

        assertEquals(List.of(), lab.evaluator.evaluatedAfter(gate), "the calculation stopped at the end of its line");
        List<String> stateAtEscape = texts(lab.view);
        EvaluatedNotebook calculated = reference(stateAtEscape);
        List<NotebookRow> rows = rowsOf(lab.view);
        for (int index = 0; index < 4; index++) {
            assertEquals(expected(calculated.results().get(index), DisplayMode.AUTO), shown(rows.get(index)), "a line calculated keeps its value");
        }
        boolean lineInProgressKept = expected(calculated.results().get(4), DisplayMode.AUTO).equals(shown(rows.get(4)));
        if (!lineInProgressKept) {
            assertInterrupted(lab.view, 5);
        }
        assertInterrupted(lab.view, 6, 7, 8);
        assertSaysInterrupted(lab.view);

        int evaluatedBefore = lab.evaluator.count();
        Outcome outcome = lab.apply(command, other);
        assertEveryViewShowsOnly(lab, outcome);
        lab.settle();
        if (command != NewCommand.NEW) {
            assertTrue(lab.evaluator.count() > evaluatedBefore, "the next command calculates again");
        }
        assertShows(lab.view, outcome);
        assertEquals(outcome.status(), lab.view.statusMessage());
    }

    @Test
    void escapeWhileTypingDropsTheInputAndInterruptsNothing() throws Exception {
        Lab lab = lab(true, baseLines(111));
        lab.settle();
        Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 333");
        gate.awaitReached();

        lab.type("a + 99");
        lab.command((p, f) -> p.onCancelEdit(f));
        assertEquals("a + 1", lab.view.activeDraft(), "Escape keeps its meaning: the input is dropped");
        assertFalse(lab.view.statusMessage().contains("interrompu"), lab.view.statusMessage());
        gate.release();
        lab.settle();

        assertEquals(THE_LINE_AFTER_IT, firstOf(lab.evaluator.evaluatedAfter(gate)), "the calculation goes on");
        assertShows(lab.view, new Outcome(baseLines(333), DisplayMode.AUTO, ""));
        assertEquals("Saisie annulée : la ligne retrouve son texte validé.", lab.view.statusMessage());

        // With the input gone, the next Escape is the one that interrupts.
        Gate again = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 444");
        again.awaitReached();
        lab.type("a + 99");
        lab.command((p, f) -> p.onCancelEdit(f));
        assertFalse(lab.view.statusMessage().contains("interrompu"), lab.view.statusMessage());
        lab.command((p, f) -> p.onCancelEdit(f));
        assertInterrupted(lab.view, 6, 7, 8);
        assertSaysInterrupted(lab.view);
    }

    @Test
    void escapeWithoutACalculationDoesNothing() throws Exception {
        Lab lab = lab(false, baseLines(111));
        lab.settle();
        int evaluated = lab.evaluator.count();
        String status = lab.view.statusMessage();

        lab.command((p, f) -> p.onCancelEdit(f));
        lab.settle();

        assertEquals(status, lab.view.statusMessage());
        assertEquals(evaluated, lab.evaluator.count());
        assertShows(lab.view, new Outcome(baseLines(111), DisplayMode.AUTO, status));
    }

    // ------------------------------------------------------------------------------------------------ the same results

    static Stream<Arguments> notebooks() {
        return Stream.of(
                Arguments.of("a quote", List.of("prix = 12,5", "quantite = 3", "prix * quantite", "ans * 1,2", "# remise", "",
                        "total = ans - 5", "total / 3", "$3 + $8")),
                Arguments.of("errors", List.of("1 / 0", "2 +", "inconnu + 1", "$99", "$1 + 1", "x = 1 / 0", "x + 1", "ans", "sqrt(-1)", "2^100000")),
                Arguments.of("roots and huge numbers", List.of("√2 * √2", "1 / 3", "1 / 3 * 3", "2^4000", "sqrt(2^4000+1)", HOSTILE_LINE, "ans - 1")),
                Arguments.of("five hundred lines with references", NotebookResponsivenessTest.lines(500, true)),
                Arguments.of("costly lines", Collections.nCopies(20, HOSTILE_LINE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("notebooks")
    void onceFinishedEachLineHasExactlyTheResultOfTheSynchronousCalculation(String name, List<String> lines) throws Exception {
        EvaluatedNotebook calculated = reference(lines);
        Lab lab = lab(false, lines);
        lab.settle();

        for (DisplayMode mode : DisplayMode.values()) {
            lab.command((p, f) -> p.onDisplayModeChanged(mode, f));
            lab.settle();
            assertShows(lab.view, new Outcome(lines, mode, ""));
            assertArrayEquals(NotebookExporter.csv(calculated, mode), lab.exported(ExportFormat.CSV, name + "-" + mode + ".csv"),
                    "the certified value and its status, in " + mode);
            Path expectedMarkdown = directory().resolve("expected.md");
            NotebookExporter.writeMarkdown(expectedMarkdown, calculated, null, mode);
            assertArrayEquals(Files.readAllBytes(expectedMarkdown), lab.exported(ExportFormat.MARKDOWN, name + "-" + mode + ".md"), "in " + mode);
        }

        lab.command((p, f) -> p.onDisplayModeChanged(DisplayMode.AUTO, f));
        lab.settle();
        List<String> edited = new ArrayList<>(lines);
        edited.set(0, "7 * 6");
        lab.edit(1, "7 * 6");
        lab.settle();
        assertShows(lab.view, new Outcome(edited, DisplayMode.AUTO, ""));
        lab.command((p, f) -> p.onUndo(f));
        lab.settle();
        assertShows(lab.view, new Outcome(lines, DisplayMode.AUTO, ""));
        lab.command((p, f) -> p.onRedo(f));
        lab.settle();
        assertShows(lab.view, new Outcome(edited, DisplayMode.AUTO, ""));
    }

    /**
     * The tests that read a result right after a command learn to wait for the end of the calculation; no assertion and no test
     * goes away. The numbers are those of the files before this task: only growing is allowed.
     */
    @Test
    void noExistingAssertionOrTestIsRemoved() throws IOException {
        Map<String, int[]> before = new LinkedHashMap<>();
        before.put("calc/MainStartupTest.java", new int[] {17, 2});
        before.put("calc/document/DocumentServiceTest.java", new int[] {154, 28});
        before.put("calc/ui/notebook/ExactEngineAcceptanceTest.java", new int[] {34, 6});
        before.put("calc/ui/notebook/MigrationAcceptanceTest.java", new int[] {28, 5});
        before.put("calc/ui/notebook/NotebookFilesTest.java", new int[] {180, 36});
        before.put("calc/ui/notebook/NotebookFrameFilesTest.java", new int[] {42, 9});
        before.put("calc/ui/notebook/NotebookFrameResponsivenessTest.java", new int[] {4, 2});
        before.put("calc/ui/notebook/NotebookFrameTest.java", new int[] {96, 14});
        before.put("calc/ui/notebook/NotebookHelpTest.java", new int[] {15, 2});
        before.put("calc/ui/notebook/NotebookLosslessChainProperties.java", new int[] {2, 1});
        before.put("calc/ui/notebook/NotebookParityTest.java", new int[] {50, 16});
        before.put("calc/ui/notebook/NotebookPresenterStatefulProperties.java", new int[] {9, 1});
        before.put("calc/ui/notebook/NotebookPresenterTest.java", new int[] {176, 36});
        before.put("calc/ui/notebook/NotebookResponsivenessTest.java", new int[] {5, 2});
        before.put("calc/ui/notebook/NotebookScenariosAcceptanceTest.java", new int[] {91, 8});
        before.put("calc/ui/notebook/NotebookShortcutConflictTest.java", new int[] {4, 1});
        before.put("calc/ui/notebook/RobustnessAcceptanceTest.java", new int[] {58, 9});

        Pattern assertion = Pattern.compile("\\bassert[A-Z][A-Za-z]*\\(");
        Pattern test = Pattern.compile("^\\s*@(Test|ParameterizedTest|Property|Example)\\b", Pattern.MULTILINE);
        for (Map.Entry<String, int[]> file : before.entrySet()) {
            String source = Files.readString(Path.of("src", "test", "java").resolve(file.getKey()), StandardCharsets.UTF_8);
            long assertions = assertion.matcher(source).results().count();
            long tests = test.matcher(source).results().count();
            assertTrue(assertions >= file.getValue()[0], file.getKey() + " had " + file.getValue()[0] + " assertions, it has " + assertions);
            assertTrue(tests >= file.getValue()[1], file.getKey() + " had " + file.getValue()[1] + " tests, it has " + tests);
        }
    }

    // ------------------------------------------------------------------------------------------------ files during a calculation

    /** Everything that writes a file. */
    enum Written { SAVE, SAVE_AS, AUTOSAVE, MARKDOWN, CSV }

    @ParameterizedTest
    @EnumSource(Written.class)
    void aFileWrittenDuringACalculationIsTheFileWrittenAfterIt(Written what) throws Exception {
        Map<String, String> during = writtenBy(what, true);
        Map<String, String> after = writtenBy(what, false);

        assertEquals(after, during, "byte for byte, file by file");
    }

    // Both runs use the same file names in two folders. The event thread answering at all, with a line blocked, proves it is not
    // waiting for the calculation.
    private Map<String, String> writtenBy(Written what, boolean duringTheCalculation) throws Exception {
        Path folder = directory();
        Path data = Files.createDirectories(folder.resolve("data"));
        Path documents = Files.createDirectories(folder.resolve("documents"));
        Path file = documents.resolve("carnet.calc.json");
        try (Lab lab = new Lab(data, baseLines(111), true, true)) {
            lab.settle();
            if (what == Written.SAVE) {
                Files.write(file, NotebookCodec.encode(Notebook.of(baseLines(111)), DisplayMode.AUTO).bytes());
                lab.dialogs.willOpen(file);
                lab.command((p, f) -> p.onOpen(f));
                lab.settle();
            }
            Gate gate = duringTheCalculation ? lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS) : null;
            lab.edit(1, "a = 333");
            if (duringTheCalculation) {
                gate.awaitReached();
            } else {
                lab.settle();
            }
            String status = switch (what) {
                case SAVE -> {
                    lab.command((p, f) -> p.onSave(f));
                    yield "« carnet.calc.json » enregistré.";
                }
                case SAVE_AS -> {
                    lab.dialogs.willSaveTo(file);
                    lab.command((p, f) -> p.onSaveAs(f));
                    yield "Carnet enregistré dans " + file.toAbsolutePath().normalize() + ".";
                }
                case AUTOSAVE -> NotebookPresenter.DEFAULT_STATUS;
                case MARKDOWN -> {
                    Path target = documents.resolve("carnet.md");
                    lab.dialogs.willExportTo(target);
                    lab.command((p, f) -> p.onExport(ExportFormat.MARKDOWN, f));
                    yield "Exporté vers " + target.toAbsolutePath().normalize();
                }
                case CSV -> {
                    Path target = documents.resolve("carnet.csv");
                    lab.dialogs.willExportTo(target);
                    lab.command((p, f) -> p.onExport(ExportFormat.CSV, f));
                    yield "Exporté vers " + target.toAbsolutePath().normalize();
                }
            };
            if (duringTheCalculation) {
                gate.release();
            }
            lab.settle();
            lab.dialogs.assertAllUsed();
            assertEquals(status, lab.view.statusMessage(), "the usual message comes back at the end");
            assertShows(lab.view, new Outcome(baseLines(333), DisplayMode.AUTO, status));

            Map<String, String> content = new TreeMap<>();
            AcceptanceSession.contentOf(data).forEach((name, hash) -> content.put("data/" + name, hash));
            AcceptanceSession.contentOf(documents).forEach((name, hash) -> content.put("documents/" + name, hash));
            if (what == Written.CSV) {
                assertArrayEquals(NotebookExporter.csv(reference(baseLines(333)), DisplayMode.AUTO),
                        Files.readAllBytes(documents.resolve("carnet.csv")), "and it holds the results of the synchronous calculation");
            }
            return content;
        }
    }

    @Test
    void anExportAskedBeforeACommandHoldsOneWholeStateOfTheNotebook() throws Exception {
        Lab lab = lab(true, baseLines(111));
        lab.settle();
        Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 333");
        gate.awaitReached();

        Path export = directory().resolve("carnet.csv");
        lab.dialogs.willExportTo(export);
        lab.command((p, f) -> p.onExport(ExportFormat.CSV, f));
        lab.edit(1, "a = 1000");
        gate.release();
        lab.settle();

        assertTrue(Files.exists(export), "the export the user asked for is not lost");
        byte[] written = Files.readAllBytes(export);
        assertTrue(Arrays.equals(NotebookExporter.csv(reference(baseLines(333)), DisplayMode.AUTO), written)
                        || Arrays.equals(NotebookExporter.csv(reference(baseLines(1000)), DisplayMode.AUTO), written),
                "the texts and the results of a same state, never a mix: " + new String(written, StandardCharsets.UTF_8));
    }

    @Test
    void anExportNeverWritesAnInterruptedNotebook() throws Exception {
        Lab lab = lab(true, baseLines(111));
        lab.settle();
        Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 333");
        gate.awaitReached();

        Path export = directory().resolve("carnet.csv");
        lab.dialogs.willExportTo(export);
        lab.command((p, f) -> p.onExport(ExportFormat.CSV, f));
        lab.command((p, f) -> p.onCancelEdit(f));
        gate.release();
        lab.settle();

        assertTrue(!Files.exists(export)
                        || Arrays.equals(NotebookExporter.csv(reference(baseLines(333)), DisplayMode.AUTO), Files.readAllBytes(export)),
                "complete or not written at all");
    }

    @Test
    void anExportAfterAnInterruptionCalculatesWhatIsMissingFirst() throws Exception {
        Lab lab = lab(true, baseLines(111));
        lab.settle();
        Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 333");
        gate.awaitReached();
        lab.command((p, f) -> p.onCancelEdit(f));
        gate.release();
        lab.settle();

        assertArrayEquals(NotebookExporter.csv(reference(baseLines(333)), DisplayMode.AUTO), lab.exported(ExportFormat.CSV, "carnet.csv"));
        assertShows(lab.view, new Outcome(baseLines(333), DisplayMode.AUTO, ""));
    }

    // ------------------------------------------------------------------------------------------------ closing during a calculation

    @Test
    void closingDuringACalculationCancelsItAndKeepsTheNotebook() throws Exception {
        Path data = directory();
        try (Lab lab = new Lab(data, baseLines(111), true, true)) {
            lab.settle();
            Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
            lab.edit(1, "a = 333");
            gate.awaitReached();

            lab.type("a + 50");
            lab.command((p, f) -> p.onQuit(f));
            assertTrue(lab.view.closeWindow(), "the window may go");
            gate.release();
            lab.settle();
            assertEquals(List.of(), lab.evaluator.evaluatedAfter(gate), "the calculation stopped at the end of its line");
        }

        List<String> expected = new ArrayList<>(baseLines(333));
        expected.set(1, "a + 50");
        try (NotebookStore store = NotebookStore.open(data)) {
            assertEquals(expected, store.load().notebook().texts(), "the notebook is kept, with the line being typed");
        }
    }

    @Test
    void aCloseTheUserCancelsLeavesTheCalculationRunning() throws Exception {
        Path file = notebookFile("carnet.calc.json", baseLines(111));
        Lab lab = lab(true, List.of());
        lab.settle();
        lab.dialogs.willOpen(file);
        lab.command((p, f) -> p.onOpen(f));
        lab.settle();
        Gate gate = lab.evaluator.gateAt(THE_LINE_THAT_BLOCKS);
        lab.edit(1, "a = 333");
        gate.awaitReached();

        lab.dialogs.willAnswer(Unsaved.CANCEL);
        lab.command((p, f) -> p.onQuit(f));
        assertFalse(lab.view.closeWindow());
        gate.release();
        lab.settle();

        assertEquals(THE_LINE_AFTER_IT, firstOf(lab.evaluator.evaluatedAfter(gate)));
        assertShows(lab.view, new Outcome(baseLines(333), DisplayMode.AUTO, ""));
    }

    // ------------------------------------------------------------------------------------------------ the real window

    @Test
    @Tag("ui")
    void theWindowShowsTheCalculationAsItGoesAndKeepsWhatIsBeingTyped() throws Exception {
        requireDisplay();
        Lab lab = new Lab(directory(), List.of("a = 2", "a + 1"), false, false);
        NotebookFrame frame = onEdt(() -> new NotebookFrame(lab.presenter));
        try {
            assertEquals(List.of(PENDING, PENDING, ""), onEdt(() -> resultLabels(frame)), "the column of the results says it");
            String counting = onEdt(() -> statusLabel(frame).getText());
            assertTrue(PROGRESS.matcher(counting).matches(), counting);

            lab.settle();
            assertEquals(List.of("2", "3", ""), onEdt(() -> resultLabels(frame)), "the window is told about the results");
            assertEquals(NotebookPresenter.DEFAULT_STATUS, onEdt(() -> statusLabel(frame).getText()));

            onEdt(() -> {
                frame.row(0).field.setText("a = 5");
                return null;
            });
            press(frame, 0, KeyEvent.VK_ENTER);
            assertEquals(1, onEdt(() -> frame.shown().activeIndex()));
            assertEquals(List.of(PENDING, PENDING, ""), onEdt(() -> resultLabels(frame)));

            onEdt(() -> {
                frame.row(1).field.setText("a + 9");
                return null;
            });
            lab.settle();
            assertEquals(List.of("5", "6", ""), onEdt(() -> resultLabels(frame)));
            assertEquals("a + 9", onEdt(() -> frame.row(1).field.getText()), "a result that arrives does not touch the line being typed");

            press(frame, 1, KeyEvent.VK_ESCAPE);
            assertEquals("a + 1", onEdt(() -> frame.row(1).field.getText()), "Escape drops the input");

            onEdt(() -> {
                frame.row(1).field.setText("a + 2");
                return null;
            });
            press(frame, 1, KeyEvent.VK_ENTER);
            press(frame, 2, KeyEvent.VK_ESCAPE);
            assertTrue(onEdt(() -> frame.row(1).errorLabel.getText()).contains(INTERRUPTED), "the key interrupts the calculation");
            assertTrue(onEdt(() -> normal(statusLabel(frame).getText())).contains("interrompu"));
            lab.settle();
            assertTrue(onEdt(() -> frame.row(1).errorLabel.getText()).contains(INTERRUPTED), "and nothing comes back afterwards");

            onEdt(() -> {
                frame.row(2).field.setText("7");
                return null;
            });
            press(frame, 2, KeyEvent.VK_ENTER);
            lab.settle();
            assertEquals(List.of("5", "7", "7", ""), onEdt(() -> resultLabels(frame)));
        } finally {
            onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    /**
     * The real application on the file of B1 (data in a temporary folder), in its own process: one background thread named
     * notebook-recalc evaluates, the event thread answers, closing keeps the notebook and the process is gone within two seconds.
     */
    @Test
    @Tag("ui")
    void theRealApplicationCalculatesOnOneBackgroundThreadAndClosesWithinTwoSeconds() throws Exception {
        requireDisplay();
        Path home = Files.createDirectories(directory().resolve("home"));
        ProcessBuilder builder = new ProcessBuilder(javaCommand(), "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                RealApplication.class.getName()).redirectErrorStream(true);
        builder.environment().put("APPDATA", home.resolve("AppData").toString());
        builder.environment().put("XDG_DATA_HOME", home.resolve("xdg").toString());
        Process process = builder.start();
        BlockingQueue<String> output = new LinkedBlockingQueue<>();
        List<String> seen = new ArrayList<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                lines.lines().forEach(output::add);
            } catch (IOException | RuntimeException closed) {
                output.add("READ FAILED: " + closed);
            }
        }, "real-application-output");
        reader.setDaemon(true);
        reader.start();
        try {
            Map<String, String> facts = new TreeMap<>();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!facts.containsKey("CLOSING")) {
                String line = output.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (line == null) {
                    fail("the application never gave the event thread back to close it. Output: " + seen);
                }
                seen.add(line);
                int equal = line.indexOf('=');
                facts.put(equal < 0 ? line : line.substring(0, equal), equal < 0 ? "" : line.substring(equal + 1));
            }

            long closeLimit = TimingBudget.scaled(CLOSE_BUDGET_SECONDS);
            boolean gone = process.waitFor(closeLimit, TimeUnit.SECONDS);
            assertTrue(gone, () -> TimingBudget.message("the process is still there after the window closed. Output: " + seen,
                    closeLimit, CLOSE_BUDGET_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue(), () -> "exit code. Output: " + seen);
            assertEquals("1", facts.get("RECALC_THREADS"), () -> "one thread named notebook-recalc. Output: " + seen);
            assertEquals("true", facts.get("EVALUATING"), () -> "it is the one evaluating. Output: " + seen);
            TimingBudget.assertWithin("the event thread answers. Output: " + seen, Long.parseLong(facts.get("EDT_MEDIAN_MILLIS")), EDT_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            assertTrue(PROGRESS.matcher(facts.get("STATUS")).matches(), () -> "the status bar counts. Output: " + seen);
            assertEquals(PENDING, facts.get("LAST_RESULT"), () -> "the last line waits. Output: " + seen);
            try (NotebookStore store = NotebookStore.open(Path.of(facts.get("DATA")))) {
                assertEquals(Collections.nCopies(HOSTILE_LINES, HOSTILE_LINE), store.load().notebook().texts(), "the notebook is kept");
            }
        } finally {
            process.destroyForcibly();
        }
    }

    /** What runs in the process of the test above: {@link Main} itself, and a look at it from outside. */
    static final class RealApplication {
        private RealApplication() {
        }

        public static void main(String[] args) throws Exception {
            PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            out.println("DATA=" + DataDirectory.resolve(DataDirectory.Environment.system()));
            try (NotebookStore store = NotebookStore.openForCurrentUser()) {
                store.saveCurrent(Notebook.of(Collections.nCopies(HOSTILE_LINES, HOSTILE_LINE)), DisplayMode.AUTO);
            }
            Main.main(new String[0]);

            NotebookFrame frame = null;
            while (frame == null) {
                NotebookFrame[] found = new NotebookFrame[1];
                EventQueue.invokeAndWait(() -> {
                    for (Frame each : Frame.getFrames()) {
                        if (each instanceof NotebookFrame window && window.isVisible()) {
                            found[0] = window;
                        }
                    }
                });
                frame = found[0];
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            int threads = 0;
            boolean evaluating = false;
            while (!evaluating && System.nanoTime() < deadline) {
                threads = 0;
                for (Map.Entry<Thread, StackTraceElement[]> thread : Thread.getAllStackTraces().entrySet()) {
                    // Another thread whose name only starts the same way (a pool of several) counts too: there must be one.
                    if (thread.getKey().getName().startsWith("notebook-recalc")) {
                        threads++;
                        evaluating |= Arrays.stream(thread.getValue()).anyMatch(call -> call.getClassName().startsWith("calc.expression.Evaluator"));
                    }
                }
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            out.println("RECALC_THREADS=" + threads);
            out.println("EVALUATING=" + evaluating);

            long[] millis = new long[9];
            for (int run = 0; run < millis.length; run++) {
                long start = System.nanoTime();
                EventQueue.invokeAndWait(() -> { });
                millis[run] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
            }
            Arrays.sort(millis);
            out.println("EDT_MEDIAN_MILLIS=" + millis[millis.length / 2]);

            NotebookFrame window = frame;
            EventQueue.invokeAndWait(() -> {
                out.println("STATUS=" + statusLabel(window).getText());
                out.println("LAST_RESULT=" + window.row(HOSTILE_LINES - 1).resultLabel.getText());
            });
            out.println("CLOSING");
            EventQueue.invokeLater(() -> window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING)));
        }
    }

    // ------------------------------------------------------------------------------------------------ documentation

    private static final Path DOCS = Path.of("docs");

    @Test
    void anAdrExplainsTheChoiceAndTheOptionSetAside() throws IOException {
        Path adr = DOCS.resolve("adr").resolve("0014-calcul-hors-interface.md");
        assertTrue(Files.isRegularFile(adr), adr + " does not exist");
        String text = Files.readString(adr, StandardCharsets.UTF_8);

        assertTrue(text.startsWith("# 0014 - "), "the title of docs/adr/0000-modele.md");
        List<String> headings = List.of("## Status (Statut)", "## Context (Contexte)", "## Decision (Décision)",
                "## Alternatives considered (Alternatives considérées)", "## Consequences (Conséquences)");
        int previous = -1;
        for (String heading : headings) {
            int position = text.indexOf(heading);
            assertTrue(position > previous, "missing or out of order: " + heading);
            previous = position;
        }
        String alternatives = normal(text.substring(text.indexOf(headings.get(3)), text.indexOf(headings.get(4))));
        assertTrue(alternatives.contains("budget global"), "the option set aside");
        assertTrue(alternatives.contains("machine"), "and why: its result depends on the machine");
    }

    @Test
    void theAdrIndexReferencesIt() throws IOException {
        List<String> rows = Files.readAllLines(DOCS.resolve("adr").resolve("README.md"), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| 0014 |")).toList();
        assertEquals(1, rows.size(), "one row for the ADR 0014 in docs/adr/README.md");
    }

    @Test
    void theUserGuideExplainsTheCalculationAndEscape() throws IOException {
        String guide = Files.readString(DOCS.resolve("GUIDE-UTILISATEUR.md"), StandardCharsets.UTF_8);

        assertTrue(guide.contains(PENDING), "the guide mentions « " + PENDING + " »");
        assertTrue(guide.lines().anyMatch(line -> line.contains("Échap") && normal(line).contains("interromp")), "and Échap to interrupt");
        assertTrue(guide.lines().anyMatch(line -> line.contains("Échap") && normal(line).contains("saisie")), "Échap keeps its first meaning");
    }

    @Test
    void theReportMarksB1AsFixedWithTheTestThatProvesIt() throws IOException {
        List<String> report = Files.readAllLines(DOCS.resolve("RECETTE.md"), StandardCharsets.UTF_8);

        List<String> defect = report.stream().filter(line -> line.startsWith("**B1.")).toList();
        assertEquals(1, defect.size(), "the paragraph of the defect B1 stays");
        assertTrue(normal(defect.get(0)).contains("corrig"), defect.get(0));
        assertTrue(defect.get(0).contains("BackgroundRecalculationAcceptanceTest"), defect.get(0));
        Pattern b1 = Pattern.compile("\\bB1\\b");
        for (String line : report) {
            if (b1.matcher(line).find()) {
                assertFalse(normal(line).contains("non corrig"), "still described as not fixed: " + line);
                assertFalse(line.strip().startsWith("- [ ]"), "still waiting for a decision: " + line);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ what is expected

    private static List<String> baseLines(int a) {
        return List.of("a = " + a, "a + 1", "a + 2", "a + 3", THE_LINE_THAT_BLOCKS, THE_LINE_AFTER_IT, "a + 6", "a + 7");
    }

    /** A state of the notebook: what it holds, in which mode, and the sentence of the status bar once calculated. */
    private record Outcome(List<String> lines, DisplayMode mode, String status) {
    }

    /** The old synchronous calculation, still in the model: the independent reference. */
    private static EvaluatedNotebook reference(List<String> lines) {
        try {
            return NotebookEvaluator.evaluate(Notebook.of(lines));
        } catch (Exception exception) {
            throw new AssertionError("the notebook of the test is refused: " + lines, exception);
        }
    }

    private static List<Object> expected(LineResult line, DisplayMode mode) {
        String name = line.assignedName().orElse("");
        String suffix = name.isEmpty() ? "" : ", définit la variable " + name;
        if (line.isError()) {
            Span span = line.error().span();
            int end = Math.min(span.end(), line.text().length());
            boolean marked = span.length() > 0 && span.start() < end;
            return List.of(line.text(), Status.ERROR, "", "", line.error().message(), marked ? span.start() : -1, marked ? end : -1, name,
                    "Erreur : " + line.error().message() + suffix);
        }
        if (line.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(line.value(), mode);
            return List.of(line.text(), formatted.exact() ? Status.EXACT : Status.APPROXIMATE, formatted.text(),
                    ResultDescription.describe(formatted), "", -1, -1, name,
                    "Résultat : " + formatted.text() + (formatted.exact() ? ", exact" : ", approché") + suffix);
        }
        boolean comment = line.kind() == ParsedLine.Kind.COMMENT;
        return List.of(line.text(), comment ? Status.COMMENT : Status.EMPTY, "", "", "", -1, -1, "", comment ? "Commentaire" : "Ligne vide");
    }

    private static List<Object> shown(NotebookRow row) {
        return List.of(row.text(), row.status(), row.result(), row.detail(), row.errorMessage(), row.errorStart(), row.errorEnd(),
                row.definedName(), row.description());
    }

    private static void assertShows(NotebookViewModel view, Outcome outcome) {
        List<LineResult> results = reference(outcome.lines()).results();
        List<NotebookRow> rows = rowsOf(view);
        assertEquals(results.size(), rows.size());
        for (int index = 0; index < rows.size(); index++) {
            assertEquals(expected(results.get(index), outcome.mode()), shown(rows.get(index)), "line " + (index + 1));
        }
        assertBlankLastRow(view);
    }

    // Since the last command, whatever the window was told: the texts of the new state, and for each line its value in that state
    // or « Calcul en cours… ». An old value can come by no other way.
    private static void assertEveryViewShowsOnly(Lab lab, Outcome outcome) {
        List<LineResult> results = reference(outcome.lines()).results();
        for (NotebookViewModel view : lab.viewsSinceTheLastCommand()) {
            List<NotebookRow> rows = rowsOf(view);
            assertEquals(results.size(), rows.size());
            for (int index = 0; index < rows.size(); index++) {
                NotebookRow row = rows.get(index);
                assertEquals(outcome.lines().get(index), row.text());
                if (!row.result().equals(PENDING)) {
                    assertEquals(expected(results.get(index), outcome.mode()), shown(row), "line " + (index + 1) + " shows an obsolete result");
                }
            }
            assertBlankLastRow(view);
        }
    }

    private static void assertPending(NotebookViewModel view, int... lines) {
        for (int number : lines) {
            NotebookRow row = view.rows().get(number - 1);
            assertEquals(PENDING, row.result(), "line " + number);
            assertFalse(row.hasValue(), "line " + number + " has no value yet");
            assertEquals("", row.errorMessage(), "line " + number);
            assertTrue(row.description().contains("Calcul en cours"), "what a screen reader says of line " + number + ": " + row.description());
        }
        assertTrue(PROGRESS.matcher(view.statusMessage()).matches(), view.statusMessage());
        assertBlankLastRow(view);
    }

    private static void assertInterrupted(NotebookViewModel view, int... lines) {
        for (int number : lines) {
            NotebookRow row = view.rows().get(number - 1);
            assertEquals(Status.ERROR, row.status(), "line " + number + " is shown with the tone of an error");
            assertEquals(INTERRUPTED, row.errorMessage(), "line " + number);
            assertEquals("", row.result(), "line " + number);
            assertFalse(row.hasValue(), "line " + number);
        }
    }

    private static void assertSaysInterrupted(NotebookViewModel view) {
        assertTrue(normal(view.statusMessage()).contains("interrompu"), "the status bar says it: " + view.statusMessage());
        assertFalse(PROGRESS.matcher(view.statusMessage()).matches(), view.statusMessage());
    }

    private static void assertBlankLastRow(NotebookViewModel view) {
        NotebookRow last = view.rows().get(view.rows().size() - 1);
        assertEquals("", last.result(), "the new line at the end is not a line of the notebook");
        assertEquals(Status.EMPTY, last.status());
    }

    private static List<NotebookRow> rowsOf(NotebookViewModel view) {
        return view.rows().subList(0, view.rows().size() - 1);
    }

    private static List<String> results(NotebookViewModel view) {
        return view.rows().stream().map(NotebookRow::result).toList();
    }

    private static List<String> texts(NotebookViewModel view) {
        return rowsOf(view).stream().map(NotebookRow::text).toList();
    }

    private static Optional<int[]> progressOf(NotebookViewModel view) {
        Matcher matcher = PROGRESS.matcher(view.statusMessage());
        return matcher.matches() ? Optional.of(new int[] {Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2))}) : Optional.empty();
    }

    private static String firstOf(List<String> list) {
        return list.isEmpty() ? "" : list.get(0);
    }

    private static String normal(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------------ files, folders, windows

    private Path directory() throws IOException {
        return Files.createDirectories(root.resolve("folder-" + nextDirectory++));
    }

    private Path notebookFile(String name, List<String> lines) throws Exception {
        Path file = directory().resolve(name);
        Files.write(file, NotebookCodec.encode(Notebook.of(lines), DisplayMode.AUTO).bytes());
        return file;
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java")
                .toString();
    }

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    private static List<String> resultLabels(NotebookFrame frame) {
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < frame.rowCount(); index++) {
            labels.add(frame.row(index).resultLabel.getText());
        }
        return labels;
    }

    private static JLabel statusLabel(Container container) {
        JLabel found = statusLabelIn(container);
        if (found == null) {
            throw new AssertionError("no status label");
        }
        return found;
    }

    private static JLabel statusLabelIn(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JLabel label && "statusLabel".equals(label.getName())) {
                return label;
            }
            if (component instanceof Container inner) {
                JLabel found = statusLabelIn(inner);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // Runs the action the field of the row binds to the key, as Swing does when the key is pressed in it.
    private static void press(NotebookFrame frame, int row, int keyCode) {
        onEdt(() -> {
            JTextField field = frame.row(row).field;
            KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
            Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            if (key == null) {
                key = field.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);
            }
            assertTrue(key != null, "nothing is bound to " + stroke);
            Action action = field.getActionMap().get(key);
            assertTrue(action != null, "no action " + key);
            action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key.toString()));
            return null;
        });
    }

    /** Runs {@code work} on the event thread; a thread that does not answer fails the test instead of hanging it. */
    private static <T> T onEdt(Callable<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable thrown) {
                result.completeExceptionally(thrown);
            }
        });
        try {
            return result.get(EDT_PATIENCE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            throw new AssertionError("the event thread did not answer within " + EDT_PATIENCE_SECONDS + " s: it waits for the calculation");
        } catch (ExecutionException failed) {
            throw rethrown(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static RuntimeException rethrown(Throwable thrown) {
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown instanceof RuntimeException runtime) {
            throw runtime;
        }
        return new IllegalStateException(thrown);
    }

    // ------------------------------------------------------------------------------------------------ the seams of the presenter

    /** The two entry points this task adds to the presenter; see the class comment. */
    private static final class Seam {
        private static final String OPEN = "static NotebookPresenter open(NotebookStore, DisplayModeStore, NotebookDialogs, Executor, BiFunction<String, Supplier<LineResult>, LineResult>)";
        private static final String LISTEN = "void setViewListener(Consumer<NotebookViewModel>)";

        private Seam() {
        }

        static NotebookPresenter open(NotebookStore store, DisplayModeStore modeStore, NotebookDialogs dialogs, Executor executor,
                                      BiFunction<String, Supplier<LineResult>, LineResult> evaluator) {
            try {
                Method open = NotebookPresenter.class.getDeclaredMethod("open", NotebookStore.class, DisplayModeStore.class, NotebookDialogs.class,
                        Executor.class, BiFunction.class);
                return (NotebookPresenter) open.invoke(null, store, modeStore, dialogs, executor, evaluator);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("The calculation off the event thread does not exist yet: no " + OPEN);
            } catch (IllegalAccessException refused) {
                throw new AssertionError(OPEN + " must be reachable from its package", refused);
            } catch (InvocationTargetException thrown) {
                throw rethrown(thrown.getCause());
            }
        }

        static void listen(NotebookPresenter presenter, Consumer<NotebookViewModel> listener) {
            try {
                Method listen = NotebookPresenter.class.getDeclaredMethod("setViewListener", Consumer.class);
                listen.invoke(presenter, listener);
            } catch (NoSuchMethodException missing) {
                throw new AssertionError("The presenter cannot tell the window about a calculation yet: no " + LISTEN);
            } catch (IllegalAccessException refused) {
                throw new AssertionError(LISTEN + " must be reachable from its package", refused);
            } catch (InvocationTargetException thrown) {
                throw rethrown(thrown.getCause());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the piloted executor

    /**
     * Holds the tasks until the test runs them on its own thread, or runs them in order on one background thread (which lets a
     * gate block a line while the event thread goes on).
     */
    private static final class PilotedExecutor implements Executor {
        private final Deque<Runnable> tasks = new ArrayDeque<>();
        private final List<Throwable> failures = new ArrayList<>();
        private final Thread worker;
        private boolean running;
        private boolean stopped;

        PilotedExecutor(boolean withWorker) {
            worker = withWorker ? new Thread(this::work, "test-recalc") : null;
            if (worker != null) {
                worker.setDaemon(true);
                worker.start();
            }
        }

        @Override
        public synchronized void execute(Runnable task) {
            tasks.add(task);
            notifyAll();
        }

        private void work() {
            while (true) {
                Runnable next;
                synchronized (this) {
                    while (tasks.isEmpty() && !stopped) {
                        waitQuietly();
                    }
                    if (stopped) {
                        return;
                    }
                    next = tasks.poll();
                    running = true;
                }
                runTask(next);
                synchronized (this) {
                    running = false;
                    notifyAll();
                }
            }
        }

        private void waitQuietly() {
            try {
                wait();
            } catch (InterruptedException interrupted) {
                stopped = true;
            }
        }

        private void runTask(Runnable task) {
            try {
                task.run();
            } catch (Throwable thrown) {
                failed(thrown);
            }
        }

        synchronized void failed(Throwable thrown) {
            failures.add(thrown);
        }

        /** Until no task is left and the event thread has taken in all that the tasks posted to it. */
        void settle() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(QUIET_PATIENCE_SECONDS);
            do {
                if (worker == null) {
                    Runnable next;
                    while ((next = poll()) != null) {
                        runTask(next);
                    }
                } else {
                    awaitTheWorker(deadline);
                }
                onEdt(() -> null);
                onEdt(() -> null);
            } while (!isQuiet());
            synchronized (this) {
                if (!failures.isEmpty()) {
                    throw new AssertionError("the calculating thread failed: " + failures.get(0), failures.get(0));
                }
            }
        }

        private synchronized Runnable poll() {
            return tasks.poll();
        }

        private synchronized boolean isQuiet() {
            return tasks.isEmpty() && !running;
        }

        private synchronized void awaitTheWorker(long deadline) {
            while (!tasks.isEmpty() || running) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("the calculation does not end");
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(this, left);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }

        synchronized void stop() {
            stopped = true;
            tasks.clear();
            notifyAll();
        }

        void join() throws InterruptedException {
            if (worker != null) {
                worker.join(TimeUnit.SECONDS.toMillis(GATE_PATIENCE_SECONDS));
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ the instrumented evaluator

    /** Stops the calculating thread on one line, until the test lets it go. */
    private static final class Gate {
        private final CountDownLatch reached = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);
        private volatile int evaluatedUpToHere;

        void awaitReached() {
            try {
                assertTrue(reached.await(GATE_PATIENCE_SECONDS, TimeUnit.SECONDS), "the calculation never reached the blocked line");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }

        void release() {
            released.countDown();
        }

        void block(int evaluated) {
            evaluatedUpToHere = evaluated;
            reached.countDown();
            try {
                if (!released.await(GATE_PATIENCE_SECONDS, TimeUnit.SECONDS)) {
                    throw new AssertionError("nobody released the blocked line");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("the blocked line was interrupted: an operation in progress must not be", interrupted);
            }
        }
    }

    /** Records the thread and the text of every line evaluated, then evaluates it for real, on that same thread. */
    private static final class InstrumentedEvaluator implements BiFunction<String, Supplier<LineResult>, LineResult> {
        private final List<String> evaluated = Collections.synchronizedList(new ArrayList<>());
        private final List<String> onTheEventThread = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Gate> gates = new ConcurrentHashMap<>();
        private volatile boolean closing;

        Gate gateAt(String text) {
            Gate gate = new Gate();
            gates.put(text, gate);
            return gate;
        }

        @Override
        public LineResult apply(String text, Supplier<LineResult> realEvaluation) {
            if (closing) {
                throw new IllegalStateException("the test is over");
            }
            if (SwingUtilities.isEventDispatchThread()) {
                onTheEventThread.add(text);
            }
            evaluated.add(text);
            Gate gate = gates.remove(text);
            if (gate != null) {
                gate.block(evaluated.size());
            }
            return realEvaluation.get();
        }

        int count() {
            return evaluated.size();
        }

        /** What was evaluated after the line of {@code gate}, in order. */
        List<String> evaluatedAfter(Gate gate) {
            synchronized (evaluated) {
                return List.copyOf(evaluated.subList(gate.evaluatedUpToHere, evaluated.size()));
            }
        }

        void releaseEverything() {
            closing = true;
            gates.values().forEach(Gate::release);
        }
    }

    // ------------------------------------------------------------------------------------------------ one start of the application

    private Lab lab(boolean withWorker, List<String> lines) throws Exception {
        return new Lab(directory(), lines, withWorker, true);
    }

    /** The real store, document service and presenter on a temporary folder, driven on the event thread like the window does. */
    private final class Lab implements AutoCloseable {
        final NotebookStore store;
        final FakeDialogs dialogs = new FakeDialogs();
        final PilotedExecutor executor;
        final InstrumentedEvaluator evaluator = new InstrumentedEvaluator();
        private final List<NotebookViewModel> views = new ArrayList<>();
        NotebookPresenter presenter;
        // The state after the last command or settle, and the text of the active field, as the window keeps it.
        NotebookViewModel view;
        private String field = "";
        private int mark;
        private boolean closed;

        Lab(Path data, List<String> lines, boolean withWorker, boolean listens) throws Exception {
            store = NotebookStore.open(data);
            executor = new PilotedExecutor(withWorker);
            labs.add(this);
            if (!lines.isEmpty()) {
                store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
            }
            onEdt(() -> {
                presenter = Seam.open(store, DisplayModeStore.noop(), dialogs, executor, evaluator);
                if (listens) {
                    Seam.listen(presenter, this::told);
                    NotebookViewModel first = presenter.initialView();
                    told(first);
                    view = first;
                    field = first.activeDraft();
                }
                return null;
            });
        }

        private void told(NotebookViewModel next) {
            if (!SwingUtilities.isEventDispatchThread()) {
                executor.failed(new AssertionError("a state reached the window outside the event thread"));
            }
            synchronized (views) {
                views.add(next);
                views.notifyAll();
            }
        }

        void type(String text) {
            field = text;
        }

        /** An intention of the user with the text of the field; returns on the event thread or fails the test. */
        void command(BiFunction<NotebookPresenter, String, NotebookViewModel> intention) {
            onEdt(() -> {
                synchronized (views) {
                    mark = views.size();
                }
                NotebookViewModel next = intention.apply(presenter, field);
                told(next);
                view = next;
                field = next.activeDraft();
                return null;
            });
        }

        void enter() {
            command((p, f) -> p.onEnter(f));
        }

        void line(String text) {
            type(text);
            enter();
        }

        /** Goes to the line {@code number}, then replaces its text and validates it. */
        void edit(int number, String text) {
            command((p, f) -> p.onActivate(number - 1, f));
            line(text);
        }

        /** While the event thread is held by {@code during}, the user goes to the first line and validates {@code text}. */
        void commandWhile(Runnable during, String text) {
            onEdt(() -> {
                during.run();
                told(presenter.onActivate(0, field));
                synchronized (views) {
                    mark = views.size();
                }
                NotebookViewModel next = presenter.onEnter(text);
                told(next);
                view = next;
                field = next.activeDraft();
                return null;
            });
        }

        /** Waits for the end of the calculation and reads the state. */
        void settle() {
            executor.settle();
            view = onEdt(presenter::currentView);
        }

        /** The last state the window was told about, once it fulfils {@code wanted}. */
        NotebookViewModel awaitView(String what, Predicate<NotebookViewModel> wanted) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_PATIENCE_SECONDS);
            synchronized (views) {
                while (views.isEmpty() || !wanted.test(views.get(views.size() - 1))) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        String last = views.isEmpty() ? "nothing" : views.get(views.size() - 1).statusMessage();
                        throw new AssertionError("the window was never told that " + what + ". The last status: " + last);
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(views, left);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }
                return views.get(views.size() - 1);
            }
        }

        List<NotebookViewModel> viewsSinceTheLastCommand() {
            synchronized (views) {
                return List.copyOf(views.subList(mark, views.size()));
            }
        }

        /** The line is blocked with the notebook in the state each {@code command} needs (something to undo, to redo...). */
        Gate startACalculationBlockedOnALine(NewCommand command) {
            settle();
            edit(1, "a = 222");
            settle();
            Gate gate = evaluator.gateAt(THE_LINE_THAT_BLOCKS);
            if (command == NewCommand.REDO) {
                command((p, f) -> p.onUndo(f));
            } else {
                edit(1, "a = 333");
            }
            gate.awaitReached();
            return gate;
        }

        /** Plays {@code command}; what the notebook then is. */
        Outcome apply(NewCommand command, Path other) {
            List<String> current = new ArrayList<>(baseLines(333));
            String defaultStatus = NotebookPresenter.DEFAULT_STATUS;
            switch (command) {
                case VALIDATE -> {
                    edit(1, "a = 1000");
                    return new Outcome(baseLines(1000), DisplayMode.AUTO, defaultStatus);
                }
                case ADD -> {
                    command((p, f) -> p.onInsertBelow(f));
                    current.add(2, "");
                    return new Outcome(current, DisplayMode.AUTO, defaultStatus);
                }
                case DELETE -> {
                    command((p, f) -> p.onDelete(f));
                    current.remove(1);
                    return new Outcome(current, DisplayMode.AUTO, "Ligne 2 supprimée. Les références à cette ligne sont signalées en erreur ; Annuler la rétablit.");
                }
                case UNDO -> {
                    command((p, f) -> p.onUndo(f));
                    return new Outcome(baseLines(222), DisplayMode.AUTO, "Dernière modification annulée.");
                }
                case REDO -> {
                    command((p, f) -> p.onRedo(f));
                    return new Outcome(baseLines(222), DisplayMode.AUTO, "Modification rétablie.");
                }
                case MODE -> {
                    command((p, f) -> p.onDisplayModeChanged(DisplayMode.SCIENTIFIC, f));
                    return new Outcome(current, DisplayMode.SCIENTIFIC, "Affichage : " + DisplayModeText.label(DisplayMode.SCIENTIFIC) + ".");
                }
                case OPEN -> {
                    dialogs.willOpen(other);
                    command((p, f) -> p.onOpen(f));
                    return new Outcome(List.of("b = 5000", "b + 1", "b + 2"), DisplayMode.AUTO, "« autre.calc.json » ouvert (3 lignes).");
                }
                case NEW -> {
                    dialogs.willAnswer(Unsaved.DISCARD);
                    command((p, f) -> p.onNew(f));
                    return new Outcome(List.of(), DisplayMode.AUTO, "Nouveau carnet.");
                }
                default -> throw new AssertionError(command);
            }
        }

        /** Exports the notebook once it is calculated, and reads the file. */
        byte[] exported(ExportFormat format, String name) throws Exception {
            Path target = directory().resolve(name);
            dialogs.willExportTo(target);
            command((p, f) -> p.onExport(format, f));
            settle();
            return Files.readAllBytes(target);
        }

        /** The command ran and whatever it started has been run: no line may have been evaluated on the event thread. */
        void expectOffTheEventThread(String what, boolean evaluates) {
            int before = evaluator.count();
            assertEquals(List.of(), evaluator.onTheEventThread, what + " evaluated on the event thread");
            settle();
            assertEquals(List.of(), evaluator.onTheEventThread, what + " evaluated on the event thread");
            if (evaluates) {
                assertTrue(evaluator.count() > before, what + " must calculate on the thread of the executor");
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            evaluator.releaseEverything();
            try {
                if (presenter != null) {
                    onEdt(() -> {
                        dialogs.reset();
                        dialogs.willAnswer(Unsaved.DISCARD);
                        return presenter.onQuit(field);
                    });
                }
            } catch (AssertionError | RuntimeException leftOpen) {
                System.out.println("[acceptance] closing the notebook at the end of a test: " + leftOpen);
            } finally {
                executor.stop();
                try {
                    executor.join();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                store.close();
            }
        }
    }
}
