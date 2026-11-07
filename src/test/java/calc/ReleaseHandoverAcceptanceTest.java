package calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.persistence.JsonException;
import calc.persistence.ReleaseJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The handover of the 1.0.0 release to the maintainer: {@code docs/RELEASE.md} says how to publish the version, check the page
 * and delete the pre-releases, {@code docs/RECETTE.md} records the dry run and what it left unverified, and {@code README.md}
 * no longer says that the release was never tried. The tag of the dry run is read from its evidence,
 * {@code docs/essais/release-1.0.0-essai-final.json}, through {@code calc.persistence.ReleaseJson}.
 *
 * <p>These tests read files by relative path and never use the network: they cannot tell whether {@code v1.0.0} exists on the
 * remote, and pushing the tag is left to the maintainer.
 */
@Tag("acceptance")
class ReleaseHandoverAcceptanceTest {
    private static final Path EVIDENCE = Path.of("docs", "essais", "release-1.0.0-essai-final.json");
    private static final Path RELEASE_DOC = Path.of("docs", "RELEASE.md");
    private static final Path RECETTE = Path.of("docs", "RECETTE.md");
    private static final Path README = Path.of("README.md");
    private static final Path RELEASE_WORKFLOW = Path.of(".github", "workflows", "release.yml");

