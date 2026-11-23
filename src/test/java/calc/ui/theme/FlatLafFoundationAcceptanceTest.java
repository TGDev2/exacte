package calc.ui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.Main;
import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.InputMap;
import javax.swing.KeyStroke;
import javax.swing.LookAndFeel;
import javax.swing.SwingUtilities;
import javax.swing.UIDefaults;
import javax.swing.UIManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Acceptance of the FlatLaf foundation (E04): the three runtime dependencies (FlatLaf 3.x, Inter, JetBrains Mono), the two
 * themes {@code CalcLightLaf} and {@code CalcDarkLaf} applied by {@code ThemeManager}, the embedded fonts, the start of the
 * application on the light theme, the rows in JetBrains Mono, the licences and their place in the application image, the
 * reproducible {@code calc.jar} without FlatLaf classes, and ADR 0017.
 *
 * <p>{@code ThemeManager}, {@code CalcLightLaf}, {@code CalcDarkLaf} and the FlatLaf classes are reached by reflection, so
 * that this class compiles, and fails with a clear message, before they exist. {@code ThemeManager.installFonts()} and
 * {@code ThemeManager.apply(Theme)} may be static methods, or instance methods of a {@code ThemeManager} built by its
 * constructor without argument. Paths are relative to the repository root, the working directory of the tests.
 *
 * <p>The jar test reads {@code build/libs/calc.jar}: the command of this class builds it first ({@code gradlew jar test ...}).
 */
@Tag("acceptance")
@DisplayName("Fondation FlatLaf : dépendances, thèmes, polices embarquées, licences et ADR 0017")
final class FlatLafFoundationAcceptanceTest {

    private static final String THEME_MANAGER = "calc.ui.theme.ThemeManager";
    private static final String LIGHT_LAF = "calc.ui.theme.CalcLightLaf";
    private static final String DARK_LAF = "calc.ui.theme.CalcDarkLaf";
    private static final String FLAT_LAF = "com.formdev.flatlaf.FlatLaf";
    private static final String FLAT_LIGHT_LAF = "com.formdev.flatlaf.FlatLightLaf";
    private static final String FLAT_DARK_LAF = "com.formdev.flatlaf.FlatDarkLaf";
    private static final String INTER = "Inter";
    private static final String JETBRAINS_MONO = "JetBrains Mono";

    private static final Path BUILD_SCRIPT = Path.of("build.gradle.kts");
    private static final Path MAIN = Path.of("src", "main", "java", "calc", "Main.java");
    private static final Path LICENCES = Path.of("config", "licences");
    private static final List<String> LICENCE_FILES = List.of("FlatLaf-LICENSE.txt", "Inter-OFL.txt", "JetBrainsMono-OFL.txt");
    private static final Path SMOKE_TEST = Path.of("tools", "release", "smoke-test.sh");
    private static final Path JAR = Path.of("build", "libs", "calc.jar");
    private static final Path ADR = Path.of("docs", "adr", "0017-flatlaf-et-polices-embarquees.md");
    private static final Path ADR_TEMPLATE = Path.of("docs", "adr", "0000-modele.md");
    private static final Path ADR_INDEX = Path.of("docs", "adr", "README.md");

    private static final Pattern DEPENDENCY_CALL =
            Pattern.compile("^\\s*(implementation|runtimeOnly|api|compileOnlyApi)\\s*\\((.*)\\)\\s*$", Pattern.MULTILINE);
    private static final Pattern FLATLAF = Pattern.compile("\"com\\.formdev:flatlaf:(3\\.\\d+(?:\\.\\d+)?)\"");
    private static final Pattern FLATLAF_INTER = Pattern.compile("\"com\\.formdev:flatlaf-fonts-inter:(\\d+(?:\\.\\d+)*)\"");
    private static final Pattern FLATLAF_MONO = Pattern.compile("\"com\\.formdev:flatlaf-fonts-jetbrains-mono:(\\d+(?:\\.\\d+)*)\"");
    private static final Pattern BYTES = Pattern.compile("(\\d[\\d \\u00A0\\u202F.]*\\d)\\s*octets");

