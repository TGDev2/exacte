package calc.expression;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.export.NotebookExporterTest;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.notebook.NotebookException;
import calc.number.Rational;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The corpus that the JVM and Node replay: same seed, same bytes, and it holds what the differential test has to go
 * through. {@code ParityCorpusWriter} is reached by name, so that this class compiles and fails on its own, without
 * stopping the other tests, as long as the writer does not exist.
 */
@DisplayName("Corpus de parité : déterministe par sa graine et complet")
class ParityCorpusWriterTest {
    private static final String WRITER = "calc.expression.ParityCorpusWriter";
    private static final String GITHUB_RUN_ID_SEED = "10000000000";
    private static final int MINIMUM_LINES = 1000;
    private static final int MINIMUM_NOTEBOOKS = 50;
    private static final int MAXIMUM_LINES_OF_A_NOTEBOOK = 30;
    private static final Pattern ENTRY = Pattern.compile("(export|(?:hand|line|notebook)-[A-Za-z0-9_-]+)\t[A-Za-z0-9+/]*={0,2}");

    private static final String ALONE_A7CB = new String(Character.toChars(0xA7CB));
    private static final String ALONE_31EF = new String(Character.toChars(0x31EF));
    private static final String ALONE_2EBF0 = new String(Character.toChars(0x2EBF0));
    private static final String RACINE_WITH_U0130 = "RAC" + (char) 0x0130 + "NE(4)";

    /** Large enough for a root beyond the range of a long: at least 10^30 under the root. */
    private static final String SMALLEST_LARGE_ROOT = "1000000000000000";

    @TempDir
    Path temporaryDirectory;

    private record Entry(String id, String text) {
        List<String> lines() {
            return List.of(text.split("\n", -1));
        }
    }

    // ---- The seed -------------------------------------------------------------------------------------------------------

    @Test
    void theSameSeedGivesTheSameBytes() throws Exception {
        byte[] first = Files.readAllBytes(write("first.txt", "42"));
        byte[] second = Files.readAllBytes(write("second.txt", "42"));

        assertTrue(first.length > 0, "The corpus must not be empty.");
        assertArrayEquals(first, second, "Two runs with the seed 42 must give the same file, byte for byte.");
    }

    @Test
    void anotherSeedGivesAnotherFile() throws Exception {
        byte[] seed42 = Files.readAllBytes(write("seed-42.txt", "42"));
        byte[] seed43 = Files.readAllBytes(write("seed-43.txt", "43"));

        assertFalse(Arrays.equals(seed42, seed43), "The seeds 42 and 43 must give different files.");
    }

    /** The task of the build runs the writer in a JVM of its own, outside any test: the bytes must not depend on that. */
    @Test
    void aFreshJvmGivesTheBytesOfThisOne() throws Exception {
        byte[] here = Files.readAllBytes(write("here.txt", "42"));

        Path there = temporaryDirectory.resolve("there.txt");
        Path log = temporaryDirectory.resolve("there.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), WRITER, there.toString(), "42")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(2, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            fail("The writer did not end in its own JVM.");
        }
        assertEquals(0, process.exitValue(), () -> "The writer must succeed in its own JVM: " + readLog(log));
        assertArrayEquals(here, Files.readAllBytes(there), "A fresh JVM must give the same file for the same seed.");
    }

    @Test
    void aSeedBeyondTwoToTheThirtyOneIsAcceptedAndKeptInTheSeedFile() throws Exception {
        assertTrue(Long.parseLong(GITHUB_RUN_ID_SEED) > Integer.MAX_VALUE, "The seed of this test goes beyond an int.");

        Path corpus = write("corpus.txt", GITHUB_RUN_ID_SEED);

        assertFalse(entries(corpus).isEmpty(), "The corpus must not be empty.");
        assertEquals(GITHUB_RUN_ID_SEED, seedFileOf(corpus));
    }

    @Test
    void theSeedFileHoldsEachSeedAndSitsNextToTheCorpus() throws Exception {
        Path corpus = write("named.txt", "42");

        assertEquals("42", seedFileOf(corpus));
        write("named.txt", "43");
        assertEquals("43", seedFileOf(corpus), "A new run must replace the seed file.");
    }

    @Test
    void aSeedThatIsNotANumberIsRefused() {
        Path corpus = temporaryDirectory.resolve("refused.txt");

        assertThrows(NumberFormatException.class, () -> writer(corpus, "not-a-number"));
    }

