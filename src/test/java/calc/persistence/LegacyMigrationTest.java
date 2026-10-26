package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.HistoryLoadResult;
import calc.engine.HistoryLoadResult.Condition;
import calc.testing.RecordGenerators;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Migrates every reference file of {@code legacy/v1} (the fixtures are the bytes a version 1 application left on disk)
 * and pins what is restored, the exact values stored in the version 2 file, the backup, and the idempotence.
 */
class LegacyMigrationTest {
    /** What a fixture must give: the entries (history line and exact value of each) and the number of rejected lines. */
    private record Expected(String file, List<String> lines, List<String> values, int rejected) {
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                new Expected("nominal.tsv",
                        List.of("0,1 + 0,2 = 0,3", "10 - 2,5 = 7,5", "1,5 * 4 = 6", "7 / 2 = 3,5", "2 ^ 10 = 1024", "√(16) = 4"),
                        List.of("3/10", "15/2", "6", "7/2", "1024", "4"), 0),
                // The version 1 file held the rounded 0,333333333333: the migration restores the exact value.
                new Expected("rounded.tsv", List.of("1 / 3 = 0,333333333333…"), List.of("1/3"), 0),
                new Expected("legacy-float.tsv", List.of("0,1 + 0,2 = 0,3"), List.of("3/10"), 0),
                new Expected("crlf.tsv", List.of("1 + 2 = 3", "2 * 4 = 8", "√(9) = 3"), List.of("3", "8", "3"), 0),
                new Expected("bom.tsv", List.of("1 + 2 = 3", "5 - 2 = 3"), List.of("3", "3"), 0),
                new Expected("bom-header.tsv", List.of("1 + 2 = 3"), List.of("3"), 0),
                new Expected("empty.tsv", List.of(), List.of(), 0),
                new Expected("one-corrupted-line.tsv", List.of("1 + 2 = 3", "3 * 4 = 12"), List.of("3", "12"), 1),
                new Expected("inconsistent.tsv", List.of(), List.of(), 1),
                new Expected("wrong-result.tsv", List.of(), List.of(), 1))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyReferenceFileMigratesToTheExpectedEntries(Expected expected, @TempDir Path directory) throws Exception {
        byte[] original = copyFixture(expected.file(), directory);

        HistoryLoadResult migrated;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            migrated = store.loadHistory();
        }

        assertEquals(Condition.MIGRATED, migrated.condition(), expected.file());
        assertEquals(expected.lines(), migrated.records().stream().map(CalculationRecord::toString).toList(), expected.file());
        assertEquals(expected.values(), migrated.records().stream().map(record -> record.getResult().lower().toString()).toList());
        assertTrue(migrated.records().stream().allMatch(CalculationRecord::isExact), "Every migrated value is exact.");
        assertEquals(expected.rejected(), migrated.rejections().size(), "rejected lines");

        Path backup = directory.resolve("history.tsv.v1.bak");
        assertEquals(backup, migrated.backupFile(), "The version 1 file is renamed, and the result says where it went.");
        assertArrayEquals(original, Files.readAllBytes(backup), "The backup is the version 1 file, byte for byte.");
        assertFalse(Files.exists(directory.resolve("history.tsv")), "The version 1 file is gone from its place.");
        assertTrue(Files.exists(directory.resolve("history.json")));
        assertEquals(List.of("history.json", "history.lock", "history.tsv.v1.bak"), names(directory), "No other file.");
        assertNoJavaNamesNorFloats(Files.readString(directory.resolve("history.json")));

        // The version 2 file holds exactly the migrated entries.
        DecodedHistory written = HistoryCodec.decode(Files.readAllBytes(directory.resolve("history.json")));
        assertEquals(expected.lines(), written.records().stream().map(CalculationRecord::toString).toList());
        assertTrue(written.rejections().isEmpty());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void migratingAgainChangesNothing(Expected expected, @TempDir Path directory) throws Exception {
        copyFixture(expected.file(), directory);
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.loadHistory();
        }
        byte[] json = Files.readAllBytes(directory.resolve("history.json"));
        byte[] backup = Files.readAllBytes(directory.resolve("history.tsv.v1.bak"));

