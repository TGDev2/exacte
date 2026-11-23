package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.expression.CanonicalPrinter;
import calc.expression.ExpressionException;
import calc.expression.ExpressionLimits;
import calc.expression.ExpressionParser;
import calc.expression.Node;
import calc.format.DisplayMode;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineExpansion;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.number.CertifiedValue;
import calc.number.Rational;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.testing.RationalGenerators;
import calc.testing.RationalGenerators.Enclosure;
import calc.ui.DisplayModeStore;
import calc.ui.ThemeStore;
import calc.ui.design.Theme;
import calc.ui.theme.SystemThemeDetector;
import calc.ui.theme.ThemeManager;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of E08: the calculation justifies itself, the detail of the active line (SPEC 4.2 « Détail de la ligne active »,
 * 4.3 « Texte du détail », ADR-D).
 *
 * <ul>
 *   <li>{@code calc.detail.LineDetailText.of(EvaluatedNotebook, LineId, DisplayMode)} returns the ordered sentences of the
 *       detail, a {@code List<String>}: « = » and a space then {@link LineExpansion} when there is an expansion; the result,
 *       « = » and a space then the text of {@link ValueFormatter#format} for an exact value, that text alone for an
 *       approximate one (it already starts with « ≈ » or reads « |x| ≤ b »); « Sources : ligne 2 » or « Sources : lignes 2,
 *       3 et 4 » when the line has sources; « Utilisée par 1 ligne », « Utilisée par N lignes » or « Utilisée par aucune
 *       ligne »; « Valeur garantie entre L et U » for an approximate value. A line without a value has no detail.</li>
 *   <li>{@code ValueFormatter.guaranteedBounds(CertifiedValue)} returns the two texts L and U, as a {@code List<String>} of
 *       two, a {@code String[]} of two or a record of two {@code String} components, in that order: decimal comma, at most
 *       12 significant digits, rounded outwards; a power of ten may be written « × 10ⁿ » as the rest of the formatter does.
 *   </li>
 *   <li>The window holds one component named {@code lineDetail}, under the active line. Its lines of text are the texts of
 *       {@code lineDetail} itself or of its visible {@code JLabel}, {@code AbstractButton} and {@code JTextComponent}
 *       descendants, from top to bottom, a text of several lines read line by line, blank lines left out.</li>
 *   <li>{@code sourceMark} shows « ◆ » on the sources of the active line, and their accessible description says « source de
 *       la ligne active ».</li>
 * </ul>
 *
 * <p>The new class and the new method are reached by reflection, so that this class compiles, and fails with a clear message,
 * before they exist. The window is the real one: the real store on a temporary directory, the real presenter,
 * {@code new NotebookFrame}, the theme applied by {@link ThemeManager} as {@code Main} does; it is never shown.
 *
 * <p>The jqwik property makes this a jqwik class too: the class carries the jqwik tags (PropertyTagsTest), each JUnit test
 * its own {@code acceptance} tag, and no JUnit annotation sits on the class or on the property, which jqwik cannot read.
 */
@net.jqwik.api.Tag("acceptance")
@net.jqwik.api.Tag("property")
class LineDetailAcceptanceTest {

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

    /** Devis cuisine and an approximate line. */
    private static final List<String> DEVIS_AND_ROOT = Stream.concat(DEVIS.stream(), Stream.of("√2")).toList();

    /** Devis cuisine, then an approximate line, an empty line, a line in error and a comment. */
    private static final List<String> WITHOUT_VALUES = Stream.concat(DEVIS.stream(),
            Stream.of("√2", "", "total - remise", "  # remarque")).toList();

    /** The line 3 of Devis cuisine after the edit of the contract. */
    private static final String NEW_FURNITURE = "meubles = 3" + NO_BREAK_SPACE + "500";

    private static final String MARK = "◆";
    private static final String SOURCE_OF_ACTIVE = "source de la ligne active";

    // The parser of the expansions, as in NotebookSourcesAcceptanceTest: long lines allowed.
    private static final ExpressionParser EXPANSION_PARSER = new ExpressionParser(new ExpressionLimits(100_000, 100, 500));

    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final long PATIENCE_SECONDS = 20;

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private final CountDownLatch released = new CountDownLatch(1);
    private final CountDownLatch reached = new CountDownLatch(1);
    private final AtomicBoolean gateArmed = new AtomicBoolean();
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

    // ================================================================================================ the text of the detail

    @Test
    @Tag("acceptance")
    @DisplayName("Devis cuisine, ligne 5 (total) : développement, résultat affiché, « Sources : lignes 2, 3 et 4 », « Utilisée par 1 ligne »")
    void theDetailOfTheTotalOfDevisCuisine() throws Exception {
        EvaluatedNotebook devis = evaluated(DEVIS);
        List<String> detail = detailOf(devis, 5, DisplayMode.AUTO);

        assertEquals(4, detail.size(), "the detail of an exact line with sources: expansion, result, sources, uses " + detail);
        assertEquals("= " + expansion(devis, 5), detail.get(0), "the expansion: « = » and LineExpansion");
        assertEquals(shape("1250 + 3480 + 437,5"), shape(withoutEquals(detail.get(0))),
                "the expansion reads as « 1250 + 3480 + 437,5 »: " + detail.get(0));
        assertEquals("= " + shown(devis, 5, DisplayMode.AUTO), detail.get(1), "the result as the line shows it");
        assertEquals("Sources : lignes 2, 3 et 4", detail.get(2));
        assertEquals("Utilisée par 1 ligne", detail.get(3));
    }

    @Test
    @Tag("acceptance")
    @DisplayName("Devis cuisine, chaque ligne : pas de développement sans source, « Sources : ligne 7 », « Utilisée par aucune ligne »")
    void everyLineOfDevisCuisineHasItsDetail() throws Exception {
        EvaluatedNotebook devis = evaluated(DEVIS);
        // The oracle relies on E06: an expansion exists exactly for the lines that have sources.
        for (int number : new int[] {2, 3, 4, 6}) {
            assertEquals(Optional.empty(), LineExpansion.expand(devis, devis.result(number).id()), "line " + number + " has no source");
        }

        assertEquals(List.of(), detailOf(devis, 1, DisplayMode.AUTO), "a title has no detail");
        for (int number : new int[] {2, 3, 4, 6}) {
            assertEquals(List.of("= " + shown(devis, number, DisplayMode.AUTO), "Utilisée par 1 ligne"),
                    detailOf(devis, number, DisplayMode.AUTO), "line " + number + " « " + DEVIS.get(number - 1) + " »");
        }
        assertEquals(List.of("= " + expansion(devis, 7), "= " + shown(devis, 7, DisplayMode.AUTO), "Sources : lignes 5 et 6",
                "Utilisée par 1 ligne"), detailOf(devis, 7, DisplayMode.AUTO), "line 7, used by line 8 through ans");
        assertEquals(List.of("= " + expansion(devis, 8), "= " + shown(devis, 8, DisplayMode.AUTO), "Sources : ligne 7",
                "Utilisée par aucune ligne"), detailOf(devis, 8, DisplayMode.AUTO), "line 8, the last one");
    }

    @Test
    @Tag("acceptance")
    @DisplayName("Accords : « Utilisée par 2 lignes », « Utilisée par 10 lignes », « Sources : lignes 1, 2, 3 et 4 », « lignes 1 et 2 », une ligne nommée une fois")
    void theSentencesAgreeWithTheirCount() throws Exception {
        EvaluatedNotebook twice = evaluated(List.of("a = 2", "a + 1", "a * 3"));
        assertEquals(List.of("= " + shown(twice, 1, DisplayMode.AUTO), "Utilisée par 2 lignes"), detailOf(twice, 1, DisplayMode.AUTO));
        assertEquals(List.of("= " + expansion(twice, 2), "= " + shown(twice, 2, DisplayMode.AUTO), "Sources : ligne 1",
                "Utilisée par aucune ligne"), detailOf(twice, 2, DisplayMode.AUTO));

        List<String> tenUses = new ArrayList<>(List.of("x = 5"));
        for (int factor = 1; factor <= 10; factor++) {
            tenUses.add("x * " + factor);
        }
        EvaluatedNotebook ten = evaluated(tenUses);
        assertEquals(List.of("= " + shown(ten, 1, DisplayMode.AUTO), "Utilisée par 10 lignes"), detailOf(ten, 1, DisplayMode.AUTO));

        EvaluatedNotebook four = evaluated(List.of("a = 1", "b = 2", "c = 3", "d = 4", "a + b + c + d", "a * b"));
        assertEquals(List.of("= " + expansion(four, 5), "= " + shown(four, 5, DisplayMode.AUTO), "Sources : lignes 1, 2, 3 et 4",
                "Utilisée par aucune ligne"), detailOf(four, 5, DisplayMode.AUTO));
        assertEquals(List.of("= " + expansion(four, 6), "= " + shown(four, 6, DisplayMode.AUTO), "Sources : lignes 1 et 2",
                "Utilisée par aucune ligne"), detailOf(four, 6, DisplayMode.AUTO));
        assertEquals(List.of("= " + shown(four, 1, DisplayMode.AUTO), "Utilisée par 2 lignes"), detailOf(four, 1, DisplayMode.AUTO));
        assertEquals(List.of("= " + shown(four, 3, DisplayMode.AUTO), "Utilisée par 1 ligne"), detailOf(four, 3, DisplayMode.AUTO));

        // Two sources on the same line: the sentence names the line once.
        EvaluatedNotebook same = evaluated(List.of("5", "$1 * 2 + ans"));
        assertEquals(List.of("= " + expansion(same, 2), "= " + shown(same, 2, DisplayMode.AUTO), "Sources : ligne 1",
                "Utilisée par aucune ligne"), detailOf(same, 2, DisplayMode.AUTO));
        assertEquals(List.of("= " + shown(same, 1, DisplayMode.AUTO), "Utilisée par 1 ligne"), detailOf(same, 1, DisplayMode.AUTO));
    }

    @Test
    @Tag("acceptance")
    @DisplayName("Le résultat du détail suit le mode d'affichage : « = » puis le texte de ValueFormatter.format dans ce mode")
    void theResultFollowsTheDisplayMode() throws Exception {
        EvaluatedNotebook third = evaluated(List.of("1 / 3", "$1 * 6"));
        assertNotEquals(shown(third, 1, DisplayMode.AUTO), shown(third, 1, DisplayMode.FRACTION), "the modes show 1/3 differently");
        for (DisplayMode mode : DisplayMode.values()) {
            assertEquals(List.of("= " + shown(third, 1, mode), "Utilisée par 1 ligne"), detailOf(third, 1, mode), "line 1 in " + mode);
            assertEquals(List.of("= " + expansion(third, 2), "= " + shown(third, 2, mode), "Sources : ligne 1",
                    "Utilisée par aucune ligne"), detailOf(third, 2, mode), "line 2 in " + mode);
        }
    }

    @Test
    @Tag("acceptance")
    @DisplayName("Valeur approchée : le texte affiché tel quel (« ≈ … », jamais « ≈ ≈ », ou « |x| ≤ b »), puis « Valeur garantie entre L et U »")
    void anApproximateValueShowsItsTextAsItIsAndItsGuaranteedBounds() throws Exception {
        List<String> lines = List.of("√2", "r = √2", "r * 3", "√2 - √2", "-√2", "1 / 3");
        EvaluatedNotebook roots = evaluated(lines);
        for (int number = 1; number <= 5; number++) {
            assertFalse(roots.result(number).value().isExact(), "line " + number + " « " + lines.get(number - 1) + " » is approximate");
        }
        assertTrue(shown(roots, 1, DisplayMode.AUTO).startsWith("≈ "), "the formatter writes « ≈ »: " + shown(roots, 1, DisplayMode.AUTO));
        assertTrue(shown(roots, 4, DisplayMode.AUTO).startsWith("|x| ≤ "), "an interval around 0: " + shown(roots, 4, DisplayMode.AUTO));

        for (DisplayMode mode : List.of(DisplayMode.AUTO, DisplayMode.SCIENTIFIC)) {
            assertEquals(List.of(shown(roots, 1, mode), "Utilisée par aucune ligne", guaranteed(roots, 1)), detailOf(roots, 1, mode),
                    "√2 in " + mode);
            assertEquals(List.of(shown(roots, 2, mode), "Utilisée par 1 ligne", guaranteed(roots, 2)), detailOf(roots, 2, mode),
                    "r = √2 in " + mode);
            List<String> withSource = new ArrayList<>();
            LineExpansion.expand(roots, roots.result(3).id()).ifPresent(expansion -> withSource.add("= " + expansion));
            withSource.addAll(List.of(shown(roots, 3, mode), "Sources : ligne 2", "Utilisée par aucune ligne", guaranteed(roots, 3)));
            assertEquals(withSource, detailOf(roots, 3, mode), "r * 3 in " + mode);
            assertEquals(List.of(shown(roots, 4, mode), "Utilisée par aucune ligne", guaranteed(roots, 4)), detailOf(roots, 4, mode),
                    "√2 - √2 in " + mode);
            assertEquals(List.of(shown(roots, 5, mode), "Utilisée par aucune ligne", guaranteed(roots, 5)), detailOf(roots, 5, mode),
                    "-√2 in " + mode);
        }
        for (int number = 1; number <= lines.size(); number++) {
            for (String sentence : detailOf(roots, number, DisplayMode.AUTO)) {
                assertFalse(sentence.contains("≈ ≈") || sentence.startsWith("= ≈") || sentence.startsWith("= |x|"),
                        "line " + number + ": no sign is added to an approximate value: « " + sentence + " »");
            }
            boolean approximate = !roots.result(number).value().isExact();
            assertEquals(approximate, detailOf(roots, number, DisplayMode.AUTO).stream().anyMatch(s -> s.startsWith("Valeur garantie")),
                    "line " + number + ": only an approximate value has guaranteed bounds");
        }
        // The bounds of √2 and of -√2 are the usual ones.
        List<String> root = bounds(roots.result(1).value());
        assertEquals(List.of("1,41421356237", "1,41421356238"), root, "the guaranteed bounds of √2");
        List<String> opposite = bounds(roots.result(5).value());
        assertTrue(Set.of("-", "−").contains(opposite.get(0).substring(0, 1)) && opposite.get(0).endsWith("1,41421356238")
                        && Set.of("-", "−").contains(opposite.get(1).substring(0, 1)) && opposite.get(1).endsWith("1,41421356237"),
                "the guaranteed bounds of -√2: " + opposite);
    }

    @Test
    @Tag("acceptance")
    @DisplayName("Un titre, un commentaire, une ligne vide ou une ligne en erreur n'ont pas de détail")
    void aLineWithoutValueHasNoDetail() throws Exception {
        List<String> lines = List.of("# titre", "", "  # remarque", "total - remise", "1 + 1", "ans / 0");
        EvaluatedNotebook notebook = evaluated(lines);
        for (int number : new int[] {1, 2, 3, 4, 6}) {
            assertFalse(notebook.result(number).hasValue(), "line " + number + " « " + lines.get(number - 1) + " » has no value");
            assertEquals(List.of(), detailOf(notebook, number, DisplayMode.AUTO), "line " + number + " « " + lines.get(number - 1) + " »");
        }
        int users = notebook.usedBy(notebook.result(5).id()).size();
        assertEquals(List.of("= " + shown(notebook, 5, DisplayMode.AUTO), "Utilisée par " + (users == 0 ? "aucune ligne"
                : users == 1 ? "1 ligne" : users + " lignes")), detailOf(notebook, 5, DisplayMode.AUTO), "line 5, used by " + users);
    }

    // ================================================================================================ the guaranteed bounds

    // ValueFormatter.guaranteedBounds: at most 12 significant digits, L ≤ lower, U ≥ upper, rounded outwards.
    @Property(tries = 300)
    void theGuaranteedBoundsEncloseTheValue(@ForAll("approximateValues") Enclosure enclosure) throws Exception {
        CertifiedValue value = enclosure.interval();
        List<String> texts = bounds(value);
        BigDecimal lower = readBound(texts.get(0));
        BigDecimal upper = readBound(texts.get(1));
        String context = "bounds " + texts + " of " + value;
        assertTrue(compare(lower, value.lower()) <= 0, "L ≤ lower: " + context);
        assertTrue(compare(upper, value.upper()) >= 0, "U ≥ upper: " + context);
        // Rounded outwards to (at most) 12 significant digits, not anywhere below or above: within 10⁻¹⁰ of each bound.
        assertTrue(close(lower, value.lower()), "L is the lower bound rounded, not further: " + context);
        assertTrue(close(upper, value.upper()), "U is the upper bound rounded, not further: " + context);
    }

    @Provide
    Arbitrary<Enclosure> approximateValues() {
        return RationalGenerators.enclosures(200).filter(enclosure -> !enclosure.interval().isExact());
    }

    // ================================================================================================ dependencies

    @Test
    @Tag("acceptance")
    @DisplayName("calc.detail n'importe ni Swing, ni AWT, ni calc.ui, ni calc.persistence, ni calc.document ; le web peut l'importer")
    void theDetailPackageStaysBelowTheInterfaceAndTheWebMayUseIt() throws IOException {
        Path folder = Path.of("src", "main", "java", "calc", "detail");
        assertTrue(Files.isRegularFile(folder.resolve("LineDetailText.java")), "src/main/java/calc/detail/LineDetailText.java exists");
        List<String> forbidden = List.of("javax.swing", "java.awt", "calc.ui", "calc.persistence", "calc.document");
        Pattern importLine = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
        List<String> imports = new ArrayList<>();
        List<String> faults = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = importLine.matcher(source);
                while (matcher.find()) {
                    imports.add(file.getFileName() + ": " + matcher.group(1));
                }
                String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
                for (String prefix : forbidden) {
                    if (Pattern.compile("\\b" + Pattern.quote(prefix) + "\\.").matcher(code).find()) {
                        faults.add(file.getFileName() + " uses " + prefix);
                    }
                }
            }
        }
        assertTrue(imports.stream().anyMatch(entry -> entry.contains(": calc.notebook.")),
                "the scan sees the imports of calc.detail, which reads calc.notebook: " + imports);
        assertEquals(List.of(), faults, "calc.detail depends on nothing of Swing, AWT, the interface, the files or the documents");

        String web = Files.readString(Path.of("src", "test", "java", "calc", "web", "WebDependenciesTest.java"), StandardCharsets.UTF_8);
        Matcher list = Pattern.compile("(?s)FORBIDDEN\\s*=\\s*List\\.of\\((.*?)\\);").matcher(web);
        assertTrue(list.find(), "WebDependenciesTest keeps its list FORBIDDEN");
        assertFalse(list.group(1).contains("calc.detail"), "WebDependenciesTest lets the web import calc.detail: " + list.group(1));
    }

    // ================================================================================================ the window

    @Test
    @Tag("ui")
    @Tag("acceptance")
    @DisplayName("Un seul composant lineDetail, sous la ligne active ; ses lignes sont LineDetailText.of(…) de la ligne active")
    void oneLineDetailUnderTheActiveLineShowsItsSentences() throws Exception {
        requireDisplay();
        EvaluatedNotebook expected = evaluated(DEVIS_AND_ROOT);
        NotebookFrame frame = window(DEVIS_AND_ROOT, 5);
        onEventThread(() -> {
            List<String> lines = assertDetailUnderActiveLine(frame, 4, detailOf(expected, 5, DisplayMode.AUTO));
            assertEquals(4, lines.size(), "the detail of the total: " + lines);
            assertEquals(shape("1250 + 3480 + 437,5"), shape(withoutEquals(lines.get(0))), "the expansion of the total: " + lines.get(0));
            assertEquals("= " + frame.shown().rows().get(4).result(), lines.get(1), "the result is exactly the one line 5 shows");
            assertEquals("Sources : lignes 2, 3 et 4", lines.get(2));
            assertEquals("Utilisée par 1 ligne", lines.get(3));
            return null;
        });

        // The detail moves with the active line: the approximate root.
        activate(frame, 8);
        onEventThread(() -> {
            List<String> lines = assertDetailUnderActiveLine(frame, 8, detailOf(expected, 9, DisplayMode.AUTO));
            assertEquals(frame.shown().rows().get(8).result(), lines.get(0), "the result of √2, as line 9 shows it");
            assertTrue(lines.get(lines.size() - 1).startsWith("Valeur garantie entre "), "the bounds of √2: " + lines);
            return null;
        });

        // And back to line 8 of Devis cuisine.
        activate(frame, 7);
        onEventThread(() -> {
            List<String> lines = assertDetailUnderActiveLine(frame, 7, detailOf(expected, 8, DisplayMode.AUTO));
            assertEquals("Sources : ligne 7", lines.get(2));
            assertEquals("Utilisée par aucune ligne", lines.get(3));
            return null;
        });
    }

    @Test
    @Tag("ui")
    @Tag("acceptance")
    @DisplayName("Le détail est vide et sans hauteur pour un titre, un commentaire, une ligne vide, une erreur ou la ligne nouvelle")
    void theDetailIsEmptyAndTakesNoHeightForALineWithoutValue() throws Exception {
        requireDisplay();
        int blankRow = WITHOUT_VALUES.size();
        NotebookFrame frame = window(WITHOUT_VALUES, blankRow + 1);
        List<Integer> heights = onEventThread(() -> {
            assertEquals(blankRow, frame.shown().activeIndex(), "the blank row at the end is active");
            assertNoDetail(frame, "the blank row at the end");
            List<Integer> each = new ArrayList<>();
            for (int index = 0; index < frame.rowCount(); index++) {
                each.add(frame.row(index).getHeight());
            }
            return each;
        });
        // The comment, the error, the title, then the empty line (left last: leaving it could remove it).
        for (int line : List.of(12, 11, 1, 10)) {
            int index = line - 1;
            activate(frame, index);
            onEventThread(() -> {
                assertEquals(WITHOUT_VALUES.size() + 1, frame.rowCount(), "no line came or went");
                String what = "line " + line + " « " + WITHOUT_VALUES.get(index) + " »";
                assertFalse(frame.shown().rows().get(index).hasValue(), what + " has no value");
                assertNoDetail(frame, what);
                assertEquals(heights.get(index), frame.row(index).getHeight(), what + ": active, its row is as high as before");
                return null;
            });
        }
    }

    @Test
    @Tag("ui")
    @Tag("acceptance")
    @DisplayName("Ligne 5 active : « ◆ » sur les lignes 2, 3 et 4, « source de la ligne active » ; à la ligne 8, seule la 7 est marquée")
    void theSourcesOfTheActiveLineAreMarked() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(DEVIS, 5);
        onEventThread(() -> {
            assertMarks(frame, Set.of(2, 3, 4), "line 5 active");
            return null;
        });
        activate(frame, 7);
        onEventThread(() -> {
            assertMarks(frame, Set.of(7), "line 8 active");
            return null;
        });
        activate(frame, 4);
        onEventThread(() -> {
            assertMarks(frame, Set.of(2, 3, 4), "line 5 active again");
            return null;
        });
        activate(frame, 0);
        onEventThread(() -> {
            assertMarks(frame, Set.of(), "the title active");
            return null;
        });
    }

    @Test
    @Tag("ui")
    @Tag("acceptance")
    @DisplayName("Changer de ligne active ne réaffiche que les lignes dont la marque change, et les deux lignes actives")
    void changingTheActiveLineOnlyRepaintsTheRowsWhoseMarkChanges() throws Exception {
        requireDisplay();
        List<String> lines = new ArrayList<>(DEVIS);
        for (int count = 0; count < 30; count++) {
            lines.add("10 * " + (count + 3));
        }
        NotebookFrame frame = window(lines, 5);
        onEventThread(() -> {
            assertMarks(frame, Set.of(2, 3, 4), "line 5 active");
            return null;
        });

        RepaintRecorder recorder = new RepaintRecorder();
        RepaintManager previous = onEventThread(() -> {
            RepaintManager current = RepaintManager.currentManager(frame);
            RepaintManager.setCurrentManager(recorder);
            return current;
        });
        Set<Integer> touched;
        try {
            activate(frame, 7);
            touched = onEventThread(() -> {
                Set<Integer> numbers = new TreeSet<>();
                for (Component component : recorder.repainted) {
                    for (Component each = component; each != null; each = each.getParent()) {
                        if (each instanceof NotebookRowView row) {
                            numbers.add(row.index + 1);
                            break;
                        }
                    }
                }
                return numbers;
            });
        } finally {
            onEventThread(() -> {
                RepaintManager.setCurrentManager(previous);
                return null;
            });
        }
        onEventThread(() -> {
            assertMarks(frame, Set.of(7), "line 8 active");
            return null;
        });
        assertTrue(touched.containsAll(Set.of(2, 7)), "the rows whose mark changes are repainted: " + touched);
        Set<Integer> allowed = Set.of(2, 3, 4, 5, 7, 8);
        Set<Integer> extra = new TreeSet<>(touched);
        extra.removeAll(allowed);
        assertEquals(Set.of(), extra, "only the rows whose mark changes (2, 3, 4, 7) and the lines left and reached (5, 8) are "
                + "repainted, of " + (lines.size() + 1) + " rows; repainted: " + touched);
    }

    @Test
    @Tag("ui")
    @Tag("acceptance")
    @DisplayName("Le détail suit les changements : meubles = 3 500 validé, la ligne 5 montre le nouveau développement et le nouveau résultat")
    void theDetailFollowsTheChanges() throws Exception {
        requireDisplay();
        NotebookStore store = store();
        store.saveCurrent(Notebook.of(DEVIS), DisplayMode.AUTO);
        NotebookPresenter presenter = onEventThread(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            return NotebookPresenter.open(store, DisplayModeStore.noop(), NotebookDialogs.cancelling(),
                    NotebookPresenter.newRecalculationExecutor(), this::blockingOnTheTotal);
        });
        NotebookFrame frame = onEventThread(() -> frame(presenter));
        awaitSettled(frame);

        List<String> before = detailOf(evaluated(DEVIS), 5, DisplayMode.AUTO);
        activate(frame, 2);
        onEventThread(() -> {
            frame.row(2).field.setText(NEW_FURNITURE);
            return null;
        });
        gateArmed.set(true);
        press(frame, 2, KeyEvent.VK_ENTER);
        assertTrue(reached.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "the calculation never reached the total again");
        // The total becomes active while it is being calculated again: the detail must follow when its result comes.
        activate(frame, 4);
        released.countDown();
        awaitSettled(frame);

        List<String> changed = new ArrayList<>(DEVIS);
        changed.set(2, NEW_FURNITURE);
        EvaluatedNotebook expected = evaluated(changed);
        onEventThread(() -> {
            assertEquals(NEW_FURNITURE, frame.shown().rows().get(2).text(), "line 3 was validated");
            List<String> lines = assertDetailUnderActiveLine(frame, 4, detailOf(expected, 5, DisplayMode.AUTO));
            assertEquals(shape("1250 + 3500 + 437,5"), shape(withoutEquals(lines.get(0))), "the new expansion: " + lines.get(0));
            assertEquals("= " + shown(expected, 5, DisplayMode.AUTO), lines.get(1), "the new result");
            assertEquals("= " + frame.shown().rows().get(4).result(), lines.get(1), "the result line 5 shows");
            assertTrue(lines.get(1).endsWith("187,5"), "5 187,5: " + lines.get(1));
            assertNotEquals(before, lines, "the detail of the total changed");
            assertMarks(frame, Set.of(2, 3, 4), "line 5 active after the edit");
            return null;
        });
    }

    // ================================================================================================ helpers: the detail

    /** The sentences of calc.detail.LineDetailText.of for the line numbered {@code number}, from 1. */
    private static List<String> detailOf(EvaluatedNotebook notebook, int number, DisplayMode mode) throws Exception {
        Class<?> type;
        try {
            type = Class.forName("calc.detail.LineDetailText");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("calc.detail.LineDetailText does not exist yet", missing);
        }
        Method of;
        try {
            of = type.getMethod("of", EvaluatedNotebook.class, LineId.class, DisplayMode.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("calc.detail.LineDetailText has no public of(EvaluatedNotebook, LineId, DisplayMode) yet", missing);
        }
        assertTrue(Modifier.isStatic(of.getModifiers()), "LineDetailText.of is static");
        Object value = invoke(of, notebook, notebook.result(number).id(), mode);
        assertTrue(value instanceof List<?>, "LineDetailText.of returns a List of sentences, returns " + value);
        List<String> sentences = new ArrayList<>();
        for (Object sentence : (List<?>) value) {
            assertTrue(sentence instanceof String, "each sentence is a String: " + value);
            sentences.add((String) sentence);
        }
        return List.copyOf(sentences);
    }

    /** The two texts of ValueFormatter.guaranteedBounds(value): L then U. */
    private static List<String> bounds(CertifiedValue value) throws Exception {
        Method method;
        try {
            method = ValueFormatter.class.getMethod("guaranteedBounds", CertifiedValue.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("ValueFormatter has no public guaranteedBounds(CertifiedValue) yet", missing);
        }
        assertTrue(Modifier.isStatic(method.getModifiers()), "ValueFormatter.guaranteedBounds is static");
        Object result = invoke(method, value);
        List<Object> parts = new ArrayList<>();
        if (result instanceof List<?> list) {
            parts.addAll(list);
        } else if (result instanceof String[] array) {
            parts.addAll(List.of((Object[]) array));
        } else if (result != null && result.getClass().isRecord()) {
            for (RecordComponent component : result.getClass().getRecordComponents()) {
                Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                parts.add(accessor.invoke(result));
            }
        } else {
            throw new AssertionError("guaranteedBounds returns a List, a String[] or a record of two texts, returns " + result);
        }
        assertEquals(2, parts.size(), "two bounds: " + parts);
        assertTrue(parts.get(0) instanceof String && parts.get(1) instanceof String, "the bounds are texts: " + parts);
        return List.of((String) parts.get(0), (String) parts.get(1));
    }

    private static String guaranteed(EvaluatedNotebook notebook, int number) throws Exception {
        List<String> texts = bounds(notebook.result(number).value());
        return "Valeur garantie entre " + texts.get(0) + " et " + texts.get(1);
    }

    private static Object invoke(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    private static EvaluatedNotebook evaluated(List<String> lines) throws Exception {
        return NotebookEvaluator.evaluate(Notebook.of(lines));
    }

    private static String shown(EvaluatedNotebook notebook, int number, DisplayMode mode) {
        LineResult result = notebook.result(number);
        assertTrue(result.hasValue(), "line " + number + " « " + result.text() + " » has a value");
        return ValueFormatter.format(result.value(), mode).text();
    }

    private static String expansion(EvaluatedNotebook notebook, int number) {
        return LineExpansion.expand(notebook, notebook.result(number).id())
                .orElseThrow(() -> new AssertionError("line " + number + " has an expansion (E06)"));
    }

    private static String withoutEquals(String sentence) {
        assertTrue(sentence.startsWith("= "), "the expansion starts with « = »: « " + sentence + " »");
        return sentence.substring(2);
    }

    // The tree of an expression without its spans: two texts that read the same have the same shape.
    private static String shape(String expression) throws ExpressionException {
        Node tree = EXPANSION_PARSER.parse(expression).tree()
                .orElseThrow(() -> new AssertionError("« " + expression + " » is an expression"));
        return shape(tree) + " / " + CanonicalPrinter.print(tree);
    }

    private static String shape(Node node) {
        return switch (node) {
            case Node.NumberLiteral literal -> literal.value().toString();
            case Node.Variable variable -> "var " + variable.name();
            case Node.Ans ans -> "ans";
            case Node.LineRef reference -> "$" + reference.line();
            case Node.Unary unary -> "(" + unary.sign() + " " + shape(unary.operand()) + ")";
            case Node.Binary binary -> "(" + shape(binary.left()) + " " + binary.operator() + " " + shape(binary.right()) + ")";
            case Node.Sqrt sqrt -> "sqrt(" + shape(sqrt.operand()) + ")";
            case Node.Assignment assignment -> assignment.name() + " := " + shape(assignment.value());
        };
    }

    // ================================================================================================ helpers: the bounds

    private static final String SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹";

    /** The value of a bound: sign « - » or « − », decimal comma, optional « × 10ⁿ » or « eN »; at most 12 significant digits. */
    private static BigDecimal readBound(String text) {
        String rest = text;
        boolean negative = rest.startsWith("-") || rest.startsWith("−");
        if (negative) {
            rest = rest.substring(1);
        }
        int exponent = 0;
        int times = rest.indexOf('×');
        int letter = Math.max(rest.indexOf('e'), rest.indexOf('E'));
        if (times >= 0) {
            String power = rest.substring(times + 1).strip();
            assertTrue(power.startsWith("10") && power.length() > 2, "a power of ten « × 10ⁿ » in « " + text + " »");
            exponent = superscript(power.substring(2), text);
            rest = rest.substring(0, times).strip();
        } else if (letter >= 0) {
            exponent = Integer.parseInt(rest.substring(letter + 1));
            rest = rest.substring(0, letter);
        }
        String mantissa = rest.replace(" ", "").replace(NO_BREAK_SPACE, "").replace(" ", "");
        assertTrue(mantissa.matches("\\d+(,\\d+)?"), "a bound is a decimal with a comma, no « ≈ » nor « … »: « " + text + " »");
        String digits = mantissa.replace(",", "").replaceFirst("^0+", "");
        if (!mantissa.contains(",")) {
            digits = digits.replaceFirst("0+$", "");
        }
        assertTrue(digits.length() <= 12, "at most 12 significant digits: « " + text + " » has " + digits.length());
        BigDecimal value = new BigDecimal(mantissa.replace(',', '.')).scaleByPowerOfTen(exponent);
        return negative ? value.negate() : value;
    }

    private static int superscript(String power, String text) {
        StringBuilder ascii = new StringBuilder();
        for (char each : power.toCharArray()) {
            int digit = SUPERSCRIPTS.indexOf(each);
            if (digit >= 0) {
                ascii.append(digit);
            } else if (each == '⁻' && ascii.isEmpty()) {
                ascii.append('-');
            } else {
                throw new AssertionError("an exponent in superscript digits: « " + text + " »");
            }
        }
        return Integer.parseInt(ascii.toString());
    }

    // The sign of decimal - rational, exactly.
    private static int compare(BigDecimal decimal, Rational rational) {
        BigInteger[] fraction = fraction(decimal);
        return fraction[0].multiply(rational.denominator()).compareTo(rational.numerator().multiply(fraction[1]));
    }

    // Whether |decimal - rational| ≤ |rational| × 10⁻¹⁰, and decimal = 0 when rational = 0.
    private static boolean close(BigDecimal decimal, Rational rational) {
        BigInteger[] fraction = fraction(decimal);
        BigInteger difference = fraction[0].multiply(rational.denominator()).subtract(rational.numerator().multiply(fraction[1])).abs();
        BigInteger denominator = fraction[1].multiply(rational.denominator());
        return difference.multiply(BigInteger.TEN.pow(10)).multiply(rational.denominator())
                .compareTo(rational.numerator().abs().multiply(denominator)) <= 0;
    }

    private static BigInteger[] fraction(BigDecimal decimal) {
        int scale = decimal.scale();
        return scale >= 0
                ? new BigInteger[] {decimal.unscaledValue(), BigInteger.TEN.pow(scale)}
                : new BigInteger[] {decimal.unscaledValue().multiply(BigInteger.TEN.pow(-scale)), BigInteger.ONE};
    }

    // ================================================================================================ helpers: the window

    // On the event thread: the one lineDetail, under the active row, shows these sentences; returns them.
    private static List<String> assertDetailUnderActiveLine(NotebookFrame frame, int activeIndex, List<String> sentences) {
        assertEquals(activeIndex, frame.shown().activeIndex(), "line " + (activeIndex + 1) + " is active");
        assertFalse(sentences.isEmpty(), "line " + (activeIndex + 1) + " has a detail");
        Component detail = lineDetail(frame);
        List<String> lines = detailLines(detail);
        assertEquals(sentences, lines, "lineDetail shows LineDetailText.of of the active line " + (activeIndex + 1));

        Container rowsPanel = rowsPanel(frame);
        Rectangle bounds = boundsIn(detail, rowsPanel);
        Rectangle field = boundsIn(frame.row(activeIndex).field, rowsPanel);
        assertTrue(visibleUpTo(detail, rowsPanel) && bounds.width > 0 && bounds.height > 0, "lineDetail is shown: " + bounds);
        assertTrue(bounds.y >= field.y + field.height, "lineDetail " + bounds + " is under the field of the active line " + field);
        if (activeIndex + 1 < frame.rowCount()) {
            Rectangle next = boundsIn(frame.row(activeIndex + 1), rowsPanel);
            assertTrue(bounds.y + bounds.height <= next.y, "lineDetail " + bounds + " is above the next line " + next);
        }
        return lines;
    }

    // On the event thread: the one lineDetail shows nothing and takes no height.
    private static void assertNoDetail(NotebookFrame frame, String what) {
        Component detail = lineDetail(frame);
        assertEquals(List.of(), detailLines(detail), what + ": lineDetail is empty");
        assertTrue(!detail.isVisible() || detail.getHeight() == 0, what + ": lineDetail takes no height, " + detail.getBounds());
    }

    // On the event thread: « ◆ » and « source de la ligne active » on these lines (from 1), on none of the others.
    private static void assertMarks(NotebookFrame frame, Set<Integer> sources, String when) {
        for (int index = 0; index < frame.rowCount(); index++) {
            int number = index + 1;
            NotebookRowView row = frame.row(index);
            boolean source = sources.contains(number);
            assertEquals(source ? MARK : "", textOf(part(row, "sourceMark")), when + ": the sourceMark of line " + number);
            String description = String.valueOf(row.field.getAccessibleContext().getAccessibleDescription());
            assertEquals(source, description.contains(SOURCE_OF_ACTIVE),
                    when + ": the accessible description of line " + number + " says « " + SOURCE_OF_ACTIVE + " » only for a source: "
                            + description);
        }
    }

    private static Component lineDetail(NotebookFrame frame) {
        List<Component> found = descendants(frame.getRootPane()).stream().filter(each -> "lineDetail".equals(each.getName())).toList();
        assertEquals(1, found.size(), "the window holds one component named lineDetail, holds " + found.size());
        return found.get(0);
    }

    // The lines of text of lineDetail: its own text or the texts of its visible parts, from top to bottom.
    private static List<String> detailLines(Component detail) {
        List<Component> parts = new ArrayList<>();
        parts.add(detail);
        if (detail instanceof Container container) {
            parts.addAll(descendants(container));
        }
        List<Component> shown = parts.stream().filter(LineDetailAcceptanceTest::hasText)
                .filter(part -> part == detail || visibleUpTo(part, detail))
                .sorted(Comparator.comparingInt(part -> part == detail ? 0 : SwingUtilities.convertPoint(part.getParent(),
                        new Point(part.getX(), part.getY()), detail).y))
                .toList();
        List<String> lines = new ArrayList<>();
        for (Component part : shown) {
            for (String line : textOf(part).split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    // Whether the component and its parents, up to the ancestor excluded, are visible.
    private static boolean visibleUpTo(Component component, Component ancestor) {
        for (Component each = component; each != null && each != ancestor; each = each.getParent()) {
            if (!each.isVisible()) {
                return false;
            }
        }
        return true;
    }

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
     * A window as Main builds it, in the light theme, with {@code lines} opened in one piece and the line numbered
     * {@code activeLine} (from 1) active, laid out at {@value #WIDTH} × {@value #HEIGHT} once every line is calculated and every
     * row built. It is never shown.
     */
    private NotebookFrame window(List<String> lines, int activeLine) throws Exception {
        NotebookStore store = store();
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        LoadedNotebook loaded = store.load();
        NotebookFrame frame = onEventThread(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
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

    // What the mouse does: a focus gained on the field of the row activates its line; then the window is laid out again.
    private static void activate(NotebookFrame frame, int index) throws Exception {
        onEventThread(() -> {
            if (frame.shown().activeIndex() != index) {
                JTextComponent field = frame.row(index).field;
                for (FocusListener listener : field.getFocusListeners()) {
                    listener.focusGained(new FocusEvent(field, FocusEvent.FOCUS_GAINED));
                }
            }
            return null;
        });
        flushEvents();
        onEventThread(() -> {
            frame.validate();
            return null;
        });
        assertEquals(index, (int) onEventThread(() -> frame.shown().activeIndex()), "line " + (index + 1) + " is active");
    }

    // Runs the action the field of the row binds to the key, as Swing does when the key is pressed in it.
    private static void press(NotebookFrame frame, int row, int keyCode) throws Exception {
        onEventThread(() -> {
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

    // Once armed, stops the calculating thread on the total of Devis cuisine, until the test lets it go.
    private LineResult blockingOnTheTotal(String text, Supplier<LineResult> evaluation) {
        if (gateArmed.get() && text.equals(DEVIS.get(4))) {
            reached.countDown();
            try {
                assertTrue(released.await(PATIENCE_SECONDS, TimeUnit.SECONDS), "nobody released the total");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        }
        return evaluation.get();
    }

    /** Records every component asked to repaint, then lets Swing do as usual. */
    private static final class RepaintRecorder extends RepaintManager {
        final Set<Component> repainted = new HashSet<>();

        @Override
        public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
            repainted.add(component);
            super.addDirtyRegion(component, x, y, width, height);
        }
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
