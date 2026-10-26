package calc.ui;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculatorEngine;
import calc.engine.FileCalculationHistoryStore;
import calc.engine.HistoryLoadResult;
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

public class CalculatorHistoryBackupTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);
    private static final String BACKUP_NAME = "history.tsv.20240102-030405.bak";
    private static final String FOUR_LINES_ONE_INVALID =
            "ADDITION\t1\t2\t3\nbad-data\nSUBTRACTION\t5\t2\t3\nMULTIPLICATION\t2\t3\t6\n";

    @Test
    void testPartiallyInvalidHistoryIsBackedUpByteForByteAndRewrittenWithValidLines(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, FOUR_LINES_ONE_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        HistoryLoadResult result = newStore(historyFile).loadHistory();

        assertEquals(3, result.records().size());
        assertEquals(1, result.skippedLines());
        assertEquals(tempDirectory.resolve(BACKUP_NAME), result.backupFile(), "The copy lives next to the history file.");
        assertArrayEquals(originalBytes, Files.readAllBytes(result.backupFile()), "The copy must be byte-for-byte identical.");
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "Loading alone must not modify the original.");
    }

    @Test
    void testCalculationAfterPartialRestoreKeepsOldEntriesAndTheBackup(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, FOUR_LINES_ONE_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), newStore(historyFile));
        CalculatorViewModel view = presenter.onCalculate("10", "5");

        assertEquals(StatusTone.SUCCESS, view.getStatusTone(), "The calculation after a partial restore must be saved.");
        assertEquals(
                List.of(
                        "#calc-history v1",
                        "ADDITION\t1\t2\t3",
                        "SUBTRACTION\t5\t2\t3",
                        "MULTIPLICATION\t2\t3\t6",
                        "ADDITION\t10\t5\t15"),
                Files.readAllLines(historyFile, StandardCharsets.UTF_8),
                "The old valid entries and the new one must all be present.");
        assertArrayEquals(originalBytes, Files.readAllBytes(tempDirectory.resolve(BACKUP_NAME)), "The copy must stay intact.");
    }

    @Test
    void testFullyInvalidHistoryIsBackedUpAndNextCalculationLeavesTheCopyIntact(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "garbage\nmore garbage\n", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);

        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), newStore(historyFile));
        CalculatorViewModel startView = presenter.createInitialView();

        assertEquals(0, startView.getHistoryEntries().size());
        assertTrue(Files.exists(tempDirectory.resolve(BACKUP_NAME)), "A copy must exist before any write.");

        presenter.onCalculate("1", "1");

        assertArrayEquals(originalBytes, Files.readAllBytes(tempDirectory.resolve(BACKUP_NAME)), "The calculation must not touch the copy.");
        assertEquals(
                List.of("#calc-history v1", "ADDITION\t1\t1\t2"),
                Files.readAllLines(historyFile, StandardCharsets.UTF_8));
    }

    @Test
    void testFullyValidHistoryCreatesNoBackup(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "ADDITION\t1\t2\t3\nSUBTRACTION\t5\t2\t3\n", StandardCharsets.UTF_8);

        HistoryLoadResult result = newStore(historyFile).loadHistory();

        assertEquals(2, result.records().size());
        assertNull(result.backupFile());
        assertEquals(List.of("history.tsv"), listFileNames(tempDirectory), "A valid history must not leave any copy behind.");
    }

    @Test
    void testBackupNeverOverwritesAnExistingCopy(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, FOUR_LINES_ONE_INVALID, StandardCharsets.UTF_8);
        Path existingCopy = tempDirectory.resolve(BACKUP_NAME);
        Files.writeString(existingCopy, "older copy", StandardCharsets.UTF_8);

        HistoryLoadResult result = newStore(historyFile).loadHistory();

        assertEquals(tempDirectory.resolve("history.tsv.20240102-030405-1.bak"), result.backupFile());
        assertEquals("older copy", Files.readString(existingCopy, StandardCharsets.UTF_8), "An existing copy must never be overwritten.");
        assertArrayEquals(Files.readAllBytes(historyFile), Files.readAllBytes(result.backupFile()));
    }

    @Test
    void testFailedBackupBlocksEveryLaterWriteForTheStoreLifetime(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, FOUR_LINES_ONE_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);
        FileCalculationHistoryStore store = newStoreWithFailingCopy(historyFile);

        HistoryLoadResult result = store.loadHistory();

        assertEquals(3, result.records().size(), "The valid entries are still restored in memory.");
        assertNull(result.backupFile(), "No copy exists after a failed copy.");
        CalculationHistoryException refusal =
                assertThrows(CalculationHistoryException.class, () -> store.saveHistory(result.records()));
        assertTrue(refusal.getMessage().contains(historyFile.toString()), "The refusal message must carry the file path.");
        assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()), "Even clearing must not delete the original.");
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "The original file must be untouched.");
    }

    @Test
    void testUnreadableHistoryIsBackedUpAndBlocksWrites(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.write(historyFile, new byte[] {'A', 'D', (byte) 0xFF, (byte) 0xFE, '\n'});
        byte[] originalBytes = Files.readAllBytes(historyFile);
        FileCalculationHistoryStore store = newStore(historyFile);

        CalculationHistoryException failure = assertThrows(CalculationHistoryException.class, store::loadHistory);

        assertTrue(failure.getMessage().contains(historyFile.toString()), "The failure message must carry the file path.");
        assertNotNull(failure.getCause(), "The I/O cause must be kept.");
        assertArrayEquals(originalBytes, Files.readAllBytes(failure.getBackupFile()), "An unreadable file is still copied.");
        assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()));
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile));
    }

    @Test
    void testPresenterReportsSaveFailureInProtectedMode(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, FOUR_LINES_ONE_INVALID, StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), newStoreWithFailingCopy(historyFile));

        CalculatorViewModel view = presenter.onCalculate("1", "1");

        assertEquals(StatusTone.ERROR, view.getStatusTone());
        assertEquals("Calcul effectué, mais l'historique n'a pas pu être enregistré.", view.getStatusMessage());
        assertFalse(view.getStatusMessage().contains("simulated"), "No technical detail in the interface.");
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "The original file must survive a refused save.");
    }

    private static FileCalculationHistoryStore newStore(Path historyFile) {
        return new FileCalculationHistoryStore(historyFile, FIXED_CLOCK, Files::copy);
    }

    private static FileCalculationHistoryStore newStoreWithFailingCopy(Path historyFile) {
        return new FileCalculationHistoryStore(historyFile, FIXED_CLOCK, (source, target) -> {
            throw new IOException("simulated copy failure");
        });
    }

    private static List<String> listFileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
