package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.format.DisplayMode;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import calc.ui.widget.Contrast;
import java.awt.Component;
import java.awt.Container;
import java.awt.FocusTraversalPolicy;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of E16, the delivery of the redesign (SPEC §1 criteria of success, §7 end-to-end verification): the pictures after,
 * the complete keyboard route on « Devis cuisine », the accessible names, and the documentation brought up to date.
 *
 * <p>The ui methods carry {@code @Tag("ui")} and the headless guard; the documentary ones need no display. The pictures in the
 * repository are only read (existence and size): they are never compared with a fresh rendering, since Java2D and FlatLaf draw
 * differently from one system to another and {@code check} runs on Windows, macOS and Linux. Determinism is checked within one
 * run: two successive renderings of the same notebook in the same theme give the same pixels.
 *
 * <p>The keyboard route uses only key events handed to the component that has the keyboard focus, as the keyboard does: no
 * mouse, no click, no direct call of the presenter. It is skipped when the system gives no focus to a new window.
 *
 * <p>Documentation, read from the repository root (the working directory of the tests):
 * <ul>
 *   <li>{@code docs/design/avant-apres.md} shows, on one line (a table row), each picture before ({@code docs/images/avant}) next
 *       to its light picture after ({@code docs/images}), and shows the dark pictures after; every relative link and image
 *       resolves; it gives the rise in size of the Windows archive in bytes as ADR 0017 writes it; a table of the contrast
 *       pairs of {@code tokens.properties} whose header names « Clair » before « Sombre » and whose row of each pair holds a
 *       cell with its key ({@code contrast.text.primary}) followed, as the first two decimal numbers of the row, by its ratio in
 *       the light theme then in the dark one (to the precision written); and, on a line naming
 *       {@code NotebookFrameResponsivenessTest} and on one naming {@code BackgroundRecalculationAcceptanceTest}, a range of
 *       measured durations (« 120 à 180 ms »), the 5000 lines being named.</li>
 *   <li>{@code docs/GUIDE-UTILISATEUR.md}, {@code docs/ARCHITECTURE.md}, {@code docs/TESTS.md}: see each test.</li>
 * </ul>
 */
