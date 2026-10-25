package Calcul;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FileCalculationHistoryStore implements CalculationHistoryStore {
    private static final String FILE_NAME = "history.tsv";

    private final Path historyFile;

    public FileCalculationHistoryStore(Path historyFile) {
        this.historyFile = Objects.requireNonNull(historyFile);
    }

    public static FileCalculationHistoryStore forCurrentUser() {
        String appDataPath = System.getenv("APPDATA");
        if (appDataPath != null && !appDataPath.isBlank()) {
            return new FileCalculationHistoryStore(Path.of(appDataPath, "Calc", FILE_NAME));
        }
        return new FileCalculationHistoryStore(Path.of(System.getProperty("user.home"), ".calc", FILE_NAME));
    }

    @Override
    public List<CalculationRecord> loadHistory() throws CalculationHistoryException {
        if (!Files.exists(historyFile)) {
            return List.of();
        }

        try {
            List<String> lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
            List<CalculationRecord> history = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                history.add(parseLine(line));
            }
            return history;
        } catch (IOException exception) {
            throw new CalculationHistoryException("Impossible de lire l'historique.", exception);
        }
    }

    @Override
    public void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException {
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
            throw new CalculationHistoryException("Impossible d'enregistrer l'historique.", exception);
        }
    }

    private CalculationRecord parseLine(String line) throws CalculationHistoryException {
        String[] columns = line.split("\t", -1);
        if (columns.length != 4) {
            throw new CalculationHistoryException("Format d'historique invalide.");
        }

        try {
            Operation operation = Operation.valueOf(columns[0]);
            BigDecimal resultValue = restoreResult(operation, columns[1], columns[2], columns[3]);
            return new CalculationRecord(operation, columns[1], columns[2], resultValue);
        } catch (IllegalArgumentException exception) {
            throw new CalculationHistoryException("Une entree d'historique est invalide.", exception);
        }
    }

    private BigDecimal restoreResult(
            Operation operation,
            String firstValue,
            String secondValue,
            String serializedResult) throws CalculationHistoryException {
        try {
            return new CalculatorEngine().calculate(operation, firstValue, secondValue).getResultValue();
        } catch (CalculationException exception) {
            try {
                return DecimalPolicy.restorePersistedValue(serializedResult);
            } catch (NumberFormatException parseException) {
                throw new CalculationHistoryException("Une entree d'historique est invalide.", parseException);
            }
        }
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
