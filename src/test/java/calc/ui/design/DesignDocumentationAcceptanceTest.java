package calc.ui.design;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Acceptance of the design document and of the static mockup (E03): {@code docs/design/DESIGN.md} states the principles, the
 * grid, the typography, every colour role and every size of {@code tokens.properties}, the states of every element and the
 * current interface; {@code docs/design/maquette.html} shows the "Devis cuisine" notebook of the README in both forced themes
 * of {@code web/site/tokens.css}, with no literal colour, no script and no remote URL.
 *
 * <p>Texts are compared after the typographic apostrophe has become {@code '} and every kind of space (no-break, narrow
 * no-break, thin) a plain space. Paths are relative to the repository root, the working directory of the tests. Whether the
 * mockup looks right, in light and in dark, is judged by a person in a browser.
 */
@Tag("acceptance")
final class DesignDocumentationAcceptanceTest {

    private static final Path DESIGN = Path.of("docs", "design", "DESIGN.md");
    private static final Path MOCKUP = Path.of("docs", "design", "maquette.html");
    private static final Path TOKENS = Path.of("src", "main", "resources", "calc", "ui", "design", "tokens.properties");
    private static final Path TOKENS_CSS = Path.of("web", "site", "tokens.css");
    private static final String TOKENS_CSS_HREF = "../../web/site/tokens.css";

    private static final List<String> SECTIONS = List.of(
            "Principes", "Grille et espacements", "Typographie", "Couleurs", "États des éléments", "Interface actuelle");
    private static final List<String> PRINCIPLES = List.of(
            "Le document d'abord", "Deux voix typographiques", "L'exactitude comme langage visuel", "Calme et focus",
            "Clair, sombre ou système", "Découvrable");
    private static final List<String> STATES = List.of(
            "ligne normale", "survol", "ligne active", "ligne titre", "affectation", "résultat exact", "résultat approché",
            "erreur", "aperçu", "source", "focus clavier");
    /** Component names of SPEC 4.2, so that the document and the code use the same words. */
    private static final List<String> COMPONENTS = List.of(
            "modeSelector", "numberLabel", "exactnessSign", "sourceMark", "lineField", "nameLabel", "resultLabel",
            "errorLabel", "lineDetail", "statusLabel", "summaryLabel", "commandPalette");
    private static final List<String> SCREENSHOTS_BEFORE = List.of(
            "../images/avant/carnet-devis.png", "../images/avant/carnet-vide.png");

