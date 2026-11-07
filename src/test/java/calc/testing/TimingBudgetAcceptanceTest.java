package calc.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * The time budgets are promises measured on the reference machine and they do not change; slower shared machines apply a
 * documented tolerance factor, read from the environment variable {@code CALC_TIMING_FACTOR}. Everything here goes through the
 * shared test class {@code calc.testing.TimingBudget}, whose expected surface is:
 *
 * <ul>
 *   <li>{@code double parseFactor(String)}: the pure reading of a text (null or empty: 1; not a finite number, or below 1: failure);
 *   <li>{@code double factor()}: the same, applied to the variable of the environment;
 *   <li>{@code long scaled(long)} and {@code Duration scaled(Duration)}: a budget multiplied by the factor;
 *   <li>{@code String message(String what, double measured, long budget, TimeUnit unit)}: the text of an exceeded budget;
 *   <li>{@code void assertWithin(String what, double measured, long budget, TimeUnit unit)}: fails unless {@code measured} is
 *       strictly below the scaled budget;
 *   <li>{@code assertCompletesWithin(Duration, ThrowingSupplier)} and {@code assertCompletesWithin(Duration, Executable)}: the
 *       replacement of {@code assertTimeoutPreemptively}, which cannot name the reference budget or the factor by itself.
 * </ul>
 *
 * <p>The class is called by reflection on purpose: a direct reference would stop the whole test source set from compiling until
 * it exists, and every other test would fail with it. The real variable is proved in a child process, so that the environment of
 * the test process, set to 3 in the CI, changes nothing here.
 *
 * <p>The scan below also covers the budgets that no constant names (for instance {@code median < 1000}, the one that failed on
 * Windows) and {@code assertTimeoutPreemptively}. It leaves alone the bounds of computation and the patience of a wait
 * ({@code deadline}, {@code waitFor(90, ...)}), which only stop a test that hangs and promise nothing.
 */
@Tag("acceptance")
class TimingBudgetAcceptanceTest {
    private static final String TOOL = "calc.testing.TimingBudget";
    private static final String VARIABLE = "CALC_TIMING_FACTOR";
    private static final Path SOURCES = Path.of("src", "test", "java");
    private static final Path TESTS_DOC = Path.of("docs", "TESTS.md");
    private static final Path CI_DOC = Path.of("docs", "CI.md");
    private static final Path CI = Path.of(".github", "workflows", "ci.yml");
    private static final Path RELEASE = Path.of(".github", "workflows", "release.yml");

    private static final Set<String> NOT_SCANNED = Set.of("TimingBudget.java", "TimingBudgetAcceptanceTest.java");
    private static final Pattern BUDGET_CONSTANT = Pattern.compile("\\b[A-Z0-9_]*BUDGET[A-Z0-9_]*\\b");
    private static final Pattern BARE_TIMEOUT = Pattern.compile("\\bassertTimeout(?:Preemptively)?\\s*\\(|@Timeout\\b");
    private static final String MEASURE =
            "(?:\\w*(?i:millis|nanos|median|best|worst|elapsed|percentile|took)\\w*|sorted\\[[^\\]]*\\]|nanoTime\\(\\)[^<>=]*?)\\s*";
    private static final String FIXED_DURATION = "\\s*(?:\\(?\\d|Duration\\.|[A-Z][A-Z0-9_]*\\b|\\w*(?i:budget|limit)\\w*)";
    private static final Pattern MEASURE_BELOW = Pattern.compile(MEASURE + "<=?" + FIXED_DURATION);
    private static final Pattern MEASURE_ABOVE = Pattern.compile(MEASURE + ">=?" + FIXED_DURATION);

    /** The bound of a computation that happens to be called a budget: bits of precision, not a duration. */
    private static final Map<String, Set<String>> COMPUTATION_BOUNDS = Map.of("CertifiedValueProperties.java", Set.of("BUDGET"));

    private static final Map<String, List<String>> LISTED_CONSTANTS = listedConstants();
    private static final Map<String, List<String>> UNNAMED_BUDGETS = unnamedBudgets();

    private static final Map<String, Map<String, String>> PROBES = new ConcurrentHashMap<>();
    private static final String ABSENT = "<absent>";

