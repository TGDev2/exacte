package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Task E17: pasting several lines into a line of the notebook replaces it by those lines, in one command that a single undo
 * takes back. SPEC §4.2 (Collage): {@code NotebookPresenter.onPaste(int ligne, int débutSélection, int finSélection, String
 * texte)} applies one command of the model (for example {@code NotebookCommand.ReplaceWithLines}), and {@code LineField} hands
 * the paste to it through its {@code TransferHandler}.
 *
 * <p>The contract this class fixes for {@code onPaste}: {@code ligne} is the index (from 0) of the row pasted into, as in
 * {@link NotebookPresenter#onActivate}, always the active row, whose text is the one the field holds (its draft, which the view
 * tells the presenter at each keystroke through {@code onDraftChanged}); the selection is {@code débutSélection} to
 * {@code finSélection} (excluded) of that text. The text is cut at {@code \r\n}, {@code \r} and {@code \n}, a final line break
 * adds no line, the first part follows the text before the selection and the last part precedes the text after it.
 *
 * <p>{@code onPaste} is reached by reflection, so that this class compiles before the task and fails with a clear message.
 * The presenter tests drive the real presenter, model, store and files on a temporary folder, without a window; the window
 * tests build the real {@link NotebookFrame} as {@code Main} does and paste as the user does: Ctrl+V with the system clipboard
 * (put back as it was at the end), or the {@code TransferHandler} of the field, which is what Ctrl+V calls. No modal dialog is
 * ever opened, and every wait has a deadline.
 */
@Tag("acceptance")
final class MultiLinePasteAcceptanceTest {
    private static final long PATIENCE_SECONDS = 60;
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final String REFUSED = "Collage refusé";
    private static final int MAX_LENGTH = NotebookPresenter.MAX_LINE_LENGTH;

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private LookAndFeel original;

