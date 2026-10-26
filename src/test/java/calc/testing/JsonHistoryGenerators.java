package calc.testing;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.engine.RejectedLine.Cause;
import calc.number.CertifiedValue;
import calc.number.Rational;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators of the content of a version 2 history file, valid, damaged or hostile. The text is written here, from the
 * fields of the records, with its own table of operation identifiers: it is an independent statement of the documented
 * format (docs/FORMAT-FICHIER.md), not a call to the production writer.
 */
public final class JsonHistoryGenerators {
    private static final int MAX_ENTRIES = 8;

    /** A rejection the reader must report: the 1-based position in {@code entries} and the cause. */
    public record ExpectedRejection(int position, Cause cause) {
    }

    /** One entry of {@code entries} and what the reader must make of it. */
    public record Entry(String json, String expectedEntry, Cause expectedCause) {
        static Entry valid(String json, CalculationRecord record) {
            return new Entry(json, record.toString(), null);
        }

        static Entry rejected(String json, Cause cause) {
            return new Entry(json, null, cause);
        }
    }

    /** The bytes of a file and, when the structure is known, what the reader must restore and reject. */
    public record JsonFile(byte[] bytes, List<String> expectedEntries, List<ExpectedRejection> expectedRejections, String shape) {
        @Override
        public String toString() {
            String text = new String(bytes, StandardCharsets.UTF_8);
            return shape + " (" + bytes.length + " bytes)\n" + (text.length() > 800 ? text.substring(0, 800) + "..." : text);
        }
    }

    private JsonHistoryGenerators() {
    }

    /** The identifier of the format, written independently of the production table. */
    public static String idOf(Operation operation) {
        return switch (operation) {
            case ADDITION -> "addition";
            case SUBTRACTION -> "subtraction";
            case MULTIPLICATION -> "multiplication";
            case DIVISION -> "division";
            case POWER -> "power";
            case ROOT -> "sqrt";
        };
    }

    /** Version 2 files of valid and damaged entries, with unknown fields, any layout, with or without a byte order mark. */
    public static Arbitrary<JsonFile> structuredFiles() {
        return Combinators.combine(entry().list().ofMaxSize(MAX_ENTRIES), unknownMembers(), Arbitraries.of(true, false, false),
                        Arbitraries.of("", " ", "\n", "\r\n  ", "\t"))
                .as(JsonHistoryGenerators::assemble);
    }

    /** Files that only hold entries the application itself wrote. */
    public static Arbitrary<JsonFile> validFiles() {
        return Combinators.combine(RecordGenerators.anySizeRecords().map(JsonHistoryGenerators::validEntry).list().ofMaxSize(MAX_ENTRIES),
                        Arbitraries.just(""), Arbitraries.just(false), Arbitraries.of("", "\n"))
                .as(JsonHistoryGenerators::assemble);
    }

    /** Files that are not usable at all, whatever the way: random bytes, random text, cut or damaged files, deep or huge JSON. */
    public static Arbitrary<byte[]> unusableContent() {
        return Arbitraries.frequencyOf(
                Tuple.of(2, Arbitraries.bytes().array(byte[].class).ofMaxSize(300)),
                Tuple.of(2, TextGenerators.unicode(80).list().ofMaxSize(8).map(parts -> utf8(String.join("\n", parts)))),
                Tuple.of(3, truncated()),
                Tuple.of(3, damaged()),
                Tuple.of(2, jsonTrees().map(JsonHistoryGenerators::utf8)),
                Tuple.of(1, Arbitraries.integers().between(1, 5000).map(depth -> utf8("[".repeat(depth) + "]".repeat(depth)))),
                Tuple.of(1, Arbitraries.integers().between(1, 5000).map(depth -> utf8("{\"a\":".repeat(depth) + "1" + "}".repeat(depth)))));
    }

    /** Anything: a structured file, an unusable one. */
    public static Arbitrary<byte[]> anyContent() {
        return Arbitraries.frequencyOf(
                Tuple.of(4, structuredFiles().map(JsonFile::bytes)),
                Tuple.of(6, unusableContent()));
    }

