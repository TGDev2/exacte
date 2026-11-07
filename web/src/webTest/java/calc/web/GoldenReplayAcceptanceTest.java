package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The decisive trial of the web version: the core compiled by TeaVM replays the golden file and a trial notebook under
 * Node with the same results as the JVM. The golden path never reaches calc.expression nor calc.notebook, which is why
 * the verdict also needs the trial notebook.
 *
 * <p>Depends only on the JDK and on JUnit, no calc.* class. Every path is relative to the root of the repository
 * ({@code workingDir = rootDir} in {@code :web:webTest}). This class never runs git: whether the core stayed unchanged is
 * checked once, by hand, and only the line recording it is read here.
 */
@DisplayName("Essai TeaVM : le golden et le carnet d'essai rejoués sous Node")
class GoldenReplayAcceptanceTest {
    private static final String CALC_JS = "web/build/generated/teavm/js/calc.js";
    private static final String REPLAY_GOLDEN = "web/parity/replay-golden.js";
    private static final String TRIAL_NOTEBOOK = "web/parity/trial-notebook.js";
    private static final String GOLDEN = "src/test/resources/golden/engine-v2.tsv";
    private static final String ERROR_MESSAGES = "src/test/resources/export/carnet-auto.csv";
    private static final String WEB_BUILD = "web/build.gradle.kts";
    private static final String ROOT_BUILD = "build.gradle.kts";
    private static final String CONFORMITY_BUILD = "gradle/conformity.gradle.kts";
    private static final String SETTINGS = "settings.gradle.kts";
    private static final String WEB_EXPORTS = "web/src/main/java/calc/web/WebExports.java";
    private static final String SBOM = "build/reports/cyclonedx/bom.json";
    private static final String ESSAY = "docs/essais/teavm-essai.md";

    private static final String REPLAY_SUCCESS = "OK 70/70";
    private static final String SQRT_2_BOUND = "± 2,1 × 10⁻²⁵";
    private static final String SQRT_2_AUTO = "≈ 1,41421356237…";
    private static final String SQRT_2_SCIENTIFIC = "≈ 1,41421356237… × 10⁰";
    private static final int OUTCOME = 3;
    private static final int DISPLAY = 4;
    private static final int REUSABLE = 5;

    private static final Pattern HASHED = Pattern.compile("len=\\d+ sha256=[0-9a-f]{64}");
    private static final Pattern REPORTED_LINE = Pattern.compile("\\bligne (\\d+)\\b");
    private static final Pattern TRIM_CALL = Pattern.compile("\\.trim(?:Start|End|Left|Right)?\\s*\\(");
    private static final Pattern REQUIRED_MODULE = Pattern.compile("\\brequire\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*\\)");
    private static final Set<String> ALLOWED_MODULES = Set.of("fs", "path", "crypto", "node:fs", "node:path", "node:crypto");
    private static final Pattern LINK_TO_LIFECYCLE = Pattern.compile(
            "\\btasks\\s*\\.\\s*(?:check|build|assemble)\\b"
                    + "|\"(?:check|build|assemble)\""
                    + "|(?<![\\w.\"])(?:check|build|assemble)\\s*[{.]");
    private static final Pattern TASK_TEST_BLOCK = Pattern.compile("\\btest\\s*\\{");
    private static final String NUMBER_SPACE = "[\\s\\u00A0\\u202F]";
    private static final Pattern SIZE_IN_BYTES = Pattern.compile(
            "(?<![\\d,.])[1-9][\\d.\\u00A0\\u202F ]*" + NUMBER_SPACE + "*octets\\b");
    private static final Pattern MEASURED_DURATION = Pattern.compile(
            "(?<![\\d,./])\\d+(?:[,.]\\d+)?" + NUMBER_SPACE + "*(?:ms|s|min|secondes?|minutes?)\\b");

    private static final long NODE_WATCHDOG_MINUTES = 5;

    @TempDir
    Path temporaryDirectory;

    private record NodeRun(int exitCode, String stdout, String stderr) {
    }

    // ---- calc.js ----------------------------------------------------------------------------------------------------

