package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.web.BrowserDriver.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.opentest4j.TestAbortedException;

/**
 * The page in a real headless browser: what a visitor reads in front of a line, in each display mode, with an error and
 * with hostile text. The lines are typed the way a visitor does, in the last line of the page, so the test also holds once
 * the page opens on the example notebook.
 *
 * <p>The expected strings come from the core ({@code ValueFormatterTest}, {@code WebExportsAcceptanceTest}): the page only
 * adds the labels {@code Erreur : }, {@code Erreur maximale : } and {@code Valeur exacte : }.
 */
@DisplayName("La page : résultats, modes, erreurs et texte hostile dans un vrai navigateur")
class WebPageDisplayAcceptanceTest {
    private static final Path SITE = Path.of("web", "build", "site");
    private static final Path SOURCES = Path.of("web", "site");
    private static final Path COMPILED = Path.of("web", "build", "generated", "teavm", "js", "calc.js");
    private static final Path BUILD_FILE = Path.of("web", "build.gradle.kts");
    private static final List<String> SITE_FILES = List.of("index.html", "style.css", "app.js", "calc.js");
    private static final long MAX_CALC_JS_BYTES = 2_097_152;

    private static final int DESKTOP = 1024;
    private static final int PHONE = 360;

    private static final String HOSTILE = "<img src=x onerror=alert(1)>";
    private static final String ROOT_RESULT = "≈ 1,41421356237…";
    private static final String ROOT_BOUND = "Erreur maximale : ± 2,1 × 10⁻²⁵";
    private static final String APPROXIMATE_BADGE = "≈ Approché";
    private static final String MISSING_OPERAND = "Erreur : Opérande manquant après « + ».";
    private static final Pattern ABSOLUTE_ADDRESS = Pattern.compile("^\\s*(?:[a-zA-Z][a-zA-Z0-9+.-]*:|/|\\\\)");
    private static final Pattern DARK_SCHEME = Pattern.compile("prefers-color-scheme\\s*:\\s*dark");
    private static final Pattern EXTERNAL_IN_CSS = Pattern.compile("@import|url\\(\\s*['\"]?\\s*(?:[a-zA-Z][a-zA-Z0-9+.-]*:|//)");
    private static final Pattern MARKUP_IN_SCRIPT = Pattern.compile("innerHTML|outerHTML|insertAdjacentHTML|document\\.write|\\beval\\s*\\(");
    private static final Pattern FORMATTING_IN_SCRIPT = Pattern.compile("toFixed|toPrecision|toExponential|toLocaleString|Intl\\.|parseFloat");

    // ---- The assembled site, without a browser ---------------------------------------------------------------------------

    @Test
    void theSiteHoldsThePageAndTheCompiledCore() {
        for (String file : SITE_FILES) {
            assertTrue(Files.isRegularFile(SITE.resolve(file)), "Manque dans le site : " + SITE.resolve(file));
        }
    }

    @Test
    void theCompiledCoreOfTheSiteIsAtMostTwoMebibytes() throws IOException {
        Path calcJs = SITE.resolve("calc.js");
        assertTrue(Files.isRegularFile(calcJs), "Manque dans le site : " + calcJs);
        long size = Files.size(calcJs);
        assertTrue(size > 0, "calc.js est vide");
        assertTrue(size <= MAX_CALC_JS_BYTES, "calc.js fait " + size + " octets, plus que " + MAX_CALC_JS_BYTES);
    }

    /** The site is rebuilt from its sources: an old folder that happens to be there would prove nothing. */
    @Test
    void theSiteIsACopyOfTheSourcesAndOfTheCompiledCore() throws IOException {
        for (String file : List.of("index.html", "style.css", "app.js")) {
            assertSameBytes(SOURCES.resolve(file), SITE.resolve(file));
        }
        assertSameBytes(COMPILED, SITE.resolve("calc.js"));
    }

    @Test
    void theBuildAssemblesTheSiteBeforeTheBrowserTests() throws IOException {
        String build = Files.readString(BUILD_FILE, UTF_8);
        assertTrue(build.contains("tasks.register<Sync>(\"site\")"), "web/build.gradle.kts ne déclare pas la tâche site");
        int webTest = build.indexOf("tasks.register<Test>(\"webTest\")");
        assertTrue(webTest >= 0, "web/build.gradle.kts ne déclare plus webTest");
        assertTrue(Pattern.compile("dependsOn\\([^)]*\\bsite\\b").matcher(build.substring(webTest)).find(), "webTest ne dépend pas de site");
    }

