package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.format.DisplayMode;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.ThemeStore;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.theme.SystemThemeDetector;
import calc.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Acceptance of E07: the redrawn rows of the notebook (SPEC 4.2 « Lignes », docs/design/DESIGN.md « Ligne du carnet »). A
 * fixed margin with the number, the exactness sign and the source mark; no more {@code ExactnessBadge}; the sign coloured by
 * {@code exact.sign} and {@code approximate.sign}; expressions and results in JetBrains Mono, titles in Inter bold; the name
 * of an assignment in {@code nameLabel}; results aligned on the decimal comma; an error underlined and explained under its
 * line, on a 4 px grid; the active and hovered surfaces; the focus border; the timing tests of the window kept as they are.
 *
 * <p>The window is the real one: the real store on a temporary directory, the real presenter, {@code new NotebookFrame}, the
 * theme applied by {@link ThemeManager} as {@code Main} does. Components are found by their names inside each row
 * ({@code numberLabel}, {@code exactnessSign}, {@code sourceMark}, {@code lineField}, {@code nameLabel}, {@code resultLabel},
 * {@code errorLabel}); their texts are read through {@code getText()}, whatever their class. The one new method,
 * {@code NotebookRowView.decimalAnchorX()} (a number: the abscissa, in the coordinates of {@code rowsPanel}, of the decimal
 * comma, or of the end of the integer part for an integer), is reached by reflection, so that this class compiles, and fails
 * with a clear message, before it exists.
 */
@Tag("acceptance")
@DisplayName("Lignes du carnet redessinées : marge, signe d'exactitude, alignement sur la virgule, titres, étiquettes, erreurs")
final class NotebookRowsAcceptanceTest {

    private static final String NO_BREAK_SPACE = String.valueOf((char) 0x00A0);

    /** The "Devis cuisine" of the README (section "Exemples de carnet"), no-break spaces included. */
    private static final List<String> DEVIS = List.of(
            "# Devis cuisine",
            "plan = 1" + NO_BREAK_SPACE + "250,00          # plan de travail (espace insécable)",
            "meubles = 3" + NO_BREAK_SPACE + "480",
            "pose = 35 * 12,5",
            "total = plan + meubles + pose     # 5 167,5",
            "taux = 0,2",
            "total * (1 + taux)       # TTC : 6 201",
            "ans / 12                 # mensualité sur un an : 516,75");

    /** Devis cuisine and an approximate line, as the mockup shows it. */
    private static final List<String> DEVIS_AND_ROOT = Stream.concat(DEVIS.stream(), Stream.of("√2")).toList();

    /** Devis cuisine, then every other kind of line: approximate, empty, in error, comment, and a second title. */
    private static final List<String> ALL_KINDS = Stream.concat(DEVIS.stream(),
            Stream.of("√2", "", "total - remise", "  # remarque", "# Options")).toList();

    private static final String EXACT = "=";
    private static final String APPROXIMATE = "≈";
    private static final String ERROR = "!";

    /** The sign of each row of {@link #ALL_KINDS}, the blank row at the end included. */
    private static final List<String> ALL_KINDS_SIGNS = List.of(
            "", EXACT, EXACT, EXACT, EXACT, EXACT, EXACT, EXACT, APPROXIMATE, "", ERROR, "", "", "");

    /** The name shown by each row of {@link #ALL_KINDS}, the blank row at the end included. */
    private static final List<String> ALL_KINDS_NAMES = List.of(
            "", "plan", "meubles", "pose", "total", "taux", "", "", "", "", "", "", "", "");

    /** The line of {@link #ALL_KINDS} in error (from 1): an unknown variable, as in DESIGN.md. */
    private static final int ERROR_LINE = 11;
    /** The whole-line comment of {@link #ALL_KINDS} that is not a title (it does not start with « # »). */
    private static final int COMMENT_LINE = 12;

    private static final String BLOCKED_LINE = "a * 3";

    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final long PATIENCE_SECONDS = 20;
    private static final int INK_THRESHOLD = 96;
    private static final int COLOUR_TOLERANCE = 16;

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch reached = new CountDownLatch(1);
    private LookAndFeel original;