    @Test
    void generateJavaScriptProducesANonEmptyCalcJsFromTheWebExports() throws IOException {
        Path calcJs = Path.of(CALC_JS);
        assertTrue(Files.isRegularFile(calcJs), CALC_JS + " must exist: :web:webTest depends on generateJavaScript.");
        assertTrue(Files.size(calcJs) > 0, CALC_JS + " must not be empty.");

        String build = withoutComments(read(WEB_BUILD));
        assertTrue(build.contains("org.teavm"), "The web module must use the TeaVM plugin.");
        assertTrue(build.contains("0.16.0"), "The TeaVM plugin must be version 0.16.0 (decision D1).");
        assertTrue(build.contains("calc.web.WebExports"), "WebExports must be the entry point of TeaVM.");
        assertTrue(build.contains("calc.js"), "The file produced by TeaVM must be calc.js.");
        assertTrue(build.contains("generateJavaScript"), "webTest must depend on generateJavaScript.");
    }

    @Test
    void webExportsReachesTheNotebookPathOfTheCore() throws IOException {
        String source = read(WEB_EXPORTS);
        assertTrue(source.contains("NotebookEditor"), "trialNotebook must go through NotebookEditor.");
        assertTrue(source.contains("ValueFormatter"), "trialNotebook must format through ValueFormatter.");
        assertTrue(source.contains("@JSExport"), "WebExports must export its methods to JavaScript.");
    }

    @Test
    void theWebModuleHoldsNoCopyOfACoreClass() throws IOException {
        List<Path> webFiles = javaFilesUnder(Path.of("web/src/main/java"));
        assertFalse(webFiles.isEmpty(), "The web module must have sources.");

        Set<String> coreNames = new TreeSet<>();
        for (Path core : javaFilesUnder(Path.of("src/main/java"))) {
            coreNames.add(core.getFileName().toString());
        }
        Path webRoot = Path.of("web/src/main/java");
        Path webPackage = Path.of("calc", "web");
        for (Path file : webFiles) {
            assertTrue(webRoot.relativize(file).startsWith(webPackage), file + " must stay in the calc.web package.");
            assertFalse(coreNames.contains(file.getFileName().toString()), file + " has the name of a class of the core.");
        }
    }

    // ---- The golden file under Node ---------------------------------------------------------------------------------

    @Test
    void theGoldenFileHoldsSeventyCasesOfWhichFifteenAreErrors() throws IOException {
        List<String[]> cases = goldenRows();
        assertEquals(70, cases.size(), "The golden file holds 70 cases.");
        assertEquals(55, cases.stream().filter(row -> row[OUTCOME].equals("OK")).count(), "55 cases are OK.");
        assertEquals(15, cases.stream().filter(row -> row[OUTCOME].equals("ERROR")).count(), "15 cases are ERROR.");
        assertTrue(cases.stream().anyMatch(row -> HASHED.matcher(row[REUSABLE]).matches()), "Some values are stored hashed.");
    }

    @Test
    void theSeventyCasesAreReproducedUnderNode() throws Exception {
        assertReplaySucceeds(GOLDEN);
    }

    @Test
    void aGoldenCopyWithWindowsLineEndingsIsStillReproduced() throws Exception {
        String content = read(GOLDEN);
        assertFalse(content.contains("\r"), "The golden file is stored with LF line endings.");
        assertReplaySucceeds(write("golden-crlf.tsv", content.replace("\n", "\r\n")).toString());
    }

    @Test
    void anAlteredDisplayIsReportedWithItsLineNumber() throws Exception {
        int first = firstLineWhere(row -> row[OUTCOME].equals("OK"));
        assertOnlyLineIsReported(goldenCopyAltering(first, DISPLAY), first);

        int approximate = firstLineWhere(row -> row[DISPLAY].startsWith("≈"));
        assertOnlyLineIsReported(goldenCopyAltering(approximate, DISPLAY), approximate);

        int last = lastLineWhere(row -> row[OUTCOME].equals("OK"));
        assertOnlyLineIsReported(goldenCopyAltering(last, DISPLAY), last);
    }

    @Test
    void anAlteredReusableValueIsReportedWithItsLineNumber() throws Exception {
        int line = firstLineWhere(row -> row[OUTCOME].equals("OK") && !HASHED.matcher(row[REUSABLE]).matches());
        assertOnlyLineIsReported(goldenCopyAltering(line, REUSABLE), line);
    }

