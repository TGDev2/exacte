package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.notebook.Source;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Task E11: the empty notebook offers three example notebooks as buttons, and Help > Syntaxe des expressions opens a window
 * that is not modal, lists examples and inserts each one below the current line. SPEC §4.2 (Découverte).
 *
 * <p>The contract this class fixes, and the names it relies on:
 * <ul>
 *   <li>{@code calc.ui.notebook.NotebookExamples} has a static constant for each of {@code DEVIS}, {@code RACINE} and
 *       {@code REFERENCES} (enum constants or fields). Each one gives its lines: it is a {@code List<String>}, or it has a
 *       method {@code lines()} (or {@code getLines()}) that returns one. It is reached by reflection, so that this class
 *       compiles before the task and fails with a clear message.</li>
 *   <li>With no line written the window holds three buttons named {@code exampleButtonDEVIS}, {@code exampleButtonRACINE} and
 *       {@code exampleButtonREFERENCES}, of texts « Devis », « Racine de 2 approchée » and « Références $n », that the Tab key
 *       reaches. Once a line is written they are not displayed.</li>
 *   <li>A click on one replaces the blank line by the lines of the example, in one action that a single undo takes back (a
 *       single redo does again); the first line of the example becomes the active line.</li>
 *   <li>Help > Syntaxe des expressions (menu item {@code syntaxItem}) opens a {@link Dialog} named {@code syntaxHelp} that is
 *       not modal. It holds one button « Insérer » named {@code insertExampleButton<n>} per example; it adds the lines of the
 *       example below the current line, in one action that a single undo takes back, and the help stays open.</li>
 * </ul>
 *
 * <p>A component is « displayed » when it and each of its parents up to the first {@link Window} (excluded) are visible and it
 * has a size: a window of these tests is never shown on screen, so the test never asks a window it did not show to be visible.
 * No modal dialog is ever opened by the test: the menu item is clicked from a queued task, and a modal window (the help as it
 * is today) is disposed and reported. Every wait has a deadline.
 */
@Tag("acceptance")
final class ExamplesAndHelpAcceptanceTest {
    private static final long PATIENCE_SECONDS = 60;
    private static final long HELP_SECONDS = 15;
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final String HELP_NAME = "syntaxHelp";
    private static final Pattern INSERT_BUTTON = Pattern.compile("insertExampleButton(\\d+)");
    private static final Pattern LINE_REFERENCE = Pattern.compile("\\$\\d+");
    private static final Map<String, String> BUTTON_TEXTS = Map.of(
            "DEVIS", "Devis",
            "RACINE", "Racine de 2 approchée",
            "REFERENCES", "Références $n");
    private static final List<String> NAMES = List.of("DEVIS", "RACINE", "REFERENCES");

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
                for (Window each : Window.getWindows()) {
                    if (HELP_NAME.equals(each.getName())) {
                        each.dispose();
                    }
                }
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

    // ================================================================================================ the three notebooks

    @Test
    void devisIsTheEightLinesOfTheDevisCuisineExampleOfTheReadme() throws Exception {
        List<String> readme = readmeQuote();

        assertEquals(8, readme.size(), "the README example has eight lines: " + readme);
        assertEquals(readme, linesOf("DEVIS"), "NotebookExamples.DEVIS is the Devis cuisine example of the README, line for line");
    }

    @Test
    void racineHoldsAnApproximateSquareRootOfTwo() throws Exception {
        EvaluatedNotebook evaluated = evaluate(linesOf("RACINE"));

        List<String> approximate = new ArrayList<>();
        for (LineResult result : evaluated.results()) {
            if (result.hasValue()) {
                FormattedValue shown = ValueFormatter.format(result.value(), DisplayMode.AUTO);
                if (!shown.exact()) {
                    approximate.add(result.text() + " -> " + shown.text());
                }
            }
        }
        assertFalse(approximate.isEmpty(), "RACINE has no line whose value is approximate: " + linesOf("RACINE"));
        assertTrue(approximate.stream().anyMatch(each -> each.endsWith("-> ≈ 1,41421356237…")),
                "the approximate line is the square root of 2: " + approximate);
    }

