package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The differential test of the web version: every notebook of the corpus, in the 4 modes, once by WebNotebook on the JVM
 * and once by calc.js under Node, and not one string may differ. There is no list of tolerated gaps.
 *
 * <p>The class path of webTest holds the compiled web module, not the core: WebNotebook runs in a class loader of its own,
 * over the classes that Gradle compiled for generateJavaScript. Notebooks cross in files and in Base64: neither the code
 * page of the system nor the arguments of a process can hide a difference.
 */
@DisplayName("Parité : la JVM et Node donnent les mêmes chaînes sur tout le corpus")
class ParityAcceptanceTest {
    private static final String CALC_JS = "web/build/generated/teavm/js/calc.js";
    private static final String RUN_PARITY = "web/parity/run-parity.js";
    private static final Path CORPUS = Path.of("build", "web-parity", "corpus.txt");
    private static final Path SEED_FILE = Path.of("build", "web-parity", "corpus.seed");
    private static final Path NODE_OUTPUT = Path.of("web", "build", "web-parity", "js.txt");
    private static final Path WEB_CLASSES = Path.of("web", "build", "classes", "java", "main");
    private static final Path CORE_CLASSES = Path.of("build", "classes", "java", "main");
    private static final Path CHARACTER_COMPAT = Path.of("web", "src", "main", "java", "calc", "web", "compat", "CharacterCompat.java");
    private static final String ROOT_BUILD = "build.gradle.kts";
    private static final String WEB_BUILD = "web/build.gradle.kts";
    private static final String ESSAY = "docs/essais/teavm-essai.md";

    private static final String SEED_PROPERTY = "calc.web.parity.seed";
    private static final String CORPUS_PROPERTY = "calc.web.parity.corpus";
    private static final String UNICODE_LINE = "Parité Unicode :";
    private static final String GAP_OBSERVED = "écart constaté, CharacterCompat";
    private static final String NO_GAP = "aucun écart";

    private static final List<String> MODES = List.of("AUTO", "DECIMAL", "FRACTION", "SCIENTIFIC");
    private static final List<String> REQUIRED_STATUSES = List.of("EXACT", "APPROXIMATE", "ERROR");
    private static final Pattern STATUS = Pattern.compile("\"status\":\"([A-Z]+)\"");
    private static final Pattern TRIM_CALL = Pattern.compile("\\.trim(?:Start|End|Left|Right)?\\s*\\(");
    private static final Pattern REQUIRED_MODULE = Pattern.compile("\\brequire\\s*\\(\\s*['\"`]([^'\"`]+)['\"`]\\s*\\)");
    private static final Set<String> ALLOWED_MODULES = Set.of("fs", "path", "crypto", "node:fs", "node:path", "node:crypto");

    private static final int MINIMUM_LINES = 1000;
    private static final int MINIMUM_NOTEBOOKS = 50;
    private static final int SHOWN_GAPS = 20;
    private static final long NODE_WATCHDOG_MINUTES = 5;

    private static final String ALONE_A7CB = new String(Character.toChars(0xA7CB));
    private static final String ALONE_31EF = new String(Character.toChars(0x31EF));
    private static final String ALONE_2EBF0 = new String(Character.toChars(0x2EBF0));

    /** The replay of the whole corpus, done once for all the tests that read it. */
    private static ParityRun parityRun;

    @TempDir
    Path temporaryDirectory;

    private record NodeRun(int exitCode, String stdout, String stderr) {
    }

    private record ParityRun(String seed, List<Entry> corpus, List<String> jvm, List<String> node) {
    }

    private record Entry(String id, String text) {
    }

    // ---- The comparison: what the verdict of this test rests on, tried directly --------------------------------------------

