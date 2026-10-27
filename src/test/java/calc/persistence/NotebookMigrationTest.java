package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.format.DisplayMode;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.NotebookEvaluator;
import calc.persistence.LoadedNotebook.Condition;
import calc.testing.RecordGenerators;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The whole chain version 1 history, version 2 history, version 3 notebook, from the reference files of {@code legacy/v1}
 * (the bytes a version 1 application left on disk): the lines, the exact values, the files and the copies left behind, the
 * idempotence, and what happens when a step fails.
 */
class NotebookMigrationTest {
    /** What a fixture must give: the lines of the notebook, the exact value of each, and the status shown. */
    private record Expected(String file, List<String> lines, List<String> values, String status) {
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                new Expected("nominal.tsv", List.of("0,1 + 0,2", "10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)"),
                        List.of("3/10", "15/2", "6", "7/2", "1024", "4"), "Votre historique a été converti en carnet (6 lignes)."),
                // The version 1 file held the rounded 0,333333333333: the chain restores the exact value.
                new Expected("rounded.tsv", List.of("1 / 3"), List.of("1/3"), "Votre historique a été converti en carnet (1 ligne)."),
                new Expected("legacy-float.tsv", List.of("0,1 + 0,2"), List.of("3/10"), "Votre historique a été converti en carnet (1 ligne)."),
                new Expected("crlf.tsv", List.of("1 + 2", "2 * 4", "√(9)"), List.of("3", "8", "3"),
                        "Votre historique a été converti en carnet (3 lignes)."),
                new Expected("bom.tsv", List.of("1 + 2", "5 - 2"), List.of("3", "3"), "Votre historique a été converti en carnet (2 lignes)."),
                new Expected("bom-header.tsv", List.of("1 + 2"), List.of("3"), "Votre historique a été converti en carnet (1 ligne)."),
                new Expected("empty.tsv", List.of(), List.of(), "Votre historique a été converti en carnet (0 ligne)."),
                new Expected("one-corrupted-line.tsv", List.of("1 + 2", "3 * 4"), List.of("3", "12"),
                        "Votre historique a été converti en carnet (2 lignes)."),
                new Expected("inconsistent.tsv", List.of(), List.of(), "Votre historique a été converti en carnet (0 ligne)."),
                new Expected("wrong-result.tsv", List.of(), List.of(), "Votre historique a été converti en carnet (0 ligne)."))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void everyReferenceFileGoesThroughTheWholeChainWithTheSameValues(Expected expected, @TempDir Path directory) throws Exception {
        byte[] original = copyFixture(expected.file(), directory);

        LoadedNotebook loaded;
        try (NotebookStore store = NotebookStore.open(directory)) {
            loaded = store.load();
            assertFalse(store.isReadOnly());
        }

        assertEquals(Condition.MIGRATED, loaded.condition(), expected.file());
        assertEquals(expected.status(), loaded.status());
        assertEquals(expected.lines(), loaded.notebook().texts(), expected.file());
        assertEquals(expected.lines().size(), loaded.migratedLines());
        assertEquals(0, loaded.commentedEntries());
        assertEquals(0, loaded.omittedEntries());
        // The same values: every line is recalculated by the language and gives the exact value of the old entry.
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(loaded.notebook());
        assertEquals(expected.values(), evaluated.results().stream().map(result -> result.value().lower().toString()).toList(), expected.file());
        assertTrue(evaluated.results().stream().allMatch(result -> result.value().isExact()));

        // The three generations are on disk: the v1 file set aside, the v2 file and its copy, the v3 notebook.
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv.v1.bak")), "the v1 file, byte for byte");
        assertFalse(Files.exists(directory.resolve("history.tsv")));
        byte[] history = Files.readAllBytes(directory.resolve("history.json"));
        assertArrayEquals(history, Files.readAllBytes(directory.resolve("history.json.v2.bak")), "the v2 copy is the v2 file, byte for byte");
        assertEquals(directory.resolve("history.json.v2.bak"), loaded.backup());
        assertEquals(List.of("carnet-courant.calc.json", "history.json", "history.json.v2.bak", "history.lock", "history.tsv.v1.bak"),
                names(directory), "no other file");

        DecodedNotebook written = NotebookCodec.read(directory.resolve("carnet-courant.calc.json"));
        assertEquals(loaded.notebook(), written.notebook(), "the notebook on disk is the migrated one");
        assertEquals(DisplayMode.AUTO, written.displayMode());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void migratingAgainChangesNothingAtAll(Expected expected, @TempDir Path directory) throws Exception {
        copyFixture(expected.file(), directory);
        LoadedNotebook first;
        try (NotebookStore store = NotebookStore.open(directory)) {
            first = store.load();
        }
        Snapshot before = Snapshot.of(directory);

        LoadedNotebook again;
        try (NotebookStore store = NotebookStore.open(directory)) {
            again = store.load();
        }

        assertEquals(Condition.NORMAL, again.condition(), "nothing is converted the second time");
        assertNull(again.status());
        assertEquals(first.notebook(), again.notebook());
        assertNull(again.backup());
        assertEquals(before, Snapshot.of(directory), "no file created, removed or modified");
    }

    @Test
    void aVersion1FileAloneInTheOldLocationGoesAllTheWayToANotebook(@TempDir Path root) throws Exception {
        Path oldDirectory = Files.createDirectories(root.resolve("home").resolve(".calc"));
        Path newDirectory = root.resolve("data").resolve("calc");
        copyFixture("nominal.tsv", oldDirectory);

        try (NotebookStore store = NotebookStore.open(newDirectory, oldDirectory, Clock.systemDefaultZone(), Files::copy, Files::move)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.MIGRATED, loaded.condition());
            assertEquals(6, loaded.notebook().size());
        }
        assertTrue(Files.exists(newDirectory.resolve("carnet-courant.calc.json")));
    }

