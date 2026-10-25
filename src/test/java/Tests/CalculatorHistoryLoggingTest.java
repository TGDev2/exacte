package Tests;

import Calcul.CalculatorEngine;
import Calcul.FileCalculationHistoryStore;
import Graphique.CalculatorPresenter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private final Logger storeLogger = Logger.getLogger("Calcul.FileCalculationHistoryStore");
    private final Logger presenterLogger = Logger.getLogger("Graphique.CalculatorPresenter");

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
    void testSkippedLinesAreLoggedWithPathLineNumberAndReason(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "ADDITION\t1\t2\t3\nbad-data\nADDITION\tabc\t2\t5\n", StandardCharsets.UTF_8);

        new FileCalculationHistoryStore(historyFile).loadHistory();

        List<LogRecord> warnings = warnings();
        assertEquals(2, warnings.size(), "One warning per skipped line.");
        assertTrue(warnings.get(0).getMessage().contains(historyFile.toString()), "The warning must carry the file path.");
        assertTrue(warnings.get(0).getMessage().contains("line 2"), "The warning must carry the line number.");
        assertTrue(warnings.get(0).getMessage().contains("expected 4 columns but found 1"), "The warning must carry the reason.");
        assertTrue(warnings.get(1).getMessage().contains("line 3"));
    }

    @Test
    void testReadFailureIsLoggedWithPathAndCause(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.write(historyFile, new byte[] {(byte) 0xFF, (byte) 0xFE, '\n'});

        new CalculatorPresenter(new CalculatorEngine(), new FileCalculationHistoryStore(historyFile));

        LogRecord warning = warnings().get(0);
        assertTrue(warning.getMessage().contains(historyFile.toString()));
        assertNotNull(warning.getThrown(), "The I/O cause must be logged.");
    }

    @Test
    void testBackupFailureIsLoggedWithPathAndCause(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.writeString(historyFile, "bad-data\n", StandardCharsets.UTF_8);

        new FileCalculationHistoryStore(historyFile, Clock.systemDefaultZone(), (source, target) -> {
            throw new IOException("simulated copy failure");
        }).loadHistory();

        LogRecord backupWarning = warnings().stream()
                .filter(record -> record.getMessage().startsWith("Cannot back up"))
                .findFirst()
                .orElseThrow();
        assertTrue(backupWarning.getMessage().contains(historyFile.toString()));
        assertEquals("simulated copy failure", backupWarning.getThrown().getMessage());
    }

    @Test
    void testPresenterLogsTheCauseWhenPersistingFails(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.createDirectories(historyFile);

        new CalculatorPresenter(new CalculatorEngine(), new FileCalculationHistoryStore(historyFile)).onCalculate("1", "1");

        LogRecord presenterWarning = warnings().stream()
                .filter(record -> "Graphique.CalculatorPresenter".equals(record.getLoggerName()))
                .findFirst()
                .orElseThrow();
        assertNotNull(presenterWarning.getThrown(), "The presenter must log the persistence failure cause.");
        assertTrue(presenterWarning.getThrown().getMessage().contains(historyFile.toString()), "The cause message must carry the path.");
    }

    private List<LogRecord> warnings() {
        return records.stream().filter(record -> record.getLevel().equals(Level.WARNING)).toList();
    }
}
