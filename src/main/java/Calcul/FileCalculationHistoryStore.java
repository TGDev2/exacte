package Calcul;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FileCalculationHistoryStore implements CalculationHistoryStore {
    private static final Logger LOGGER = Logger.getLogger(FileCalculationHistoryStore.class.getName());
    private static final String FILE_NAME = "history.tsv";
    private static final char BYTE_ORDER_MARK = '\uFEFF';
    private static final int COLUMN_COUNT = 4;
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    @FunctionalInterface
    public interface FileCopier {
        void copy(Path source, Path target) throws IOException;
    }

    private final Path historyFile;
    private final Clock clock;
    private final FileCopier copier;
    private boolean writeProtected;

    public FileCalculationHistoryStore(Path historyFile) {
        this(historyFile, Clock.systemDefaultZone(), Files::copy);
    }

    public FileCalculationHistoryStore(Path historyFile, Clock clock, FileCopier copier) {
        this.historyFile = Objects.requireNonNull(historyFile);
        this.clock = Objects.requireNonNull(clock);
        this.copier = Objects.requireNonNull(copier);
    }

    public static FileCalculationHistoryStore forCurrentUser() {
        String appDataPath = System.getenv("APPDATA");
        if (appDataPath != null && !appDataPath.isBlank()) {
            return new FileCalculationHistoryStore(Path.of(appDataPath, "Calc", FILE_NAME));
        }
        return new FileCalculationHistoryStore(Path.of(System.getProperty("user.home"), ".calc", FILE_NAME));
    }

    @Override
    public HistoryLoadResult loadHistory() throws CalculationHistoryException {
        if (!Files.exists(historyFile)) {
            return new HistoryLoadResult(List.of(), 0, null);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot read history file " + historyFile + ", writes are now refused", exception);
            writeProtected = true;
            throw new CalculationHistoryException(
                    "Impossible de lire l'historique : " + historyFile,
                    exception,
                    backupOriginalFile());
        }

        CalculatorEngine engine = new CalculatorEngine();
        List<CalculationRecord> history = new ArrayList<>();
        int skippedLines = 0;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith(String.valueOf(BYTE_ORDER_MARK))) {
                line = line.substring(1);
            }
            if (line.isBlank()) {
                continue;
            }
            try {
                history.add(parseLine(engine, line));
            } catch (IllegalArgumentException | ArithmeticException | CalculationException exception) {
                skippedLines++;
                LOGGER.warning("Skipped line " + (index + 1) + " of " + historyFile + ": " + exception.getMessage());
            }
        }
        Path backupFile = skippedLines > 0 ? backupOriginalFile() : null;
        return new HistoryLoadResult(history, skippedLines, backupFile);
    }

    @Override
    public void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException {
        if (writeProtected) {
            LOGGER.warning("Refused to write " + historyFile + ": the original file is protected");
            throw new CalculationHistoryException(
                    "Ecriture refusee pour proteger le fichier d'origine : " + historyFile);
        }

        try {
            if (history.isEmpty()) {
                Files.deleteIfExists(historyFile);
                return;
            }

            Path parentDirectory = historyFile.getParent();
            if (parentDirectory != null) {
                Files.createDirectories(parentDirectory);
            }

            List<String> serializedHistory = new ArrayList<>();
            for (CalculationRecord record : history) {
                serializedHistory.add(serialize(record));
            }
            Files.write(historyFile, serializedHistory, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot write history file " + historyFile, exception);
            throw new CalculationHistoryException("Impossible d'enregistrer l'historique : " + historyFile, exception);
        }
    }

    private Path backupOriginalFile() {
        try {
            return copyToBackup();
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot back up " + historyFile + ", writes are now refused", exception);
            writeProtected = true;
            return null;
        }
    }

    private Path copyToBackup() throws IOException {
        String baseName = historyFile.getFileName() + "." + LocalDateTime.now(clock).format(BACKUP_TIMESTAMP);
        for (int attempt = 0; ; attempt++) {
            String suffix = attempt == 0 ? "" : "-" + attempt;
            Path backupFile = historyFile.resolveSibling(baseName + suffix + ".bak");
            try {
                copier.copy(historyFile, backupFile);
                return backupFile;
            } catch (FileAlreadyExistsException exception) {
                // never overwrite an existing copy: try the next numbered name
            }
        }
    }

    private CalculationRecord parseLine(CalculatorEngine engine, String line) throws CalculationException {
        String[] columns = line.split("\t", -1);
        if (columns.length != COLUMN_COUNT) {
            throw new IllegalArgumentException("expected " + COLUMN_COUNT + " columns but found " + columns.length);
        }

        Operation operation = Operation.valueOf(columns[0]);
        return new CalculationRecord(
                operation,
                columns[1],
                columns[2],
                engine.calculate(operation, columns[1], columns[2]).getResultValue());
    }

    private String serialize(CalculationRecord record) {
        return record.getOperation().name()
                + "\t"
                + record.getFirstValue()
                + "\t"
                + record.getSecondValue()
                + "\t"
                + NumberFormatter.serialize(record.getResultValue());
    }
}