    @Test
    void anAlteredErrorMessageIsReportedWithItsLineNumber() throws Exception {
        int line = firstLineWhere(row -> row[OUTCOME].equals("ERROR"));
        assertOnlyLineIsReported(goldenCopyAltering(line, DISPLAY), line);
    }

    @Test
    void anErrorThatExpectsAReusableValueIsReportedWithItsLineNumber() throws Exception {
        int line = lastLineWhere(row -> row[OUTCOME].equals("ERROR"));
        assertOnlyLineIsReported(goldenCopyWith(line, REUSABLE, "1"), line);
    }

    @Test
    void anAlteredOutcomeIsReportedWithItsLineNumber() throws Exception {
        int success = firstLineWhere(row -> row[OUTCOME].equals("OK"));
        assertOnlyLineIsReported(goldenCopyWith(success, OUTCOME, "ERROR"), success);

        int refusal = firstLineWhere(row -> row[OUTCOME].equals("ERROR"));
        assertOnlyLineIsReported(goldenCopyWith(refusal, OUTCOME, "OK"), refusal);
    }

    @Test
    void everyGapIsReportedNotOnlyTheFirstOne() throws Exception {
        int first = firstLineWhere(row -> row[OUTCOME].equals("OK"));
        int last = lastLineWhere(row -> row[OUTCOME].equals("ERROR"));
        List<String> lines = goldenLines();
        for (int line : List.of(first, last)) {
            String[] columns = lines.get(line - 1).split("\t", -1);
            columns[DISPLAY] += " altéré";
            lines.set(line - 1, String.join("\t", columns));
        }
        NodeRun run = node(REPLAY_GOLDEN, CALC_JS, write("golden-two-gaps.tsv", String.join("\n", lines)).toString());
        assertEquals(1, run.exitCode(), run::toString);
        assertEquals(Set.of(first, last), reportedLines(run.stderr()), run::toString);
    }

    @Test
    void aHashedValueIsRecomputedAndComparedBothOnItsLengthAndOnItsDigest() throws Exception {
        int line = firstLineWhere(row -> HASHED.matcher(row[REUSABLE]).matches());
        String stored = goldenLine(line)[REUSABLE];

        String lastDigit = stored.substring(stored.length() - 1);
        String wrongDigest = stored.substring(0, stored.length() - 1) + (lastDigit.equals("0") ? "1" : "0");
        assertOnlyLineIsReported(goldenCopyWith(line, REUSABLE, wrongDigest), line);

        String digest = stored.substring(stored.indexOf(' '));
        int length = Integer.parseInt(stored.substring("len=".length(), stored.indexOf(' ')));
        assertOnlyLineIsReported(goldenCopyWith(line, REUSABLE, "len=" + (length + 1) + digest), line);
    }

    /**
     * GoldenOutputsTest.comparable applies to any expected value that has the hashed form, with the length in UTF-16 units
     * and the digest of the UTF-8 bytes: a non-ASCII display stored that way must still match.
     */
    @Test
    void aNonAsciiDisplayStoredHashedIsSummarisedLikeTheJvmDoes() throws Exception {
        int line = firstLineWhere(row -> row[DISPLAY].equals(SQRT_2_AUTO));
        Path copy = goldenCopyWith(line, DISPLAY, comparable(SQRT_2_AUTO));
        assertReplaySucceeds(copy.toString());
    }

    @Test
    void aGoldenCopyWithoutACaseIsRefused() throws Exception {
        List<String> lines = goldenLines();
        lines.remove(firstLineWhere(row -> row[OUTCOME].equals("OK")) - 1);
        assertCaseCountIsRefused(write("golden-69.tsv", String.join("\n", lines)));
    }

    @Test
    void aGoldenCopyWithAnExtraCaseIsRefused() throws Exception {
        List<String> lines = goldenLines();
        int line = firstLineWhere(row -> row[OUTCOME].equals("OK"));
        lines.add(line, lines.get(line - 1));
        assertCaseCountIsRefused(write("golden-71.tsv", String.join("\n", lines)));
    }

