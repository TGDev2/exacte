package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.format.DisplayMode;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.accessibility.AccessibleContext;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of E09: the preview while typing (SPEC 4.2 « Aperçu », docs/adr/0014-calcul-hors-interface.md).
 *
 * <p>What this test fixes beyond the criteria of the task, so that the task knows what to build:
 * <ul>
 *   <li>{@code NotebookPresenter.onDraftChanged(String pending)} returns a {@link NotebookViewModel}. The view calls it on the
 *       event thread at each change of the text of the active field; it validates nothing.</li>
 *   <li>{@code NotebookRow.preview()} returns a {@code String}, never null: on the active line, the result the line would have
 *       with the text typed, formatted by {@code ValueFormatter} in the mode in force; empty when there is none (nothing
 *       pending, a text in error, a preview not known yet, or one made for another text). No other line has a preview.</li>
 *   <li>The preview evaluates the typed line through the evaluator the presenter is given (the {@code aroundEachLine} of
 *       {@code NotebookPresenter.open(store, modeStore, dialogs, executor, aroundEachLine)}, the one the recalculation already
 *       calls), off the event thread, then hands its result back on the event thread: the presenter tells its view listener and
 *       {@link NotebookPresenter#currentView()} shows it.</li>
 *   <li>In the window, the {@code resultLabel} of the active row shows the preview, in the colour of the role
 *       {@code text.muted}, with an accessible description that starts with « Aperçu ».</li>
 * </ul>
 *
 * <p>The new method and the new component are reached by reflection, so that this class compiles, and fails with a clear
 * message, before they exist. Every wait has a deadline. The presenter tests drive the real presenter on the event thread
 * without a window; the window tests build the real {@link NotebookFrame} in the light theme, as {@code Main} does, and never
 * show it. The preview is tried from the blank last row, the first line and the last line.
 */
@Tag("acceptance")
final class TypingPreviewAcceptanceTest {
    private static final long PATIENCE_SECONDS = 20;
    // How long a test lets a preview that should not come have its chance to come anyway.
    private static final long GRACE_MILLIS = 1500;
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final String DOCUMENT_TITLE = "Devis.calc.json — Calc";

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    // Every line the evaluator of the presenter was handed, with the thread it ran on.
    private final List<Evaluation> evaluations = new CopyOnWriteArrayList<>();
    // The texts whose evaluation has ended, normally or not.
    private final Set<String> finished = ConcurrentHashMap.newKeySet();
    // The texts whose next evaluation waits for the test to release it.
    private final Set<String> gated = ConcurrentHashMap.newKeySet();
    private final CountDownLatch gateReached = new CountDownLatch(1);
    private final CountDownLatch gateReleased = new CountDownLatch(1);
    // Every view model the presenter told its listener, in order.
    private final List<NotebookViewModel> told = new CopyOnWriteArrayList<>();
    private LookAndFeel original;

    private record Evaluation(String text, boolean onEventThread, String thread) {
    }

    @BeforeEach
    void rememberTheLookAndFeel() {
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
        gateReleased.countDown();
        if (!frames.isEmpty()) {
            SwingUtilities.invokeAndWait(() -> {
                frames.forEach(NotebookFrame::dispose);
                if (original != null && UIManager.getLookAndFeel() != original) {
                    try {
                        UIManager.setLookAndFeel(original);
                    } catch (Exception exception) {
                        throw new IllegalStateException(exception);
                    }
                }
            });
        }
        for (NotebookStore store : stores) {
            store.close();
        }
    }

    // ================================================================================================ the presenter

    @Test
    void typingInTheBlankLastLineShowsThePreviewAndValidatesNothing() throws Exception {
        Path document = temp.resolve("Devis.calc.json");
        NotebookPresenter presenter = presenter(List.of("prix = 12,5", "prix * 2"), new FakeDialogs().willSaveTo(document));
        NotebookViewModel saved = onEdt(() -> presenter.onSaveAs(presenter.currentView().activeDraft()));
        assertTrue(Files.isRegularFile(document), "the document has its file");
        assertEquals(DOCUMENT_TITLE, saved.title(), "the document is saved");
        assertFalse(saved.canUndo(), "nothing to undo after opening and saving");
        activate(presenter, 2);
        NotebookViewModel before = onEdt(presenter::currentView);
        Map<Path, String> files = files();

        NotebookViewModel typed = type(presenter, "2 + 3");
        assertEquals("2 + 3", typed.activeDraft(), "the text typed stays in the field");
        NotebookViewModel shown = awaitPreview(presenter, "5", "« 2 + 3 » typed in the blank last line");

        assertEquals(2, shown.activeIndex(), "the blank last line is still the active one");
        assertEquals("2 + 3", shown.activeDraft(), "the text typed is still pending");
        assertEquals(3, shown.rows().size(), "the notebook still has its two lines and the blank one");
        assertEquals("", shown.rows().get(2).result(), "a preview is not a result: nothing is validated");
        assertEquals(shapes(before.rows().subList(0, 2)), shapes(shown.rows().subList(0, 2)), "the lines above are untouched");
        assertFalse(shown.canUndo(), "a preview never enters the editor: there is nothing to undo");
        assertFalse(shown.canRedo(), "nor anything to redo");
        assertEquals(DOCUMENT_TITLE, shown.title(), "the document is not marked modified");
        flushEvents();
        assertEquals(files, files(), "no file is written by a preview");
        assertEveryPreviewMatchesItsText(presenter, Map.of("2 + 3", "5"));
    }

    @Test
    void thePreviewIsCalculatedOffTheEventThread() throws Exception {
        NotebookPresenter presenter = presenter(List.of("prix = 12,5", "prix * 2"));
        activate(presenter, 2);
        gated.add("2 + 3");

        // If the preview were calculated on the event thread, the gate would hold it and this call would never return.
        NotebookViewModel typed = type(presenter, "2 + 3");
        awaitGate("2 + 3");
        NotebookViewModel during = onEdt(presenter::currentView);
        assertEquals("2 + 3", typed.activeDraft(), "the event thread took the keystroke");
        assertEquals("", activePreview(during), "no preview before its calculation has ended");
        assertEquals("2 + 3", during.activeDraft(), "the event thread answers while the preview is being calculated");
        gateReleased.countDown();
        awaitPreview(presenter, "5", "« 2 + 3 » once its calculation is released");

        List<Evaluation> previewed = evaluations.stream().filter(each -> each.text().equals("2 + 3")).toList();
        assertFalse(previewed.isEmpty(), "the preview went through the evaluator of the presenter");
        assertEquals(List.of(), evaluations.stream().filter(Evaluation::onEventThread).toList(),
                "no line, previewed or calculated, is ever evaluated on the event thread");
    }

    @Test
    void aPreviewIsOnlyShownForTheTextThatProducedIt() throws Exception {
        NotebookPresenter presenter = presenter(List.of("prix = 12,5", "prix * 2"));
        activate(presenter, 2);

        type(presenter, "2 + 3");
        awaitPreview(presenter, "5", "« 2 + 3 »");
        NotebookViewModel incomplete = type(presenter, "2 + 3 *");
        assertEquals("", activePreview(incomplete), "« 5 » is no longer shown once the text is « 2 + 3 * »");
        assertEquals("", activePreview(onEdt(presenter::currentView)), "nor afterwards");
        NotebookViewModel complete = type(presenter, "2 + 3 * 4");
        assertNotEquals("5", activePreview(complete), "« 5 » is not the preview of « 2 + 3 * 4 »");
        NotebookViewModel shown = awaitPreview(presenter, "14", "« 2 + 3 * 4 »");
        assertEquals("2 + 3 * 4", shown.activeDraft(), "the preview is that of the text in the field");
        stays(presenter, "14", "« 2 + 3 * 4 »");
        assertEveryPreviewMatchesItsText(presenter, Map.of("2 + 3", "5", "2 + 3 * 4", "14"));
    }

    @Test
    void aStalePreviewThatArrivesAfterAKeystrokeIsIgnored() throws Exception {
        NotebookPresenter presenter = presenter(List.of("prix = 12,5", "prix * 2"));
        activate(presenter, 2);
        gated.add("2 + 3");

        type(presenter, "2 + 3");
        awaitGate("2 + 3");
        NotebookViewModel next = type(presenter, "2 + 3 * 4");
        assertNotEquals("5", activePreview(next), "the text is now « 2 + 3 * 4 »");
        gateReleased.countDown();
        awaitFinished("2 + 3");
        NotebookViewModel shown = awaitPreview(presenter, "14", "« 2 + 3 * 4 », typed while « 2 + 3 » was being calculated");
        assertEquals("2 + 3 * 4", shown.activeDraft());
        stays(presenter, "14", "« 2 + 3 * 4 » after the stale preview of « 2 + 3 » arrived");
        // « 2 + 3 » had no preview yet when « 2 + 3 * 4 » was typed: « 5 » must never have been shown at all.
        assertEveryPreviewMatchesItsText(presenter, Map.of("2 + 3 * 4", "14"));
    }

    @Test
    void aTextInErrorGivesNeitherAPreviewNorAnErrorMessage() throws Exception {
        NotebookPresenter presenter = presenter(List.of("a = 2", "a * 10"));
        NotebookViewModel before = onEdt(presenter::currentView);

        // The blank last line.
        activate(presenter, 2);
        assertNoPreviewNorError(type(presenter, "2 + "), "« 2 + » typed in the blank last line, at once");
        allowTheEvaluationOf("2 + ");
        assertNoPreviewNorError(onEdt(presenter::currentView), "« 2 + » typed in the blank last line, once calculated");
        NotebookViewModel escaped = onEdt(() -> presenter.onCancelEdit("2 + "));
        assertEquals("", escaped.activeDraft(), "Escape empties the blank last line");

        // The first line.
        activate(presenter, 0);
        assertNoPreviewNorError(type(presenter, "a = 2 +"), "« a = 2 + » typed in the first line, at once");
        allowTheEvaluationOf("a = 2 +");
        NotebookViewModel first = onEdt(presenter::currentView);
        assertNoPreviewNorError(first, "« a = 2 + » typed in the first line, once calculated");
        assertEquals(shapes(before.rows().subList(1, 2)), shapes(first.rows().subList(1, 2)), "the second line is untouched");
        onEdt(() -> presenter.onCancelEdit("a = 2 +"));

        // The last line.
        activate(presenter, 1);
        assertNoPreviewNorError(type(presenter, "a *"), "« a * » typed in the last line, at once");
        allowTheEvaluationOf("a *");
        NotebookViewModel last = onEdt(presenter::currentView);
        assertNoPreviewNorError(last, "« a * » typed in the last line, once calculated");
        assertEquals(shapes(before.rows().subList(0, 1)), shapes(last.rows().subList(0, 1)), "the first line is untouched");

        assertEveryPreviewMatchesItsText(presenter, Map.of());
    }

    @Test
    void escapeRestoresTheValidatedTextAndClearsThePreview() throws Exception {
        NotebookPresenter presenter = presenter(List.of("a = 2", "a * 10"));

        // The first line.
        activate(presenter, 0);
        type(presenter, "a = 7");
        NotebookViewModel previewed = awaitPreview(presenter, "7", "« a = 7 » typed in the first line");
        assertEquals("20", previewed.rows().get(1).result(), "the preview changes no other result");
        NotebookViewModel escaped = onEdt(() -> presenter.onCancelEdit("a = 7"));
        assertEquals("a = 2", escaped.activeDraft(), "Escape restores the validated text of the first line");
        assertEquals("", activePreview(escaped), "Escape clears the preview of the first line");
        assertEquals("2", escaped.rows().get(0).result(), "the first line shows its validated result again");
        stays(presenter, "", "the first line after Escape");

        // The last line.
        activate(presenter, 1);
        type(presenter, "a * 100");
        awaitPreview(presenter, "200", "« a * 100 » typed in the last line");
        escaped = onEdt(() -> presenter.onCancelEdit("a * 100"));
        assertEquals("a * 10", escaped.activeDraft(), "Escape restores the validated text of the last line");
        assertEquals("", activePreview(escaped), "Escape clears the preview of the last line");
        assertEquals("20", escaped.rows().get(1).result(), "the last line shows its validated result again");
        stays(presenter, "", "the last line after Escape");

        // The blank last line, with the preview still being calculated when Escape is pressed.
        activate(presenter, 2);
        gated.add("2 + 3");
        type(presenter, "2 + 3");
        awaitGate("2 + 3");
        escaped = onEdt(() -> presenter.onCancelEdit("2 + 3"));
        assertEquals("", escaped.activeDraft(), "Escape empties the blank last line");
        assertEquals("", activePreview(escaped), "Escape leaves no preview in the blank last line");
        gateReleased.countDown();
        awaitFinished("2 + 3");
        stays(presenter, "", "the blank last line after Escape, once the preview of « 2 + 3 » has arrived");

        NotebookViewModel last = onEdt(presenter::currentView);
        assertEquals(List.of("a = 2", "a * 10", ""), last.rows().stream().map(NotebookRow::text).toList(), "the notebook is unchanged");
        assertEquals(List.of("2", "20", ""), last.rows().stream().map(NotebookRow::result).toList(), "and so are its results");
        assertFalse(last.canUndo(), "nothing was validated");
        assertEveryPreviewMatchesItsText(presenter, Map.of("a = 7", "7", "a * 100", "200"));
    }

    @Test
    void enterValidatesAsBeforeAndTheValidatedResultReplacesThePreview() throws Exception {
        // The blank last line, against a notebook driven the same way without any preview.
        NotebookPresenter previewed = presenter(List.of("prix = 12,5"));
        NotebookPresenter reference = plainPresenter(List.of("prix = 12,5"));
        activate(previewed, 1);
        activate(reference, 1);
        type(previewed, "prix * 2");
        awaitPreview(previewed, "25", "« prix * 2 » typed in the blank last line");
        onEdt(() -> previewed.onEnter("prix * 2"));
        onEdt(() -> reference.onEnter("prix * 2"));
        awaitIdle(previewed);
        awaitIdle(reference);
        flushEvents();
        NotebookViewModel entered = onEdt(previewed::currentView);
        assertEquals(onEdt(reference::currentView), entered, "Enter validates as it does without a preview");
        assertEquals("25", entered.rows().get(1).result(), "the validated result is the one the preview showed");
        assertNoPreviewAnywhere(entered, "after Enter in the blank last line");
        assertTrue(entered.canUndo(), "Enter is a command of the editor");
        assertEquals(2, entered.activeIndex(), "Enter moves to the new blank line");

        // The first line, then the last line.
        NotebookPresenter presenter = presenter(List.of("a = 2", "a * 10"));
        activate(presenter, 0);
        type(presenter, "a = 3");
        awaitPreview(presenter, "3", "« a = 3 » typed in the first line");
        enter(presenter, "a = 3");
        NotebookViewModel first = onEdt(presenter::currentView);
        assertEquals(List.of("3", "30", ""), first.rows().stream().map(NotebookRow::result).toList(),
                "Enter in the first line validates it and the lines below follow");
        assertNoPreviewAnywhere(first, "after Enter in the first line");
        assertEquals(1, first.activeIndex(), "Enter moves to the next line");

        type(presenter, "a * 4");
        awaitPreview(presenter, "12", "« a * 4 » typed in the last line");
        enter(presenter, "a * 4");
        NotebookViewModel last = onEdt(presenter::currentView);
        assertEquals(List.of("3", "12", ""), last.rows().stream().map(NotebookRow::result).toList(), "Enter validates the last line");
        assertNoPreviewAnywhere(last, "after Enter in the last line");
        assertEquals(2, last.activeIndex(), "Enter moves to the blank line below");
        stays(presenter, "", "the blank line after Enter");
    }

    @Test
    void thePreviewIsTheResultEnterGivesInEveryDisplayMode() throws Exception {
        NotebookPresenter presenter = presenter(List.of("a = 1/3"));
        activate(presenter, 1);
        for (DisplayMode mode : DisplayMode.values()) {
            onEdt(() -> presenter.onDisplayModeChanged(mode, presenter.currentView().activeDraft()));
            awaitIdle(presenter);
            for (String text : List.of("a + 1/6", "sqrt(2) * a", "a * 3000000")) {
                type(presenter, text);
                NotebookViewModel previewed = awaitPreview(presenter, preview -> !preview.isEmpty(), "« " + text + " » in " + mode);
                String preview = activePreview(previewed);
                enter(presenter, text);
                NotebookViewModel entered = onEdt(presenter::currentView);
                NotebookRow validated = entered.rows().get(entered.activeIndex() - 1);
                assertEquals(text, validated.text());
                assertEquals(validated.result(), preview, "the preview of « " + text + " » in " + mode + " is the result Enter gives");
                assertNoPreviewAnywhere(entered, "after Enter of « " + text + " » in " + mode);
            }
        }
    }

    @Test
    void thePreviewReadsTheLinesAboveWithoutChangingTheNotebookOrTheirResults() throws Exception {
        NotebookPresenter presenter = presenter(List.of("prix = 12,5", "prix * 2"));
        NotebookViewModel before = onEdt(presenter::currentView);
        Map<Path, String> files = files();

        // The blank last line: a variable, a line reference and ans.
        activate(presenter, 2);
        type(presenter, "prix + $2 + ans");
        NotebookViewModel blank = awaitPreview(presenter, "62,5", "« prix + $2 + ans » under « prix = 12,5 » and « prix * 2 »");
        assertEquals(shapes(before.rows().subList(0, 2)), shapes(blank.rows().subList(0, 2)), "the lines above are untouched");
        onEdt(() -> presenter.onCancelEdit("prix + $2 + ans"));

        // The first line: the line below keeps its result.
        activate(presenter, 0);
        type(presenter, "prix = 20");
        NotebookViewModel first = awaitPreview(presenter, "20", "« prix = 20 » typed in the first line");
        assertEquals(shapes(before.rows().subList(1, 2)), shapes(first.rows().subList(1, 2)),
                "the second line keeps its result: « 25 », not « 40 »");
        onEdt(() -> presenter.onCancelEdit("prix = 20"));

        // The last line: a line reference, then ans, which is the result just above.
        activate(presenter, 1);
        type(presenter, "prix * 3 + $1");
        NotebookViewModel last = awaitPreview(presenter, "50", "« prix * 3 + $1 » typed in the last line");
        assertEquals(shapes(before.rows().subList(0, 1)), shapes(last.rows().subList(0, 1)), "the first line is untouched");
        type(presenter, "ans * 3");
        last = awaitPreview(presenter, "37,5", "« ans * 3 » typed in the last line");
        assertEquals(shapes(before.rows().subList(0, 1)), shapes(last.rows().subList(0, 1)), "the first line is untouched");
        onEdt(() -> presenter.onCancelEdit("ans * 3"));

        NotebookViewModel after = onEdt(presenter::currentView);
        assertEquals(shapes(before.rows()), shapes(after.rows()), "the notebook and its results are as they were");
        assertFalse(after.canUndo(), "no preview entered the editor");
        flushEvents();
        assertEquals(files, files(), "no file is written by a preview");
        assertEveryPreviewMatchesItsText(presenter, Map.of("prix + $2 + ans", "62,5", "prix = 20", "20", "prix * 3 + $1", "50",
                "ans * 3", "37,5"));
    }

    // ================================================================================================ the window

    @Test
    @Tag("ui")
    void theWindowShowsThePreviewMutedWithAnAccessibleDescriptionAndWritesNothing() throws Exception {
        requireDisplay();
        requireThePreview();
        Path document = temp.resolve("Devis.calc.json");
        NotebookFrame frame = window(List.of("prix = 12,5", "prix * 2"), new FakeDialogs().willSaveTo(document), true);
        assertEquals(DOCUMENT_TITLE, onEdt(frame::getTitle), "the document is saved");
        Map<Path, String> files = files();

        activate(frame, 2);
        typeInto(frame, 2, "2 + 3");
        JLabel result = awaitResultText(frame, 2, "5", "« 2 + 3 » typed in the blank last line of the window");

        DesignTokens tokens = DesignTokens.load();
        assertEquals(hex(tokens.color(Theme.LIGHT, "text.muted")), hex(onEdt(result::getForeground)),
                "the preview is drawn in text.muted");
        AccessibleContext accessible = onEdt(result::getAccessibleContext);
        String description = onEdt(accessible::getAccessibleDescription);
        assertNotNull(description, "the preview has an accessible description");
        assertTrue(description.startsWith("Aperçu"), "the accessible description of the preview starts with « Aperçu »: " + description);
        assertTrue(description.contains("5"), "and says the value: " + description);
        assertEquals("2 + 3", onEdt(() -> frame.row(2).field.getText()), "the text typed stays in the field");
        assertEquals("", textOf(onEdt(() -> part(frame.row(2), "errorLabel"))), "no error under the line");
        NotebookViewModel shown = onEdt(frame::shown);
        assertFalse(shown.canUndo(), "a preview never enters the editor");
        assertEquals(3, shown.rows().size(), "the notebook still has its two lines and the blank one");
        assertEquals(DOCUMENT_TITLE, onEdt(frame::getTitle), "the document is not marked modified");
        flushEvents();
        assertEquals(files, files(), "no file is written by a preview");
        assertTrue(evaluations.stream().anyMatch(each -> each.text().equals("2 + 3")), "the preview went through the evaluator");
        assertEquals(List.of(), evaluations.stream().filter(Evaluation::onEventThread).toList(),
                "nothing is evaluated on the event thread");
    }

    @Test
    @Tag("ui")
    void inTheWindowAnErrorShowsNothingEscapeClearsThePreviewAndEnterReplacesIt() throws Exception {
        requireDisplay();
        requireThePreview();
        NotebookFrame frame = window(List.of("prix = 12,5", "prix * 2"), NotebookDialogs.cancelling(), false);
        DesignTokens tokens = DesignTokens.load();

        activate(frame, 2);
        typeInto(frame, 2, "2 + ");
        allowTheEvaluationOf("2 + ");
        assertEquals("", textOf(onEdt(() -> part(frame.row(2), "resultLabel"))), "« 2 + » has no preview");
        assertEquals("", textOf(onEdt(() -> part(frame.row(2), "errorLabel"))), "and no error message");
        assertNotEquals(StatusTone.ERROR, onEdt(() -> frame.shown().statusTone()), "the status bar shows no error");

        press(frame, 2, KeyEvent.VK_ESCAPE);
        assertEquals("", onEdt(() -> frame.row(2).field.getText()), "Escape empties the blank last line");
        typeInto(frame, 2, "2 + 3");
        awaitResultText(frame, 2, "5", "« 2 + 3 » typed in the blank last line of the window");
        press(frame, 2, KeyEvent.VK_ESCAPE);
        assertEquals("", onEdt(() -> frame.row(2).field.getText()), "Escape empties the blank last line again");
        allowTheEvaluationOf("2 + 3");
        assertEquals("", textOf(onEdt(() -> part(frame.row(2), "resultLabel"))), "Escape clears the preview");

        typeInto(frame, 2, "2 + 3");
        awaitResultText(frame, 2, "5", "« 2 + 3 » typed again");
        press(frame, 2, KeyEvent.VK_ENTER);
        awaitSettled(frame);
        NotebookViewModel entered = onEdt(frame::shown);
        assertEquals(3, entered.activeIndex(), "Enter moves to the new blank line");
        assertEquals("5", entered.rows().get(2).result(), "Enter validates « 2 + 3 »");
        JLabel validated = (JLabel) onEdt(() -> part(frame.row(2), "resultLabel"));
        assertEquals("5", textOf(validated), "the validated result takes the place of the preview");
        assertEquals(hex(tokens.color(Theme.LIGHT, "text.primary")), hex(onEdt(validated::getForeground)),
                "a validated exact result is drawn in text.primary, no longer muted");
        String description = onEdt(() -> validated.getAccessibleContext().getAccessibleDescription());
        assertTrue(description != null && description.startsWith("Résultat"), "the validated line is described as a result: " + description);
        assertEquals("", textOf(onEdt(() -> part(frame.row(3), "resultLabel"))), "the new blank line has no preview");
    }

    // ================================================================================================ the evaluator

    // Records the line and its thread, and holds the line the test gated until it is released.
    private LineResult recording(String text, Supplier<LineResult> evaluation) {
        evaluations.add(new Evaluation(text, SwingUtilities.isEventDispatchThread(), Thread.currentThread().getName()));
        try {
            if (gated.remove(text)) {
                gateReached.countDown();
                awaitRelease();
            }
            return evaluation.get();
        } finally {
            finished.add(text);
        }
    }

    // Waits for the test even when the thread is interrupted: a preview made stale must still be able to arrive late.
    private void awaitRelease() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        boolean interrupted = false;
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("nobody released the gated line");
                }
                try {
                    if (gateReleased.await(left, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void awaitGate(String text) throws InterruptedException {
        assertTrue(gateReached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the preview of « " + text
                + " » was never handed to the evaluator of the presenter (aroundEachLine), off the event thread");
    }

    private void awaitFinished(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!finished.contains(text)) {
            if (System.nanoTime() > deadline) {
                fail("the evaluation of « " + text + " » has not ended after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(5);
        }
    }

    // Gives a text that must show nothing the time to be evaluated, if it is evaluated at all, then lets its answer arrive.
    private void allowTheEvaluationOf(String text) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MILLIS);
        while (!finished.contains(text) && System.nanoTime() < end) {
            Thread.sleep(5);
        }
        flushEvents();
    }

    // ================================================================================================ the presenter, driven

    private NotebookPresenter presenter(List<String> lines) throws Exception {
        return presenter(lines, NotebookDialogs.cancelling());
    }

    // The presenter of a store holding lines, with the recording evaluator, once every line is calculated.
    private NotebookPresenter presenter(List<String> lines, NotebookDialogs dialogs) throws Exception {
        NotebookStore store = store(lines);
        NotebookPresenter presenter = onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                NotebookPresenter.newRecalculationExecutor(), this::recording));
        onEdt(() -> {
            presenter.setViewListener(told::add);
            return null;
        });
        awaitIdle(presenter);
        return presenter;
    }

    // The same presenter, driven without any preview: what Enter did before the task.
    private NotebookPresenter plainPresenter(List<String> lines) throws Exception {
        NotebookStore store = store(lines);
        NotebookPresenter presenter = onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling(),
                NotebookPresenter.newRecalculationExecutor(), (text, evaluation) -> evaluation.get()));
        awaitIdle(presenter);
        return presenter;
    }

    private NotebookStore store(List<String> lines) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        return store;
    }

    // Goes to the row at index (from 0) as the mouse does, with what the active field holds.
    private static void activate(NotebookPresenter presenter, int index) throws Exception {
        NotebookViewModel view = onEdt(() -> presenter.onActivate(index, presenter.currentView().activeDraft()));
        assertEquals(index, view.activeIndex(), "line " + (index + 1) + " is active");
        awaitIdle(presenter);
    }

    // What the view does at each change of the text of the active field.
    private static NotebookViewModel type(NotebookPresenter presenter, String text) throws Exception {
        return onEdt(() -> draftChanged(presenter, text));
    }

    private static void enter(NotebookPresenter presenter, String pending) throws Exception {
        onEdt(() -> presenter.onEnter(pending));
        awaitIdle(presenter);
        flushEvents();
    }

    private static void awaitIdle(NotebookPresenter presenter) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (onEdt(presenter::isCalculating)) {
            if (System.nanoTime() > deadline) {
                fail("the notebook is still being calculated after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(5);
        }
    }

    private static NotebookViewModel awaitPreview(NotebookPresenter presenter, String expected, String what) throws Exception {
        return awaitPreview(presenter, expected::equals, what + ", expected « " + expected + " »");
    }

    private static NotebookViewModel awaitPreview(NotebookPresenter presenter, Predicate<String> expected, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (true) {
            NotebookViewModel view = onEdt(presenter::currentView);
            String preview = activePreview(view);
            if (expected.test(preview)) {
                return view;
            }
            if (System.nanoTime() > deadline) {
                fail(what + ": after " + PATIENCE_SECONDS + " s, line " + (view.activeIndex() + 1) + " holding « " + view.activeDraft()
                        + " » shows the preview « " + preview + " »");
            }
            Thread.sleep(5);
        }
    }

    // The preview of the active line stays what it is while whatever is still on its way arrives.
    private static void stays(NotebookPresenter presenter, String expected, String what) throws Exception {
        long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MILLIS / 5);
        do {
            flushEvents();
            assertEquals(expected, activePreview(onEdt(presenter::currentView)), "the preview of " + what);
            Thread.sleep(20);
        } while (System.nanoTime() < end);
    }

    /**
     * Every view model told to the listener, and the current one: only the active line may have a preview, and a preview shown
     * is the one of the text the line held when it was shown.
     */
    private void assertEveryPreviewMatchesItsText(NotebookPresenter presenter, Map<String, String> previews) throws Exception {
        List<NotebookViewModel> views = new ArrayList<>(told);
        views.add(onEdt(presenter::currentView));
        for (NotebookViewModel view : views) {
            for (int index = 0; index < view.rows().size(); index++) {
                String preview = preview(view.rows().get(index));
                if (preview.isEmpty()) {
                    continue;
                }
                assertEquals(view.activeIndex(), index, "only the active line has a preview, yet line " + (index + 1)
                        + " shows « " + preview + " »");
                assertEquals(previews.get(view.activeDraft()), preview, "the preview shown while the line holds « "
                        + view.activeDraft() + " »");
            }
        }
    }

    private static void assertNoPreviewNorError(NotebookViewModel view, String what) {
        NotebookRow row = activeRow(view);
        assertEquals("", preview(row), what + ": no preview");
        assertEquals("", row.errorMessage(), what + ": no error message on the line");
        assertNotEquals(NotebookRow.Status.ERROR, row.status(), what + ": the line is not in error");
        assertNotEquals(NotebookRow.Sign.ERROR, row.sign(), what + ": no « ! » in the margin");
        assertNotEquals(StatusTone.ERROR, view.statusTone(), what + ": no error in the status bar (" + view.statusMessage() + ")");
    }

    private static void assertNoPreviewAnywhere(NotebookViewModel view, String what) {
        for (NotebookRow row : view.rows()) {
            assertEquals("", preview(row), what + ": line " + row.number() + " has no preview");
        }
    }

    private static NotebookRow activeRow(NotebookViewModel view) {
        return view.rows().get(view.activeIndex());
    }

    private static String activePreview(NotebookViewModel view) {
        return preview(activeRow(view));
    }

    // What a line shows apart from its preview and its source mark.
    private static List<String> shapes(List<NotebookRow> rows) {
        return rows.stream().map(row -> row.number() + " | " + row.text() + " | " + row.status() + " | " + row.result() + " | "
                + row.detail() + " | " + row.errorMessage() + " | " + row.definedName()).toList();
    }

    // ================================================================================================ the new API, by reflection

    private static void requireThePreview() {
        previewMethod();
        draftChangedMethod();
    }

    private static Method draftChangedMethod() {
        Method method;
        try {
            method = NotebookPresenter.class.getDeclaredMethod("onDraftChanged", String.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("The preview while typing does not exist yet: no NotebookPresenter.onDraftChanged(String)");
        }
        assertEquals(NotebookViewModel.class, method.getReturnType(), "onDraftChanged returns the new NotebookViewModel");
        method.setAccessible(true);
        return method;
    }

    private static Method previewMethod() {
        Method method;
        try {
            method = NotebookRow.class.getMethod("preview");
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("The preview while typing does not exist yet: no NotebookRow.preview()");
        }
        assertEquals(String.class, method.getReturnType(), "NotebookRow.preview() is a text, empty without a preview");
        return method;
    }

    private static NotebookViewModel draftChanged(NotebookPresenter presenter, String pending) {
        try {
            return (NotebookViewModel) draftChangedMethod().invoke(presenter, pending);
        } catch (IllegalAccessException refused) {
            throw new AssertionError("onDraftChanged must be reachable from its package", refused);
        } catch (InvocationTargetException thrown) {
            throw rethrown(thrown.getCause());
        }
    }

    private static String preview(NotebookRow row) {
        Object preview;
        try {
            preview = previewMethod().invoke(row);
        } catch (IllegalAccessException refused) {
            throw new AssertionError("NotebookRow.preview() must be public", refused);
        } catch (InvocationTargetException thrown) {
            throw rethrown(thrown.getCause());
        }
        assertNotNull(preview, "NotebookRow.preview() is never null: it is empty without a preview");
        return (String) preview;
    }

    private static RuntimeException rethrown(Throwable cause) {
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(cause);
    }

    // ================================================================================================ the window, driven

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    /**
     * A window as Main builds it, in the light theme, on a store holding {@code lines}, with the recording evaluator; when
     * {@code saveFirst}, the notebook is first saved as the document the dialogs name. Laid out once every line is calculated.
     */
    private NotebookFrame window(List<String> lines, NotebookDialogs dialogs, boolean saveFirst) throws Exception {
        NotebookStore store = store(lines);
        NotebookFrame frame = onEdt(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            NotebookPresenter presenter = NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                    NotebookPresenter.newRecalculationExecutor(), this::recording);
            if (saveFirst) {
                presenter.onSaveAs(presenter.currentView().activeDraft());
            }
            NotebookFrame built;
            // The JDK lists a window as visible while its constructor runs; Window.getWindows() takes this lock.
            synchronized (Window.class) {
                built = new NotebookFrame(presenter);
            }
            frames.add(built);
            built.pack();
            Insets border = built.getInsets();
            built.setSize(WIDTH + border.left + border.right, HEIGHT + border.top + border.bottom);
            built.validate();
            return built;
        });
        awaitSettled(frame);
        return frame;
    }

    // What the mouse does: a focus gained on the field of the row activates its line.
    private static void activate(NotebookFrame frame, int index) throws Exception {
        onEdt(() -> {
            if (frame.shown().activeIndex() != index) {
                JTextComponent field = frame.row(index).field;
                for (FocusListener listener : field.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
                }
            }
            return null;
        });
        awaitSettled(frame);
        assertEquals(index, (int) onEdt(() -> frame.shown().activeIndex()), "line " + (index + 1) + " is active");
    }

    // Types the text at the end of the field of the row, one character at a time, as the keyboard does.
    private static void typeInto(NotebookFrame frame, int index, String text) throws Exception {
        for (char character : text.toCharArray()) {
            onEdt(() -> {
                JTextComponent field = frame.row(index).field;
                field.setCaretPosition(field.getDocument().getLength());
                field.replaceSelection(String.valueOf(character));
                return null;
            });
        }
        flushEvents();
        assertEquals(text, onEdt(() -> frame.row(index).field.getText()), "the field holds what was typed");
    }

    // Runs the action the field of the row binds to the key, as Swing does when the key is pressed in it.
    private static void press(NotebookFrame frame, int row, int keyCode) throws Exception {
        onEdt(() -> {
            JTextComponent field = frame.row(row).field;
            KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
            Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertTrue(key != null, "nothing is bound to " + stroke);
            Action action = field.getActionMap().get(key);
            assertTrue(action != null, "no action " + key);
            action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key.toString()));
            return null;
        });
        flushEvents();
    }

    // Waits until nothing is being calculated and every row is built, then lays the window out again.
    private static void awaitSettled(NotebookFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!onEdt(() -> !frame.isCalculating() && frame.rowCount() == frame.shown().rows().size())) {
            if (System.nanoTime() > deadline) {
                fail("the notebook is still being calculated after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(5);
        }
        flushEvents();
        onEdt(() -> {
            frame.validate();
            return null;
        });
    }

    private static JLabel awaitResultText(NotebookFrame frame, int index, String expected, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (true) {
            Component label = onEdt(() -> part(frame.row(index), "resultLabel"));
            String shown = textOf(label);
            if (expected.equals(shown)) {
                assertTrue(label instanceof JLabel, "resultLabel is a label");
                return (JLabel) label;
            }
            if (System.nanoTime() > deadline) {
                fail(what + ": after " + PATIENCE_SECONDS + " s, the result zone of line " + (index + 1) + " shows « " + shown
                        + " », expected « " + expected + " »");
            }
            Thread.sleep(5);
        }
    }

    // The one component of the row with this name.
    private static Component part(NotebookRowView row, String name) {
        List<Component> found = descendants(row).stream().filter(each -> name.equals(each.getName())).toList();
        assertEquals(1, found.size(), "line " + (row.index + 1) + " has one component named " + name + ", has " + found.size());
        return found.get(0);
    }

    private static List<Component> descendants(Container root) {
        List<Component> all = new ArrayList<>();
        Deque<Component> pending = new ArrayDeque<>(List.of(root.getComponents()));
        while (!pending.isEmpty()) {
            Component each = pending.pop();
            all.add(each);
            if (each instanceof Container container) {
                pending.addAll(List.of(container.getComponents()));
            }
        }
        return all;
    }

    private static String textOf(Component component) throws Exception {
        return onEdt(() -> {
            String text;
            if (component instanceof JLabel label) {
                text = label.getText();
            } else if (component instanceof JTextComponent field) {
                text = field.getText();
            } else {
                throw new AssertionError(component.getName() + " shows no text");
            }
            return text == null ? "" : text;
        });
    }

    private static String hex(Color color) {
        return String.format("#%06X", color.getRGB() & 0xFFFFFF);
    }

    // ================================================================================================ files and threads

    // Every file under the temporary folder: its size, its date and a digest of its bytes.
    private Map<Path, String> files() throws IOException {
        Map<Path, String> state = new TreeMap<>();
        List<Path> all;
        try (Stream<Path> walk = Files.walk(temp)) {
            all = walk.filter(Files::isRegularFile).toList();
        }
        for (Path file : all) {
            String content;
            try {
                content = Integer.toHexString(Arrays.hashCode(Files.readAllBytes(file)));
            } catch (IOException locked) {
                content = "unreadable";
            }
            state.put(temp.relativize(file), Files.size(file) + " bytes, " + Files.getLastModifiedTime(file) + ", " + content);
        }
        return new LinkedHashMap<>(state);
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable thrown) {
                result.completeExceptionally(thrown);
            }
        });
        try {
            return result.get(PATIENCE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException blocked) {
            throw new AssertionError("the event thread has not answered for " + PATIENCE_SECONDS
                    + " s: is something calculated on it?", blocked);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    // Lets what the event thread queued run before the test reads.
    private static void flushEvents() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
