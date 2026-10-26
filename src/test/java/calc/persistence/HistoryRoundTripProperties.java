package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.testing.DecimalGenerators;
import calc.testing.JsonHistoryGenerators;
import calc.testing.RecordGenerators;
import calc.testing.TempDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Writing a history and reading it back gives the same history: the same exact values and the same intervals, the same
 * display, the same reusable value. Unlike version 1, an entry calculated from a remembered root keeps its interval.
 */
@Tag("property")
class HistoryRoundTripProperties {
    @Provide
    Arbitrary<List<CalculationRecord>> histories() {
        return Arbitraries.oneOf(RecordGenerators.anySizeRecords(), RecordGenerators.rememberedRootRecords()).list().ofMaxSize(10);
    }

    @Provide
    Arbitrary<JsonHistoryGenerators.JsonFile> validJsonFiles() {
        return JsonHistoryGenerators.validFiles();
    }

    @Property(tries = 300)
    void aSavedHistoryReloadsWithTheSameValuesAndIntervals(@ForAll("histories") List<CalculationRecord> history) throws Exception {
        TempDirectories.with(directory -> {
            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                store.saveHistory(history);
            }

            HistoryLoadResult loaded;
            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                loaded = store.loadHistory();
            }

            assertTrue(loaded.rejections().isEmpty(), () -> "Nothing may be rejected, got " + loaded.rejections());
            assertEquals(HistoryLoadResult.Condition.NORMAL, loaded.condition());
            assertEquals(history.size(), loaded.records().size(), "Number of restored entries");
            for (int index = 0; index < history.size(); index++) {
                assertSameEntry(history.get(index), loaded.records().get(index));
            }
            assertEquals(List.of("history.lock", "history.json"), names(directory), "No temporary file, no backup, no stray file.");
        });
    }

    @Property(tries = 300)
    void theFileHoldsTheDocumentedFormat(@ForAll("histories") List<CalculationRecord> history) {
        String text = new String(HistoryCodec.encode(history).bytes(), StandardCharsets.UTF_8);

        assertTrue(text.startsWith("{\n  \"format\": \"calc-history\",\n  \"version\": 2,\n  \"entries\": ["), "Header");
        assertFalse(text.contains("\r"), "LF line endings");
        assertFalse(text.matches("(?s).*\"(ADDITION|SUBTRACTION|MULTIPLICATION|DIVISION|POWER|ROOT)\".*"), "No Java constant name");
        // A value is a text: nothing after a colon, a bracket or a comma is a bare number, except the version.
        assertFalse(text.replace("\"version\": 2", "").matches("(?s).*[:\\[,]\\s*-?\\d.*"), "No JSON number besides the version");
        assertEquals(history.size(), text.lines().filter(line -> line.contains("\"op\":")).count(), "One entry per line");
    }

    @Property(tries = 300)
    void writingTheReloadedHistoryChangesNothing(@ForAll("histories") List<CalculationRecord> history) throws Exception {
        byte[] first = HistoryCodec.encode(history).bytes();

        DecodedHistory decoded = HistoryCodec.decode(first);

        assertTrue(decoded.rejections().isEmpty());
        assertArrayEquals(first, HistoryCodec.encode(decoded.records()).bytes(), "Read then write is the identity on the bytes.");
    }

    /** The independent writer of the generators and the production reader agree on every valid entry. */
    @Property(tries = 300)
    void theReaderAcceptsTheIndependentlyWrittenFormat(@ForAll("validJsonFiles") JsonHistoryGenerators.JsonFile file) throws Exception {
        DecodedHistory decoded = HistoryCodec.decode(file.bytes());

        assertEquals(file.expectedEntries(), decoded.records().stream().map(CalculationRecord::toString).toList());
        assertTrue(decoded.rejections().isEmpty(), () -> decoded.rejections() + " in " + file);
    }

    /**
     * The operand without integer part with the most decimals the core accepts: its canonical text gains the "0" of
     * "0,xxx" and must still be restored from the file it was saved in.
     */
    @Example
    void anOperandWithTheMostDecimalsWithoutIntegerPartReloads() throws Exception {
        String longFraction = "," + "7".repeat(DecimalGenerators.MAX_DIGITS);
        CalculationRecord record = new CalculatorEngine().calculate(Operation.ROOT, longFraction, "");

        DecodedHistory decoded = HistoryCodec.decode(HistoryCodec.encode(List.of(record)).bytes());

        assertTrue(decoded.rejections().isEmpty(), () -> "The saved entry was rejected: " + decoded.rejections());
        assertSameEntry(record, decoded.records().get(0));
    }

    /**
     * Version 1 had to write an exact recalculation for an entry calculated from a remembered root, and could not store a
     * power of it above about 48. Version 2 stores the interval: the same entries come back identical.
     */
    @Example
    void anEntryCalculatedFromARememberedRootKeepsItsInterval() throws Exception {
        CalculatorEngine engine = new CalculatorEngine();
        CalculationRecord root = engine.calculate(Operation.ROOT, "2", "");
        Operand remembered = Operand.known(root.getResult());
        List<CalculationRecord> history = new ArrayList<>(List.of(
                root,
                engine.calculate(Operation.POWER, remembered, Operand.typed("2")),
                engine.calculate(Operation.MULTIPLICATION, remembered, Operand.typed("123456789012345")),
                engine.calculate(Operation.POWER, remembered, Operand.typed("40")),
                engine.calculate(Operation.POWER, remembered, Operand.typed("100")),
                engine.calculate(Operation.DIVISION, Operand.typed("1e15"), remembered),
                engine.calculate(Operation.ADDITION, remembered, remembered)));
        assertFalse(history.get(1).isExact(), "The square of a remembered root is approximate.");

        DecodedHistory decoded = HistoryCodec.decode(HistoryCodec.encode(history).bytes());

        assertTrue(decoded.rejections().isEmpty(), () -> "Nothing may be rejected, got " + decoded.rejections());
        assertEquals(history.size(), decoded.records().size());
        for (int index = 0; index < history.size(); index++) {
            assertSameEntry(history.get(index), decoded.records().get(index));
        }
    }

    @Example
    void anEmptyHistoryIsAnEmptyDocument() throws Exception {
        HistoryCodec.Encoded encoded = HistoryCodec.encode(List.of());

        assertEquals("{\n  \"format\": \"calc-history\",\n  \"version\": 2,\n  \"entries\": [\n  ]\n}\n",
                new String(encoded.bytes(), StandardCharsets.UTF_8));
        assertEquals(0, HistoryCodec.decode(encoded.bytes()).records().size());
    }

    static void assertSameEntry(CalculationRecord expected, CalculationRecord actual) {
        assertEquals(expected.getOperation(), actual.getOperation());
        assertEquals(expected.getFirstValue(), actual.getFirstValue(), "First operand");
        assertEquals(expected.getSecondValue(), actual.getSecondValue(), "Second operand");
        assertEquals(expected.getFormattedResult(), actual.getFormattedResult(), "Result display");
        assertEquals(expected.getResult(), actual.getResult(), "Value of " + expected);
        assertEquals(expected.getFirstOperand(), actual.getFirstOperand(), "First operand value");
        assertEquals(expected.getSecondOperand(), actual.getSecondOperand(), "Second operand value");
        assertEquals(expected.getReusableValue(), actual.getReusableValue(), "Reusable value of " + expected);
        assertEquals(expected.toString(), actual.toString(), "History line");
    }

    static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted(Comparator.reverseOrder()).toList();
        }
    }
}
