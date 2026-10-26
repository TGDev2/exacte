package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
import calc.testing.RecordGenerators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Hostile and oversized files are refused quickly and cleanly, and never replaced. Each case is timed (best of three runs,
 * after a warm-up) and the measure is printed: {@code [hostile] <name>: <reason> in <ms> ms}. The assertion is a generous
 * budget, not the measure: it fails on a hang or a quadratic behaviour, not on a slow machine.
 */
class HostileFilesTest {
    private static final long BUDGET_MILLIS = 2_000;
    private static final String HEAD = "{\"format\": \"calc-history\", \"version\": 2, ";

    private enum Hostile {
        VERY_DEEP_ARRAYS("one million nested arrays", () -> "[".repeat(1_000_000), HistoryFormatException.Reason.JSON_LIMIT),
        VERY_DEEP_OBJECTS("500 000 nested objects", () -> "{\"a\":".repeat(500_000), HistoryFormatException.Reason.JSON_LIMIT),
        DEEP_INSIDE_A_HISTORY("an entry nested 1000 deep", () -> HEAD + "\"entries\": [" + "[".repeat(1000) + "]".repeat(1000) + "]}",
                HistoryFormatException.Reason.JSON_LIMIT),
        GIGANTIC_STRING("a 6 MB string", () -> HEAD + "\"entries\": [], \"x\": \"" + "a".repeat(6_000_000) + "\"}",
                HistoryFormatException.Reason.JSON_LIMIT),
        GIGANTIC_KEY("a 6 MB key", () -> HEAD + "\"entries\": [], \"" + "k".repeat(6_000_000) + "\": 1}", HistoryFormatException.Reason.JSON_LIMIT),
        HUGE_NUMBER_AS_VERSION("a version of 3 million digits", () -> "{\"format\": \"calc-history\", \"version\": " + "9".repeat(3_000_000) + "}",
                HistoryFormatException.Reason.JSON_LIMIT),
        HUGE_NUMBER_IN_AN_UNKNOWN_FIELD("a number of 3 million digits in an unknown field",
                () -> HEAD + "\"entries\": [], \"x\": 1" + "0".repeat(3_000_000) + "}", HistoryFormatException.Reason.JSON_LIMIT),
        HUGE_EXPONENT("a number with an exponent of 3 million digits", () -> HEAD + "\"entries\": [], \"x\": 1e" + "9".repeat(3_000_000) + "}",
                HistoryFormatException.Reason.JSON_LIMIT),
        TOO_MANY_ENTRIES("10 001 entries", () -> HEAD + "\"entries\": [" + "1,".repeat(StorageLimits.MAX_ENTRIES) + "1]}",
                HistoryFormatException.Reason.TOO_MANY_ENTRIES),
        OVER_THE_FILE_LIMIT("a file one byte over the size limit", () -> padded(StorageLimits.MAX_FILE_BYTES + 1), HistoryFormatException.Reason.TOO_LARGE),
        DUPLICATE_KEYS_FLOOD("a million equal keys", () -> "{" + "\"a\":1,".repeat(1_000_000) + "\"a\":1}", HistoryFormatException.Reason.MALFORMED_JSON);

        final String label;
        final Callable<String> content;
        final HistoryFormatException.Reason expected;

        Hostile(String label, Callable<String> content, HistoryFormatException.Reason expected) {
            this.label = label;
            this.content = content;
            this.expected = expected;
        }
    }

    @ParameterizedTest
    @EnumSource(Hostile.class)
    void aHostileFileIsRefusedQuicklyForTheRightReason(Hostile hostile) throws Exception {
        byte[] bytes = hostile.content.call().getBytes(StandardCharsets.UTF_8);

        long millis = bestOfThree(() -> {
            HistoryFormatException refusal = assertThrows(HistoryFormatException.class, () -> HistoryCodec.decode(bytes));
            assertEquals(hostile.expected, refusal.reason(), hostile.label);
        });

        System.out.println("[hostile] " + hostile.label + " (" + bytes.length + " bytes): " + hostile.expected + " in " + millis + " ms");
        assertTrue(millis < BUDGET_MILLIS, hostile.label + " took " + millis + " ms");
    }