    private static Map<String, List<String>> listedConstants() {
        Map<String, List<String>> constants = new LinkedHashMap<>();
        constants.put("calc/engine/InputFuzzProperties.java", List.of("static final long BUDGET_NANOS = 100_000_000L;"));
        constants.put("calc/expression/ExpressionFuzzProperties.java", List.of("static final long BUDGET_NANOS = 50_000_000L;"));
        constants.put("calc/notebook/NotebookPerformanceTest.java", List.of("static final long BUDGET_MILLIS = 200;"));
        constants.put("calc/number/NumericLimitsTest.java", List.of("static final long BUDGET_NANOS = Duration.ofMillis(10).toNanos();"));
        constants.put("calc/persistence/HistoryJsonFuzzProperties.java", List.of("static final Duration BUDGET = Duration.ofSeconds(10);"));
        constants.put("calc/persistence/NotebookFileFuzzProperties.java", List.of("static final Duration BUDGET = Duration.ofSeconds(10);"));
        constants.put("calc/persistence/HostileFilesTest.java", List.of("static final long BUDGET_MILLIS = 2_000;"));
        constants.put("calc/persistence/NotebookHostileFilesTest.java", List.of("static final long BUDGET_MILLIS = 2_000;"));
        constants.put("calc/ui/notebook/BackgroundRecalculationAcceptanceTest.java",
                List.of("static final long EDT_BUDGET_MILLIS = 300;", "static final long CLOSE_BUDGET_SECONDS = 2;"));
        constants.put("calc/ui/notebook/ExactEngineAcceptanceTest.java", List.of("static final long BUDGET_MILLIS = 1_000;"));
        constants.put("calc/ui/notebook/NotebookFrameResponsivenessTest.java", List.of("static final long BUDGET_MILLIS = 100;"));
        constants.put("calc/ui/notebook/NotebookResponsivenessTest.java", List.of("static final long BUDGET_MILLIS = 100;"));
        constants.put("calc/ui/notebook/RobustnessAcceptanceTest.java", List.of("static final long BUDGET_MILLIS = 1_000;"));
        constants.put("calc/ui/notebook/NotebookRecalculationTest.java", List.of("static final long EDT_BUDGET_MILLIS = 300;"));
        return constants;
    }

    // Regular expressions on the code of the file: a budget written as a literal may become a constant, its value stays.
    private static Map<String, List<String>> unnamedBudgets() {
        Map<String, List<String>> budgets = new LinkedHashMap<>();
        budgets.put("calc/document/DocumentServiceTest.java", List.of("\\b2_?000L?\\b"));
        budgets.put("calc/engine/SizeLimitsTest.java", List.of("ACCEPTED_CASE_LIMIT = Duration\\.ofSeconds\\(5\\)",
                "Duration\\.ofMillis\\(100\\)", "Duration\\.ofSeconds\\(1\\)", "Duration\\.ofSeconds\\(2\\)", "Duration\\.ofMillis\\(50\\)"));
        budgets.put("calc/expression/ExpressionRobustnessTest.java", List.of("\\b5_?000_?000L?\\b"));
        budgets.put("calc/persistence/HostileFilesTest.java", List.of("\\b500L?\\b", "\\b5_?000L?\\b", "\\b15_?000L?\\b"));
        budgets.put("calc/persistence/LegacyTsvReaderTest.java", List.of("Duration\\.ofSeconds\\(2\\)"));
        budgets.put("calc/persistence/NotebookHostileFilesTest.java", List.of("\\b500L?\\b", "\\b60_?000L?\\b"));
        budgets.put("calc/ui/notebook/NotebookFrameResponsivenessTest.java", List.of("\\b1_?000L?\\b"));
        budgets.put("calc/ui/notebook/NotebookParityTest.java", List.of("Duration\\.ofSeconds\\(1\\)"));
        budgets.put("calc/ui/notebook/NotebookResponsivenessTest.java", List.of("\\b150L?\\b"));
        return budgets;
    }

    // ---------------------------------------------------------------------------------------------------- the factor

    @Test
    void anAbsentOrEmptyTextMeansTheReferenceMachine() throws Throwable {
        assertEquals(1.0, parseFactor(null));
        assertEquals(1.0, parseFactor(""));
    }

    @Test
    void aNumberFromOneUpwardsIsTheFactor() throws Throwable {
        assertEquals(1.0, parseFactor("1"));
        assertEquals(1.5, parseFactor("1.5"));
        assertEquals(2.5, parseFactor("2.5"));
        assertEquals(3.0, parseFactor("3"));
        assertEquals(10.0, parseFactor("10"));
    }

    @Test
    void aTextThatIsNotAFiniteNumberOrIsBelowOneFailsClearly() {
        // Below 1 the factor would make a budget stricter; infinity or NaN would switch every budget off without a word.
        for (String refused : List.of("abc", "trois", "3x", "NaN", "Infinity", "-Infinity", "0", "0.99", "0.5", "-1", "-3")) {
            Throwable failure = thrownBy(() -> parseFactor(refused), "the text " + refused);
            assertNamesTheVariableAndTheValue(failure, refused);
        }
    }

    @Test
    void theAbsentOrEmptyVariableOfTheEnvironmentLeavesTheBudgetsAsTheyAre() throws Exception {
        for (String value : List.of(ABSENT, "")) {
            Map<String, String> facts = probe(value);
            assertEquals(1.0, Double.parseDouble(facts.get("FACTOR")), "factor with the variable " + value + ": " + facts);
            assertEquals("100", facts.get("SCALED_LONG"), "with the variable " + value);
            assertEquals("PT10S", facts.get("SCALED_DURATION"), "with the variable " + value);
        }
    }

