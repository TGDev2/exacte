package calc.engine;

import java.io.IOException;
import java.math.BigDecimal;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileCalculationHistoryStore implements CalculationHistoryStore {
    private static final Logger LOGGER = Logger.getLogger(FileCalculationHistoryStore.class.getName());
    private static final String FILE_NAME = "history.tsv";
    private static final char BYTE_ORDER_MARK = '﻿';
    private static final int COLUMN_COUNT = 4;
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int SUPPORTED_FORMAT_VERSION = 1;
    private static final String FORMAT_HEADER = "#calc-history v" + SUPPORTED_FORMAT_VERSION;
    private static final Pattern FORMAT_HEADER_PATTERN = Pattern.compile("#calc-history v(\\d+)");
    // A stored result is plain text of at most this many characters and this scale; anything bigger is refused
    // before being parsed, so a hostile exponent such as 1E999999999 never gets expanded.
    private static final int MAX_STORED_RESULT_LENGTH = CalculationLimits.MAX_INPUT_LENGTH + 100;
    private static final int MAX_STORED_RESULT_SCALE = 2 * CalculationLimits.MAX_RESULT_DIGITS;

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
            return new HistoryLoadResult(List.of(), List.of(), null);
        }

        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(historyFile, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot read history file " + historyFile + ", writes are now refused", exception);
            writeProtected = true;
            throw new CalculationHistoryException(
                    "Impossible de lire l'historique : " + historyFile,
                    exception,
                    backupOriginalFile());
        }

        if (!lines.isEmpty() && lines.get(0).startsWith(String.valueOf(BYTE_ORDER_MARK))) {
            lines.set(0, lines.get(0).substring(1));
        }

        int bodyStart = 0;
        if (!lines.isEmpty()) {
            Matcher headerMatch = FORMAT_HEADER_PATTERN.matcher(lines.get(0));
            if (headerMatch.matches()) {
                int version = Integer.parseInt(headerMatch.group(1));
                if (version != SUPPORTED_FORMAT_VERSION) {
                    return refuseUnsupportedVersion(version, lines);
                }
                bodyStart = 1;
            }
        }

        CalculatorEngine engine = new CalculatorEngine();
        List<CalculationRecord> history = new ArrayList<>();
        List<RejectedLine> rejections = new ArrayList<>();
        for (int index = bodyStart; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            try {
                history.add(parseLine(engine, line));
            } catch (LineRejection rejection) {
                RejectedLine rejected = new RejectedLine(index + 1, rejection.cause(), rejection.getMessage());
                rejections.add(rejected);
                LOGGER.warning("Skipped line " + rejected.lineNumber() + " of " + historyFile + " ("
                        + rejected.cause() + "): " + rejected.detail());
            }
        }
        Path backupFile = rejections.isEmpty() ? null : backupOriginalFile();
        return new HistoryLoadResult(history, rejections, backupFile);
    }

    @Override
    public void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException {
        if (writeProtected) {
            LOGGER.warning("Refused to write " + historyFile + ": the original file is protected");
            throw new CalculationHistoryException(
                    "Écriture refusée pour protéger le fichier d'origine : " + historyFile);
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
            serializedHistory.add(FORMAT_HEADER);
            for (CalculationRecord record : history) {
                serializedHistory.add(serialize(record));
            }
            AtomicFileWriter.write(historyFile, (String.join("\n", serializedHistory) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot write history file " + historyFile, exception);
            throw new CalculationHistoryException("Impossible d'enregistrer l'historique : " + historyFile, exception);
        }
    }

    private HistoryLoadResult refuseUnsupportedVersion(int version, List<String> lines) {
        LOGGER.warning("Unsupported history format version " + version + " in " + historyFile + ", writes are now refused");
        writeProtected = true;
        String detail = "format version " + version + " is not supported (latest: " + SUPPORTED_FORMAT_VERSION + ")";
        List<RejectedLine> rejections = new ArrayList<>();
        for (int index = 1; index < lines.size(); index++) {
            if (!lines.get(index).isBlank()) {
                rejections.add(new RejectedLine(index + 1, RejectedLine.Cause.UNSUPPORTED_FORMAT_VERSION, detail));
            }
        }
        if (rejections.isEmpty()) {
            rejections.add(new RejectedLine(1, RejectedLine.Cause.UNSUPPORTED_FORMAT_VERSION, detail));
        }
        return new HistoryLoadResult(List.of(), rejections, backupOriginalFile());
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
                LOGGER.info("Backed up " + historyFile + " to " + backupFile);
                return backupFile;
            } catch (FileAlreadyExistsException exception) {
                // never overwrite an existing copy: try the next numbered name
            }
        }
    }

    private CalculationRecord parseLine(CalculatorEngine engine, String line) throws LineRejection {
        String[] columns = line.split("\t", -1);
        if (columns.length != COLUMN_COUNT) {
            throw new LineRejection(
                    RejectedLine.Cause.WRONG_COLUMN_COUNT, "expected " + COLUMN_COUNT + " columns but found " + columns.length);
        }

        Operation operation = operationFromKey(columns[0]);
        BigDecimal recomputed;
        try {
            recomputed = engine.calculate(operation, columns[1], columns[2]).getResultValue();
        } catch (CalculationException | ArithmeticException exception) {
            String reason = exception.getMessage() == null ? "the operands cannot be recalculated" : exception.getMessage();
            throw new LineRejection(RejectedLine.Cause.INVALID_OPERAND, reason);
        }
        checkStoredResult(columns[3], recomputed);
        return new CalculationRecord(operation, columns[1], columns[2], recomputed);
    }

    /**
     * The stored result must agree with the recalculation. Two deviations are tolerated: the result rounded under
     * the current rounding policy, and the floating-point drift of the oldest versions (0.30000000000000004), which
     * rounds to the right value under that same policy.
     */
    private static void checkStoredResult(String storedText, BigDecimal recomputed) throws LineRejection {
        BigDecimal stored;
        try {
            if (storedText.length() > MAX_STORED_RESULT_LENGTH) {
                throw new NumberFormatException("too long");
            }
            stored = new BigDecimal(storedText.trim());
        } catch (NumberFormatException exception) {
            throw new LineRejection(RejectedLine.Cause.UNREADABLE_RESULT, "the result column is not a number");
        }
        if (Math.abs((long) stored.scale()) > MAX_STORED_RESULT_SCALE) {
            throw new LineRejection(RejectedLine.Cause.UNREADABLE_RESULT, "the result column is out of range");
        }
        if (stored.compareTo(recomputed) != 0 && DecimalPolicy.roundApproximation(stored).compareTo(recomputed) != 0) {
            throw new LineRejection(RejectedLine.Cause.INCONSISTENT_RESULT, "the stored result differs from the recalculation");
        }
    }

    private String serialize(CalculationRecord record) {
        return keyForOperation(record.getOperation())
                + "\t"
                + record.getFirstValue()
                + "\t"
                + record.getSecondValue()
                + "\t"
                + DecimalPolicy.serialize(record.getResultValue());
    }

    private static String keyForOperation(Operation operation) {
        return switch (operation) {
            case ADDITION -> "ADDITION";
            case SUBTRACTION -> "SUBTRACTION";
            case MULTIPLICATION -> "MULTIPLICATION";
            case DIVISION -> "DIVISION";
            case POWER -> "POWER";
            case ROOT -> "ROOT";
        };
    }

    private static Operation operationFromKey(String key) throws LineRejection {
        return switch (key) {
            case "ADDITION" -> Operation.ADDITION;
            case "SUBTRACTION" -> Operation.SUBTRACTION;
            case "MULTIPLICATION" -> Operation.MULTIPLICATION;
            case "DIVISION" -> Operation.DIVISION;
            case "POWER" -> Operation.POWER;
            case "ROOT" -> Operation.ROOT;
            default -> throw new LineRejection(RejectedLine.Cause.UNKNOWN_OPERATION, "unknown operation key");
        };
    }

    private static final class LineRejection extends Exception {
        private static final long serialVersionUID = 1L;

        private final RejectedLine.Cause cause;

        LineRejection(RejectedLine.Cause cause, String detail) {
            super(detail);
            this.cause = cause;
        }

        RejectedLine.Cause cause() {
            return cause;
        }
    }
}
