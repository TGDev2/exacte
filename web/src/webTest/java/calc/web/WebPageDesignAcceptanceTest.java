package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.web.BrowserDriver.Json;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The page of the site wears the design of the desktop (SPEC §4.4, docs/design/DESIGN.md): the design tokens loaded before
 * the style sheet, a margin with the number, the sign of exactness and the source mark, the title line, the name of an
 * assignment, the result aligned on the right, the error under the line, the detail of the current line, and the theme of
 * {@code prefers-color-scheme}, emulated by the browser through {@link BrowserDriver} and never by {@code data-theme}.
 *
 * <p>The notebook is « Devis cuisine » followed by a blank line, a root and an error, kept in the localStorage before the
 * page opens (page-design-driver.html). The expected strings are the snapshot the compiled core of the page gives for the
 * same lines.
 */
@DisplayName("La page au même design que le bureau : jetons, marge, signes, détail et thème")
class WebPageDesignAcceptanceTest {
    private static final Path SITE = Path.of("web", "build", "site");
    private static final Path SOURCES = Path.of("web", "site");
    private static final String DRIVER = "page-design-driver.html";

    private static final int DESKTOP = 1024;
    private static final int PHONE = 360;
    private static final int MINIMUM_TOUCH_SIZE = 44;
    private static final double TOLERANCE = 0.5;

    private static final List<String> DEVIS_CUISINE = List.of("# Devis cuisine", "plan = 1\u00a0250,00", "meubles = 3\u00a0480",
            "pose = 35 * 12,5", "total = plan + meubles + pose", "taux = 0,2", "total * (1 + taux)", "ans / 12");
    /** Lines 1 to 8 are « Devis cuisine », 9 is blank, 10 a root, 11 an error; the page adds the blank line 12. */
    private static final List<String> LINES = concat(DEVIS_CUISINE, List.of("", "√2", "2 +"));
    private static final int ROOT_LINE = 10;
    private static final int ERROR_LINE = 11;
    private static final int DRAFT_LINE = 12;
    private static final List<String> DETAIL_OF_TOTAL = List.of("= 1250 + 3480 + 437,5", "= 5167,5", "Sources : lignes 2, 3 et 4",
            "Utilisée par 1 ligne");
    private static final String MISSING_OPERAND = "Erreur : Opérande manquant après « + ».";
    private static final String SOURCE_MARK = "◆";