    @Test
    void theStyleSheetHasADarkModeAndNoExternalResource() throws IOException {
        Path css = SITE.resolve("style.css");
        assertTrue(Files.isRegularFile(css), "Manque dans le site : " + css);
        String content = Files.readString(css, UTF_8);
        assertTrue(DARK_SCHEME.matcher(content).find(), "style.css ne contient pas prefers-color-scheme: dark");
        Matcher external = EXTERNAL_IN_CSS.matcher(content);
        assertFalse(external.find(), () -> "Ressource externe dans style.css : " + external.group());
    }

    /** A comment holding one of these words is refused too: nothing in this script may look like it writes markup. */
    @Test
    void theScriptOfThePageNeverWritesMarkupNorFormatsAValue() throws IOException {
        Path script = SITE.resolve("app.js");
        assertTrue(Files.isRegularFile(script), "Manque dans le site : " + script);
        String content = Files.readString(script, UTF_8);
        Matcher markup = MARKUP_IN_SCRIPT.matcher(content);
        assertFalse(markup.find(), () -> "app.js écrit du balisage : " + markup.group());
        Matcher formatting = FORMATTING_IN_SCRIPT.matcher(content);
        assertFalse(formatting.find(), () -> "app.js met en forme une valeur : " + formatting.group());
    }

    // ---- Finding a browser, on a simulated environment --------------------------------------------------------------------

    @Test
    void noBrowserOnASimulatedEnvironmentGivesNothing() {
        assertEquals(Optional.empty(), BrowserDriver.find(Map.of(), file -> false));
        assertEquals(Optional.empty(), BrowserDriver.find(Map.of("PATH", "somewhere"), file -> false));
    }

    @Test
    void theBrowserOfTheVariableComesFirstWhenItsFileExists() {
        Path named = Path.of("portable", "browser.exe");
        Map<String, String> environment = Map.of(BrowserDriver.BROWSER_VARIABLE, named.toString());

        assertEquals(Optional.of(named), BrowserDriver.find(environment, named::equals));
        assertEquals(Optional.of(named), BrowserDriver.find(environment, file -> true));
    }

    @Test
    void aVariableNamingAMissingFileIsNotABrowser() {
        Map<String, String> environment = Map.of(BrowserDriver.BROWSER_VARIABLE, Path.of("gone", "browser.exe").toString());

        assertEquals(Optional.empty(), BrowserDriver.find(environment, file -> false));
    }

    @Test
    void theBrowsersOfThePathComeInTheOrderOfTheSpecification() {
        Path folder = Path.of("tools");
        Map<String, String> environment = Map.of("PATH", folder.toString());
        Path chrome = folder.resolve("chrome");
        Path googleChrome = folder.resolve("google-chrome");
        Path chromium = folder.resolve("chromium");

        assertEquals(Optional.of(chromium), BrowserDriver.find(environment, chromium::equals));
        assertEquals(Optional.of(googleChrome), BrowserDriver.find(environment, file -> file.equals(chromium) || file.equals(googleChrome)));
        assertEquals(Optional.of(chrome), BrowserDriver.find(environment, file -> file.equals(chromium) || file.equals(chrome)));
    }

    /** Under Windows the variable is often written Path, and System.getenv() as a map keeps that case. */
    @Test
    void thePathIsFoundWhateverTheCaseOfItsName() {
        Path chrome = Path.of("tools").resolve("chrome");

        assertEquals(Optional.of(chrome), BrowserDriver.find(Map.of("Path", "tools"), chrome::equals));
    }

    @Test
    void edgeAtItsFixedPlaceComesLast() {
        Path edge = Path.of("C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe");
        Path chrome = Path.of("tools").resolve("chrome");

        assertEquals(Optional.of(edge), BrowserDriver.find(Map.of(), edge::equals));
        assertEquals(Optional.of(chrome), BrowserDriver.find(Map.of("PATH", "tools"), file -> file.equals(edge) || file.equals(chrome)));
    }