    /** Shortcuts of the window that no FlatLaf component may bind (the next display mode, and the guide's notebook keys). */
    private static final KeyStroke NEXT_MODE = KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_M, java.awt.event.InputEvent.CTRL_DOWN_MASK);

    private static final AtomicReference<LookAndFeel> ORIGINAL = new AtomicReference<>();

    @AfterAll
    static void restoreTheLookAndFeel() throws Exception {
        LookAndFeel original = ORIGINAL.get();
        if (original != null) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    UIManager.setLookAndFeel(original);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ dependencies

    @Test
    @DisplayName("build.gradle.kts déclare exactement trois dépendances implementation : flatlaf 3.x, flatlaf-fonts-inter, flatlaf-fonts-jetbrains-mono")
    void theBuildDeclaresExactlyTheThreeFlatLafDependencies() throws IOException {
        String script = lf(read(BUILD_SCRIPT));
        List<String> calls = new ArrayList<>();
        Matcher call = DEPENDENCY_CALL.matcher(script);
        while (call.find()) {
            calls.add(call.group(1) + "(" + call.group(2).trim() + ")");
        }
        assertEquals(3, calls.size(), "exactly three runtime dependency declarations, all `implementation`: " + calls);
        assertTrue(calls.stream().allMatch(each -> each.startsWith("implementation(")),
                "no runtimeOnly, api or compileOnlyApi dependency: " + calls);
        String declared = String.join("\n", calls);
        assertTrue(FLATLAF.matcher(declared).find(), "com.formdev:flatlaf in a stable version 3.x, written in line: " + calls);
        assertTrue(FLATLAF_INTER.matcher(declared).find(), "com.formdev:flatlaf-fonts-inter with its version written in line: " + calls);
        assertTrue(FLATLAF_MONO.matcher(declared).find(),
                "com.formdev:flatlaf-fonts-jetbrains-mono with its version written in line: " + calls);
        Set<String> artifacts = new TreeSet<>();
        for (String each : calls) {
            Matcher coordinates = Pattern.compile("\"([^\":]+):([^\":]+):[^\"]+\"").matcher(each);
            assertTrue(coordinates.find(), "a dependency written as group:artifact:version: " + each);
            artifacts.add(coordinates.group(1) + ":" + coordinates.group(2));
        }
        assertEquals(new TreeSet<>(List.of("com.formdev:flatlaf", "com.formdev:flatlaf-fonts-inter",
                "com.formdev:flatlaf-fonts-jetbrains-mono")), artifacts, "no other runtime dependency");
    }

    // ------------------------------------------------------------------------------------------------ themes

    @Test
    @Tag("ui")
    @DisplayName("ThemeManager.apply : CalcLightLaf (FlatLightLaf) et CalcDarkLaf (FlatDarkLaf), Panel.background = surface.base")
    void applyingAThemeInstallsTheCalcLookAndFeelWithTheTokenBackground() throws Throwable {
        requireDisplay();
        rememberTheOriginalLookAndFeel();
        Class<?> flatLight = type(FLAT_LIGHT_LAF);
        Class<?> flatDark = type(FLAT_DARK_LAF);
        Class<?> calcLight = type(LIGHT_LAF);
        Class<?> calcDark = type(DARK_LAF);
        assertTrue(flatLight.isAssignableFrom(calcLight), LIGHT_LAF + " extends " + FLAT_LIGHT_LAF);
        assertTrue(flatDark.isAssignableFrom(calcDark), DARK_LAF + " extends " + FLAT_DARK_LAF);
        DesignTokens tokens = DesignTokens.load();

        for (Theme theme : Theme.values()) {
            Class<?> expected = theme == Theme.LIGHT ? calcLight : calcDark;
            Map<String, Object> seen = onEventThread(() -> {
                apply(theme);
                Map<String, Object> facts = new TreeMap<>();
                facts.put("laf", UIManager.getLookAndFeel());
                facts.put("panel", UIManager.getColor("Panel.background"));
                return facts;
            });
            Object laf = seen.get("laf");
            assertNotNull(laf, "a look and feel is installed after apply(" + theme + ")");
            assertTrue(expected.isInstance(laf), "after apply(" + theme + ") the look and feel is a " + expected.getName()
                    + ", is " + laf.getClass().getName());
            Color panel = (Color) seen.get("panel");
            assertNotNull(panel, "UIManager has a Panel.background after apply(" + theme + ")");
            Color base = tokens.color(theme, "surface.base");
            assertEquals(hex(base), hex(panel), "Panel.background is the token surface.base of " + theme);
        }
    }

    @Test
    @Tag("ui")
    @DisplayName("ThemeManager.installFonts : police par défaut Inter, chasse fixe préférée JetBrains Mono, familles connues du système")
    void installingTheFontsMakesInterTheDefaultAndJetBrainsMonoTheMonospacedFont() throws Throwable {
        requireDisplay();
        rememberTheOriginalLookAndFeel();
        Map<String, Object> seen = onEventThread(() -> {
            installFonts();
            // The preferred families take effect when a FlatLaf theme is set up, as at the start of the application.
            apply(Theme.LIGHT);
            Map<String, Object> facts = new TreeMap<>();
            Font font = UIManager.getFont("defaultFont");
            facts.put("default", font != null ? font : UIManager.getFont("Label.font"));
            facts.put("monospaced", type(FLAT_LAF).getMethod("getPreferredMonospacedFontFamily").invoke(null));
            return facts;
        });
        Font font = (Font) seen.get("default");
        assertNotNull(font, "UIManager has a defaultFont (or a Label.font)");
        assertEquals(INTER, font.getFamily(Locale.ROOT), "the default font of the interface is Inter");
        assertEquals(JETBRAINS_MONO, seen.get("monospaced"), "the preferred monospaced family of FlatLaf is JetBrains Mono");

        Set<String> families = Set.of(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames(Locale.ROOT));
        assertTrue(families.contains(INTER), "the graphics environment knows the family Inter");
        assertTrue(families.contains(JETBRAINS_MONO), "the graphics environment knows the family JetBrains Mono");
    }

    @Test
    @Tag("ui")
    @DisplayName("Les thèmes Calc ne lient ni Ctrl+M ni les raccourcis du guide dans leurs composants")
    void theCalcThemesBindNeitherCtrlMNorTheNotebookShortcuts() throws Throwable {
        requireDisplay();
        rememberTheOriginalLookAndFeel();
        List<KeyStroke> strokes = new ArrayList<>();
        strokes.add(NEXT_MODE);
        for (int mask : new int[] {java.awt.event.InputEvent.CTRL_DOWN_MASK, java.awt.event.InputEvent.META_DOWN_MASK}) {
            calc.ui.notebook.NotebookShortcuts shortcuts = new calc.ui.notebook.NotebookShortcuts(mask);
            for (calc.ui.notebook.NotebookShortcuts.Command command : calc.ui.notebook.NotebookShortcuts.Command.values()) {
                KeyStroke stroke = shortcuts.stroke(command);
                if (stroke.getModifiers() != 0 && !strokes.contains(stroke)) {
                    strokes.add(stroke);
                }
            }
        }
        List<String> conflicts = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (Theme theme : Theme.values()) {
            List<String> found = onEventThread(() -> {
                apply(theme);
                List<String> here = new ArrayList<>();
                UIDefaults defaults = UIManager.getLookAndFeelDefaults();
                int maps = 0;
                for (Object key : new ArrayList<>(defaults.keySet())) {
                    if (key instanceof String name && name.endsWith("InputMap") && defaults.get(name) instanceof InputMap map) {
                        maps++;
                        for (InputMap level = map; level != null; level = level.getParent()) {
                            for (KeyStroke stroke : strokes) {
                                boolean nextMode = stroke.equals(NEXT_MODE);
                                // Ctrl+M anywhere; the notebook keys with a modifier in the text fields, like NotebookShortcutConflictTest.
                                if ((nextMode || name.contains("TextField")) && level.get(stroke) != null) {
                                    here.add(theme + ": " + name + " binds " + stroke);
                                }
                            }
                        }
                    }
                }
                here.add(0, "maps=" + maps + " " + UIManager.getLookAndFeel().getClass().getName());
                return here;
            });
            String header = found.remove(0);
            assertTrue(header.startsWith("maps=") && !header.startsWith("maps=0 "), theme + " exposes input maps: " + header);
            assertTrue(header.endsWith(theme == Theme.LIGHT ? LIGHT_LAF : DARK_LAF), "the Calc theme is installed: " + header);
            checked.add(header);
            conflicts.addAll(found);
        }
        assertEquals(List.of(), conflicts, "checked: " + checked);
    }

    // ------------------------------------------------------------------------------------------------ start of the application

    @Test
    @DisplayName("Main n'appelle plus UIManager.getSystemLookAndFeelClassName()")
    void mainNoLongerAppliesTheSystemLookAndFeel() throws IOException {
        String main = read(MAIN);
        assertFalse(main.contains("getSystemLookAndFeelClassName"), MAIN + " no longer applies the system look and feel");
        assertTrue(main.contains("ThemeManager"), MAIN + " applies the theme through ThemeManager");
    }

    @Test
    @Tag("ui")
    @DisplayName("Au démarrage réel, la fenêtre utilise CalcLightLaf et les lignes (lineField, resultLabel) sont en JetBrains Mono")
    void theRealApplicationStartsOnTheLightThemeWithItsRowsInJetBrainsMono(@TempDir Path home) throws Exception {
        requireDisplay();
        Map<String, String> facts = runRealApplication(home);
        assertEquals(LIGHT_LAF, facts.get("LAF"), "the window of the real application uses CalcLightLaf: " + facts);
        assertEquals("true", facts.get("FRAME_LAF_MATCHES"), "the window was built under the installed look and feel: " + facts);
        assertNotEquals("0", facts.get("LINE_FIELDS"), "the window shows at least one line: " + facts);
        assertEquals(JETBRAINS_MONO, facts.get("LINE_FIELD_FAMILIES"), "every lineField is in JetBrains Mono: " + facts);
        assertNotEquals("0", facts.get("RESULT_LABELS"), "the window shows at least one result: " + facts);
        assertEquals(JETBRAINS_MONO, facts.get("RESULT_LABEL_FAMILIES"), "every resultLabel is in JetBrains Mono: " + facts);
    }

    // ------------------------------------------------------------------------------------------------ licences

    @Test
    @DisplayName("config/licences contient le texte complet de l'Apache License 2.0 (FlatLaf) et de l'OFL 1.1 (Inter, JetBrains Mono)")
    void theLicencesAreTheFullTexts() throws IOException {
        String flatlaf = words(read(LICENCES.resolve("FlatLaf-LICENSE.txt")));
        for (String part : List.of("Apache License", "Version 2.0, January 2004",
                "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION", "1. Definitions.", "2. Grant of Copyright License.",
                "3. Grant of Patent License.", "4. Redistribution.", "7. Disclaimer of Warranty.", "8. Limitation of Liability.",
                "9. Accepting Warranty or Additional Liability.", "END OF TERMS AND CONDITIONS")) {
            assertTrue(flatlaf.contains(part), "FlatLaf-LICENSE.txt is the full Apache License 2.0: it has \"" + part + "\"");
        }
        assertTrue(flatlaf.length() > 9000, "FlatLaf-LICENSE.txt is the full text, not a summary: " + flatlaf.length() + " characters");

        for (String name : List.of("Inter-OFL.txt", "JetBrainsMono-OFL.txt")) {
            String text = words(read(LICENCES.resolve(name)));
            String upper = text.toUpperCase(Locale.ROOT);
            for (String part : List.of("SIL OPEN FONT LICENSE", "VERSION 1.1", "PREAMBLE", "DEFINITIONS", "TERMINATION", "DISCLAIMER",
                    "Reserved Font Name")) {
                assertTrue(upper.contains(part.toUpperCase(Locale.ROOT)), name + " is the full SIL Open Font License 1.1: it has \"" + part + "\"");
            }
            // The official texts differ here: JetBrains Mono writes "PERMISSION & CONDITIONS", Inter "PERMISSION AND CONDITIONS".
            assertTrue(upper.contains("PERMISSION & CONDITIONS") || upper.contains("PERMISSION AND CONDITIONS"),
                    name + " is the full SIL Open Font License 1.1: it has its section PERMISSION & CONDITIONS");
            assertTrue(text.length() > 3500, name + " is the full text, not a summary: " + text.length() + " characters");
            String font = name.startsWith("Inter") ? "Inter" : "JetBrains Mono";
            int copyright = text.indexOf("Copyright");
            assertTrue(copyright >= 0 && copyright < text.indexOf("PREAMBLE"), name + " starts with the copyright notice of the font");
            assertTrue(text.substring(copyright, text.indexOf("PREAMBLE")).contains(font), name + " names " + font + " in its copyright notice");
        }
    }

    @Test
    @DisplayName("stageAppInput copie config/licences dans le sous-dossier licences de l'entrée jpackage")
    void theStagingTaskCopiesTheLicencesIntoTheApplicationImage() throws IOException {
        String script = lf(read(BUILD_SCRIPT));
        int start = script.indexOf("tasks.register<Sync>(\"stageAppInput\")");
        assertTrue(start >= 0, "build.gradle.kts registers the Sync task stageAppInput");
        String block = block(script, script.indexOf('{', start));
        assertTrue(block.contains("from(tasks.jar)") && block.contains("configurations.runtimeClasspath"),
                "stageAppInput still collects calc.jar and the runtime jars, as separate jars: " + block);
        Matcher licences = Pattern.compile("from\\([^\\n]*config/licences[^\\n]*\\)\\s*\\{\\s*into\\(\"licences\"\\)\\s*}").matcher(block);
        assertTrue(licences.find(), "stageAppInput has from(\"config/licences\") { into(\"licences\") }: " + block);
    }

    @Test
    @DisplayName("tools/release/smoke-test.sh échoue si app/licences ne contient pas les trois licences")
    void theSmokeTestFailsWhenALicenceIsMissingFromTheImage(@TempDir Path work) throws Exception {
        String bash = gitBash();
        Path script = SMOKE_TEST.toAbsolutePath();
        assertTrue(Files.isRegularFile(script), script + " exists");

        Run complete = smokeTest(bash, script, work.resolve("complete"), LICENCE_FILES);
        assertEquals(0, complete.exit(), "with the three licences the fake image passes the smoke test (known-good case): " + complete.output());

        for (String missing : LICENCE_FILES) {
            List<String> present = new ArrayList<>(LICENCE_FILES);
            present.remove(missing);
            Run run = smokeTest(bash, script, work.resolve("without-" + missing.replace('.', '-')), present);
            assertNotEquals(0, run.exit(), "smoke-test.sh fails when app/licences lacks " + missing + ": " + run.output());
        }
    }

    // ------------------------------------------------------------------------------------------------ calc.jar

    @Test
    @DisplayName("calc.jar reste reproductible et ne contient aucune classe de FlatLaf ; les dépendances sont des jars séparés")
    void theApplicationJarStaysReproducibleAndHoldsNoFlatLafClass() throws IOException {
        String script = lf(read(BUILD_SCRIPT));
        assertTrue(Pattern.compile("isPreserveFileTimestamps\\s*=\\s*false").matcher(script).find(),
                "the archives are built with isPreserveFileTimestamps = false");
        assertTrue(Pattern.compile("isReproducibleFileOrder\\s*=\\s*true").matcher(script).find(),
                "the archives are built with isReproducibleFileOrder = true");

        assertTrue(Files.isRegularFile(JAR), JAR + " exists (the command of this test runs `gradlew jar` first)");
        List<String> flat = new ArrayList<>();
        Set<LocalDateTime> times = new LinkedHashSet<>();
        int entries = 0;
        try (JarFile jar = new JarFile(JAR.toFile())) {
            for (Enumeration<JarEntry> all = jar.entries(); all.hasMoreElements(); ) {
                JarEntry entry = all.nextElement();
                entries++;
                String name = entry.getName();
                if (name.startsWith("com/formdev/") || name.endsWith(".ttf") || name.endsWith(".otf")) {
                    flat.add(name);
                }
                times.add(entry.getTimeLocal());
            }
        }
        assertTrue(entries > 10, "calc.jar holds the application: " + entries + " entries");
        assertEquals(List.of(), flat, "calc.jar holds no class nor font of FlatLaf");
        assertEquals(1, times.size(), "every entry of calc.jar has the same constant time (no file timestamp): " + times);
        assertEquals(1980, times.iterator().next().getYear(), "the constant time of reproducible archives: " + times);

        List<String> classPath = Arrays.stream(System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator)))
                .map(each -> Path.of(each).getFileName())
                .filter(each -> each != null)
                .map(Path::toString)
                .toList();
        for (Pattern jar : List.of(Pattern.compile("flatlaf-3\\.\\d+(\\.\\d+)?\\.jar"), Pattern.compile("flatlaf-fonts-inter-[\\d.]+\\.jar"),
                Pattern.compile("flatlaf-fonts-jetbrains-mono-[\\d.]+\\.jar"))) {
            assertTrue(classPath.stream().anyMatch(each -> jar.matcher(each).matches()),
                    "the runtime class path has the separate jar " + jar.pattern() + ": " + classPath);
        }
    }

    // ------------------------------------------------------------------------------------------------ ADR 0017

    @Test
    @DisplayName("ADR 0017 : rubriques du modèle 0000, dépendances justifiées, licences, SBOM, tailles avant et après, une ligne d'index")
    void adr0017RecordsTheDecisionWithLicencesSbomAndSizes() throws IOException {
        assertTrue(Files.isRegularFile(ADR), ADR + " exists");
        List<String> templateHeadings = lf(read(ADR_TEMPLATE)).lines().filter(line -> line.startsWith("## ")).toList();
        assertEquals(5, templateHeadings.size(), "the template has five sections: " + templateHeadings);
        String text = lf(read(ADR));
        List<String> lines = text.lines().toList();
        assertTrue(lines.get(0).startsWith("# 0017 - "), "the title line is \"# 0017 - ...\", is: " + lines.get(0));
        int previous = -1;
        for (String heading : templateHeadings) {
            int index = lines.indexOf(heading);
            assertTrue(index > previous, ADR + " has the heading \"" + heading + "\" after the previous section of the template");
            previous = index;
        }

        for (String dependency : List.of("com.formdev:flatlaf", "flatlaf-fonts-inter", "flatlaf-fonts-jetbrains-mono")) {
            assertTrue(text.contains(dependency), ADR + " justifies the dependency " + dependency);
        }
        for (String name : List.of("FlatLaf", "Inter", "JetBrains Mono")) {
            assertTrue(text.contains(name), ADR + " names " + name);
        }
        assertTrue(text.contains("Apache") && text.contains("2.0"), ADR + " cites the Apache License 2.0 of FlatLaf");
        assertTrue((text.contains("OFL") || text.contains("Open Font License")) && text.contains("1.1"),
                ADR + " cites the SIL Open Font License 1.1 of the fonts");
        assertTrue(text.contains("SBOM"), ADR + " says the three components are in the SBOM");

        Set<Long> sizes = new TreeSet<>();
        Matcher bytes = BYTES.matcher(text);
        while (bytes.find()) {
            long value = Long.parseLong(bytes.group(1).replaceAll("[^\\d]", ""));
            if (value > 1_000_000L) {
                sizes.add(value);
            }
        }
        assertTrue(sizes.size() >= 2, ADR + " gives the size of the Windows archive before and after, in bytes (« octets »): " + sizes);
        String lower = text.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("avant") && lower.contains("après"), ADR + " says which size is before and which is after");
        assertTrue(lower.contains("windows"), ADR + " names the Windows archive (Calc-1.0.0-windows-*.zip)");

        long rows = lf(read(ADR_INDEX)).lines().filter(line -> line.startsWith("| 0017 |")).count();
        assertEquals(1, rows, ADR_INDEX + " has exactly one line \"| 0017 |\"");
    }

    // ------------------------------------------------------------------------------------------------ the real application

    /** Starts {@link Main} in its own process, data in {@code home}, and returns what {@link RealApplication} printed. */
    private static Map<String, String> runRealApplication(Path home) throws Exception {
        Path output = home.resolve("output.txt");
        ProcessBuilder builder = new ProcessBuilder(javaCommand(), "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"),
                RealApplication.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("APPDATA", home.resolve("AppData").toString());
        builder.environment().put("XDG_DATA_HOME", home.resolve("xdg").toString());
        Process process = builder.start();
        try {
            boolean ended = process.waitFor(90, TimeUnit.SECONDS);
            String printed = Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
            assertTrue(ended, "the real application answered within 90 s: " + printed);
            Map<String, String> facts = new TreeMap<>();
            for (String line : lf(printed).lines().toList()) {
                int equals = line.indexOf('=');
                if (equals > 0 && line.substring(0, equals).matches("[A-Z_]+")) {
                    facts.put(line.substring(0, equals), line.substring(equals + 1));
                }
            }
            assertEquals("0", facts.get("EXIT"), "the probe of the real application ended normally: " + printed);
            return facts;
        } finally {
            process.destroyForcibly();
        }
    }

    /** The real application, data redirected by its parent: starts {@link Main}, waits for the window, prints what it shows. */
    static final class RealApplication {
        private RealApplication() {
        }

        public static void main(String[] args) throws Exception {
            PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            try {
                Main.main(new String[0]);
                Frame frame = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (frame == null && System.nanoTime() < deadline) {
                    Frame[] found = new Frame[1];
                    EventQueue.invokeAndWait(() -> {
                        for (Frame each : Frame.getFrames()) {
                            if (each.isShowing() && !find(each, "lineField").isEmpty()) {
                                found[0] = each;
                            }
                        }
                    });
                    frame = found[0];
                    Thread.sleep(20);
                }
                if (frame == null) {
                    out.println("NO_WINDOW=true");
                    out.println("EXIT=1");
                    Runtime.getRuntime().halt(0);
                }
                Frame window = frame;
                Map<String, String> facts = new TreeMap<>();
                EventQueue.invokeAndWait(() -> {
                    LookAndFeel laf = UIManager.getLookAndFeel();
                    facts.put("LAF", laf == null ? "none" : laf.getClass().getName());
                    String rootUi = window instanceof javax.swing.JFrame swing ? swing.getRootPane().getUI().getClass().getName() : "";
                    // A FlatLaf window has a root pane UI of FlatLaf: the window was built after the theme was set up.
                    facts.put("FRAME_LAF_MATCHES", Boolean.toString(rootUi.startsWith("com.formdev.flatlaf.")));
                    facts.put("ROOT_PANE_UI", rootUi);
                    List<Component> fields = find(window, "lineField");
                    List<Component> results = find(window, "resultLabel");
                    facts.put("LINE_FIELDS", Integer.toString(fields.size()));
                    facts.put("RESULT_LABELS", Integer.toString(results.size()));
                    facts.put("LINE_FIELD_FAMILIES", families(fields));
                    facts.put("RESULT_LABEL_FAMILIES", families(results));
                });
                facts.forEach((key, value) -> out.println(key + "=" + value));
                out.println("EXIT=0");
            } catch (Throwable problem) {
                out.println("PROBLEM=" + problem);
                problem.printStackTrace(out);
                out.println("EXIT=1");
            }
            out.flush();
            Runtime.getRuntime().halt(0);
        }

        private static String families(List<Component> components) {
            Set<String> families = new TreeSet<>();
            for (Component component : components) {
                Font font = component.getFont();
                families.add(font == null ? "null" : font.getFamily(Locale.ROOT));
            }
            return String.join(",", families);
        }
    }

    private static List<Component> find(Container container, String name) {
        List<Component> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (name.equals(child.getName())) {
                found.add(child);
            }
            if (child instanceof Container inner) {
                found.addAll(find(inner, name));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------------------------------------ the smoke test

    private record Run(int exit, String output) {
    }

    /**
     * Runs smoke-test.sh on a fake Linux application image (tar.gz): a launcher that stays alive, a runtime with the expected
     * modules, and app/licences holding {@code licences} only.
     */
    private static Run smokeTest(String bash, Path script, Path root, List<String> licences) throws Exception {
        Path image = root.resolve("Calc");
        Path launcher = image.resolve("bin").resolve("Calc");
        Files.createDirectories(launcher.getParent());
        Files.write(launcher, "#!/bin/sh\nexec sleep 30\n".getBytes(StandardCharsets.US_ASCII));
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        Path release = image.resolve("lib").resolve("runtime").resolve("release");
        Files.createDirectories(release.getParent());
        Files.write(release, "JAVA_VERSION=\"21\"\nMODULES=\"java.base java.datatransfer java.desktop java.prefs jdk.localedata\"\n"
                .getBytes(StandardCharsets.US_ASCII));
        Path app = image.resolve("lib").resolve("app");
        Files.createDirectories(app.resolve("licences"));
        Files.write(app.resolve("calc.jar"), new byte[0]);
        for (String licence : licences) {
            String text = licence.startsWith("FlatLaf") ? "Apache License\nVersion 2.0, January 2004\n"
                    : "SIL OPEN FONT LICENSE Version 1.1 - 26 February 2007\n";
            Files.write(app.resolve("licences").resolve(licence), text.getBytes(StandardCharsets.US_ASCII));
        }
        String archive = "Calc-1.0.0-linux-x64.tar.gz";
        Run packed = run(root, root.resolve("tar.log"), bash, "-c", "tar -czf " + archive + " Calc");
        assertEquals(0, packed.exit(), "the fake image is packed: " + packed.output());
        return run(root, root.resolve("smoke.log"), bash, script.toString().replace('\\', '/'), archive, "1");
    }

    private static Run run(Path directory, Path log, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(log.toFile()).start();
        try {
            boolean ended = process.waitFor(120, TimeUnit.SECONDS);
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertTrue(ended, String.join(" ", command) + " ended within 120 s: " + output);
            return new Run(process.exitValue(), output);
        } finally {
            process.destroyForcibly();
        }
    }

    /** Git for Windows' bash by its full path on Windows (`bash` there may be WSL), else the bash of the system. */
    private static String gitBash() {
        if (!System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            return "bash";
        }
        List<Path> candidates = new ArrayList<>();
        for (String variable : List.of("ProgramFiles", "ProgramW6432", "LOCALAPPDATA")) {
            String root = System.getenv(variable);
            if (root != null) {
                candidates.add(Path.of(root, "Git", "bin", "bash.exe"));
                candidates.add(Path.of(root, "Programs", "Git", "bin", "bash.exe"));
            }
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
                if (!directory.isBlank() && Files.isRegularFile(Path.of(directory.trim(), "git.exe"))) {
                    Path parent = Path.of(directory.trim()).getParent();
                    if (parent != null) {
                        candidates.add(parent.resolve("bin").resolve("bash.exe"));
                    }
                }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        fail("Git for Windows' bash.exe was not found (tried " + candidates + "): it runs tools/release/smoke-test.sh");
        return "";
    }

    // ------------------------------------------------------------------------------------------------ ThemeManager

    private interface EventThreadWork<T> {
        T run() throws Throwable;
    }

    private static <T> T onEventThread(EventThreadWork<T> work) throws Throwable {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> problem = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.run());
            } catch (Throwable thrown) {
                problem.set(thrown);
            }
        });
        if (problem.get() != null) {
            throw problem.get();
        }
        return result.get();
    }

    private static void rememberTheOriginalLookAndFeel() {
        ORIGINAL.compareAndSet(null, UIManager.getLookAndFeel());
    }

    private static void apply(Theme theme) throws Throwable {
        invokeThemeManager("apply", new Class<?>[] {Theme.class}, theme);
    }

    private static void installFonts() throws Throwable {
        invokeThemeManager("installFonts", new Class<?>[0]);
    }

    private static void invokeThemeManager(String name, Class<?>[] parameters, Object... arguments) throws Throwable {
        Class<?> manager = type(THEME_MANAGER);
        Method method;
        try {
            method = manager.getDeclaredMethod(name, parameters);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(THEME_MANAGER + " has a method " + name + Arrays.toString(parameters), missing);
        }
        method.setAccessible(true);
        Object target = null;
        if (!Modifier.isStatic(method.getModifiers())) {
            try {
                var constructor = manager.getDeclaredConstructor();
                constructor.setAccessible(true);
                target = constructor.newInstance();
            } catch (NoSuchMethodException missing) {
                throw new AssertionError(name + " is static, or " + THEME_MANAGER + " has a constructor without argument", missing);
            }
        }
        try {
            method.invoke(target, arguments);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("the class " + name + " exists", missing);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void requireDisplay() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
    }

    private static String hex(Color color) {
        return String.format(Locale.ROOT, "#%06X", color.getRGB() & 0xFFFFFF);
    }

    /** The text from {@code open}, a '{', to its matching '}'. */
    private static String block(String text, int open) {
        assertTrue(open >= 0, "a block opens with {");
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char each = text.charAt(index);
            if (each == '{') {
                depth++;
            } else if (each == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(open, index + 1);
                }
            }
        }
        throw new AssertionError("the block opened at " + open + " is closed");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), path + " exists");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String lf(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** The text with every run of white space made one space: a licence wrapped differently still reads the same. */
    private static String words(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
