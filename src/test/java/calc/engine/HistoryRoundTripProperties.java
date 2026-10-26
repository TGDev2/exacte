package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
     * The longest operand without integer part that the calculator accepts. Its canonical text gains the "0" of "0,xxx"
     * and is exactly at the bound: it must be restored from the file it was saved in. (One character more used to be
     * accepted, saved, then refused as too long on reload: see ErrorProperties.)
     */
    @Example
    void anOperandOfTheMaximumLengthWithoutIntegerPartReloads() throws Exception {
        String longFraction = "," + "7".repeat(CalculationLimits.MAX_INPUT_LENGTH - 2);
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

    private static void assertSameEntry(CalculationRecord expected, CalculationRecord actual) {
        assertEquals(expected.getOperation(), actual.getOperation());
        assertEquals(expected.getFirstValue(), actual.getFirstValue(), "First operand display");
        assertEquals(expected.getSecondValue(), actual.getSecondValue(), "Second operand display");
        assertEquals(expected.getFormattedResult(), actual.getFormattedResult(), "Result display");
        assertEquals(0, expected.getResultValue().compareTo(actual.getResultValue()), "Reusable value of " + expected);
        assertEquals(expected.toString(), actual.toString(), "History line");
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
