package Tests;

import Calcul.CalculationHistoryStore;
import Calcul.FileCalculationHistoryStore;
import Calcul.HistoryLoadResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileCalculationHistoryStoreTest {
    private static final String VALID_ADDITION = "ADDITION\t1\t2\t3";
    private static final String VALID_SUBTRACTION = "SUBTRACTION\t5\t2\t3";
    private static final String VALID_MULTIPLICATION = "MULTIPLICATION\t2\t3\t6";

    @Test
    void testInvalidLineIsSkippedAndCountedWhileOthersAreRestored(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, VALID_ADDITION, "bad-data", VALID_SUBTRACTION, VALID_MULTIPLICATION);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(3, result.records().size(), "Valid lines around an invalid one should be restored.");
        assertEquals(1, result.skippedLines(), "Exactly one line should be reported as skipped.");
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
        assertEquals("5 - 2 = 3", result.records().get(1).toString());
        assertEquals("2 * 3 = 6", result.records().get(2).toString());
    }

    @Test
    void testLeadingUtf8ByteOrderMarkIsIgnored(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "﻿" + VALID_ADDITION + "\n" + VALID_SUBTRACTION + "\n", StandardCharsets.UTF_8);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(2, result.records().size(), "A BOM must not invalidate the first line.");
        assertEquals(0, result.skippedLines(), "A BOM must not be counted as a skipped line.");
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
    }

    @Test
    void testCrlfLineEndingsAreAccepted(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, VALID_ADDITION + "\r\n" + VALID_SUBTRACTION + "\r\n", StandardCharsets.UTF_8);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(2, result.records().size(), "CRLF files should load completely.");
        assertEquals(0, result.skippedLines());
    }

    @Test
    void testBlankLinesAreIgnoredWithoutBeingCounted(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "", VALID_ADDITION, "   ", VALID_SUBTRACTION, "");

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(2, result.records().size());
        assertEquals(0, result.skippedLines(), "Blank lines are not invalid entries.");
    }

    @Test
    void testInconsistentOperandIsSkippedInsteadOfFallingBackToStoredResult(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION\tabc\t2\t5");

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(0, result.records().size(), "An unparsable operand must never be displayed as a calculation.");
        assertEquals(1, result.skippedLines());
    }

    @Test
    void testStoredResultColumnIsIgnoredOnRestore(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "MULTIPLICATION\t2\t3\t999");

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(1, result.records().size());
        assertEquals("2 * 3 = 6", result.records().get(0).toString(), "The result is recomputed, never read from the file.");
    }

    @Test
    void testUnknownOperationIsSkipped(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "MODULO\t5\t2\t1");

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(0, result.records().size());
        assertEquals(1, result.skippedLines());
    }

    @Test
    void testLinesWithThreeOrFiveColumnsAreSkipped(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION\t1\t2", "ADDITION\t1\t2\t3\textra", VALID_ADDITION);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(1, result.records().size());
        assertEquals(2, result.skippedLines(), "3-column and 5-column lines are invalid.");
    }

    @Test
    void testMissingFileLoadsAsEmptyHistory(@TempDir Path tempDirectory) throws Exception {
        HistoryLoadResult result = new FileCalculationHistoryStore(tempDirectory.resolve("history.tsv")).loadHistory();

        assertTrue(result.records().isEmpty());
        assertEquals(0, result.skippedLines());
    }

    @Test
    void testNoopStoreLoadsAnEmptyResult() throws Exception {
        HistoryLoadResult result = CalculationHistoryStore.noop().loadHistory();

        assertTrue(result.records().isEmpty(), "The noop store has no records.");
        assertEquals(0, result.skippedLines());
        assertEquals(null, result.backupFile());
    }

    private static Path writeHistory(Path directory, String... lines) throws Exception {
        Path historyFile = directory.resolve("history.tsv");
        Files.writeString(historyFile, String.join("\n", lines), StandardCharsets.UTF_8);
        return historyFile;
    }
}
