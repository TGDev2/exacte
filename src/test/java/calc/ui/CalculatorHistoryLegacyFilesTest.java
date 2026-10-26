package calc.ui;

import calc.engine.CalculatorEngine;
import calc.engine.FileCalculationHistoryStore;
import calc.engine.HistoryLoadResult;
import calc.engine.RejectedLine.Cause;
import java.io.IOException;
import java.io.InputStream;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Loads every reference file of {@code legacy/v1} and pins what is restored, rejected and backed up. */
public class CalculatorHistoryLegacyFilesTest {
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2024-01-02T03:04:05Z"), ZoneOffset.UTC);

    @ParameterizedTest(name = "{0}: {1} restored, {2} rejected ({3}), backup {4}")
    @CsvSource(nullValues = "-", value = {
        "nominal.tsv,            6, 0, -,                          false",
        "rounded.tsv,            1, 0, -,                          false",
        "legacy-float.tsv,       1, 0, -,                          false",
        "crlf.tsv,               3, 0, -,                          false",
        "bom.tsv,                2, 0, -,                          false",
        "bom-header.tsv,         1, 0, -,                          false",
        "empty.tsv,              0, 0, -,                          false",
        "one-corrupted-line.tsv, 2, 1, WRONG_COLUMN_COUNT,         true",
        "inconsistent.tsv,       0, 1, INVALID_OPERAND,            true",
        "wrong-result.tsv,       0, 1, INCONSISTENT_RESULT,        true",
        "future-version.tsv,     0, 1, UNSUPPORTED_FORMAT_VERSION, true",
    })
    void testEveryReferenceFileLoadsAsExpected(
            String fileName, int restored, int rejected, Cause cause, boolean backupCreated, @TempDir Path directory)
            throws Exception {
        Path historyFile = copyFixture(fileName, directory);
        byte[] original = Files.readAllBytes(historyFile);

        HistoryLoadResult result = new FileCalculationHistoryStore(historyFile, FIXED_CLOCK, Files::copy).loadHistory();

        assertEquals(restored, result.records().size(), "entries restored");
        assertEquals(rejected, result.skippedLines(), "lines rejected");
        assertEquals(rejected, result.rejections().size());
        if (cause != null) {
            assertEquals(cause, result.rejections().get(0).cause());
        }
        if (backupCreated) {
            assertNotNull(result.backupFile(), "a backup is needed when something was rejected");
            assertArrayEquals(original, Files.readAllBytes(result.backupFile()), "the copy is byte-for-byte the original");
        } else {
            assertNull(result.backupFile(), "no backup when nothing was rejected");
            assertEquals(List.of("history.tsv"), fileNames(directory), "no stray file next to the history");
        }
        assertArrayEquals(original, Files.readAllBytes(historyFile), "loading never modifies the original");
    }

    @Test
    void testCorruptedLineScenarioKeepsTheValidLinesAndTheOriginalCopy(@TempDir Path directory) throws Exception {
        Path historyFile = copyFixture("one-corrupted-line.tsv", directory);
        byte[] original = Files.readAllBytes(historyFile);

        CalculatorPresenter presenter = new CalculatorPresenter(
                new CalculatorEngine(), new FileCalculationHistoryStore(historyFile, FIXED_CLOCK, Files::copy));
        CalculatorViewModel start = presenter.createInitialView();
        CalculatorViewModel afterCalculation = presenter.onCalculate("10", "5");

        Path backup = directory.resolve("history.tsv.20240102-030405.bak");
        assertEquals(
                "2 calculs restaurés, 1 ligne illisible ignorée. Copie de sauvegarde : " + backup,
                start.statusMessage());
        assertEquals(StatusTone.SUCCESS, afterCalculation.statusTone());
        assertArrayEquals(original, Files.readAllBytes(backup), "the backup holds the untouched original, corrupted line included");
        assertEquals(
                List.of("#calc-history v1", "ADDITION\t1\t2\t3", "MULTIPLICATION\t3\t4\t12", "ADDITION\t10\t5\t15"),
                Files.readAllLines(historyFile, StandardCharsets.UTF_8),
                "the valid lines are still in the current file, followed by the new calculation");
        assertFalse(Files.readString(historyFile, StandardCharsets.UTF_8).contains("not a calculation"));
    }

    private static Path copyFixture(String fileName, Path directory) throws IOException {
        Path target = directory.resolve("history.tsv");
        try (InputStream stream = CalculatorHistoryLegacyFilesTest.class.getResourceAsStream("/legacy/v1/" + fileName)) {
            assertNotNull(stream, "missing reference file " + fileName);
            Files.write(target, stream.readAllBytes());
        }
        return target;
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
