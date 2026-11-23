package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of the screenshot generator (E01): {@code ScreenshotGenerator} paints {@link NotebookFrame} off screen, from a fixed
 * demonstration notebook (the "Devis cuisine" of the README), and the Gradle task {@code screenshots} runs it.
 *
 * <p>The generator is reached by reflection so that this class compiles, and fails with a clear message, before the generator
 * exists. The pictures are compared pixel by pixel, on the same machine: the rendering depends on the system fonts.
 */
@Tag("ui")
@Tag("acceptance")
final class ScreenshotGeneratorAcceptanceTest {

    private static final String GENERATOR = "calc.ui.notebook.ScreenshotGenerator";
    private static final Path BUILD_SCRIPT = Path.of("build.gradle.kts");
    private static final Path README = Path.of("README.md");
    private static final Path BEFORE_DIR = Path.of("docs", "images", "avant");
    private static final int WIDTH = 1100;
    private static final int HEIGHT = 720;
    /** The line {@code total = plan + meubles + pose}, as the contract numbers it. */
    private static final int ACTIVE_LINE = 5;

    @TempDir
    Path out;

    @BeforeEach
    void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    // ------------------------------------------------------------------------------------------------ the demonstration notebook

    @Test
    @DisplayName("DEMO : les 8 lignes « Devis cuisine » du README, telles quelles")
    void demoIsTheKitchenQuoteOfTheReadme() throws Exception {
        List<String> demo = demo();
        assertEquals(readmeKitchenQuote(), demo, "DEMO must be the 8 lines of the README, no-break spaces included");
        assertEquals(8, demo.size());
        assertTrue(demo.get(0).startsWith("# Devis cuisine"), demo.get(0));
        assertTrue(demo.get(4).startsWith("total = plan + meubles + pose"), "line 5 is the total: " + demo.get(4));
    }

    // ------------------------------------------------------------------------------------------------ render

    @Test
    @DisplayName("render : largeur round(largeur × échelle), hauteur round(hauteur × échelle)")
    void renderHasTheScaledSize() throws Exception {
        BufferedImage plain = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        assertEquals(WIDTH, plain.getWidth());
        assertEquals(HEIGHT, plain.getHeight());

        BufferedImage scaled = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.25);
        assertEquals(1375, scaled.getWidth());
        assertEquals(900, scaled.getHeight());

