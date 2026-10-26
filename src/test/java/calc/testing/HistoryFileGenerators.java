package calc.testing;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.engine.RejectedLine.Cause;
import calc.number.Rational;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators of the content of a history file, valid, damaged or hostile.
 *
 * <p>The structured files come with what the loader must make of them: the entries to restore, in order, and the
 * line number and cause of every rejection. The on-disk format they write is the documented one: an optional
 * {@code #calc-history v1} header, then one line per entry with four tab-separated columns (operation name, first
 * operand, second operand, result as its canonical text: docs/adr/0004-regles-affichage.md, section 9).
 */
public final class HistoryFileGenerators {
    public static final String HEADER = "#calc-history v1";

    private static final String BYTE_ORDER_MARK = String.valueOf((char) 0xFEFF);
    private static final int MAX_LINES = 10;

    /** What the loader must make of a line: restore an entry, ignore it silently or reject it with a cause. */
    public record Line(String text, String expectedEntry, Cause expectedCause) {
        public static Line valid(CalculationRecord record) {
            return new Line(storedLine(record, storedResult(record)), record.toString(), null);
        }

        public static Line rejected(String text, Cause cause) {
            return new Line(text, null, cause);
        }

        public static Line blank(String text) {
            return new Line(text, null, null);
        }

        public boolean isValid() {
            return expectedEntry != null;
        }

        public boolean isRejected() {
            return expectedCause != null;
        }
    }

    /** A rejection the loader must report. */
    public record ExpectedRejection(int lineNumber, Cause cause) {
    }

    /** The bytes of a file and, when the structure is known, what the loader must restore and reject. */
    public record HistoryFile(byte[] bytes, List<String> expectedEntries, List<ExpectedRejection> expectedRejections, String shape) {
        public boolean isStructured() {
            return expectedEntries != null;
        }

        @Override
        public String toString() {
            String text = new String(bytes, StandardCharsets.UTF_8)
                    .replace("\t", "<TAB>").replace("\r", "<CR>").replace("\n", "<LF>\n");
            String preview = text.length() > 600 ? text.substring(0, 600) + "...(" + bytes.length + " bytes)" : text;
            return shape + " (" + bytes.length + " bytes)\n" + preview;
        }
    }

    private HistoryFileGenerators() {
    }

    /** Files of valid, invalid and blank lines in any order, with or without BOM and header, and any line terminators. */
    public static Arbitrary<HistoryFile> structuredFiles() {
        return fileOf(line().list().ofMaxSize(MAX_LINES), Arbitraries.just(HEADER).injectNull(0.5), Arbitraries.of("\n", "\r\n", "\r"));
    }

    /** Files that only hold valid entries (the history written by the application, whatever the operand sizes). */
    public static Arbitrary<HistoryFile> validFiles() {
        return fileOf(RecordGenerators.anySizeRecords().map(Line::valid).list().ofMaxSize(8), Arbitraries.of(HEADER), Arbitraries.of("\n", "\r\n"));
    }