    @Test
    void aMissingBrowserFailsWhenRequiredAndAbortsOtherwise() {
        assertThrows(AssertionError.class, () -> BrowserDriver.require(Optional.empty(), true));
        assertThrows(TestAbortedException.class, () -> BrowserDriver.require(Optional.empty(), false));
    }

    @Test
    void aPresentBrowserIsGivenBackWhateverIsRequired() {
        Path browser = Path.of("browser.exe");

        assertEquals(browser, BrowserDriver.require(Optional.of(browser), true));
        assertEquals(browser, BrowserDriver.require(Optional.of(browser), false));
    }

    // ---- What a visitor reads in front of a line ------------------------------------------------------------------------

    @Test
    void oneThirdTimesThreeIsExactlyOneAndTheRootShowsItsBound() throws Exception {
        Observation page = drive(DESKTOP, enter("(1/3)*3"), enter("√2"));

        Json one = page.typedLine(0);
        assertEquals("(1/3)*3", one.get("value").text());
        assertEquals("1", one.get("result").text());
        assertEquals("Exact", one.get("badge").text());
        assertEquals("", one.get("detail").text(), "Un résultat exact et complet n'a rien à préciser");
        assertTrue(one.get("shown").get("result").bool(), "Le résultat 1 n'est pas visible");
        assertTrue(one.get("shown").get("badge").bool(), "Le badge Exact n'est pas visible");

        Json root = page.typedLine(1);
        assertEquals("√2", root.get("value").text());
        assertEquals(ROOT_RESULT, root.get("result").text());
        assertEquals(APPROXIMATE_BADGE, root.get("badge").text());
        assertEquals(ROOT_BOUND, root.get("detail").text());
        assertTrue(root.get("shown").get("result").bool(), "Le résultat de √2 n'est pas visible");
        assertTrue(root.get("shown").get("badge").bool(), "Le badge de √2 n'est pas visible");
        assertTrue(root.get("shown").get("detail").bool(), "La borne doit se lire sans survol, pas dans une infobulle");
        assertEquals("", root.get("error").text());
        assertEquals(List.of(), root.get("marks").items());
    }

    @Test
    void aTruncatedExactResultShowsItsExactValue() throws Exception {
        Json third = drive(DESKTOP, enter("1/3")).typedLine(0);

        assertEquals("0,333333333333…", third.get("result").text());
        assertEquals("Exact", third.get("badge").text());
        assertEquals("Valeur exacte : 1/3", third.get("detail").text());
        assertTrue(third.get("shown").get("detail").bool(), "La valeur exacte doit se lire sans survol");
    }

    /** Enter gives the result at that moment, and the whole notebook follows: nothing of the old result stays. */
    @Test
    void editingALineUpdatesItAndTheLinesThatUseIt() throws Exception {
        Observation page = drive(DESKTOP, enter("x = 10"), enter("x + 1"), enter("2 +"),
                snapshot("avant"), edit(0, "x = 20"), snapshot("après"), edit(2, "2 + 1"));

        assertEquals("11", page.line("avant", page.typedNumber(1)).get("result").text());
        // Read before the next edit: that one may draw every line again, and would hide a line left behind by this one.
        assertEquals("x = 20", page.line("après", page.typedNumber(0)).get("value").text());
        assertEquals("21", page.line("après", page.typedNumber(1)).get("result").text());
        Json mended = page.typedLine(2);
        assertEquals("3", mended.get("result").text());
        assertEquals("Exact", mended.get("badge").text());
        assertEquals("", mended.get("error").text());
        assertEquals(List.of(), mended.get("marks").items());
    }

    // ---- An error ---------------------------------------------------------------------------------------------------------

    @Test
    void aLineInErrorShowsTheMessageOfTheCoreAndMarksItsFaultyPart() throws Exception {
        Json line = drive(DESKTOP, enter("2 +")).typedLine(0);

        assertEquals("2 +", line.get("value").text());
        assertEquals(MISSING_OPERAND, line.get("error").text());
        assertTrue(line.get("shown").get("error").bool(), "Le message d'erreur n'est pas visible");
        assertEquals("", line.get("result").text(), "Une erreur n'a pas de résultat");
        assertNoExactnessBadge(line);
        assertMarked(line, "2 ", "+", "");
    }