    /**
     * Fails if one string differs between the two outputs, each line being {@code <id>\t<base64 UTF-8 of the 4 snapshots>}.
     * The message gives the seed and, for each gap, the notebook, the mode and the two values.
     */
    static void assertSameOutputs(List<String> jvm, List<String> node, String seed) {
        List<String> gaps = new ArrayList<>();
        if (jvm.size() != node.size()) {
            gaps.add("number of entries: JVM " + jvm.size() + ", Node " + node.size());
        }
        for (int index = 0; index < Math.max(jvm.size(), node.size()); index++) {
            if (index >= node.size()) {
                gaps.add("notebook " + idOf(jvm.get(index)) + ": no entry under Node");
            } else if (index >= jvm.size()) {
                gaps.add("notebook " + idOf(node.get(index)) + ": no entry on the JVM");
            } else {
                compareEntries(jvm.get(index), node.get(index), gaps);
            }
        }
        if (!gaps.isEmpty()) {
            List<String> shown = gaps.subList(0, Math.min(gaps.size(), SHOWN_GAPS));
            throw new AssertionError("Seed " + seed + ": " + gaps.size() + " gap(s) between the JVM and Node\n" + String.join("\n", shown));
        }
    }

    private static void compareEntries(String jvm, String node, List<String> gaps) {
        if (jvm.equals(node)) {
            return;
        }
        String id = idOf(jvm);
        if (!id.equals(idOf(node))) {
            gaps.add("notebook " + id + " on the JVM, notebook " + idOf(node) + " under Node at the same place");
            return;
        }
        String[] jvmSnapshots = snapshotsOf(jvm);
        String[] nodeSnapshots = snapshotsOf(node);
        for (int index = 0; index < Math.max(jvmSnapshots.length, nodeSnapshots.length); index++) {
            String jvmValue = index < jvmSnapshots.length ? jvmSnapshots[index] : "(absent)";
            String nodeValue = index < nodeSnapshots.length ? nodeSnapshots[index] : "(absent)";
            if (!jvmValue.equals(nodeValue)) {
                String mode = index < MODES.size() ? MODES.get(index) : "snapshot " + (index + 1);
                gaps.add("notebook " + id + ", mode " + mode + ": JVM « " + jvmValue + " », Node « " + nodeValue + " »");
            }
        }
    }

    private static String idOf(String entry) {
        int tab = entry.indexOf('\t');
        return tab < 0 ? entry : entry.substring(0, tab);
    }

    /** A line that is not an entry stands for itself, so that it shows in the message as one value that differs. */
    private static String[] snapshotsOf(String entry) {
        int tab = entry.indexOf('\t');
        if (tab < 0) {
            return new String[] {entry};
        }
        try {
            return new String(Base64.getDecoder().decode(entry.substring(tab + 1)), UTF_8).split("\n", -1);
        } catch (IllegalArgumentException notBase64) {
            return new String[] {entry};
        }
    }

