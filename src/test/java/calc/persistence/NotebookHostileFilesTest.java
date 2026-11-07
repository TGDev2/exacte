package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.notebook.EvaluatedNotebook;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookFormatException.Reason;
import calc.testing.NotebookGenerators;
import calc.testing.TimingBudget;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Notebook files from someone else cannot block the application or exhaust its memory. Each case is timed (best of three
 * after a warm-up) and the measure is printed: {@code [hostile-notebook] <name>: <reason> in <ms> ms}. The assertion is a
 * generous budget, not the measure: it fails on a hang or a quadratic behaviour, not on a slow machine.
 */
class NotebookHostileFilesTest {
    private static final long BUDGET_MILLIS = 2_000;
    private static final String HEAD = "{\"format\": \"calc-notebook\", \"version\": 3, ";

    private enum Hostile {
        VERY_DEEP_ARRAYS("one million nested arrays", () -> "[".repeat(1_000_000), Reason.JSON_LIMIT),
        VERY_DEEP_OBJECTS("500 000 nested objects", () -> "{\"a\":".repeat(500_000), Reason.JSON_LIMIT),
        DEEP_INSIDE_A_LINE("a line nested 1000 deep", () -> HEAD + "\"lines\": [" + "[".repeat(1000) + "]".repeat(1000) + "]}", Reason.JSON_LIMIT),
        GIGANTIC_SOURCE("a 6 MB source", () -> HEAD + "\"lines\": [{\"source\": \"" + "1+".repeat(3_000_000) + "\"}]}", Reason.JSON_LIMIT),
        SOURCE_ONE_CHARACTER_TOO_LONG("a source of 16 385 characters",
                () -> HEAD + "\"lines\": [{\"source\": \"" + "1".repeat(StorageLimits.MAX_STRING_LENGTH + 1) + "\"}]}", Reason.JSON_LIMIT),
        GIGANTIC_KEY("a 6 MB key", () -> HEAD + "\"lines\": [], \"" + "k".repeat(6_000_000) + "\": 1}", Reason.JSON_LIMIT),
        HUGE_NUMBER_AS_VERSION("a version of 3 million digits", () -> "{\"format\": \"calc-notebook\", \"version\": " + "9".repeat(3_000_000) + "}",
                Reason.JSON_LIMIT),
        HUGE_NUMBER_AS_ID("an id that is a number of 3 million digits", () -> HEAD + "\"lines\": [{\"id\": " + "9".repeat(3_000_000) + ", \"source\": \"1\"}]}",
                Reason.JSON_LIMIT),
        HUGE_EXPONENT("a number with an exponent of 3 million digits", () -> HEAD + "\"lines\": [], \"x\": 1e" + "9".repeat(3_000_000) + "}",
                Reason.JSON_LIMIT),
        TOO_MANY_LINES("5 001 lines", () -> HEAD + "\"lines\": [" + "{\"source\":\"1\"},".repeat(Notebook.MAX_LINES) + "{\"source\":\"1\"}]}",
                Reason.TOO_MANY_LINES),
        MILLIONS_OF_EMPTY_ARRAYS("three million empty arrays in an unknown field", () -> HEAD + "\"lines\": [], \"x\": [" + "[],".repeat(3_000_000) + "[]]}",
                Reason.JSON_LIMIT),
        MILLIONS_OF_UNKNOWN_KEYS("a hundred thousand unknown keys", () -> HEAD + "\"lines\": [], " + keys(100_000) + "\"z\": 1}", Reason.JSON_LIMIT),
        OVER_THE_FILE_LIMIT("a file one byte over the size limit", () -> padded(StorageLimits.MAX_NOTEBOOK_FILE_BYTES + 1), Reason.TOO_LARGE),
        DUPLICATE_KEYS_FLOOD("a million equal keys", () -> "{" + "\"a\":1,".repeat(1_000_000) + "\"a\":1}", Reason.MALFORMED_JSON),
        NOT_UTF8("two megabytes of 0xFF", () -> "", Reason.NOT_UTF8);

        final String label;
        final Callable<String> content;
        final Reason expected;

        Hostile(String label, Callable<String> content, Reason expected) {
            this.label = label;
            this.content = content;
            this.expected = expected;
        }

        byte[] bytes() throws Exception {
            if (this == NOT_UTF8) {
                // The byte 0xFF is never valid UTF-8.
                byte[] bytes = new byte[2_000_000];
                java.util.Arrays.fill(bytes, (byte) 0xFF);
                return bytes;
            }
            return content.call().getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String keys(int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) {
            text.append("\"k").append(index).append("\":1,");
        }
        return text.toString();
    }

    @ParameterizedTest
    @EnumSource(Hostile.class)
    void aHostileFileIsRefusedQuicklyForTheRightReason(Hostile hostile) throws Exception {
        byte[] bytes = hostile.bytes();

        long millis = bestOfThree(() -> {
            NotebookFormatException refusal = assertThrows(NotebookFormatException.class, () -> NotebookCodec.decode(bytes));
            assertEquals(hostile.expected, refusal.reason(), hostile.label);
        });

        System.out.println("[hostile-notebook] " + hostile.label + " (" + bytes.length + " bytes): " + hostile.expected + " in " + millis + " ms");
        TimingBudget.assertWithin(hostile.label, millis, BUDGET_MILLIS, TimeUnit.MILLISECONDS);
    }

