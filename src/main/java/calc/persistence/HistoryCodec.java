package calc.persistence;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes the version 2 history file (docs/FORMAT-FICHIER.md).
 *
 * <p>Reading never trusts the file: the structure is validated, every number is read with the exact parser of the core
 * (canonical texts only, so no floating-point literal can slip in), and every entry is <em>recalculated</em> by the engine
 * from its stored operands and compared with the stored result. An entry that is malformed, refused by the engine or
 * inconsistent is rejected and counted, never restored. Unknown fields are ignored at every level, so a later version can
 * add some without breaking this reader.
 */
public final class HistoryCodec {
    public static final String FORMAT = "calc-history";
    public static final int VERSION = 2;

    private static final String INDENT = "  ";

    /** The bytes to write, how many entries they hold and how many of the oldest were left out to respect the limits. */
    public record Encoded(byte[] bytes, int written, int dropped) {
    }

    private HistoryCodec() {
    }

    // ------------------------------------------------------------------------------------------------ writing

    /**
     * The file for {@code history}, oldest entry first. When the history is larger than the limits allow (more than
     * {@link StorageLimits#MAX_ENTRIES} entries, or {@link StorageLimits#MAX_FILE_BYTES} bytes), the oldest entries are
     * left out: the application never writes a file it would refuse to read.
     */
    public static Encoded encode(List<CalculationRecord> history) {
        String header = "{\n" + INDENT + "\"format\": " + quote(FORMAT) + ",\n" + INDENT + "\"version\": " + VERSION + ",\n"
                + INDENT + "\"entries\": [";
        String footer = "\n" + INDENT + "]\n}\n";
        // The newest entries are kept: walk back from the end until the next one would not fit.
        int budget = StorageLimits.MAX_FILE_BYTES - header.length() - footer.length();
        List<String> lines = new ArrayList<>();
        int first = history.size();
        while (first > 0 && lines.size() < StorageLimits.MAX_ENTRIES) {
            String entry = INDENT + INDENT + encodeEntry(history.get(first - 1));
            int cost = entry.getBytes(StandardCharsets.UTF_8).length + 2;
            if (cost > budget) {
                break;
            }
            budget -= cost;
            lines.add(entry);
            first--;
        }
        StringBuilder text = new StringBuilder(header);
        for (int index = lines.size() - 1; index >= 0; index--) {
            text.append('\n').append(lines.get(index));
            if (index > 0) {
                text.append(',');
            }
        }
        text.append(footer);
        return new Encoded(text.toString().getBytes(StandardCharsets.UTF_8), lines.size(), first);
    }

    private static String encodeEntry(CalculationRecord record) {
        StringBuilder entry = new StringBuilder("{ \"op\": ").append(quote(OperationIds.idOf(record.getOperation())));
        entry.append(", \"operands\": [").append(encodeOperand(record.getFirstOperand()));
        record.getSecondOperand().ifPresent(second -> entry.append(", ").append(encodeOperand(second)));
        entry.append("], \"result\": ").append(encodeResult(record.getResult())).append(" }");
        return entry.toString();
    }

    // An exact operand is its text, an approximate one (a remembered root) is an interval: both read back exactly.
    private static String encodeOperand(CertifiedValue value) {
        return value.isExact() ? quote(value.lower().toString()) : encodeResult(value);
    }

    private static String encodeResult(CertifiedValue value) {
        if (value.isExact()) {
            return "{ \"exact\": true, \"value\": " + quote(value.lower().toString()) + " }";
        }
        return "{ \"exact\": false, \"lower\": " + quote(value.lower().toString())
                + ", \"upper\": " + quote(value.upper().toString()) + " }";
    }