        HistoryLoadResult again;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            again = store.loadHistory();
        }

        assertEquals(Condition.NORMAL, again.condition(), "Nothing is migrated the second time.");
        assertEquals(expected.lines(), again.records().stream().map(CalculationRecord::toString).toList());
        assertTrue(again.rejections().isEmpty());
        assertNull(again.backupFile());
        assertArrayEquals(json, Files.readAllBytes(directory.resolve("history.json")), "The version 2 file is not rewritten.");
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("history.tsv.v1.bak")), "The backup is not touched.");
        assertEquals(List.of("history.json", "history.lock", "history.tsv.v1.bak"), names(directory));
    }

    @Test
    void aFileFromANewerVersionOfTheOldFormatIsNeitherReadNorMigratedNorWritten(@TempDir Path directory) throws Exception {
        byte[] original = copyFixture("future-version.tsv", directory);
        HistoryLoadResult result;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            result = store.loadHistory();

            assertEquals(Condition.NEWER_VERSION_NOT_LOADED, result.condition());
            assertTrue(result.records().isEmpty());
            assertTrue(store.isReadOnly());
            store.saveHistory(List.of(RecordGenerators.calculated(calc.engine.Operation.ADDITION, "1", "2")));
        }

        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv")), "The file stays where it is.");
        assertEquals(List.of("history.lock", "history.tsv"), names(directory), "Nothing is created: no JSON file, no backup.");
    }

    @Test
    void aMigrationNeverOverwritesAnExistingBackup(@TempDir Path directory) throws Exception {
        copyFixture("nominal.tsv", directory);
        Files.writeString(directory.resolve("history.tsv.v1.bak"), "older backup");
        Files.writeString(directory.resolve("history.tsv.v1.bak.1"), "even older backup");

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            HistoryLoadResult result = store.loadHistory();

            assertEquals(directory.resolve("history.tsv.v1.bak.2"), result.backupFile());
        }

        assertEquals("older backup", Files.readString(directory.resolve("history.tsv.v1.bak")));
        assertEquals("even older backup", Files.readString(directory.resolve("history.tsv.v1.bak.1")));
        assertEquals(7, Files.readAllLines(directory.resolve("history.tsv.v1.bak.2")).size(), "header and six lines");
    }

    @Test
    void whenBothFormatsExistTheVersion2FileWinsAndTheOldOneIsNotTouched(@TempDir Path directory) throws Exception {
        byte[] tsv = copyFixture("nominal.tsv", directory);
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(List.of(RecordGenerators.calculated(calc.engine.Operation.ADDITION, "40", "2")));
        }
        byte[] json = Files.readAllBytes(directory.resolve("history.json"));

        HistoryLoadResult result;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            result = store.loadHistory();
        }

        assertEquals(Condition.NORMAL, result.condition());
        assertEquals(List.of("40 + 2 = 42"), result.records().stream().map(CalculationRecord::toString).toList());
        assertArrayEquals(tsv, Files.readAllBytes(directory.resolve("history.tsv")), "The old file is left alone.");
        assertArrayEquals(json, Files.readAllBytes(directory.resolve("history.json")));
        assertFalse(Files.exists(directory.resolve("history.tsv.v1.bak")));
    }

    @Test
    void aFailedConversionLeavesTheVersion1FileIntactAndIsRetriedLater(@TempDir Path directory) throws Exception {
        byte[] original = copyFixture("nominal.tsv", directory);
        HistoryLoadResult result;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory, null, java.time.Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            throw new IOException("simulated disk failure");
        })) {
            result = store.loadHistory();
        }

        assertEquals(Condition.NORMAL, result.condition(), "Not converted: the entries are shown, nothing more is claimed.");
        assertEquals(6, result.records().size());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv")), "The version 1 file is intact.");
        assertFalse(Files.exists(directory.resolve("history.json")));
        assertFalse(Files.exists(directory.resolve("history.json.tmp")), "No temporary file is left.");

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            assertEquals(Condition.MIGRATED, store.loadHistory().condition(), "The next start converts.");
        }
    }

    @Test
    void anOldHistoryInTheOldLocationIsAdoptedThenConverted(@TempDir Path root) throws Exception {
        Path oldDirectory = Files.createDirectories(root.resolve("home").resolve(".calc"));
        Path newDirectory = root.resolve("data").resolve("calc");
        byte[] original = copyFixture("nominal.tsv", oldDirectory);

        HistoryLoadResult result;
        try (JsonHistoryStore store = JsonHistoryStore.open(newDirectory, oldDirectory, java.time.Clock.systemDefaultZone(), Files::copy, Files::move)) {
            result = store.loadHistory();
        }

        assertEquals(Condition.MIGRATED, result.condition());
        assertEquals(6, result.records().size());
        assertTrue(Files.exists(newDirectory.resolve("history.json")));
        assertArrayEquals(original, Files.readAllBytes(newDirectory.resolve("history.tsv.v1.bak")));
        assertFalse(Files.exists(oldDirectory.resolve("history.tsv")), "The old file is set aside, not deleted.");
        assertArrayEquals(original, Files.readAllBytes(oldDirectory.resolve("history.tsv.v1.bak")), "The old location keeps a backup.");
    }

    @Test
    void theOldLocationIsIgnoredWhenTheNewOneAlreadyHoldsAHistory(@TempDir Path root) throws Exception {
        Path oldDirectory = Files.createDirectories(root.resolve("home").resolve(".calc"));
        Path newDirectory = root.resolve("data").resolve("calc");
        byte[] original = copyFixture("nominal.tsv", oldDirectory);
        try (JsonHistoryStore store = JsonHistoryStore.open(newDirectory)) {
            store.saveHistory(List.of(RecordGenerators.calculated(calc.engine.Operation.ADDITION, "1", "1")));
        }

        HistoryLoadResult result;
        try (JsonHistoryStore store = JsonHistoryStore.open(newDirectory, oldDirectory, java.time.Clock.systemDefaultZone(), Files::copy, Files::move)) {
            result = store.loadHistory();
        }

        assertEquals(List.of("1 + 1 = 2"), result.records().stream().map(CalculationRecord::toString).toList());
        assertArrayEquals(original, Files.readAllBytes(oldDirectory.resolve("history.tsv")), "The old file is not touched.");
    }

    private static void assertNoJavaNamesNorFloats(String json) {
        assertFalse(json.matches("(?s).*(ADDITION|SUBTRACTION|MULTIPLICATION|DIVISION|POWER|ROOT).*"), "No Java constant name in the file");
        assertFalse(json.replace("\"version\": 2", "").matches("(?s).*[:\\[,]\\s*-?\\d.*"), "No JSON number besides the version");
    }

    private static byte[] copyFixture(String fileName, Path directory) throws IOException {
        try (InputStream stream = LegacyMigrationTest.class.getResourceAsStream("/legacy/v1/" + fileName)) {
            assertNotNull(stream, "missing reference file " + fileName);
            byte[] bytes = stream.readAllBytes();
            Files.write(directory.resolve("history.tsv"), bytes);
            return bytes;
        }
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
