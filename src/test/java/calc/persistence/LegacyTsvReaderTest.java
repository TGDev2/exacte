package calc.persistence;

import calc.engine.CalculationHistoryStore;
import calc.engine.HistoryLoadResult;
import calc.engine.RejectedLine;
import calc.persistence.LegacyTsvReader.LegacyHistory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LegacyTsvReaderTest {
    private static final String VALID_ADDITION = "ADDITION\t1\t2\t3";
    private static final String VALID_SUBTRACTION = "SUBTRACTION\t5\t2\t3";
    private static final String VALID_MULTIPLICATION = "MULTIPLICATION\t2\t3\t6";

    @Test
    void testInvalidLineIsSkippedAndCountedWhileOthersAreRestored(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, VALID_ADDITION, "bad-data", VALID_SUBTRACTION, VALID_MULTIPLICATION);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(3, result.records().size(), "Valid lines around an invalid one should be restored.");
        assertEquals(1, result.rejections().size(), "Exactly one line should be reported as skipped.");
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
        assertEquals("5 - 2 = 3", result.records().get(1).toString());
        assertEquals("2 * 3 = 6", result.records().get(2).toString());
    }

    @Test
    void testLeadingUtf8ByteOrderMarkIsIgnored(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "\uFEFF" + VALID_ADDITION + "\n" + VALID_SUBTRACTION + "\n", StandardCharsets.UTF_8);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(2, result.records().size(), "A BOM must not invalidate the first line.");
        assertEquals(0, result.rejections().size(), "A BOM must not be counted as a skipped line.");
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
    }

    @Test
    void testCrlfLineEndingsAreAccepted(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, VALID_ADDITION + "\r\n" + VALID_SUBTRACTION + "\r\n", StandardCharsets.UTF_8);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(2, result.records().size(), "CRLF files should load completely.");
        assertEquals(0, result.rejections().size());
    }

    @Test
    void testBlankLinesAreIgnoredWithoutBeingCounted(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "", VALID_ADDITION, "   ", VALID_SUBTRACTION, "");

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(2, result.records().size());
        assertEquals(0, result.rejections().size(), "Blank lines are not invalid entries.");
    }

    @Test
    void testInconsistentOperandIsSkippedInsteadOfFallingBackToStoredResult(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION\tabc\t2\t5");

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(0, result.records().size(), "An unparsable operand must never be displayed as a calculation.");
        assertEquals(1, result.rejections().size());
    }

    @Test
    void testStoredResultContradictingTheOperandsIsRejectedAndCounted(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION	1	2	5", "MULTIPLICATION	2	3	999", VALID_ADDITION);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(1, result.records().size(), "Only the consistent entry may be restored.");
        assertEquals("1 + 2 = 3", result.records().get(0).toString());
        assertEquals(2, result.rejections().size(), "Both inconsistent entries are rejected and counted.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADDITION	1	2	3.5", "ADDITION	1	2	abc", "ADDITION	1	2	", "DIVISION	1	3	0.33", "ROOT	9		4"})
    void testUnreadableOrContradictingStoredResultIsRejected(String line, @TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, line);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(0, result.records().size(), line);
        assertEquals(1, result.rejections().size(), line);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "ADDITION	0,1	0,2	0.30000000000000004",
        "DIVISION	1	3	0.333333333333",
        "DIVISION	1	3	0.3333333333333333",
        "ADDITION	1	2	3.0",
        "POWER	2	10	1024"})
    void testRoundedAndHistoricalFloatResultsAreTolerated(String line, @TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, line);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(1, result.records().size(), line);
        assertEquals(0, result.rejections().size(), line);
    }

    @Test
    void testEveryRejectionCarriesItsLineNumberAndCause(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(
                tempDirectory,
                "#calc-history v1",
                VALID_ADDITION,
                "ADDITION\t1\t2",
                "MODULO\t5\t2\t1",
                "ADDITION\tabc\t2\t5",
                "ADDITION\t1\t2\tnope",
                "ADDITION\t1\t2\t5",
                VALID_SUBTRACTION);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(2, result.records().size());
        assertEquals(
                List.of(
                        new RejectedLine(3, RejectedLine.Cause.WRONG_COLUMN_COUNT, "expected 4 columns but found 3"),
                        new RejectedLine(4, RejectedLine.Cause.UNKNOWN_OPERATION, "unknown operation key"),
                        new RejectedLine(5, RejectedLine.Cause.INVALID_OPERAND,
                                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point."),
                        new RejectedLine(6, RejectedLine.Cause.UNREADABLE_RESULT, "the result column is not a number"),
                        new RejectedLine(7, RejectedLine.Cause.INCONSISTENT_RESULT,
                                "the stored result differs from the recalculation")),
                result.rejections());
        assertEquals(5, result.rejections().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E999999999", "-1E999999999", "1E-999999999", "NaN", "Infinity", "0x10"})
    void testHostileStoredResultIsRejectedImmediately(String storedResult, @TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION\t1\t2\t" + storedResult);

        LegacyHistory result = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> LegacyTsvReader.read(historyFile));

        assertEquals(0, result.records().size(), storedResult);
        assertEquals(1, result.rejections().size(), storedResult);
    }

    @Test
    void testUnknownOperationIsSkipped(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "MODULO\t5\t2\t1");

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(0, result.records().size());
        assertEquals(1, result.rejections().size());
    }

    @Test
    void testLinesWithThreeOrFiveColumnsAreSkipped(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = writeHistory(tempDirectory, "ADDITION\t1\t2", "ADDITION\t1\t2\t3\textra", VALID_ADDITION);

        LegacyHistory result = LegacyTsvReader.read(historyFile);

        assertEquals(1, result.records().size());
        assertEquals(2, result.rejections().size(), "3-column and 5-column lines are invalid.");
    }

    @Test
    void testNoopStoreLoadsAnEmptyResult() throws Exception {
        HistoryLoadResult result = CalculationHistoryStore.noop().loadHistory();

        assertTrue(result.records().isEmpty(), "The noop store has no records.");
        assertEquals(0, result.rejections().size());
        assertEquals(null, result.backupFile());
    }

    private static Path writeHistory(Path directory, String... lines) throws Exception {
        Path historyFile = directory.resolve("history.tsv");
        Files.writeString(historyFile, String.join("\n", lines), StandardCharsets.UTF_8);
        return historyFile;
    }
}
