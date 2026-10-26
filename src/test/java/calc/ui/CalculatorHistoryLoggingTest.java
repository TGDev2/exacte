package calc.ui;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculatorEngine;
import calc.persistence.JsonHistoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorHistoryLoggingTest {
    private final List<LogRecord> records = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger storeLogger = Logger.getLogger("calc.persistence.JsonHistoryStore");
    private final Logger presenterLogger = Logger.getLogger("calc.ui.CalculatorPresenter");

    @BeforeEach
    void attachHandler() {
        storeLogger.addHandler(handler);
        presenterLogger.addHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        storeLogger.removeHandler(handler);
        presenterLogger.removeHandler(handler);
    }

    @Test
    void testRejectedEntriesAreLoggedWithPathPositionAndReasonButNotTheirContent(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, "{\"format\": \"calc-history\", \"version\": 2, \"entries\": [\n"
                + "{\"op\": \"addition\", \"operands\": [\"1\", \"2\"], \"result\": {\"exact\": true, \"value\": \"3\"}},\n"
                + "{\"op\": \"addition\", \"operands\": [\"123456\"], \"result\": {\"exact\": true, \"value\": \"1\"}},\n"
                + "{\"op\": \"addition\", \"operands\": [\"abc\", \"2\"], \"result\": {\"exact\": true, \"value\": \"5\"}}]}\n",
                StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            store.loadHistory();
        }

        List<LogRecord> warnings = warnings();
        assertEquals(2, warnings.size(), "One warning per rejected entry.");
        assertTrue(warnings.get(0).getMessage().contains(historyFile.toString()), "The warning must carry the file path.");
        assertTrue(warnings.get(0).getMessage().contains("entry 2"), "The warning must carry the entry number.");
        assertTrue(warnings.get(0).getMessage().contains("expected 2 operands"), "The warning must carry the reason.");
        assertTrue(warnings.get(1).getMessage().contains("entry 3"));
        assertFalse(warnings.stream().anyMatch(warning -> warning.getMessage().contains("123456") || warning.getMessage().contains("abc")),
                "The content of an entry is never logged.");
    }

    @Test
    void testReadFailureIsLoggedWithPathAndCause(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.write(historyFile, new byte[] {(byte) 0xFF, (byte) 0xFE, '\n'});

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            new CalculatorPresenter(new CalculatorEngine(), store);
        }

        LogRecord warning = warnings().get(0);
        assertTrue(warning.getMessage().contains(historyFile.toString()));
        assertNotNull(warning.getThrown(), "The cause must be logged.");
    }

    @Test
    void testBackupFailureIsLoggedWithPathAndCause(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, "bad-data\n", StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory, null, Clock.systemDefaultZone(), (source, target) -> {
            throw new IOException("simulated copy failure");
        }, Files::move)) {
            assertThrows(CalculationHistoryException.class, store::loadHistory);
        }

        LogRecord backupWarning = warnings().stream()
                .filter(record -> record.getMessage().startsWith("Cannot back up"))
                .findFirst()
                .orElseThrow();
        assertTrue(backupWarning.getMessage().contains(historyFile.toString()));
        assertEquals("simulated copy failure", backupWarning.getThrown().getMessage());
    }

    @Test
    void testPresenterLogsTheCauseWhenPersistingFails(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.createDirectories(historyFile);

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            new CalculatorPresenter(new CalculatorEngine(), store).onCalculate("1", "1");
        }

        LogRecord presenterWarning = warnings().stream()
                .filter(record -> "calc.ui.CalculatorPresenter".equals(record.getLoggerName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(presenterWarning.getThrown(), "The presenter must log the persistence failure cause.");
        assertTrue(presenterWarning.getThrown().getMessage().contains(historyFile.toString()), "The cause message must carry the path.");
    }

    @Test
    void testPathsMigrationAndLockAreLogged(@TempDir Path tempDirectory) throws Exception {
        Files.writeString(tempDirectory.resolve("history.tsv"), "ADDITION\t1\t2\t3\n", StandardCharsets.UTF_8);
        Logger lockLogger = Logger.getLogger("calc.persistence.StoreLock");
        lockLogger.addHandler(handler);
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            store.loadHistory();
            try (JsonHistoryStore second = JsonHistoryStore.open(tempDirectory)) {
                assertTrue(second.isReadOnly());
            }
        } finally {
            lockLogger.removeHandler(handler);
        }

        List<String> messages = records.stream().map(LogRecord::getMessage).toList();
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("History directory: " + tempDirectory)), messages.toString());
        assertTrue(messages.stream().anyMatch(message -> message.startsWith("Converted ") && message.contains("1 entries")), messages.toString());
        assertTrue(messages.stream().anyMatch(message -> message.contains("history.lock") && message.contains("held by")), messages.toString());
    }

    private List<LogRecord> warnings() {
        return records.stream().filter(record -> record.getLevel().equals(Level.WARNING)).toList();
    }
}
