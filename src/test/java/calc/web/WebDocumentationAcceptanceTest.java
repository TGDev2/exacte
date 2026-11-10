package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The documentation of the web version, read as text: ADR 0015, the link at the top of the README and its two new known
 * limits, guarantee G29 and the section that explains it in {@code docs/TESTS.md}, the web part of {@code docs/ARCHITECTURE.md}
 * and {@code docs/CI.md}, and the changelog section. Paths are relative to the repository root, as in the other documentation
 * tests. Each key phrase is searched on a single line of Markdown, so a wrapped sentence does not satisfy a test by luck.
 *
 * <p>That {@code DocumentationLinksTest}, {@code CiTriggersTest}, {@code InstallationGuideAcceptanceTest},
 * {@code ReadmeExamplesTest}, {@code ReleaseNotesAcceptanceTest}, {@code ReleaseHandoverAcceptanceTest} and
 * {@code RecetteReportAcceptanceTest} stay green is proved by those tests themselves; none of them is repeated here.
 */
@Tag("acceptance")
class WebDocumentationAcceptanceTest {
    private static final Path TEMPLATE = Path.of("docs", "adr", "0000-modele.md");
    private static final Path ADR = Path.of("docs", "adr", "0015-version-web-teavm.md");
    private static final Path ADR_INDEX = Path.of("docs", "adr", "README.md");
    private static final Path README = Path.of("README.md");
    private static final Path TESTS = Path.of("docs", "TESTS.md");
    private static final Path ARCHITECTURE = Path.of("docs", "ARCHITECTURE.md");
    private static final Path CI = Path.of("docs", "CI.md");
    private static final Path CHANGELOG = Path.of("CHANGELOG.md");
    private static final Path TEST_SOURCES = Path.of("src", "test", "java");

    private static final String PAGE_LINK = "[Essayer dans le navigateur](https://tgdev2.github.io/exacte/)";

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        return Files.readString(file, UTF_8).replace("\r\n", "\n");
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    /** The level-2 sections of a document by exact title line, body up to the next level-2 title; fences are skipped. */
    private static Map<String, String> sections(String document) {
        Map<String, String> sections = new LinkedHashMap<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        boolean fenced = false;
        for (String line : document.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.startsWith("## ")) {
                if (title != null) {
                    sections.put(title, body.toString());
                }
                title = line.stripTrailing();
                body = new StringBuilder();
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            sections.put(title, body.toString());
        }
        return sections;
    }

    private static String section(Path file, String title) throws IOException {
        Map<String, String> sections = sections(read(file));
        assertTrue(sections.containsKey(title), file + " must have the heading \"" + title + "\", has " + sections.keySet());
        return sections.get(title);
    }

    private static void assertContainsAll(String where, String text, String... expected) {
        List<String> missing = new ArrayList<>();
        for (String item : expected) {
            if (!text.contains(item)) {
                missing.add(item);
            }
        }
        assertTrue(missing.isEmpty(), where + " is missing: " + missing);
    }

    /** True when one single line of the text contains every fragment (compared ignoring case). */
    private static boolean someLineContainsAll(String text, String... fragments) {
        return lines(text).stream().anyMatch(line -> {
            String lower = line.toLowerCase(Locale.ROOT);
            return Stream.of(fragments).allMatch(fragment -> lower.contains(fragment.toLowerCase(Locale.ROOT)));
        });
    }

