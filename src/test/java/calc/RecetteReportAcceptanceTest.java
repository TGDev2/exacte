package calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The deliverable of the final acceptance, {@code docs/RECETTE.md}: its nine sections in order, the results by guarantee with
 * proofs that exist, the defects with what they need, a reasoned verdict per idea, the gaps between documentation and reality,
 * the manual checklist. Whether its judgements are right is for a reader to tell; this test proves that nothing is missing and
 * that what it cites exists.
 */
@Tag("acceptance")
class RecetteReportAcceptanceTest {
    private static final Path REPORT = Path.of("docs", "RECETTE.md");
    private static final Path TESTS = Path.of("src", "test", "java");
    private static final Pattern SECTION = Pattern.compile("^##\\s+(\\d+)[.)]\\s*(.+)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\|[\\s:|-]+\\|$");
    private static final Pattern COMMIT = Pattern.compile("\\b[0-9a-f]{7,40}\\b");
    private static final Pattern TEST_CLASS = Pattern.compile("\\b[A-Z]\\w*(?:Test|Properties)\\b");
    private static final Pattern VERDICT = Pattern.compile("\\b(non livree|livree avec reserves|livree)\\b");
    // Not the name of a file such as tasks/todo.md.
    private static final Pattern PLACEHOLDER = Pattern.compile("(?<![\\w/])(todo|tbd|a completer)(?![\\w.])");

    // ------------------------------------------------------------------------------------------------ reading the report

    /** What is compared: lower case and no accents, so that a typographic choice does not fail the test. */
    private static String normal(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static List<String> reportLines() throws IOException {
        assertTrue(Files.isRegularFile(REPORT), "the report " + REPORT + " does not exist");
        List<String> lines = new ArrayList<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(REPORT, StandardCharsets.UTF_8)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            lines.add(line);
        }
        assertFalse(fenced, "a code block of the report is not closed");
        return lines;
    }

    private record Section(int number, String title, List<String> lines) {
        String text() {
            return String.join("\n", lines);
        }
    }