    @Test
    void theReplayReallyCallsTheFileItIsGiven() throws Exception {
        Path stub = stubCalcJs("exports.goldenCase = function () { return \"OK\\tfaux\\tfaux\"; };");
        NodeRun run = node(REPLAY_GOLDEN, stub.toString(), GOLDEN);
        assertEquals(1, run.exitCode(), () -> "A wrong calc.js must fail the replay: " + run);
        assertTrue(reportedLines(run.stderr()).contains(2), () -> "The first case must be reported: " + run);
    }

    @Test
    void aMissingGoldenFileFailsTheReplay() throws Exception {
        NodeRun run = node(REPLAY_GOLDEN, CALC_JS, temporaryDirectory.resolve("absent.tsv").toString());
        assertEquals(1, run.exitCode(), run::toString);
    }

    // ---- The trial notebook under Node ------------------------------------------------------------------------------

    @Test
    void theTrialNotebookGivesTheExactValueTheRootAndItsBoundAndTheError() throws Exception {
        NodeRun run = trial("(1/3)*3\n√2\n2 +", "AUTO", 0, 0);
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals(String.join("\n",
                row(1, "(1/3)*3", "EXACT", "1", "", ""),
                row(2, "√2", "APPROXIMATE", SQRT_2_AUTO, SQRT_2_BOUND, ""),
                row(3, "2 +", "ERROR", "", "", missingOperandMessage())) + "\n", run.stdout());
    }

    @Test
    void movingALineRenumbersTheReferencesThenUndoAndRedoRestoreThem() throws Exception {
        NodeRun run = trial("10\n3\n$1 + 1", "AUTO", 2, 1);
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals(String.join("\n",
                row(1, "3", "EXACT", "3", "", ""),
                row(2, "10", "EXACT", "10", "", ""),
                row(3, "$2 + 1", "EXACT", "11", "", ""),
                "undo\t10|3|$1 + 1",
                "redo\t3|10|$2 + 1") + "\n", run.stdout());
    }

    @Test
    void theFractionModeShowsTheExactFraction() throws Exception {
        NodeRun run = trial("1/3\n√2", "FRACTION", 0, 0);
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals(row(1, "1/3", "EXACT", "1/3", "", ""), stdoutLines(run).get(0));
    }

    @Test
    void theScientificModeShowsThePowerOfTen() throws Exception {
        NodeRun run = trial("1/3\n√2", "SCIENTIFIC", 0, 0);
        assertEquals(0, run.exitCode(), run::toString);
        String[] root = stdoutLines(run).get(1).split("\t", -1);
        assertEquals("2", root[0]);
        assertEquals("√2", root[1]);
        assertEquals("APPROXIMATE", root[2]);
        assertEquals(SQRT_2_SCIENTIFIC, root[3]);
    }

    @Test
    void theEndOfLineCarriageReturnsOfTheNotebookFileAreRemoved() throws Exception {
        NodeRun run = trial("(1/3)*3\r\n√2\r\n2 +", "AUTO", 0, 0);
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals(String.join("\n",
                row(1, "(1/3)*3", "EXACT", "1", "", ""),
                row(2, "√2", "APPROXIMATE", SQRT_2_AUTO, SQRT_2_BOUND, ""),
                row(3, "2 +", "ERROR", "", "", missingOperandMessage())) + "\n", run.stdout());
    }

    @Test
    void aNotebookTheCoreRefusesIsReportedAsRefused() throws Exception {
        NodeRun run = trial("1\n".repeat(5000) + "1", "AUTO", 0, 0);
        assertEquals(0, run.exitCode(), run::toString);
        assertTrue(run.stdout().startsWith("REFUSED\t"), () -> "A NotebookException gives REFUSED: " + run);
        assertTrue(run.stdout().contains("5000"), () -> "The message of the core must be kept: " + run);
        assertEquals(1, stdoutLines(run).size(), () -> "A refusal is a single line: " + run);
    }

    @Test
    void theTrialScriptPassesItsArgumentsAndPrintsTheReturnedValue() throws Exception {
        Path stub = stubCalcJs("exports.trialNotebook = function (lines, mode, moveNumber, moveToNumber) {"
                + " return JSON.stringify([lines, mode, moveNumber, moveToNumber]); };");
        NodeRun run = node(TRIAL_NOTEBOOK, stub.toString(), write("carnet.txt", "(1/3)*3\r\n√2").toString(), "FRACTION", "2", "1");
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals("[\"(1/3)*3\\n√2\",\"FRACTION\",2,1]\n", run.stdout());
    }

