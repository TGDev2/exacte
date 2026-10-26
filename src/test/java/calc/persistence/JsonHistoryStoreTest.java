package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.HistoryLoadResult.Condition;
import calc.engine.Operation;
import calc.testing.RecordGenerators;
import calc.ui.CalculatorPresenter;
import calc.ui.CalculatorViewModel;
import calc.ui.StatusTone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonHistoryStoreTest {
    private static final CalculationRecord ONE_PLUS_TWO = RecordGenerators.calculated(Operation.ADDITION, "1", "2");
    private static final String FUTURE_FILE = "{\"format\": \"calc-history\", \"version\": 99, \"carnet\": {\"lines\": [\"2 + 2\"]}, \"entries\": ["
            + "{\"op\": \"addition\", \"operands\": [\"1\", \"2\"], \"result\": {\"exact\": true, \"value\": \"3\"}, \"line\": 12},"
            + "{\"op\": \"matrix\", \"operands\": [\"1\"], \"result\": {\"exact\": true, \"value\": \"3\"}}]}\n";

    @Test
    void theFileHasTheDocumentedShape(@TempDir Path directory) throws Exception {
        CalculatorEngine engine = new CalculatorEngine();
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(
                    engine.calculate(Operation.DIVISION, "1", "3"),
                    engine.calculate(Operation.ROOT, "2", "")));
        }

        String json = Files.readString(directory.resolve("history.json"), StandardCharsets.UTF_8);

        String[] lines = json.split("\n");
        assertEquals("{", lines[0]);
        assertEquals("  \"format\": \"calc-history\",", lines[1]);
        assertEquals("  \"version\": 2,", lines[2]);
        assertEquals("  \"entries\": [", lines[3]);
        assertEquals("    { \"op\": \"division\", \"operands\": [\"1\", \"3\"], \"result\": { \"exact\": true, \"value\": \"1/3\" } },", lines[4]);
        assertTrue(lines[5].startsWith("    { \"op\": \"sqrt\", \"operands\": [\"2\"], \"result\": { \"exact\": false, \"lower\": \""), lines[5]);
        assertTrue(lines[5].endsWith("\" } }"), lines[5]);
        assertEquals("  ]", lines[6]);
        assertEquals("}", lines[7]);
    }

    @Test
    void aSuccessfulSaveLeavesNoTemporaryFile(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(ONE_PLUS_TWO));
        }

        assertEquals(List.of("history.json", "history.lock"), fileNames(directory));
    }

    @Test
    void aFailedWriteLeavesThePreviousVersion2FileIntact(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(ONE_PLUS_TWO));
        }
        byte[] before = Files.readAllBytes(directory.resolve("history.json"));

        try (JsonHistoryStore failing = JsonHistoryStore.open(directory, null, Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            throw new IOException("simulated disk failure");
        })) {
            CalculationHistoryException failure = assertThrows(CalculationHistoryException.class,
                    () -> failing.saveHistory(List.of(ONE_PLUS_TWO, RecordGenerators.calculated(Operation.ADDITION, "3", "4"))));
            assertTrue(failure.getMessage().contains("history.json"));
        }

        assertArrayEquals(before, Files.readAllBytes(directory.resolve("history.json")), "The previous file is intact.");
        assertEquals(List.of("history.json", "history.lock"), fileNames(directory), "No temporary file is left.");
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            assertEquals(List.of("1 + 2 = 3"), store.loadHistory().records().stream().map(CalculationRecord::toString).toList());
        }
    }

    @Test
    void aFailureInTheMiddleOfTheContentLeavesThePreviousFileIntactToo(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(ONE_PLUS_TWO));
        }
        byte[] before = Files.readAllBytes(directory.resolve("history.json"));
        // The temporary name is taken by a directory: the new content cannot even be started.
        Files.createDirectory(directory.resolve("history.json.tmp"));

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(ONE_PLUS_TWO, ONE_PLUS_TWO)));
        }

        assertArrayEquals(before, Files.readAllBytes(directory.resolve("history.json")));
    }

    @Test
    void anEmptyHistoryIsWrittenAsAnEmptyDocument(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(ONE_PLUS_TWO));
            store.saveHistory(List.of());
        }

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            HistoryLoadResult loaded = store.loadHistory();
            assertTrue(loaded.records().isEmpty());
            assertEquals(Condition.NORMAL, loaded.condition());
        }
    }

    @Test
    void aFileFromANewerVersionIsShownReadOnlyAndNeverRewritten(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), FUTURE_FILE, StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(directory.resolve("history.json"));

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), store);
            CalculatorViewModel opened = presenter.createInitialView();

            assertEquals("Historique créé par une version plus récente de Calc : ouvert en lecture seule.", opened.statusMessage());
            assertEquals(List.of("1 + 2 = 3"), opened.historyEntries().stream().map(Object::toString).toList(),
                    "The entries this version understands are shown.");
            assertTrue(store.isReadOnly());

            CalculatorViewModel afterCalculation = presenter.onCalculate("10", "5");
            assertEquals(StatusTone.SUCCESS, afterCalculation.statusTone());
            assertTrue(afterCalculation.statusMessage().contains("en lecture seule"), afterCalculation.statusMessage());
            presenter.onClearHistory();
            store.saveHistory(List.of());
        }

        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.json")), "Never rewritten, even after a calculation.");
        assertEquals(List.of("history.json", "history.lock"), fileNames(directory), "No backup, no temporary file.");
    }

    @Test
    void aNewerVersionThatCannotBeReadIsNotLoadedAtAllAndNeverRewritten(@TempDir Path directory) throws Exception {
        for (String content : List.of(
                "{\"format\": \"calc-history\", \"version\": 3}",
                "{\"format\": \"calc-history\", \"version\": 4, \"entries\": {\"by\": \"day\"}}",
                "{\"format\": \"calc-history\", \"version\": 500, \"entries\": \"later\"}",
                "{\"format\": \"calc-history\", \"version\": 99999999999999999999}")) {
            Files.writeString(directory.resolve("history.json"), content, StandardCharsets.UTF_8);

            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                HistoryLoadResult loaded = store.loadHistory();

                assertEquals(Condition.NEWER_VERSION_NOT_LOADED, loaded.condition(), content);
                assertTrue(loaded.records().isEmpty());
                store.saveHistory(List.of(ONE_PLUS_TWO));
            }
            assertEquals(content, Files.readString(directory.resolve("history.json")), "Never rewritten.");
        }
    }

    @Test
    void version99IsOpenedEvenWhenItsEntriesAreNotAllUnderstood(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), FUTURE_FILE, StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            HistoryLoadResult loaded = store.loadHistory();

            assertEquals(Condition.NEWER_VERSION_READ_ONLY, loaded.condition());
            assertEquals(1, loaded.records().size());
            assertNullBackup(loaded);
        }
    }

    @Test
    void aVersionBelowTwoOrNotAnIntegerIsADamagedFile(@TempDir Path directory) throws Exception {
        for (String version : List.of("1", "0", "-3", "2.5", "\"2\"", "null", "2e0")) {
            Files.writeString(directory.resolve("history.json"), "{\"format\": \"calc-history\", \"version\": " + version + ", \"entries\": []}",
                    StandardCharsets.UTF_8);
            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                CalculationHistoryException failure = assertThrows(CalculationHistoryException.class, store::loadHistory, version);
                assertTrue(failure.getBackupFile() != null, "A damaged file is copied first: " + version);
            }
        }
    }

    @Test
    void unknownFieldsAtEveryLevelAreIgnoredOnReading(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), "{\"format\": \"calc-history\", \"version\": 2, \"program\": \"Calc 9\", \"entries\": ["
                + "{\"op\": \"division\", \"at\": 12, \"operands\": [\"1\", \"3\"], \"note\": [null, {\"x\": true}],"
                + " \"result\": {\"exact\": true, \"value\": \"1/3\", \"digits\": 12}}]}", StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            HistoryLoadResult loaded = store.loadHistory();

            assertEquals(Condition.NORMAL, loaded.condition());
            assertEquals(1, loaded.records().size());
            assertTrue(loaded.rejections().isEmpty());
        }
    }

    @Test
    void aHistoryOverTheEntryLimitIsWrittenWithItsNewestEntriesOnly(@TempDir Path directory) throws Exception {
        List<CalculationRecord> history = new ArrayList<>();
        for (int index = 0; index < StorageLimits.MAX_ENTRIES + 5; index++) {
            history.add(RecordGenerators.calculated(Operation.ADDITION, String.valueOf(index), "1"));
        }
        HistoryCodec.Encoded encoded = HistoryCodec.encode(history);
        assertEquals(StorageLimits.MAX_ENTRIES, encoded.written());
        assertEquals(5, encoded.dropped());

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(history);
        }
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            HistoryLoadResult loaded = store.loadHistory();
            assertEquals(StorageLimits.MAX_ENTRIES, loaded.records().size(), "The file it wrote is a file it reads.");
            assertEquals("5 + 1 = 6", loaded.records().get(0).toString(), "The five oldest are the ones left out.");
            assertEquals("10004 + 1 = 10005", loaded.records().get(StorageLimits.MAX_ENTRIES - 1).toString());
        }
    }

    @Test
    void aHistoryOverTheSizeLimitIsWrittenWithItsNewestEntriesOnly() throws Exception {
        CalculationRecord large = new CalculatorEngine().calculate(Operation.POWER, "3", "2584");
        List<CalculationRecord> history = new ArrayList<>();
        for (int index = 0; index < 8000; index++) {
            history.add(large);
        }

        HistoryCodec.Encoded encoded = HistoryCodec.encode(history);

        assertTrue(encoded.dropped() > 0, "8000 entries of 1233 digits do not fit in " + StorageLimits.MAX_FILE_BYTES + " bytes.");
        assertTrue(encoded.bytes().length <= StorageLimits.MAX_FILE_BYTES);
        assertEquals(history.size(), encoded.written() + encoded.dropped());
        assertEquals(encoded.written(), HistoryCodec.decode(encoded.bytes()).records().size(), "The file it wrote is a file it reads.");
    }

    @Test
    void aDamagedFileIsCopiedThenReplacedByTheNextSave(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), "{\"format\": \"calc-history\", \"versi", StandardCharsets.UTF_8);
        byte[] damaged = Files.readAllBytes(directory.resolve("history.json"));

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            CalculationHistoryException failure = assertThrows(CalculationHistoryException.class, store::loadHistory);
            assertArrayEquals(damaged, Files.readAllBytes(failure.getBackupFile()));

            store.saveHistory(List.of(ONE_PLUS_TWO));

            assertEquals(1, HistoryCodec.decode(Files.readAllBytes(directory.resolve("history.json"))).records().size());
            assertArrayEquals(damaged, Files.readAllBytes(failure.getBackupFile()), "The copy survives the replacement.");
        }
    }

    @Test
    void theFileOfAnotherFormatIsNotMistakenForAHistory(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), "{\"format\": \"calc-notebook\", \"version\": 2, \"entries\": []}",
                StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            assertThrows(CalculationHistoryException.class, store::loadHistory);
        }
    }

    private static void assertNullBackup(HistoryLoadResult loaded) {
        assertFalse(loaded.backupFile() != null, "A file that is never written needs no copy.");
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