    /** Files that announce a format version this program does not know, including versions too large for an int. */
    public static Arbitrary<HistoryFile> futureVersionFiles() {
        Arbitrary<String> version = Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.integers().between(2, 99).map(String::valueOf)),
                Tuple.of(1, Arbitraries.just("0")),
                Tuple.of(1, Arbitraries.just("2147483648")),
                Tuple.of(1, Arbitraries.just("99999999999999999999")),
                Tuple.of(1, Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(40)))
                .filter(number -> !number.matches("0*1"));
        return Combinators.combine(version, line().list().ofMaxSize(5), Arbitraries.of(true, false))
                .as((number, body, bom) -> {
                    StringBuilder content = new StringBuilder(bom ? BYTE_ORDER_MARK : "");
                    content.append("#calc-history v").append(number).append("\n");
                    for (Line line : body) {
                        content.append(line.text()).append("\n");
                    }
                    // Nothing is read from such a file and nothing is rejected: the reader reports the version.
                    return new HistoryFile(content.toString().getBytes(StandardCharsets.UTF_8), List.of(), List.of(), "future version v" + number);
                });
    }

    /** Arbitrary bytes: mostly invalid UTF-8, so the file cannot be read as text. */
    public static Arbitrary<HistoryFile> randomBytes() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(300)
                .map(bytes -> new HistoryFile(bytes, null, null, "random bytes"));
    }

    /** Arbitrary Unicode text, split in lines, written as UTF-8. */
    public static Arbitrary<HistoryFile> randomText() {
        return TextGenerators.unicode(80).list().ofMaxSize(8)
                .map(parts -> new HistoryFile(String.join("\n", parts).getBytes(StandardCharsets.UTF_8), null, null, "random text"));
    }

    /** A structured file in which some bytes were replaced, inserted or removed. */
    public static Arbitrary<HistoryFile> corruptedFiles() {
        return Combinators.combine(
                        structuredFiles(),
                        Arbitraries.integers().between(1, 4),
                        Arbitraries.integers().between(0, 100_000).list().ofSize(4),
                        Arbitraries.bytes().list().ofSize(4))
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
                    byte[] damaged = new byte[bytes.size()];
                    for (int index = 0; index < damaged.length; index++) {
                        damaged[index] = bytes.get(index);
                    }
                    return new HistoryFile(damaged, null, null, "corrupted");
                });
    }

    /** Every kind of file above. */
    public static Arbitrary<HistoryFile> anyFile() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, structuredFiles()),
                Tuple.of(1, futureVersionFiles()),
                Tuple.of(2, randomBytes()),
                Tuple.of(2, randomText()),
                Tuple.of(3, corruptedFiles()));
    }

    /** One line of a history file with its expected treatment. */
    public static Arbitrary<Line> line() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, RecordGenerators.anySizeRecords().map(Line::valid)),
                Tuple.of(4, rejectedLine()),
                Tuple.of(1, Arbitraries.of(" ", "\t", "  \t ").map(Line::blank)));
    }

    /** A line the loader must reject, with the cause it must give. */
    public static Arbitrary<Line> rejectedLine() {
        return Arbitraries.oneOf(wrongColumnCount(), unknownOperation(), invalidOperand(), unreadableResult(), inconsistentResult());
    }

    /** The stored form of a record: the four columns. */
    public static String storedLine(CalculationRecord record, String result) {
        return record.getOperation().name() + "\t" + record.getFirstValue() + "\t" + record.getSecondValue() + "\t" + result;
    }

    /** The result column the application writes for a record. */
    public static String storedResult(CalculationRecord record) {
        return record.getReusableValue();
    }

    private static Arbitrary<Line> wrongColumnCount() {
        Arbitrary<String> column = Arbitraries.strings().withCharRange(' ', '~').ofMaxLength(12);
        return Combinators.combine(
                        Arbitraries.of(1, 2, 3, 5, 6),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8),
                        column.list().ofSize(5))
                .as((count, head, others) -> {
                    List<String> columns = new ArrayList<>();
                    columns.add(head);
                    columns.addAll(others.subList(0, count - 1));
                    return Line.rejected(String.join("\t", columns), Cause.WRONG_COLUMN_COUNT);
                });
    }

    private static Arbitrary<Line> unknownOperation() {
        Arbitrary<String> key = Arbitraries.of("", "add", "Addition", "ADDITIONS", "PLUS", "ADDITION ", " ADDITION", "0", "+", "ROOT2", "NONE");
        return Combinators.combine(key, DecimalGenerators.decimals(20), DecimalGenerators.decimals(20))
                .as((name, first, second) -> Line.rejected(
                        name + "\t" + first.text() + "\t" + second.text() + "\t0", Cause.UNKNOWN_OPERATION));
    }

    /** Four columns whose operands the engine certainly refuses: not a number, too long, division by zero, root of a negative... */
    private static Arbitrary<Line> invalidOperand() {
        Arbitrary<String> notANumber = Arbitraries.oneOf(
                DecimalGenerators.invalidDecimals(),
                Arbitraries.of("", "abc", "1e", "1 000", "1,000.5", "1/0", "NaN", ".", "-", "1".repeat(DecimalGenerators.MAX_LENGTH + 1)));
        Arbitrary<Line> badFirst = Combinators.combine(RecordGenerators.operations(), notANumber, DecimalGenerators.decimals(20))
                .as((operation, bad, second) -> operandLine(operation, bad, second.text()));
        Arbitrary<Line> badSecond = Combinators.combine(binaryOperations(), DecimalGenerators.decimals(20), notANumber)
                .as((operation, first, bad) -> operandLine(operation, first.text(), bad));
        Arbitrary<Line> divisionByZero = Combinators.combine(DecimalGenerators.decimals(20), DecimalGenerators.zeros())
                .as((first, zero) -> operandLine(Operation.DIVISION, first.text(), zero.text()));
        Arbitrary<Line> negativeRoot = DecimalGenerators.nonZero(DecimalGenerators.decimals(20))
                .map(value -> operandLine(Operation.ROOT, "-" + value.text().replaceAll("^[+-]", ""), ""));
        Arbitrary<Line> badExponent = Combinators.combine(DecimalGenerators.decimals(20), Arbitraries.of("1,5", "0.5", "5000", "-5000", "99999999999", "2.0000001"))
                .as((base, exponent) -> operandLine(Operation.POWER, base.text(), exponent));
        return Arbitraries.oneOf(badFirst, badSecond, divisionByZero, negativeRoot, badExponent);
    }

    private static Arbitrary<Operation> binaryOperations() {
        return Arbitraries.of(Operation.ADDITION, Operation.SUBTRACTION, Operation.MULTIPLICATION, Operation.DIVISION, Operation.POWER);
    }

    private static Line operandLine(Operation operation, String first, String second) {
        return Line.rejected(operation.name() + "\t" + first + "\t" + second + "\t0", Cause.INVALID_OPERAND);
    }

    private static Arbitrary<Line> unreadableResult() {
        Arbitrary<String> garbage = Arbitraries.of("", " ", "abc", "1,5.2", "1e", "1/0", "NaN", "0x10", "--1", "1E999999999", "9".repeat(1300));
        return Combinators.combine(RecordGenerators.records(), garbage)
                .as((record, result) -> Line.rejected(storedLine(record, result), Cause.UNREADABLE_RESULT));
    }

    private static Arbitrary<Line> inconsistentResult() {
        return RecordGenerators.records().map(record -> Line.rejected(
                storedLine(record, record.getResult().midpoint().add(Rational.ONE).toString()), Cause.INCONSISTENT_RESULT));
    }

    private static Arbitrary<HistoryFile> fileOf(Arbitrary<List<Line>> lines, Arbitrary<String> header, Arbitrary<String> terminator) {
        return Combinators.combine(
                        lines,
                        header,
                        Arbitraries.of(true, false, false),
                        terminator.list().ofSize(MAX_LINES + 2),
                        Arbitraries.of(true, false))
                .as(HistoryFileGenerators::assemble);
    }

    private static HistoryFile assemble(List<Line> lines, String header, boolean bom, List<String> terminators, boolean trailingTerminator) {
        StringBuilder content = new StringBuilder(bom ? BYTE_ORDER_MARK : "");
        List<String> entries = new ArrayList<>();
        List<ExpectedRejection> rejections = new ArrayList<>();
        int lineNumber = 0;
        int terminatorIndex = 0;
        if (header != null) {
            lineNumber++;
            content.append(header);
            if (!lines.isEmpty() || trailingTerminator) {
                content.append(terminators.get(terminatorIndex++));
            }
        }
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            lineNumber++;
            content.append(line.text());
            if (index < lines.size() - 1 || trailingTerminator) {
                content.append(terminators.get(terminatorIndex++));
            }
            if (line.isValid()) {
                entries.add(line.expectedEntry());
            } else if (line.isRejected()) {
                rejections.add(new ExpectedRejection(lineNumber, line.expectedCause()));
            }
        }
        String shape = (bom ? "BOM " : "") + (header != null ? "header " : "") + lines.size() + " lines";
        return new HistoryFile(content.toString().getBytes(StandardCharsets.UTF_8), entries, rejections, shape);
    }
}