    /** The mark follows errorStart and errorEnd: in the middle of the line, and in UTF-16 units past a surrogate pair. */
    @Test
    void theMarkFollowsTheSpanOfTheErrorInUtf16Units() throws Exception {
        String face = new String(Character.toChars(0x1F600));
        Observation page = drive(DESKTOP, enter("1 + * 3"), enter(face + " + 1"), enter("2 + " + HOSTILE));

        Json middle = page.typedLine(0);
        assertEquals(MISSING_OPERAND, middle.get("error").text());
        assertMarked(middle, "1 ", "+", " * 3");

        Json surrogates = page.typedLine(1);
        assertEquals("Erreur : Caractère inattendu « " + face + " ».", surrogates.get("error").text());
        assertMarked(surrogates, "", face, " + 1");

        Json hostile = page.typedLine(2);
        assertEquals("Erreur : Caractère inattendu « < ».", hostile.get("error").text());
        assertMarked(hostile, "2 + ", "<", "img src=x onerror=alert(1)>");
    }

    // ---- The display modes -------------------------------------------------------------------------------------------------

    @Test
    void theSelectorOffersTheFourModesUnderItsLabel() throws Exception {
        Json modes = drive(DESKTOP, enter("1")).root().get("modes");

        assertFalse(modes.isNull(), "La page n'a pas de #display-mode");
        assertEquals("SELECT", modes.get("tag").text());
        assertEquals("Affichage", modes.get("label").text());
        assertEquals("Auto", modes.get("selected").text());
        assertEquals(List.of("Auto", "Décimal", "Fraction", "Scientifique"), modes.get("options").items().stream().map(Json::text).toList());
    }

    @Test
    void eachModeChangesTheResultAndKeepsTheLines() throws Exception {
        Observation page = drive(DESKTOP, enter("1/3"), enter("√2"),
                mode("Fraction"), snapshot("fraction"),
                mode("Scientifique"), snapshot("scientifique"),
                mode("Décimal"), snapshot("décimal"),
                mode("Auto"));
        int third = page.typedNumber(0);
        int root = page.typedNumber(1);

        assertEquals("1/3", page.line("fraction", third).get("result").text());
        assertEquals("1/3", page.line("fraction", third).get("value").text());

        Json scientificRoot = page.line("scientifique", root);
        assertEquals("≈ 1,41421356237… × 10⁰", scientificRoot.get("result").text());
        assertEquals(APPROXIMATE_BADGE, scientificRoot.get("badge").text());
        assertEquals(ROOT_BOUND, scientificRoot.get("detail").text());
        assertEquals("√2", scientificRoot.get("value").text());

        assertEquals("0,333333333333…", page.line("décimal", third).get("result").text());

        assertEquals(ROOT_RESULT, page.typedLine(1).get("result").text());
        assertEquals("0,333333333333…", page.typedLine(0).get("result").text());
        assertEquals("Auto", page.root().get("modes").get("selected").text());
    }

    // ---- Hostile text ------------------------------------------------------------------------------------------------------

    @Test
    void aHostileLineStaysTextAndRunsNothing() throws Exception {
        String comment = "# " + HOSTILE;
        Observation page = drive(DESKTOP, enter(HOSTILE), enter(comment), enter("2 + " + HOSTILE),
                mode("Fraction"), mode("Auto"));

        Json hostile = page.typedLine(0);
        assertEquals(HOSTILE, hostile.get("value").text(), "Le texte de la ligne a changé");
        assertEquals("Erreur : Caractère inattendu « < ».", hostile.get("error").text());
        assertMarked(hostile, "", "<", "img src=x onerror=alert(1)>");
        assertEquals(comment, page.typedLine(1).get("value").text());
        assertEquals("2 + " + HOSTILE, page.typedLine(2).get("value").text());

        Json root = page.root();
        assertEquals(0.0, root.get("imgCount").number(), "Le DOM contient un élément img");
        assertEquals(List.of(), root.get("addedElements").items(), "Le texte d'une ligne a créé des éléments");
        assertEquals(0.0, root.get("alerts").number(), "Le texte d'une ligne a exécuté alert");
        assertEquals(List.of(), root.get("pageErrors").items());
        List<String> resources = page.resources();
        assertFalse(resources.contains("/x"), "L'image du texte hostile a été demandée au serveur : " + resources);
    }

    // ---- index.html -----------------------------------------------------------------------------------------------------------

