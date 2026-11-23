package calc.ui.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.ui.widget.Contrast;
import java.awt.Color;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Acceptance of the design tokens (E02): {@code tokens.properties} is the single source of the colours, sizes and fonts; the
 * runtime reader {@code DesignTokens}, the generator {@code DesignTokenGenerator} and its three versioned outputs (two FlatLaf
 * themes and {@code web/site/tokens.css}), the contrast pairs of both themes, the Gradle task {@code generateDesignTokens},
 * the palettes that read the tokens and ADR 0016.
 *
 * <p>{@code DesignTokens}, {@code Theme} and {@code DesignTokenGenerator} are reached by reflection, so that this class compiles,
 * and fails with a clear message, before they exist. Paths are relative to the repository root, the working directory of the
 * tests.
 */
@Tag("acceptance")
final class DesignTokensAcceptanceTest {

    private static final String TOKENS_CLASS = "calc.ui.design.DesignTokens";
    private static final String THEME_CLASS = "calc.ui.design.Theme";
    private static final String GENERATOR_CLASS = "calc.ui.design.DesignTokenGenerator";
    private static final String TOKENS_RESOURCE = "/calc/ui/design/tokens.properties";

    private static final Path TOKENS = Path.of("src", "main", "resources", "calc", "ui", "design", "tokens.properties");
    private static final Path LIGHT_LAF = Path.of("src", "main", "resources", "calc", "ui", "theme", "CalcLightLaf.properties");
    private static final Path DARK_LAF = Path.of("src", "main", "resources", "calc", "ui", "theme", "CalcDarkLaf.properties");
    private static final Path TOKENS_CSS = Path.of("web", "site", "tokens.css");
    private static final Path BUILD_SCRIPT = Path.of("build.gradle.kts");
    private static final Path PALETTE = Path.of("src", "main", "java", "calc", "ui", "widget", "Palette.java");
    private static final Path NOTEBOOK_PALETTE = Path.of("src", "main", "java", "calc", "ui", "notebook", "NotebookPalette.java");
    private static final Path PALETTE_TEST = Path.of("src", "test", "java", "calc", "ui", "widget", "PaletteContrastTest.java");
    private static final Path NOTEBOOK_PALETTE_TEST =
            Path.of("src", "test", "java", "calc", "ui", "notebook", "NotebookPaletteContrastTest.java");
    private static final Path ADR = Path.of("docs", "adr", "0016-systeme-de-design-et-jetons.md");
    private static final Path ADR_TEMPLATE = Path.of("docs", "adr", "0000-modele.md");
    private static final Path ADR_INDEX = Path.of("docs", "adr", "README.md");

    private static final List<String> THEMES = List.of("light", "dark");
    private static final List<String> REQUIRED_ROLES = List.of(
            "surface.base", "surface.raised", "surface.hover", "surface.active",
            "border.subtle", "border.focus",
            "text.primary", "text.secondary", "text.muted",
            "accent", "exact.sign", "approximate.sign", "source.mark",
            "error.text", "error.surface", "error.underline",
            "selection.background", "selection.text");
    private static final Pattern HEX = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern PAIR = Pattern.compile("^\\s*(\\S+)\\s+on\\s+(\\S+)\\s*$");
    private static final Pattern PIXELS = Pattern.compile("^\\s*(\\d+)\\s*(px)?\\s*$");
    private static final double UI_RATIO = 3.0;

    // ------------------------------------------------------------------------------------------------ tokens.properties

    @Test
    @DisplayName("tokens.properties : mêmes rôles #RRGGBB dans les deux thèmes, rôles minimaux présents")
    void tokensDeclareTheSameColourRolesInBothThemes() throws IOException {
        Properties tokens = tokens();
        Map<String, String> light = colours(tokens, "light");
        Map<String, String> dark = colours(tokens, "dark");
        assertFalse(light.isEmpty(), "tokens.properties declares color.light.<role> keys");
        assertEquals(light.keySet(), dark.keySet(), "the light and the dark themes declare exactly the same roles");
        for (String theme : THEMES) {
            colours(tokens, theme).forEach((role, value) -> assertTrue(HEX.matcher(value).matches(),
                    "color." + theme + "." + role + " is #RRGGBB, is: " + value));
        }
        for (String role : REQUIRED_ROLES) {
            assertTrue(light.containsKey(role), "the role " + role + " is declared (color.light." + role + " and color.dark." + role + ")");
        }
    }