    @ParameterizedTest
    @EnumSource(Hostile.class)
    void aStoreRefusesAHostileFileCleanlyAndNeverReplacesIt(Hostile hostile, @TempDir Path directory) throws Exception {
        Path file = directory.resolve("history.json");
        Files.write(file, hostile.content.call().getBytes(StandardCharsets.UTF_8));
        byte[] original = Files.readAllBytes(file);

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            long start = System.nanoTime();
            CalculationHistoryException refusal = assertThrows(CalculationHistoryException.class, store::loadHistory);
            long millis = (System.nanoTime() - start) / 1_000_000;

            assertTrue(millis < BUDGET_MILLIS, hostile.label + " took " + millis + " ms");
            assertTrue(refusal.getMessage().contains(file.toString()), "The error names the file.");
            if (hostile.expected.isLimit()) {
                assertEquals(null, refusal.getBackupFile(), "A file over a limit is not copied.");
                assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "1", "2"))),
                        "And nothing is written over it.");
                assertArrayEquals(original, Files.readAllBytes(file));
            } else {
                assertArrayEquals(original, Files.readAllBytes(refusal.getBackupFile()), "A damaged file is copied first.");
            }
        }
    }

    @Test
    void anOversizedFileIsRefusedWithoutBeingRead(@TempDir Path directory) throws Exception {
        // A sparse-looking file of 100 MB: reading it would take far longer than the budget and a lot of memory.
        Path file = directory.resolve("history.json");
        try (var channel = Files.newByteChannel(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(100L * 1024 * 1024 - 1);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {'}'}));
        }

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            long start = System.nanoTime();
            assertThrows(CalculationHistoryException.class, store::loadHistory);
            long millis = (System.nanoTime() - start) / 1_000_000;

            System.out.println("[hostile] a 100 MB file: TOO_LARGE in " + millis + " ms");
            assertTrue(millis < 500, "Refused from its size alone: " + millis + " ms");
            assertEquals(List.of("history.json", "history.lock"), fileNames(directory), "Not copied.");
        }
    }

    @Test
    void numbersWrittenAsTextAreBoundedByTheCoreLimits() throws Exception {
        List<String> hostileValues = new ArrayList<>();
        hostileValues.add("1e999999999");
        hostileValues.add("1" + "0".repeat(5000));
        hostileValues.add("9".repeat(12000) + "/7");
        hostileValues.add("1/" + "9".repeat(5000));
        hostileValues.add("1/0");
        hostileValues.add("-".repeat(10000));
        StringBuilder entries = new StringBuilder();
        for (String value : hostileValues) {
            entries.append("{\"op\": \"addition\", \"operands\": [\"").append(value).append("\", \"1\"], \"result\": {\"exact\": true, \"value\": \"1\"}},");
        }
        byte[] bytes = (HEAD + "\"entries\": [" + entries + "{}]}").getBytes(StandardCharsets.UTF_8);

        long millis = bestOfThree(() -> {
            DecodedHistory decoded = HistoryCodec.decode(bytes);
            assertEquals(0, decoded.records().size());
            assertEquals(hostileValues.size() + 1, decoded.rejections().size(), "Every hostile entry is rejected, none is expanded.");
        });

        System.out.println("[hostile] six hostile numbers written as text: rejected in " + millis + " ms");
        assertTrue(millis < BUDGET_MILLIS, "took " + millis + " ms");
    }

    @Test
    void theLargestHistoryTheApplicationWritesLoadsInReasonableTime(@TempDir Path directory) throws Exception {
        // 10 000 usual entries, the entry limit.
        List<CalculationRecord> usual = new ArrayList<>();
        CalculatorEngine engine = new CalculatorEngine();
        for (int index = 0; index < StorageLimits.MAX_ENTRIES; index++) {
            usual.add(engine.calculate(Operation.values()[index % Operation.values().length], index % 97 + 3 + "," + index % 10, "2"));
        }
        byte[] usualFile = HistoryCodec.encode(usual).bytes();
        long usualMillis = bestOfThree(() -> assertEquals(StorageLimits.MAX_ENTRIES, HistoryCodec.decode(usualFile).records().size()));
        System.out.println("[hostile] " + StorageLimits.MAX_ENTRIES + " usual entries (" + usualFile.length + " bytes): loaded in " + usualMillis + " ms");

        // The size limit filled with the largest entries the core allows: the worst file the reader accepts.
        CalculationRecord large = engine.calculate(Operation.MULTIPLICATION, "3".repeat(600), "7".repeat(600));
        byte[] worstFile = HistoryCodec.encode(java.util.Collections.nCopies(StorageLimits.MAX_ENTRIES, large)).bytes();
        long worstMillis = bestOfThree(() -> assertTrue(HistoryCodec.decode(worstFile).records().size() > 100));
        System.out.println("[hostile] the size limit filled with entries of two 600-digit operands (" + worstFile.length + " bytes): loaded in " + worstMillis + " ms");

        assertTrue(usualMillis < 5_000 && worstMillis < 15_000, usualMillis + " ms and " + worstMillis + " ms");
        assertFalse(worstFile.length > StorageLimits.MAX_FILE_BYTES);
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

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