    @Test
    void theVariableOfTheEnvironmentIsTheFactorThatMultipliesTheBudget() throws Exception {
        Map<String, String> three = probe("3");
        assertEquals(3.0, Double.parseDouble(three.get("FACTOR")), () -> "factor: " + three);
        assertEquals("300", three.get("SCALED_LONG"), () -> "scaled: " + three);
        assertEquals("PT30S", three.get("SCALED_DURATION"), () -> "scaled: " + three);

        Map<String, String> twoAndAHalf = probe("2.5");
        assertEquals(2.5, Double.parseDouble(twoAndAHalf.get("FACTOR")), () -> "factor: " + twoAndAHalf);
        assertEquals("250", twoAndAHalf.get("SCALED_LONG"), () -> "scaled: " + twoAndAHalf);
        assertEquals("PT25S", twoAndAHalf.get("SCALED_DURATION"), () -> "scaled: " + twoAndAHalf);
    }

    @Test
    void aBadVariableFailsClearlyEveryTestThatReadsIt() throws Exception {
        for (String value : List.of("abc", "0.5")) {
            Map<String, String> facts = probe(value);
            // FACTOR_AGAIN: a value read in a static initialiser would give a NoClassDefFoundError that names nothing, the second time.
            for (String reading : List.of("FACTOR", "FACTOR_AGAIN", "SCALED_LONG", "SCALED_DURATION")) {
                String fact = facts.get(reading);
                assertTrue(fact != null && fact.startsWith("THROWN"), reading + " with the variable " + value + ": " + facts);
                assertTrue(fact.contains(VARIABLE), reading + " must name the variable: " + fact);
                assertTrue(fact.contains(value), reading + " must quote the value: " + fact);
            }
        }
    }

    // --------------------------------------------------------------------------------------------------- the budget

    @Test
    void onTheReferenceMachineTheBudgetIsTheOneOfTheConstant() throws Exception {
        Map<String, String> facts = probe(ABSENT);
        assertPasses(facts, "UNDER_REFERENCE");
        assertFailsTheTest(facts, "AT_REFERENCE"); // the existing tests say "<": a measure equal to the budget exceeds it
        assertFailsTheTest(facts, "ABOVE_REFERENCE");
        assertFailsTheTest(facts, "NANOS_ABOVE_REFERENCE");
        assertFailsTheTest(facts, "SECONDS_ABOVE_REFERENCE");
    }

    @Test
    void theFactorWidensTheBudgetAndNothingElse() throws Exception {
        Map<String, String> facts = probe("3");
        assertPasses(facts, "UNDER_REFERENCE");
        assertPasses(facts, "AT_REFERENCE");
        assertPasses(facts, "ABOVE_REFERENCE");
        assertPasses(facts, "NANOS_ABOVE_REFERENCE");
        assertPasses(facts, "SECONDS_ABOVE_REFERENCE");
        assertFailsTheTest(facts, "AT_SCALED");
        assertFailsTheTest(facts, "FAR_ABOVE");
    }

    @Test
    void anExceededBudgetGivesTheMeasureTheReferenceBudgetAndTheAppliedFactor() throws Exception {
        Map<String, String> factors = new LinkedHashMap<>();
        factors.put(ABSENT, "1");
        factors.put("3", "3");
        factors.put("2.5", "2[.,]5");
        for (Map.Entry<String, String> factor : factors.entrySet()) {
            Map<String, String> facts = probe(factor.getKey());
            assertFailsTheTest(facts, "FAR_ABOVE");
            for (String text : List.of(facts.get("FAR_ABOVE"), facts.get("MESSAGE"))) {
                assertTrue(text.contains("the probe"), "what was measured: " + text);
                assertShows(text, "(?<!\\d)777(?:[.,]0+)?(?!\\d)", "the measure 777");
                assertShows(text, "(?<!\\d)250(?:[.,]0+)?(?!\\d)", "the reference budget 250");
                assertShows(text, standalone(factor.getValue()), "the factor " + factor.getValue());
            }
        }
    }

    @Test
    void aTimeoutIsScaledAndItsFailureNamesTheReferenceBudgetAndTheFactor() throws Exception {
        Map<String, String> reference = probe(ABSENT);
        assertFailsTheTest(reference, "TIMEOUT_WIDENED"); // 3 s of work for a budget of 2 s
        assertTimeoutFailure(reference.get("TIMEOUT_EXCEEDED"), 200, "1");
        assertTimeoutFailure(reference.get("TIMEOUT_SUPPLIER_EXCEEDED"), 200, "1");

        Map<String, String> three = probe("3");
        assertPasses(three, "TIMEOUT_WIDENED"); // 3 s of work for a budget of 2 s times 3
        assertTimeoutFailure(three.get("TIMEOUT_EXCEEDED"), 600, "3");
        assertTimeoutFailure(three.get("TIMEOUT_SUPPLIER_EXCEEDED"), 600, "3");
    }

    @Test
    void aTimedActionThatEndsInTimeGivesItsResult() throws Exception {
        Map<String, String> facts = probe(ABSENT);
        assertEquals("computed", facts.get("TIMEOUT_SUPPLIER"), () -> "the result of the action: " + facts);
        assertPasses(facts, "TIMEOUT_EXECUTABLE");
    }

