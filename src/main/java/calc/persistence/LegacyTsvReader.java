package calc.persistence;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import calc.number.Rational;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads a version 1 history file ({@code history.tsv}), only to migrate it. It is the tolerant loader of version 1
 * without its side effects: it never copies, writes or protects anything, it only reports what it could restore.
 *
 * <p>A line is four tab-separated columns: the name of the {@code Operation} constant, the first operand, the second
 * operand (empty for a root) and the result; an optional first line {@code #calc-history v1} may follow a byte order mark.
 * Each line is recalculated exactly from its operand texts; the stored result is accepted when it is, as a number, inside
 * the recalculated value, or the recalculated value rounded under one of the version 1 policies, or rounds to the same
 * number as it under the last policy (the floating-point drift of the oldest versions); see
 * docs/adr/0004-regles-affichage.md, section 9. Any other line is rejected and counted.
 */
public final class LegacyTsvReader {
    private static final int SUPPORTED_VERSION = 1;
    private static final int COLUMN_COUNT = 4;
    private static final Pattern HEADER = Pattern.compile("#calc-history v(\\d+)");

    /**
     * What a version 1 file held.
     *
     * @param newerVersion the version of a header newer than 1: nothing is read from such a file
     */
    public record LegacyHistory(List<CalculationRecord> records, List<RejectedLine> rejections, OptionalInt newerVersion) {
        public LegacyHistory {
            records = List.copyOf(records);
            rejections = List.copyOf(rejections);
        }
    }

    private LegacyTsvReader() {
    }

    /**
     * @throws HistoryFormatException if the file is larger than {@link StorageLimits#MAX_FILE_BYTES} or is not UTF-8
     * @throws IOException            if it cannot be read
     */
    public static LegacyHistory read(Path file) throws IOException, HistoryFormatException {
        if (Files.size(file) > StorageLimits.MAX_FILE_BYTES) {
            throw new HistoryFormatException(HistoryFormatException.Reason.TOO_LARGE);
        }
        List<String> lines = decode(Files.readAllBytes(file)).lines().collect(Collectors.toCollection(ArrayList::new));
        if (!lines.isEmpty() && lines.get(0).startsWith("﻿")) {
            lines.set(0, lines.get(0).substring(1));
        }
        int bodyStart = 0;
        if (!lines.isEmpty()) {
            Matcher header = HEADER.matcher(lines.get(0));
            if (header.matches()) {
                int version = versionOf(header.group(1));
                if (version != SUPPORTED_VERSION) {
                    return new LegacyHistory(List.of(), List.of(), OptionalInt.of(version));
                }
                bodyStart = 1;
            }
        }
        CalculatorEngine engine = new CalculatorEngine();
        List<CalculationRecord> records = new ArrayList<>();
        List<RejectedLine> rejections = new ArrayList<>();
        for (int index = bodyStart; index < lines.size(); index++) {
            if (lines.get(index).isBlank()) {
                continue;
            }
            try {
                records.add(parseLine(engine, lines.get(index)));
            } catch (LineRejection rejection) {
                rejections.add(new RejectedLine(index + 1, rejection.cause(), rejection.getMessage()));
            }
        }
        return new LegacyHistory(records, rejections, OptionalInt.empty());
    }

    private static String decode(byte[] bytes) throws HistoryFormatException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new HistoryFormatException(HistoryFormatException.Reason.NOT_UTF8, exception);
        }
    }

    // The header only holds digits: the one way to fail is a number beyond an int, a version newer than any known.
    private static int versionOf(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException tooLarge) {
            return Integer.MAX_VALUE;
        }
    }

    private static CalculationRecord parseLine(CalculatorEngine engine, String line) throws LineRejection {
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
            super(detail, null, false, false);
            this.cause = cause;
        }

        RejectedLine.Cause cause() {
            return cause;
        }
    }
}