    // ---- The format -----------------------------------------------------------------------------------------------------

    @Test
    void everyEntryIsAnIdentifierATabAndBase64WithUniqueIdentifiers() throws Exception {
        Path corpus = write("corpus.txt", "42");
        Set<String> identifiers = new HashSet<>();

        for (String line : rawLines(corpus)) {
            assertTrue(ENTRY.matcher(line).matches(), () -> "Not an entry of the corpus: " + line);
            assertTrue(identifiers.add(line.substring(0, line.indexOf('\t'))), () -> "Identifier used twice: " + line);
        }
    }

    // ---- The content: run on a small seed and on a seed of the size of github.run_id -------------------------------------

    @Test
    void theCorpusHoldsTheExportNotebook() throws Exception {
        for (String seed : List.of("42", GITHUB_RUN_ID_SEED)) {
            Entry export = only(entries(write("export-" + seed + ".txt", seed)), "export");

            assertEquals(19, NotebookExporterTest.REPRESENTATIVE.size(), "The notebook of the exports has 19 lines.");
            assertEquals(NotebookExporterTest.REPRESENTATIVE, export.lines(), "Seed " + seed);
        }
    }

    @Test
    void theCorpusHoldsEnoughGeneratedLinesInCanonicalForm() throws Exception {
        for (String seed : List.of("42", GITHUB_RUN_ID_SEED)) {
            List<Entry> lines = withPrefix(entries(write("lines-" + seed + ".txt", seed)), "line-");

            assertTrue(lines.size() >= MINIMUM_LINES, () -> "Seed " + seed + ": " + lines.size() + " line-* entries.");
            Set<String> distinct = new HashSet<>();
            for (Entry entry : lines) {
                assertEquals(1, entry.lines().size(), () -> entry.id() + " must hold one line.");
                assertIsCanonicalPrint(entry);
                distinct.add(entry.text());
            }
            assertTrue(distinct.size() * 2 >= lines.size(),
                    () -> "Seed " + seed + ": only " + distinct.size() + " different line-* entries: the generator must drive them.");
        }
    }

    @Test
    void theCorpusHoldsEnoughGeneratedNotebooks() throws Exception {
        for (String seed : List.of("42", GITHUB_RUN_ID_SEED)) {
            List<Entry> notebooks = withPrefix(entries(write("notebooks-" + seed + ".txt", seed)), "notebook-");

            assertTrue(notebooks.size() >= MINIMUM_NOTEBOOKS, () -> "Seed " + seed + ": " + notebooks.size() + " notebook-* entries.");
            Set<String> distinct = new HashSet<>();
            for (Entry entry : notebooks) {
                assertTrue(entry.lines().size() <= MAXIMUM_LINES_OF_A_NOTEBOOK, () -> entry.id() + " has more than 30 lines.");
                distinct.add(entry.text());
            }
            assertTrue(distinct.size() * 2 >= notebooks.size(),
                    () -> "Seed " + seed + ": only " + distinct.size() + " different notebook-* entries: the generator must drive them.");
            assertTrue(notebooks.stream().anyMatch(entry -> entry.lines().size() > 1), "The notebooks must have several lines.");
            assertTrue(notebooks.stream().anyMatch(entry -> entry.text().contains("$")), "The notebooks must use line references.");
        }
    }

    @Test
    void eachScriptAndControlCaseOfHandIsOnALineOfItsOwn() throws Exception {
        List<String> lines = handLines("42");
        Map<String, IntPredicate> kinds = Map.of(
                "lettre accentuée", ParityCorpusWriterTest::isAccentedLatin,
                "lettre grecque", codePoint -> isLetterOfScript(codePoint, Character.UnicodeScript.GREEK),
                "lettre cyrillique", codePoint -> isLetterOfScript(codePoint, Character.UnicodeScript.CYRILLIC),
                "caractère chinois", codePoint -> isLetterOfScript(codePoint, Character.UnicodeScript.HAN),
                "émoji", codePoint -> codePoint >= 0x1F300 && codePoint <= 0x1FAFF,
                "espace insécable", codePoint -> codePoint == 0x00A0,
                "caractère de contrôle", codePoint -> Character.isISOControl(codePoint) && !Character.isWhitespace(codePoint));

        for (Map.Entry<String, IntPredicate> kind : kinds.entrySet()) {
            boolean alone = lines.stream().anyMatch(line -> line.codePoints().anyMatch(kind.getValue())
                    && kinds.entrySet().stream().filter(other -> !other.getKey().equals(kind.getKey()))
                            .noneMatch(other -> line.codePoints().anyMatch(other.getValue())));
            assertTrue(alone, () -> "A hand-* line must hold a " + kind.getKey() + " and none of the other kinds.");
        }
    }