    private static Arbitrary<byte[]> truncated() {
        return Combinators.combine(structuredFiles(), Arbitraries.integers().between(0, 100_000))
                .as((file, cut) -> {
                    byte[] bytes = file.bytes();
                    int length = bytes.length == 0 ? 0 : cut % bytes.length;
                    byte[] shorter = new byte[length];
                    System.arraycopy(bytes, 0, shorter, 0, length);
                    return shorter;
                });
    }

    private static Arbitrary<byte[]> damaged() {
        return Combinators.combine(structuredFiles(), Arbitraries.integers().between(1, 4),
                        Arbitraries.integers().between(0, 100_000).list().ofSize(4), Arbitraries.bytes().list().ofSize(4))
                .as((file, damages, positions, replacements) -> {
                    List<Byte> bytes = new ArrayList<>();
                    for (byte value : file.bytes()) {
                        bytes.add(value);
                    }
                    for (int index = 0; index < damages; index++) {
                        int position = bytes.isEmpty() ? 0 : positions.get(index) % bytes.size();
                        switch (index % 3) {
                            case 0 -> bytes.add(position, replacements.get(index));
                            case 1 -> {
                                if (!bytes.isEmpty()) {
                                    bytes.set(position, replacements.get(index));
                                }
                            }
                            default -> {
                                if (!bytes.isEmpty()) {
                                    bytes.remove(position);
                                }
                            }
                        }
                    }
                    byte[] result = new byte[bytes.size()];
                    for (int index = 0; index < result.length; index++) {
                        result[index] = bytes.get(index);
                    }
                    return result;
                });
    }

    /** Well-formed JSON of any shape: it is JSON, but mostly not a history. */
    public static Arbitrary<String> jsonTrees() {
        return jsonValue(4);
    }

    private static Arbitrary<String> jsonValue(int depth) {
        Arbitrary<String> leaf = Arbitraries.oneOf(
                Arbitraries.of("null", "true", "false", "0", "-1", "2", "1.5", "1e5", "-0.0E-3", "\"\"", "\"text\"", "\"1/3\"", "\"calc-history\""),
                TextGenerators.unicode(12).map(JsonHistoryGenerators::quote));
        if (depth == 0) {
            return leaf;
        }
        Arbitrary<String> array = Arbitraries.lazy(() -> jsonValue(depth - 1)).list().ofMaxSize(4).map(items -> "[" + String.join(",", items) + "]");
        Arbitrary<String> object = Combinators.combine(
                        Arbitraries.of("format", "version", "entries", "op", "operands", "result", "exact", "value", "lower", "upper", "x")
                                .list().ofMaxSize(4).uniqueElements(),
                        Arbitraries.lazy(() -> jsonValue(depth - 1)).list().ofSize(4))
                .as((keys, values) -> {
                    List<String> members = new ArrayList<>();
                    for (int index = 0; index < keys.size(); index++) {
                        members.add(quote(keys.get(index)) + ":" + values.get(index));
                    }
                    return "{" + String.join(",", members) + "}";
                });
        return Arbitraries.frequencyOf(Tuple.of(3, leaf), Tuple.of(2, array), Tuple.of(3, object));
    }

    // ------------------------------------------------------------------------------------------------ entries