    private static List<String> lineMatches(String text, String prefix) {
        return lines(text).stream().filter(line -> line.startsWith(prefix)).toList();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // ADR 0015
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void adr0015HasTheHeadingsOfTheTemplateInTheirOrder() throws IOException {
        List<String> templateHeadings = lines(read(TEMPLATE)).stream().filter(line -> line.startsWith("## ")).toList();
        assertEquals(5, templateHeadings.size(), "the template has five sections: " + templateHeadings);
        String adr = read(ADR);
        List<String> adrLines = lines(adr);
        int previous = -1;
        for (String heading : templateHeadings) {
            int index = adrLines.indexOf(heading);
            assertTrue(index >= 0, ADR + " must have the heading line \"" + heading + "\"");
            assertTrue(index > previous, "\"" + heading + "\" must come after the previous section of the template");
            previous = index;
        }
        assertTrue(adrLines.get(0).startsWith("# 0015 - "), "the title line is \"# 0015 - …\", is: " + adrLines.get(0));
    }

    @Test
    void adr0015IsAcceptedWithADate() throws IOException {
        String status = section(ADR, "## Status (Statut)");
        assertTrue(status.contains("Accepted"), "the status says Accepted: " + status.strip());
        assertTrue(Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(status).find(), "the status carries a date: " + status.strip());
    }

    @Test
    void adr0015NamesTheTechnologiesAndTheAlternativesItWeighed() throws IOException {
        String adr = read(ADR);
        assertContainsAll(ADR.toString(), adr, "TeaVM", "CheerpJ", "TypeScript", "sqrtAndRemainder");
        String alternatives = section(ADR, "## Alternatives considered (Alternatives considérées)");
        assertContainsAll("the alternatives of ADR 0015", alternatives, "CheerpJ", "TypeScript");
    }

    @Test
    void adr0015AccountsForTheLimitsOfTheWebVersion() throws IOException {
        String adr = read(ADR);
        String lower = adr.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("limites"), "ADR 0015 has a passage on the limits of the web version");
        assertTrue(someLineContainsAll(adr, "synchrone"), "the synchronous calculation (D7) is stated: it freezes the tab");
        assertTrue(someLineContainsAll(adr, "fichier"), "the absence of files is stated");
        assertTrue(someLineContainsAll(adr, "unicode"), "the Unicode difference of TeaVM is stated");
        assertTrue(someLineContainsAll(adr, "rapport de conformité"), "the limit of the conformity report is stated");
    }

    @Test
    void theIndexOfTheAdrsHasExactlyOneLineFor0015AndStillOneFor0014() throws IOException {
        String index = read(ADR_INDEX);
        assertEquals(1, lineMatches(index, "| 0015 |").size(), "one line | 0015 | in docs/adr/README.md");
        assertEquals(1, lineMatches(index, "| 0014 |").size(), "one line | 0014 | in docs/adr/README.md");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // README
    // ---------------------------------------------------------------------------------------------------------------

    /** From the line that starts with "Calc est une" to the first blank line, the lines joined by a space. */
    private static String openingParagraph(String readme) {
        List<String> lines = lines(readme);
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("Calc est une")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "the README has a paragraph that starts with \"Calc est une\"");
        StringBuilder paragraph = new StringBuilder();
        for (int i = start; i < lines.size() && !lines.get(i).isBlank(); i++) {
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(lines.get(i).strip());
        }
        return paragraph.toString();
    }

    @Test
    void theReadmeLinksToThePageBeforeTheFeaturesAndOutsideTheOpeningParagraph() throws IOException {
        String readme = read(README);
        int link = readme.indexOf(PAGE_LINK);
        assertTrue(link >= 0, "README.md must contain " + PAGE_LINK);
        int features = readme.indexOf("\n## Fonctionnalités\n");
        assertTrue(features >= 0, "README.md keeps its \"## Fonctionnalités\" line");
        assertTrue(link < features, "the link comes before \"## Fonctionnalités\"");
        assertEquals(readme.indexOf(PAGE_LINK), readme.lastIndexOf(PAGE_LINK), "the link appears once");
        assertFalse(openingParagraph(readme).contains(PAGE_LINK), "the link stays out of the opening paragraph");
        assertFalse(section(README, "## Téléchargement").contains(PAGE_LINK), "the link is not in the download section");
    }

    @Test
    void theOpeningParagraphOfTheReadmeSpeaksOfTheBrowserAndNoLongerOnlyOfTheDesktop() throws IOException {
        String paragraph = openingParagraph(read(README));
        assertTrue(paragraph.startsWith("Calc est une"), paragraph);
        assertFalse(paragraph.contains("pour le bureau"), "the paragraph no longer says \"pour le bureau\": " + paragraph);
        assertTrue(paragraph.contains("navigateur"), "the paragraph mentions the browser: " + paragraph);
    }

