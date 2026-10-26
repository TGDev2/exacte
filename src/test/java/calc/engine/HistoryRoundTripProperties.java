package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.DecimalGenerators;
import calc.testing.HistoryFileGenerators;
import calc.testing.RecordGenerators;
import calc.testing.TempDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/** Saving a history and loading it back gives the same history, with the same display and the same reusable value. */
@Tag("property")
class HistoryRoundTripProperties {
    @Provide
    Arbitrary<List<CalculationRecord>> histories() {
        return RecordGenerators.anySizeRecords().list().ofMaxSize(10);
    }

    @Property(tries = 300)
    void aSavedHistoryReloadsIdentically(@ForAll("histories") List<CalculationRecord> history) throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.tsv");
            new FileCalculationHistoryStore(file).saveHistory(history);

            HistoryLoadResult loaded = new FileCalculationHistoryStore(file).loadHistory();

            assertTrue(loaded.rejections().isEmpty(), () -> "Nothing may be rejected, got " + loaded.rejections());
            assertNull(loaded.backupFile(), "A clean file is not backed up.");
            assertEquals(history.size(), loaded.records().size(), "Number of restored entries");
            for (int index = 0; index < history.size(); index++) {
                assertSameEntry(history.get(index), loaded.records().get(index));
            }
            assertEquals(history.isEmpty() ? List.of() : List.of("history.tsv"), names(directory),
                    "Saving leaves no temporary file behind, loading writes nothing.");
        });
    }

    @Property(tries = 300)
    void theFileHoldsTheDocumentedFormat(@ForAll("histories") List<CalculationRecord> history) throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.tsv");
            new FileCalculationHistoryStore(file).saveHistory(history);

            if (history.isEmpty()) {
                assertTrue(Files.notExists(file), "An empty history is stored as no file.");
                return;
            }
            List<String> expected = new ArrayList<>();
            expected.add(HistoryFileGenerators.HEADER);
            for (CalculationRecord record : history) {
                expected.add(HistoryFileGenerators.storedLine(record, HistoryFileGenerators.storedResult(record)));
            }
            String content = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            assertEquals(String.join("\n", expected) + "\n", content, "Header, then one tab-separated line per entry, LF endings, UTF-8.");
        });
    }

    @Property(tries = 300)
    void savingTheReloadedHistoryChangesNothing(@ForAll("histories") List<CalculationRecord> history) throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.tsv");
            new FileCalculationHistoryStore(file).saveHistory(history);
            byte[] first = history.isEmpty() ? new byte[0] : Files.readAllBytes(file);

            FileCalculationHistoryStore store = new FileCalculationHistoryStore(file);
            store.saveHistory(store.loadHistory().records());

            byte[] second = history.isEmpty() ? new byte[0] : Files.readAllBytes(file);
            assertTrue(Arrays.equals(first, second), "Load then save is the identity on the bytes.");
        });
    }

    /**
     * The operand without integer part with the most decimals the core accepts: its canonical text gains the "0" of
     * "0,xxx" and must still be restored from the file it was saved in (a 1005-character operand of this shape used to
     * be saved, then refused as too long on reload: see ErrorProperties).
     */
    @Example
    void anOperandWithTheMostDecimalsWithoutIntegerPartReloads() throws Exception {
        String longFraction = "," + "7".repeat(DecimalGenerators.MAX_DIGITS);
        CalculationRecord record = new CalculatorEngine().calculate(Operation.ROOT, longFraction, "");
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.tsv");
            new FileCalculationHistoryStore(file).saveHistory(List.of(record));

            HistoryLoadResult loaded = new FileCalculationHistoryStore(file).loadHistory();

            assertTrue(loaded.rejections().isEmpty(), () -> "The saved entry was rejected: " + loaded.rejections());
            assertEquals(1, loaded.records().size());
            assertSameEntry(record, loaded.records().get(0));
        });
    }

    /**
     * Version 1 cannot store an interval: an entry calculated from a remembered approximate value (a root reused, then
     * squared) is written with the canonical point of that value as operand. It is restored without rejection, as the
     * exact result of that operand, which lies in the interval that was shown (docs/adr/0004-regles-affichage.md, section 9).
     */
    @Example
    void anEntryCalculatedFromARememberedRootReloadsAsItsExactRecalculation() throws Exception {
        CalculatorEngine engine = new CalculatorEngine();
        CalculationRecord root = engine.calculate(Operation.ROOT, "2", "");
        CalculationRecord square = engine.calculate(Operation.POWER, Operand.known(root.getResult()), Operand.typed("2"));
        assertFalse(square.isExact(), "The square of a remembered root is approximate.");
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.tsv");
            new FileCalculationHistoryStore(file).saveHistory(List.of(root, square));

            HistoryLoadResult loaded = new FileCalculationHistoryStore(file).loadHistory();

            assertTrue(loaded.rejections().isEmpty(), () -> "Nothing may be rejected, got " + loaded.rejections());
            assertSameEntry(root, loaded.records().get(0));
            CalculationRecord restored = loaded.records().get(1);
            assertEquals(square.getFirstValue(), restored.getFirstValue());
            assertTrue(restored.isExact(), "The recalculation from the stored operand is exact.");
            assertTrue(square.getResult().contains(restored.getResult().lower()), "It lies in the interval that was shown.");
        });
    }

    private static void assertSameEntry(CalculationRecord expected, CalculationRecord actual) {
        assertEquals(expected.getOperation(), actual.getOperation());
        assertEquals(expected.getFirstValue(), actual.getFirstValue(), "First operand");
        assertEquals(expected.getSecondValue(), actual.getSecondValue(), "Second operand");
        assertEquals(expected.getFormattedResult(), actual.getFormattedResult(), "Result display");
        assertEquals(expected.getResult(), actual.getResult(), "Value of " + expected);
        assertEquals(expected.getReusableValue(), actual.getReusableValue(), "Reusable value of " + expected);
        assertEquals(expected.toString(), actual.toString(), "History line");
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