    @Test
    @DisplayName("tokens.properties : polices Inter et JetBrains Mono, tailles, espacements multiples de 4, rayons")
    void tokensDeclareFontsSizesSpacesAndRadii() throws IOException {
        Properties tokens = tokens();
        assertEquals("Inter", tokens.getProperty("font.ui.family"), "font.ui.family");
        assertEquals("JetBrains Mono", tokens.getProperty("font.mono.family"), "font.mono.family");
        Map<String, Integer> sizes = pixels(tokens, "font.size.");
        Map<String, Integer> spaces = pixels(tokens, "space.");
        Map<String, Integer> radii = pixels(tokens, "radius.");
        assertFalse(sizes.isEmpty(), "tokens.properties declares font.size.* keys");
        assertFalse(spaces.isEmpty(), "tokens.properties declares space.* keys");
        assertFalse(radii.isEmpty(), "tokens.properties declares radius.* keys");
        sizes.forEach((name, size) -> assertTrue(size > 0, "font.size." + name + " is a positive size: " + size));
        spaces.forEach((name, space) -> assertEquals(0, space % 4, "space." + name + " is on the 4 px grid: " + space));
        for (String key : tokens.stringPropertyNames()) {
            assertTrue(key.startsWith("color.light.") || key.startsWith("color.dark.") || key.startsWith("font.")
                            || key.startsWith("space.") || key.startsWith("radius.") || key.startsWith("contrast.text.")
                            || key.startsWith("contrast.ui."),
                    "every key follows one of the formats of the tokens: " + key);
        }
    }

    // ------------------------------------------------------------------------------------------------ DesignTokens

    @Test
    @DisplayName("DesignTokens.load() lit le classpath ; color(thème, rôle) rend la couleur déclarée")
    void designTokensReturnsTheDeclaredColours() throws Exception {
        assertNotNull(DesignTokensAcceptanceTest.class.getResource(TOKENS_RESOURCE), "tokens.properties is on the classpath: " + TOKENS_RESOURCE);
        Properties tokens = tokens();
        Object loaded = load();
        for (String theme : THEMES) {
            Object constant = theme(theme.equals("light") ? "LIGHT" : "DARK");
            for (Map.Entry<String, String> entry : colours(tokens, theme).entrySet()) {
                Color expected = Color.decode(entry.getValue());
                assertEquals(expected, color(loaded, constant, entry.getKey()), "color(" + constant + ", " + entry.getKey() + ")");
            }
        }
    }