    @Test
    void anExceptionFailsTheTrialScript() throws Exception {
        Path stub = stubCalcJs("exports.trialNotebook = function () { throw new Error(\"panne\"); };");
        NodeRun run = node(TRIAL_NOTEBOOK, stub.toString(), write("carnet.txt", "1").toString(), "AUTO", "0", "0");
        assertEquals(1, run.exitCode(), run::toString);
    }

    @Test
    void aMissingNotebookFileFailsTheTrialScript() throws Exception {
        NodeRun run = node(TRIAL_NOTEBOOK, CALC_JS, temporaryDirectory.resolve("absent.txt").toString(), "AUTO", "0", "0");
        assertEquals(1, run.exitCode(), run::toString);
    }

    @Test
    void theNodeScriptsUseNoExternalModuleAndNeverTrim() throws IOException {
        for (String script : List.of(REPLAY_GOLDEN, TRIAL_NOTEBOOK)) {
            String source = read(script);
            assertFalse(TRIM_CALL.matcher(source).find(), script + " must not trim: empty and blank entries count.");
            Matcher modules = REQUIRED_MODULE.matcher(source);
            while (modules.find()) {
                assertTrue(ALLOWED_MODULES.contains(modules.group(1)), script + " requires " + modules.group(1));
            }
        }
        for (String manifest : List.of("package.json", "web/package.json", "web/parity/package.json")) {
            assertFalse(Files.exists(Path.of(manifest)), manifest + ": the scripts run without npm.");
        }
    }

    // ---- The build ---------------------------------------------------------------------------------------------------

    @Test
    void theWebBuildKeepsTheStrictCompilerOptions() throws IOException {
        String build = withoutComments(read(WEB_BUILD));
        assertTrue(build.contains("-Xlint:all"), "web/build.gradle.kts must compile with -Xlint:all.");
        assertTrue(build.contains("-Werror"), "web/build.gradle.kts must compile with -Werror.");
        assertFalse(build.contains("-Xlint:-"), "No lint category may be excluded.");
    }

    @Test
    void theWebBuildNeverWritesABlockThatDrivesTheTestTask() throws IOException {
        String build = read(WEB_BUILD);
        for (String form : List.of("tasks.test {", "tasks.withType<Test>", "test.enabled", "test.filter")) {
            assertFalse(build.contains(form), "web/build.gradle.kts must not contain " + form);
        }
        assertFalse(TASK_TEST_BLOCK.matcher(build).find(), "web/build.gradle.kts must not contain test {");
    }

    @Test
    void neitherWebTestNorGenerateJavaScriptIsLinkedToTheLifecycle() throws IOException {
        Matcher link = LINK_TO_LIFECYCLE.matcher(withoutComments(read(WEB_BUILD)));
        assertFalse(link.find(), () -> "web/build.gradle.kts must not touch check, build or assemble: " + link.group());

        for (String rootFile : List.of(ROOT_BUILD, CONFORMITY_BUILD)) {
            String root = withoutComments(read(rootFile));
            assertFalse(root.contains("webTest"), rootFile + " must not reach webTest.");
            assertFalse(root.contains("generateJavaScript"), rootFile + " must not reach generateJavaScript.");
        }
    }

    @Test
    void theWebModuleIsDeclaredAndTheRootDependsOnItForItsTestsOnly() throws IOException {
        assertTrue(read(SETTINGS).contains("include(\"web\")"), "settings.gradle.kts must include web.");

        String root = withoutComments(read(ROOT_BUILD));
        assertTrue(root.contains("testImplementation(project(\":web\"))"), "The root depends on :web for its tests.");
        assertTrue(root.contains("isTransitive = false"), "calc.jar must not come twice on the test classpath.");
        assertFalse(root.contains("implementation(project(\":web\"))"), "The application must not depend on :web.");

        assertTrue(withoutComments(read(WEB_BUILD)).contains("implementation(project(\":\"))"), ":web uses the core as it is.");
    }