    @Test
    void theFactorIsATestMatter() throws IOException {
        assertTrue(Files.isRegularFile(SOURCES.resolve("calc/testing/TimingBudget.java")),
                "the shared class lives with the other test helpers, in src/test/java/calc/testing");
        assertFalse(Files.exists(Path.of("src/main/java/calc/testing/TimingBudget.java")), "not in the product");
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains(VARIABLE), file + ": the product does not read the factor");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- the sources

    @Test
    void everyListedConstantKeepsItsValueAndGoesThroughTimingBudget() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Map.Entry<String, List<String>> file : LISTED_CONSTANTS.entrySet()) {
            String code = singleSpaced(code(Files.readString(SOURCES.resolve(file.getKey()), StandardCharsets.UTF_8)));
            for (String declaration : file.getValue()) {
                if (!code.contains(declaration)) {
                    faults.add(file.getKey() + ": the declaration « " + declaration + " » has changed");
                }
            }
            if (!code.contains("TimingBudget.")) {
                faults.add(file.getKey() + ": never goes through TimingBudget");
            }
        }
        assertEquals(List.of(), faults);
    }

    @Test
    void everyBudgetWithoutAListedConstantKeepsItsValueAndGoesThroughTimingBudget() throws IOException {
        List<String> faults = new ArrayList<>();
        for (Map.Entry<String, List<String>> file : UNNAMED_BUDGETS.entrySet()) {
            String code = singleSpaced(code(Files.readString(SOURCES.resolve(file.getKey()), StandardCharsets.UTF_8)));
            for (String value : file.getValue()) {
                if (!Pattern.compile(value).matcher(code).find()) {
                    faults.add(file.getKey() + ": the budget " + value + " has changed");
                }
            }
            if (!code.contains("TimingBudget.")) {
                faults.add(file.getKey() + ": never goes through TimingBudget");
            }
        }
        assertEquals(List.of(), faults);
    }

    @Test
    void noTimeBudgetIsComparedToAMeasureWithoutTimingBudget() throws IOException {
        List<String> faults = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                if (!NOT_SCANNED.contains(name)) {
                    scanned++;
                    for (String fault : scan(name, Files.readString(file, StandardCharsets.UTF_8))) {
                        faults.add(SOURCES.relativize(file).toString().replace('\\', '/') + ": " + fault);
                    }
                }
            }
        }
        assertTrue(scanned >= 100, "The scan must really read the sources (read " + scanned + " files).");
        assertEquals("", String.join("\n", faults), faults.size() + " budgets do not go through TimingBudget"
                + " (written TimingBudget.assertWithin, .assertCompletesWithin, .scaled or .message, qualified):");
    }

    @Test
    void theScanRecognisesTheFormsItLooksFor() {
        for (String faulty : List.of(
                "assertTrue(best < BUDGET_MILLIS, \"took\");",
                "assertTrue(took < BUDGET_NANOS, () -> \"took\");",
                "assertTrue(Long.parseLong(facts.get(\"EDT_MEDIAN_MILLIS\")) < EDT_BUDGET_MILLIS, () -> \"answers\");",
                "for (int run = 0; run < 3 && best >= EDT_BUDGET_MILLIS; run++) {",
                "boolean gone = process.waitFor(CLOSE_BUDGET_SECONDS, TimeUnit.SECONDS);",
                "assertTrue(gone, \"still there \" + CLOSE_BUDGET_SECONDS + \" s\");",
                "Duration limit = BUDGET;",
                "assertTimeoutPreemptively(BUDGET, () -> {",
                "assertTimeoutPreemptively(Duration.ofMillis(100), () -> compute());",
                "assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT,\n        () -> compute());",
                "assertTimeout(Duration.ofSeconds(1), () -> compute());",
                "assertTimeoutPreemptively(TimingBudget.scaled(BUDGET), () -> compute());", // the failure would not name the factor
                "@Timeout(5) void aTest() {",
                "assertTrue(median < 1000, \"median\");",
                "assertTrue(sorted[RUNS / 2] < 150, \"median\");",
                "assertTrue(sorted[0] <= 150, \"best\");",
                "assertTrue(millis < 500, \"refused\");",
                "assertTrue(worst < 60_000, \"slowest\");",
                "assertTrue(best < 5_000_000L, \"took\");",
                "assertTrue(best < Duration.ofMillis(50).toNanos(),\n        \"took\");",
                "assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, \"quickly\");",
                "assertTrue(usualMillis < 5_000 && worstMillis < 15_000, \"both\");",
                "assertFalse(best >= 100, \"took\");",
                "assertTrue(best < TimingBudget.scaled(BUDGET_MILLIS), \"the message names neither the budget nor the factor\");",
                "assertTrue(best < limit, \"took\");")) {
            assertFalse(scan("Sample.java", faulty).isEmpty(), faulty);
        }
        assertFalse(scan("Sample.java", "if (magnitude >= BUDGET - MAX_BITS) {").isEmpty(), "a time budget anywhere else");
    }

    @Test
    void theScanLeavesTheOtherFormsAlone() {
        for (String correct : List.of(
                "private static final long BUDGET_MILLIS = 100;",
                "private static final long BUDGET_NANOS = Duration.ofMillis(10).toNanos();",
                "private static final Duration BUDGET = Duration.ofSeconds(10);",
                "TimingBudget.assertWithin(\"took\", best, BUDGET_MILLIS, TimeUnit.MILLISECONDS);",
                "TimingBudget.assertWithin(\"median\", median, 1000, TimeUnit.MILLISECONDS);",
                "for (int run = 0; run < 3 && best >= TimingBudget.scaled(EDT_BUDGET_MILLIS); run++) {",
                "boolean gone = process.waitFor(TimingBudget.scaled(CLOSE_BUDGET_SECONDS), TimeUnit.SECONDS);",
                "assertTrue(gone, () -> TimingBudget.message(\"closed\", 2, CLOSE_BUDGET_SECONDS, TimeUnit.SECONDS));",
                "TimingBudget.assertCompletesWithin(BUDGET, () -> compute());",
                "TimingBudget.assertCompletesWithin(Duration.ofMillis(100), () -> compute());",
                "import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;",
                // The bounds of computation.
                "assertTrue(OutwardRounding.fitsBudget(bound));",
                "assertTrue(digits < NumericLimits.MAX_DIGITS, \"digits\");",
                "assertTrue(bytes.length <= StorageLimits.MAX_FILE_BYTES);",
                "assertFalse(worstFile.length > StorageLimits.MAX_FILE_BYTES);",
                // The patience of a wait, and what is not a budget.
                "assertTrue(System.nanoTime() < deadline, \"the rows never all appeared\");",
                "assertTrue(process.waitFor(90, TimeUnit.SECONDS), \"the process did not end\");",
                "while (afterTheCrash.isReadOnly() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10)) {",
                "assertTrue(sorted.size() > 10, \"the wait was sampled\");",
                "long millis = (System.nanoTime() - start) / 1_000_000;",
                "assertTrue(best > 0, \"a measure was taken\");",
                // Neither a comment nor a text is code.
                "// assertTrue(best < BUDGET_MILLIS, \"took\");",
                "/* best < 1000 and BUDGET */",
                "String text = \"assertTrue(best < 1000); BUDGET_MILLIS\";",
                "char quote = '\"'; /* BUDGET */ char other = '\\'';")) {
            assertEquals(List.of(), scan("Sample.java", correct), correct);
        }
        assertEquals(List.of(), scan("CertifiedValueProperties.java", "if (magnitude >= BUDGET - MAX_BITS) {"), "a bound of computation");
    }

    // ----------------------------------------------------------------------------------------------- the workflows

    @Test
    void theCiDefinesTheFactorForEveryJobThatRunsTheTests() throws IOException {
        assertTestJobsDefineTheFactor(CI, Set.of("check", "conformity"));
    }

    @Test
    void theReleaseDefinesTheFactorForEveryJobThatRunsTheTests() throws IOException {
        assertTestJobsDefineTheFactor(RELEASE, Set.of("build", "conformity"));
    }

    @Test
    void theTriggersAndTheOtherJobsAreLeftAlone() throws IOException {
        List<String> ci = meaningfulLines(CI);
        assertEquals(List.of("on:", "  workflow_dispatch:"), onBlock(ci), "the triggers of ci.yml");
        List<String> releaseTriggers = onBlock(meaningfulLines(RELEASE));
        assertEquals(List.of("on:", "  push:", "    tags: ['v*.*.*']", "  workflow_dispatch:"),
                releaseTriggers.stream().filter(line -> !line.startsWith("    ") || line.contains("tags:")).toList(),
                "the triggers of release.yml");
        for (Path workflow : List.of(CI, RELEASE)) {
            for (String line : meaningfulLines(workflow)) {
                if (line.contains(VARIABLE)) {
                    assertEquals("      " + VARIABLE + ": \"3\"", line, workflow + ": the factor is only a key of a job's env block");
                }
            }
            for (Map.Entry<String, List<String>> job : jobs(workflow).entrySet()) {
                if (!runsTheTests(job.getValue())) {
                    assertFalse(String.join("\n", job.getValue()).contains(VARIABLE), workflow + ", job " + job.getKey() + " runs no test");
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------- the docs

    @Test
    void theTestsDocumentationExplainsTheBudgetsInTheirSection() throws IOException {
        String section = budgetSection(Files.readString(TESTS_DOC, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        assertExplainsTheFactor("docs/TESTS.md, section des budgets", section);
    }

    @Test
    void theCiDocumentationExplainsTheFactor() throws IOException {
        assertExplainsTheFactor("docs/CI.md", Files.readString(CI_DOC, StandardCharsets.UTF_8));
    }

    private static void assertExplainsTheFactor(String where, String text) {
        String flat = singleSpaced(text);
        assertTrue(flat.contains(VARIABLE), where + " names the variable");
        assertTrue(flat.toLowerCase(Locale.ROOT).contains("machine de référence"), where + " says what the machine of reference is");
        assertShows(flat, "(?i)(?:facteur|" + VARIABLE + ")[^.\\n]{0,120}" + standalone("3")
                + "|" + standalone("3") + "[^.\\n]{0,120}(?:facteur|" + VARIABLE + ")", where + ": the value of the factor in the CI, 3");
        assertShows(flat, "(?i)(?:\\bpas\\b|jamais|aucun|sans)[^.\\n]{0,80}relev", where + ": the budgets are not raised");
        assertShows(flat, "(?i)promesse|parce que|\\bcar\\b|pourquoi", where + ": why they are not raised");
    }

    // The section of the first heading that talks about budgets, up to the next heading of the same or a higher level.
    private static String budgetSection(String document) {
        Matcher heading = Pattern.compile("(?m)^(#{2,6})\\s+.*[Bb]udget.*$").matcher(document);
        assertTrue(heading.find(), "docs/TESTS.md must have a heading about the time budgets");
        String rest = document.substring(heading.end());
        Matcher next = Pattern.compile("(?m)^#{1," + heading.group(1).length() + "}\\s").matcher(rest);
        return next.find() ? rest.substring(0, next.start()) : rest;
    }

    // ----------------------------------------------------------------------------------------------------- helpers

    private static void assertTestJobsDefineTheFactor(Path workflow, Set<String> expected) throws IOException {
        Map<String, List<String>> jobs = jobs(workflow);
        Set<String> testJobs = new TreeSet<>();
        for (Map.Entry<String, List<String>> job : jobs.entrySet()) {
            if (runsTheTests(job.getValue())) {
                testJobs.add(job.getKey());
            }
        }
        assertEquals(new TreeSet<>(expected), testJobs, workflow + ": the jobs that run the tests");
        for (String name : testJobs) {
            assertTrue(jobEnv(jobs.get(name)).contains(VARIABLE + ": \"3\""), workflow + ", job " + name + " must have " + VARIABLE
                    + ": \"3\" in its env block, it has " + jobEnv(jobs.get(name)));
        }
    }

    private static boolean runsTheTests(List<String> job) {
        return job.stream().anyMatch(line -> line.matches(".*\\./gradlew\\b.*\\b(?:check|test|conformity)\\b.*"));
    }

    // The keys of the env block of the job itself (4 spaces), which applies to all of its steps.
    private static List<String> jobEnv(List<String> job) {
        List<String> env = new ArrayList<>();
        int start = job.indexOf("    env:");
        for (int index = start + 1; start >= 0 && index < job.size() && job.get(index).startsWith("      "); index++) {
            env.add(job.get(index).strip());
        }
        return env;
    }

    private static Map<String, List<String>> jobs(Path workflow) throws IOException {
        Map<String, List<String>> jobs = new TreeMap<>();
        List<String> current = null;
        boolean inside = false;
        for (String line : meaningfulLines(workflow)) {
            if (line.equals("jobs:")) {
                inside = true;
            } else if (inside && line.matches("^  [A-Za-z][\\w-]*:\\s*$")) {
                current = new ArrayList<>();
                jobs.put(line.strip().replace(":", ""), current);
            } else if (inside && current != null) {
                current.add(line);
            }
        }
        assertTrue(jobs.size() >= 3, workflow + ": the jobs must be read, found " + jobs.keySet());
        return jobs;
    }

    private static List<String> onBlock(List<String> lines) {
        List<String> block = new ArrayList<>();
        for (String line : lines) {
            if (line.equals("on:") || (!block.isEmpty() && line.startsWith(" "))) {
                block.add(line);
            } else if (!block.isEmpty()) {
                break;
            }
        }
        return block;
    }

    private static List<String> meaningfulLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.stripLeading().startsWith("#")).toList();
    }

    /**
     * What is wrong in this source, statement by statement. A budget in a statement must come with {@code TimingBudget.}; an
     * assertion on a measure must build its text with {@code TimingBudget.message} (or be replaced by
     * {@code TimingBudget.assertWithin}), since its failure has to give the measure, the reference budget and the factor; and
     * {@code assertTimeoutPreemptively} leaves its place to {@code TimingBudget.assertCompletesWithin}, for the same reason.
     */
    static List<String> scan(String fileName, String source) {
        Set<String> exempted = COMPUTATION_BOUNDS.getOrDefault(fileName, Set.of());
        List<String> faults = new ArrayList<>();
        for (String raw : code(source).split("[;{}]")) {
            String statement = singleSpaced(raw).strip();
            if (statement.isEmpty() || statement.startsWith("import ") || statement.startsWith("package ")) {
                continue;
            }
            boolean bareTimeout = BARE_TIMEOUT.matcher(statement).find();
            boolean assertion = statement.contains("assertTrue(") || statement.contains("assertFalse(");
            boolean measureCompared = (statement.contains("assertTrue(") && MEASURE_BELOW.matcher(statement).find())
                    || (statement.contains("assertFalse(") && MEASURE_ABOVE.matcher(statement).find());
            boolean budgetUsed = usesABudgetConstant(statement, exempted);
            boolean fault;
            if (bareTimeout) {
                fault = true;
            } else if (assertion && (budgetUsed || measureCompared)) {
                fault = !statement.contains("TimingBudget.message(");
            } else {
                fault = budgetUsed && !statement.contains("TimingBudget.");
            }
            if (fault) {
                faults.add(statement);
            }
        }
        return faults;
    }

    // A constant called *BUDGET*, its own declaration left out.
    private static boolean usesABudgetConstant(String statement, Set<String> exempted) {
        Matcher declared = Pattern.compile("^(?:[\\w.]+ )*(?:long|int|double|Duration) ([A-Z0-9_]+) =").matcher(statement);
        String declaredName = declared.find() ? declared.group(1) : null;
        Matcher constant = BUDGET_CONSTANT.matcher(declaredName == null ? statement : statement.substring(declared.end()));
        while (constant.find()) {
            if (!exempted.contains(constant.group())) {
                return true;
            }
        }
        return false;
    }

    // The source without its comments and with its texts and characters emptied: only the code remains.
    private static String code(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                code.append(' ');
            } else if (source.startsWith("\"\"\"", index)) {
                index = afterLiteral(source, index + 3, "\"\"\"");
                code.append("\"\"");
            } else if (current == '"' || current == '\'') {
                index = afterLiteral(source, index + 1, String.valueOf(current));
                code.append(current).append(current);
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    private static int afterLiteral(String source, int from, String closing) {
        int index = from;
        while (index < source.length() && !source.startsWith(closing, index)) {
            index += source.charAt(index) == '\\' ? 2 : 1;
        }
        return Math.min(source.length(), index + closing.length());
    }

    private static String singleSpaced(String text) {
        return text.replaceAll("\\s+", " ");
    }

    // A number on its own: 3 or 3.0 or 3,0, but not 300, 1.3 or 3,5. A sentence may end right after it.
    private static String standalone(String number) {
        return "(?<![\\d.,])(?:" + number + ")(?:[.,]0+)?(?!\\d|[.,]\\d)";
    }

    private static void assertShows(String text, String regex, String what) {
        assertTrue(Pattern.compile(regex).matcher(text).find(), "must show " + what + ": " + text);
    }

    private static void assertPasses(Map<String, String> facts, String fact) {
        assertEquals("passed", facts.get(fact), () -> fact + ": " + facts);
    }

    private static void assertFailsTheTest(Map<String, String> facts, String fact) {
        String value = facts.get(fact);
        assertTrue(value != null && value.startsWith("THROWN assertion"), () -> fact + " must be a failed assertion: " + facts);
    }

    // The failure of a timeout: the reference budget of 200 ms, the factor, and a measure of at least the scaled budget.
    private static void assertTimeoutFailure(String failure, long scaledMillis, String factor) {
        assertTrue(failure != null && failure.startsWith("THROWN assertion"), "must be a failed assertion: " + failure);
        assertShows(failure, "(?<!\\d)200(?!\\d)|PT0\\.2S|(?<![\\d.,])0[.,]2 ?s", "the reference budget of 200 ms");
        assertShows(failure, standalone(factor), "the factor " + factor);
        Matcher number = Pattern.compile("(?<![\\d.,])\\d+").matcher(failure);
        boolean measured = false;
        while (number.find() && !measured) {
            measured = number.group().length() < 12 && Long.parseLong(number.group()) >= scaledMillis;
        }
        assertTrue(measured, "must show a measure of at least " + scaledMillis + " ms: " + failure);
    }

    private static void assertNamesTheVariableAndTheValue(Throwable failure, String value) {
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(VARIABLE), "must name the variable: " + failure);
        assertTrue(message.contains(value), "must quote the value: " + failure);
    }

    private static double parseFactor(String text) throws Throwable {
        return ((Number) call("parseFactor", new Class<?>[] {String.class}, text)).doubleValue();
    }

    private static Throwable thrownBy(ThrowingAction action, String what) {
        try {
            action.run();
        } catch (Throwable failure) {
            if (failure instanceof ToolMissing) {
                throw (ToolMissing) failure;
            }
            return failure;
        }
        return fail(what + " must make the test that reads it fail");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Throwable;
    }

    // Not an AssertionError: a missing class or method must not pass for a budget that was exceeded.
    private static final class ToolMissing extends Error {
        private static final long serialVersionUID = 1L;

        ToolMissing(String message) {
            super(message);
        }
    }

    private static Object call(String name, Class<?>[] types, Object... arguments) throws Throwable {
        Class<?> tool;
        try {
            tool = Class.forName(TOOL);
        } catch (ClassNotFoundException missing) {
            throw new ToolMissing(TOOL + " does not exist");
        }
        Method method;
        try {
            method = tool.getMethod(name, types);
        } catch (NoSuchMethodException missing) {
            throw new ToolMissing(TOOL + " has no public method " + name + Arrays.toString(types));
        }
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException thrown) {
            throw thrown.getCause();
        }
    }

    // The facts printed by a child process that has this value in its environment. Each value is only run once.
    private static Map<String, String> probe(String value) throws Exception {
        Map<String, String> known = PROBES.get(value);
        if (known != null) {
            return known;
        }
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The timeouts take seconds of real time: they run where a test reads them.
        boolean timeouts = value.equals(ABSENT) || value.equals("3");
        ProcessBuilder builder = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName(),
                timeouts ? "timeouts" : "quick").redirectErrorStream(true);
        builder.environment().remove(VARIABLE);
        if (!value.equals(ABSENT)) {
            builder.environment().put(VARIABLE, value);
        }
        Process process = builder.start();
        String output;
        try {
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the probe did not end");
        } finally {
            process.destroyForcibly();
        }
        Map<String, String> facts = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            int equal = line.indexOf('=');
            if (equal > 0) {
                facts.put(line.substring(0, equal), line.substring(equal + 1));
            }
        }
        assertTrue(facts.containsKey("FACTOR"), () -> "no fact from the probe: " + output);
        PROBES.put(value, facts);
        return facts;
    }

    /** Runs in a child process, whose environment holds the value under test: one line per fact. */
    public static final class Probe {
        private static final TimeUnit MILLIS = TimeUnit.MILLISECONDS;
        private static final Class<?>[] WITHIN = {String.class, double.class, long.class, TimeUnit.class};
        private static final Class<?>[] WITH_EXECUTABLE = {Duration.class, Executable.class};
        private static final Class<?>[] WITH_SUPPLIER = {Duration.class, ThrowingSupplier.class};
        private static final long LONG_SLEEP_MILLIS = 30_000;

        private static PrintStream out;

        private Probe() {
        }

        public static void main(String[] arguments) {
            out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            fact("FACTOR", () -> call("factor", new Class<?>[0]));
            fact("FACTOR_AGAIN", () -> call("factor", new Class<?>[0]));
            fact("SCALED_LONG", () -> call("scaled", new Class<?>[] {long.class}, 100L));
            fact("SCALED_DURATION", () -> call("scaled", new Class<?>[] {Duration.class}, Duration.ofSeconds(10)));
            fact("MESSAGE", () -> call("message", WITHIN, "the probe", 777.0, 250L, MILLIS));
            within("UNDER_REFERENCE", 249.5, 250L, MILLIS);
            within("AT_REFERENCE", 250.0, 250L, MILLIS);
            within("ABOVE_REFERENCE", 749.0, 250L, MILLIS);
            within("AT_SCALED", 750.0, 250L, MILLIS);
            within("FAR_ABOVE", 777.0, 250L, MILLIS);
            within("NANOS_ABOVE_REFERENCE", 120_000_000.0, 100_000_000L, TimeUnit.NANOSECONDS);
            within("SECONDS_ABOVE_REFERENCE", 3.0, 2L, TimeUnit.SECONDS);
            if (arguments[0].equals("timeouts")) {
                Executable sleeps = () -> Thread.sleep(LONG_SLEEP_MILLIS);
                ThrowingSupplier<String> computes = () -> "computed";
                ThrowingSupplier<String> sleepsThenComputes = () -> {
                    Thread.sleep(LONG_SLEEP_MILLIS);
                    return "computed";
                };
                Executable doesNothing = () -> { };
                Executable threeSeconds = () -> Thread.sleep(3_000);
                fact("TIMEOUT_SUPPLIER", () -> call("assertCompletesWithin", WITH_SUPPLIER, Duration.ofSeconds(60), computes));
                done("TIMEOUT_EXECUTABLE", () -> call("assertCompletesWithin", WITH_EXECUTABLE, Duration.ofSeconds(60), doesNothing));
                done("TIMEOUT_EXCEEDED", () -> call("assertCompletesWithin", WITH_EXECUTABLE, Duration.ofMillis(200), sleeps));
                done("TIMEOUT_SUPPLIER_EXCEEDED", () -> call("assertCompletesWithin", WITH_SUPPLIER, Duration.ofMillis(200), sleepsThenComputes));
                done("TIMEOUT_WIDENED", () -> call("assertCompletesWithin", WITH_EXECUTABLE, Duration.ofSeconds(2), threeSeconds));
            }
            out.flush();
            // A timed action that was interrupted may leave a thread behind: it must not hold the process.
            System.exit(0);
        }

        private static void within(String name, double measured, long budget, TimeUnit unit) {
            done(name, () -> call("assertWithin", WITHIN, "the probe", measured, budget, unit));
        }

        private static void done(String name, Reading action) {
            fact(name, () -> {
                action.read();
                return "passed";
            });
        }

        private static void fact(String name, Reading reading) {
            String value;
            try {
                value = String.valueOf(reading.read());
            } catch (Throwable thrown) {
                value = "THROWN " + (thrown instanceof AssertionError ? "assertion" : thrown.getClass().getName()) + ": " + thrown.getMessage();
            }
            out.println(name + "=" + value.replaceAll("\\R", " "));
        }

        @FunctionalInterface
        private interface Reading {
            Object read() throws Throwable;
        }
    }
}