    @Test
    @DisplayName("DesignTokens.color : un rôle inconnu lève IllegalArgumentException qui cite le rôle")
    void anUnknownRoleIsRefusedWithItsName() throws Exception {
        Object loaded = load();
        String unknown = "surface.inconnue-e02";
        for (String name : List.of("LIGHT", "DARK")) {
            try {
                Object value = colorMethod().invoke(Modifier.isStatic(colorMethod().getModifiers()) ? null : loaded, theme(name), unknown);
                fail("color(" + name + ", " + unknown + ") must throw IllegalArgumentException, returned " + value);
            } catch (InvocationTargetException thrown) {
                Throwable cause = thrown.getCause();
                assertTrue(cause instanceof IllegalArgumentException, "an unknown role throws IllegalArgumentException, threw " + cause);
                assertNotNull(cause.getMessage(), "the exception carries a message");
                assertTrue(cause.getMessage().contains(unknown), "the message names the role: " + cause.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ contrast

    @Test
    @DisplayName("Contrastes : texte au moins 4,5:1, indicateurs au moins 3:1, dans les deux thèmes ; tableau imprimé")
    void everyContrastPairReachesItsMinimumInBothThemes() throws Exception {
        Properties tokens = tokens();
        Map<String, String[]> textPairs = pairs(tokens, "contrast.text.");
        Map<String, String[]> uiPairs = pairs(tokens, "contrast.ui.");
        assertFalse(textPairs.isEmpty(), "tokens.properties declares contrast.text.* pairs");
        assertFalse(uiPairs.isEmpty(), "tokens.properties declares contrast.ui.* pairs");
        Object loaded = load();
        StringBuilder table = new StringBuilder(String.format("%-40s %-50s %8s %8s %8s%n", "pair", "roles", "light", "dark", "minimum"));
        List<String> failures = new ArrayList<>();
        Map<String, Map<String, String[]>> groups = new LinkedHashMap<>();
        groups.put("contrast.text.", textPairs);
        groups.put("contrast.ui.", uiPairs);
        for (Map.Entry<String, Map<String, String[]>> group : groups.entrySet()) {
            double minimum = group.getKey().equals("contrast.text.") ? Contrast.MINIMUM_TEXT_RATIO : UI_RATIO;
            for (Map.Entry<String, String[]> pair : group.getValue().entrySet()) {
                String[] roles = pair.getValue();
                double[] ratios = new double[2];
                for (int i = 0; i < 2; i++) {
                    Object constant = theme(i == 0 ? "LIGHT" : "DARK");
                    ratios[i] = Contrast.ratio(color(loaded, constant, roles[0]), color(loaded, constant, roles[1]));
                    if (ratios[i] < minimum) {
                        failures.add(String.format(Locale.ROOT, "%s%s (%s on %s) in %s: %.2f < %.1f",
                                group.getKey(), pair.getKey(), roles[0], roles[1], constant, ratios[i], minimum));
                    }
                }
                table.append(String.format(Locale.ROOT, "%-40s %-50s %8.2f %8.2f %8.1f%n",
                        group.getKey() + pair.getKey(), roles[0] + " on " + roles[1], ratios[0], ratios[1], minimum));
            }
        }
        System.out.print(table);
        assertTrue(failures.isEmpty(), "pairs under their minimum: " + failures);
    }

    @Test
    @DisplayName("Contrastes : chaque rôle de texte, de signe, de marque et de sélection est mesuré ; focus et soulignement d'erreur aussi")
    void everyForegroundRoleIsCoveredByAPair() throws IOException {
        Properties tokens = tokens();
        Set<String> roles = colours(tokens, "light").keySet();
        Map<String, String[]> textPairs = pairs(tokens, "contrast.text.");
        Map<String, String[]> uiPairs = pairs(tokens, "contrast.ui.");
        Set<String> textForegrounds = new TreeSet<>();
        textPairs.values().forEach(pair -> textForegrounds.add(pair[0]));
        Set<String> uiMembers = new TreeSet<>();
        uiPairs.values().forEach(pair -> {
            uiMembers.add(pair[0]);
            uiMembers.add(pair[1]);
        });
        for (String role : roles) {
            boolean text = role.startsWith("text.") || role.endsWith(".sign") || role.endsWith(".mark")
                    || role.equals("error.text") || role.equals("selection.text");
            if (text) {
                assertTrue(textForegrounds.contains(role), role + " is the first member of a contrast.text.* pair; foregrounds: " + textForegrounds);
            }
        }
        for (String role : List.of("text.primary", "text.secondary", "text.muted", "exact.sign", "approximate.sign", "source.mark",
                "error.text", "selection.text")) {
            assertTrue(textForegrounds.contains(role), role + " is the first member of a contrast.text.* pair");
        }
        for (String role : List.of("border.focus", "error.underline")) {
            assertTrue(uiMembers.contains(role), role + " is measured by a contrast.ui.* pair; members: " + uiMembers);
        }
    }

    // ------------------------------------------------------------------------------------------------ generator

    @Test
    @DisplayName("Générateur : les trois fichiers versionnés sont exactement sa sortie (fins de ligne LF)")
    void theVersionedFilesAreTheOutputOfTheGenerator() throws Exception {
        String light = generated("flatLafProperties", theme("LIGHT"));
        String dark = generated("flatLafProperties", theme("DARK"));
        String css = generated("css");
        assertEquals(light, generated("flatLafProperties", theme("LIGHT")), "the output is deterministic");
        assertEquals(css, generated("css"), "the output is deterministic");
        for (String output : List.of(light, dark, css)) {
            assertFalse(output.contains("\r"), "the generator writes LF line endings");
            assertFalse(output.isBlank(), "the generator writes something");
        }
        assertEquals(light, lf(read(LIGHT_LAF)), LIGHT_LAF + " differs from the generator: run gradlew generateDesignTokens");
        assertEquals(dark, lf(read(DARK_LAF)), DARK_LAF + " differs from the generator: run gradlew generateDesignTokens");
        assertEquals(css, lf(read(TOKENS_CSS)), TOKENS_CSS + " differs from the generator: run gradlew generateDesignTokens");
        Method main = generatorClass().getMethod("main", String[].class);
        assertTrue(Modifier.isStatic(main.getModifiers()) && Modifier.isPublic(main.getModifiers()), "DesignTokenGenerator has a public static main");
    }

    @Test
    @DisplayName("tokens.css : --color-* en clair, en sombre (@media) et dans [data-theme=\"clair\"] / [data-theme=\"sombre\"]")
    void theStyleSheetDeclaresEveryColourInEveryBlock() throws IOException {
        Properties tokens = tokens();
        String css = stripComments(lf(read(TOKENS_CSS)));
        assertFalse(css.contains("@import"), "tokens.css has no @import");
        assertFalse(css.contains("url("), "tokens.css has no url(");

        int rootStart = css.indexOf(":root");
        Matcher media = Pattern.compile("@media\\s*\\(\\s*prefers-color-scheme\\s*:\\s*dark\\s*\\)").matcher(css);
        assertTrue(media.find(), "tokens.css has @media (prefers-color-scheme: dark)");
        int clairStart = css.indexOf("[data-theme=\"clair\"]");
        int sombreStart = css.indexOf("[data-theme=\"sombre\"]");
        assertTrue(rootStart >= 0, "tokens.css has a :root block");
        assertTrue(clairStart >= 0, "tokens.css has a [data-theme=\"clair\"] block");
        assertTrue(sombreStart >= 0, "tokens.css has a [data-theme=\"sombre\"] block");
        assertTrue(rootStart < media.start(), ":root (light) comes before the @media block");
        assertTrue(media.start() < clairStart && media.start() < sombreStart, "the [data-theme] blocks come after the @media block, so they win");

        Map<String, String> rootBlock = declarations(block(css, rootStart));
        String mediaBody = block(css, media.start());
        int innerRoot = mediaBody.indexOf(":root");
        assertTrue(innerRoot >= 0, "the @media block holds a :root block");
        Map<String, String> darkBlock = declarations(block(mediaBody, innerRoot));
        Map<String, String> clairBlock = declarations(block(css, clairStart));
        Map<String, String> sombreBlock = declarations(block(css, sombreStart));

        Map<String, Map<String, String>> expected = new LinkedHashMap<>();
        expected.put(":root", rootBlock);
        expected.put("[data-theme=\"clair\"]", clairBlock);
        for (Map.Entry<String, Map<String, String>> entry : expected.entrySet()) {
            assertColours(entry.getKey(), entry.getValue(), colours(tokens, "light"));
        }
        assertColours("@media (prefers-color-scheme: dark) :root", darkBlock, colours(tokens, "dark"));
        assertColours("[data-theme=\"sombre\"]", sombreBlock, colours(tokens, "dark"));
    }

    @Test
    @DisplayName("tokens.css : --font-ui (Inter), --font-mono (\"JetBrains Mono\"), --space-* et --radius-*")
    void theStyleSheetDeclaresFontsSpacesAndRadii() throws IOException {
        Properties tokens = tokens();
        String css = stripComments(lf(read(TOKENS_CSS)));
        Map<String, String> all = declarations(css);
        String ui = all.get("--font-ui");
        String mono = all.get("--font-mono");
        assertNotNull(ui, "tokens.css declares --font-ui");
        assertNotNull(mono, "tokens.css declares --font-mono");
        assertTrue(ui.startsWith("Inter") || ui.startsWith("\"Inter\""), "--font-ui starts with Inter: " + ui);
        assertTrue(mono.startsWith("\"JetBrains Mono\""), "--font-mono starts with \"JetBrains Mono\": " + mono);
        for (Map.Entry<String, String> prefix : Map.of("space.", "--space-", "radius.", "--radius-").entrySet()) {
            Map<String, Integer> values = pixels(tokens, prefix.getKey());
            assertFalse(values.isEmpty(), "tokens.properties declares " + prefix.getKey() + "* keys");
            for (Map.Entry<String, Integer> value : values.entrySet()) {
                String property = prefix.getValue() + value.getKey().replace('.', '-');
                String declared = all.get(property);
                assertNotNull(declared, "tokens.css declares " + property);
                Matcher number = Pattern.compile("^(\\d+)(px)?$").matcher(declared);
                assertTrue(number.matches() && Integer.parseInt(number.group(1)) == value.getValue()
                                && (number.group(2) != null || value.getValue() == 0),
                        property + " is " + value.getValue() + "px, is: " + declared);
            }
        }
    }

    @Test
    @DisplayName("Thèmes FlatLaf : @background, @foreground, @accentColor, Component.focusColor et Calc.<rôle> viennent des jetons")
    void theFlatLafThemesComeFromTheTokens() throws IOException {
        Properties tokens = tokens();
        Map<String, Path> files = new LinkedHashMap<>();
        files.put("light", LIGHT_LAF);
        files.put("dark", DARK_LAF);
        for (Map.Entry<String, Path> file : files.entrySet()) {
            Map<String, String> theme = colours(tokens, file.getKey());
            Properties laf = new Properties();
            laf.load(new StringReader(read(file.getValue())));
            Set<String> tokenColours = new TreeSet<>();
            theme.values().forEach(value -> tokenColours.add(value.toUpperCase(Locale.ROOT)));
            for (String key : List.of("@background", "@foreground", "@accentColor", "Component.focusColor")) {
                String value = resolve(laf, key);
                assertNotNull(value, file.getValue() + " defines " + key);
                assertTrue(tokenColours.contains(value.toUpperCase(Locale.ROOT)),
                        file.getValue() + ": " + key + " = " + value + " is a colour of the " + file.getKey() + " tokens");
            }
            for (Map.Entry<String, String> role : theme.entrySet()) {
                String key = "Calc." + role.getKey();
                String value = resolve(laf, key);
                assertNotNull(value, file.getValue() + " defines " + key);
                assertEquals(role.getValue().toUpperCase(Locale.ROOT), value.toUpperCase(Locale.ROOT), file.getValue() + ": " + key);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ Gradle task

    @Test
    @DisplayName("Gradle : generateDesignTokens lance le générateur et n'est liée ni à check, ni à build, ni à assemble")
    void gradleTaskGenerateDesignTokensIsOutsideTheBuild() throws IOException {
        String script = lf(read(BUILD_SCRIPT));
        Matcher register = Pattern.compile("register\\s*<\\s*JavaExec\\s*>\\s*\\(\\s*\"generateDesignTokens\"\\s*\\)").matcher(script);
        assertTrue(register.find(), "build.gradle.kts registers the JavaExec task generateDesignTokens");
        int start = register.start();
        int open = script.indexOf('{', start);
        int end = closing(script, open);
        assertTrue(open > 0 && end > open, "the generateDesignTokens block is closed");
        String block = script.substring(start, end + 1);
        assertTrue(block.contains(GENERATOR_CLASS), "the task runs " + GENERATOR_CLASS);
        assertTrue(block.contains("sourceSets") && block.contains("runtimeClasspath"), "the task runs on the test runtime classpath");
        assertFalse(Pattern.compile("(dependsOn|finalizedBy)\\s*\\([^)]*\"(check|build|assemble)\"").matcher(block).find(),
                "the task does not hang itself on check, build or assemble");
        String outside = script.substring(0, start) + script.substring(end + 1);
        for (String line : outside.split("\n")) {
            if (line.contains("generateDesignTokens") && Pattern.compile("dependsOn|finalizedBy|mustRunAfter|shouldRunAfter").matcher(line).find()) {
                fail("another task depends on generateDesignTokens: " + line.strip());
            }
        }
        assertFalse(Pattern.compile("(?s)(named|getByName|tasks)[^\\n]*\"(check|build|assemble|processResources|compileJava)\"[^}]*generateDesignTokens")
                .matcher(outside).find(), "check, build and assemble do not run generateDesignTokens");
    }

    // ------------------------------------------------------------------------------------------------ palettes

    @Test
    @DisplayName("Palette et NotebookPalette : plus aucun « new Color( », les couleurs viennent de DesignTokens")
    void thePalettesReadTheTokens() throws IOException {
        for (Path palette : List.of(PALETTE, NOTEBOOK_PALETTE)) {
            String source = read(palette);
            assertFalse(source.contains("new Color("), palette + " has no new Color( left");
            assertTrue(source.contains("DesignTokens"), palette + " reads its colours from DesignTokens");
        }
    }

    @Test
    @DisplayName("PaletteContrastTest et NotebookPaletteContrastTest existent et vérifient les deux thèmes")
    void theContrastTestsOfThePalettesCheckBothThemes() throws IOException {
        for (Path test : List.of(PALETTE_TEST, NOTEBOOK_PALETTE_TEST)) {
            assertTrue(Files.isRegularFile(test), test + " still exists (named by G22 in docs/TESTS.md)");
            String source = read(test);
            assertTrue(source.contains("Contrast.ratio"), test + " measures contrast ratios");
            boolean both = source.contains("Theme.values()") || (source.contains("LIGHT") && source.contains("DARK"));
            assertTrue(source.contains("Theme") && both, test + " checks the light and the dark themes");
        }
    }

    // ------------------------------------------------------------------------------------------------ ADR 0016

    @Test
    @DisplayName("ADR 0016 : les rubriques du modèle 0000 dans l'ordre, et une seule ligne « | 0016 | » dans l'index")
    void adr0016HasTheTemplateSectionsAndOneIndexLine() throws IOException {
        assertTrue(Files.isRegularFile(ADR), ADR + " exists");
        List<String> templateHeadings = lf(read(ADR_TEMPLATE)).lines().filter(line -> line.startsWith("## ")).toList();
        assertEquals(5, templateHeadings.size(), "the template has five sections: " + templateHeadings);
        List<String> adrLines = lf(read(ADR)).lines().toList();
        assertTrue(adrLines.get(0).startsWith("# 0016 - "), "the title line is \"# 0016 - ...\", is: " + adrLines.get(0));
        int previous = -1;
        for (String heading : templateHeadings) {
            int index = adrLines.indexOf(heading);
            assertTrue(index > previous, ADR + " has the heading \"" + heading + "\" after the previous section of the template");
            previous = index;
        }
        for (String word : List.of("Status", "Context", "Decision", "Alternatives considered", "Consequences")) {
            assertTrue(templateHeadings.stream().anyMatch(heading -> heading.contains(word)), "the template has the section " + word);
        }
        long rows = lf(read(ADR_INDEX)).lines().filter(line -> line.startsWith("| 0016 |")).count();
        assertEquals(1, rows, ADR_INDEX + " has exactly one line \"| 0016 |\"");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Properties tokens() throws IOException {
        assertTrue(Files.isRegularFile(TOKENS), TOKENS + " exists");
        Properties properties = new Properties();
        properties.load(new StringReader(read(TOKENS)));
        return properties;
    }

    /** The colour roles of one theme, role -> value, sorted by role. */
    private static Map<String, String> colours(Properties tokens, String theme) {
        String prefix = "color." + theme + ".";
        Map<String, String> colours = new TreeMap<>();
        for (String key : tokens.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                colours.put(key.substring(prefix.length()), tokens.getProperty(key).strip());
            }
        }
        return colours;
    }

    private static Map<String, Integer> pixels(Properties tokens, String prefix) {
        Map<String, Integer> values = new TreeMap<>();
        for (String key : tokens.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                Matcher number = PIXELS.matcher(tokens.getProperty(key));
                assertTrue(number.matches(), key + " is a number of pixels, is: " + tokens.getProperty(key));
                values.put(key.substring(prefix.length()), Integer.parseInt(number.group(1)));
            }
        }
        return values;
    }

    /** The pairs of one kind, name -> {foreground role, background role}; every role is a declared colour role. */
    private static Map<String, String[]> pairs(Properties tokens, String prefix) {
        Set<String> roles = colours(tokens, "light").keySet();
        Map<String, String[]> pairs = new TreeMap<>();
        for (String key : tokens.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                Matcher pair = PAIR.matcher(tokens.getProperty(key));
                assertTrue(pair.matches(), key + " reads \"<role> on <role>\", is: " + tokens.getProperty(key));
                for (int group = 1; group <= 2; group++) {
                    assertTrue(roles.contains(pair.group(group)), key + " names a declared colour role: " + pair.group(group));
                }
                pairs.put(key.substring(prefix.length()), new String[] {pair.group(1), pair.group(2)});
            }
        }
        return pairs;
    }

    private static Class<?> tokensClass() {
        return type(TOKENS_CLASS);
    }

    private static Class<?> generatorClass() {
        return type(GENERATOR_CLASS);
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError(name + " does not exist yet", missing);
        }
    }

    private static Object theme(String name) {
        Class<?> theme = type(THEME_CLASS);
        assertTrue(theme.isEnum(), THEME_CLASS + " is an enum");
        for (Object constant : theme.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new AssertionError(THEME_CLASS + " has no constant " + name);
    }

    private static Object load() throws Exception {
        Method load = tokensClass().getMethod("load");
        assertTrue(Modifier.isStatic(load.getModifiers()), "DesignTokens.load() is static");
        return invoke(load, null);
    }

    private static Method colorMethod() throws NoSuchMethodException {
        Method color = tokensClass().getMethod("color", type(THEME_CLASS), String.class);
        assertEquals(Color.class, color.getReturnType(), "color(Theme, String) returns a java.awt.Color");
        return color;
    }

    private static Color color(Object loaded, Object theme, String role) throws Exception {
        Method color = colorMethod();
        Object target = Modifier.isStatic(color.getModifiers()) ? null : loaded;
        if (target == null && !Modifier.isStatic(color.getModifiers())) {
            fail("DesignTokens.load() returned null and color(Theme, String) is an instance method");
        }
        return (Color) invoke(color, target, theme, role);
    }

    /** One output of the generator: a public method, static or called on an instance made by the no-argument constructor. */
    private static String generated(String name, Object... arguments) throws Exception {
        Class<?> generator = generatorClass();
        Method method = null;
        for (Method candidate : generator.getMethods()) {
            if (candidate.getName().equals(name) && candidate.getParameterCount() == arguments.length) {
                method = candidate;
            }
        }
        assertNotNull(method, GENERATOR_CLASS + " has a public method " + name + " with " + arguments.length + " parameter(s)");
        Object target = Modifier.isStatic(method.getModifiers()) ? null : generator.getConstructor().newInstance();
        Object output = invoke(method, target, arguments);
        assertNotNull(output, name + " returns a text");
        return output.toString();
    }

    private static Object invoke(Method method, Object target, Object... arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException thrown) {
            Throwable cause = thrown.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new AssertionError(method + " failed", cause);
        }
    }

    /** Follows FlatLaf references ($key and @variable) down to a #RRGGBB colour, or null when the key is missing. */
    private static String resolve(Properties laf, String key) {
        String value = laf.getProperty(key);
        for (int depth = 0; value != null && depth < 10; depth++) {
            value = value.strip();
            if (value.startsWith("$")) {
                value = laf.getProperty(value.substring(1));
            } else if (value.startsWith("@")) {
                value = laf.getProperty(value);
            } else {
                assertTrue(HEX.matcher(value).matches(), key + " resolves to a #RRGGBB colour, is: " + value);
                return value;
            }
        }
        return value;
    }

    private static void assertColours(String where, Map<String, String> declared, Map<String, String> expected) {
        for (Map.Entry<String, String> role : expected.entrySet()) {
            String property = "--color-" + role.getKey().replace('.', '-');
            String value = declared.get(property);
            assertNotNull(value, "tokens.css declares " + property + " in " + where);
            assertEquals(role.getValue().toLowerCase(Locale.ROOT), value.toLowerCase(Locale.ROOT), property + " in " + where);
        }
    }

    /** The text between the first '{' after {@code from} and its matching '}'. */
    private static String block(String text, int from) {
        int open = text.indexOf('{', from);
        int end = closing(text, open);
        assertTrue(open >= 0 && end > open, "a block opened after position " + from + " is closed");
        return text.substring(open + 1, end);
    }

    private static int closing(String text, int open) {
        if (open < 0) {
            return -1;
        }
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The custom properties declared in a text, first declaration wins. */
    private static Map<String, String> declarations(String text) {
        Map<String, String> declarations = new LinkedHashMap<>();
        Matcher declaration = Pattern.compile("(--[A-Za-z0-9-]+)\\s*:\\s*([^;{}]*?)\\s*;").matcher(text);
        while (declaration.find()) {
            declarations.putIfAbsent(declaration.group(1), declaration.group(2));
        }
        return declarations;
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(Path path) throws IOException {
        assertTrue(Files.isRegularFile(path), path + " exists");
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }
}