        // 801 × 0.5 = 400.5 and 533 × 0.5 = 266.5: Math.round goes up.
        BufferedImage rounded = render(demo(), ACTIVE_LINE, 801, 533, 0.5);
        assertEquals(401, rounded.getWidth());
        assertEquals(267, rounded.getHeight());
    }

    @Test
    @DisplayName("render : aucune fenêtre n'est jamais visible, aucune fenêtre affichable ne reste")
    void renderNeverShowsAWindow() throws Exception {
        Set<Window> before = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Window window : Window.getWindows()) {
            if (window.isDisplayable()) {
                before.add(window);
            }
        }
        AtomicReference<String> seenVisible = new AtomicReference<>();
        Thread watcher = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                for (Window window : Window.getWindows()) {
                    if (window.isVisible() && !before.contains(window)) {
                        seenVisible.compareAndSet(null, window.getClass().getName() + " \"" + window + "\"");
                    }
                }
                try {
                    Thread.sleep(1);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
        }, "screenshot-window-watcher");
        watcher.setDaemon(true);
        watcher.start();
        try {
            render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        } finally {
            watcher.interrupt();
            watcher.join(5_000);
        }
        assertNull(seenVisible.get(), "a window was visible while rendering");
        List<String> left = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (window.isVisible() || (window.isDisplayable() && !before.contains(window))) {
                left.add(window.getClass().getName());
            }
        }
        assertEquals(List.of(), left, "no displayable window may remain after render (dispose)");
    }

    @Test
    @DisplayName("render : deux appels avec le carnet DEMO donnent des images identiques pixel à pixel")
    void twoRendersOfTheDemoAreIdentical() throws Exception {
        BufferedImage first = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        BufferedImage second = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        assertSamePixels(first, second);
    }

    @Test
    @DisplayName("render : l'image du carnet DEMO n'est pas uniforme (3 couleurs, 2 % de pixels autres que le coin)")
    void theDemoPictureIsNotBlank() throws Exception {
        BufferedImage image = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        int corner = image.getRGB(0, 0) & 0xFFFFFF;
        Set<Integer> colours = new HashSet<>();
        long different = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y) & 0xFFFFFF;
                colours.add(rgb);
                if (rgb != corner) {
                    different++;
                }
            }
        }
        assertTrue(colours.size() >= 3, "only " + colours.size() + " distinct colours");
        long total = (long) image.getWidth() * image.getHeight();
        assertTrue(different * 100 >= total * 2, "only " + different + " of " + total + " pixels differ from the corner");
    }

    @Test
    @DisplayName("render : la ligne active change l'image (elle passe par le présentateur)")
    void theActiveLineIsPartOfThePicture() throws Exception {
        BufferedImage onTotal = render(demo(), ACTIVE_LINE, WIDTH, HEIGHT, 1.0);
        BufferedImage onLastLine = render(demo(), 8, WIDTH, HEIGHT, 1.0);
        assertTrue(differs(onTotal, onLastLine), "the active line must show in the picture");
    }

    @Test
    @DisplayName("render : attend la fin du calcul, donc la barre d'état ne dit jamais « Calcul en cours »")
    void renderWaitsForTheCalculationToEnd() throws Exception {
        // A long notebook: the rows are built by slices of 15 ms and the status bar counts the lines known so far,
        // so a render that does not wait paints a different count each time.
        List<String> chain = new ArrayList<>();
        chain.add("1");
        for (int i = 1; i < 1500; i++) {
            chain.add("ans * 3 + 1");
        }
        BufferedImage first = render(chain, 3, WIDTH, HEIGHT, 1.0);
        BufferedImage second = render(chain, 3, WIDTH, HEIGHT, 1.0);
        assertSamePixels(first, second);
    }

    // ------------------------------------------------------------------------------------------------ main

    @Test
    @DisplayName("main : écrit carnet-devis.png et carnet-vide.png aux dimensions attendues")
    void mainWritesTheTwoPictures() throws Exception {
        Path folder = out.resolve("shots");
        runMain(folder.toString(), "clair", "1");
        BufferedImage devis = readPng(folder.resolve("carnet-devis.png"));
        BufferedImage vide = readPng(folder.resolve("carnet-vide.png"));
        assertEquals(WIDTH, devis.getWidth());
        assertEquals(HEIGHT, devis.getHeight());
        assertEquals(WIDTH, vide.getWidth());
        assertEquals(HEIGHT, vide.getHeight());
        assertTrue(differs(devis, vide), "the empty notebook and the quote must not look the same");
    }

    @Test
    @DisplayName("main : l'échelle multiplie les dimensions des fichiers")
    void mainAppliesTheScale() throws Exception {
        Path folder = out.resolve("half");
        runMain(folder.toString(), "clair", "0.5");
        BufferedImage devis = readPng(folder.resolve("carnet-devis.png"));
        assertEquals(550, devis.getWidth());
        assertEquals(360, devis.getHeight());
    }

    @Test
    @DisplayName("main : un thème inconnu échoue avec un message clair et n'écrit rien")
    void mainRejectsAnUnknownTheme() throws Exception {
        Path folder = out.resolve("bad");
        generator();
        Throwable failure = assertThrows(Exception.class, () -> runMain(folder.toString(), "violet", "1"));
        Throwable cause = failure;
        while (cause instanceof InvocationTargetException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertNotNull(cause.getMessage(), "the failure must carry a message");
        assertFalse(cause.getMessage().isBlank(), "the failure must carry a message");
        assertFalse(Files.exists(folder.resolve("carnet-devis.png")), "nothing is written for a refused theme");
    }

    // ------------------------------------------------------------------------------------------------ Gradle task

    @Test
    @DisplayName("Gradle : la tâche screenshots transmet les trois propriétés et n'est liée ni à check, ni à build, ni à assemble")
    void gradleTaskScreenshotsExistsAndIsOutsideTheBuild() throws IOException {
        String script = readText(BUILD_SCRIPT);
        int start = script.indexOf("register<JavaExec>(\"screenshots\")");
        assertTrue(start >= 0, "build.gradle.kts must register the JavaExec task screenshots");
        int open = script.indexOf('{', start);
        int depth = 0;
        int end = -1;
        for (int i = open; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        assertTrue(open > 0 && end > open, "the screenshots task block must be closed");
        String block = script.substring(start, end + 1);
        assertTrue(block.contains("calc.ui.notebook.ScreenshotGenerator"), "the task runs calc.ui.notebook.ScreenshotGenerator");
        assertTrue(block.contains("sourceSets") && block.contains("runtimeClasspath"), "the task runs on the test runtime classpath");
        assertTrue(block.contains("screenshotDir") && (block.contains("build/screenshots") || block.contains("buildDirectory")), "-PscreenshotDir defaults to build/screenshots");
        assertTrue(block.contains("screenshotTheme") && block.contains("clair"), "-PscreenshotTheme defaults to clair");
        assertTrue(block.contains("screenshotScale"), "-PscreenshotScale is passed on");
        Matcher scaleDefault = Pattern.compile("screenshotScale[^\\n]*\\n?[^\\n]*\"1(\\.0)?\"").matcher(block);
        assertTrue(scaleDefault.find(), "-PscreenshotScale defaults to 1");
        assertFalse(Pattern.compile("(dependsOn|finalizedBy)\\s*\\([^)]*\"(check|build|assemble)\"").matcher(block).find(),
                "the task does not hang itself on check, build or assemble");

        String outside = script.substring(0, start) + script.substring(end + 1);
        for (String line : outside.split("\n")) {
            if (line.contains("screenshots") && Pattern.compile("dependsOn|finalizedBy|mustRunAfter").matcher(line).find()) {
                fail("another task depends on screenshots: " + line.strip());
            }
        }
        assertFalse(Pattern.compile("(?s)(named|getByName|tasks)[^\\n]*\"(check|build|assemble)\"[^}]*screenshots").matcher(outside).find(),
                "check, build and assemble do not run screenshots");
    }

    // ------------------------------------------------------------------------------------------------ the pictures before

    @Test
    @DisplayName("docs/images/avant : carnet-devis.png et carnet-vide.png existent en 1100 × 720 et ne sont pas vierges")
    void theBeforePicturesAreInTheRepository() throws Exception {
        for (String name : List.of("carnet-devis.png", "carnet-vide.png")) {
            BufferedImage image = readPng(BEFORE_DIR.resolve(name));
            assertEquals(WIDTH, image.getWidth(), name);
            assertEquals(HEIGHT, image.getHeight(), name);
            Set<Integer> colours = new HashSet<>();
            for (int y = 0; y < image.getHeight(); y += 3) {
                for (int x = 0; x < image.getWidth(); x += 3) {
                    colours.add(image.getRGB(x, y) & 0xFFFFFF);
                }
            }
            assertTrue(colours.size() >= 3, name + " looks blank: " + colours.size() + " colours");
        }
        BufferedImage devis = readPng(BEFORE_DIR.resolve("carnet-devis.png"));
        BufferedImage vide = readPng(BEFORE_DIR.resolve("carnet-vide.png"));
        assertTrue(differs(devis, vide), "the two pictures before must differ");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Class<?> generator() {
        try {
            return Class.forName(GENERATOR);
        } catch (ClassNotFoundException missing) {
            return fail(GENERATOR + " does not exist yet (src/test/java/calc/ui/notebook/ScreenshotGenerator.java)");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> demo() throws Exception {
        Object value = generator().getField("DEMO").get(null);
        assertTrue(value instanceof List, "ScreenshotGenerator.DEMO must be a public static List<String>");
        return (List<String>) value;
    }

    private static BufferedImage render(List<String> lines, int activeLine, int width, int height, double scale) throws Exception {
        Method render = generator().getDeclaredMethod("render", List.class, int.class, int.class, int.class, double.class);
        Object image;
        try {
            image = render.invoke(null, lines, activeLine, width, height, scale);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
        assertTrue(image instanceof BufferedImage, "render must return a BufferedImage");
        return (BufferedImage) image;
    }

    private static void runMain(String... args) throws Exception {
        Method main = generator().getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause() instanceof Exception cause ? cause : failure;
        }
    }

    private static BufferedImage readPng(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        BufferedImage image = ImageIO.read(file.toFile());
        assertNotNull(image, file + " must be a readable PNG");
        return image;
    }

    private static String readText(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The lines of the first text block under "Exemples de carnet" in the README, which is the Devis cuisine. */
    private static List<String> readmeKitchenQuote() throws IOException {
        List<String> lines = readText(README).lines().toList();
        int heading = lines.indexOf("## Exemples de carnet");
        assertTrue(heading >= 0, "README.md must keep its section \"Exemples de carnet\"");
        int open = -1;
        for (int i = heading + 1; i < lines.size(); i++) {
            if (lines.get(i).startsWith("```")) {
                open = i;
                break;
            }
        }
        assertTrue(open > 0, "the first code block of the section");
        List<String> block = new ArrayList<>();
        for (int i = open + 1; i < lines.size() && !lines.get(i).startsWith("```"); i++) {
            block.add(lines.get(i));
        }
        return block;
    }

    private static boolean differs(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return true;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void assertSamePixels(BufferedImage expected, BufferedImage actual) {
        assertEquals(expected.getWidth(), actual.getWidth(), "width");
        assertEquals(expected.getHeight(), actual.getHeight(), "height");
        for (int y = 0; y < expected.getHeight(); y++) {
            for (int x = 0; x < expected.getWidth(); x++) {
                if (expected.getRGB(x, y) != actual.getRGB(x, y)) {
                    fail("the pictures differ at (" + x + ", " + y + "): "
                            + Integer.toHexString(expected.getRGB(x, y)) + " against " + Integer.toHexString(actual.getRGB(x, y)));
                }
            }
        }
    }
}