    private static final Pattern H2 = Pattern.compile("^##\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern NUMBERED = Pattern.compile("^\\s{0,3}(?:#{3,6}\\s+)?(\\d+)[.)]\\s+(.*)$");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HTML_IMAGE = Pattern.compile("<img\\b[^>]*\\bsrc\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL_HEX =
            Pattern.compile("(?<![&\\w])#(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3,4})(?![0-9A-Za-z_-])");
    private static final Pattern LITERAL_FUNCTION = Pattern.compile("\\b(?:rgba?|hsla?)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOTE_URL = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEAF = Pattern.compile("<([a-zA-Z][\\w-]*)\\b([^>]*)>([^<]*)</\\1\\s*>");
    private static final Pattern CLASS = Pattern.compile("\\bclass\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TWO_WORDS = Pattern.compile("\\p{L}{2,}\\s+\\p{L}{2,}");

    // ------------------------------------------------------------------------------------------------------- DESIGN.md

    @Test
    void theDesignDocumentHasTheSixSections() throws IOException {
        Map<String, List<String>> sections = sections();
        List<String> missing = new ArrayList<>();
        for (String title : SECTIONS) {
            if (!sections.containsKey(title)) {
                missing.add(title);
            }
        }
        assertTrue(missing.isEmpty(), () -> DESIGN + " lacks the level 2 headings " + missing + ", has " + sections.keySet());
    }

    @Test
    void thePrinciplesAreTheSixOfTheBriefNumberedFromOneToSix() throws IOException {
        List<String> items = numberedItems(section("Principes"));
        assertEquals(6, items.size(), () -> "the section Principes should state six numbered principles, found " + items);
        List<String> missing = new ArrayList<>();
        Set<Integer> used = new TreeSet<>();
        for (String principle : PRINCIPLES) {
            int found = -1;
            for (int i = 0; i < items.size(); i++) {
                if (!used.contains(i) && lower(items.get(i)).contains(lower(principle))) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                missing.add(principle);
            } else {
                used.add(found);
            }
        }
        assertTrue(missing.isEmpty(), () -> "each principle of the brief should be one numbered item; missing " + missing
                + " in " + items);
    }

    @Test
    void theColourSectionCitesEveryColourRoleOfTheTokens() throws IOException {
        Set<String> roles = new TreeSet<>();
        for (String key : tokens().stringPropertyNames()) {
            Matcher matcher = Pattern.compile("^color\\.(?:light|dark)\\.(.+)$").matcher(key);
            if (matcher.matches()) {
                roles.add(matcher.group(1));
            }
        }
        assertTrue(roles.size() >= 18, () -> "tokens.properties should define the colour roles, found " + roles);
        String colours = text(section("Couleurs"));
        List<String> missing = new ArrayList<>();
        for (String role : roles) {
            if (!colours.contains("`" + role + "`")) {
                missing.add(role);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the section Couleurs should cite every colour role between backticks; missing "
                + missing);
    }

    @Test
    void theDesignDocumentCitesEveryFontSizeAndEverySpace() throws IOException {
        String design = text(read(DESIGN));
        List<String> missing = new ArrayList<>();
        for (String key : new TreeSet<>(tokens().stringPropertyNames())) {
            if (key.startsWith("font.size.")) {
                String name = key.substring("font.size.".length());
                if (!design.contains("`" + key + "`") && !design.contains("`--font-size-" + name + "`")
                        && !design.contains("`" + name + "`")) {
                    missing.add(key);
                }
            } else if (key.startsWith("space.")) {
                String name = key.substring("space.".length());
                if (!design.contains("`" + key + "`") && !design.contains("`--space-" + name + "`")) {
                    missing.add(key);
                }
            }
        }
        assertTrue(missing.isEmpty(), () -> DESIGN + " should cite every font size and every space between backticks; missing "
                + missing);
    }

    @Test
    void theGridAndTheTypographyFollowTheSpecification() throws IOException {
        String grid = lower(text(section("Grille et espacements")));
        assertTrue(grid.contains("4 px"), "the section Grille et espacements should state the grid of 4 px");
        String typography = lower(text(section("Typographie")));
        List<String> missing = new ArrayList<>();
        for (String word : List.of("inter", "jetbrains mono", "tabulaire", "virgule", "droite")) {
            if (!typography.contains(word)) {
                missing.add(word);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the section Typographie should name Inter, JetBrains Mono with tabular figures and"
                + " results aligned right on the decimal comma; missing " + missing);
    }

    @Test
    void theStatesSectionDescribesEveryStateOfTheElements() throws IOException {
        String states = lower(text(section("États des éléments")));
        List<String> missing = new ArrayList<>();
        for (String state : STATES) {
            if (!states.contains(lower(state))) {
                missing.add(state);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the section États des éléments should describe " + missing);
        assertTrue(states.contains("#"), "the section États des éléments should show the title line (#)");
    }

    @Test
    void theStatesSectionGivesTheSignsAndTheTextsOfTheSpecification() throws IOException {
        String states = text(section("États des éléments"));
        List<String> missing = new ArrayList<>();
        for (String expected : List.of("=", "≈", "!", "◆", "Utilisée par", "Valeur garantie entre", "exactes")) {
            if (!states.contains(expected)) {
                missing.add(expected);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the section États des éléments should give the signs and texts " + missing);
    }

    @Test
    void theDesignDocumentUsesTheComponentNamesOfTheSpecification() throws IOException {
        String design = read(DESIGN);
        List<String> missing = new ArrayList<>();
        for (String component : COMPONENTS) {
            if (!Pattern.compile("\\b" + component + "\\b").matcher(design).find()) {
                missing.add(component);
            }
        }
        assertTrue(missing.isEmpty(), () -> DESIGN + " should name the components of SPEC 4.2; missing " + missing);
    }

    @Test
    void theCurrentInterfaceSectionShowsTheScreenshotsBefore() throws IOException {
        String current = String.join("\n", section("Interface actuelle"));
        Set<String> images = new TreeSet<>();
        for (Pattern pattern : List.of(MARKDOWN_IMAGE, HTML_IMAGE)) {
            Matcher matcher = pattern.matcher(current);
            while (matcher.find()) {
                images.add(matcher.group(1));
            }
        }
        for (String screenshot : SCREENSHOTS_BEFORE) {
            assertTrue(images.contains(screenshot), () -> "the section Interface actuelle should show " + screenshot
                    + ", shows " + images);
            assertTrue(Files.isRegularFile(DESIGN.resolveSibling(screenshot).normalize()),
                    () -> screenshot + " should resolve from " + DESIGN);
        }
    }

    // --------------------------------------------------------------------------------------------------- maquette.html

    @Test
    void theMockupIsAStaticFrenchPageOnTheSharedTokens() throws IOException {
        String html = read(MOCKUP);
        assertTrue(Pattern.compile("<html\\b[^>]*\\blang\\s*=\\s*\"fr\"", Pattern.CASE_INSENSITIVE).matcher(html).find(),
                "maquette.html should declare lang=\"fr\" on <html>");
        List<String> stylesheets = stylesheets(html);
        assertTrue(stylesheets.contains(TOKENS_CSS_HREF), () -> "maquette.html should load " + TOKENS_CSS_HREF
                + " by a <link rel=\"stylesheet\">, loads " + stylesheets);
        assertEquals(TOKENS_CSS.toAbsolutePath().normalize(),
                MOCKUP.resolveSibling(TOKENS_CSS_HREF).toAbsolutePath().normalize(), "the link should reach web/site/tokens.css");
        assertFalse(SCRIPT.matcher(html).find(), "maquette.html should hold no <script>");
        assertNoLiteralColourNorUrl(MOCKUP, html);
        for (String href : stylesheets) {
            Path sheet = MOCKUP.resolveSibling(href).normalize();
            assertTrue(Files.isRegularFile(sheet), () -> "the stylesheet " + href + " of maquette.html should exist");
            if (!href.equals(TOKENS_CSS_HREF)) {
                assertNoLiteralColourNorUrl(sheet, read(sheet));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"clair", "sombre"})
    void eachThemeOfTheMockupShowsTheDevisCuisineNotebookInEveryState(String theme) throws IOException {
        String block = themeBlock(read(MOCKUP), theme);
        String text = plainText(block);
        List<String> missing = new ArrayList<>();
        for (String expected : List.of("Devis cuisine", "meubles", "pose", "taux", "5 167,5", "6 201")) {
            if (!text.contains(expected)) {
                missing.add(expected);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the block " + theme + " should show the notebook Devis cuisine of the README;"
                + " missing " + missing);

        List<Leaf> leaves = leaves(block);
        for (String exact : List.of("total", "=", "≈", "!")) {
            assertTrue(leaves.stream().anyMatch(leaf -> leaf.text().equals(exact)), () -> "the block " + theme
                    + " should hold an element whose text is exactly « " + exact + " » (assignment label or margin sign)");
        }
        int errorSign = -1;
        for (Leaf leaf : leaves) {
            if (leaf.text().equals("!")) {
                errorSign = leaf.position();
                break;
            }
        }
        int sign = errorSign;
        assertTrue(leaves.stream().anyMatch(leaf -> leaf.position() > sign && leaf.isError()
                        && TWO_WORDS.matcher(leaf.text()).find()),
                () -> "the block " + theme + " should show, after the sign « ! », the error message under the line"
                        + " (an element whose class names an error, holding a sentence)");
        assertTrue(Pattern.compile("Utilisée par 1 ligne(?!s)").matcher(text).find(), () -> "the block " + theme
                + " should show the detail of the active line with « Utilisée par 1 ligne »");
        assertTrue(Pattern.compile("\\d+ exactes").matcher(text).find(), () -> "the block " + theme
                + " should show a status bar with « N exactes »");
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private record Leaf(int position, String attributes, String text) {
        boolean isError() {
            Matcher matcher = CLASS.matcher(attributes);
            return matcher.find() && lower(matcher.group(1)).matches(".*(error|erreur).*");
        }
    }

    private static void assertNoLiteralColourNorUrl(Path file, String content) {
        Matcher hex = LITERAL_HEX.matcher(content);
        assertFalse(hex.find(), () -> file + " should use colours through var(--…) only, found " + hex.group());
        Matcher function = LITERAL_FUNCTION.matcher(content);
        assertFalse(function.find(), () -> file + " should use colours through var(--…) only, found " + function.group());
        assertFalse(REMOTE_URL.matcher(content).find(), () -> file + " should hold no http(s) URL");
    }

    private static List<String> stylesheets(String html) {
        List<String> hrefs = new ArrayList<>();
        Matcher tag = LINK_TAG.matcher(html);
        while (tag.find()) {
            String link = tag.group();
            Matcher rel = Pattern.compile("\\brel\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(link);
            Matcher href = Pattern.compile("\\bhref\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(link);
            if (rel.find() && lower(rel.group(1)).contains("stylesheet") && href.find()) {
                hrefs.add(href.group(1));
            }
        }
        return hrefs;
    }

    /** The inner HTML of the first element carrying {@code data-theme="<theme>"}, up to its matching closing tag. */
    private static String themeBlock(String html, String theme) {
        Matcher open = Pattern.compile("<([a-zA-Z][\\w-]*)\\b[^>]*\\bdata-theme\\s*=\\s*\"" + theme + "\"[^>]*>").matcher(html);
        assertTrue(open.find(), () -> "maquette.html should hold a container data-theme=\"" + theme + "\"");
        String name = open.group(1);
        Matcher tags = Pattern.compile("<(/?)" + name + "\\b[^>]*?(/?)>", Pattern.CASE_INSENSITIVE).matcher(html);
        int depth = 1;
        int from = open.end();
        tags.region(from, html.length());
        while (tags.find()) {
            if (!tags.group(1).isEmpty()) {
                depth--;
                if (depth == 0) {
                    return html.substring(from, tags.start());
                }
            } else if (tags.group(2).isEmpty()) {
                depth++;
            }
        }
        throw new AssertionError("the container data-theme=\"" + theme + "\" of maquette.html is never closed");
    }

    private static List<Leaf> leaves(String html) {
        List<Leaf> leaves = new ArrayList<>();
        Matcher matcher = LEAF.matcher(html);
        while (matcher.find()) {
            leaves.add(new Leaf(matcher.start(), matcher.group(2), normalize(decode(matcher.group(3))).strip()));
        }
        return leaves;
    }

    private static String plainText(String html) {
        String withoutComments = html.replaceAll("(?s)<!--.*?-->", " ");
        return normalize(decode(withoutComments.replaceAll("<[^>]+>", " ")));
    }

    private static String decode(String html) {
        Matcher entity = Pattern.compile("&(#x[0-9A-Fa-f]+|#\\d+|[a-zA-Z]+);").matcher(html);
        StringBuilder out = new StringBuilder();
        while (entity.find()) {
            String name = entity.group(1);
            String replacement;
            if (name.startsWith("#x") || name.startsWith("#X")) {
                replacement = new String(Character.toChars(Integer.parseInt(name.substring(2), 16)));
            } else if (name.startsWith("#")) {
                replacement = new String(Character.toChars(Integer.parseInt(name.substring(1))));
            } else {
                replacement = switch (name) {
                    case "amp" -> "&";
                    case "lt" -> "<";
                    case "gt" -> ">";
                    case "quot" -> "\"";
                    case "apos" -> "'";
                    case "nbsp", "thinsp", "ensp", "emsp" -> " ";
                    case "asymp" -> "≈";
                    case "rsquo" -> "'";
                    default -> entity.group();
                };
            }
            entity.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        entity.appendTail(out);
        return out.toString();
    }

    /** Typographic apostrophe to {@code '}, every space (no-break, narrow, thin) to one plain space. */
    private static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == (char) 0x2019) {
                c = '\'';
            }
            boolean blank = Character.isWhitespace(c) || Character.isSpaceChar(c);
            if (blank) {
                if (!space) {
                    out.append(' ');
                }
                space = true;
            } else {
                out.append(c);
                space = false;
            }
        }
        return out.toString();
    }

    private static String lower(String text) {
        return normalize(text).toLowerCase(Locale.ROOT);
    }

    private static String text(List<String> lines) {
        return normalize(String.join("\n", lines));
    }

    private static String text(String content) {
        return normalize(content);
    }

    /** Level 2 sections of DESIGN.md (outside code fences), each with the lines up to the next level 2 heading. */
    private static Map<String, List<String>> sections() throws IOException {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        boolean fenced = false;
        for (String line : read(DESIGN).split("\\R", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            Matcher heading = H2.matcher(line);
            if (!fenced && heading.matches()) {
                current = new ArrayList<>();
                sections.put(normalize(heading.group(1)).strip(), current);
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }

    private static List<String> section(String title) throws IOException {
        Map<String, List<String>> sections = sections();
        List<String> lines = sections.get(title);
        assertNotNull(lines, () -> DESIGN + " should have a level 2 heading « " + title + " », has " + sections.keySet());
        return lines;
    }

    /** Numbered items (1. or 1) list items, or ### 1. headings), each with its continuation lines, markup stars removed. */
    private static List<String> numberedItems(List<String> lines) {
        List<String> items = new ArrayList<>();
        List<Integer> numbers = new ArrayList<>();
        StringBuilder current = null;
        for (String line : lines) {
            Matcher item = NUMBERED.matcher(line);
            if (item.matches()) {
                if (current != null) {
                    items.add(current.toString());
                }
                numbers.add(Integer.parseInt(item.group(1)));
                current = new StringBuilder(item.group(2));
            } else if (current != null) {
                current.append(' ').append(line);
            }
        }
        if (current != null) {
            items.add(current.toString());
        }
        for (int i = 0; i < numbers.size(); i++) {
            assertEquals(i + 1, numbers.get(i), "the principles should be numbered from 1 to 6, found " + numbers);
        }
        List<String> plain = new ArrayList<>();
        for (String item : items) {
            plain.add(normalize(item.replace("*", "").replace("`", "")).strip());
        }
        return plain;
    }

    private static Properties tokens() throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(read(TOKENS)));
        return properties;
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> file + " should exist");
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