    @Test
    void referencesHoldsAtLeastTwoLinesThatUseALineReference() throws Exception {
        List<String> lines = linesOf("REFERENCES");
        EvaluatedNotebook evaluated = evaluate(lines);

        long typed = lines.stream().filter(line -> LINE_REFERENCE.matcher(typedPart(line)).find()).count();
        assertTrue(typed >= 2, "at least two lines write a $n reference: " + lines);
        long read = evaluated.results().stream()
                .filter(result -> result.sources().stream().anyMatch(source -> source.kind() == Source.Kind.LINE_REFERENCE))
                .count();
        assertTrue(read >= 2, "at least two lines have a $n reference as a source once evaluated: " + lines);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVIS", "RACINE", "REFERENCES"})
    void noLineOfTheExampleNotebooksIsInError(String name) throws Exception {
        List<String> lines = linesOf(name);

        assertFalse(lines.isEmpty(), name + " has lines");
        assertTrue(lines.stream().anyMatch(line -> !typedPart(line).isBlank()), name + " has a line that computes: " + lines);
        for (String line : lines) {
            assertTrue(line.length() <= NotebookPresenter.MAX_LINE_LENGTH, name + ": a line is too long: " + line);
            assertFalse(line.contains("\n") || line.contains("\r"), name + ": a line holds a line break");
        }
        for (LineResult result : evaluate(lines).results()) {
            assertFalse(result.isError(), name + ", line " + result.number() + " « " + result.text() + " » is in error: "
                    + result.failure().map(error -> error.message()).orElse(""));
        }
    }

    // ================================================================================================ the empty notebook

    @Test
    @Tag("ui")
    void anEmptyNotebookShowsTheThreeExampleButtonsWithTheirTexts() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of());

