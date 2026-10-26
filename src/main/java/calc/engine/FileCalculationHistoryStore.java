package calc.engine;

import java.io.IOException;
import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import calc.number.Rational;
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
import java.util.Optional;
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
                int version = parseVersion(headerMatch.group(1));
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

    // The header only holds digits, so the sole way to fail is a number too large for an int: that is a version
    // newer than any this program knows, not a reason to crash the loader.
    private static int parseVersion(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
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
        CalculationRecord recomputed;
        try {
            recomputed = engine.calculate(operation, columns[1], columns[2]);
        } catch (CalculationException | ArithmeticException exception) {
            String reason = exception.getMessage() == null ? "the operands cannot be recalculated" : exception.getMessage();
            throw new LineRejection(RejectedLine.Cause.INVALID_OPERAND, reason);
        }
        checkStoredResult(columns[3], recomputed.getResult());
        return recomputed;
    }

    /**
     * The stored result must agree with the exact recalculation (docs/adr/0004-regles-affichage.md, section 9). It is
     * accepted when it lies in the recalculated value (the exact value or the canonical point of a root), when it is
     * the recalculated value rounded under one of the version 1 policies, or when it rounds to the same number as the
     * recalculated value under the last version 1 policy (the floating-point drift of the oldest versions,
     * 0.30000000000000004). Parsing is bounded by the core limits, so a hostile 1E999999999 is never expanded.
     */
    private static void checkStoredResult(String storedText, CertifiedValue recomputed) throws LineRejection {
        Rational stored;
        try {
            stored = Rational.parse(storedText.trim());
        } catch (NumericException exception) {
            throw new LineRejection(RejectedLine.Cause.UNREADABLE_RESULT, exception.error() == NumericError.MALFORMED_INPUT
                    ? "the result column is not a number"
                    : "the result column is out of range");
        }
        if (recomputed.contains(stored)) {
            return;
        }
        Optional<BigDecimal> legacy = LegacyRounding.of(recomputed, LegacyRounding::significant);
        if (legacy.isPresent() && (LegacyRounding.same(legacy.get(), stored)
                || LegacyRounding.significant(stored).compareTo(legacy.get()) == 0)) {
            return;
        }
        Optional<BigDecimal> older = LegacyRounding.of(recomputed, LegacyRounding::twelveDecimals);
        if (older.isPresent() && LegacyRounding.same(older.get(), stored)) {
            return;
        }
        throw new LineRejection(RejectedLine.Cause.INCONSISTENT_RESULT, "the stored result differs from the recalculation");
    }

    private String serialize
(CalculationRecord record) {
        return keyForOperation(record.getOperation())
                + "\t"
                + record.getFirstValue()
                + "\t"
                + record.getSecondValue()
                + "\t"
                + record.getReusableValue();
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