    @Test
    void theCharactersNewerThanUnicode15AreEachAloneOnALine() throws Exception {
        List<String> lines = handLines("42");

        assertTrue(lines.contains(ALONE_A7CB), "U+A7CB alone on a hand-* line.");
        assertTrue(lines.contains(ALONE_31EF), "U+31EF alone on a hand-* line.");
        assertTrue(lines.contains(ALONE_2EBF0), "U+2EBF0 alone on a hand-* line.");
    }

    @Test
    void theHandEntriesHoldTheLinesOfTheContract() throws Exception {
        List<String> lines = handLines("42");

        for (String line : List.of(RACINE_WITH_U0130, "2^4095", "2^4096", "2^100000", "1/0", "√(-1)")) {
            assertTrue(lines.contains(line), () -> "The line " + line + " must be in a hand-* entry.");
        }
    }

    @Test
    void theHandEntriesHoldTheRootOfALargePerfectSquare() throws Exception {
        Rational smallest = Rational.parse(SMALLEST_LARGE_ROOT);
        boolean found = false;
        for (String line : handLines("42")) {
            found |= line.startsWith("√") && line.length() > 1 && isExactAndAtLeast(line, smallest);
        }
        assertTrue(found, "A hand-* line must be the √ of a perfect square of at least 10^30, whose root is exact.");
    }

    @Test
    void theHandEntriesReachTheDepthLimitAndGoOneBeyond() throws Exception {
        List<String> lines = handLines("42");

        assertTrue(lines.stream().anyMatch(line -> parenthesisDepth(line) == 100 && errorOf(ExpressionLimits.DEFAULT, line).isEmpty()),
                "A hand-* line must nest 100 parentheses and be accepted.");
        assertTrue(lines.stream().anyMatch(line -> parenthesisDepth(line) == 101
                        && errorOf(ExpressionLimits.DEFAULT, line).equals(Optional.of(ExpressionError.TOO_DEEP))),
                "A hand-* line must nest 101 parentheses and be refused as too deep.");
    }

    @Test
    void theHandEntriesReachTheLengthLimitAndGoOneBeyond() throws Exception {
        List<String> lines = handLines("42");

        assertTrue(lines.stream().anyMatch(line -> line.length() == 1000
                        && !errorOf(ExpressionLimits.DEFAULT, line).equals(Optional.of(ExpressionError.LINE_TOO_LONG))),
                "A hand-* line must have 1000 characters and not be refused as too long.");
        assertTrue(lines.stream().anyMatch(line -> line.length() == 1001
                        && errorOf(ExpressionLimits.DEFAULT, line).equals(Optional.of(ExpressionError.LINE_TOO_LONG))),
                "A hand-* line must have 1001 characters and be refused as too long.");
    }

    @Test
    void theHandEntriesReachTheNodeLimitAndGoOneBeyond() throws Exception {
        List<String> lines = handLines("42");

        assertTrue(lines.stream().anyMatch(line -> errorOf(ExpressionLimits.DEFAULT, line).isEmpty()
                        && errorOf(withMaxNodes(499), line).equals(Optional.of(ExpressionError.TOO_MANY_NODES))),
                "A hand-* line must have exactly 500 nodes: accepted at 500, refused at 499.");
        assertTrue(lines.stream().anyMatch(line -> errorOf(ExpressionLimits.DEFAULT, line).equals(Optional.of(ExpressionError.TOO_MANY_NODES))
                        && errorOf(withMaxNodes(501), line).isEmpty()),
                "A hand-* line must have exactly 501 nodes: refused at 500, accepted at 501.");
    }