    private static Arbitrary<Entry> entry() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, RecordGenerators.anySizeRecords().map(JsonHistoryGenerators::validEntry)),
                Tuple.of(1, RecordGenerators.rememberedRootRecords().map(JsonHistoryGenerators::validEntry)),
                Tuple.of(5, rejectedEntry()));
    }

    /** The entry the application writes for a record, with an unknown field now and then. */
    public static Entry validEntry(CalculationRecord record) {
        List<String> operands = new ArrayList<>();
        operands.add(operandJson(record.getFirstOperand()));
        record.getSecondOperand().ifPresent(second -> operands.add(operandJson(second)));
        String json = "{ \"op\": " + quote(idOf(record.getOperation())) + ", \"operands\": [" + String.join(", ", operands)
                + "], \"result\": " + valueJson(record.getResult()) + (record.toString().length() % 3 == 0 ? ", \"note\": {\"later\": [1, null, true]}" : "") + " }";
        return Entry.valid(json, record);
    }

    private static String operandJson(CertifiedValue value) {
        return value.isExact() ? quote(value.lower().toString()) : valueJson(value);
    }

    private static String valueJson(CertifiedValue value) {
        return value.isExact()
                ? "{\"exact\": true, \"value\": " + quote(value.lower().toString()) + "}"
                : "{\"exact\": false, \"lower\": " + quote(value.lower().toString()) + ", \"upper\": " + quote(value.upper().toString()) + "}";
    }

    private static Arbitrary<Entry> rejectedEntry() {
        return Arbitraries.oneOf(notAnObject(), unknownOperation(), wrongOperandCount(), nonCanonicalOperand(), refusedOperands(),
                missingResult(), wrongResult(), unorderedBounds());
    }

    private static Arbitrary<Entry> notAnObject() {
        return Arbitraries.of("1", "\"entry\"", "null", "[]", "[1,2]", "true")
                .map(json -> Entry.rejected(json, Cause.MALFORMED_ENTRY));
    }

    private static Arbitrary<Entry> unknownOperation() {
        return Arbitraries.of("", "ADDITION", "plus", "Addition", "modulo", "root", "0")
                .map(id -> Entry.rejected("{\"op\": " + quote(id) + ", \"operands\": [\"1\", \"2\"], \"result\": " + exactJson("3") + "}",
                        Cause.UNKNOWN_OPERATION));
    }

    private static Arbitrary<Entry> wrongOperandCount() {
        return Arbitraries.of(
                        "{\"op\": \"addition\", \"operands\": [\"1\"], \"result\": " + exactJson("1") + "}",
                        "{\"op\": \"addition\", \"operands\": [\"1\", \"2\", \"3\"], \"result\": " + exactJson("3") + "}",
                        "{\"op\": \"sqrt\", \"operands\": [\"4\", \"2\"], \"result\": " + exactJson("2") + "}",
                        "{\"op\": \"sqrt\", \"operands\": [], \"result\": " + exactJson("0") + "}",
                        "{\"op\": \"sqrt\", \"operands\": \"4\", \"result\": " + exactJson("2") + "}",
                        "{\"op\": \"sqrt\", \"result\": " + exactJson("2") + "}",
                        "{\"operands\": [\"1\", \"2\"], \"result\": " + exactJson("3") + "}")
                .map(json -> Entry.rejected(json, json.contains("\"op\": \"") ? Cause.MALFORMED_ENTRY : Cause.UNKNOWN_OPERATION));
    }

    /** Operands that are not the canonical text of a number: decimals, exponents, a JSON number, spaces, a non-reduced fraction. */
    private static Arbitrary<Entry> nonCanonicalOperand() {
        return Arbitraries.of("\"0,5\"", "\"0.5\"", "\"1e3\"", "\"1E3\"", "\" 2\"", "\"2 \"", "\"+2\"", "\"02\"", "\"2/4\"", "\"4/2\"",
                        "\"-0\"", "\"1/-2\"", "\"abc\"", "\"\"", "2", "2.5", "null", "true", "[]", "{}", "{\"exact\": 1}",
                        "{\"exact\": true}", "{\"exact\": true, \"value\": 2}", "{\"exact\": false, \"lower\": \"1\"}")
                .map(operand -> Entry.rejected("{\"op\": \"addition\", \"operands\": [" + operand + ", \"2\"], \"result\": " + exactJson("3") + "}",
                        Cause.INVALID_OPERAND));
    }

    /** Operands that are numbers but that the engine refuses. */
    private static Arbitrary<Entry> refusedOperands() {
        return Arbitraries.of(
                        "{\"op\": \"division\", \"operands\": [\"1\", \"0\"], \"result\": " + exactJson("0") + "}",
                        "{\"op\": \"sqrt\", \"operands\": [\"-4\"], \"result\": " + exactJson("2") + "}",
                        "{\"op\": \"power\", \"operands\": [\"2\", \"1/2\"], \"result\": " + exactJson("1") + "}",
                        "{\"op\": \"power\", \"operands\": [\"2\", \"99999\"], \"result\": " + exactJson("1") + "}",
                        "{\"op\": \"power\", \"operands\": [\"0\", \"-1\"], \"result\": " + exactJson("1") + "}")
                .map(json -> Entry.rejected(json, Cause.INVALID_OPERAND));
    }

    private static Arbitrary<Entry> missingResult() {
        return Arbitraries.of("", ", \"result\": null", ", \"result\": \"3\"", ", \"result\": 3", ", \"result\": []",
                        ", \"result\": {}", ", \"result\": {\"exact\": true}", ", \"result\": {\"exact\": \"yes\", \"value\": \"3\"}",
                        ", \"result\": {\"exact\": true, \"value\": 3}", ", \"result\": {\"exact\": true, \"value\": \"3.0\"}",
                        ", \"result\": {\"exact\": true, \"value\": \"1e400\"}")
                .map(result -> Entry.rejected("{\"op\": \"addition\", \"operands\": [\"1\", \"2\"]" + result + "}", Cause.UNREADABLE_RESULT));
    }

    /** A well-formed result that differs from the recalculation, or claims the wrong exactness. */
    private static Arbitrary<Entry> wrongResult() {
        return RecordGenerators.records().map(record -> {
            CertifiedValue result = record.getResult();
            Rational other = result.midpoint().add(Rational.ONE);
            String wrong = result.isExact()
                    ? exactJson(other.toString())
                    : "{\"exact\": false, \"lower\": " + quote(result.lower().toString()) + ", \"upper\": " + quote(other.add(result.upper()).toString()) + "}";
            List<String> operands = new ArrayList<>();
            operands.add(operandJson(record.getFirstOperand()));
            record.getSecondOperand().ifPresent(second -> operands.add(operandJson(second)));
            return Entry.rejected("{\"op\": " + quote(idOf(record.getOperation())) + ", \"operands\": [" + String.join(", ", operands)
                    + "], \"result\": " + wrong + "}", Cause.INCONSISTENT_RESULT);
        });
    }

    /** An interval whose bounds are not increasing: the file must write a single point as an exact value. */
    private static Arbitrary<Entry> unorderedBounds() {
        return Arbitraries.of("{\"exact\": false, \"lower\": \"2\", \"upper\": \"1\"}", "{\"exact\": false, \"lower\": \"2\", \"upper\": \"2\"}")
                .map(result -> Entry.rejected("{\"op\": \"sqrt\", \"operands\": [\"4\"], \"result\": " + result + "}", Cause.UNREADABLE_RESULT));
    }

    private static String exactJson(String text) {
        return "{\"exact\": true, \"value\": " + quote(text) + "}";
    }

    private static Arbitrary<String> unknownMembers() {
        return Arbitraries.of("", ", \"generator\": \"Calc 9\"", ", \"later\": {\"deep\": [[1], {\"a\": null}]}", ", \"count\": 12, \"ok\": true",
                ", \"entries_v3\": [1, 2, 3]");
    }

    private static JsonFile assemble(List<Entry> entries, String unknown, boolean bom, String gap) {
        List<String> expectedEntries = new ArrayList<>();
        List<ExpectedRejection> rejections = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            rendered.add(entry.json());
            if (entry.expectedEntry() != null) {
                expectedEntries.add(entry.expectedEntry());
            } else {
                rejections.add(new ExpectedRejection(index + 1, entry.expectedCause()));
            }
        }
        String text = (bom ? "﻿" : "") + "{" + gap + "\"format\": \"calc-history\"," + gap + "\"version\": 2" + unknown + "," + gap
                + "\"entries\": [" + gap + String.join("," + gap, rendered) + gap + "]" + gap + "}" + gap;
        return new JsonFile(utf8(text), expectedEntries, rejections, (bom ? "BOM " : "") + entries.size() + " entries");
    }

    public static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"' || current == '\\') {
                quoted.append('\\').append(current);
            } else if (current < 0x20 || current >= 0x7f) {
                quoted.append(String.format("\\u%04x", (int) current));
            } else {
                quoted.append(current);
            }
        }
        return quoted.append('"').toString();
    }

    public static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