@Tag("acceptance")
@DisplayName("Livraison de la refonte : captures, parcours clavier, noms accessibles, documentation")
final class RedesignDeliveryAcceptanceTest {
    private static final Path IMAGES = Path.of("docs", "images");
    private static final Path BEFORE = IMAGES.resolve("avant");
    private static final Path BEFORE_AFTER = Path.of("docs", "design", "avant-apres.md");
    private static final Path ADR_0017 = Path.of("docs", "adr", "0017-flatlaf-et-polices-embarquees.md");
    private static final Path GUIDE = Path.of("docs", "GUIDE-UTILISATEUR.md");
    private static final Path ARCHITECTURE = Path.of("docs", "ARCHITECTURE.md");
    private static final Path TESTS = Path.of("docs", "TESTS.md");
    private static final Path CHANGELOG = Path.of("CHANGELOG.md");
    private static final List<String> AFTER_PICTURES = List.of(
            "carnet-devis-clair.png", "carnet-devis-sombre.png", "carnet-vide-clair.png", "carnet-vide-sombre.png");
    private static final int PICTURE_WIDTH = 1100;
    private static final int PICTURE_HEIGHT = 720;
    /** The line {@code total = plan + meubles + pose} of the Devis cuisine, numbered from 1. */
    private static final int TOTAL_LINE = 5;
    private static final long PATIENCE_SECONDS = 60;
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    private static final int MOST_TABS = 40;
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(([^)\\s]+)[^)]*\\)|<img\\b[^>]*\\bsrc=\"([^\"]+)\"");
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)[^)]*\\)|\\b(?:src|href)=\"([^\"]+)\"");
    private static final Pattern DECIMAL = Pattern.compile("(?<![\\d.,])(\\d+)[.,](\\d+)");
    private static final Pattern DURATION_RANGE = Pattern.compile(
            "\\d+(?:[.,]\\d+)?\\s*(?:ms|s)?\\s*(?:à|-|–|—)\\s*\\d+(?:[.,]\\d+)?\\s*(?:ms|s)(?![\\p{L}])");

    @TempDir
    Path temp;

    private final List<NotebookStore> stores = new ArrayList<>();
    private final List<NotebookFrame> frames = new ArrayList<>();
    private final Map<NotebookFrame, NotebookPresenter> presenters = new LinkedHashMap<>();
    private LookAndFeel original;

    @BeforeEach
    void rememberTheLookAndFeel() {
        original = UIManager.getLookAndFeel();
    }

    @AfterEach
    void cleanUp() throws Exception {
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

    // ================================================================================================ the pictures

    @Test
    @DisplayName("docs/images : carnet-devis-clair, carnet-devis-sombre, carnet-vide-clair et carnet-vide-sombre se relisent en 1100 × 720")
    void theFourPicturesAfterAreInTheRepository() throws Exception {
        for (String name : AFTER_PICTURES) {
            Path file = IMAGES.resolve(name);
            assertTrue(Files.isRegularFile(file), file + " must exist");
            BufferedImage image = ImageIO.read(file.toFile());
            assertNotNull(image, file + " must be a PNG that ImageIO reads");
            assertEquals(PICTURE_WIDTH, image.getWidth(), name + " width");
            assertEquals(PICTURE_HEIGHT, image.getHeight(), name + " height");
            Set<Integer> colours = new HashSet<>();
            for (int y = 0; y < image.getHeight(); y += 3) {
                for (int x = 0; x < image.getWidth(); x += 3) {
                    colours.add(image.getRGB(x, y) & 0xFFFFFF);
                }
            }
            assertTrue(colours.size() >= 3, name + " looks blank: " + colours.size() + " colours");
        }
    }

    @Test
    @Tag("ui")
    @DisplayName("Dans une même exécution, deux rendus de ScreenshotGenerator (même carnet, même thème) sont identiques pixel à pixel")
    void twoRendersOfTheSameNotebookInTheSameThemeAreIdentical() throws Exception {
        requireDisplay();
        for (Theme theme : Theme.values()) {
            BufferedImage first = ScreenshotGenerator.render(ScreenshotGenerator.DEMO, TOTAL_LINE, WIDTH, HEIGHT, 1.0, theme);
            BufferedImage second = ScreenshotGenerator.render(ScreenshotGenerator.DEMO, TOTAL_LINE, WIDTH, HEIGHT, 1.0, theme);
            assertSamePixels(first, second, "Devis cuisine, " + theme);
            BufferedImage emptyFirst = ScreenshotGenerator.render(List.of(), 1, WIDTH, HEIGHT, 1.0, theme);
            BufferedImage emptySecond = ScreenshotGenerator.render(List.of(), 1, WIDTH, HEIGHT, 1.0, theme);
            assertSamePixels(emptyFirst, emptySecond, "empty notebook, " + theme);
        }
    }

    // ================================================================================================ the keyboard route

    @Test
    @Tag("ui")
    @DisplayName("Parcours au clavier sans souris : Devis cuisine, ↑ jusqu'à total, Ctrl+K « sombre », Tab jusqu'à Fraction, Ctrl+Z")
    void theWholeRouteRunsFromTheKeyboard() throws Exception {
        requireDisplay();
        NotebookFrame frame = window();
        showWithFocusOnLine(frame, 0);
        assertFocusIsShownAndNamed("the empty notebook, line 1");

        // 1. The 8 lines of Devis cuisine, each typed in the line that has the focus and validated with Enter.
        List<List<String>> history = new ArrayList<>();
        history.add(notebookTexts(frame));
        List<String> demo = ScreenshotGenerator.DEMO;
        for (int i = 0; i < demo.size(); i++) {
            int row = i;
            type(frame.row(row).field, demo.get(i));
            assertEquals(demo.get(i), onEdt(() -> frame.row(row).field.getText()), "line " + (i + 1) + " is typed");
            press(KeyEvent.VK_ENTER, 0);
            awaitSettled(frame);
            assertTrue(await(() -> focusOwner() == frame.row(row + 1).field),
                    "after Enter on line " + (i + 1) + ", the focus is on line " + (i + 2) + ", not on " + onEdt(() -> describe(focusOwner())));
            assertFocusIsShownAndNamed("Enter on line " + (i + 1));
            history.add(notebookTexts(frame));
        }
        assertEquals(demo, notebookTexts(frame), "the notebook holds the Devis cuisine");

        // 2. ↑ up to the line total, whose detail says it is used by one line.
        for (int row = demo.size(); row > TOTAL_LINE - 1; row--) {
            press(KeyEvent.VK_UP, 0);
            awaitSettled(frame);
            int expected = row - 1;
            assertTrue(await(() -> focusOwner() == frame.row(expected).field), "↑ moves the focus to line " + (expected + 1));
            assertFocusIsShownAndNamed("↑ to line " + (expected + 1));
        }
        assertEquals(TOTAL_LINE - 1, (int) onEdt(() -> frame.shown().activeIndex()), "the line total is active");
        assertTrue(await(() -> detailTexts(frame).contains("Utilisée par 1 ligne")),
                "the detail of the line total reads « Utilisée par 1 ligne »: " + onEdt(() -> detailTexts(frame)));

        // 3. Ctrl+K, « sombre », Enter: the dark theme is applied to the window.
        press(KeyEvent.VK_K, menuMask());
        assertTrue(await(() -> isPaletteOpen(frame)), "Ctrl+K opens the command palette");
        assertTrue(await(() -> focusOwner() == part(palette(frame), "commandPaletteSearch")),
                "the focus is in the search of the palette, not in " + onEdt(() -> describe(focusOwner())));
        assertFocusIsShownAndNamed("the palette open");
        type((JTextComponent) onEdt(() -> part(palette(frame), "commandPaletteSearch")), "sombre");
        assertFocusIsShownAndNamed("« sombre » typed in the palette");
        press(KeyEvent.VK_ENTER, 0);
        awaitSettled(frame);
        assertTrue(await(() -> !isPaletteOpen(frame)), "Enter runs the entry and closes the palette");
        assertTrue(await(() -> "calc.ui.theme.CalcDarkLaf".equals(UIManager.getLookAndFeel().getClass().getName())),
                "the dark theme is applied: " + onEdt(() -> UIManager.getLookAndFeel().getClass().getName()));
        assertEquals(Theme.DARK, onEdt(() -> frame.shown().effectiveTheme()), "the window draws the dark theme");
        assertTrue(await(() -> focusOwner() != null), "a component has the focus after the palette");
        assertFocusIsShownAndNamed("the palette closed");
        assertEquals(demo, notebookTexts(frame), "the theme changes no line");

        // 4. Tab up to the segmented control, then Fraction with the arrows or Space.
        boolean reached = false;
        for (int i = 0; i < MOST_TABS && !reached; i++) {
            pressAndWaitForFocusChange(KeyEvent.VK_TAB, 0);
            assertFocusIsShownAndNamed("Tab number " + (i + 1) + " toward the display modes");
            reached = onEdt(() -> isModeButton(focusOwner()));
        }
        assertTrue(reached, "Tab reaches a segment of the display modes within " + MOST_TABS + " presses");
        for (int i = 0; i < 2 * DisplayMode.values().length + 2 && !fractionChosen(frame); i++) {
            Component owner = onEdt(RedesignDeliveryAcceptanceTest::focusOwner);
            assertTrue(onEdt(() -> isModeButton(owner)), "the focus stays in the display modes: " + onEdt(() -> describe(owner)));
            if ("modeButtonFRACTION".equals(owner.getName())) {
                pressAndRelease(KeyEvent.VK_SPACE, ' ');
            } else {
                press(KeyEvent.VK_RIGHT, 0);
                if (onEdt(RedesignDeliveryAcceptanceTest::focusOwner) == owner && !fractionChosen(frame)) {
                    pressAndWaitForFocusChange(KeyEvent.VK_TAB, 0);
                }
            }
            awaitSettled(frame);
            assertFocusIsShownAndNamed("choosing Fraction");
        }
        assertTrue(fractionChosen(frame), "the keyboard chooses Fraction in the segmented control");

        // 5. Back to the notebook with Tab; Ctrl+Z undoes the last edit, the line ans / 12 typed last.
        boolean back = false;
        for (int i = 0; i < MOST_TABS && !back; i++) {
            pressAndWaitForFocusChange(KeyEvent.VK_TAB, 0);
            assertFocusIsShownAndNamed("Tab number " + (i + 1) + " back to the notebook");
            back = onEdt(() -> isLineField(frame, focusOwner()));
        }
        assertTrue(back, "Tab goes back to a line of the notebook within " + MOST_TABS + " presses");
        assertEquals(demo, notebookTexts(frame), "going through the window changes no line");
        press(KeyEvent.VK_Z, menuMask());
        awaitSettled(frame);
        assertEquals(history.get(history.size() - 2), notebookTexts(frame),
                "Ctrl+Z undoes the last edit: the line « " + demo.get(demo.size() - 1) + " » goes away");
        assertEquals(DisplayMode.FRACTION, onEdt(() -> frame.shown().displayMode()), "the display mode stays Fraction");
        assertTrue(await(() -> focusOwner() != null), "a component has the focus after Ctrl+Z");
        assertFocusIsShownAndNamed("Ctrl+Z");
    }

    // ================================================================================================ accessible names

    @Test
    @Tag("ui")
    @DisplayName("Chaque composant focalisable de la fenêtre (lignes, contrôle segmenté) a un nom accessible ; chaque ligne garde « Ligne n »")
    void everyFocusableComponentOfTheQuoteHasAnAccessibleName() throws Exception {
        requireDisplay();
        NotebookFrame frame = window(ScreenshotGenerator.DEMO);
        showWithFocusOnLine(frame, TOTAL_LINE - 1);

        List<Component> cycle = focusCycle(frame);
        for (Component each : cycle) {
            String name = onEdt(() -> accessibleName(each));
            assertFalse(name == null || name.isBlank(), "the focusable " + onEdt(() -> describe(each)) + " has an accessible name");
        }
        int rows = onEdt(frame::rowCount);
        assertEquals(ScreenshotGenerator.DEMO.size() + 1, rows, "the 8 lines and the blank line at the end");
        for (int i = 0; i < rows; i++) {
            int row = i;
            JTextComponent field = onEdt(() -> frame.row(row).field);
            assertTrue(cycle.contains(field), "the field of line " + (i + 1) + " is in the focus cycle of the window");
            assertEquals("Ligne " + (i + 1), onEdt(() -> field.getAccessibleContext().getAccessibleName()),
                    "the accessible name of line " + (i + 1));
        }
        assertEquals("Affichage", onEdt(() -> frame.modeSelector().getAccessibleContext().getAccessibleName()),
                "the segmented control is named « Affichage »");
        boolean segmentInCycle = false;
        for (DisplayMode mode : DisplayMode.values()) {
            AbstractButton button = onEdt(() -> frame.modeSelector().button(mode));
            String name = onEdt(() -> accessibleName(button));
            assertFalse(name == null || name.isBlank(), "the segment " + mode + " has an accessible name");
            segmentInCycle |= cycle.contains(button);
        }
        assertTrue(segmentInCycle, "the segmented control is in the focus cycle of the window");
    }

    @Test
    @Tag("ui")
    @DisplayName("Carnet vide : les trois boutons d'exemple sont focalisables et nommés ; palette : recherche, liste et entrées nommées")
    void theExampleButtonsAndThePaletteHaveAccessibleNames() throws Exception {
        requireDisplay();
        NotebookFrame frame = window();
        showWithFocusOnLine(frame, 0);

        List<Component> cycle = focusCycle(frame);
        for (Component each : cycle) {
            String name = onEdt(() -> accessibleName(each));
            assertFalse(name == null || name.isBlank(), "the focusable " + onEdt(() -> describe(each)) + " has an accessible name");
        }
        for (String example : List.of("exampleButtonDEVIS", "exampleButtonRACINE", "exampleButtonREFERENCES")) {
            Component button = onEdt(() -> named(frame, example));
            assertTrue(cycle.contains(button), example + " is in the focus cycle of the window");
            String name = onEdt(() -> accessibleName(button));
            assertFalse(name == null || name.isBlank(), example + " has an accessible name");
        }
        assertEquals("Ligne 1", onEdt(() -> frame.row(0).field.getAccessibleContext().getAccessibleName()));

        press(KeyEvent.VK_K, menuMask());
        assertTrue(await(() -> isPaletteOpen(frame)), "Ctrl+K opens the command palette");
        assertTrue(await(() -> focusOwner() == part(palette(frame), "commandPaletteSearch")), "the focus is in the search");
        assertFocusIsShownAndNamed("the palette open");
        Component palette = onEdt(() -> palette(frame));
        assertFalse(onEdt(() -> accessibleName(palette)).isBlank(), "the palette has an accessible name");
        JList<?> list = onEdt(() -> (JList<?>) part(palette, "commandPaletteList"));
        String listName = onEdt(() -> accessibleName(list));
        assertFalse(listName == null || listName.isBlank(), "the list of the palette has an accessible name");
        int entries = onEdt(() -> list.getModel().getSize());
        assertTrue(entries > 0, "the palette lists commands");
        for (int i = 0; i < entries; i++) {
            int index = i;
            String name = onEdt(() -> {
                var child = list.getAccessibleContext().getAccessibleChild(index);
                return child == null ? null : child.getAccessibleContext().getAccessibleName();
            });
            assertFalse(name == null || name.isBlank(), "entry " + (i + 1) + " of the palette has an accessible name");
        }
    }

    // ================================================================================================ the documentation

    @Test
    @DisplayName("avant-apres.md : chaque capture avant à côté de sa capture après (clair), les captures sombres, tous les liens résolus")
    void theBeforeAfterPageShowsThePicturesSideBySide() throws Exception {
        String text = read(BEFORE_AFTER);
        Path folder = BEFORE_AFTER.getParent();
        List<Set<Path>> imagesPerLine = new ArrayList<>();
        Set<Path> allImages = new HashSet<>();
        for (String line : text.lines().toList()) {
            Set<Path> images = new HashSet<>();
            Matcher image = IMAGE.matcher(line);
            while (image.find()) {
                String target = image.group(1) != null ? image.group(1) : image.group(2);
                images.add(folder.resolve(target).normalize());
            }
            imagesPerLine.add(images);
            allImages.addAll(images);
        }
        for (String notebook : List.of("carnet-devis", "carnet-vide")) {
            Path before = BEFORE.resolve(notebook + ".png").normalize();
            Path after = IMAGES.resolve(notebook + "-clair.png").normalize();
            assertTrue(imagesPerLine.stream().anyMatch(images -> images.contains(before) && images.contains(after)),
                    "one line (a table row) of " + BEFORE_AFTER + " shows " + before + " next to " + after + "; images seen: " + allImages);
            Path dark = IMAGES.resolve(notebook + "-sombre.png").normalize();
            assertTrue(allImages.contains(dark), BEFORE_AFTER + " shows " + dark);
        }
        Matcher link = LINK.matcher(text);
        int links = 0;
        while (link.find()) {
            String target = link.group(1) != null ? link.group(1) : link.group(2);
            if (target.startsWith("#") || target.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) {
                continue;
            }
            String path = target.replaceAll("#.*$", "");
            Path file = folder.resolve(java.net.URLDecoder.decode(path, StandardCharsets.UTF_8)).normalize();
            assertTrue(Files.exists(file), BEFORE_AFTER + " links to " + target + ", which does not resolve (" + file + ")");
            links++;
        }
        assertTrue(links >= 6, "the page links its pictures: " + links + " links");
    }

    @Test
    @DisplayName("avant-apres.md : la hausse de taille de l'archive Windows est celle de l'ADR 0017, en octets")
    void theBeforeAfterPageGivesTheSizeRiseOfAdr0017() throws Exception {
        String adr = spaces(read(ADR_0017));
        Matcher rise = Pattern.compile("La hausse est de ([\\d ]+\\d) octets").matcher(adr);
        assertTrue(rise.find(), "ADR 0017 gives the rise in size of the Windows archive (« La hausse est de … octets »)");
        String page = spaces(read(BEFORE_AFTER));
        assertTrue(page.contains(rise.group(1) + " octets"),
                BEFORE_AFTER + " gives the rise in size of ADR 0017: « " + rise.group(1) + " octets »");
        assertTrue(page.contains("Windows"), BEFORE_AFTER + " says the archive is the Windows one");
    }

    @Test
    @DisplayName("avant-apres.md : tableau des contrastes, colonnes Clair puis Sombre, chaque paire de tokens.properties à sa valeur")
    void theBeforeAfterPageGivesTheContrastsOfBothThemes() throws Exception {
        List<String> lines = read(BEFORE_AFTER).lines().toList();
        assertTrue(lines.stream().anyMatch(line -> line.trim().startsWith("|") && line.contains("Clair")
                        && line.indexOf("Sombre") > line.indexOf("Clair")),
                BEFORE_AFTER + " has a table whose header names « Clair » then « Sombre »");
        DesignTokens tokens = DesignTokens.load();
        List<DesignTokens.ContrastPair> pairs = tokens.contrastPairs();
        assertFalse(pairs.isEmpty(), "tokens.properties declares contrast pairs");
        for (DesignTokens.ContrastPair pair : pairs) {
            String row = lines.stream().filter(line -> line.trim().startsWith("|") && hasCell(line, pair.key())).findFirst()
                    .orElseGet(() -> fail(BEFORE_AFTER + " has no table row with a cell `" + pair.key() + "`"));
            String after = row.substring(row.indexOf(pair.key()) + pair.key().length());
            Matcher number = DECIMAL.matcher(after);
            for (Theme theme : List.of(Theme.LIGHT, Theme.DARK)) {
                assertTrue(number.find(), "the row of " + pair.key() + " gives its ratio in " + theme + ": " + row);
                double written = Double.parseDouble(number.group(1) + "." + number.group(2));
                double precision = 0.5 * Math.pow(10, -number.group(2).length()) + 1e-9;
                double actual = Contrast.ratio(tokens.color(theme, pair.foreground()), tokens.color(theme, pair.background()));
                assertTrue(Math.abs(written - actual) <= precision, String.format(Locale.ROOT,
                        "%s in %s: the page writes %s, the tokens give %.2f", pair.key(), theme, number.group(), actual));
            }
        }
    }

    @Test
    @DisplayName("avant-apres.md : durées mesurées (fourchette) des budgets de 5000 lignes des deux tests de réactivité")
    void theBeforeAfterPageGivesTheMeasuredDurationsOfTheBudgets() throws Exception {
        String text = spaces(read(BEFORE_AFTER));
        assertTrue(text.contains("5000") || text.contains("5 000"), BEFORE_AFTER + " names the 5000 lines of the budgets");
        for (String test : List.of("NotebookFrameResponsivenessTest", "BackgroundRecalculationAcceptanceTest")) {
            assertTrue(text.lines().anyMatch(line -> line.contains(test) && DURATION_RANGE.matcher(line).find()),
                    BEFORE_AFTER + " gives, on the line naming " + test + ", the range of its measured durations (« 120 à 180 ms »)");
        }
    }

    @Test
    @DisplayName("GUIDE-UTILISATEUR.md : thèmes, palette de commandes, détail, aperçu, collage multiligne, exemples, barre d'état")
    void theUserGuideDescribesTheRedesign() throws Exception {
        String guide = read(GUIDE);
        List<String> headings = guide.lines().filter(line -> line.startsWith("#"))
                .map(line -> line.toLowerCase(Locale.ROOT)).toList();
        for (String heading : List.of("thème", "palette de commandes", "détail", "barre d'état")) {
            assertTrue(headings.stream().anyMatch(each -> each.contains(heading)),
                    GUIDE + " has a heading about « " + heading + " »: " + headings);
        }
        for (String phrase : List.of("Clair", "Sombre", "Ctrl+K", "Utilisée par", "aperçu", "Coller", "plusieurs lignes",
                "Devis", "Racine de 2 approchée", "Références $n", "exactes", "approchée", "en erreur")) {
            assertTrue(guide.contains(phrase), GUIDE + " contains « " + phrase + " »");
        }
    }

    @Test
    @DisplayName("ARCHITECTURE.md cite calc.ui.design, calc.ui.theme, calc.detail et tokens.css")
    void theArchitectureNamesTheNewPackagesAndTheTokens() throws Exception {
        String architecture = read(ARCHITECTURE);
        for (String name : List.of("calc.ui.design", "calc.ui.theme", "calc.detail", "tokens.css")) {
            assertTrue(architecture.contains(name), ARCHITECTURE + " names " + name);
        }
    }

    @Test
    @DisplayName("TESTS.md : nouvelles garanties (3 cellules) pour jetons et contrastes, détail, aperçu et collage, palette, parcours clavier ; G22 cite les deux thèmes")
    void theTestStrategyHasTheGuaranteesOfTheRedesign() throws Exception {
        List<String> rows = read(TESTS).lines().filter(line -> line.matches("^\\|\\s*G\\d+\\s*\\|.*")).toList();
        assertTrue(rows.size() > 29, TESTS + " has new guarantee rows after G29: " + rows.size() + " rows");
        Map<String, String[]> byId = new LinkedHashMap<>();
        for (String row : rows) {
            String[] cells = row.trim().replaceAll("^\\|", "").replaceAll("\\|$", "").split("(?<!\\\\)\\|", -1);
            assertEquals(3, cells.length, "a guarantee row has 3 cells (the conformity report reads them): " + row);
            String id = cells[0].trim();
            assertTrue(byId.put(id, cells) == null, "the guarantee " + id + " is listed once");
        }
        Map<String, String> required = new LinkedHashMap<>();
        required.put("DesignTokensAcceptanceTest", "jetons et contrastes");
        required.put("LineDetailAcceptanceTest", "détail de la ligne active");
        required.put("TypingPreviewAcceptanceTest", "aperçu");
        required.put("MultiLinePasteAcceptanceTest", "collage");
        required.put("CommandPaletteAcceptanceTest", "palette de commandes");
        required.put("RedesignDeliveryAcceptanceTest", "parcours au clavier");
        for (Map.Entry<String, String> each : required.entrySet()) {
            String quoted = "`" + each.getKey() + "`";
            assertTrue(byId.values().stream().anyMatch(cells -> cells[2].contains(quoted)),
                    TESTS + " has a guarantee row (" + each.getValue() + ") citing " + quoted + " in its third cell");
            try (var files = Files.walk(Path.of("src", "test", "java"))) {
                assertTrue(files.anyMatch(file -> file.getFileName().toString().equals(each.getKey() + ".java")),
                        each.getKey() + " exists under src/test/java");
            }
        }
        String[] g22 = byId.get("G22");
        assertNotNull(g22, TESTS + " keeps the guarantee G22");
        String g22Text = String.join("|", g22).toLowerCase(Locale.ROOT);
        assertTrue(g22Text.contains("clair") && g22Text.contains("sombre"), "G22 names the two themes, clair and sombre: " + g22Text);
    }

    @Test
    @DisplayName("CHANGELOG.md : « ## [Non publié] » reste vide, suivie directement de « ## [1.0.0] - 2026-11-07 »")
    void theChangelogKeepsAnEmptyUnreleasedSection() throws Exception {
        List<String> lines = read(CHANGELOG).lines().toList();
        int unreleased = lines.indexOf("## [Non publié]");
        assertTrue(unreleased >= 0, "CHANGELOG.md keeps its section « ## [Non publié] »");
        int next = unreleased + 1;
        while (next < lines.size() && lines.get(next).isBlank()) {
            next++;
        }
        assertTrue(next < lines.size(), "a section follows « ## [Non publié] »");
        assertEquals("## [1.0.0] - 2026-11-07", lines.get(next), "« ## [Non publié] » is empty and followed by the 1.0.0 section");
    }

    // ================================================================================================ the window, built and driven

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    // The modifier of the menu shortcuts of this system: Ctrl on Windows and Linux, Cmd on macOS.
    private static int menuMask() {
        return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    // A window as Main builds it, in the light theme, on a store of the temporary folder (never the data of the user).
    private NotebookFrame window(List<String> lines) throws Exception {
        NotebookStore store = NotebookStore.open(Files.createDirectories(temp.resolve("data-" + stores.size())));
        stores.add(store);
        if (!lines.isEmpty()) {
            store.saveCurrent(calc.notebook.Notebook.of(lines), DisplayMode.AUTO);
        }
        var loaded = store.load();
        NotebookFrame frame = onEdt(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(Theme.LIGHT);
            NotebookPresenter presenter = NotebookPresenter.open(store, loaded, DisplayModeStore.noop(),
                    NotebookDialogs.cancelling(), NotebookPresenter.newRecalculationExecutor());
            NotebookFrame built;
            // The JDK lists a window as visible while its constructor runs; Window.getWindows() takes this lock.
            synchronized (Window.class) {
                built = new NotebookFrame(presenter, new NotebookShortcuts(menuMask()));
            }
            frames.add(built);
            presenters.put(built, presenter);
            built.pack();
            Insets border = built.getInsets();
            built.setSize(WIDTH + border.left + border.right, HEIGHT + border.top + border.bottom);
            built.validate();
            return built;
        });
        awaitSettled(frame);
        return frame;
    }

    private NotebookFrame window() throws Exception {
        return window(List.of());
    }

    // Shows the window and puts the keyboard focus in the field of the row (from 0); skipped where a new window gets no focus.
    private void showWithFocusOnLine(NotebookFrame frame, int row) throws Exception {
        onEdt(() -> {
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            frame.toFront();
            frame.row(row).field.requestFocusInWindow();
            return null;
        });
        assumeTrue(await(() -> frame.row(row).field.isFocusOwner()),
                "a system that gives no focus to a new window: the keys cannot be observed");
        awaitSettled(frame);
        assertEquals(row, (int) onEdt(() -> frame.shown().activeIndex()), "line " + (row + 1) + " is active");
    }

    // Types the text as the keyboard does, one KEY_TYPED per character, into the component that must have the focus.
    private static void type(JTextComponent target, String text) throws Exception {
        assertTrue(await(() -> focusOwner() == target), "the focus is on " + describe(target) + " before typing, not on "
                + onEdt(() -> describe(focusOwner())));
        for (char each : text.toCharArray()) {
            onEdt(() -> {
                Component owner = focusOwner();
                if (owner != target) {
                    throw new AssertionError("the focus left " + describe(target) + " while typing, for " + describe(owner));
                }
                owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, each));
                return null;
            });
        }
        flushEvents();
    }

    // A key pressed on the keyboard: the event goes to the component that has the focus, through its listeners and bindings.
    private static void press(int keyCode, int modifiers) throws Exception {
        onEdt(() -> {
            Component owner = focusOwner();
            assertNotNull(owner, "a component of the window has the keyboard focus");
            owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode,
                    KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        flushEvents();
    }

    // A key pressed then released (Space on a button acts on its release).
    private static void pressAndRelease(int keyCode, char character) throws Exception {
        onEdt(() -> {
            Component owner = focusOwner();
            assertNotNull(owner, "a component of the window has the keyboard focus");
            owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, character));
            owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, character));
            owner.dispatchEvent(new KeyEvent(owner, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, character));
            return null;
        });
        flushEvents();
    }

    // A traversal key: waits until the focus has moved to another component.
    private static void pressAndWaitForFocusChange(int keyCode, int modifiers) throws Exception {
        Component before = onEdt(RedesignDeliveryAcceptanceTest::focusOwner);
        press(keyCode, modifiers);
        assertTrue(await(() -> focusOwner() != null && focusOwner() != before),
                KeyEvent.getKeyText(keyCode) + " moves the focus away from " + onEdt(() -> describe(before)));
    }

    // At each step: the component that has the focus is shown and has a non-empty accessible name.
    private static void assertFocusIsShownAndNamed(String step) throws Exception {
        Component owner = onEdt(RedesignDeliveryAcceptanceTest::focusOwner);
        assertNotNull(owner, step + ": a component has the keyboard focus");
        assertTrue(onEdt(owner::isShowing), step + ": the component that has the focus is shown: " + onEdt(() -> describe(owner)));
        String name = onEdt(() -> accessibleName(owner));
        assertFalse(name == null || name.isBlank(), step + ": the component that has the focus has an accessible name: "
                + onEdt(() -> describe(owner)));
    }

    private static boolean fractionChosen(NotebookFrame frame) throws Exception {
        return onEdt(() -> frame.shown().displayMode() == DisplayMode.FRACTION
                && frame.modeSelector().button(DisplayMode.FRACTION).isSelected());
    }

    private static boolean isModeButton(Component component) {
        return component != null && component.getName() != null && component.getName().startsWith("modeButton");
    }

    private static boolean isLineField(NotebookFrame frame, Component component) {
        for (int i = 0; i < frame.rowCount(); i++) {
            if (frame.row(i).field == component) {
                return true;
            }
        }
        return false;
    }

    // The texts of the lines of the notebook, without the blank line at the end.
    private static List<String> notebookTexts(NotebookFrame frame) throws Exception {
        return onEdt(() -> {
            List<String> texts = new ArrayList<>(frame.shown().rows().stream().map(NotebookRow::text).toList());
            while (!texts.isEmpty() && texts.get(texts.size() - 1).isEmpty()) {
                texts.remove(texts.size() - 1);
            }
            return texts;
        });
    }

    // The texts of the shown labels of the one component lineDetail.
    private static List<String> detailTexts(NotebookFrame frame) {
        List<Component> found = descendants(frame.getRootPane()).stream().filter(each -> "lineDetail".equals(each.getName())).toList();
        assertEquals(1, found.size(), "one component lineDetail");
        Component detail = found.get(0);
        if (!detail.isShowing()) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        for (Component each : descendants((Container) detail)) {
            if (each instanceof JLabel label && label.isShowing() && label.getText() != null) {
                texts.add(label.getText());
            }
        }
        return texts;
    }

    // The focus cycle of the shown window, as Tab walks it.
    private static List<Component> focusCycle(NotebookFrame frame) throws Exception {
        return onEdt(() -> {
            FocusTraversalPolicy policy = frame.getFocusTraversalPolicy();
            List<Component> cycle = new ArrayList<>();
            Component first = policy.getFirstComponent(frame);
            assertNotNull(first, "the shown window has a first focusable component");
            Component each = first;
            for (int i = 0; i < 1000 && each != null; i++) {
                cycle.add(each);
                each = policy.getComponentAfter(frame, each);
                if (each == first) {
                    return cycle;
                }
            }
            return fail("the focus cycle of the window does not come back to its first component: " + cycle.size());
        });
    }

    private static String accessibleName(Component component) {
        return component.getAccessibleContext() == null ? null : component.getAccessibleContext().getAccessibleName();
    }

    private static Component named(NotebookFrame frame, String name) {
        List<Component> found = descendants(frame.getRootPane()).stream().filter(each -> name.equals(each.getName())).toList();
        assertEquals(1, found.size(), "one component named " + name);
        return found.get(0);
    }

    private static boolean isPaletteOpen(NotebookFrame frame) {
        return descendants(frame.getLayeredPane()).stream()
                .anyMatch(each -> "commandPalette".equals(each.getName()) && each.isShowing());
    }

    private static Component palette(NotebookFrame frame) {
        List<Component> found = descendants(frame.getLayeredPane()).stream()
                .filter(each -> "commandPalette".equals(each.getName()) && each.isShowing()).toList();
        assertEquals(1, found.size(), "one shown component named commandPalette");
        return found.get(0);
    }

    private static Component part(Component root, String name) {
        List<Component> found = root instanceof Container container
                ? descendants(container).stream().filter(each -> name.equals(each.getName())).toList()
                : List.of();
        assertEquals(1, found.size(), "one component named " + name + " in " + root.getName());
        return found.get(0);
    }

    private static List<Component> descendants(Container root) {
        List<Component> all = new ArrayList<>();
        List<Component> pending = new ArrayList<>(List.of(root.getComponents()));
        while (!pending.isEmpty()) {
            Component each = pending.remove(0);
            all.add(each);
            if (each instanceof Container container) {
                pending.addAll(List.of(container.getComponents()));
            }
        }
        return all;
    }

    private static Component focusOwner() {
        return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
    }

    private static String describe(Component component) {
        return component == null ? "nothing" : component.getClass().getSimpleName() + " " + component.getName()
                + " « " + accessibleName(component) + " »";
    }

    // ================================================================================================ waiting

    // Waits until nothing is being calculated or previewed and every row is built, then lays the window out again.
    private void awaitSettled(NotebookFrame frame) throws Exception {
        NotebookPresenter presenter = presenters.get(frame);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!onEdt(() -> !frame.isCalculating() && !presenter.isCalculating() && !presenter.isPreviewing()
                && frame.rowCount() == frame.shown().rows().size())) {
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

    // Whether the condition holds within 10 s, read on the event thread.
    private static boolean await(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            flushEvents();
            if (onEdt(condition)) {
                return true;
            }
            Thread.sleep(5);
        }
        return onEdt(condition);
    }

    private static void flushEvents() throws Exception {
        for (int i = 0; i < 3; i++) {
            SwingUtilities.invokeAndWait(() -> { });
        }
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
        Throwable thrown = failure.get();
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown instanceof Exception exception) {
            throw exception;
        }
        return result.get();
    }

    // ================================================================================================ reading files

    private static String read(Path file) throws Exception {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    // No-break and narrow no-break spaces read as plain spaces, so that a figure is found however it is spaced.
    private static String spaces(String text) {
        return text.replace(' ', ' ').replace(' ', ' ');
    }

    // Whether a cell of the table row holds exactly this text, backticks aside.
    private static boolean hasCell(String row, String text) {
        for (String cell : row.split("\\|")) {
            if (cell.trim().replace("`", "").equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual, String what) {
        assertEquals(expected.getWidth(), actual.getWidth(), what + ": width");
        assertEquals(expected.getHeight(), actual.getHeight(), what + ": height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    fail(what + ": the two renderings differ at (" + x + ", " + y + "): "
                            + Integer.toHexString(expected.getRGB(x, y)) + " against " + Integer.toHexString(actual.getRGB(x, y)));
                }
            }
        }
    }
}