    @Test
    void thePageIsFrenchAndDeclaresAViewport() throws Exception {
        Json root = drive(DESKTOP, enter("1")).root();
        Json source = root.get("source");

        assertEquals("fr", source.get("lang").text());
        assertEquals("fr", root.get("lang").text());
        assertFalse(source.get("viewport").isNull(), "index.html n'a pas de balise meta viewport");
        assertTrue(source.get("viewport").text().contains("width=device-width"), source.get("viewport").text());
    }

    @Test
    void thePageLoadsOnlyItsOwnClassicScriptsAndItsOwnStyleSheet() throws Exception {
        Observation page = drive(DESKTOP, enter("1"));
        Json source = page.root().get("source");

        List<String> scripts = new ArrayList<>();
        for (Json script : source.get("scripts").items()) {
            assertFalse(script.get("src").isNull(), "index.html a un script en ligne");
            scripts.add(script.get("src").text());
            assertAddressIsRelative(script.get("src").text());
            assertTrue(script.get("type").isNull() || !script.get("type").text().trim().toLowerCase(Locale.ROOT).equals("module"),
                    "Script de type module : " + script.get("src").text());
        }
        assertTrue(scripts.contains("calc.js") || scripts.contains("./calc.js"), "index.html ne charge pas calc.js : " + scripts);
        assertTrue(scripts.contains("app.js") || scripts.contains("./app.js"), "index.html ne charge pas app.js : " + scripts);

        List<String> links = new ArrayList<>();
        for (Json link : source.get("links").items()) {
            assertFalse(link.get("href").isNull(), "Élément link sans adresse");
            links.add(link.get("href").text());
            assertAddressIsRelative(link.get("href").text());
        }
        assertTrue(links.contains("style.css") || links.contains("./style.css"), "index.html ne charge pas style.css : " + links);
        for (Json image : source.get("images").items()) {
            assertFalse(image.isNull(), "Élément img sans adresse");
            assertAddressIsRelative(image.text());
        }
        assertEquals(0.0, source.get("styles").number(), "index.html a une feuille de style en ligne");

        List<String> resources = page.resources();
        for (String resource : resources) {
            assertTrue(resource.startsWith("/"), "Ressource hors du site chargée par la page : " + resource);
        }
        for (String file : List.of("/calc.js", "/app.js", "/style.css")) {
            assertTrue(resources.contains(file), "La page n'a pas chargé " + file + " : " + resources);
        }
        assertEquals(List.of(), page.root().get("pageErrors").items());
    }

    @Test
    void nothingOverflowsAPhoneScreenEvenWithLongLines() throws Exception {
        String longDigits = "1234567890".repeat(8);
        String longName = "a".repeat(300);
        Observation page = drive(PHONE, enter("(1/3)*3"), enter("√2"), enter("1/3"), enter("2 +"), enter(longDigits),
                enter("1/" + longDigits), enter("2 + " + longName), mode("Fraction"), mode("Auto"), mode("Fraction"));

        Json layout = page.root().get("layout");
        assertEquals(PHONE, layout.get("innerWidth").number(), "L'iframe n'a pas la largeur d'un téléphone");
        assertTrue(layout.get("scrollWidth").number() <= layout.get("clientWidth").number(),
                "Défilement horizontal à 360 px : scrollWidth " + layout.get("scrollWidth").number()
                        + ", clientWidth " + layout.get("clientWidth").number());
        for (int typed = 0; typed < 7; typed++) {
            Json line = page.typedLine(typed);
            for (String part : List.of("result", "badge", "detail", "error")) {
                assertTrue(line.get("inside").get(part).bool(), part + " sort de l'écran à la ligne " + line.get("value").text());
            }
        }
    }

    /** The desktop keeps the bound in a tooltip: here it is visible, on a phone too. */
    @Test
    void theBoundAndTheResultStayVisibleOnAPhone() throws Exception {
        Json root = drive(PHONE, enter("√2")).typedLine(0);

        assertEquals(ROOT_RESULT, root.get("result").text());
        assertEquals(ROOT_BOUND, root.get("detail").text());
        assertEquals(APPROXIMATE_BADGE, root.get("badge").text());
        for (String part : List.of("result", "badge", "detail")) {
            assertTrue(root.get("shown").get(part).bool(), part + " n'est pas visible à 360 px");
            assertTrue(root.get("inside").get(part).bool(), part + " sort de l'écran à 360 px");
        }
    }