    @Test
    void webTestFollowsItsContractAndTheWebModuleHasNoTestSources() throws IOException {
        String build = withoutComments(read(WEB_BUILD));
        assertTrue(build.contains("tasks.register<Test>(\"webTest\")"), "webTest is registered as its own Test task.");
        assertTrue(build.contains("upToDateWhen"), "webTest must run each time: its inputs are not all declared.");
        assertTrue(build.contains("webtest-results"), "The reports of webTest must stay out of web/build/test-results.");
        assertFalse(Files.exists(Path.of("web/src/test")), ":web:test must stay NO-SOURCE.");

        Path results = Path.of("web/build/test-results");
        if (Files.exists(results)) {
            try (Stream<Path> files = Files.walk(results)) {
                List<Path> reports = files.filter(file -> file.toString().endsWith(".xml")).toList();
                assertEquals(List.of(), reports, "web/build/test-results must hold no report: it would be counted.");
            }
        }
    }

    @Test
    void theSoftwareBillOfMaterialsDoesNotMentionTeaVm() throws IOException {
        assertTrue(withoutComments(read(WEB_BUILD)).contains(":cyclonedxBom"), "webTest must refresh the SBOM it reads.");
        String bom = read(SBOM);
        assertTrue(bom.contains("CycloneDX"), SBOM + " must be a CycloneDX document.");
        assertFalse(bom.toLowerCase(Locale.ROOT).contains("teavm"), SBOM + " describes what the desktop application ships.");
    }

    // ---- The essay ----------------------------------------------------------------------------------------------------

    @Test
    void theEssayRecordsTheVerdictAndItsEvidence() throws IOException {
        String essay = read(ESSAY);
        for (String text : List.of(
                "Verdict : GO",
                "0.16.0",
                "70/70",
                "sqrtAndRemainder",
                "Carnet d'essai : OK",
                "Cœur inchangé : git diff --quiet 045c91d -- src/main/java, code 0")) {
            assertTrue(essay.contains(text), ESSAY + " must contain: " + text);
        }
        assertFalse(essay.contains("Verdict : STOP"), "The verdict must be unique.");
    }

    @Test
    void theEssayGivesTheSizeOfCalcJsInBytes() throws IOException {
        assertTrue(read(ESSAY).lines().anyMatch(line -> line.contains("calc.js") && SIZE_IN_BYTES.matcher(line).find()),
                ESSAY + " must give the size of calc.js in octets, on one line.");
    }

    @Test
    void theEssayGivesTheMeasuredDurationOfWebTest() throws IOException {
        assertTrue(read(ESSAY).lines().anyMatch(line -> line.contains("webTest") && MEASURED_DURATION.matcher(line).find()),
                ESSAY + " must give the measured duration of webTest, on one line.");
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------------

    private void assertReplaySucceeds(String golden) throws IOException, InterruptedException {
        NodeRun run = node(REPLAY_GOLDEN, CALC_JS, golden);
        assertEquals(0, run.exitCode(), run::toString);
        String[] lines = run.stdout().split("\n");
        assertEquals(REPLAY_SUCCESS, lines[lines.length - 1], run::toString);
        assertEquals(Set.of(), reportedLines(run.stderr()), run::toString);
    }

    private void assertOnlyLineIsReported(Path golden, int lineNumber) throws IOException, InterruptedException {
        NodeRun run = node(REPLAY_GOLDEN, CALC_JS, golden.toString());
        assertEquals(1, run.exitCode(), run::toString);
        assertNotEquals(REPLAY_SUCCESS, lastStdoutLine(run), run::toString);
        assertEquals(Set.of(lineNumber), reportedLines(run.stderr()), run::toString);
    }

    private void assertCaseCountIsRefused(Path golden) throws IOException, InterruptedException {
        NodeRun run = node(REPLAY_GOLDEN, CALC_JS, golden.toString());
        assertEquals(1, run.exitCode(), run::toString);
        assertNotEquals(REPLAY_SUCCESS, lastStdoutLine(run), run::toString);
        assertFalse(reportedLines(run.stderr()).isEmpty(), () -> "stderr must say ligne <n> : ...: " + run);
    }

    private static String lastStdoutLine(NodeRun run) {
        String[] lines = run.stdout().split("\n");
        return lines[lines.length - 1];
    }

    private static List<String> stdoutLines(NodeRun run) {
        assertTrue(run.stdout().endsWith("\n"), () -> "The returned value is followed by a line feed: " + run);
        return List.of(run.stdout().substring(0, run.stdout().length() - 1).split("\n", -1));
    }

    private static Set<Integer> reportedLines(String stderr) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = REPORTED_LINE.matcher(stderr);
        while (matcher.find()) {
            numbers.add(Integer.valueOf(matcher.group(1)));
        }
        return numbers;
    }