    private static final Pattern LINK = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLESHEET = Pattern.compile("\\brel\\s*=\\s*[\"']?stylesheet\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("\\bhref\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern DARK_SCHEME = Pattern.compile("prefers-color-scheme\\s*:\\s*dark");
    private static final Pattern INLINE_STYLE = Pattern.compile("<style\\b|\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODULE_SCRIPT = Pattern.compile("<script\\b[^>]*\\btype\\s*=\\s*[\"']?module", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKUP_IN_SCRIPT = Pattern.compile("innerHTML|outerHTML|insertAdjacentHTML|document\\.write|\\beval\\s*\\(");
    private static final Pattern FORMATTING_IN_SCRIPT = Pattern.compile("toFixed|toPrecision|toExponential|toLocaleString|Intl\\.|parseFloat");

    private static final Pattern COMMENT = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern DECLARATIONS = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-fA-F]{3,8}\\b");
    private static final Pattern COLOUR_FUNCTION = Pattern.compile("(?i)(?<![\\w-])(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color)\\s*\\(");
    private static final Pattern WORD = Pattern.compile("(?<![\\w-])[a-zA-Z]+(?![\\w-])");
    private static final Pattern VARIABLE = Pattern.compile("var\\(\\s*(--[\\w-]+)");
    private static final Pattern COLOUR_PROPERTY = Pattern.compile(
            "color|background-color|caret-color|accent-color|outline-color|text-decoration-color|column-rule-color|fill|stroke"
                    + "|border(-[a-z]+)*-color");
    private static final Pattern PROPERTY_HOLDING_COLOURS = Pattern.compile(
            "--.*|.*(color|background|border|outline|shadow|fill|stroke|column-rule|text-decoration).*");
    private static final Set<String> NAMED_COLOURS = new HashSet<>(Arrays.asList((""
            + "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood "
            + "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray "
            + "darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon darkseagreen "
            + "darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue "
            + "firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green greenyellow grey honeydew "
            + "hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan "
            + "lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray "
            + "lightslategrey lightsteelblue lightyellow lime limegreen linen magenta maroon mediumaquamarine mediumblue "
            + "mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred "
            + "midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid "
            + "palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum powderblue purple "
            + "rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue "
            + "slategray slategrey snow springgreen steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke "
            + "yellow yellowgreen canvas canvastext linktext visitedtext activetext buttonface buttontext buttonborder field "
            + "fieldtext highlight highlighttext selecteditem selecteditemtext mark marktext graytext accentcolor accentcolortext")
            .split(" ")));

    private static final Map<String, Json> OBSERVATIONS = new HashMap<>();

    // ---- The files of the page, without a browser -------------------------------------------------------------------------

    @Test
    void indexLoadsTheDesignTokensThenTheStyleSheet() throws IOException {
        String index = Files.readString(SOURCES.resolve("index.html"), UTF_8);
        List<String> stylesheets = new ArrayList<>();
        Matcher link = LINK.matcher(index);
        while (link.find()) {
            if (STYLESHEET.matcher(link.group()).find()) {
                Matcher href = HREF.matcher(link.group());
                stylesheets.add(href.find() ? href.group(1) : "");
            }
        }
        assertEquals(List.of("tokens.css", "style.css"), stylesheets,
                "index.html doit charger tokens.css puis style.css, par deux <link rel=\"stylesheet\"> dans cet ordre");
    }

    @Test
    void theTokensOfTheSiteAreACopyOfTheGeneratedTokens() throws IOException {
        Path copy = SITE.resolve("tokens.css");
        assertTrue(Files.isRegularFile(copy), "Manque dans le site : " + copy);
        assertArrayEquals(Files.readAllBytes(SOURCES.resolve("tokens.css")), Files.readAllBytes(copy),
                "web/build/site/tokens.css n'est pas une copie octet pour octet de web/site/tokens.css");
    }

    /** Hexadecimal codes, colour functions and named colours are refused; a colour property reads only var(--color-…). */
    @Test
    void theStyleSheetUsesNoLiteralColour() throws IOException {
        String css = COMMENT.matcher(Files.readString(SOURCES.resolve("style.css"), UTF_8)).replaceAll("");
        List<String> literals = new ArrayList<>();
        Matcher block = DECLARATIONS.matcher(css);
        while (block.find()) {
            for (String declaration : block.group(1).split(";")) {
                int colon = declaration.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String property = declaration.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = QUOTED.matcher(declaration.substring(colon + 1)).replaceAll("\"\"");
                String shown = property + ":" + value.trim();
                if (HEX_COLOUR.matcher(value).find() || COLOUR_FUNCTION.matcher(value).find()) {
                    literals.add(shown);
                    continue;
                }
                if (PROPERTY_HOLDING_COLOURS.matcher(property).matches()) {
                    Matcher word = WORD.matcher(value);
                    while (word.find()) {
                        if (NAMED_COLOURS.contains(word.group().toLowerCase(Locale.ROOT))) {
                            literals.add(shown);
                        }
                    }
                }
                if (COLOUR_PROPERTY.matcher(property).matches()) {
                    Matcher variable = VARIABLE.matcher(value);
                    while (variable.find()) {
                        if (!variable.group(1).startsWith("--color-")) {
                            literals.add(shown);
                        }
                    }
                }
            }
        }
        assertEquals(List.of(), literals, "style.css doit lire ses couleurs dans les jetons, par var(--color-…) seulement");
    }

    @Test
    void theTokensOrTheStyleSheetHaveADarkMode() throws IOException {
        String tokens = Files.readString(SOURCES.resolve("tokens.css"), UTF_8);
        String style = Files.readString(SOURCES.resolve("style.css"), UTF_8);
        assertTrue(DARK_SCHEME.matcher(tokens).find() || DARK_SCHEME.matcher(style).find(),
                "Ni tokens.css ni style.css ne contient prefers-color-scheme: dark");
    }

    @Test
    void indexHasNoInlineStyleAndNoModule() throws IOException {
        String index = Files.readString(SOURCES.resolve("index.html"), UTF_8);
        Matcher inline = INLINE_STYLE.matcher(index);
        assertFalse(inline.find(), () -> "Style dans index.html : " + inline.group());
        assertFalse(MODULE_SCRIPT.matcher(index).find(), "index.html charge un module");
    }

    /** A comment holding one of these words is refused too: nothing in this script may look like it writes markup. */
    @Test
    void theScriptOfThePageStillNeverWritesMarkupNorFormatsAValue() throws IOException {
        String content = Files.readString(SOURCES.resolve("app.js"), UTF_8);
        Matcher markup = MARKUP_IN_SCRIPT.matcher(content);
        assertFalse(markup.find(), () -> "app.js écrit du balisage : " + markup.group());
        Matcher formatting = FORMATTING_IN_SCRIPT.matcher(content);
        assertFalse(formatting.find(), () -> "app.js met en forme une valeur : " + formatting.group());
    }

    // ---- The colour scheme of BrowserDriver --------------------------------------------------------------------------------

    @Test
    void theColourSchemeOfTheDriverIsLightOrDark() {
        Class<?> type = colourSchemeType();
        assertTrue(type.isEnum(), "BrowserDriver.ColorScheme doit être une énumération");
        List<String> names = Arrays.stream(type.getEnumConstants()).map(constant -> ((Enum<?>) constant).name()).toList();
        assertEquals(List.of("LIGHT", "DARK"), names);
    }

    @Test
    void theDriverKeepsItsRunAndGainsARunWithAColourScheme() throws NoSuchMethodException {
        Method plain = BrowserDriver.class.getMethod("run", String.class, String.class);
        assertTrue(Modifier.isStatic(plain.getModifiers()) && Modifier.isPublic(plain.getModifiers()), "run(page, requête) a changé");
        assertEquals(String.class, plain.getReturnType(), "run(page, requête) ne rend plus le constat");
        assertEquals(Set.of(IOException.class, InterruptedException.class), Set.of(plain.getExceptionTypes()),
                "run(page, requête) a changé d'exceptions");

        Method withScheme = runWithScheme();
        assertTrue(Modifier.isStatic(withScheme.getModifiers()) && Modifier.isPublic(withScheme.getModifiers()),
                "run(page, requête, schéma) doit être public et statique");
        assertEquals(String.class, withScheme.getReturnType(), "run(page, requête, schéma) doit rendre le constat");
    }

    /** This machine prefers the dark scheme: a light page proves the option given to the browser, not the system. */
    @Test
    void withTheLightSchemeThePageDoesNotPreferDark() throws Exception {
        Json scheme = light().get("scheme");
        assertFalse(scheme.get("pagePrefersDark").bool(), "LIGHT : matchMedia(\"(prefers-color-scheme: dark)\") est vrai dans la page");
        assertFalse(scheme.get("driverPrefersDark").bool(), "LIGHT : matchMedia(\"(prefers-color-scheme: dark)\") est vrai");
    }

    @Test
    void withTheDarkSchemeThePagePrefersDark() throws Exception {
        Json scheme = dark().get("scheme");
        assertTrue(scheme.get("pagePrefersDark").bool(), "DARK : matchMedia(\"(prefers-color-scheme: dark)\") est faux dans la page");
        assertTrue(scheme.get("driverPrefersDark").bool(), "DARK : matchMedia(\"(prefers-color-scheme: dark)\") est faux");
    }

    // ---- The theme -----------------------------------------------------------------------------------------------------------

    @Test
    void theBackgroundOfThePageFollowsTheColourSchemeWithoutDataTheme() throws Exception {
        String tokens = Files.readString(SOURCES.resolve("tokens.css"), UTF_8);
        String lightBase = rgb(surfaceBase(tokens, 0));
        String darkBase = rgb(surfaceBase(tokens, tokens.indexOf("prefers-color-scheme: dark")));
        assertNotEquals(lightBase, darkBase, "tokens.css donne le même --color-surface-base en clair et en sombre");

        Json light = light().get("scheme");
        Json dark = dark().get("scheme");
        for (Json scheme : List.of(light, dark)) {
            assertEquals(0, (int) scheme.get("themedInPage").number(), "La page pose data-theme");
            assertEquals(0, (int) scheme.get("themedInDriver").number(), "La page pilote pose data-theme");
        }
        assertEquals(lightBase, background(light), "LIGHT : le fond de la page n'est pas --color-surface-base claire");
        assertEquals(darkBase, background(dark), "DARK : le fond de la page n'est pas --color-surface-base sombre");
        assertNotEquals(background(light), background(dark), "Le fond de la page ne change pas avec le schéma de couleurs");
    }

    // ---- The lines -----------------------------------------------------------------------------------------------------------

    @Test
    void theNotebookOfTheTestHoldsEveryStatus() throws Exception {
        Set<String> statuses = new HashSet<>();
        for (Json line : expectedLines()) {
            statuses.add(line.get("status").text());
        }
        assertEquals(Set.of("COMMENT", "EXACT", "EMPTY", "APPROXIMATE", "ERROR"), statuses);
        assertEquals(LINES.size(), expectedLines().size());
    }

    @Test
    void everyLineHasAMarginWithItsNumberItsSignAndItsSourceMark() throws Exception {
        List<Json> rows = rows(light(), "final");
        assertEquals(DRAFT_LINE, rows.size(), "La page doit montrer les 11 lignes du carnet et la ligne vierge qui le suit");
        for (Json row : rows) {
            int number = (int) row.get("line").number();
            String where = "Ligne " + number + " : ";
            assertEquals(String.valueOf(number), row.get("number").isNull() ? null : row.get("number").text(), where + ".number");
            assertFalse(row.get("sign").isNull(), where + "pas de .sign dans la marge");
            assertFalse(row.get("sourceMark").isNull(), where + "pas de .source-mark dans la marge");
            double inputLeft = row.get("boxes").get("input").get("left").number();
            for (String part : List.of("number", "sign", "sourceMark")) {
                assertTrue(row.get("boxes").get(part).get("right").number() <= inputLeft + TOLERANCE,
                        where + part + " n'est pas dans la marge, à gauche de l'expression");
            }
        }
    }

    @Test
    void theSignOfTheMarginSaysTheStatus() throws Exception {
        Map<Integer, String> statusOf = statuses();
        for (String snapshot : List.of("final", "l5", "l1")) {
            for (Json row : rows(light(), snapshot)) {
                int number = (int) row.get("line").number();
                String expected = switch (statusOf.getOrDefault(number, "EMPTY")) {
                    case "EXACT" -> "=";
                    case "APPROXIMATE" -> "≈";
                    case "ERROR" -> "!";
                    default -> "";
                };
                assertEquals(expected, row.get("sign").isNull() ? null : row.get("sign").text(), snapshot + ", ligne " + number + " (" + statusOf.get(number) + ") : .sign");
            }
        }
    }

    @Test
    void noLineShowsABadgeAnyMore() throws Exception {
        for (Json observation : List.of(light(), dark(), phone())) {
            for (Json snapshot : snapshots(observation)) {
                for (Json row : snapshot.items()) {
                    String where = "Ligne " + (int) row.get("line").number() + " : ";
                    assertEquals(0, (int) row.get("badges").number(), where + "un .badge est encore là");
                    assertEquals(List.of(), texts(row.get("badgeTexts")), where + "le badge « Exact » ou « ≈ Approché » est encore là");
                }
            }
        }
    }

    @Test
    void aLineStartingWithAHashIsATitleThatKeepsItsRawText() throws Exception {
        Json observation = light();
        for (Json row : rows(observation, "final")) {
            int number = (int) row.get("line").number();
            boolean title = texts(row.get("classes")).contains("titre");
            assertEquals(number == 1, title, "Ligne " + number + " : classe titre");
        }
        Json title = row(observation, "final", 1);
        assertEquals("# Devis cuisine", title.get("value").text(), "Le champ de la ligne titre garde son texte brut, # compris");
        assertEquals(family(observation.get("fonts").get("ui").text()), family(title.get("style").get("inputFamily").text()),
                "La ligne titre n'est pas écrite en --font-ui");
    }

    @Test
    void anAssignmentShowsItsNameAndEveryResultIsTheTextOfTheSnapshot() throws Exception {
        Map<Integer, Json> expected = expectedByNumber();
        for (Json row : rows(light(), "final")) {
            int number = (int) row.get("line").number();
            Json line = expected.get(number);
            String where = "Ligne " + number + " : ";
            String name = line == null || line.get("name").isNull() ? null : line.get("name").text();
            if (name == null) {
                assertTrue(row.get("name").isNull() || row.get("name").text().isEmpty(), where + "un .name sans affectation");
            } else {
                assertEquals(name, row.get("name").isNull() ? null : row.get("name").text(), where + ".name");
                assertTrue(row.get("shown").get("name").bool(), where + ".name n'est pas visible");
            }
            String result = line == null || line.get("result").isNull() ? null : line.get("result").text();
            if (result == null) {
                assertTrue(row.get("result").isNull() || row.get("result").text().isEmpty(), where + "un .result sans résultat");
            } else {
                assertEquals(result, row.get("result").isNull() ? null : row.get("result").text(), where + ".result");
            }
        }
        assertEquals("plan", row(light(), "final", 2).get("name").text());
        assertEquals("total", row(light(), "final", 5).get("name").text());
    }

    @Test
    void theResultsAreAlignedOnTheRightInTheMonospaceFaceWithTabularFigures() throws Exception {
        Json observation = light();
        String mono = family(observation.get("fonts").get("mono").text());
        List<Double> rights = new ArrayList<>();
        for (Json row : rows(observation, "final")) {
            if (row.get("result").isNull() || row.get("result").text().isEmpty()) {
                continue;
            }
            String where = "Ligne " + (int) row.get("line").number() + " : ";
            Json boxes = row.get("boxes");
            assertTrue(boxes.get("result").get("left").number() >= boxes.get("input").get("right").number() - TOLERANCE,
                    where + "le résultat n'est pas à droite de l'expression");
            rights.add(boxes.get("result").get("right").number());
            assertTrue(row.get("style").get("resultNumeric").text().contains("tabular-nums"), where + "pas de tabular-nums");
            assertEquals(mono, family(row.get("style").get("resultFamily").text()), where + "le résultat n'est pas en --font-mono");
        }
        assertTrue(rights.size() >= 7, "Trop peu de résultats : " + rights);
        double first = rights.get(0);
        for (double right : rights) {
            assertEquals(first, right, 1.0, "Les résultats ne sont pas alignés à droite : " + rights);
        }
    }

    @Test
    void aLineInErrorMarksItsFaultyPartOnceAndShowsItsMessageUnderTheLine() throws Exception {
        Json expected = expectedByNumber().get(ERROR_LINE);
        String text = expected.get("text").text();
        String faulty = text.substring((int) expected.get("errorStart").number(), (int) expected.get("errorEnd").number());
        for (String snapshot : List.of("final", "l5")) {
            Json row = row(light(), snapshot, ERROR_LINE);
            assertEquals(List.of(faulty), texts(row.get("marks")), snapshot + " : la ligne en erreur doit marquer une fois « " + faulty + " »");
            assertEquals("Erreur : " + expected.get("error").text(), row.get("error").text(), snapshot + " : .error");
            assertEquals(MISSING_OPERAND, row.get("error").text());
            assertTrue(row.get("shown").get("error").bool(), snapshot + " : .error n'est pas visible");
            assertTrue(row.get("boxes").get("error").get("top").number() >= row.get("boxes").get("input").get("bottom").number() - TOLERANCE,
                    snapshot + " : le message d'erreur n'est pas sous la ligne");
        }
        for (Json row : rows(light(), "final")) {
            if ((int) row.get("line").number() != ERROR_LINE) {
                assertEquals(List.of(), texts(row.get("marks")), "Ligne " + (int) row.get("line").number() + " : une marque sans erreur");
            }
        }
    }

    @Test
    void theCurrentLineShowsItsDetailAndTheSourceMarksOfDevisCuisine() throws Exception {
        Json observation = light();
        List<String> expected = texts(expectedByNumber().get(5).get("detail"));
        assertEquals(DETAIL_OF_TOTAL, expected, "Le snapshot du cœur a changé");
        Json row = row(observation, "l5", 5);
        Json detail = detailWith(row, expected);
        assertTrue(texts(detail.get("paragraphs")).contains("Utilisée par 1 ligne"));
        assertTrue(detail.get("shown").bool(), "Le détail de la ligne 5 n'est pas visible");
        assertTrue(detail.get("box").get("top").number() >= row.get("boxes").get("input").get("bottom").number() - TOLERANCE,
                "Le détail n'est pas sous la ligne courante");
        assertSourceMarks(observation, "l5", List.of(2, 3, 4));
    }

    /** From the first line, the last written line and the blank last line: the detail and the marks follow the current line. */
    @Test
    void theDetailAndTheSourceMarksFollowTheCurrentLine() throws Exception {
        Json observation = light();
        Map<Integer, Json> expected = expectedByNumber();
        for (int current : List.of(1, 5, 8, DRAFT_LINE)) {
            String snapshot = "l" + current;
            Json line = expected.get(current);
            List<String> sentences = line == null ? List.of() : texts(line.get("detail"));
            Json row = row(observation, snapshot, current);
            if (sentences.isEmpty()) {
                assertEquals(List.of(), paragraphsOf(row), snapshot + " : la ligne courante n'a pas de détail");
            } else {
                detailWith(row, sentences);
            }
            assertSourceMarks(observation, snapshot, line == null ? List.of() : numbers(line.get("sources")));
            for (Json other : rows(observation, snapshot)) {
                int number = (int) other.get("line").number();
                if (number != current && number >= 2 && number <= DEVIS_CUISINE.size()) {
                    assertEquals(List.of(), paragraphsOf(other), snapshot + " : la ligne " + number + " montre un détail sans être courante");
                }
            }
        }
        assertEquals(List.of(7), numbers(expected.get(8).get("sources")));
    }

    // ---- The constraints of the page -------------------------------------------------------------------------------------------

    @Test
    void onAPhoneThePageNeverScrollsSidewaysAndItsButtonsCanBeTapped() throws Exception {
        Json observation = phone();
        Json layout = observation.get("layout");
        assertEquals(PHONE, (int) layout.get("innerWidth").number());
        assertTrue(layout.get("scrollWidth").number() <= layout.get("clientWidth").number(),
                "Défilement horizontal à 360 px : " + layout.value());
        List<Json> buttons = observation.get("buttons").items();
        assertFalse(buttons.isEmpty(), "La page n'a plus de bouton");
        for (Json button : buttons) {
            String label = button.get("text").text();
            assertTrue(button.get("shown").bool(), "Le bouton " + label + " n'est pas visible à 360 px");
            assertTrue(button.get("width").number() >= MINIMUM_TOUCH_SIZE, "Le bouton " + label + " est trop étroit");
            assertTrue(button.get("height").number() >= MINIMUM_TOUCH_SIZE, "Le bouton " + label + " est trop bas");
            assertTrue(button.get("right").number() <= layout.get("clientWidth").number() + TOLERANCE, "Le bouton " + label + " sort de l'écran");
        }
        Json row = row(observation, "l5", 5);
        Json detail = detailWith(row, DETAIL_OF_TOTAL);
        assertTrue(detail.get("box").get("right").number() <= layout.get("clientWidth").number() + TOLERANCE, "Le détail sort de l'écran");
    }

    @Test
    void thePageLoadsNothingFromOutsideAndAddsNoImage() throws Exception {
        for (Json observation : List.of(light(), dark(), phone())) {
            for (Json resource : observation.get("resources").items()) {
                assertTrue(resource.text().startsWith("/"), "Ressource externe : " + resource.text());
            }
            assertEquals(List.of(), texts(observation.get("addedElements")), "La page ajoute une image, un script ou un cadre");
        }
        assertTrue(texts(light().get("resources")).contains("/tokens.css"), "La page ne charge pas tokens.css");
    }

    // ---- Running the driver page ---------------------------------------------------------------------------------------------

    private static Json light() throws Exception {
        return observe("light", DESKTOP, "LIGHT", List.of(5, 1, 8, DRAFT_LINE));
    }

    private static Json dark() throws Exception {
        return observe("dark", DESKTOP, "DARK", List.of(5));
    }

    private static Json phone() throws Exception {
        return observe("phone", PHONE, "LIGHT", List.of(5));
    }

    /** One browser run per configuration, shared by the tests: the snapshot {@code l<n>} is taken with line n current. */
    private static synchronized Json observe(String key, int width, String scheme, List<Integer> currentLines) throws Exception {
        Json known = OBSERVATIONS.get(key);
        if (known != null) {
            return known;
        }
        StringJoiner steps = new StringJoiner(",", "[", "]");
        for (int line : currentLines) {
            steps.add("{\"focus\":" + line + "}").add("{\"snapshot\":\"l" + line + "\"}");
        }
        StringJoiner lines = new StringJoiner(",", "[", "]");
        LINES.forEach(line -> lines.add(jsonString(line)));
        String scenario = "{\"width\":" + width + ",\"mode\":\"AUTO\",\"lines\":" + lines + ",\"steps\":" + steps + "}";
        Json observation = BrowserDriver.parseJson(runWithScheme(DRIVER, "scenario=" + URLEncoder.encode(scenario, UTF_8), scheme));
        assertTrue(observation.get("error").isNull(), () -> "La page pilote a échoué : " + observation.get("error").value());
        assertEquals(List.of(), texts(observation.get("pageErrors")), "Erreurs de la page");
        OBSERVATIONS.put(key, observation);
        return observation;
    }

    private static Class<?> colourSchemeType() {
        try {
            return Class.forName("calc.web.BrowserDriver$ColorScheme");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("BrowserDriver n'a pas d'énumération ColorScheme { LIGHT, DARK }");
        }
    }

    private static Method runWithScheme() {
        try {
            return BrowserDriver.class.getMethod("run", String.class, String.class, colourSchemeType());
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("BrowserDriver n'a pas de run(String driverPage, String queryString, ColorScheme schéma)");
        }
    }

    // Through reflection, so that the other browser tests compile and run before the overload exists.
    private static String runWithScheme(String page, String query, String schemeName) throws Exception {
        Object scheme = Arrays.stream(colourSchemeType().getEnumConstants())
                .filter(constant -> ((Enum<?>) constant).name().equals(schemeName)).findFirst()
                .orElseThrow(() -> new AssertionError("BrowserDriver.ColorScheme n'a pas " + schemeName));
        try {
            return (String) runWithScheme().invoke(null, page, query, scheme);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw failure;
        }
    }

    // ---- Reading the observation ---------------------------------------------------------------------------------------------

    private static List<Json> expectedLines() throws Exception {
        return light().get("expected").get("lines").items();
    }

    private static Map<Integer, Json> expectedByNumber() throws Exception {
        Map<Integer, Json> lines = new HashMap<>();
        for (Json line : expectedLines()) {
            lines.put((int) line.get("number").number(), line);
        }
        return lines;
    }

    private static Map<Integer, String> statuses() throws Exception {
        Map<Integer, String> statuses = new HashMap<>();
        expectedByNumber().forEach((number, line) -> statuses.put(number, line.get("status").text()));
        assertEquals("APPROXIMATE", statuses.get(ROOT_LINE));
        return statuses;
    }

    private static List<Json> snapshots(Json observation) {
        Object value = observation.get("snapshots").value();
        assertTrue(value instanceof Map<?, ?>, "Pas d'états de la page");
        return ((Map<?, ?>) value).values().stream().map(Json::new).toList();
    }

    private static List<Json> rows(Json observation, String snapshot) {
        return observation.get("snapshots").get(snapshot).items();
    }

    private static Json row(Json observation, String snapshot, int number) {
        return rows(observation, snapshot).stream().filter(row -> (int) row.get("line").number() == number).findFirst()
                .orElseThrow(() -> new AssertionError(snapshot + " : pas de ligne [data-line=\"" + number + "\"]"));
    }

    private static Json detailWith(Json row, List<String> sentences) {
        int number = (int) row.get("line").number();
        List<Json> details = row.get("details").items();
        return details.stream().filter(detail -> texts(detail.get("paragraphs")).equals(sentences)).findFirst()
                .orElseThrow(() -> new AssertionError("Ligne " + number + " : aucun bloc .detail dont les paragraphes sont " + sentences
                        + ", blocs vus : " + details.stream().map(detail -> texts(detail.get("paragraphs"))).toList()));
    }

    private static List<String> paragraphsOf(Json row) {
        List<String> paragraphs = new ArrayList<>();
        for (Json detail : row.get("details").items()) {
            paragraphs.addAll(texts(detail.get("paragraphs")));
        }
        return paragraphs;
    }

    private static void assertSourceMarks(Json observation, String snapshot, List<Integer> sources) {
        for (Json row : rows(observation, snapshot)) {
            int number = (int) row.get("line").number();
            String expected = sources.contains(number) ? SOURCE_MARK : "";
            assertEquals(expected, row.get("sourceMark").isNull() ? null : row.get("sourceMark").text(),
                    snapshot + ", ligne " + number + " : .source-mark");
        }
    }

    private static List<String> texts(Json list) {
        return list.items().stream().map(Json::text).toList();
    }

    private static List<Integer> numbers(Json list) {
        return list.items().stream().map(item -> (int) item.number()).toList();
    }

    private static String background(Json scheme) {
        String body = scheme.get("bodyBackground").text();
        boolean transparent = body.equals("transparent") || body.replace(" ", "").equals("rgba(0,0,0,0)");
        return transparent ? scheme.get("rootBackground").text() : body;
    }

    private static String surfaceBase(String tokens, int from) {
        Matcher matcher = Pattern.compile("--color-surface-base\\s*:\\s*#([0-9a-fA-F]{6})\\s*;").matcher(tokens);
        assertTrue(from >= 0 && matcher.find(from), "tokens.css ne définit pas --color-surface-base après la position " + from);
        return matcher.group(1);
    }

    private static String rgb(String hex) {
        int value = Integer.parseInt(hex, 16);
        return "rgb(" + (value >> 16 & 0xff) + ", " + (value >> 8 & 0xff) + ", " + (value & 0xff) + ")";
    }

    private static String family(String fontFamily) {
        return fontFamily.replace("\"", "").replace("'", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String jsonString(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (char unit : text.toCharArray()) {
            if (unit == '"' || unit == '\\') {
                json.append('\\').append(unit);
            } else if (unit < 0x20) {
                json.append(String.format(Locale.ROOT, "\\u%04x", (int) unit));
            } else {
                json.append(unit);
            }
        }
        return json.append('"').toString();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }
}