    @Test
    void aVersion2HistoryBecomesANotebookInOrderWithAnUntouchedHistoryAndACopy(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "40", "2"),
                    RecordGenerators.calculated(Operation.DIVISION, "1", "3"),
                    RecordGenerators.calculated(Operation.POWER, "2", "-3"),
                    RecordGenerators.calculated(Operation.SUBTRACTION, "-1,5", "-2"),
                    RecordGenerators.calculated(Operation.ROOT, "9", "")));
        }
        byte[] json = Files.readAllBytes(directory.resolve("history.json"));

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.MIGRATED, loaded.condition());
            assertEquals(List.of("40 + 2", "1 / 3", "2 ^ (-3)", "(-1,5) - (-2)", "√(9)"), loaded.notebook().texts());
            assertEquals("Votre historique a été converti en carnet (5 lignes).", loaded.status());
        }
        assertArrayEquals(json, Files.readAllBytes(directory.resolve("history.json")), "history.json is not touched");
        assertArrayEquals(json, Files.readAllBytes(directory.resolve("history.json.v2.bak")));
    }

    @Test
    void anEntryNoLineCanExpressBecomesACommentAndIsCounted(@TempDir Path directory) throws Exception {
        CalculatorEngine engine = new CalculatorEngine();
        CalculationRecord root = engine.calculate(Operation.ROOT, "2", "");
        // The root of 2 is an approximate value; squaring it with the remembered interval gives an entry with an approximate operand.
        CalculationRecord squared = engine.calculate(Operation.POWER, Operand.known(root.getResult()), Operand.typed("2"));
        try (JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "1", "2"), root, squared));
        }

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.MIGRATED, loaded.condition());
            assertEquals(3, loaded.notebook().size());
            assertEquals(1, loaded.commentedEntries());
            assertEquals("1 + 2", loaded.notebook().line(1).text());
            assertEquals("√(2)", loaded.notebook().line(2).text());
            String comment = loaded.notebook().line(3).text();
            assertTrue(comment.startsWith(HistoryMigration.COMMENT_PREFIX), comment);
            assertTrue(comment.contains("^ 2"), "the comment says what the entry was: " + comment);
            List<LineResult> results = NotebookEvaluator.evaluate(loaded.notebook()).results();
            assertFalse(results.get(2).isError() || results.get(2).hasValue(), "a comment is neither a value nor an error");
            assertFalse(results.get(1).value().isExact(), "the root stays an interval");
        }
    }

    @Test
    void aHistoryLongerThanANotebookKeepsTheNewestEntriesAndSaysSo(@TempDir Path directory) throws Exception {
        List<CalculationRecord> history = new ArrayList<>();
        CalculationRecord entry = RecordGenerators.calculated(Operation.ADDITION, "1", "2");
        for (int index = 0; index < 5003; index++) {
            history.add(RecordGenerators.calculated(Operation.ADDITION, Integer.toString(index), "1"));
        }
        history.add(entry);
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            store.saveHistory(history);
        }

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(5000, loaded.notebook().size());
            assertEquals(4, loaded.omittedEntries());
            assertEquals("5002 + 1", loaded.notebook().line(4999).text());
            assertEquals("1 + 2", loaded.notebook().line(5000).text(), "the newest entry is kept");
            assertTrue(loaded.status().startsWith("Votre historique a été converti en carnet (5000 lignes)."), loaded.status());
            assertTrue(loaded.status().contains("Les 4 entrées les plus anciennes restent dans la sauvegarde history.json.v2.bak."));
        }
        assertTrue(Files.size(directory.resolve("history.json.v2.bak")) > 0);
    }

    @Test
    void aBackupThatAlreadyExistsIsNeverOverwritten(@TempDir Path directory) throws Exception {
        copyFixture("nominal.tsv", directory);
        Files.writeString(directory.resolve("history.json.v2.bak"), "older backup");
        Files.writeString(directory.resolve("history.json.v2.bak.1"), "even older backup");

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(directory.resolve("history.json.v2.bak.2"), loaded.backup());
        }
        assertEquals("older backup", Files.readString(directory.resolve("history.json.v2.bak")));
        assertEquals("even older backup", Files.readString(directory.resolve("history.json.v2.bak.1")));
        assertTrue(Files.size(directory.resolve("history.json.v2.bak.2")) > 20);
    }

    @Test
    void whenBothHistoriesExistTheVersion2FileWinsAndTheOldOneIsLeftAlone(@TempDir Path directory) throws Exception {
        byte[] tsv = copyFixture("nominal.tsv", directory);
        try (JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "40", "2")));
        }

        try (NotebookStore store = NotebookStore.open(directory)) {
            assertEquals(List.of("40 + 2"), store.load().notebook().texts());
        }
        assertArrayEquals(tsv, Files.readAllBytes(directory.resolve("history.tsv")));
    }

    @Test
    void anExistingCurrentNotebookIsNeverReplacedByAMigration(@TempDir Path directory) throws Exception {
        copyFixture("nominal.tsv", directory);
        byte[] mine = NotebookCodec.encode(calc.testing.NotebookGenerators.notebook(List.of("mine = 1", "mine + 1")), DisplayMode.DECIMAL).bytes();
        Files.write(directory.resolve("carnet-courant.calc.json"), mine);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.NORMAL, loaded.condition());
            assertEquals(List.of("mine = 1", "mine + 1"), loaded.notebook().texts());
            assertEquals(DisplayMode.DECIMAL, loaded.displayMode());
        }
        assertArrayEquals(mine, Files.readAllBytes(directory.resolve("carnet-courant.calc.json")));
        assertTrue(Files.exists(directory.resolve("history.tsv")), "the history is not even converted");
    }

    // ------------------------------------------------------------------------------------------------ failures

    @Test
    void aCopyThatFailsStopsTheMigrationAndLeavesEverySourceIntactForTheNextStart(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "40", "2")));
        }
        Snapshot before = Snapshot.of(directory);

        try (NotebookStore store = NotebookStore.open(directory, null, Clock.systemDefaultZone(), (source, target) -> {
            throw new IOException("simulated full disk");
        }, Files::move)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.HISTORY_NOT_CONVERTED, loaded.condition());
            assertTrue(loaded.notebook().isEmpty());
            assertEquals("L'historique n'a pas pu être converti en carnet : il reste tel quel et sera converti au prochain démarrage.",
                    loaded.status());
        }
        assertEquals(before, Snapshot.of(directory), "no file created, removed or modified");

        try (NotebookStore store = NotebookStore.open(directory)) {
            assertEquals(Condition.MIGRATED, store.load().condition(), "the next start converts");
        }
    }

    @Test
    void aWriteThatFailsLeavesTheHistoryAndTheBackupAndNoPartialNotebook(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "40", "2")));
        }
        byte[] json = Files.readAllBytes(directory.resolve("history.json"));

        try (NotebookStore store = NotebookStore.open(directory, null, Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            throw new IOException("simulated disk failure");
        })) {
            assertEquals(Condition.HISTORY_NOT_CONVERTED, store.load().condition());
        }

        assertArrayEquals(json, Files.readAllBytes(directory.resolve("history.json")));
        assertFalse(Files.exists(directory.resolve("carnet-courant.calc.json")), "no half-written notebook");
        assertFalse(Files.exists(directory.resolve("carnet-courant.calc.json.tmp")), "no temporary file");
        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();
            assertEquals(Condition.MIGRATED, loaded.condition(), "the next start converts");
            assertEquals(List.of("40 + 2"), loaded.notebook().texts());
        }
    }

    @Test
    void aHistoryTooDamagedToBeReadIsNotConvertedAndIsNotTouched(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.json"), "{\"format\": \"calc-history\", \"version\": 2, \"entries\": [");
        byte[] damaged = Files.readAllBytes(directory.resolve("history.json"));

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.HISTORY_NOT_CONVERTED, loaded.condition());
            assertEquals("L'historique n'a pas pu être lu : il n'est pas converti en carnet et reste tel quel.", loaded.status());
        }
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve("history.json")));
        assertFalse(Files.exists(directory.resolve("carnet-courant.calc.json")));
    }

    @Test
    void aVersion1FileFromANewerVersionIsNeitherReadNorConverted(@TempDir Path directory) throws Exception {
        byte[] original = copyFixture("future-version.tsv", directory);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.HISTORY_NOT_CONVERTED, loaded.condition());
            assertTrue(loaded.notebook().isEmpty());
        }
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv")));
        assertEquals(List.of("history.lock", "history.tsv"), names(directory), "nothing is created");
    }

    @Test
    void aSecondInstanceConvertsInMemoryAndWritesNothing(@TempDir Path directory) throws Exception {
        copyFixture("nominal.tsv", directory);
        try (NotebookStore first = NotebookStore.open(directory); NotebookStore second = NotebookStore.open(directory)) {
            assertFalse(first.isReadOnly());
            assertTrue(second.isReadOnly());

            LoadedNotebook loaded = second.load();

            assertEquals(Condition.IN_USE_READ_ONLY, loaded.condition());
            assertEquals("Calc est déjà ouvert : le carnet de cette fenêtre ne sera pas enregistré.", loaded.status());
            assertEquals(6, loaded.notebook().size(), "the lines are shown");
        }
        assertEquals(List.of("history.lock", "history.tsv"), names(directory), "no conversion, no backup, no notebook");
    }

    @Test
    void anInstallationWithoutAnyHistoryStartsWithAnEmptyNotebookAndWritesNothing(@TempDir Path directory) throws Exception {
        try (NotebookStore store = NotebookStore.open(directory.resolve("fresh"))) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.NORMAL, loaded.condition());
            assertTrue(loaded.notebook().isEmpty());
            assertNull(loaded.status());
            assertNull(loaded.backup());
        }
        assertEquals(List.of("history.lock"), names(directory.resolve("fresh")));
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static byte[] copyFixture(String fileName, Path directory) throws IOException {
        try (InputStream stream = NotebookMigrationTest.class.getResourceAsStream("/legacy/v1/" + fileName)) {
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

    /** The names and the bytes of every file of a directory, to prove that nothing changed. */
    private record Snapshot(List<String> files, List<Integer> hashes) {
        static Snapshot of(Path directory) throws IOException {
            List<String> files = NotebookMigrationTest.names(directory);
            List<Integer> hashes = new ArrayList<>();
            for (String name : files) {
                hashes.add(java.util.Arrays.hashCode(Files.readAllBytes(directory.resolve(name))));
            }
            return new Snapshot(files, hashes);
        }
    }
}