    private NodeRun trial(String notebook, String mode, int moveNumber, int moveToNumber)
            throws IOException, InterruptedException {
        Path file = write("carnet.txt", notebook);
        return node(TRIAL_NOTEBOOK, CALC_JS, file.toString(), mode, Integer.toString(moveNumber), Integer.toString(moveToNumber));
    }

    private static String row(int number, String text, String status, String result, String bound, String error) {
        return String.join("\t", Integer.toString(number), text, status, result, bound, error);
    }

    /** The message of "2 +" as the desktop exports it, third column of line 9. */
    private static String missingOperandMessage() throws IOException {
        String line = Files.readAllLines(Path.of(ERROR_MESSAGES), UTF_8).get(8);
        String[] columns = line.split(";", -1);
        assertEquals("2 +", columns[1], "Line 9 of " + ERROR_MESSAGES + " should be the line 2 +.");
        return columns[2];
    }

    private NodeRun node(String... arguments) throws IOException, InterruptedException {
        Path stdout = Files.createTempFile(temporaryDirectory, "node", ".out");
        Path stderr = Files.createTempFile(temporaryDirectory, "node", ".err");
        List<String> command = new ArrayList<>();
        command.add("node");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .redirectOutput(stdout.toFile())
                .redirectError(stderr.toFile())
                .start();
        if (!process.waitFor(NODE_WATCHDOG_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            fail("node did not finish: " + command);
        }
        return new NodeRun(process.exitValue(), Files.readString(stdout, UTF_8), Files.readString(stderr, UTF_8));
    }

    private Path stubCalcJs(String exports) throws IOException {
        return write("calc-stub.js", "exports.main = function () {};\n" + exports + "\n");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(temporaryDirectory.resolve(name), content, UTF_8);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path), UTF_8);
    }

    private static List<Path> javaFilesUnder(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }

    private static String withoutComments(String kotlin) {
        return kotlin.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /** The golden file split on line feeds: the line number in the file is the index plus 1. */
    private static List<String> goldenLines() throws IOException {
        return new ArrayList<>(List.of(read(GOLDEN).split("\n", -1)));
    }

    private static String[] goldenLine(int lineNumber) throws IOException {
        return goldenLines().get(lineNumber - 1).split("\t", -1);
    }

    private static List<String[]> goldenRows() throws IOException {
        List<String[]> rows = new ArrayList<>();
        List<String> lines = goldenLines();
        for (String line : lines.subList(1, lines.size())) {
            if (!line.isEmpty()) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    private static int firstLineWhere(Predicate<String[]> condition) throws IOException {
        List<String[]> rows = goldenRows();
        for (int index = 0; index < rows.size(); index++) {
            if (condition.test(rows.get(index))) {
                return index + 2;
            }
        }
        return fail("No golden case matches.");
    }

    private static int lastLineWhere(Predicate<String[]> condition) throws IOException {
        List<String[]> rows = goldenRows();
        for (int index = rows.size() - 1; index >= 0; index--) {
            if (condition.test(rows.get(index))) {
                return index + 2;
            }
        }
        return fail("No golden case matches.");
    }

    private Path goldenCopyAltering(int lineNumber, int column) throws IOException {
        return goldenCopyWith(lineNumber, column, goldenLine(lineNumber)[column] + " altéré");
    }

    private Path goldenCopyWith(int lineNumber, int column, String value) throws IOException {
        List<String> lines = goldenLines();
        String[] columns = lines.get(lineNumber - 1).split("\t", -1);
        columns[column] = value;
        lines.set(lineNumber - 1, String.join("\t", columns));
        return write("golden-altered.tsv", String.join("\n", lines));
    }

    /** The summary of GoldenOutputsTest.comparable: length in UTF-16 units, digest of the UTF-8 bytes. */
    private static String comparable(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8));
        return "len=" + value.length() + " sha256=" + HexFormat.of().formatHex(digest);
    }
}