    /** Counts the statuses of the lines of all the snapshots. A text in a snapshot has its quotes escaped: it cannot match. */
    static Map<String, Integer> statusCounts(List<String> entries) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String entry : entries) {
            for (String snapshot : snapshotsOf(entry)) {
                Matcher status = STATUS.matcher(snapshot);
                while (status.find()) {
                    counts.merge(status.group(1), 1, Integer::sum);
                }
            }
        }
        return counts;
    }

    /** A corpus reduced to errors proves nothing: exact, approximate and error results must all be among those compared. */
    static void assertEveryKindOfResultIsCompared(Map<String, Integer> counts, String seed) {
        for (String status : REQUIRED_STATUSES) {
            if (counts.getOrDefault(status, 0) < 1) {
                throw new AssertionError("Seed " + seed + ": no " + status + " among the compared results: " + counts);
            }
        }
    }

    @Test
    void twoEqualOutputsPass() {
        List<String> output = List.of(entry("a", snapshots("1", "2", "3", "4")), entry("b", snapshots("5", "6", "7", "8")));

        assertDoesNotThrow(() -> assertSameOutputs(output, List.copyOf(output), "42"));
    }

    @Test
    void oneCharacterOfOneModeFailsAndTheMessageGivesAllThatIsNeeded() {
        List<String> jvm = List.of(entry("hand-1", snapshots("≈ 1,5", "b", "c", "d")), entry("line-7", snapshots("e", "f", "g", "≈ 1,41421356237…")));
        for (int mode = 0; mode < MODES.size(); mode++) {
            String[] altered = {"e", "f", "g", "≈ 1,41421356237…"};
            altered[mode] = altered[mode] + "x";
            List<String> node = List.of(jvm.get(0), entry("line-7", snapshots(altered)));

            AssertionError error = assertThrows(AssertionError.class, () -> assertSameOutputs(jvm, node, "20261107"));

            String message = error.getMessage();
            assertTrue(message.contains("20261107"), () -> "The seed must be in the message: " + message);
            assertTrue(message.contains("line-7"), () -> "The notebook must be in the message: " + message);
            assertFalse(message.contains("hand-1"), () -> "The notebook that is equal must not be in the message: " + message);
            assertTrue(message.contains(MODES.get(mode)), "The mode must be in the message: " + message);
            assertTrue(message.contains("« " + altered[mode] + " »"), "The value of Node must be in the message: " + message);
            String expected = new String[] {"e", "f", "g", "≈ 1,41421356237…"}[mode];
            assertTrue(message.contains("« " + expected + " »"), "The value of the JVM must be in the message: " + message);
        }
    }

    /** The two blanks print alike, and only the comparison of the strings can tell them apart. */
    @Test
    void aDifferenceThatDoesNotShowStillFails() {
        List<String> jvm = List.of(entry("a", snapshots("1" + (char) 0x202F + "000", "b", "c", "d")));
        List<String> node = List.of(entry("a", snapshots("1" + (char) 0x00A0 + "000", "b", "c", "d")));

        assertThrows(AssertionError.class, () -> assertSameOutputs(jvm, node, "1"));
    }

    @Test
    void differentNumbersOfEntriesFailInBothDirections() {
        List<String> shorter = List.of(entry("a", snapshots("1", "2", "3", "4")));
        List<String> longer = List.of(shorter.get(0), entry("b", snapshots("5", "6", "7", "8")));

        for (List<List<String>> sides : List.of(List.of(shorter, longer), List.of(longer, shorter))) {
            String message = assertThrows(AssertionError.class, () -> assertSameOutputs(sides.get(0), sides.get(1), "123")).getMessage();

            assertTrue(message.contains("123"), () -> "The seed must be in the message: " + message);
            assertTrue(message.contains("JVM " + sides.get(0).size()), () -> "The count of the JVM must be in the message: " + message);
            assertTrue(message.contains("Node " + sides.get(1).size()), () -> "The count of Node must be in the message: " + message);
            assertTrue(message.contains("notebook b"), () -> "The entry without a counterpart must be named: " + message);
        }
    }

    @Test
    void anEmptyOutputAgainstAFullOneFails() {
        List<String> full = List.of(entry("a", snapshots("1", "2", "3", "4")));

        assertThrows(AssertionError.class, () -> assertSameOutputs(full, List.of(), "1"));
        assertThrows(AssertionError.class, () -> assertSameOutputs(List.of(), full, "1"));
    }

    @Test
    void theSameEntriesInAnotherOrderFail() {
        String first = entry("a", snapshots("1", "2", "3", "4"));
        String second = entry("b", snapshots("5", "6", "7", "8"));

        assertThrows(AssertionError.class, () -> assertSameOutputs(List.of(first, second), List.of(second, first), "1"));
    }

    @Test
    void aMissingSnapshotFails() {
        List<String> jvm = List.of(entry("a", snapshots("1", "2", "3", "4")));
        List<String> node = List.of(entry("a", snapshots("1", "2", "3")));

        String message = assertThrows(AssertionError.class, () -> assertSameOutputs(jvm, node, "1")).getMessage();

        assertTrue(message.contains("SCIENTIFIC"), () -> "The mode without a snapshot must be named: " + message);
    }

    @Test
    void aCorpusWithoutOneKindOfResultProvesNothing() {
        String exact = "{\"status\":\"EXACT\"}";
        String approximate = "{\"status\":\"APPROXIMATE\"}";
        String error = "{\"status\":\"ERROR\"}";

        List<List<String>> incomplete = List.of(
                List.of(error, error, error, error),
                List.of(exact, approximate, exact, approximate),
                List.of(exact, error, exact, error),
                List.of(approximate, error, approximate, error),
                List.of("{\"status\":\"EMPTY\"}", "{\"status\":\"COMMENT\"}", "{}", "{}"));
        for (List<String> texts : incomplete) {
            Map<String, Integer> counts = statusCounts(List.of(entry("a", snapshots(texts.toArray(String[]::new)))));
            String message = assertThrows(AssertionError.class, () -> assertEveryKindOfResultIsCompared(counts, "77")).getMessage();
            assertTrue(message.contains("77"), () -> "The seed must be in the message: " + message);
        }

        Map<String, Integer> complete = statusCounts(List.of(entry("a", snapshots(exact, approximate, error, error))));
        assertEquals(Map.of("EXACT", 1, "APPROXIMATE", 1, "ERROR", 2), complete);
        assertDoesNotThrow(() -> assertEveryKindOfResultIsCompared(complete, "77"));
    }

    @Test
    void theTextOfALineIsNeverCountedAsAStatus() {
        String escaped = "{\"text\":\"\\\"status\\\":\\\"EXACT\\\"\",\"status\":\"ERROR\"}";

        assertEquals(Map.of("ERROR", 1), statusCounts(List.of(entry("a", snapshots(escaped)))));
    }

    // ---- The replay of the corpus ------------------------------------------------------------------------------------------

    @Test
    void everyNotebookInEveryModeGivesTheSameStringOnTheJvmAndUnderNode() throws Exception {
        ParityRun run = parityRun();

        assertFalse(run.corpus().isEmpty(), "The corpus must not be empty.");
        assertEquals(run.corpus().size(), run.jvm().size(), "One entry per notebook on the JVM.");
        assertSameOutputs(run.jvm(), run.node(), run.seed());
    }

    @Test
    void theOutputOfNodeFollowsTheCorpusWithFourSnapshotsPerNotebookInTheOrderOfTheModes() throws Exception {
        ParityRun run = parityRun();

        assertEquals(run.corpus().size(), run.node().size(), "One entry per notebook under Node.");
        for (int index = 0; index < run.corpus().size(); index++) {
            String id = run.corpus().get(index).id();
            assertEquals(id, idOf(run.node().get(index)), "The output of Node follows the order of the corpus.");
            String[] snapshots = snapshotsOf(run.node().get(index));
            assertEquals(MODES.size(), snapshots.length, () -> id + " must have one snapshot per mode.");
            for (int mode = 0; mode < MODES.size(); mode++) {
                String start = "{\"mode\":\"" + MODES.get(mode) + "\",";
                assertTrue(snapshots[mode].startsWith(start), id + " snapshot " + mode + " must start with " + start);
            }
        }
    }

    @Test
    void theComparedResultsHoldExactApproximateAndErrorOnBothSides() throws Exception {
        ParityRun run = parityRun();

        Map<String, Integer> counts = statusCounts(run.jvm());
        System.out.println("Parité, graine " + run.seed() + ", " + run.corpus().size() + " carnets : " + counts);
        assertEveryKindOfResultIsCompared(counts, run.seed());
        assertEveryKindOfResultIsCompared(statusCounts(run.node()), run.seed());
    }

    @Test
    void theCorpusReplayedHoldsAllTheFamilies() throws Exception {
        List<Entry> corpus = parityRun().corpus();

        assertEquals(1, corpus.stream().filter(entry -> entry.id().equals("export")).count(), "The export notebook.");
        assertTrue(count(corpus, "line-") >= MINIMUM_LINES, () -> count(corpus, "line-") + " line-* entries.");
        assertTrue(count(corpus, "notebook-") >= MINIMUM_NOTEBOOKS, () -> count(corpus, "notebook-") + " notebook-* entries.");
        List<String> handLines = new ArrayList<>();
        corpus.stream().filter(entry -> entry.id().startsWith("hand-")).forEach(entry -> handLines.addAll(List.of(entry.text().split("\n", -1))));
        for (String alone : List.of(ALONE_A7CB, ALONE_31EF, ALONE_2EBF0)) {
            assertTrue(handLines.contains(alone), () -> "The corpus must hold U+" + Integer.toHexString(alone.codePointAt(0)) + " alone on a line.");
        }
    }

    // ---- The seed ------------------------------------------------------------------------------------------------------------

    @Test
    void theSeedFileHoldsTheSeedThatTheTestReceives() throws Exception {
        String seed = System.getProperty(SEED_PROPERTY);

        assertNotNull(seed, "web/build.gradle.kts must pass the system property " + SEED_PROPERTY + " to webTest.");
        assertDoesNotThrow(() -> Long.parseLong(seed), SEED_PROPERTY + " must be a long.");
        assertTrue(Files.isRegularFile(SEED_FILE), SEED_FILE + " must exist: webTest depends on :writeParityCorpus.");
        assertEquals(seed, Files.readString(SEED_FILE, UTF_8).replaceAll("[\\r\\n]+$", ""));
    }

    @Test
    void theTestReceivesThePathOfTheCorpus() {
        String corpus = System.getProperty(CORPUS_PROPERTY);

        assertNotNull(corpus, "web/build.gradle.kts must pass the system property " + CORPUS_PROPERTY + " to webTest.");
        assertEquals(CORPUS.toAbsolutePath().normalize(), Path.of(corpus).toAbsolutePath().normalize());
    }

    @Test
    void theBuildsWriteTheCorpusWithTheDefaultSeedAndBeforeWebTest() throws IOException {
        String root = withoutComments(read(ROOT_BUILD));
        assertTrue(Pattern.compile("register<JavaExec>\\(\\s*\"writeParityCorpus\"").matcher(root).find(),
                ROOT_BUILD + " must register the JavaExec task writeParityCorpus.");
        assertTrue(Pattern.compile("(?s)\"paritySeed\".{0,200}?\"?20261107\\b").matcher(root).find(),
                ROOT_BUILD + " must give 20261107 as the default of -PparitySeed.");
        assertTrue(Pattern.compile("(?s)\"writeParityCorpus\".{0,1000}?runtimeClasspath").matcher(root).find(),
                "writeParityCorpus must run on the test runtime class path.");

        String web = withoutComments(read(WEB_BUILD));
        assertTrue(web.contains(":writeParityCorpus"), "webTest must depend on :writeParityCorpus.");
        assertTrue(web.contains(SEED_PROPERTY), "webTest must receive " + SEED_PROPERTY + ".");
        assertTrue(web.contains(CORPUS_PROPERTY), "webTest must receive " + CORPUS_PROPERTY + ".");
    }

    // ---- web/parity/run-parity.js -----------------------------------------------------------------------------------------------

    /** A stand-in for calc.js: what comes out is what parityNotebook returned, and what it received is visible. */
    @Test
    void theScriptPassesTheDecodedTextsAndWritesBase64OfUtf8InTheOrderOfTheCorpus() throws Exception {
        Path stub = Files.writeString(temporaryDirectory.resolve("calc-stub.js"),
                "exports.parityNotebook = function (lines) { return 'vu:' + lines; };\n", UTF_8);
        String accents = "√2\n# é ≈ 😀";
        String blanks = "  ";
        Path corpus = Files.writeString(temporaryDirectory.resolve("corpus.txt"),
                entry("z", accents) + "\r\n" + "a\t\r\n" + entry("m", blanks) + "\r\n", UTF_8);
        Path output = temporaryDirectory.resolve("a").resolve("deep").resolve("folder").resolve("out.txt");

        NodeRun run = node(RUN_PARITY, stub.toString(), corpus.toString(), output.toString());

        assertEquals(0, run.exitCode(), run::toString);
        assertTrue(Files.isRegularFile(output), "The script must create the folder of its output.");
        assertEquals(List.of(entry("z", "vu:" + accents), entry("a", "vu:"), entry("m", "vu:" + blanks)), outputLines(output));
    }

    @Test
    void anExceptionInTheCompiledCodeMakesTheScriptFail() throws Exception {
        Path stub = Files.writeString(temporaryDirectory.resolve("calc-stub.js"),
                "exports.parityNotebook = function () { throw new Error('boom'); };\n", UTF_8);
        Path corpus = Files.writeString(temporaryDirectory.resolve("corpus.txt"), entry("a", "1") + "\n", UTF_8);

        NodeRun run = node(RUN_PARITY, stub.toString(), corpus.toString(), temporaryDirectory.resolve("out.txt").toString());

        assertNotEquals(0, run.exitCode(), run::toString);
    }

    @Test
    void theScriptUsesNoExternalModuleAndNeverTrims() throws IOException {
        String source = read(RUN_PARITY);
        assertFalse(TRIM_CALL.matcher(source).find(), RUN_PARITY + " must not trim: empty and blank entries count.");
        Matcher modules = REQUIRED_MODULE.matcher(source);
        while (modules.find()) {
            assertTrue(ALLOWED_MODULES.contains(modules.group(1)), RUN_PARITY + " requires " + modules.group(1));
        }
    }

    // ---- WebExports.parityNotebook ---------------------------------------------------------------------------------------------

    @Test
    void parityNotebookGivesTheFourSnapshotsOfAKnownNotebook() throws Exception {
        String[] snapshots = snapshotsOf(oneEntryUnderNode("(1/3)*3\n√2\n2 +"));

        assertEquals(MODES.size(), snapshots.length, "One snapshot per mode.");
        assertTrue(snapshots[0].contains("\"status\":\"EXACT\",\"result\":\"1\""), snapshots[0]);
        assertTrue(snapshots[0].contains("\"status\":\"APPROXIMATE\",\"result\":\"≈ 1,41421356237…\""), snapshots[0]);
        assertTrue(snapshots[0].contains("\"errorBound\":\"± 2,1 × 10⁻²⁵\""), snapshots[0]);
        assertTrue(snapshots[0].contains("\"status\":\"ERROR\""), snapshots[0]);
        assertTrue(snapshots[0].contains("Opérande manquant après « + »."), snapshots[0]);
        assertTrue(snapshots[3].contains("\"result\":\"≈ 1,41421356237… × 10⁰\""), snapshots[3]);
    }

    /** A new instance each time: the notebook the page is working on stays as it is, and nothing carries over. */
    @Test
    void parityNotebookWorksOnANewInstance() throws Exception {
        Path runner = Files.writeString(temporaryDirectory.resolve("new-instance.js"), """
                'use strict';
                const path = require('path');
                const calc = require(path.resolve(process.argv[2]));
                calc.load('1 + 1\\n2 + 2', 'FRACTION');
                const before = calc.snapshot();
                const first = calc.parityNotebook('3 + 3');
                if (calc.snapshot() !== before) { throw new Error('the notebook of the page has changed'); }
                calc.parityNotebook('4 + 4\\n5 + 5\\nx = 7');
                if (calc.parityNotebook('3 + 3') !== first) { throw new Error('a previous call carried over'); }
                if (!first.startsWith('{"mode":"AUTO",')) { throw new Error('the first snapshot must be AUTO: ' + first); }
                if (first.includes('"canUndo":true')) { throw new Error('history must start empty: ' + first); }
                console.log('OK');
                """, UTF_8);

        NodeRun run = node(runner.toString(), CALC_JS);

        assertEquals(0, run.exitCode(), run::toString);
        assertEquals("OK", run.stdout().strip(), run::toString);
    }

    @Test
    void anEmptyNotebookIsComparedLikeAnyOther() throws Exception {
        assertSameOutputs(List.of(entry("known", jvmSnapshots(""))), List.of(oneEntryUnderNode("")), "none");
    }

    // ---- The Unicode gap ---------------------------------------------------------------------------------------------------------

    /** The JVM is the reference: these characters are refused there, and must be refused under Node with the same message. */
    @Test
    void theCharactersNewerThanUnicode15GiveTheAnswerOfTheJvm() throws Exception {
        assertTrue(snapshotsOf(entry("x", jvmSnapshots(ALONE_A7CB)))[0].contains("Caractère inattendu « U+A7CB »"), "U+A7CB on the JVM.");
        assertTrue(snapshotsOf(entry("x", jvmSnapshots(ALONE_2EBF0)))[0].contains("Caractère inattendu « U+2EBF0 »"), "U+2EBF0 on the JVM.");

        for (String alone : List.of(ALONE_A7CB, ALONE_31EF, ALONE_2EBF0)) {
            assertTrue(jvmSnapshots(alone).contains("\"status\":\"ERROR\""), () -> "U+" + Integer.toHexString(alone.codePointAt(0)) + " on the JVM.");
            assertSameOutputs(List.of(entry("known", jvmSnapshots(alone))), List.of(oneEntryUnderNode(alone)), "none");
        }
    }

    @Test
    void theEssayRecordsTheOutcomeOfTheUnicodeParity() throws IOException {
        List<String> lines = read(ESSAY).lines().filter(line -> line.startsWith(UNICODE_LINE)).toList();

        assertEquals(1, lines.size(), ESSAY + " must hold one line that starts with " + UNICODE_LINE);
        String line = lines.get(0);
        boolean completed = Files.exists(CHARACTER_COMPAT);
        assertEquals(completed, line.contains(GAP_OBSERVED),
                () -> "CharacterCompat exists: " + completed + ", but the essay says: " + line);
        assertEquals(!completed, line.contains(NO_GAP),
                () -> "CharacterCompat exists: " + completed + ", but the essay says: " + line);
    }

    // ---- The golden file, in the same run ------------------------------------------------------------------------------------------

    /** All the tests of the golden file, rerun in the very execution of the parity. */
    @Nested
    @DisplayName("Le golden sous Node reste vert dans la même exécution")
    class GoldenInTheSameRun extends GoldenReplayAcceptanceTest {
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------------------------

    private static long count(List<Entry> corpus, String prefix) {
        return corpus.stream().filter(entry -> entry.id().startsWith(prefix)).count();
    }

    private static String snapshots(String... snapshots) {
        return String.join("\n", snapshots);
    }

    private static String entry(String id, String text) {
        return id + "\t" + Base64.getEncoder().encodeToString(text.getBytes(UTF_8));
    }

    private static synchronized ParityRun parityRun() throws Exception {
        if (parityRun == null) {
            String seed = System.getProperty(SEED_PROPERTY);
            String corpusProperty = System.getProperty(CORPUS_PROPERTY);
            assertNotNull(seed, "web/build.gradle.kts must pass the system property " + SEED_PROPERTY + " to webTest.");
            assertNotNull(corpusProperty, "web/build.gradle.kts must pass the system property " + CORPUS_PROPERTY + " to webTest.");
            Path corpusFile = Path.of(corpusProperty);
            assertTrue(Files.isRegularFile(corpusFile), corpusFile + " must exist: webTest depends on :writeParityCorpus.");
            List<Entry> corpus = readCorpus(corpusFile);

            Files.createDirectories(NODE_OUTPUT.getParent());
            Files.deleteIfExists(NODE_OUTPUT);
            NodeRun run = node(RUN_PARITY, CALC_JS, corpusFile.toString(), NODE_OUTPUT.toString());
            assertEquals(0, run.exitCode(), () -> "Seed " + seed + ": " + run);
            assertTrue(Files.isRegularFile(NODE_OUTPUT), RUN_PARITY + " must write " + NODE_OUTPUT);

            parityRun = new ParityRun(seed, corpus, onTheJvm(corpus), outputLines(NODE_OUTPUT));
        }
        return parityRun;
    }

    private static List<Entry> readCorpus(Path corpusFile) throws IOException {
        List<Entry> corpus = new ArrayList<>();
        for (String line : outputLines(corpusFile)) {
            int tab = line.indexOf('\t');
            assertTrue(tab > 0, () -> "Not an entry of the corpus: " + line);
            corpus.add(new Entry(line.substring(0, tab), new String(Base64.getDecoder().decode(line.substring(tab + 1)), UTF_8)));
        }
        return corpus;
    }

    /** The lines of the file, without the final line feed and without the carriage return a platform may add. */
    private static List<String> outputLines(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readString(file, UTF_8).split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static List<String> onTheJvm(List<Entry> corpus) throws Exception {
        try (URLClassLoader loader = coreLoader()) {
            Class<?> type = Class.forName("calc.web.WebNotebook", true, loader);
            List<String> output = new ArrayList<>();
            for (Entry entry : corpus) {
                output.add(entry(entry.id(), snapshotsOnTheJvm(type, entry.text())));
            }
            return output;
        }
    }

    private static String jvmSnapshots(String text) throws Exception {
        try (URLClassLoader loader = coreLoader()) {
            return snapshotsOnTheJvm(Class.forName("calc.web.WebNotebook", true, loader), text);
        }
    }

    /** What parityNotebook does: a new notebook, loaded, then one snapshot per mode. */
    private static String snapshotsOnTheJvm(Class<?> type, String text) throws Exception {
        Object notebook = type.getConstructor().newInstance();
        type.getMethod("load", String.class, String.class).invoke(notebook, text, MODES.get(0));
        List<String> snapshots = new ArrayList<>();
        for (String mode : MODES) {
            snapshots.add((String) type.getMethod("setMode", String.class).invoke(notebook, mode));
        }
        return String.join("\n", snapshots);
    }

    private static URLClassLoader coreLoader() throws IOException {
        assertTrue(Files.isDirectory(WEB_CLASSES), WEB_CLASSES + " must exist: :web:webTest compiles the web module.");
        assertTrue(Files.isDirectory(CORE_CLASSES), CORE_CLASSES + " must exist: generateJavaScript reads the core.");
        URL[] classes = {WEB_CLASSES.toUri().toURL(), CORE_CLASSES.toUri().toURL()};
        return new URLClassLoader(classes, ClassLoader.getPlatformClassLoader());
    }

    private String oneEntryUnderNode(String text) throws Exception {
        Path corpus = Files.writeString(temporaryDirectory.resolve("known.txt"), entry("known", text) + "\n", UTF_8);
        Path output = temporaryDirectory.resolve("known-out.txt");
        Files.deleteIfExists(output);

        NodeRun run = node(RUN_PARITY, CALC_JS, corpus.toString(), output.toString());

        assertEquals(0, run.exitCode(), run::toString);
        List<String> lines = outputLines(output);
        assertEquals(1, lines.size(), "One entry in, one entry out.");
        return lines.get(0);
    }

    private static NodeRun node(String... arguments) throws IOException, InterruptedException {
        Path stdout = Files.createTempFile("parity-node", ".out");
        Path stderr = Files.createTempFile("parity-node", ".err");
        try {
            List<String> command = new ArrayList<>(List.of("node"));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
            if (!process.waitFor(NODE_WATCHDOG_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor();
                fail("node did not finish: " + command);
            }
            return new NodeRun(process.exitValue(), Files.readString(stdout, UTF_8), Files.readString(stderr, UTF_8));
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }

    private static String read(String file) throws IOException {
        return Files.readString(Path.of(file), UTF_8);
    }

    private static String withoutComments(String kotlin) {
        return kotlin.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
