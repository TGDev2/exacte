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
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import calc.ui.theme.ThemeManager;
import calc.ui.widget.Contrast;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.MenuElement;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Acceptance of E12: the status bar is redesigned (SPEC 4.2 « Barre d'état »). The window of this test is the real one, as
 * {@code Main} builds it: the real store on a temporary directory, the real presenter calculating on its own thread,
 * {@code new NotebookFrame}, the theme applied by {@link ThemeManager}; it is never shown and no dialog is ever opened
 * ({@link FakeDialogs}).
 *
 * <p>What this test reads in the window, all of it by name:
 * <ul>
 *   <li>four {@link JLabel}s named {@code statusLabel}, {@code saveStateLabel}, {@code lineCountLabel} and
 *       {@code summaryLabel}, which share one bar (a container of the window that holds no notebook row, laid out under the
 *       scroll pane {@code notebookScroll}) whose background is the role {@code surface.raised};</li>
 *   <li>their texts and their accessible names, no more: how the presenter hands the texts to the window is the choice of the
 *       implementation.</li>
 * </ul>
 * The menu items are the ones of the File menu ({@code openItem}, {@code saveItem}, {@code saveAsItem}, {@code newItem}); the
 * keys are the actions the active field binds, as {@code NotebookFrameFilesTest} presses them.
 */
@Tag("ui")
@Tag("acceptance")
@DisplayName("Barre d'état redessinée : état d'enregistrement, nombre de lignes, résumé, progression (nécessite un affichage)")
class StatusBarAcceptanceTest {

    private static final String NO_BREAK_SPACE = String.valueOf((char) 0x00A0);

    /** The "Devis cuisine" of the README: one title line and seven exact lines. */
    private static final List<String> DEVIS = List.of(
            "# Devis cuisine",
            "plan = 1" + NO_BREAK_SPACE + "250,00          # plan de travail (espace insécable)",
            "meubles = 3" + NO_BREAK_SPACE + "480",
            "pose = 35 * 12,5",
            "total = plan + meubles + pose     # 5 167,5",
            "taux = 0,2",
            "total * (1 + taux)       # TTC : 6 201",
            "ans / 12                 # mensualité sur un an : 516,75");

    private static final List<String> WITH_EVERY_KIND = Stream.concat(DEVIS.stream(),
            Stream.of("√2", "", "total - remise", "  # remarque")).toList();

    private static final String SAVE_STATE = "saveStateLabel";
    private static final String LINE_COUNT = "lineCountLabel";
    private static final String SUMMARY = "summaryLabel";
    private static final String STATUS = "statusLabel";
    private static final List<String> LABELS = List.of(STATUS, SAVE_STATE, LINE_COUNT, SUMMARY);

    /** « Calcul en cours : n / N lignes — Échap pour interrompre », with the em dash. */
    private static final Pattern PROGRESS = Pattern.compile("Calcul en cours : (\\d+) / (\\d+) lignes — Échap pour interrompre");
    private static final String SURFACE = "surface.raised";

    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final long PATIENCE_SECONDS = 20;
    // Longer than any wait of the test, so that a missing feature fails on its own assertion, not on the held line.
    private static final long GATE_PATIENCE_SECONDS = 90;

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private final FakeDialogs dialogs = new FakeDialogs();
    private final List<String> evaluated = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch reached = new CountDownLatch(1);
    private final CountDownLatch released = new CountDownLatch(1);
    private volatile String gatedLine;
    private LookAndFeel original;
    private Path documents;

    @BeforeEach
    void start() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
        documents = Files.createDirectories(temp.resolve("documents"));
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
        released.countDown();
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

    // ================================================================================================ the bar

    @Test
    @DisplayName("la barre contient statusLabel, saveStateLabel, lineCountLabel et summaryLabel, sous le carnet")
    void theBarHoldsTheFourLabelsUnderTheNotebook() throws Exception {
        NotebookFrame frame = window(DEVIS, Theme.LIGHT);

        List<JLabel> labels = new ArrayList<>();
        for (String name : LABELS) {
            labels.add(label(frame, name));
        }
        assertEquals(4, Set.copyOf(labels).size(), "four labels, not one under four names");
        Container bar = barOf(frame, labels);
        assertNotEquals(frame.getContentPane(), bar, "the labels share a bar, not the whole content of the window");
        assertTrue(onEdt(() -> {
            Component scroll = find(frame.getRootPane(), "notebookScroll");
            assertNotNull(scroll, "the scroll pane notebookScroll");
            assertFalse(SwingUtilities.isDescendingFrom(scroll, bar), "the bar holds no notebook row");
            Rectangle barBounds = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), frame.getContentPane());
            Rectangle scrollBounds = SwingUtilities.convertRectangle(scroll.getParent(), scroll.getBounds(), frame.getContentPane());
            return barBounds.y >= scrollBounds.y + scrollBounds.height - 1 && barBounds.height > 0;
        }), "the bar is laid out under the notebook");
        for (JLabel label : labels) {
            assertTrue(onEdt(label::isVisible), label.getName() + " is visible");
        }
        assertEquals(NotebookPresenter.DEFAULT_STATUS, text(window(List.of(), Theme.LIGHT), STATUS),
                "statusLabel keeps the messages: the hint at rest");
    }

    @Test
    @DisplayName("statusLabel garde ses messages et leur priorité : l'erreur de lecture seule remplace l'aide")
    void theStatusLabelKeepsItsMessages() throws Exception {
        NotebookFrame frame = window(DEVIS, Theme.LIGHT);
        openFutureFile(frame);

        assertEquals(NotebookPresenter.READ_ONLY_FILE_STATUS, text(frame, STATUS));
    }

    // ================================================================================================ save state

    @Test
    @DisplayName("saveStateLabel : Enregistrement automatique, Enregistré, Modifié, puis de nouveau Enregistré")
    void theSaveStateFollowsTheLifeOfADocument() throws Exception {
        NotebookFrame frame = window(List.of("1 + 1"), Theme.LIGHT);
        assertShows(frame, SAVE_STATE, "Enregistrement automatique");

        activate(frame, frame.rowCount() - 1);
        typeLine(frame, "ans * 2");
        assertShows(frame, SAVE_STATE, "Enregistrement automatique");

        dialogs.willSaveTo(documents.resolve("devis"));
        click(frame, "saveAsItem");
        assertShows(frame, SAVE_STATE, "Enregistré");

        typeLine(frame, "ans * 3");
        assertShows(frame, SAVE_STATE, "Modifié");

        click(frame, "saveItem");
        assertShows(frame, SAVE_STATE, "Enregistré");

        click(frame, "newItem");
        assertShows(frame, SAVE_STATE, "Enregistrement automatique");
        dialogs.assertAllUsed();
    }

    @Test
    @DisplayName("saveStateLabel : un document ouvert sans modification est Enregistré")
    void anOpenedDocumentIsSaved() throws Exception {
        NotebookFrame frame = window(List.of("1 + 1"), Theme.LIGHT);
        Path file = documents.resolve("ouvert.calc.json");
        Files.write(file, NotebookCodec.encode(Notebook.of(List.of("a = 2", "a * 3")), DisplayMode.AUTO).bytes());

        dialogs.willOpen(file);
        click(frame, "openItem");

        assertShows(frame, SAVE_STATE, "Enregistré");
        dialogs.assertAllUsed();
    }

    @Test
    @DisplayName("saveStateLabel : un document d'une version future est en Lecture seule")
    void aFutureVersionDocumentIsReadOnly() throws Exception {
        NotebookFrame frame = window(DEVIS, Theme.LIGHT);

        openFutureFile(frame);

        assertShows(frame, SAVE_STATE, "Lecture seule");
    }

    // ================================================================================================ line count and summary

    static Stream<Arguments> notebooks() {
        return Stream.of(
                Arguments.of(List.of(), "0 ligne", "0 exacte, 0 approchée, 0 en erreur"),
                Arguments.of(List.of("1 + 1"), "1 ligne", "1 exacte, 0 approchée, 0 en erreur"),
                Arguments.of(List.of("1 + 1", "2 + 2"), "2 lignes", "2 exactes, 0 approchée, 0 en erreur"),
                Arguments.of(DEVIS, "8 lignes", "7 exactes, 0 approchée, 0 en erreur"),
                Arguments.of(List.of("√2"), "1 ligne", "0 exacte, 1 approchée, 0 en erreur"),
                Arguments.of(List.of("√2", "√3", "√5"), "3 lignes", "0 exacte, 3 approchées, 0 en erreur"),
                Arguments.of(List.of("1 / 0"), "1 ligne", "0 exacte, 0 approchée, 1 en erreur"),
                Arguments.of(List.of("1 / 0", "foo", "2 +"), "3 lignes", "0 exacte, 0 approchée, 3 en erreur"),
                Arguments.of(List.of("# titre", "", "  # remarque"), "3 lignes", "0 exacte, 0 approchée, 0 en erreur"),
                Arguments.of(WITH_EVERY_KIND, "12 lignes", "7 exactes, 1 approchée, 1 en erreur"));
    }

    @ParameterizedTest(name = "{0} -> « {1} », « {2} »")
    @MethodSource("notebooks")
    @DisplayName("lineCountLabel et summaryLabel : singulier pour 0 et 1, pluriel au-delà, commentaires et lignes vides non comptés")
    void theCountsAndTheSummaryOfAFinishedCalculation(List<String> lines, String count, String summary) throws Exception {
        NotebookFrame frame = window(lines, Theme.LIGHT);

        assertShows(frame, LINE_COUNT, count);
        assertShows(frame, SUMMARY, summary);
    }

    @Test
    @DisplayName("Devis cuisine : « 7 exactes, 0 approchée, 0 en erreur » sur « 8 lignes », puis les comptes suivent les lignes validées")
    void theCountsFollowTheLinesTheUserValidates() throws Exception {
        NotebookFrame frame = window(DEVIS, Theme.LIGHT);
        assertShows(frame, LINE_COUNT, "8 lignes");
        assertShows(frame, SUMMARY, "7 exactes, 0 approchée, 0 en erreur");

        activate(frame, frame.rowCount() - 1);
        typeLine(frame, "√2");
        assertShows(frame, LINE_COUNT, "9 lignes");
        assertShows(frame, SUMMARY, "7 exactes, 1 approchée, 0 en erreur");

        typeLine(frame, "1 / 0");
        assertShows(frame, LINE_COUNT, "10 lignes");
        assertShows(frame, SUMMARY, "7 exactes, 1 approchée, 1 en erreur");

        typeLine(frame, "# fin");
        assertShows(frame, LINE_COUNT, "11 lignes");
        assertShows(frame, SUMMARY, "7 exactes, 1 approchée, 1 en erreur");
    }

    // ================================================================================================ a calculation under way

    @Test
    @DisplayName("pendant un calcul : « Calcul en cours : n / N lignes — Échap pour interrompre », le résumé attend la fin")
    void theStatusSaysHowToInterruptAndTheSummaryWaitsForTheEnd() throws Exception {
        List<String> lines = List.of("x = 1", "x + 1", "x + 2", "x + 3", "x + 4", "1 / 0");
        NotebookFrame frame = window(List.of(), Theme.LIGHT);
        Path file = documents.resolve("budget.calc.json");
        Files.write(file, NotebookCodec.encode(Notebook.of(lines), DisplayMode.AUTO).bytes());
        gatedLine = "x + 3";

        dialogs.willOpen(file);
        onEdt(() -> {
            item("openItem", frame).doClick(0);
            return null;
        });
        assertTrue(reached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the calculation reached the line it is held on");

        assertShows(frame, STATUS, "Calcul en cours : 3 / 6 lignes — Échap pour interrompre");
        Matcher progress = PROGRESS.matcher(text(frame, STATUS));
        assertTrue(progress.matches(), text(frame, STATUS));
        assertEquals("6", progress.group(2));
        assertNotEquals("5 exactes, 0 approchée, 1 en erreur", text(frame, SUMMARY),
                "the summary is told once the calculation has ended, not line by line");

        released.countDown();
        awaitSettled(frame);
        assertShows(frame, SUMMARY, "5 exactes, 0 approchée, 1 en erreur");
        assertShows(frame, LINE_COUNT, "6 lignes");
        assertFalse(text(frame, STATUS).startsWith("Calcul en cours"), text(frame, STATUS));
    }

    @Test
    @DisplayName("Échap pendant un calcul : le message d'interruption s'affiche et le calcul s'arrête")
    void escapeInterruptsAndTheStatusSaysSo() throws Exception {
        List<String> lines = List.of("x = 1", "x + 1", "x + 2", "x + 3", "x + 4", "1 / 0");
        NotebookFrame frame = window(List.of(), Theme.LIGHT);
        Path file = documents.resolve("budget.calc.json");
        Files.write(file, NotebookCodec.encode(Notebook.of(lines), DisplayMode.AUTO).bytes());
        gatedLine = "x + 3";

        dialogs.willOpen(file);
        onEdt(() -> {
            item("openItem", frame).doClick(0);
            return null;
        });
        assertTrue(reached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the calculation reached the line it is held on");
        assertShows(frame, STATUS, "Calcul en cours : 3 / 6 lignes — Échap pour interrompre");

        pressInRow(frame, 0, KeyEvent.VK_ESCAPE);
        awaitCondition("the status bar says the calculation was interrupted", () -> text(frame, STATUS).contains("interrompu"));
        String interrupted = text(frame, STATUS);
        assertFalse(PROGRESS.matcher(interrupted).matches(), interrupted);
        assertFalse(interrupted.contains("Échap pour interrompre"), interrupted);

        released.countDown();
        awaitSettled(frame);
        assertFalse(evaluated.contains("x + 4"), "the calculation stopped at the end of its line: " + evaluated);
        assertFalse(evaluated.contains("1 / 0"), "the calculation stopped at the end of its line: " + evaluated);
        assertTrue(text(frame, STATUS).contains("interrompu"), "the message stays once the held line is over: " + text(frame, STATUS));
        assertFalse(text(frame, STATUS).startsWith("Calcul en cours"), text(frame, STATUS));
    }

    // ================================================================================================ accessibility

    @Test
    @DisplayName("chaque libellé a un nom accessible et un texte : l'information n'est jamais portée par la seule couleur")
    void everyLabelHasAnAccessibleNameAndATextOfItsOwn() throws Exception {
        NotebookFrame frame = window(WITH_EVERY_KIND, Theme.LIGHT);

        assertEquals("État d'enregistrement", accessibleName(frame, SAVE_STATE));
        assertEquals("Nombre de lignes", accessibleName(frame, LINE_COUNT));
        assertEquals("Résumé des résultats", accessibleName(frame, SUMMARY));
        assertFalse(accessibleName(frame, STATUS).isBlank(), "statusLabel is named too");

        // In every state the bar goes through, each label says it in words: a screen reader can read it, a colour is no more than a help.
        for (String shown : visitEveryState(frame, null)) {
            assertFalse(shown.isBlank(), "a label of the bar is empty in some state");
        }
    }

    // ================================================================================================ contrast

    @ParameterizedTest(name = "thème {0}")
    @EnumSource(Theme.class)
    @DisplayName("fond surface.raised et textes à 4,5:1 au moins, avec leur paire contrast.text.* déclarée")
    void everyTextOfTheBarReaches45OnTheRaisedSurface(Theme theme) throws Exception {
        NotebookFrame frame = window(DEVIS, theme);
        DesignTokens tokens = DesignTokens.load();
        Color raised = tokens.color(theme, SURFACE);

        List<JLabel> labels = new ArrayList<>();
        for (String name : LABELS) {
            labels.add(label(frame, name));
        }
        Container bar = barOf(frame, labels);
        assertEquals(raised, onEdt(() -> backgroundBehind(bar)), "the bar is painted with " + SURFACE + " in " + theme);

        Set<Color> foregrounds = new LinkedHashSet<>();
        visitEveryState(frame, foregrounds);
        assertTrue(foregrounds.size() >= 2, "the bar tells an error, a confirmation and a plain state apart: " + foregrounds);
        for (Color foreground : foregrounds) {
            List<String> roles = tokens.colorRoles().stream().filter(role -> tokens.color(theme, role).equals(foreground)).toList();
            assertFalse(roles.isEmpty(), "the colour " + foreground + " of a label of the bar comes from no role of tokens.properties");
            List<String> declared = roles.stream().filter(role -> tokens.contrastPairs().stream()
                    .anyMatch(pair -> pair.text() && pair.foreground().equals(role) && pair.background().equals(SURFACE))).toList();
            assertFalse(declared.isEmpty(), "no contrast.text.* pair « " + roles + " on " + SURFACE + " » in tokens.properties");
            assertTrue(Contrast.ratio(foreground, raised) >= Contrast.MINIMUM_TEXT_RATIO,
                    roles + " on " + SURFACE + " in " + theme + ": " + Contrast.ratio(foreground, raised));
        }
    }

    // ================================================================================================ the states of the bar

    /**
     * Takes the bar through an untitled notebook, a saved document, a modified one and a read-only one; it returns the texts of
     * the four labels in every state and, when {@code colours} is not null, adds the foreground of each of them.
     */
    private List<String> visitEveryState(NotebookFrame frame, Set<Color> colours) throws Exception {
        List<String> texts = new ArrayList<>();
        Runnable record = () -> {
            try {
                for (String name : LABELS) {
                    JLabel label = label(frame, name);
                    texts.add(onEdt(label::getText).strip());
                    if (colours != null) {
                        colours.add(onEdt(label::getForeground));
                    }
                }
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        };
        record.run();

        activate(frame, frame.rowCount() - 1);
        dialogs.willSaveTo(documents.resolve("etats"));
        click(frame, "saveAsItem");
        record.run();
        typeLine(frame, "1 / 0");
        record.run();
        typeLine(frame, "√2");
        record.run();

        dialogs.willAnswer(Unsaved.DISCARD);
        openFutureFile(frame);
        record.run();
        return texts;
    }

    // ================================================================================================ helpers

    // A window as Main builds it, in the given theme, on a store holding lines, laid out once every line is calculated.
    private NotebookFrame window(List<String> lines, Theme theme) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        if (!lines.isEmpty()) {
            store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        }
        NotebookFrame frame = onEdt(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(theme);
            NotebookPresenter presenter = NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs,
                    NotebookPresenter.newRecalculationExecutor(), this::around);
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

    // Records every line evaluated and holds the gated one until the test lets it go, even when the thread is interrupted.
    private LineResult around(String text, Supplier<LineResult> evaluation) {
        evaluated.add(text);
        if (text.equals(gatedLine)) {
            gatedLine = null;
            reached.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GATE_PATIENCE_SECONDS);
            boolean interrupted = false;
            try {
                while (true) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new AssertionError("nobody released the gated line");
                    }
                    try {
                        if (released.await(left, TimeUnit.NANOSECONDS)) {
                            break;
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
        return evaluation.get();
    }

    // Opens a file of a version the application does not know yet, with the answer to the unsaved changes queued if asked.
    private void openFutureFile(NotebookFrame frame) throws Exception {
        Path future = documents.resolve("future-" + frames.size() + ".calc.json");
        Files.writeString(future, "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\"}]}");
        dialogs.willOpen(future);
        click(frame, "openItem");
    }

    private void click(NotebookFrame frame, String itemName) throws Exception {
        onEdt(() -> {
            item(itemName, frame).doClick(0);
            return null;
        });
        awaitSettled(frame);
    }

    private void typeLine(NotebookFrame frame, String text) throws Exception {
        onEdt(() -> {
            frame.row(frame.shown().activeIndex()).field.setText(text);
            return null;
        });
        pressInRow(frame, onEdt(() -> frame.shown().activeIndex()), KeyEvent.VK_ENTER);
        awaitSettled(frame);
    }

    // Presses a key as Swing does: the binding of the field of the row, through its input map.
    private static void pressInRow(NotebookFrame frame, int row, int keyCode) throws Exception {
        onEdt(() -> {
            JComponent field = frame.row(row).field;
            KeyStroke stroke = KeyStroke.getKeyStroke(keyCode, 0);
            Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertNotNull(key, "nothing is bound to " + stroke);
            Action action = field.getActionMap().get(key);
            assertNotNull(action, "no action " + key);
            action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, key.toString()));
            return null;
        });
    }

    // What the mouse does in a window that is not shown: a focus gained on the field of the row activates its line.
    private static void activate(NotebookFrame frame, int row) throws Exception {
        onEdt(() -> {
            if (frame.shown().activeIndex() != row) {
                JTextComponent field = frame.row(row).field;
                for (FocusListener listener : field.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
                }
            }
            return null;
        });
        awaitSettled(frame);
        assertEquals(row, (int) onEdt(() -> frame.shown().activeIndex()), "line " + (row + 1) + " is active");
    }

    private static JMenuItem item(String name, NotebookFrame window) {
        List<MenuElement> pending = new ArrayList<>(List.of(window.getJMenuBar().getSubElements()));
        while (!pending.isEmpty()) {
            MenuElement element = pending.remove(0);
            if (element instanceof JMenuItem item && name.equals(item.getName())) {
                return item;
            }
            pending.addAll(List.of(element.getSubElements()));
        }
        throw new AssertionError("no menu item " + name);
    }

    private static Component find(Container container, String name) {
        for (Component component : container.getComponents()) {
            if (name.equals(component.getName())) {
                return component;
            }
            if (component instanceof Container inner) {
                Component found = find(inner, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JLabel label(NotebookFrame frame, String name) {
        Component found = find(frame.getRootPane(), name);
        assertNotNull(found, "the window has no component named " + name);
        assertTrue(found instanceof JLabel, name + " is a JLabel, not " + found.getClass().getName());
        return (JLabel) found;
    }

    // The smallest container that holds the four labels.
    private static Container barOf(NotebookFrame frame, List<JLabel> labels) {
        Container bar = labels.get(0).getParent();
        while (bar != null && !holdsEvery(bar, labels)) {
            bar = bar.getParent();
        }
        assertNotNull(bar, "the four labels are in one container");
        return bar;
    }

    private static boolean holdsEvery(Container container, List<JLabel> labels) {
        return labels.stream().allMatch(label -> SwingUtilities.isDescendingFrom(label, container));
    }

    // The first opaque background the bar is painted with, from the bar itself outwards.
    private static Color backgroundBehind(Container bar) {
        for (Component each = bar; each != null; each = each.getParent()) {
            if (each instanceof JComponent component && component.isOpaque()) {
                return component.getBackground();
            }
        }
        return null;
    }

    private static String text(NotebookFrame frame, String name) throws Exception {
        return onEdt(() -> {
            String shown = label(frame, name).getText();
            return shown == null ? "" : shown;
        });
    }

    private static String accessibleName(NotebookFrame frame, String name) throws Exception {
        return onEdt(() -> {
            String accessible = label(frame, name).getAccessibleContext().getAccessibleName();
            return accessible == null ? "" : accessible;
        });
    }

    // The text of a label, once it is what is expected (the end of a calculation reaches the window after the calculation).
    private static void assertShows(NotebookFrame frame, String name, String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        String shown = text(frame, name);
        while (!expected.equals(shown) && System.nanoTime() < deadline) {
            Thread.sleep(5);
            shown = text(frame, name);
        }
        assertEquals(expected, shown, name);
    }

    private static void awaitCondition(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                fail("never: " + what);
            }
            Thread.sleep(5);
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
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
        onEdt(() -> {
            frame.validate();
            return null;
        });
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        if (failure.get() instanceof Error error) {
            throw error;
        }
        if (failure.get() instanceof Exception exception) {
            throw exception;
        }
        return result.get();
    }
}
