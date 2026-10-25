package Calcul;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class FileCalculationHistoryStore implements CalculationHistoryStore {
    private static final String FILE_NAME = "history.tsv";
    private static final char BYTE_ORDER_MARK = '﻿';
    private static final int COLUMN_COUNT = 4;

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
    public HistoryLoadResult loadHistory() throws CalculationHistoryException {
        if (!Files.exists(historyFile)) {
            return new HistoryLoadResult(List.of(), 0, null);
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(historyFile, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new CalculationHistoryException("Impossible de lire l'historique : " + historyFile, exception);
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
            }
        }
        return new HistoryLoadResult(history, skippedLines, null);
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
            throw new CalculationHistoryException("Impossible d'enregistrer l'historique : " + historyFile, exception);
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