    @Test
    void theHandEntriesDoNotDependOnTheSeed() throws Exception {
        assertEquals(handLines("42"), handLines(GITHUB_RUN_ID_SEED), "A seed must never take a hand-* case away.");
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------------

    private static void assertIsCanonicalPrint(Entry entry) {
        String line = entry.text();
        try {
            Optional<Node> tree = ExpressionParser.parseLine(line).tree();
            assertTrue(tree.isPresent(), () -> entry.id() + " must be an expression or an assignment: " + line);
            assertEquals(line, CanonicalPrinter.print(tree.orElseThrow()), () -> entry.id() + " must be printed by CanonicalPrinter.");
        } catch (ExpressionException exception) {
            fail(entry.id() + " must read back: " + line + " gives " + exception.getMessage());
        }
    }

    private static boolean isAccentedLatin(int codePoint) {
        return codePoint > 0x7F && codePoint != 0x0130 && isLetterOfScript(codePoint, Character.UnicodeScript.LATIN);
    }

    private static boolean isLetterOfScript(int codePoint, Character.UnicodeScript script) {
        return Character.isLetter(codePoint) && Character.UnicodeScript.of(codePoint) == script;
    }

    private static boolean isExactAndAtLeast(String line, Rational smallest) throws NotebookException {
        LineResult result = NotebookEvaluator.evaluate(Notebook.of(List.of(line))).results().get(0);
        return result.hasValue() && result.value().isExact() && result.value().lower().compareTo(smallest) >= 0;
    }

    private static int parenthesisDepth(String line) {
        int depth = 0;
        int deepest = 0;
        for (char character : line.toCharArray()) {
            depth += character == '(' ? 1 : character == ')' ? -1 : 0;
            deepest = Math.max(deepest, depth);
        }
        return deepest;
    }

    /** The limits of a notebook line, except for the nodes. */
    private static ExpressionLimits withMaxNodes(int maxNodes) {
        return new ExpressionLimits(ExpressionLimits.DEFAULT.maxLineLength(), ExpressionLimits.DEFAULT.maxDepth(), maxNodes);
    }

    private static Optional<ExpressionError> errorOf(ExpressionLimits limits, String line) {
        try {
            new ExpressionParser(limits).parse(line);
            return Optional.empty();
        } catch (ExpressionException exception) {
            return Optional.of(exception.error());
        }
    }

    private List<String> handLines(String seed) throws Exception {
        List<String> lines = new ArrayList<>();
        for (Entry entry : withPrefix(entries(write("hand-" + seed + ".txt", seed)), "hand-")) {
            lines.addAll(entry.lines());
        }
        return lines;
    }

    private static List<Entry> withPrefix(List<Entry> entries, String prefix) {
        return entries.stream().filter(entry -> entry.id().startsWith(prefix)).toList();
    }

    private static Entry only(List<Entry> entries, String id) {
        List<Entry> found = entries.stream().filter(entry -> entry.id().equals(id)).toList();
        assertEquals(1, found.size(), () -> "The corpus must hold one entry " + id);
        return found.get(0);
    }

    private static List<Entry> entries(Path corpus) throws IOException {
        List<Entry> entries = new ArrayList<>();
        for (String line : rawLines(corpus)) {
            int tab = line.indexOf('\t');
            assertTrue(tab > 0, () -> "Not an entry of the corpus: " + line);
            String text = new String(Base64.getDecoder().decode(line.substring(tab + 1)), UTF_8);
            entries.add(new Entry(line.substring(0, tab), text));
        }
        return entries;
    }

    /** The lines of the file, without the final line feed and without the carriage return a platform may add. */
    private static List<String> rawLines(Path corpus) throws IOException {
        String content = Files.readString(corpus, UTF_8);
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private String seedFileOf(Path corpus) throws IOException {
        String name = corpus.getFileName().toString();
        Path seedFile = corpus.resolveSibling(name.substring(0, name.lastIndexOf('.')) + ".seed");
        assertTrue(Files.isRegularFile(seedFile), () -> "The writer must also write " + seedFile.getFileName());
        return Files.readString(seedFile, UTF_8).replaceAll("[\\r\\n]+$", "");
    }

    private static String readLog(Path log) {
        try {
            return Files.readString(log, UTF_8);
        } catch (IOException exception) {
            return exception.toString();
        }
    }

    private Path write(String name, String seed) throws Exception {
        Path corpus = temporaryDirectory.resolve(name);
        writer(corpus, seed);
        assertTrue(Files.isRegularFile(corpus), () -> "The writer must write " + name);
        return corpus;
    }

    /** ParityCorpusWriter.main(output, seed), with the exception it throws, if any. */
    private static void writer(Path output, String seed) throws Exception {
        Class<?> writer;
        try {
            writer = Class.forName(WRITER);
        } catch (ClassNotFoundException exception) {
            throw new AssertionError(WRITER + " must exist in src/test/java.", exception);
        }
        try {
            writer.getMethod("main", String[].class).invoke(null, (Object) new String[] {output.toString(), seed});
        } catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }
}