    @Test
    void aFileOfOneHundredMegabytesIsRefusedFromItsSizeAlone(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("big.calc.json");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(100L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {'}'}));
        }

        long start = System.nanoTime();
        NotebookFormatException refusal = assertThrows(NotebookFormatException.class, () -> NotebookCodec.read(file));
        long millis = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[hostile-notebook] a 100 MB file: " + refusal.reason() + " in " + millis + " ms");
        assertEquals(Reason.TOO_LARGE, refusal.reason());
        TimingBudget.assertWithin("a 100 MB file, refused from its size alone", millis, 500, TimeUnit.MILLISECONDS);
    }

    @Test
    void onlyARegularFileIsRead(@TempDir Path directory) {
        assertEquals(Reason.NOT_A_FILE, assertThrows(NotebookFormatException.class, () -> NotebookCodec.read(directory)).reason());
        assertThrows(IOException.class, () -> NotebookCodec.read(directory.resolve("missing.calc.json")));
    }

    @Test
    void theBoundedReadNeverHoldsMoreThanTheLimit(@TempDir Path directory) throws Exception {
        // Exactly the limit is read; one byte more is refused although the size on disk is only checked once.
        Path file = directory.resolve("edge.calc.json");
        byte[] edge = HEAD.getBytes(StandardCharsets.UTF_8);
        byte[] exact = new byte[StorageLimits.MAX_NOTEBOOK_FILE_BYTES];
        System.arraycopy(edge, 0, exact, 0, edge.length);
        java.util.Arrays.fill(exact, edge.length, exact.length - 1, (byte) ' ');
        exact[exact.length - 1] = '}';
        Files.write(file, exact);

        // The content is not a notebook (no lines), but it is read in full and parsed: the reason is not the size.
        assertEquals(Reason.MALFORMED_JSON, assertThrows(NotebookFormatException.class, () -> NotebookCodec.read(file)).reason());
    }

    // ------------------------------------------------------------------------------------------------ hostile but within limits

    /** A readable file whose lines are as demanding as the language lets them be. The loading is instant; the recalculation is bounded. */
    @Test
    void theRecalculationOfAHostileNotebookWithinTheLimitsIsBounded() throws Exception {
        record Case(String label, String line) {
        }
        String digits1000 = "9".repeat(1000);
        String digits990 = "9".repeat(990);
        List<Case> cases = List.of(
                new Case("a literal of 1000 digits", digits1000),
                new Case("a literal of 1000 digits squared", "(" + digits990 + ")*(" + digits990 + ")"),
                new Case("an exponent of a million digits", "1e" + "9".repeat(990)),
                new Case("a power tower", "9^9^9^9^9^9^9^9^9^9"),
                new Case("a huge exact power", "(99/100)^4096 * (101/100)^4096"),
                new Case("a hundred nested roots", "√(".repeat(99) + "2" + ")".repeat(99)),
                new Case("a hundred nested parentheses", "(".repeat(99) + "1+1" + ")".repeat(99)),
                new Case("five hundred operations", String.join("+", Collections.nCopies(245, "1/3"))),
                new Case("small fractions summed", String.join("+", Collections.nCopies(120, "1/997")) + "*" + digits990.substring(0, 500)));

        long worst = 0;
        for (Case hostile : cases) {
            Notebook notebook = NotebookGenerators.notebook(Collections.nCopies(Notebook.MAX_LINES, hostile.line()));
            byte[] file = NotebookCodec.encode(notebook, calc.format.DisplayMode.AUTO).bytes();

            long start = System.nanoTime();
            DecodedNotebook read = NotebookCodec.decode(file);
            long loadMillis = (System.nanoTime() - start) / 1_000_000;
            start = System.nanoTime();
            EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(read.notebook());
            long evaluateMillis = (System.nanoTime() - start) / 1_000_000;

            long values = evaluated.results().stream().filter(result -> result.hasValue()).count();
            System.out.println("[hostile-notebook] " + Notebook.MAX_LINES + " lines of " + hostile.label() + " (" + file.length
                    + " bytes): loaded in " + loadMillis + " ms, recalculated in " + evaluateMillis + " ms (" + values + " values, "
                    + (Notebook.MAX_LINES - values) + " errors)");
            assertEquals(Notebook.MAX_LINES, read.notebook().size());
            TimingBudget.assertWithin(hostile.label() + ": load", loadMillis, BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            worst = Math.max(worst, evaluateMillis);
        }
        TimingBudget.assertWithin("the slowest recalculation", worst, 60_000, TimeUnit.MILLISECONDS);
    }

    private static String padded(int length) {
        return "{" + " ".repeat(length - 2) + "}";
    }

    private static long bestOfThree(ThrowingRunnable action) throws Exception {
        action.run();
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 3; run++) {
            long start = System.nanoTime();
            action.run();
            best = Math.min(best, (System.nanoTime() - start) / 1_000_000);
        }
        return best;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