    @BeforeEach
    void rememberTheLookAndFeel() {
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
        released.countDown();
        if (!frames.isEmpty() || !GraphicsEnvironment.isHeadless()) {
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

    // ------------------------------------------------------------------------------------------------ the margin

    @Test
    @Tag("ui")
    @DisplayName("Marge de largeur fixe : numéro, signe « = », « ≈ », « ! » ou vide, marque de source vide sans source active")
    void everyRowHasAFixedMarginWithItsNumberItsSignAndAnEmptySourceMark() throws Exception {
        requireDisplay();
        // The active line is the title, then the blank row: neither has a source, so no row is marked as one.
        for (int activeLine : List.of(1, ALL_KINDS.size() + 1)) {
            NotebookFrame frame = window(ALL_KINDS, Theme.LIGHT, activeLine);
            onEventThread(() -> {
                assertEquals(ALL_KINDS.size() + 1, frame.rowCount(), "one row per line and the blank row at the end");
                Container rowsPanel = rowsPanel(frame);
                Set<Integer> fieldStarts = new TreeSet<>();
                for (int index = 0; index < frame.rowCount(); index++) {
                    NotebookRowView row = frame.row(index);
                    String line = "line " + (index + 1) + " « " + textOfLine(ALL_KINDS, index) + " » (active line " + activeLine + ")";
                    Component number = part(row, "numberLabel");
                    Component sign = part(row, "exactnessSign");
                    Component mark = part(row, "sourceMark");
                    Rectangle field = boundsIn(part(row, "lineField"), rowsPanel);
                    assertEquals(String.valueOf(index + 1), textOf(number).trim(), line + ": the number");
                    assertEquals(ALL_KINDS_SIGNS.get(index), textOf(sign), line + ": the exactness sign");
                    assertEquals("", textOf(mark), line + ": no line is a source of the active line");
                    for (Component margin : List.of(number, sign, mark)) {
                        assertTrue(margin.isVisible(), line + ": " + margin.getName() + " is never hidden, its text is emptied");
                        Rectangle bounds = boundsIn(margin, rowsPanel);
                        assertTrue(bounds.x + bounds.width <= field.x,
                                line + ": " + margin.getName() + " " + bounds + " is in the margin, left of the field " + field);
                    }
                    fieldStarts.add(field.x);
                }
                assertEquals(1, fieldStarts.size(), "the margin has a fixed width: every field starts at the same abscissa " + fieldStarts);
                return null;
            });
        }
    }

    @Test
    @Tag("ui")
    @DisplayName("Une ligne en attente de calcul n'a pas de signe ; le signe « = » vient avec son résultat")
    void aLineWaitingForItsCalculationHasNoSignUntilItsResultComes() throws Exception {
        requireDisplay();
        NotebookStore store = store();
        store.saveCurrent(Notebook.of(List.of("a = 2", BLOCKED_LINE, "a + 1")), DisplayMode.AUTO);
        NotebookPresenter presenter = onEventThread(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            return NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling(),
                    NotebookPresenter.newRecalculationExecutor(), this::blockingOnOneLine);
        });
        assertTrue(reached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the calculation never reached the blocked line");
        flushEvents();
        NotebookFrame frame = onEventThread(() -> frame(presenter));
        flushEvents();

        List<NotebookRow.Status> statuses = onEventThread(() -> frame.shown().rows().stream().map(NotebookRow::status).toList());
        assertEquals(List.of(NotebookRow.Status.PENDING, NotebookRow.Status.PENDING), statuses.subList(1, 3),
                "the lines from the blocked one wait for the calculation");
        List<String> waiting = onEventThread(() -> signs(frame));
        assertEquals(List.of("", "", ""), waiting.subList(1, 4), "a line waiting for its calculation, and the blank row, have no sign");
        assertEquals(List.of("", "", "", ""), onEventThread(() -> sourceMarks(frame)), "no source mark while no line is a source");

        released.countDown();
        awaitSettled(frame);
        assertEquals(List.of(EXACT, EXACT, EXACT, ""), onEventThread(() -> signs(frame)), "each calculated line shows its sign");
    }

    // ------------------------------------------------------------------------------------------------ no badge

    @Test
    @Tag("ui")
    @DisplayName("Plus d'ExactnessBadge ni de texte « Exact » ; nom accessible « Ligne n », description « exacte » ou « approchée »")
    void theBadgeIsGoneAndTheAccessibleTextsStillSayTheExactness() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(ALL_KINDS, Theme.LIGHT, 1);
        onEventThread(() -> {
            for (Component component : descendants(frame.getRootPane())) {
                for (Class<?> type = component.getClass(); type != null; type = type.getSuperclass()) {
                    assertFalse(type.getSimpleName().equals("ExactnessBadge"), "an ExactnessBadge is still in the window: " + component);
                }
                assertFalse("exactnessBadge".equals(component.getName()), "a component is still named exactnessBadge: " + component);
            }
            for (int index = 0; index < frame.rowCount(); index++) {
                NotebookRowView row = frame.row(index);
                String line = "line " + (index + 1) + " « " + textOfLine(ALL_KINDS, index) + " »";
                for (Component component : descendants(row)) {
                    if (hasText(component)) {
                        assertFalse(textOf(component).contains("Exact"), line + " shows « Exact » in " + component.getName()
                                + ": « " + textOf(component) + " »");
                    }
                }
                JTextComponent field = (JTextComponent) part(row, "lineField");
                assertEquals("Ligne " + (index + 1), field.getAccessibleContext().getAccessibleName(), line + ": the accessible name");
                String description = String.valueOf(field.getAccessibleContext().getAccessibleDescription());
                String sign = ALL_KINDS_SIGNS.get(index);
                if (sign.equals(EXACT)) {
                    assertTrue(description.contains("exacte"), line + ": the accessible description says « exacte »: " + description);
                    assertFalse(description.contains("approchée"), line + ": an exact value is not « approchée »: " + description);
                } else if (sign.equals(APPROXIMATE)) {
                    assertTrue(description.contains("approchée"), line + ": the accessible description says « approchée »: " + description);
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ colours of the sign

    @ParameterizedTest(name = "{0}")
    @EnumSource(Theme.class)
    @Tag("ui")
    @DisplayName("Sur Devis cuisine, le signe a la couleur exact.sign ou approximate.sign du thème")
    void onDevisCuisineTheSignHasTheColourOfItsExactness(Theme theme) throws Exception {
        requireDisplay();
        NotebookFrame frame = window(DEVIS_AND_ROOT, theme, 1);
        assertSignColours(frame, theme);
    }

    @Test
    @Tag("ui")
    @DisplayName("Affichage > Thème > Sombre recolore les signes de la fenêtre ouverte")
    void choosingDarkRecoloursTheSignsOfTheOpenWindow() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(DEVIS_AND_ROOT, Theme.LIGHT, 1);
        assertSignColours(frame, Theme.LIGHT);
        JMenuItem dark = menuItem(frame, "themeItemDARK");
        onEventThread(() -> {
            dark.doClick(0);
            return null;
        });
        flushEvents();
        assertEquals("calc.ui.theme.CalcDarkLaf", onEventThread(() -> UIManager.getLookAndFeel().getClass().getName()),
                "Sombre applies the dark theme");
        assertSignColours(frame, Theme.DARK);
    }

    // ------------------------------------------------------------------------------------------------ fonts and titles

    @Test
    @Tag("ui")
    @DisplayName("Expressions et résultats en JetBrains Mono ; une ligne « # » est un titre en Inter gras, plus grand, texte brut gardé")
    void expressionsAndResultsAreInJetBrainsMonoAndTitlesInLargerBoldInter() throws Exception {
        requireDisplay();
        DesignTokens tokens = DesignTokens.load();
        String mono = tokens.fontFamily("mono");
        String ui = tokens.fontFamily("ui");
        NotebookFrame frame = window(ALL_KINDS, Theme.LIGHT, 1);
        onEventThread(() -> {
            List<Integer> titles = new ArrayList<>();
            float largestOther = 0;
            for (int index = 0; index < frame.rowCount(); index++) {
                NotebookRowView row = frame.row(index);
                String text = textOfLine(ALL_KINDS, index);
                String line = "line " + (index + 1) + " « " + text + " »";
                JTextComponent field = (JTextComponent) part(row, "lineField");
                Font fieldFont = field.getFont();
                assertEquals(mono, part(row, "resultLabel").getFont().getFamily(), line + ": the result is in " + mono);
                if (text.startsWith("#")) {
                    titles.add(index);
                    assertEquals(ui, fieldFont.getFamily(), line + " is a title, in " + ui);
                    assertTrue(fieldFont.isBold(), line + " is a title, in bold: " + fieldFont);
                    assertEquals(text, field.getText(), line + ": the title keeps its raw text, « # » included");
                } else {
                    largestOther = Math.max(largestOther, fieldFont.getSize2D());
                    if (index + 1 != COMMENT_LINE) {
                        assertEquals(mono, fieldFont.getFamily(), line + ": what is typed is in " + mono);
                    }
                }
            }
            assertEquals(List.of(0, ALL_KINDS.size() - 1), titles, "the two lines that start with « # » are titles");
            for (int index : titles) {
                float size = part(frame.row(index), "lineField").getFont().getSize2D();
                assertTrue(size > largestOther, "the title of line " + (index + 1) + " (" + size
                        + ") is larger than every other line (" + largestOther + ")");
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ name of an assignment

    @Test
    @Tag("ui")
    @DisplayName("Une affectation affiche son nom dans nameLabel, en Inter ; une ligne sans affectation a un nameLabel vide")
    void anAssignmentShowsItsNameInInter() throws Exception {
        requireDisplay();
        String ui = DesignTokens.load().fontFamily("ui");
        NotebookFrame frame = window(ALL_KINDS, Theme.LIGHT, 1);
        onEventThread(() -> {
            for (int index = 0; index < frame.rowCount(); index++) {
                NotebookRowView row = frame.row(index);
                String line = "line " + (index + 1) + " « " + textOfLine(ALL_KINDS, index) + " »";
                Component name = part(row, "nameLabel");
                assertEquals(ALL_KINDS_NAMES.get(index), textOf(name), line + ": the name of the assignment");
                assertEquals(ui, name.getFont().getFamily(), line + ": the name is in " + ui);
                if (!ALL_KINDS_NAMES.get(index).isEmpty()) {
                    Rectangle bounds = boundsIn(name, row);
                    assertTrue(name.isVisible() && bounds.width > 0 && bounds.height > 0, line + ": the name is laid out " + bounds);
                    assertTrue(bounds.x >= 0 && bounds.x + bounds.width <= row.getWidth(), line + ": the name is inside its row " + bounds);
                }
            }
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ decimal alignment

    @Test
    @Tag("ui")
    @DisplayName("Sur Devis cuisine, la virgule (ou la fin d'un entier) tombe à la même abscisse à 1 px près, et la peinture le montre")
    void theResultsOfDevisCuisineAreAlignedOnTheDecimalComma() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(DEVIS, Theme.LIGHT, 1);
        onEventThread(() -> {
            Container rowsPanel = rowsPanel(frame);
            Map<String, Double> anchors = new LinkedHashMap<>();
            List<String> problems = new ArrayList<>();
            int integers = 0;
            int decimals = 0;
            for (int index = 1; index < DEVIS.size(); index++) {
                NotebookRowView row = frame.row(index);
                String result = frame.shown().rows().get(index).result();
                assertFalse(result.isEmpty(), "line " + (index + 1) + " of Devis cuisine has a result");
                boolean decimal = result.contains(",");
                if (decimal) {
                    decimals++;
                } else {
                    integers++;
                }
                double anchor = decimalAnchorX(row);
                String line = "line " + (index + 1) + " (" + result + ", anchor " + anchor + ")";
                anchors.put(line, anchor);
                assertTrue(anchor > 0 && anchor < rowsPanel.getWidth(), line + ": the anchor is inside the rows " + rowsPanel.getWidth());

                // What is painted agrees: the integer part ends at the anchor, the comma and the decimals come after it.
                JComponent shown = (JComponent) part(row, "resultLabel");
                Rectangle where = boundsIn(shown, rowsPanel);
                boolean[] ink = inkColumns(paint(shown, row.getBackground()));
                int last = lastInk(ink);
                if (last < 0) {
                    problems.add(line + ": the result paints nothing");
                    continue;
                }
                double cell = shown.getFontMetrics(shown.getFont()).charWidth('0');
                double inkEnd = where.x + last + 1.0;
                if (!decimal && (inkEnd < anchor - cell / 2 || inkEnd > anchor + 1)) {
                    problems.add(line + ": the integer ends at " + inkEnd + ", not at the anchor");
                }
                if (decimal) {
                    if (inkEnd < anchor + cell) {
                        problems.add(line + ": the decimals end at " + inkEnd + ", they do not come after the anchor");
                    }
                    if (!anyInk(ink, where.x, anchor - cell, anchor - 1)) {
                        problems.add(line + ": no digit ends just before the anchor");
                    }
                    if (!anyBlank(ink, where.x, (int) Math.floor(anchor) - 1, (int) Math.floor(anchor) + 2)) {
                        problems.add(line + ": no gap between the integer part and the comma at the anchor");
                    }
                }
            }
            assertTrue(integers >= 2 && decimals >= 2, "Devis cuisine has integers and decimals: " + integers + ", " + decimals);
            double lowest = anchors.values().stream().mapToDouble(Double::doubleValue).min().orElseThrow();
            double highest = anchors.values().stream().mapToDouble(Double::doubleValue).max().orElseThrow();
            assertTrue(highest - lowest <= 1.0, "the comma, or the end of an integer, has the same abscissa within 1 px: " + anchors);
            assertEquals(List.of(), problems, "the painted results follow the anchor " + anchors);
            return null;
        });
    }

    // ------------------------------------------------------------------------------------------------ errors and the grid

    @Test
    @Tag("ui")
    @DisplayName("Une erreur est soulignée et expliquée sous sa ligne ; hauteurs multiples de 4 px, égales sans erreur")
    void anErrorIsUnderlinedAndExplainedBelowItsLineOnAGridOfFourPixels() throws Exception {
        requireDisplay();
        Set<Integer> heightsWithoutError = new TreeSet<>();
        // Each row is measured once while another one is active: the title first, then the blank row.
        for (int activeLine : List.of(1, ALL_KINDS.size() + 1)) {
            NotebookFrame frame = window(ALL_KINDS, Theme.LIGHT, activeLine);
            onEventThread(() -> {
                NotebookRow model = frame.shown().rows().get(ERROR_LINE - 1);
                assertEquals(NotebookRow.Status.ERROR, model.status(), "line " + ERROR_LINE + " is in error");
                assertTrue(model.errorStart() >= 0 && model.errorEnd() > model.errorStart(), "the error has a faulty part: " + model);
                NotebookRowView row = frame.row(ERROR_LINE - 1);
                JTextComponent field = (JTextComponent) part(row, "lineField");
                boolean underlined = false;
                for (Highlighter.Highlight highlight : field.getHighlighter().getHighlights()) {
                    underlined |= highlight.getStartOffset() == model.errorStart() && highlight.getEndOffset() == model.errorEnd();
                }
                assertTrue(underlined, "the faulty part " + model.errorStart() + "-" + model.errorEnd() + " is underlined in the field");

                Component message = part(row, "errorLabel");
                assertTrue(textOf(message).contains(model.errorMessage()), "errorLabel says the error: « " + textOf(message) + " »");
                Rectangle fieldBounds = boundsIn(field, row);
                Rectangle messageBounds = boundsIn(message, row);
                assertTrue(message.isVisible() && messageBounds.width > 0 && messageBounds.height > 0,
                        "errorLabel is laid out " + messageBounds);
                assertTrue(messageBounds.y >= fieldBounds.y + fieldBounds.height,
                        "errorLabel " + messageBounds + " is under the field " + fieldBounds);
                assertTrue(messageBounds.y + messageBounds.height <= row.getHeight(),
                        "errorLabel " + messageBounds + " is inside its row, " + row.getHeight() + " px high");

                for (int index = 0; index < frame.rowCount(); index++) {
                    int height = frame.row(index).getHeight();
                    String line = "line " + (index + 1) + " « " + textOfLine(ALL_KINDS, index) + " » (active line " + activeLine + ")";
                    assertTrue(height > 0 && height % 4 == 0, line + " is " + height + " px high, not a multiple of 4");
                    if (index + 1 != ERROR_LINE && index != frame.shown().activeIndex()) {
                        heightsWithoutError.add(height);
                    }
                }
                return null;
            });
        }
        assertEquals(1, heightsWithoutError.size(), "every line without an error has the same height: " + heightsWithoutError);
    }

    // ------------------------------------------------------------------------------------------------ active, hover, focus

    @ParameterizedTest(name = "{0}")
    @EnumSource(Theme.class)
    @Tag("ui")
    @DisplayName("La ligne active a le fond surface.active, la ligne survolée surface.hover, les autres surface.base")
    void theActiveRowAndTheRowUnderThePointerTakeTheirSurfaces(Theme theme) throws Exception {
        requireDisplay();
        DesignTokens tokens = DesignTokens.load();
        String base = hex(tokens.color(theme, "surface.base"));
        String active = hex(tokens.color(theme, "surface.active"));
        String hover = hex(tokens.color(theme, "surface.hover"));
        int activeIndex = 4;
        int hoveredIndex = 6;
        NotebookFrame frame = window(DEVIS, theme, activeIndex + 1);
        assertEquals(activeIndex, (int) onEventThread(() -> frame.shown().activeIndex()), "line 5 is active");
        assertEquals(surfaces(frame.rowCount(), base, activeIndex, active, -1, hover), onEventThread(() -> backgrounds(frame)),
                "the active line has surface.active, the others surface.base");

        NotebookRowView hovered = onEventThread(() -> frame.row(hoveredIndex));
        onEventThread(() -> {
            int middle = hovered.getHeight() / 2;
            hovered.dispatchEvent(mouse(hovered, MouseEvent.MOUSE_ENTERED, 2, middle));
            hovered.dispatchEvent(mouse(hovered, MouseEvent.MOUSE_MOVED, 2, middle));
            return null;
        });
        flushEvents();
        assertEquals(surfaces(frame.rowCount(), base, activeIndex, active, hoveredIndex, hover), onEventThread(() -> backgrounds(frame)),
                "the line under the pointer has surface.hover");

        onEventThread(() -> {
            hovered.dispatchEvent(mouse(hovered, MouseEvent.MOUSE_EXITED, 2, -4));
            return null;
        });
        flushEvents();
        assertEquals(surfaces(frame.rowCount(), base, activeIndex, active, -1, hover), onEventThread(() -> backgrounds(frame)),
                "the pointer gone, the line goes back to surface.base");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Theme.class)
    @Tag("ui")
    @DisplayName("Le champ qui a le focus clavier montre une bordure de la couleur border.focus")
    void theFieldWithTheKeyboardFocusShowsABorderOfBorderFocus(Theme theme) throws Exception {
        requireDisplay();
        Color focus = DesignTokens.load().color(theme, "border.focus");
        NotebookFrame frame = window(DEVIS, theme, 5);
        NotebookRowView row = onEventThread(() -> frame.row(4));
        onEventThread(() -> {
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
            row.field.requestFocusInWindow();
            return null;
        });
        assumeTrue(await(() -> row.field.isFocusOwner()), "a system that gives no focus to a new window: the focus cannot be observed");
        flushEvents();
        int[] counted = onEventThread(() -> {
            JTextComponent field = (JTextComponent) part(row, "lineField");
            BufferedImage image = paint(field, row.getBackground());
            Insets insets = field.getInsets();
            int inBorder = 0;
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    boolean inside = x >= insets.left && x < image.getWidth() - insets.right
                            && y >= insets.top && y < image.getHeight() - insets.bottom;
                    if (!inside && close(image.getRGB(x, y), focus.getRGB(), COLOUR_TOLERANCE)) {
                        inBorder++;
                    }
                }
            }
            return new int[] {inBorder, image.getWidth()};
        });
        assertTrue(counted[0] >= counted[1] / 2, "the border of the focused field has " + counted[0] + " pixels of border.focus "
                + hex(focus) + ", for a field " + counted[1] + " px wide");
    }

    // ------------------------------------------------------------------------------------------------ the timing tests

    @Test
    @DisplayName("Les tests de budget de la fenêtre restent tels quels : mêmes tests, mêmes carnets de 500 et 5000 lignes")
    void theTimingTestsOfTheWindowAreKeptAsTheyAre() throws IOException {
        Path folder = Path.of("src", "test", "java", "calc", "ui", "notebook");
        Map<String, List<String>> kept = new LinkedHashMap<>();
        kept.put("NotebookFrameResponsivenessTest.java", List.of(
                "measure(NotebookResponsivenessTest.LINES)", "measure(calc.notebook.Notebook.MAX_LINES)", "TimingBudget.assertWithin("));
        kept.put("NotebookResponsivenessTest.java", List.of("static final int LINES = 500;"));
        kept.put("BackgroundRecalculationAcceptanceTest.java", List.of("HOSTILE_LINES = 5000;", "TimingBudget.assertWithin("));
        kept.put("NotebookRecalculationTest.java", List.of("EDT_LINES = 5000;", "TimingBudget.assertWithin("));
        Map<String, Integer> tests = new HashMap<>();
        tests.put("NotebookFrameResponsivenessTest.java", 2);
        tests.put("BackgroundRecalculationAcceptanceTest.java", 20);
        tests.put("NotebookRecalculationTest.java", 21);
        List<String> faults = new ArrayList<>();
        for (Map.Entry<String, List<String>> file : kept.entrySet()) {
            String code = Files.readString(folder.resolve(file.getKey()), StandardCharsets.UTF_8);
            for (String expected : file.getValue()) {
                if (!code.contains(expected)) {
                    faults.add(file.getKey() + " no longer holds « " + expected + " »");
                }
            }
            if (code.contains("@Disabled")) {
                faults.add(file.getKey() + " disables a test");
            }
            int count = code.split("@Test\\b", -1).length - 1;
            if (count < tests.getOrDefault(file.getKey(), 0)) {
                faults.add(file.getKey() + " has " + count + " tests, fewer than " + tests.get(file.getKey()));
            }
        }
        assertEquals(List.of(), faults);
    }

    // ------------------------------------------------------------------------------------------------ helpers: checks

    private static void assertSignColours(NotebookFrame frame, Theme theme) throws Exception {
        DesignTokens tokens = DesignTokens.load();
        String exact = hex(tokens.color(theme, "exact.sign"));
        String approximate = hex(tokens.color(theme, "approximate.sign"));
        onEventThread(() -> {
            for (int index = 1; index < DEVIS_AND_ROOT.size(); index++) {
                boolean root = index == DEVIS_AND_ROOT.size() - 1;
                Component sign = part(frame.row(index), "exactnessSign");
                String line = "line " + (index + 1) + " « " + DEVIS_AND_ROOT.get(index) + " » in " + theme;
                assertEquals(root ? APPROXIMATE : EXACT, textOf(sign), line + ": the sign");
                assertEquals(root ? approximate : exact, hex(sign.getForeground()),
                        line + ": the colour of the sign is " + (root ? "approximate.sign" : "exact.sign"));
            }
            return null;
        });
    }

    private static List<String> surfaces(int rows, String base, int activeIndex, String active, int hoveredIndex, String hover) {
        List<String> surfaces = new ArrayList<>();
        for (int index = 0; index < rows; index++) {
            surfaces.add(index == activeIndex ? active : index == hoveredIndex ? hover : base);
        }
        return surfaces;
    }

    private static List<String> backgrounds(NotebookFrame frame) {
        List<String> colours = new ArrayList<>();
        for (int index = 0; index < frame.rowCount(); index++) {
            colours.add(hex(frame.row(index).getBackground()));
        }
        return colours;
    }

    private static List<String> signs(NotebookFrame frame) {
        List<String> signs = new ArrayList<>();
        for (int index = 0; index < frame.rowCount(); index++) {
            signs.add(textOf(part(frame.row(index), "exactnessSign")));
        }
        return signs;
    }

    private static List<String> sourceMarks(NotebookFrame frame) {
        List<String> marks = new ArrayList<>();
        for (int index = 0; index < frame.rowCount(); index++) {
            marks.add(textOf(part(frame.row(index), "sourceMark")));
        }
        return marks;
    }

    // The abscissa NotebookRowView gives for the decimal comma, in the coordinates of rowsPanel.
    private static double decimalAnchorX(NotebookRowView row) throws Exception {
        Method method;
        try {
            method = NotebookRowView.class.getDeclaredMethod("decimalAnchorX");
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("NotebookRowView has no method decimalAnchorX() yet", missing);
        }
        method.setAccessible(true);
        Object value;
        try {
            value = method.invoke(row);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
        assertTrue(value instanceof Number, "decimalAnchorX() returns a number, returns " + value);
        return ((Number) value).doubleValue();
    }

    // ------------------------------------------------------------------------------------------------ helpers: pixels

    // The component painted alone on the background of its row, at its own size, one pixel per unit.
    private static BufferedImage paint(JComponent component, Color background) {
        BufferedImage image = new BufferedImage(Math.max(1, component.getWidth()), Math.max(1, component.getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(background);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            component.printAll(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    // The columns that hold ink: a pixel far from the most frequent colour of the picture, its background.
    private static boolean[] inkColumns(BufferedImage image) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                counts.merge(image.getRGB(x, y), 1, Integer::sum);
            }
        }
        int background = counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        boolean[] ink = new boolean[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (distance(image.getRGB(x, y), background) > INK_THRESHOLD) {
                    ink[x] = true;
                }
            }
        }
        return ink;
    }

    private static int lastInk(boolean[] ink) {
        for (int column = ink.length - 1; column >= 0; column--) {
            if (ink[column]) {
                return column;
            }
        }
        return -1;
    }

    // Whether a column whose left edge lies in [from, to) of rowsPanel holds ink; origin is the abscissa of column 0.
    private static boolean anyInk(boolean[] ink, int origin, double from, double to) {
        for (int column = (int) Math.ceil(from - origin); column < Math.ceil(to - origin); column++) {
            if (column >= 0 && column < ink.length && ink[column]) {
                return true;
            }
        }
        return false;
    }

    // Whether one column with a left edge from {@code from} to {@code to} of rowsPanel, both included, holds no ink.
    private static boolean anyBlank(boolean[] ink, int origin, int from, int to) {
        for (int x = from; x <= to; x++) {
            int column = x - origin;
            if (column >= 0 && column < ink.length && !ink[column]) {
                return true;
            }
        }
        return false;
    }

    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static boolean close(int a, int b, int tolerance) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= tolerance
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= tolerance
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= tolerance;
    }

    private static String hex(Color colour) {
        return String.format("#%06X", colour.getRGB() & 0xFFFFFF);
    }

    // ------------------------------------------------------------------------------------------------ helpers: window

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    private NotebookStore store() throws IOException {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        return store;
    }

    /**
     * A window as Main builds it, in {@code theme}, with {@code lines} opened in one piece and the line numbered
     * {@code activeLine} (from 1) active, laid out at {@value #WIDTH} × {@value #HEIGHT} once every line is calculated and every
     * row built. It is never shown.
     */
    private NotebookFrame window(List<String> lines, Theme theme, int activeLine) throws Exception {
        NotebookStore store = store();
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        LoadedNotebook loaded = store.load();
        NotebookFrame frame = onEventThread(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(theme);
            NotebookPresenter presenter = NotebookPresenter.open(store, loaded, DisplayModeStore.noop(), ThemeStore.noop(),
                    SystemThemeDetector.none(), NotebookDialogs.cancelling(), NotebookPresenter.newRecalculationExecutor());
            if (activeLine != 1) {
                presenter.onActivate(activeLine - 1, presenter.currentView().activeDraft());
            }
            return frame(presenter);
        });
        awaitSettled(frame);
        return frame;
    }

    // On the event thread: the window of the presenter, laid out at its size.
    private NotebookFrame frame(NotebookPresenter presenter) {
        NotebookFrame frame;
        // The JDK lists a window as visible while its constructor runs; Window.getWindows() takes this lock.
        synchronized (Window.class) {
            frame = new NotebookFrame(presenter);
        }
        frames.add(frame);
        frame.pack();
        Insets border = frame.getInsets();
        frame.setSize(WIDTH + border.left + border.right, HEIGHT + border.top + border.bottom);
        frame.validate();
        return frame;
    }

    // Waits until nothing is being calculated and every row is built, then lays the window out again.
    private static void awaitSettled(NotebookFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!onEventThread(() -> !frame.isCalculating() && frame.rowCount() == frame.shown().rows().size())) {
            if (System.nanoTime() > deadline) {
                fail("the notebook is still being calculated after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(5);
        }
        flushEvents();
        onEventThread(() -> {
            frame.validate();
            return null;
        });
    }

    private static boolean await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!onEventThread(condition)) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    // Stops the calculating thread on the blocked line, until the test lets it go.
    private LineResult blockingOnOneLine(String text, Supplier<LineResult> evaluation) {
        if (text.equals(BLOCKED_LINE)) {
            reached.countDown();
            try {
                assertTrue(released.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "nobody released the line");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        return evaluation.get();
    }

    private static MouseEvent mouse(Component target, int id, int x, int y) {
        return new MouseEvent(target, id, System.currentTimeMillis(), 0, x, y, 0, false);
    }

    private static JMenuItem menuItem(NotebookFrame frame, String name) throws Exception {
        return onEventThread(() -> {
            JMenuBar bar = frame.getJMenuBar();
            Deque<Component> pending = new ArrayDeque<>();
            for (int index = 0; index < bar.getMenuCount(); index++) {
                pending.add(bar.getMenu(index));
            }
            while (!pending.isEmpty()) {
                Component each = pending.pop();
                if (each instanceof JMenuItem item && name.equals(item.getName())) {
                    return item;
                }
                if (each instanceof JMenu menu) {
                    pending.addAll(List.of(menu.getMenuComponents()));
                }
            }
            throw new AssertionError("no menu item named " + name);
        });
    }

    private static Container rowsPanel(NotebookFrame frame) {
        return (Container) descendants(frame.getContentPane()).stream().filter(each -> "rowsPanel".equals(each.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("no component named rowsPanel"));
    }

    // The one component of the row with this name.
    private static Component part(NotebookRowView row, String name) {
        List<Component> found = descendants(row).stream().filter(each -> name.equals(each.getName())).toList();
        assertEquals(1, found.size(), "line " + (row.index + 1) + " has one component named " + name + ", has " + found.size());
        return found.get(0);
    }

    private static Rectangle boundsIn(Component component, Component ancestor) {
        return SwingUtilities.convertRectangle(component.getParent(), component.getBounds(), ancestor);
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

    private static boolean hasText(Component component) {
        return component instanceof JLabel || component instanceof AbstractButton || component instanceof JTextComponent;
    }

    // The text a component shows, whatever its class: getText(), an empty text for null.
    private static String textOf(Component component) {
        String text;
        if (component instanceof JLabel label) {
            text = label.getText();
        } else if (component instanceof AbstractButton button) {
            text = button.getText();
        } else if (component instanceof JTextComponent field) {
            text = field.getText();
        } else {
            try {
                Method getText = component.getClass().getMethod("getText");
                getText.setAccessible(true);
                text = String.valueOf(getText.invoke(component));
            } catch (ReflectiveOperationException missing) {
                throw new AssertionError(component.getName() + " shows no text: it has no getText()", missing);
            }
        }
        return text == null ? "" : text;
    }

    private static String textOfLine(List<String> lines, int index) {
        return index < lines.size() ? lines.get(index) : "";
    }

    private static <T> T onEventThread(Callable<T> work) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.call();
        }
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<Error> error = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception exception) {
                failure.set(exception);
            } catch (Error thrown) {
                error.set(thrown);
            }
        });
        if (error.get() != null) {
            throw error.get();
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    // Lets what the event thread queued run before the test reads the window.
    private static void flushEvents() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
    }
}
