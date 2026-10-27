package calc.persistence;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.engine.HistoryLoadResult;
import calc.testing.RecordGenerators;
import java.util.ArrayList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The copy kept before a version 2 file that was read only in part, or not at all, can be replaced. */
public class JsonHistoryBackupTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final String BACKUP_NAME = "history.json.20240102-030405.bak";
    private static final String ADDITION = "{\"op\": \"addition\", \"operands\": [\"1\", \"2\"], \"result\": {\"exact\": true, \"value\": \"3\"}}";
    private static final String SUBTRACTION = "{\"op\": \"subtraction\", \"operands\": [\"5\", \"2\"], \"result\": {\"exact\": true, \"value\": \"3\"}}";
    private static final String MULTIPLICATION = "{\"op\": \"multiplication\", \"operands\": [\"2\", \"3\"], \"result\": {\"exact\": true, \"value\": \"6\"}}";
    private static final String BROKEN = "{\"op\": \"addition\", \"operands\": [\"1\"], \"result\": {\"exact\": true, \"value\": \"1\"}}";
    private static final String ONE_ENTRY_INVALID = document(ADDITION, BROKEN, SUBTRACTION, MULTIPLICATION);

    @Test
    void testPartiallyInvalidHistoryIsBackedUpByteForByteAndRewrittenWithValidEntries(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, ONE_ENTRY_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        HistoryLoadResult result;
        try (JsonHistoryStore store = newStore(tempDirectory)) {
            result = store.loadHistory();
        }

        assertEquals(3, result.records().size());
        assertEquals(1, result.skippedLines());
        assertEquals(tempDirectory.resolve(BACKUP_NAME), result.backupFile(), "The copy lives next to the history file.");
        assertArrayEquals(originalBytes, Files.readAllBytes(result.backupFile()), "The copy must be byte-for-byte identical.");
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "Loading alone must not modify the original.");
    }

    @Test
    void testCalculationAfterPartialRestoreKeepsOldEntriesAndTheBackup(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, ONE_ENTRY_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        try (JsonHistoryStore store = newStore(tempDirectory)) {
            List<CalculationRecord> history = new ArrayList<>(store.loadHistory().records());
            history.add(RecordGenerators.calculated(Operation.ADDITION, "10", "5"));
            store.saveHistory(history);
        }

        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        assertEquals(4, lines.stream().filter(line -> line.contains("\"op\":")).count(), "The 3 valid entries and the new one.");
        assertTrue(lines.get(lines.size() - 4).contains("\"multiplication\""), "Old entries first, in order.");
        assertTrue(lines.get(lines.size() - 3).contains("\"operands\": [\"10\", \"5\"]"), "Then the new calculation.");
        assertArrayEquals(originalBytes, Files.readAllBytes(tempDirectory.resolve(BACKUP_NAME)), "The copy must stay intact.");
    }

    @Test
    void testUnusableFileIsBackedUpAndTheNextCalculationLeavesTheCopyIntact(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, "{ this is not JSON", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        try (JsonHistoryStore store = newStore(tempDirectory)) {
            assertThrows(CalculationHistoryException.class, store::loadHistory);
            assertTrue(Files.exists(tempDirectory.resolve(BACKUP_NAME)), "A copy must exist before any write.");

            store.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "1", "1")));
        }

        assertArrayEquals(originalBytes, Files.readAllBytes(tempDirectory.resolve(BACKUP_NAME)), "The next save must not touch the copy.");
        assertTrue(Files.readString(historyFile, StandardCharsets.UTF_8).contains("\"operands\": [\"1\", \"1\"]"));
    }

    @Test
    void testFullyValidHistoryCreatesNoBackup(@TempDir Path tempDirectory) throws Exception {
        Files.writeString(tempDirectory.resolve("history.json"), document(ADDITION, SUBTRACTION), StandardCharsets.UTF_8);

        HistoryLoadResult result;
        try (JsonHistoryStore store = newStore(tempDirectory)) {
            result = store.loadHistory();
        }

        assertEquals(2, result.records().size());
        assertNull(result.backupFile());
        assertEquals(List.of("history.json", "history.lock"), listFileNames(tempDirectory), "A valid history must not leave any copy behind.");
    }

    @Test
    void testBackupNeverOverwritesAnExistingCopy(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, ONE_ENTRY_INVALID, StandardCharsets.UTF_8);
        Path existingCopy = tempDirectory.resolve(BACKUP_NAME);
        Files.writeString(existingCopy, "older copy", StandardCharsets.UTF_8);

        HistoryLoadResult result;
        try (JsonHistoryStore store = newStore(tempDirectory)) {
            result = store.loadHistory();
        }

        assertEquals(tempDirectory.resolve("history.json.20240102-030405-1.bak"), result.backupFile());
        assertEquals("older copy", Files.readString(existingCopy, StandardCharsets.UTF_8), "An existing copy must never be overwritten.");
        assertArrayEquals(Files.readAllBytes(historyFile), Files.readAllBytes(result.backupFile()));
    }

    @Test
    void testFailedBackupBlocksEveryLaterWriteForTheStoreLifetime(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, ONE_ENTRY_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        try (JsonHistoryStore store = newStoreWithFailingCopy(tempDirectory)) {
            HistoryLoadResult result = store.loadHistory();

            assertEquals(3, result.records().size(), "The valid entries are still restored in memory.");
            assertNull(result.backupFile(), "No copy exists after a failed copy.");
            CalculationHistoryException refusal =
                    assertThrows(CalculationHistoryException.class, () -> store.saveHistory(result.records()));
            assertTrue(refusal.getMessage().contains(historyFile.toString()), "The refusal message must carry the file path.");
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()), "Even clearing must not replace the original.");
        }
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "The original file must be untouched.");
    }

    @Test
    void testUnreadableFileIsBackedUpAndTheErrorCarriesTheCopy(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.write(historyFile, new byte[] {'{', (byte) 0xFF, (byte) 0xFE, '}'});
        byte[] originalBytes = Files.readAllBytes(historyFile);

        try (JsonHistoryStore store = newStore(tempDirectory)) {
            CalculationHistoryException failure = assertThrows(CalculationHistoryException.class, store::loadHistory);

            assertTrue(failure.getMessage().contains(historyFile.toString()), "The failure message must carry the file path.");
            assertNotNull(failure.getCause(), "The cause must be kept.");
            assertArrayEquals(originalBytes, Files.readAllBytes(failure.getBackupFile()), "An unreadable file is still copied.");
        }
    }

    private static String document(String... entries) {
        return "{\"format\": \"calc-history\", \"version\": 2, \"entries\": [" + String.join(",\n", entries) + "]}\n";
    }

    private static JsonHistoryStore newStore(Path directory) {
        return JsonHistoryStore.open(directory, null, FIXED_CLOCK, Files::copy, Files::move);
    }

    private static JsonHistoryStore newStoreWithFailingCopy(Path directory) {
        return JsonHistoryStore.open(directory, null, FIXED_CLOCK, (source, target) -> {
            throw new IOException("simulated copy failure");
        }, Files::move);
    }

    private static List<String> listFileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