    @BeforeEach
    void rememberTheLookAndFeel() {
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
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

    // ================================================================================================ several lines, where pasted

    static Stream<Named<String>> oneTwoThree() {
        return Stream.of("1\n2\n3", "1\r\n2\r\n3", "1\r2\r3", "1\n2\n3\n", "1\r\n2\r\n3\r\n", "1\r2\r3\r")
                .map(text -> Named.of(shown(text), text));
    }

    @ParameterizedTest
    @MethodSource("oneTwoThree")
    void pastedIntoABlankLineEveryLineOfTheTextBecomesALineAtThatPlace(String text) throws Exception {
        Session s = session(List.of("x = 1", "", "x + 1"));
        s.activate(1);

        s.paste(1, 0, 0, text);

        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), s.texts(), "pasted « " + shown(text) + " » into line 2");
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), s.savedTexts(), "the pasted lines are saved");
        assertEquals(List.of("1", "1", "2", "3", "2"), s.results(), "every pasted line is calculated");
        assertFalse(s.view().statusMessage().startsWith(REFUSED), "the paste is accepted");
    }

    @ParameterizedTest
    @MethodSource("oneTwoThree")
    void pastedIntoTheNewLineOfAnEmptyNotebookTheTextGivesItsLines(String text) throws Exception {
        Session s = session(List.of());

        s.paste(0, 0, 0, text);

        assertEquals(List.of("1", "2", "3"), s.texts(), "pasted « " + shown(text) + " » into the empty notebook");
        assertEquals(List.of("1", "2", "3"), s.savedTexts());
        assertEquals(List.of("1", "2", "3", ""), s.rowTexts(), "no blank line is added, only the new line at the end");
    }

    @Test
    void pastedInTheMiddleOfAValidatedLineTheTextAroundTheCaretFramesThePastedLines() throws Exception {
        Session s = session(List.of("b = 5", "a = b"));
        s.activate(1);

        s.paste(1, 4, 4, "1 +\n2\n3 * ");

        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.texts(),
                "« a = » then the first part, the middle line, the last part then « b »");
        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.savedTexts());
    }

    @Test
    void pastedInTheMiddleOfATypedTextTheTypedTextFramesThePastedLines() throws Exception {
        Session s = session(List.of("b = 5"));
        s.activate(1);
        s.typing("a = b");

        s.paste(1, 4, 4, "1 +\r\n2\r\n3 * \r\n");

        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.texts(),
                "the text typed and not validated is the one around the caret");
        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.savedTexts());
    }

    @Test
    void aSelectionIsReplacedByThePastedLines() throws Exception {
        Session s = session(List.of("b = 5", "a = bc + 1"));
        s.activate(1);

        s.paste(1, 4, 6, "7\n8 * ");

        assertEquals(List.of("b = 5", "a = 7", "8 *  + 1"), s.texts(), "« bc », selected, is replaced");
    }

    // ================================================================================================ the three places of E05

    @Test
    void atTheEndOfTheFirstLineOfTheLastWrittenLineThenInTheLastBlankLineTheLinesGoThereAndNothingElseMoves() throws Exception {
        Session s = session(List.of("a = 2", "b = a * 3", "b + 1"));

        s.activate(0);
        s.paste(0, 5, 5, "1\n2");
        assertEquals(List.of("a = 21", "2", "b = a * 3", "b + 1"), s.texts(), "at the end of the first line");

        s.activate(3);
        s.paste(3, 5, 5, "1\n2");
        assertEquals(List.of("a = 21", "2", "b = a * 3", "b + 11", "2"), s.texts(), "at the end of the last written line");

        s.activate(5);
        s.paste(5, 0, 0, "1\n2");
        assertEquals(List.of("a = 21", "2", "b = a * 3", "b + 11", "2", "1", "2"), s.texts(), "in the last blank line");
        assertEquals(List.of("a = 21", "2", "b = a * 3", "b + 11", "2", "1", "2", ""), s.rowTexts(),
                "the new line stays the last row");
        assertEquals(s.texts(), s.savedTexts());
    }

    @Test
    void theSameThreePlacesWithATextTypedFirst() throws Exception {
        Session s = session(List.of("a = 2", "b = a * 3", "b + 1"));

        s.activate(0);
        s.typing("a = 2 + ");
        s.paste(0, 8, 8, "1\n2");
        assertEquals(List.of("a = 2 + 1", "2", "b = a * 3", "b + 1"), s.texts(), "after the text typed in the first line");

        s.activate(3);
        s.typing("b + 1 - ");
        s.paste(3, 8, 8, "1\n2");
        assertEquals(List.of("a = 2 + 1", "2", "b = a * 3", "b + 1 - 1", "2"), s.texts(),
                "after the text typed in the last written line");

        s.activate(5);
        s.typing("c = ");
        s.paste(5, 4, 4, "1\n2");
        assertEquals(List.of("a = 2 + 1", "2", "b = a * 3", "b + 1 - 1", "2", "c = 1", "2"), s.texts(),
                "after the text typed in the last blank line");
        assertEquals(s.texts(), s.savedTexts());
    }

    // ================================================================================================ one undo, one redo

    @Test
    void oneUndoGivesBackExactlyTheNotebookOfBeforeAndRedoPastesAgain() throws Exception {
        Session s = session(List.of("x = 1", "", "x + 1"));
        assertFalse(s.view().canUndo(), "a notebook just opened has nothing to undo");
        s.activate(1);
        s.paste(1, 0, 0, "1\n2\n3");
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), s.texts());
        assertTrue(s.view().canUndo());

        s.undo();

        assertEquals(List.of("x = 1", "", "x + 1"), s.texts(), "one undo takes the whole paste back");
        assertEquals(List.of("x = 1", "", "x + 1"), s.savedTexts());
        assertEquals(List.of("1", "", "2"), s.results());
        assertFalse(s.view().canUndo(), "the paste was one command: nothing is left to undo");

        s.redo();

        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), s.texts(), "one redo pastes it again");
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), s.savedTexts());
        assertFalse(s.view().canRedo());
    }

    @Test
    void afterATypedTextOneUndoGivesBackTheNotebookOfBeforeTheTypedTextWasNeverValidated() throws Exception {
        Session s = session(List.of("b = 5"));
        s.activate(1);
        s.typing("a = b");
        s.paste(1, 4, 4, "1 +\n2\n3 * ");
        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.texts());

        s.undo();

        assertEquals(List.of("b = 5"), s.texts(), "the notebook of before the paste, where « a = b » was only typed");
        assertEquals(List.of("b = 5"), s.savedTexts());
        assertFalse(s.view().canUndo(), "the paste was one command, the typed text none");

        s.redo();

        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.texts());
        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), s.savedTexts());
    }

    @Test
    void oneUndoAfterAPasteInTheMiddleOfAValidatedLineGivesItsTextBack() throws Exception {
        Session s = session(List.of("a = 2", "b = a * 3", "b + 1"));
        s.activate(1);
        s.paste(1, 4, 4, "1\n2\n");
        assertEquals(List.of("a = 2", "b = 1", "2a * 3", "b + 1"), s.texts());

        s.undo();

        assertEquals(List.of("a = 2", "b = a * 3", "b + 1"), s.texts());
        assertEquals(List.of("2", "6", "7"), s.results());
        assertFalse(s.view().canUndo());
    }

    // ================================================================================================ refused as a whole

    @Test
    void aPasteBeyondTheMaximumNumberOfLinesIsRefusedAndChangesNothing() throws Exception {
        List<String> lines = ones(Notebook.MAX_LINES - 1);
        Session s = session(lines);
        s.activate(lines.size());

        s.paste(lines.size(), 0, 0, "1\n2");

        s.assertRefused(lines, "4 999 lines and 2 pasted into the new line would make 5 001");
    }

    @Test
    void replacingALineByTooManyLinesIsRefused() throws Exception {
        List<String> lines = ones(Notebook.MAX_LINES - 1);
        Session s = session(lines);
        s.activate(0);

        s.paste(0, 0, 1, "1\n2\n3");

        s.assertRefused(lines, "line 1 of 4 999 replaced by 3 lines would make 5 001");
    }

    @Test
    void aPasteOfMoreLinesThanTheMaximumIntoAnEmptyNotebookIsRefused() throws Exception {
        Session s = session(List.of());

        s.paste(0, 0, 0, String.join("\n", ones(Notebook.MAX_LINES + 1)));

        s.assertRefused(List.of(), "5 001 lines pasted into an empty notebook");
    }

    @Test
    void aPasteThatReachesExactlyTheMaximumNumberOfLinesIsAccepted() throws Exception {
        List<String> lines = ones(Notebook.MAX_LINES - 1);
        Session s = session(lines);
        s.activate(0);

        s.paste(0, 0, 1, "7\n8");

        List<String> saved = s.savedTexts();
        assertEquals(Notebook.MAX_LINES, saved.size(), "line 1 of 4 999 replaced by 2 lines makes 5 000, the maximum");
        assertEquals(List.of("7", "8", "1"), saved.subList(0, 3));
        assertFalse(s.view().statusMessage().startsWith(REFUSED), s.view().statusMessage());
    }

    @Test
    void aPastedLineLongerThanTheMaximumIsRefusedWithAllTheOthers() throws Exception {
        Session s = session(List.of("x = 1", "", "x + 1"));
        s.activate(1);

        s.paste(1, 0, 0, "1\n" + "9".repeat(MAX_LENGTH + 1) + "\n3");

        s.assertRefused(List.of("x = 1", "", "x + 1"), "the second pasted line is one character too long");
    }

    @Test
    void aLineMadeTooLongByTheTextBeforeTheCaretIsRefused() throws Exception {
        Session s = session(List.of("x = 1", "1 + "));
        s.activate(1);

        s.paste(1, 4, 4, "2".repeat(MAX_LENGTH - 3) + "\n3");

        s.assertRefused(List.of("x = 1", "1 + "), "« 1 + » and the first part make a line one character too long");
    }

    @Test
    void aLineMadeTooLongByTheTextAfterTheCaretIsRefused() throws Exception {
        Session s = session(List.of("x = 1", "1 + 2"));
        s.activate(1);

        s.paste(1, 4, 4, "3\n" + "4".repeat(MAX_LENGTH));

        s.assertRefused(List.of("x = 1", "1 + 2"), "the last part and « 2 » make a line one character too long");
    }

    @Test
    void aPastedLineOfExactlyTheMaximumLengthIsAccepted() throws Exception {
        Session s = session(List.of("x = 1"));
        s.activate(1);
        String longest = "1" + " ".repeat(MAX_LENGTH - 1);

        s.paste(1, 0, 0, "2\n" + longest);

        assertEquals(List.of("x = 1", "2", longest), s.savedTexts(), "a line of " + MAX_LENGTH + " characters is allowed");
        assertFalse(s.view().statusMessage().startsWith(REFUSED), s.view().statusMessage());
    }

    // ================================================================================================ the window

    @Test
    @Tag("ui")
    void ctrlVWithSeveralLinesOnTheClipboardPastesThemAsLinesAndCtrlZCtrlYUndoAndRedoIt() throws Exception {
        requireDisplay();
        requireThePaste();
        NotebookFrame frame = window(List.of("x = 1", "", "x + 1"));
        activate(frame, 1);

        withClipboard("1\r\n2\r\n3\r\n", () -> press(frame, 1, KeyEvent.VK_V, menuMask()));
        awaitSettled(frame);

        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), windowTexts(frame), "Ctrl+V pastes three lines into line 2");
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1", ""), fieldTexts(frame), "each field shows its line");

        press(frame, activeRow(frame), KeyEvent.VK_Z, menuMask());
        awaitSettled(frame);
        assertEquals(List.of("x = 1", "", "x + 1"), windowTexts(frame), "one Ctrl+Z takes the whole paste back");
        assertEquals(List.of("x = 1", "", "x + 1", ""), fieldTexts(frame));

        press(frame, activeRow(frame), KeyEvent.VK_Y, menuMask());
        awaitSettled(frame);
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1"), windowTexts(frame), "Ctrl+Y pastes it again");
        assertEquals(List.of("x = 1", "1", "2", "3", "x + 1", ""), fieldTexts(frame));
    }

    @Test
    @Tag("ui")
    void theFieldHandsAMultiLinePasteAtItsCaretToThePresenter() throws Exception {
        requireDisplay();
        requireThePaste();
        NotebookFrame frame = window(List.of("b = 5", "a = b"));
        activate(frame, 1);
        select(frame, 1, 4, 4);

        importInto(frame, 1, "1 +\n2\n3 * ");
        awaitSettled(frame);

        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b"), windowTexts(frame));
        assertEquals(List.of("b = 5", "a = 1 +", "2", "3 * b", ""), fieldTexts(frame));
    }

    @Test
    @Tag("ui")
    void aPasteWithoutALineBreakIsInsertedInTheFieldAsBefore() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("b = 5", "a = b"));
        activate(frame, 1);
        boolean couldUndo = onEdt(() -> frame.shown().canUndo());
        select(frame, 1, 4, 4);

        assertTrue(importInto(frame, 1, "2 * "), "the field takes the text");
        awaitSettled(frame);

        assertEquals("a = 2 * b", onEdt(() -> frame.row(1).field.getText()), "the text goes in at the caret");
        assertEquals(8, (int) onEdt(() -> frame.row(1).field.getCaretPosition()), "the caret follows the pasted text");
        assertEquals(List.of("b = 5", "a = b"), windowTexts(frame), "nothing is validated");
        assertEquals("a = 2 * b", onEdt(() -> frame.shown().activeDraft()), "the presenter keeps it as the draft");
        assertEquals(couldUndo, (boolean) onEdt(() -> frame.shown().canUndo()), "no command of the model");

        select(frame, 1, 4, 5);
        assertTrue(importInto(frame, 1, "7"));
        awaitSettled(frame);
        assertEquals("a = 7 * b", onEdt(() -> frame.row(1).field.getText()), "a selection is replaced");

        press(frame, 1, KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);
        assertEquals(List.of("b = 5", "a = 7 * b"), windowTexts(frame), "Enter validates it");
    }

    @Test
    @Tag("ui")
    void aRefusedPasteLeavesTheWindowAsItWasAndTheStatusBarSaysSo() throws Exception {
        requireDisplay();
        requireThePaste();
        NotebookFrame frame = window(List.of("x = 1", "", "x + 1"));
        activate(frame, 1);

        importInto(frame, 1, "1\n" + "9".repeat(MAX_LENGTH + 1));
        awaitSettled(frame);

        assertEquals(List.of("x = 1", "", "x + 1"), windowTexts(frame), "nothing of the paste is kept");
        assertEquals(List.of("x = 1", "", "x + 1", ""), fieldTexts(frame), "no field holds a truncated line");
        String status = textOf(onEdt(() -> part(frame, "statusLabel")));
        assertTrue(status.startsWith(REFUSED), "the status bar says « " + status + " »");
    }

    // ================================================================================================ the presenter, driven

    // The view of one presenter: what the active field holds is the draft the presenter was last told.
    private final class Session {
        final NotebookStore store;
        final NotebookPresenter presenter;

        Session(NotebookStore store, NotebookPresenter presenter) {
            this.store = store;
            this.presenter = presenter;
        }

        NotebookViewModel view() throws Exception {
            return onEdt(presenter::currentView);
        }

        String draft() throws Exception {
            return view().activeDraft();
        }

        // Goes to the row at index (from 0) as the mouse does, with what the active field holds.
        void activate(int index) throws Exception {
            String pending = draft();
            onEdt(() -> presenter.onActivate(index, pending));
            awaitIdle();
            assertEquals(index, view().activeIndex(), "row " + (index + 1) + " is active");
        }

        // What the view does at each change of the text of the active field.
        void typing(String text) throws Exception {
            onEdt(() -> presenter.onDraftChanged(text));
            awaitIdle();
            assertEquals(text, draft());
        }

        void paste(int line, int start, int end, String text) throws Exception {
            onEdt(() -> invokePaste(presenter, line, start, end, text));
            awaitIdle();
        }

        void undo() throws Exception {
            String pending = draft();
            onEdt(() -> presenter.onUndo(pending));
            awaitIdle();
        }

        void redo() throws Exception {
            String pending = draft();
            onEdt(() -> presenter.onRedo(pending));
            awaitIdle();
        }

        void awaitIdle() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
            while (onEdt(() -> presenter.isCalculating() || presenter.isPreviewing())) {
                if (System.nanoTime() > deadline) {
                    fail("the notebook is still being calculated after " + PATIENCE_SECONDS + " s");
                }
                Thread.sleep(5);
            }
            flushEvents();
        }

        // Every row, the new line at the end included.
        List<String> rowTexts() throws Exception {
            return view().rows().stream().map(NotebookRow::text).toList();
        }

        // The lines of the notebook: every row but the new line at the end.
        List<String> texts() throws Exception {
            List<String> rows = rowTexts();
            return rows.subList(0, rows.size() - 1);
        }

        // The results of the lines of the notebook.
        List<String> results() throws Exception {
            List<String> rows = view().rows().stream().map(NotebookRow::result).toList();
            return rows.subList(0, rows.size() - 1);
        }

        List<String> savedTexts() throws Exception {
            return NotebookCodec.read(store.currentFile()).notebook().texts();
        }

        void assertRefused(List<String> before, String what) throws Exception {
            NotebookViewModel view = view();
            assertTrue(view.statusMessage().startsWith(REFUSED), what + ": the status bar says « " + view.statusMessage() + " »");
            List<String> texts = texts();
            assertEquals(before.size(), texts.size(), what + ": no line is added nor removed");
            assertEquals(before, texts, what + ": the notebook is unchanged");
            assertEquals(before, savedTexts(), what + ": nothing is written");
            assertFalse(view.canUndo(), what + ": the refused paste is no command");
        }
    }

    private Session session(List<String> lines) throws Exception {
        NotebookStore store = store(lines);
        NotebookPresenter presenter = onEdt(() -> NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling()));
        Session session = new Session(store, presenter);
        session.awaitIdle();
        return session;
    }

    private NotebookStore store(List<String> lines) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        return store;
    }

    private static List<String> ones(int count) {
        return Collections.nCopies(count, "1");
    }

    private static String shown(String text) {
        return text.replace("\r", "\\r").replace("\n", "\\n");
    }

    // ================================================================================================ the new API, by reflection

    private static Method pasteMethod() {
        try {
            Method method = NotebookPresenter.class.getDeclaredMethod("onPaste", int.class, int.class, int.class, String.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("NotebookPresenter.onPaste(int ligne, int débutSélection, int finSélection, String texte) "
                    + "does not exist yet (task E17, SPEC §4.2 Collage)", missing);
        }
    }

    private static void requireThePaste() {
        pasteMethod();
    }

    private static Object invokePaste(NotebookPresenter presenter, int line, int start, int end, String text) {
        try {
            return pasteMethod().invoke(presenter, line, start, end, text);
        } catch (IllegalAccessException denied) {
            throw new AssertionError(denied);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        }
    }

    // ================================================================================================ the window, driven

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    // A window as Main builds it, in the light theme, on a store holding lines, laid out once every line is calculated.
    private NotebookFrame window(List<String> lines) throws Exception {
        NotebookStore store = store(lines);
        NotebookFrame frame = onEdt(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            NotebookPresenter presenter = NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling());
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

    private static int activeRow(NotebookFrame frame) throws Exception {
        return onEdt(() -> frame.shown().activeIndex());
    }

    private static void select(NotebookFrame frame, int row, int start, int end) throws Exception {
        onEdt(() -> {
            JTextComponent field = frame.row(row).field;
            field.setCaretPosition(start);
            field.moveCaretPosition(end);
            return null;
        });
        flushEvents();
    }

    // What Ctrl+V does with the clipboard: the TransferHandler of the field imports it at the selection.
    private static boolean importInto(NotebookFrame frame, int row, String text) throws Exception {
        boolean imported = onEdt(() -> {
            JTextComponent field = frame.row(row).field;
            TransferHandler handler = field.getTransferHandler();
            assertNotNull(handler, "the field of line " + (row + 1) + " has a TransferHandler");
            return handler.importData(new TransferHandler.TransferSupport(field, new StringSelection(text)));
        });
        flushEvents();
        return imported;
    }

    // Runs the action the field of the row binds to the key, as Swing does when the key is pressed in it.
    private static void press(NotebookFrame frame, int row, int keyCode, int modifiers) throws Exception {
        onEdt(() -> {
            JTextComponent field = frame.row(row).field;
            KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, modifiers);
            Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertNotNull(key, "nothing is bound to " + stroke);
            Action action = field.getActionMap().get(key);
            assertNotNull(action, "no action " + key);
            action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key.toString()));
            return null;
        });
        flushEvents();
    }

    private static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    private interface Step {
        void run() throws Exception;
    }

    // Puts text on the system clipboard for the step, then puts back what it held.
    private static void withClipboard(String text, Step step) throws Exception {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable before = clipboardCall(() -> clipboard.getContents(null));
        String beforeText = null;
        if (before != null && before.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            try {
                beforeText = (String) before.getTransferData(DataFlavor.stringFlavor);
            } catch (Exception unreadable) {
                beforeText = null;
            }
        }
        try {
            clipboardCall(() -> {
                clipboard.setContents(new StringSelection(text), null);
                return null;
            });
            step.run();
        } finally {
            Transferable restored = beforeText != null ? new StringSelection(beforeText) : before;
            if (restored != null) {
                try {
                    clipboardCall(() -> {
                        clipboard.setContents(restored, null);
                        return null;
                    });
                } catch (Exception | AssertionError lost) {
                    // The clipboard of the machine is a convenience of the user: the test does not fail on it.
                }
            }
        }
    }

    // Another program may hold the clipboard for a moment: tries again until the deadline.
    private static <T> T clipboardCall(Callable<T> call) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                return call.call();
            } catch (IllegalStateException busy) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the system clipboard stays unavailable", busy);
                }
                Thread.sleep(20);
            }
        }
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

    // The lines of the notebook the window shows: every row but the new line at the end.
    private static List<String> windowTexts(NotebookFrame frame) throws Exception {
        List<String> rows = onEdt(() -> frame.shown().rows().stream().map(NotebookRow::text).toList());
        return rows.subList(0, rows.size() - 1);
    }

    // What the field of every row holds.
    private static List<String> fieldTexts(NotebookFrame frame) throws Exception {
        return onEdt(() -> IntStream.range(0, frame.rowCount()).mapToObj(index -> frame.row(index).field.getText()).toList());
    }

    // The one component of the window with this name.
    private static Component part(Container root, String name) {
        List<Component> found = descendants(root).stream().filter(each -> name.equals(each.getName())).toList();
        assertEquals(1, found.size(), "the window has one component named " + name + ", has " + found.size());
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
            assertTrue(component instanceof JLabel, component.getName() + " is a label");
            String text = ((JLabel) component).getText();
            return text == null ? "" : text;
        });
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