    private static final String PUBLISH_HEADING = "## Publier la version 1.0.0";
    private static final String TAG_COMMAND = "git tag -a v1.0.0 -m \"Calc 1.0.0\"";
    private static final String PUSH_COMMAND = "git push origin v1.0.0";
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}(er)? "
            + "(janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre) \\d{4}");

    // ------------------------------------------------------------------------------------------ docs/RELEASE.md

    @Test
    void theReleaseGuideHasASectionToPublishVersion100AfterThePublishSection() throws IOException {
        List<String> lines = lines(RELEASE_DOC);

        int general = indexOfHeading(lines, "## Publier une version");
        int specific = indexOfHeading(lines, PUBLISH_HEADING);
        assertTrue(general >= 0, RELEASE_DOC + " keeps its section « Publier une version »");
        assertTrue(specific > general, RELEASE_DOC + " has « " + PUBLISH_HEADING + " » after « Publier une version »");
    }

    @Test
    void thePublishSectionGivesTheExactTagAndPushCommandsInThatOrder() throws IOException {
        String section = publishSection();

        assertTrue(section.contains(TAG_COMMAND), "the section contains " + TAG_COMMAND);
        assertTrue(section.contains(PUSH_COMMAND), "the section contains " + PUSH_COMMAND);
        assertTrue(section.indexOf(TAG_COMMAND) < section.indexOf(PUSH_COMMAND), "the tag is created before it is pushed");
        assertFalse(Pattern.compile("git tag v\\S").matcher(section).find(), "no lightweight tag in the section");
    }

    @Test
    void thePublishSectionStartsWithTheChecksOnMain() throws IOException {
        String section = publishSection();

        assertTrue(section.contains("git switch main"), "the section says to switch to main");
        assertTrue(section.contains("git pull"), "the section says to pull main");
        assertTrue(section.indexOf("git pull") < section.indexOf(TAG_COMMAND), "main is updated before the tag is created");
    }

    @Test
    void thePublishSectionDeletesTheTrialPreReleaseWithTheTagOfTheEvidence() throws Exception {
        String tag = trialTag();
        String section = publishSection();

        assertTrue(section.contains("gh release delete " + tag + " --cleanup-tag --yes"),
                "the section deletes the pre-release " + tag + " read from " + EVIDENCE);
        assertFalse(section.contains("test-v1.0.0-12"), "the section does not keep the example tag of the dry run guide");
    }

    @Test
    void thePublishSectionDeletesTheOtherPreReleasesFoundWithGhReleaseList() throws IOException {
        String section = publishSection();

        assertTrue(section.contains("gh release list"), "the section lists the releases to find the other pre-releases");
        assertTrue(section.contains("test-v1.0.0-"), "the listing is filtered on the test-v1.0.0- pre-releases");
        assertTrue(section.contains("gh release delete v1.0.0 --cleanup-tag --yes"),
                "the section also gives the deletion of the final tag");
    }

    @Test
    void thePublishSectionNamesTheSixFilesTheTitleAndTheNotes() throws Exception {
        String section = publishSection();

        List<String> files = trialFiles();
        assertEquals(6, files.size(), "the evidence names the six files of a release");
        for (String file : files) {
            assertTrue(section.contains(file), "the section names the file " + file);
        }
        assertTrue(section.contains("Calc 1.0.0"), "the section gives the title « Calc 1.0.0 »");
        assertTrue(section.contains("pré-version"), "the section says that the release is not a pré-version");
        assertTrue(section.contains("INSTALLATION.md"), "the section says that the notes link to INSTALLATION.md");
        assertTrue(section.contains("SHA256SUMS.txt"), "the section compares a download to SHA256SUMS.txt");
    }

    @Test
    void thePublishSectionAsksToCorrectTheDateOfTheChangelogWhenTheTagIsPushedAnotherDay() throws IOException {
        List<String> lines = lines(RELEASE_DOC);
        List<String> section = block(lines, indexOfHeading(lines, PUBLISH_HEADING), "## ");

        assertTrue(section.stream().map(ReleaseHandoverAcceptanceTest::normal)
                        .anyMatch(line -> line.contains("changelog.md") && line.contains("[1.0.0]") && line.contains("date")),
                "one line of the section tells to correct the date of [1.0.0] in CHANGELOG.md");
        assertTrue(normal(String.join("\n", section)).contains("autre jour"), "the section says « un autre jour »");
    }

    @Test
    void theGeneralStepsCreateAnAnnotatedTag() throws IOException {
        String text = text(RELEASE_DOC);

        assertTrue(text.contains("git tag -a vX.Y.Z -m \"Calc X.Y.Z\""), "the general steps create an annotated tag");
        assertFalse(Pattern.compile("git tag vX\\.Y\\.Z").matcher(text).find(), "no lightweight git tag vX.Y.Z is left");
    }

    @Test
    void theGuideNoLongerPromisesAFirstRealRunNorListsWhatWasNotExecutedForE14() throws IOException {
        String text = text(RELEASE_DOC);

        assertFalse(text.contains("La première exécution réelle est l'essai décrit plus haut"),
                "the obsolete sentence about the first real run is gone");
        assertFalse(text.contains("Ce qui n'a pas été exécuté pour E14"), "the E14 passage is replaced by what really ran");
    }

    @Test
    void theReleaseWorkflowNeverTagsNorPushes() throws IOException {
        String workflow = text(RELEASE_WORKFLOW);

        assertFalse(workflow.contains("git tag"), RELEASE_WORKFLOW + " does not create a tag");
        assertFalse(workflow.contains("git push"), RELEASE_WORKFLOW + " does not push");
    }

    // ------------------------------------------------------------------------------------------ docs/RECETTE.md

    @Test
    void theReportHasADatedSubsectionAboutTheDryRunBetweenSections2And3() throws Exception {
        List<String> lines = lines(RECETTE);
        int second = indexOfSection(lines, 2);
        int third = indexOfSection(lines, 3);
        int heading = indexOfSubsection(lines, "essai a blanc de la version 1.0.0");

        assertTrue(second >= 0 && third > second, RECETTE + " keeps its sections 2 and 3");
        assertTrue(heading > second && heading < third, "the dry run subsection sits between « ## 2. » and « ## 3. »");
        assertTrue(lines.get(heading).startsWith("### "), "the dry run is a ### subsection, not a numbered section");

        String body = subsectionText(lines, heading);
        String normal = normal(body);
        assertTrue(DATE.matcher(normal).find(), "the subsection is dated");
        assertTrue(body.contains(trialTag()), "the subsection gives the tag " + trialTag());
        assertTrue(body.contains(string("runId")), "the subsection gives the run number");
        assertTrue(body.contains(string("commit").substring(0, 7)), "the subsection gives the commit");
        assertTrue(body.contains("docs/essais/release-1.0.0-essai-final.json"), "the subsection links the evidence");
        assertTrue(normal.contains("non verifie"), "the subsection says what is « non vérifié »");
        assertTrue(normal.contains("macos") && normal.contains("linux"), "the subsection names macOS and Linux");
        assertTrue(normal.contains("5 secondes"), "the subsection says that Windows only ran for 5 seconds");
    }

    @Test
    void theReportHasNoTenthSection() throws IOException {
        assertTrue(lines(RECETTE).stream().noneMatch(line -> line.matches("^##\\s+10[.)].*")), "no « ## 10. » heading");
    }

    @Test
    void theChecklistTicksOnlyBillingAndCiAndTheFirstReleaseRunWithTheirProof() throws Exception {
        List<String> items = checklist();

        List<String> billing = items.stream().filter(item -> normal(item).contains("facturation")).toList();
        assertFalse(billing.isEmpty(), "the checklist keeps its billing and CI item");
        assertTrue(billing.stream().allMatch(item -> item.startsWith("- [x]")), "the billing and CI item is ticked: " + billing);
        assertTrue(billing.stream().anyMatch(item -> Pattern.compile("\\d{8,}").matcher(item).find()),
                "the billing and CI item gives the number of a run: " + billing);

        List<String> firstRun = items.stream().filter(item -> normal(item).contains("workflow release")).toList();
        assertFalse(firstRun.isEmpty(), "the checklist keeps its item about the first run of the Release workflow");
        assertTrue(firstRun.stream().allMatch(item -> item.startsWith("- [x]")), "the first run item is ticked: " + firstRun);
        String tag = trialTag();
        String run = string("runId");
        assertTrue(firstRun.stream().anyMatch(item -> item.contains(tag) || item.contains(run)),
                "the first run item gives the tag or the run of the dry run: " + firstRun);
    }

    @Test
    void theChecklistKeepsWhatOnlyAHumanCanDoUnticked() throws Exception {
        List<String> unticked = checklist().stream().filter(item -> item.startsWith("- [ ]")).map(ReleaseHandoverAcceptanceTest::normal)
                .toList();

        for (String[] pair : new String[][] {{"macos", "linux"}, {"excel", "csv"}, {"smartscreen", "gatekeeper"},
                {"release", "sha-256"}, {"clavier", "windows"}, {"badge", "badge"}}) {
            assertTrue(unticked.stream().anyMatch(item -> item.contains(pair[0]) && item.contains(pair[1])),
                    "no unticked item about « " + pair[0] + " » and « " + pair[1] + " » among " + unticked);
        }
        assertTrue(unticked.stream().anyMatch(item -> item.contains("macos") && item.contains("linux")
                && !item.contains("xattr")), "the macOS and Linux item stays");
    }

    @Test
    void theDownloadItemNowTargetsTheReleaseV100AndStaysUnticked() throws Exception {
        List<String> items = checklist();

        assertTrue(items.stream().anyMatch(item -> item.startsWith("- [ ]") && normal(item).contains("sha-256")
                        && item.contains("v1.0.0")),
                "an unticked item about the SHA-256 download names the release v1.0.0");
    }

    @Test
    void theChecklistAddsUntickedItemsForXattrAndForTheAboutBox() throws Exception {
        List<String> unticked = checklist().stream().filter(item -> item.startsWith("- [ ]")).map(ReleaseHandoverAcceptanceTest::normal)
                .toList();

        assertTrue(unticked.stream().anyMatch(item -> item.contains("xattr")), "an unticked item about the xattr command");
        assertTrue(unticked.stream().anyMatch(item -> item.contains("aide > a propos de calc")),
                "an unticked item about Aide > À propos de Calc");
    }

    @Test
    void theDefectsD10D13AndD6AreRecordedAsFixedAfterTheReport() throws IOException {
        List<String> lines = lines(RECETTE);

        for (String id : List.of("D10", "D13", "D6")) {
            List<String> rows = lines.stream().filter(line -> line.startsWith("| " + id + " |")).toList();
            assertEquals(1, rows.size(), "one row for " + id);
            assertTrue(rows.get(0).contains("Corrigé après la recette"), id + " says « Corrigé après la recette »");
        }
    }

    // ------------------------------------------------------------------------------------------ README.md

    @Test
    void theReadmeNoLongerSaysTheReleaseWasNeverTried() throws IOException {
        assertFalse(text(README).contains("Release non essayée"), "README.md no longer says « Release non essayée »");
    }

    @Test
    void theKnownLimitsOfTheReadmeSayThatMacOsAndLinuxWereOnlyLaunchedInCi() throws IOException {
        List<String> lines = lines(README);
        int heading = indexOfHeading(lines, "## Limites connues");
        assertTrue(heading >= 0, "README.md keeps its section « Limites connues »");

        List<String> section = block(lines, heading, "## ");
        List<String> bullets = new ArrayList<>();
        for (String line : section) {
            if (line.startsWith("- ")) {
                bullets.add(line);
            } else if (!line.isBlank() && !bullets.isEmpty()) {
                bullets.set(bullets.size() - 1, bullets.get(bullets.size() - 1) + " " + line.strip());
            }
        }
        assertTrue(bullets.stream().anyMatch(bullet -> bullet.contains("macOS") && bullet.contains("Linux")
                        && normal(bullet).contains("non verifie")),
                "one known limit names macOS, Linux and « non vérifié »: " + bullets);
        assertTrue(bullets.stream().anyMatch(bullet -> normal(bullet).contains("essai a blanc")),
                "one known limit says that the chain ran as a dry run");
    }

    // ------------------------------------------------------------------------------------------ reading

    private static String normal(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    private static String text(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " does not exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> lines(Path file) throws IOException {
        return List.of(text(file).split("\n", -1));
    }

    /** The index of the line that is exactly {@code heading}, outside code blocks, or -1. */
    private static int indexOfHeading(List<String> lines, String heading) {
        boolean fenced = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && line.strip().equals(heading)) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfSection(List<String> lines, int number) {
        Pattern heading = Pattern.compile("^##\\s+" + number + "[.)].*");
        for (int index = 0; index < lines.size(); index++) {
            if (heading.matcher(lines.get(index)).matches()) {
                return index;
            }
        }
        return -1;
    }

    private static int indexOfSubsection(List<String> lines, String keyword) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).startsWith("#") && normal(lines.get(index)).contains(keyword)) {
                return index;
            }
        }
        return -1;
    }

    /** The lines after {@code start} up to the next heading that starts with {@code stop} (code blocks excluded). */
    private static List<String> block(List<String> lines, int start, String stop) {
        assertTrue(start >= 0, "the heading is missing");
        List<String> block = new ArrayList<>();
        boolean fenced = false;
        for (int index = start + 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced && line.startsWith(stop)) {
                break;
            }
            block.add(line);
        }
        return block;
    }

    /** The body of the {@code ###} subsection at {@code heading}, up to the next heading of any level. */
    private static String subsectionText(List<String> lines, int heading) {
        List<String> body = new ArrayList<>();
        for (int index = heading + 1; index < lines.size() && !lines.get(index).startsWith("#"); index++) {
            body.add(lines.get(index));
        }
        return String.join("\n", body);
    }

    private static String publishSection() throws IOException {
        List<String> lines = lines(RELEASE_DOC);
        int heading = indexOfHeading(lines, PUBLISH_HEADING);
        assertTrue(heading >= 0, RELEASE_DOC + " has no section « " + PUBLISH_HEADING + " »");
        return String.join("\n", block(lines, heading, "## "));
    }

    private static List<String> checklist() throws IOException {
        List<String> lines = lines(RECETTE);
        int heading = indexOfSection(lines, 8);
        assertTrue(heading >= 0, RECETTE + " has no section 8");
        return block(lines, heading, "## ").stream().filter(line -> line.startsWith("- [")).toList();
    }

    // ------------------------------------------------------------------------------------------ the evidence

    private static Map<?, ?> evidence() throws IOException {
        assertTrue(Files.isRegularFile(EVIDENCE), EVIDENCE + " must exist: it is the evidence of the final dry run");
        Object parsed;
        try {
            parsed = ReleaseJson.parse(Files.readString(EVIDENCE, StandardCharsets.UTF_8));
        } catch (JsonException e) {
            return fail(EVIDENCE + " must be valid JSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(Map.class, parsed, EVIDENCE + " must be a JSON object");
    }

    private static String string(String key) throws IOException {
        return assertInstanceOf(String.class, evidence().get(key), key + " of " + EVIDENCE);
    }

    private static String trialTag() throws IOException {
        String tag = string("tag");
        assertTrue(tag.matches("test-v1\\.0\\.0-[0-9]+"), "the tag of the dry run: " + tag);
        return tag;
    }

    private static List<String> trialFiles() throws IOException {
        Map<?, ?> files = assertInstanceOf(Map.class, evidence().get("files"), "files of " + EVIDENCE);
        return files.keySet().stream().map(String::valueOf).toList();
    }
}