    static String quote(String text) {
        StringBuilder quoted = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            switch (current) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (current < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) current));
                    } else {
                        quoted.append(current);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }

    // ------------------------------------------------------------------------------------------------ reading

    /**
     * Reads a whole file. A file that cannot be read as a whole is a {@link HistoryFormatException}; a damaged entry
     * inside a readable file is a {@link RejectedLine}. A file of a newer version is read on a best-effort basis with
     * the rules of version 2 and never refused for its entries.
     */
    public static DecodedHistory decode(byte[] bytes) throws HistoryFormatException {
        if (bytes.length > StorageLimits.MAX_FILE_BYTES) {
            throw new HistoryFormatException(HistoryFormatException.Reason.TOO_LARGE);
        }
        Object root = parseJson(decodeUtf8(bytes));
        if (!(root instanceof Map<?, ?> document) || !FORMAT.equals(document.get("format"))) {
            throw new HistoryFormatException(HistoryFormatException.Reason.NOT_A_HISTORY);
        }
        int version = readVersion(document.get("version"));
        boolean newer = version > VERSION;
        if (!(document.get("entries") instanceof List<?> entries)) {
            if (newer) {
                return new DecodedHistory(version, List.of(), List.of(), false);
            }
            throw new HistoryFormatException(HistoryFormatException.Reason.MALFORMED_STRUCTURE);
        }
        if (entries.size() > StorageLimits.MAX_ENTRIES) {
            if (newer) {
                return new DecodedHistory(version, List.of(), List.of(), false);
            }
            throw new HistoryFormatException(HistoryFormatException.Reason.TOO_MANY_ENTRIES);
        }
        List<CalculationRecord> records = new ArrayList<>();
        List<RejectedLine> rejections = new ArrayList<>();
        CalculatorEngine engine = new CalculatorEngine();
        for (int index = 0; index < entries.size(); index++) {
            try {
                records.add(decodeEntry(engine, entries.get(index)));
            } catch (EntryRejection rejection) {
                rejections.add(new RejectedLine(index + 1, rejection.cause(), rejection.getMessage()));
            }
        }
        return new DecodedHistory(version, records, rejections, true);
    }

    private static String decodeUtf8(byte[] bytes) throws HistoryFormatException {
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

    private static Object parseJson(String text) throws HistoryFormatException {
        try {
            return JsonParser.parse(text);
        } catch (JsonException exception) {
            throw new HistoryFormatException(exception.reason().isLimit()
                    ? HistoryFormatException.Reason.JSON_LIMIT
                    : HistoryFormatException.Reason.MALFORMED_JSON, exception);
        }
    }

    private static int readVersion(Object node) throws HistoryFormatException {
        if (node instanceof JsonParser.JsonNumber number && number.literal().matches("[1-9][0-9]*") && number.asInt().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Optional<Integer> version = node instanceof JsonParser.JsonNumber number ? number.asInt() : Optional.empty();
        if (version.isEmpty() || version.get() < VERSION) {
            throw new HistoryFormatException(HistoryFormatException.Reason.INVALID_VERSION);
        }
        return version.get();
    }

    private static CalculationRecord decodeEntry(CalculatorEngine engine, Object node) throws EntryRejection {
        if (!(node instanceof Map<?, ?> entry)) {
            throw new EntryRejection(RejectedLine.Cause.MALFORMED_ENTRY, "the entry is not an object");
        }
        Operation operation = entry.get("op") instanceof String id ? OperationIds.fromId(id).orElse(null) : null;
        if (operation == null) {
            throw new EntryRejection(RejectedLine.Cause.UNKNOWN_OPERATION, "unknown operation identifier");
        }
        int arity = operation.requiresSecondOperand() ? 2 : 1;
        if (!(entry.get("operands") instanceof List<?> operands) || operands.size() != arity) {
            throw new EntryRejection(RejectedLine.Cause.MALFORMED_ENTRY, "expected " + arity + " operands");
        }
        Operand first = Operand.known(readValue(operands.get(0), true, RejectedLine.Cause.INVALID_OPERAND));
        Operand second = arity == 2
                ? Operand.known(readValue(operands.get(1), true, RejectedLine.Cause.INVALID_OPERAND))
                : Operand.typed("");
        CertifiedValue stored = readValue(entry.get("result"), false, RejectedLine.Cause.UNREADABLE_RESULT);

        CalculationRecord recomputed;
        try {
            recomputed = engine.calculate(operation, first, second);
        } catch (CalculationException exception) {
            throw new EntryRejection(RejectedLine.Cause.INVALID_OPERAND, "the engine refuses the operands");
        }
        if (!recomputed.getResult().equals(stored)) {
            throw new EntryRejection(RejectedLine.Cause.INCONSISTENT_RESULT, "the stored result differs from the recalculation");
        }
        return recomputed;
    }

    /**
     * A value is {@code {"exact": true, "value": t}} or {@code {"exact": false, "lower": t, "upper": t}}; an operand may
     * also be the bare text {@code t} of an exact value.
     */
    private static CertifiedValue readValue(Object node, boolean bareTextAllowed, RejectedLine.Cause cause) throws EntryRejection {
        try {
            if (node instanceof String text && bareTextAllowed) {
                return CertifiedValue.exact(readRational(text, cause));
            }
            if (node instanceof Map<?, ?> value && value.get("exact") instanceof Boolean exact) {
                if (exact) {
                    return CertifiedValue.exact(readRational(value.get("value"), cause));
                }
                Rational lower = readRational(value.get("lower"), cause);
                Rational upper = readRational(value.get("upper"), cause);
                // An interval of a single point would be an exact value: the file must say so.
                if (lower.compareTo(upper) >= 0) {
                    throw new EntryRejection(cause, "the bounds of an approximate value are not in increasing order");
                }
                return CertifiedValue.between(lower, upper);
            }
        } catch (NumericException exception) {
            throw new EntryRejection(cause, "the value is out of range");
        }
        throw new EntryRejection(cause, "the value is not an exact or approximate value");
    }

    // Only the canonical text of the core is accepted (`p` or `p/q`, reduced): no decimal point, no exponent, no float.
    private static Rational readRational(Object node, RejectedLine.Cause cause) throws EntryRejection {
        if (!(node instanceof String text)) {
            throw new EntryRejection(cause, "a number is not written as a text");
        }
        Rational value;
        try {
            value = Rational.parse(text);
        } catch (NumericException exception) {
            throw new EntryRejection(cause, "the text is not a number of the supported size");
        }
        if (!value.toString().equals(text)) {
            throw new EntryRejection(cause, "the number is not in canonical form");
        }
        return value;
    }

    private static final class EntryRejection extends Exception {
        private static final long serialVersionUID = 1L;

        private final RejectedLine.Cause cause;

        EntryRejection(RejectedLine.Cause cause, String detail) {
            super(detail, null, false, false);
            this.cause = cause;
        }

        RejectedLine.Cause cause() {
            return cause;
        }
    }
}