        for (String name : NAMES) {
            AbstractButton button = exampleButton(frame, name);
            assertTrue(displayed(button), "exampleButton" + name + " is displayed in the empty notebook");
            assertEquals(BUTTON_TEXTS.get(name), onEdt(button::getText), "the text of exampleButton" + name);
            assertTrue(onEdt(button::isEnabled), "exampleButton" + name + " is enabled");
        }
    }

    @Test
    @Tag("ui")
    void theExampleButtonsAreReachedWithTheTabKey() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of());

        for (String name : NAMES) {
            AbstractButton button = exampleButton(frame, name);
            assertTrue(onEdt(button::isFocusable), "exampleButton" + name + " can take the focus");
            assertTrue(onEdt(button::isEnabled), "exampleButton" + name + " is enabled");
            assertTrue(onEdt(button::getFocusTraversalKeysEnabled), "Tab traverses exampleButton" + name);
            assertTrue(reachedByTab(frame, button), "exampleButton" + name + " is in the focus cycle of the window, so Tab reaches it");
        }
    }

    @Test
    @Tag("ui")
    void theExampleButtonsAreNotDisplayedOnceALineIsWritten() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of());
        assertTrue(displayed(exampleButton(frame, "DEVIS")), "they are there to begin with");

        onEdt(() -> {
            frame.row(0).field.setText("1 + 1");
            return null;
        });
        press(frame, activeRow(frame), KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);

        assertEquals(List.of("1 + 1"), windowTexts(frame), "a line is written");
        for (String name : NAMES) {
            assertFalse(displayedByName(frame, "exampleButton" + name), "exampleButton" + name + " is gone once a line is written");
        }
    }

    @Test
    @Tag("ui")
    void aNotebookThatHasLinesNeverShowsTheExampleButtons() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("a = 2", "a * 3"));

        for (String name : NAMES) {
            assertFalse(displayedByName(frame, "exampleButton" + name), "exampleButton" + name + " is not displayed in a notebook with lines");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEVIS", "RACINE", "REFERENCES"})
    @Tag("ui")
    void clickingAnExampleInsertsItsLinesInOneUndoableActionAndTheFirstLineBecomesActive(String name) throws Exception {
        requireDisplay();
        List<String> lines = linesOf(name);
        NotebookFrame frame = window(List.of());
        assertFalse(onEdt(() -> frame.shown().canUndo()), "a notebook just opened has nothing to undo");

        click(frame, exampleButton(frame, name));

        assertEquals(lines, windowTexts(frame), "the lines of " + name + " are the lines of the notebook");
        assertEquals(0, activeRow(frame), "the first line of the example is the active line");
        assertEquals(lines.get(0), onEdt(() -> frame.shown().rows().get(0).text()));
        assertTrue(onEdt(() -> frame.shown().canUndo()), "the insertion can be undone");
        for (NotebookRow row : onEdt(() -> frame.shown().rows())) {
            assertNotEquals(NotebookRow.Status.ERROR, row.status(), "line " + row.number() + " « " + row.text() + " » is in error: "
                    + row.errorMessage());
            assertNotEquals(NotebookRow.Status.PENDING, row.status(), "line " + row.number() + " is calculated");
        }

        press(frame, activeRow(frame), KeyEvent.VK_Z, menuMask());
        awaitSettled(frame);
        assertEquals(List.of(), windowTexts(frame), "one Ctrl+Z gives the empty notebook back");
        assertFalse(onEdt(() -> frame.shown().canUndo()), "the insertion was one action: nothing is left to undo");
        for (String each : NAMES) {
            assertTrue(displayed(exampleButton(frame, each)), "exampleButton" + each + " is back in the empty notebook");
        }

        press(frame, activeRow(frame), KeyEvent.VK_Y, menuMask());
        awaitSettled(frame);
        assertEquals(lines, windowTexts(frame), "one Ctrl+Y inserts the example again");
    }

    // ================================================================================================ the help

    @Test
    @Tag("ui")
    void theSyntaxHelpIsAWindowThatIsNotModalAndHoldsAnInsertButtonPerExample() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(List.of("base = 1", "base + 1"));

        Dialog help = openSyntaxHelp(frame);

        assertFalse(onEdt(help::isModal), "the help does not block the notebook");
        assertTrue(onEdt(help::isVisible), "the help is open");
        List<AbstractButton> buttons = insertButtons(help);
        assertFalse(buttons.isEmpty(), "the help lists examples, each with an insertExampleButton<n>");
        Set<String> names = new LinkedHashSet<>();
        for (AbstractButton button : buttons) {
            assertTrue(names.add(button.getName()), "the button " + button.getName() + " is the only one of its name");
            assertEquals("Insérer", onEdt(button::getText), "the text of " + button.getName());
            assertTrue(displayed(button), button.getName() + " is displayed in the help");
            assertTrue(onEdt(button::isEnabled) && onEdt(button::isFocusable), button.getName() + " can be used from the keyboard");
        }
    }

    @Test
    @Tag("ui")
    void insertingAnExampleOfTheHelpAddsItsLinesBelowTheCurrentLineInOneUndoableActionAndTheHelpStaysOpen() throws Exception {
        requireDisplay();
        List<String> base = List.of("base = 1", "base + 1");
        NotebookFrame frame = window(base);
        Dialog help = openSyntaxHelp(frame);
        List<String> buttonNames = insertButtons(help).stream().map(AbstractButton::getName).toList();
        assertFalse(buttonNames.isEmpty(), "the help lists examples, each with an insertExampleButton<n>");
        assertFalse(onEdt(() -> frame.shown().canUndo()), "a notebook just opened has nothing to undo");

        for (String buttonName : buttonNames) {
            activate(frame, 0);
            AbstractButton button = insertButton(help, buttonName);

            click(frame, button);

            List<String> after = windowTexts(frame);
            int added = after.size() - base.size();
            assertTrue(added >= 1, buttonName + " adds at least one line: " + after);
            List<String> inserted = after.subList(1, 1 + added);
            assertEquals(base.get(0), after.get(0), buttonName + ": the lines before the current line do not move");
            assertEquals(base.subList(1, base.size()), after.subList(1 + added, after.size()),
                    buttonName + ": the lines after the current line follow the example");
            assertTrue(inserted.stream().anyMatch(line -> !typedPart(line).isBlank()), buttonName + " inserts a line that computes: " + inserted);
            assertFalse(inserted.stream().anyMatch(line -> line.contains("\n") || line.contains("\r")), buttonName + ": a line break");
            for (LineResult result : evaluate(inserted).results()) {
                assertFalse(result.isError(), buttonName + " alone: « " + result.text() + " » is in error: "
                        + result.failure().map(error -> error.message()).orElse(""));
            }
            List<NotebookRow> rows = onEdt(() -> frame.shown().rows());
            for (int index = 1; index <= added; index++) {
                NotebookRow row = rows.get(index);
                assertNotEquals(NotebookRow.Status.ERROR, row.status(), buttonName + " in the notebook: « " + row.text()
                        + " » is in error: " + row.errorMessage());
            }
            assertTrue(onEdt(() -> frame.shown().canUndo()), buttonName + ": the insertion can be undone");
            assertTrue(onEdt(help::isVisible) && onEdt(help::isDisplayable), buttonName + ": the help stays open");

            press(frame, activeRow(frame), KeyEvent.VK_Z, menuMask());
            awaitSettled(frame);
            assertEquals(base, windowTexts(frame), buttonName + ": one Ctrl+Z takes the whole insertion back");
            assertFalse(onEdt(() -> frame.shown().canUndo()), buttonName + ": the insertion was one action");

            press(frame, activeRow(frame), KeyEvent.VK_Y, menuMask());
            awaitSettled(frame);
            assertEquals(after, windowTexts(frame), buttonName + ": one Ctrl+Y inserts it again");

            press(frame, activeRow(frame), KeyEvent.VK_Z, menuMask());
            awaitSettled(frame);
            assertEquals(base, windowTexts(frame), buttonName + ": undone again, ready for the next example");
        }
        assertTrue(onEdt(help::isVisible), "the help is still open after every insertion");
    }

    // ================================================================================================ the examples, by reflection

    private static Class<?> examplesClass() {
        try {
            return Class.forName("calc.ui.notebook.NotebookExamples");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("calc.ui.notebook.NotebookExamples does not exist yet (task E11, SPEC §4.2 Découverte)", missing);
        }
    }

    // The lines of NotebookExamples.<name>: the constant is a list of lines, or has lines() (or getLines()).
    private static List<String> linesOf(String name) throws Exception {
        Class<?> type = examplesClass();
        Object value;
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            value = field.get(null);
        } catch (NoSuchFieldException missing) {
            throw new AssertionError("NotebookExamples." + name + " does not exist yet (task E11): a static constant of the class", missing);
        }
        assertNotNull(value, "NotebookExamples." + name);
        if (!(value instanceof List<?>)) {
            for (String accessor : List.of("lines", "getLines")) {
                try {
                    Method method = value.getClass().getMethod(accessor);
                    method.setAccessible(true);
                    value = method.invoke(value);
                    break;
                } catch (NoSuchMethodException other) {
                    // Tries the next name.
                } catch (InvocationTargetException thrown) {
                    throw new AssertionError("NotebookExamples." + name + "." + accessor + "() failed", thrown.getCause());
                }
            }
        }
        if (!(value instanceof List<?> list)) {
            throw new AssertionError("NotebookExamples." + name + " must be a List<String> or have a lines() method that returns one");
        }
        List<String> lines = new ArrayList<>();
        for (Object each : list) {
            assertTrue(each instanceof String, "NotebookExamples." + name + " holds lines of text: " + each);
            lines.add((String) each);
        }
        return lines;
    }

    private static EvaluatedNotebook evaluate(List<String> lines) throws Exception {
        return NotebookEvaluator.evaluate(Notebook.of(lines));
    }

    // The line without its comment, as the language reads it.
    private static String typedPart(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    // The Devis cuisine block of the README, as ReadmeExamplesTest reads it: the first text block of the examples section.
    private static List<String> readmeQuote() throws IOException {
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        int section = readme.indexOf("## Exemples de carnet");
        assertTrue(section >= 0, "the README has a section « Exemples de carnet »");
        int start = readme.indexOf("```text", section);
        assertTrue(start >= 0, "the section has a text block");
        start += "```text".length();
        int stop = readme.indexOf("```", start);
        return readme.substring(start, stop).strip().lines().toList();
    }

    // ================================================================================================ the window, driven

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    // A window as Main builds it, in the light theme, on a store holding lines, laid out once every line is calculated. It is
    // never shown: the tests ask a component to be displayed (see the class comment), never a window to be visible.
    private NotebookFrame window(List<String> lines) throws Exception {
        NotebookStore store = NotebookStore.open(java.nio.file.Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
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
        assertEquals(index, activeRow(frame), "line " + (index + 1) + " is active");
    }

    private static int activeRow(NotebookFrame frame) throws Exception {
        return onEdt(() -> frame.shown().activeIndex());
    }

    // A click of the mouse on a button.
    private static void click(NotebookFrame frame, AbstractButton button) throws Exception {
        onEdt(() -> {
            button.doClick(0);
            return null;
        });
        awaitSettled(frame);
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

    // ================================================================================================ components

    // The one button of the window with this name; the test fails when the window has none or several.
    private static AbstractButton exampleButton(NotebookFrame frame, String name) throws Exception {
        String full = "exampleButton" + name;
        List<Component> found = onEdt(() -> named(frame, full));
        assertEquals(1, found.size(), "the window has one component named " + full + ", has " + found.size()
                + " (the empty notebook shows three example buttons)");
        assertTrue(found.get(0) instanceof AbstractButton, full + " is a button");
        return (AbstractButton) found.get(0);
    }

    private static boolean displayedByName(Container root, String name) throws Exception {
        return onEdt(() -> named(root, name)).stream().anyMatch(ExamplesAndHelpAcceptanceTest::displayedNow);
    }

    private static boolean displayed(Component component) throws Exception {
        return onEdt(() -> displayedNow(component));
    }

    // Visible itself and through each parent up to the first window (excluded), with a size.
    private static boolean displayedNow(Component component) {
        if (component.getWidth() <= 0 || component.getHeight() <= 0) {
            return false;
        }
        for (Component each = component; each != null && !(each instanceof Window); each = each.getParent()) {
            if (!each.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static List<Component> named(Container root, String name) {
        return descendants(root).stream().filter(each -> name.equals(each.getName())).toList();
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

    // Whether Tab reaches the button: it can take the focus, is enabled, lets Tab traverse it, is displayed, and sits in the focus
    // cycle of the window itself (no cycle root of its own between them). The traversal policy of a window that is not shown
    // finds no component at all, so the test reads these conditions instead of walking the policy.
    private static boolean reachedByTab(NotebookFrame frame, AbstractButton button) throws Exception {
        return onEdt(() -> button.isFocusable() && button.isEnabled() && button.getFocusTraversalKeysEnabled()
                && displayedNow(button) && button.getFocusCycleRootAncestor() == frame);
    }

    // ================================================================================================ the help, driven

    private static JMenuItem menuItem(NotebookFrame frame, String name) {
        Deque<Component> pending = new ArrayDeque<>(List.of(frame.getJMenuBar().getComponents()));
        while (!pending.isEmpty()) {
            Component each = pending.pop();
            if (each instanceof JMenu menu) {
                pending.addAll(List.of(menu.getMenuComponents()));
            } else if (each instanceof JMenuItem item && name.equals(item.getName())) {
                return item;
            }
        }
        return null;
    }

    private static Window helpWindow() {
        for (Window each : Window.getWindows()) {
            if (HELP_NAME.equals(each.getName())) {
                return each;
            }
        }
        return null;
    }

    private static void disposeShowingWindows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window each : Window.getWindows()) {
                if (each.isShowing()) {
                    each.dispose();
                }
            }
        });
    }

    // Aide > Syntaxe des expressions. The click is queued, never run here: a modal dialog (the help as it was before the task)
    // would hold the event thread for good. The test waits for the window named syntaxHelp until a deadline; when it does not
    // come, whatever is showing is disposed (which frees the event thread) and the test fails.
    private static Dialog openSyntaxHelp(NotebookFrame frame) throws Exception {
        JMenuItem item = onEdt(() -> menuItem(frame, "syntaxItem"));
        assertNotNull(item, "the Help menu has the item syntaxItem");
        SwingUtilities.invokeLater(item::doClick);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HELP_SECONDS);
        while (true) {
            Window found = helpWindow();
            if (found instanceof Dialog dialog && onEdt(dialog::isVisible) && !insertButtons(dialog).isEmpty()) {
                flushEvents();
                return dialog;
            }
            if (System.nanoTime() > deadline) {
                if (found instanceof Dialog dialog) {
                    return dialog;
                }
                disposeShowingWindows();
                if (found != null) {
                    fail("the help window named " + HELP_NAME + " is not a java.awt.Dialog: " + found.getClass().getName());
                }
                fail("Aide > Syntaxe des expressions did not open a window named " + HELP_NAME
                        + " within " + HELP_SECONDS + " s: a modal dialog is not what the task asks for");
            }
            Thread.sleep(20);
        }
    }

    // The insert buttons of the help, in the order of their number.
    private static List<AbstractButton> insertButtons(Dialog help) throws Exception {
        return onEdt(() -> {
            List<AbstractButton> buttons = new ArrayList<>();
            for (Component each : descendants(help)) {
                if (each instanceof AbstractButton button && each.getName() != null && INSERT_BUTTON.matcher(each.getName()).matches()) {
                    buttons.add(button);
                }
            }
            buttons.sort(Comparator.comparingInt(button -> {
                java.util.regex.Matcher matcher = INSERT_BUTTON.matcher(button.getName());
                return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
            }));
            return buttons;
        });
    }

    private static AbstractButton insertButton(Dialog help, String name) throws Exception {
        List<AbstractButton> found = insertButtons(help).stream().filter(button -> name.equals(button.getName())).toList();
        assertEquals(1, found.size(), "the help has one button named " + name);
        return found.get(0);
    }

    // ================================================================================================ the event thread

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
