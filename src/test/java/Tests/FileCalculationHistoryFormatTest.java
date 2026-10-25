package Tests;

import Calcul.CalculationHistoryException;
import Calcul.CalculationRecord;
import Calcul.FileCalculationHistoryStore;
import Calcul.HistoryLoadResult;
import Calcul.Operation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileCalculationHistoryFormatTest {
    @Test
    void testSuccessfulSaveLeavesNoTmpFileAndStartsWithTheHeader(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        FileCalculationHistoryStore store = new FileCalculationHistoryStore(historyFile);

        store.saveHistory(List.of(new CalculationRecord(Operation.ADDITION, "1", "2", new BigDecimal("3"))));

        assertFalse(Files.exists(tempDirectory.resolve("history.tsv.tmp")), "No temporary file must survive a successful save.");
        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        assertEquals("#calc-history v1", lines.get(0));
        assertEquals("ADDITION\t1\t2\t3", lines.get(1));
    }

    @Test
    void testWriteFailureLeavesTheOriginalFileIntact(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "#calc-history v1\nADDITION\t1\t2\t3\n", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);
        Files.createDirectory(tempDirectory.resolve("history.tsv.tmp"));
        FileCalculationHistoryStore store = new FileCalculationHistoryStore(historyFile);

        assertThrows(
                CalculationHistoryException.class,
                () -> store.saveHistory(List.of(new CalculationRecord(Operation.ADDITION, "1", "1", new BigDecimal("2")))));

        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "The original file must survive a failed write.");
        assertEquals(
                List.of("ADDITION\t1\t2\t3"),
                Files.readAllLines(historyFile, StandardCharsets.UTF_8).subList(1, 2),
                "The original content must stay readable.");
    }

    @Test
    void testLegacyFileWithoutHeaderLoadsThenGetsRewrittenWithTheHeader(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "ADDITION\t0,1\t0,2\t0.30000000000000004", StandardCharsets.UTF_8);

        FileCalculationHistoryStore store = new FileCalculationHistoryStore(historyFile);
        HistoryLoadResult loaded = store.loadHistory();

        assertEquals(1, loaded.records().size(), "The legacy entry without a header must still load.");
        assertEquals(0, loaded.skippedLines());
        assertEquals("0,1 + 0,2 = 0,3", loaded.records().get(0).toString());

        store.saveHistory(loaded.records());

        List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        assertEquals("#calc-history v1", lines.get(0), "The next save must add the version header.");
        assertEquals("ADDITION\t0,1\t0,2\t0.3", lines.get(1));
    }

    @Test
    void testFutureFormatVersionIsNeverReadNorOverwritten(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "#calc-history v2\nADDITION\t1\t2\t3\n", StandardCharsets.UTF_8);
        byte[] originalBytes = Files.readAllBytes(historyFile);
        FileCalculationHistoryStore store = new FileCalculationHistoryStore(historyFile);

        HistoryLoadResult result = store.loadHistory();

        assertTrue(result.records().isEmpty(), "A newer format must not be interpreted as data.");
        assertNotNull(result.backupFile(), "A backup copy of the unreadable file must be kept.");
        assertArrayEquals(originalBytes, Files.readAllBytes(result.backupFile()));
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "Loading must not modify the file.");

        assertThrows(
                CalculationHistoryException.class,
                () -> store.saveHistory(List.of(new CalculationRecord(Operation.ADDITION, "9", "9", new BigDecimal("18")))),
                "A future format version must permanently block writes.");
        assertArrayEquals(originalBytes, Files.readAllBytes(historyFile), "No write may ever replace the newer-format file.");
    }

    @Test
    void testByteOrderMarkBeforeTheVersionHeaderIsIgnored(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "﻿#calc-history v1\nADDITION\t1\t2\t3\n", StandardCharsets.UTF_8);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(1, result.records().size());
        assertEquals(0, result.skippedLines());
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
    }

    @Test
    void testOperationPersistenceKeysRoundTripForEveryOperation(@TempDir Path tempDirectory) throws Exception {
        List<String> expectedKeys =
                List.of("ADDITION", "SUBTRACTION", "MULTIPLICATION", "DIVISION", "POWER", "ROOT");
        Operation[] operations = Operation.values();
        assertEquals(expectedKeys.size(), operations.length, "The expected key table must cover every operation.");

        for (int index = 0; index < operations.length; index++) {
            Operation operation = operations[index];
            String secondOperand = operation.requiresSecondOperand() ? "2" : "";
            Path historyFile = tempDirectory.resolve("history-" + index + ".tsv");
            CalculationRecord record = new CalculationRecord(operation, "4", secondOperand, new BigDecimal("4"));
            FileCalculationHistoryStore store = new FileCalculationHistoryStore(historyFile);

            store.saveHistory(List.of(record));

            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            String writtenKey = lines.get(1).split("\t", -1)[0];
            assertEquals(expectedKeys.get(index), writtenKey, "The written key must match the persistence contract table.");

            HistoryLoadResult reloaded = new FileCalculationHistoryStore(historyFile).loadHistory();
            assertEquals(1, reloaded.records().size());
            assertEquals(operation, reloaded.records().get(0).getOperation(), "The key must read back as the same operation.");
        }
    }
}