    // ---- The footer ---------------------------------------------------------------------------------------------------------

    @Test
    void theFooterGivesTheSourceTheReportAndSaysTheNotebookStaysHere() throws Exception {
        Json footer = drive(DESKTOP, enter("1")).root().get("footer");

        assertFalse(footer.isNull(), "La page n'a pas de footer");
        Map<String, String> links = new HashMap<>();
        for (Json link : footer.get("links").items()) {
            links.put(link.get("text").text(), link.get("href").isNull() ? null : link.get("href").text());
        }
        assertEquals("https://github.com/TGDev2/Calculatrice", links.get("Code source"), "Liens du pied de page : " + links);
        assertEquals("rapport-conformite.html", links.get("Rapport de conformité"), "Liens du pied de page : " + links);
        String text = footer.get("text").text().toLowerCase(Locale.ROOT);
        assertTrue(text.contains("carnet") && text.contains("reste dans ce navigateur"), "Pied de page : " + footer.get("text").text());
    }

    // ---- The driver ----------------------------------------------------------------------------------------------------------

    private record Observation(Json root) {
        private Json lastReading() {
            return root.get("snapshots").get("final");
        }

        Json typedLine(int typed) {
            return lineIn(lastReading(), typedNumber(typed));
        }

        int typedNumber(int typed) {
            return (int) root.get("typed").items().get(typed).get("line").number();
        }

        Json line(String snapshot, int number) {
            return lineIn(root.get("snapshots").get(snapshot), number);
        }

        List<String> resources() {
            return root.get("resources").items().stream().map(Json::text).toList();
        }

        private static Json lineIn(Json lines, int number) {
            for (Json line : lines.items()) {
                if ((int) line.get("line").number() == number) {
                    return line;
                }
            }
            return fail("Pas de ligne " + number + " dans " + lines.value());
        }
    }

    private static String enter(String text) {
        return "{\"enter\":" + quoted(text) + "}";
    }

    private static String edit(int typed, String text) {
        return "{\"edit\":" + quoted(text) + ",\"typed\":" + typed + "}";
    }

    private static String mode(String label) {
        return "{\"mode\":" + quoted(label) + "}";
    }

    private static String snapshot(String name) {
        return "{\"snapshot\":" + quoted(name) + "}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Observation drive(int width, String... steps) throws IOException, InterruptedException {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (String step : steps) {
            joined.add(step);
        }
        String scenario = "{\"width\":" + width + ",\"steps\":" + joined + "}";
        Json root = BrowserDriver.parseJson(BrowserDriver.run("page-driver.html", "scenario=" + URLEncoder.encode(scenario, UTF_8)));
        if (!root.get("error").isNull()) {
            fail("La page pilote s'est arrêtée : " + root.get("error").text());
        }
        return new Observation(root);
    }

    // ---- Checks -----------------------------------------------------------------------------------------------------------

    private static void assertMarked(Json line, String before, String marked, String after) {
        List<Json> marks = line.get("marks").items();
        assertEquals(1, marks.size(), "Il faut un seul mark, dans la ligne " + line.get("value").text());
        Json mark = marks.get(0);
        assertEquals(marked, mark.get("text").text());
        assertTrue(mark.get("shown").bool(), "Le mark n'est pas visible");
        assertTrue(mark.get("before").text().endsWith(before), "Avant le mark : " + mark.get("before").text());
        assertTrue(mark.get("after").text().startsWith(after), "Après le mark : " + mark.get("after").text());
    }

    private static void assertNoExactnessBadge(Json line) {
        String badge = line.get("badge").text();
        assertFalse(badge.equals("Exact") || badge.equals(APPROXIMATE_BADGE), "Une erreur n'a pas ce badge : " + badge);
    }

    private static void assertAddressIsRelative(String address) {
        assertFalse(ABSOLUTE_ADDRESS.matcher(address).find(), "Adresse absolue dans index.html : " + address);
    }

    private static void assertSameBytes(Path expected, Path actual) throws IOException {
        assertTrue(Files.isRegularFile(expected), "Manque : " + expected);
        assertTrue(Files.isRegularFile(actual), "Manque : " + actual);
        assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(actual), actual + " diffère de " + expected);
    }
}