    @Test
    void theKnownLimitsOfTheReadmeStateWhatTheBrowserDoesNotHave() throws IOException {
        String limits = section(README, "## Limites connues");
        assertTrue(someLineContainsAll(limits, "navigateur", "menu Fichier", ".calc.json", "migration", "verrou", "écriture atomique"),
                "one line of \"Limites connues\" says that the browser has no File menu, no opening or saving of .calc.json files,"
                        + " no migration of the old histories, no lock and no atomic write");
        assertTrue(someLineContainsAll(limits, "navigateur", "carnet", "lourd", "onglet")
                        && lines(limits).stream().anyMatch(line -> line.toLowerCase(Locale.ROOT).contains("onglet")
                                && Pattern.compile("fig(er|e|ent)\\b").matcher(line.toLowerCase(Locale.ROOT)).find()),
                "one line of \"Limites connues\" says that a very heavy notebook can freeze the tab");
        assertTrue(someLineContainsAll(limits, "essai", "blanc"), "the bullet on the dry run of the release stays");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // docs/TESTS.md : G29
    // ---------------------------------------------------------------------------------------------------------------

    private static Set<String> testClassNames() throws IOException {
        Set<String> names = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TEST_SOURCES)) {
            files.filter(Files::isRegularFile).forEach(file -> {
                String name = file.getFileName().toString();
                if (name.endsWith(".java")) {
                    names.add(name.substring(0, name.length() - ".java".length()));
                }
            });
        }
        return names;
    }

    private static List<String> g29Cells() throws IOException {
        List<String> rows = lineMatches(read(TESTS), "| G29 |");
        assertEquals(1, rows.size(), "docs/TESTS.md must have exactly one line \"| G29 |\", has " + rows.size());
        String row = rows.get(0).strip();
        assertTrue(row.startsWith("|") && row.endsWith("|"), "the row is a table row: " + row);
        String inner = row.substring(1, row.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    @Test
    void guaranteeG29IsAThreeCellRowInTheGuaranteesTable() throws IOException {
        List<String> cells = g29Cells();
        assertEquals(3, cells.size(), "G29 has exactly 3 cells: " + cells);
        assertEquals("G29", cells.get(0));
        assertFalse(cells.get(1).isBlank(), "the promise is written");
        Map<String, String> sections = sections(read(TESTS));
        assertTrue(sections.get("## Garanties du produit").contains("| G29 |"), "G29 sits in the table \"Garanties du produit\"");
        assertEquals(1, lineMatches(sections.get("## Garanties du produit"), "| G28 |").size(), "G28 is still there");
    }

    @Test
    void guaranteeG29PromisesTheSameResultsAsTheDesktopComparedUnderNode() throws IOException {
        String promise = g29Cells().get(1);
        assertContainsAll("the promise of G29", promise, "version web", "mêmes résultats", "bureau", "Node", "webTest");
        assertFalse(promise.contains("`"), "no backtick in the second cell, the conformity report reads the classes of the third: " + promise);
    }

    @Test
    void guaranteeG29CitesTestClassesThatExistUnderSrcTestJava() throws IOException {
        String cited = g29Cells().get(2);
        Matcher matcher = Pattern.compile("`([A-Za-z0-9_]+)`").matcher(cited);
        List<String> classes = new ArrayList<>();
        while (matcher.find()) {
            classes.add(matcher.group(1));
        }
        assertFalse(classes.isEmpty(), "the third cell cites at least one class between backticks: " + cited);
        Set<String> existing = testClassNames();
        List<String> unknown = classes.stream().filter(name -> !existing.contains(name)).toList();
        assertTrue(unknown.isEmpty(), "classes without a file under src/test/java (web/src/webTest is not read by the report): " + unknown);
    }

    @Test
    void aSectionOfTestsMdDescribesTheCorpusTheSeedAndTheLimitOfTheReport() throws IOException {
        Map<String, String> sections = sections(read(TESTS));
        List<String> candidates = new ArrayList<>();
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            String body = entry.getValue();
            if (body.contains("corpus") && body.contains("graine") && body.contains("webTest") && body.contains("Node")) {
                candidates.add(entry.getKey());
            }
        }
        assertFalse(candidates.isEmpty(), "one section of docs/TESTS.md describes the parity corpus, its seed, Node and webTest");
        boolean limitStated = candidates.stream().anyMatch(title -> someLineContainsAll(sections.get(title), "rapport de conformité", "racine"));
        assertTrue(limitStated,
                "that section says on one line that the conformity report only reads the results of the root project, not of web/");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // docs/ARCHITECTURE.md and docs/CI.md
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void architectureDescribesTheWebSubprojectCalcWebAndThePathFromJavaToThePage() throws IOException {
        String architecture = read(ARCHITECTURE);
        assertContainsAll(ARCHITECTURE.toString(), architecture, "`web/`", "`calc.web`", "TeaVM");
        boolean path = lines(architecture).stream()
                .anyMatch(line -> Pattern.compile("Java\\s*→\\s*TeaVM\\s*→.*page").matcher(line).find());
        assertTrue(path, "one line of docs/ARCHITECTURE.md draws the path Java → TeaVM → page");
        assertTrue(someLineContainsAll(architecture, "WebNotebook"), "WebNotebook, the entry of JavaScript, is named");
    }

    @Test
    void ciDescribesThePagesWorkflowItsTriggerItsPrerequisiteAndItsJobs() throws IOException {
        String ci = read(CI);
        assertTrue(ci.contains("pages.yml"), "docs/CI.md names pages.yml");
        assertTrue(ci.contains("Paramètres → Pages → Source : GitHub Actions"),
                "docs/CI.md states the prerequisite Paramètres → Pages → Source : GitHub Actions");
        Map<String, String> sections = sections(ci);
        String pages = null;
        for (Map.Entry<String, String> entry : sections.entrySet()) {
            if (entry.getValue().contains("pages.yml")) {
                pages = entry.getKey() + "\n" + entry.getValue();
                break;
            }
        }
        assertTrue(pages != null, "a section of docs/CI.md is about pages.yml");
        assertTrue(someLineContainsAll(pages, "`push`", "`main`"), "the trigger is a `push` on the `main` branch, on one line");
        assertTrue(pages.contains("workflow_dispatch") || pages.contains("à la main") || pages.contains("manuel"),
                "the manual trigger is stated");
        for (String job : List.of("conformity", "site", "deploy")) {
            assertTrue(Pattern.compile("\\b" + job + "\\b").matcher(pages).find(), "the section names the job " + job);
        }
        assertTrue(sections.containsKey("## Protection de la branche `main` (action manuelle)"),
                "the section on the protection of main is left as it was");
    }

    // ---------------------------------------------------------------------------------------------------------------
    // CHANGELOG.md
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void theChangelogHasAWebSectionBeforeUnreleasedAndUnreleasedIsStillFollowedByTheFirstRelease() throws IOException {
        List<String> lines = lines(read(CHANGELOG));
        int unreleased = lines.indexOf("## [Non publié]");
        assertTrue(unreleased >= 0, "the changelog keeps \"## [Non publié]\"");
        int web = -1;
        for (int i = 0; i < unreleased; i++) {
            String line = lines.get(i);
            if (line.startsWith("## ") && line.toLowerCase(Locale.ROOT).contains("web")) {
                web = i;
            }
        }
        assertTrue(web >= 0, "a level-2 section about the web version comes before \"## [Non publié]\"");
        assertFalse(lines.get(web).startsWith("## ["), "the web section has no version number: " + lines.get(web));
        boolean content = lines.subList(web + 1, unreleased).stream().anyMatch(line -> line.startsWith("- "));
        assertTrue(content, "the web section lists what it adds");

        int next = unreleased + 1;
        while (next < lines.size() && lines.get(next).isBlank()) {
            next++;
        }
        assertEquals("## [1.0.0] - 2026-11-07", lines.get(next), "\"## [Non publié]\" is followed directly by the 1.0.0 section");
    }
}