    private static List<Section> sections() throws IOException {
        List<Section> sections = new ArrayList<>();
        boolean fenced = false;
        for (String line : reportLines()) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            Matcher heading = fenced ? null : SECTION.matcher(line);
            if (heading != null && heading.matches()) {
                sections.add(new Section(Integer.parseInt(heading.group(1)), heading.group(2), new ArrayList<>()));
            } else if (!sections.isEmpty()) {
                sections.get(sections.size() - 1).lines().add(line);
            }
        }
        return sections;
    }

    private static Section section(int number) throws IOException {
        return sections().stream().filter(section -> section.number() == number).findFirst()
                .orElseThrow(() -> new AssertionError("the report has no section " + number));
    }

    /** The lines of the part of {@code section} under the {@code ###} heading that mentions {@code keyword}. */
    private static List<String> subsection(Section section, String keyword) {
        List<String> lines = new ArrayList<>();
        boolean inside = false;
        boolean found = false;
        for (String line : section.lines()) {
            if (line.startsWith("### ")) {
                inside = !found && normal(line).contains(keyword);
                found |= inside;
            } else if (inside) {
                lines.add(line);
            }
        }
        assertTrue(found, "section " + section.number() + " has no ### heading about « " + keyword + " »");
        return lines;
    }

    private record Table(List<String> header, List<List<String>> rows) {
        int column(String keyword) {
            for (int index = 0; index < header.size(); index++) {
                if (normal(header.get(index)).contains(keyword)) {
                    return index;
                }
            }
            throw new AssertionError("no column « " + keyword + " » in " + header);
        }
    }

    /** The first Markdown table among {@code lines}, if any. */
    private static Optional<Table> firstTable(List<String> lines) {
        List<String> header = null;
        List<List<String>> rows = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (header == null) {
                if (line.startsWith("|") && index + 1 < lines.size() && TABLE_SEPARATOR.matcher(lines.get(index + 1).strip()).matches()) {
                    header = cells(line);
                    index++;
                }
            } else if (line.startsWith("|")) {
                rows.add(cells(line));
            } else {
                break;
            }
        }
        return header == null ? Optional.empty() : Optional.of(new Table(header, rows));
    }

    private static List<String> cells(String row) {
        String inner = row.substring(1, row.endsWith("|") ? row.length() - 1 : row.length());
        return Arrays.stream(inner.split("(?<!\\\\)\\|", -1)).map(String::strip).toList();
    }

    private static void assertEveryCellIsFilled(Table table, String where) {
        for (List<String> row : table.rows()) {
            assertEquals(table.header().size(), row.size(), where + ": " + row);
            for (int index = 0; index < row.size(); index++) {
                assertFalse(row.get(index).isBlank(), where + ", column " + table.header().get(index) + " is empty: " + row);
            }
        }
    }

    private static boolean saysNone(List<String> lines) {
        return normal(String.join("\n", lines)).contains("aucun");
    }

    private static List<Path> testSources() throws IOException {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static void assertEveryCitedTestExists(String text, String where) throws IOException {
        List<String> names = testSources().stream()
                .map(file -> file.getFileName().toString().replace(".java", "")).toList();
        Matcher cited = TEST_CLASS.matcher(text);
        while (cited.find()) {
            assertTrue(names.contains(cited.group()), where + " cites a test that does not exist: " + cited.group());
        }
    }

    // ------------------------------------------------------------------------------------------------ the sections

    @Test
    void theReportHasItsNineSectionsInOrderAndNoneIsEmpty() throws IOException {
        List<Section> sections = sections();

        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), sections.stream().map(Section::number).toList(),
                "nine numbered sections (## 1. ...), in order");
        for (Section section : sections) {
            assertFalse(section.text().isBlank(), "section " + section.number() + " is empty");
        }
        assertFalse(PLACEHOLDER.matcher(normal(String.join("\n", reportLines()))).find(), "a placeholder is left in the report");
    }

    @ParameterizedTest(name = "section {0}: {1}")
    @CsvSource({
        "1, methode",
        "1, environnement",
        "2, build",
        "2, livraison",
        "3, garantie",
        "4, defaut",
        "5, risque",
        "5, preference",
        "6, ecart",
        "6, documentation",
        "7, verdict",
        "8, manuelle",
        "9, recommandation"})
    void everySectionHasTheExpectedSubject(int number, String keyword) throws IOException {
        assertTrue(normal(section(number).title()).contains(keyword), section(number).title());
    }

    @Test
    void theMethodSaysOnWhatTheAcceptanceWasRun() throws IOException {
        String text = normal(section(1).text());

        assertTrue(text.contains("windows"), "the operating system");
        assertTrue(text.contains("java") || text.contains("jdk"), "the JDK");
        assertTrue(text.contains("temporaire"), "how the data of the user were kept out of it");
    }

    @Test
    void theBuildSectionReportsWhatWasRunWithItsResults() throws IOException {
        String text = normal(section(2).text());

        for (String expected : List.of("gradlew", "clean check", "conformityreport", "packageapp", "sha-256", "build successful")) {
            assertTrue(text.contains(expected), "section 2 does not mention « " + expected + " »");
        }
        for (String tag : List.of("acceptance", "property", "robustness", "ui")) {
            assertTrue(Pattern.compile("\\b" + tag + "\\b").matcher(text).find(), "section 2 has no count for the tag " + tag);
        }
        assertTrue(text.contains("gh ") || text.contains("check-list"), "the remote CI and release: checked with gh, or left to the manual checklist");
    }

    @Test
    void theResultsTableGivesAProofAndAStatusForTheGuaranteesOfEveryIdea() throws IOException {
        Section section = section(3);
        Table table = firstTable(section.lines()).orElseThrow(() -> new AssertionError("section 3 has no table"));
        table.column("garantie");
        table.column("preuve");
        table.column("statut");
        assertEveryCellIsFilled(table, "section 3");
        assertTrue(table.rows().size() >= 15, "a table of the guarantees has more than " + table.rows().size() + " rows");

        String text = normal(section.text());
        for (String subject : List.of("idee 1", "idee 2", "idee 3", "migration", "robustesse")) {
            assertTrue(text.contains(subject), "section 3 says nothing about « " + subject + " »");
        }
        assertEveryCitedTestExists(section.text(), "section 3");
        for (String test : List.of("ExactEngineAcceptanceTest", "NotebookScenariosAcceptanceTest", "MigrationAcceptanceTest", "RobustnessAcceptanceTest")) {
            assertTrue(section.text().contains(test), "section 3 does not use " + test + " as a proof");
        }
    }

    @Test
    void everyBlockingDefectFixedNamesItsDedicatedCommitAndItsTest() throws Exception {
        List<String> fixed = subsection(section(4), "bloquant");
        Optional<Table> table = firstTable(fixed);
        if (table.isEmpty() || table.get().rows().isEmpty()) {
            assertTrue(saysNone(fixed), "without a table of the blocking defects, the report says there was none (« Aucun »)");
            return;
        }
        int commitColumn = table.get().column("commit");
        int testColumn = table.get().column("test");
        assertEveryCellIsFilled(table.get(), "blocking defects");
        for (List<String> row : table.get().rows()) {
            Matcher commit = COMMIT.matcher(row.get(commitColumn));
            assertTrue(commit.find(), "no commit hash: " + row);
            assertTrue(TEST_CLASS.matcher(row.get(testColumn)).find(), "no test class: " + row);
            assertEveryCitedTestExists(row.get(testColumn), "the blocking defect " + row);
            subjectOf(commit.group()).ifPresent(subject -> assertTrue(subject.startsWith("fix"),
                    "a blocking defect is fixed in a dedicated fix commit, not in: " + subject));
        }
    }

    // The subject of a commit, if git can tell: a shallow clone of the CI does not hold the old commits.
    private static Optional<String> subjectOf(String commit) throws Exception {
        Optional<String> shallow = git("rev-parse", "--is-shallow-repository");
        if (shallow.isEmpty() || shallow.get().equals("true")) {
            return Optional.empty();
        }
        Optional<String> subject = git("log", "-1", "--format=%s", commit);
        assertTrue(subject.isPresent(), "the commit " + commit + " does not exist");
        return subject;
    }

    private static Optional<String> git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException noGit) {
            return Optional.empty();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return Optional.empty();
        }
        return process.exitValue() == 0 ? Optional.of(output) : Optional.empty();
    }

    @Test
    void everyOtherDefectIsRecordedWithItsSeverityAndItsReproduction() throws IOException {
        List<String> recorded = subsection(section(4), "consign");
        Optional<Table> table = firstTable(recorded);
        if (table.isEmpty() || table.get().rows().isEmpty()) {
            assertTrue(saysNone(recorded), "without a table of the other defects, the report says there was none (« Aucun »)");
            return;
        }
        table.get().column("gravite");
        table.get().column("reproduction");
        assertEveryCellIsFilled(table.get(), "other defects");
    }

    @Test
    void theReportKeepsDefectsRisksAndPreferencesApart() throws IOException {
        String risks = normal(section(5).text());

        assertTrue(risks.contains("risque"), "section 5 lists the risks");
        assertTrue(risks.contains("preference"), "section 5 lists the preferences");
    }

    @Test
    void theGapsBetweenDocumentationAndRealityCoverTheReadmeAndTheUserGuide() throws IOException {
        Section section = section(6);
        String text = normal(section.text());

        assertTrue(text.contains("readme"), "the README was checked");
        assertTrue(text.contains("guide-utilisateur") || text.contains("guide utilisateur"), "the user guide was checked");
        boolean listed = section.lines().stream().anyMatch(line -> line.strip().startsWith("- ") || line.strip().startsWith("|"));
        assertTrue(listed || text.contains("aucun ecart"), "the gaps are listed, or the report says there is none (« Aucun écart »)");
    }

    @ParameterizedTest(name = "idea {0}")
    @CsvSource({"1", "2", "3"})
    void everyIdeaHasAVerdictWithItsArguments(int idea) throws IOException {
        String text = normal(section(7).text());
        int start = text.indexOf("idee " + idea);
        assertTrue(start >= 0, "section 7 has no verdict for the idea " + idea);
        int next = text.indexOf("idee " + (idea + 1), start);
        String verdict = text.substring(start, next < 0 ? text.length() : next);

        assertTrue(VERDICT.matcher(verdict).find(), "the verdict of the idea " + idea + " is one of: livrée, livrée avec réserves, non livrée");
        assertTrue(verdict.length() >= 200, "the verdict of the idea " + idea + " has no real argument: " + verdict);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "clavier, windows",
        "macos, linux",
        "csv, excel",
        "badge, badge",
        "release, sha-256",
        "smartscreen, gatekeeper"})
    void theManualChecklistHasItsUncheckedItem(String first, String second) throws IOException {
        List<String> items = section(8).lines().stream().map(RecetteReportAcceptanceTest::normal)
                .filter(line -> line.strip().startsWith("- [ ]")).toList();

        assertTrue(items.stream().anyMatch(item -> item.contains(first) && item.contains(second)),
                "no unchecked item about « " + first + " » and « " + second + " » among " + items);
    }

    @Test
    void theRecommendationsAreRankedByCriticalityImpactAndCost() throws IOException {
        Section section = section(9);
        String text = normal(section.text());

        for (String criterion : List.of("criticite", "impact", "cout")) {
            assertTrue(text.contains(criterion), "section 9 does not rank by « " + criterion + " »");
        }
        assertTrue(section.lines().stream().anyMatch(line -> line.strip().startsWith("|") || line.strip().matches("^(-|\\d+\\.)\\s.*")),
                "section 9 lists its recommendations");
    }
}
