package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.LoadedNotebook.Condition;
import calc.testing.NotebookGenerators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The current notebook on disk: automatic save, lock, newer versions, damaged and oversized files, failing disks. */
class NotebookStoreTest {
    private static final String CURRENT = "carnet-courant.calc.json";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T10:15:30Z"), ZoneOffset.UTC);

    private static Notebook notebook(String... texts) {
        return NotebookGenerators.notebook(List.of(texts));
    }

    @Test
    void theCurrentNotebookIsWrittenAtomicallyAndComesBack(@TempDir Path directory) throws Exception {
        Notebook mine = notebook("# Devis", "surface = 12,5", "surface * 42");
        try (NotebookStore store = NotebookStore.open(directory)) {
            assertTrue(store.saveCurrent(mine, DisplayMode.FRACTION));
        }

        assertEquals(List.of(CURRENT, "history.lock"), names(directory), "one file, no temporary file");
        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.NORMAL, loaded.condition());
            assertNull(loaded.status());
            assertEquals(mine, loaded.notebook(), "same lines, same identifiers");
            assertEquals(DisplayMode.FRACTION, loaded.displayMode());
        }
    }

    @Test
    void everyWriteReplacesTheWholeFile(@TempDir Path directory) throws Exception {
        try (NotebookStore store = NotebookStore.open(directory)) {
            store.saveCurrent(notebook("1", "2", "3"), DisplayMode.AUTO);
            store.saveCurrent(notebook("only"), DisplayMode.AUTO);
            store.saveCurrent(Notebook.empty(), DisplayMode.AUTO);

            assertTrue(store.load().notebook().isEmpty());
        }
    }

    @Test
    void aSecondInstanceLoadsButNeverWrites(@TempDir Path directory) throws Exception {
        Notebook mine = notebook("a = 1", "a + 1");
        try (NotebookStore first = NotebookStore.open(directory)) {
            first.saveCurrent(mine, DisplayMode.AUTO);
            byte[] before = Files.readAllBytes(directory.resolve(CURRENT));

            try (NotebookStore second = NotebookStore.open(directory)) {
                assertTrue(second.isReadOnly(), "the state is exposed");
                LoadedNotebook loaded = second.load();
                assertEquals(Condition.IN_USE_READ_ONLY, loaded.condition());
                assertEquals("Calc est déjà ouvert : le carnet de cette fenêtre ne sera pas enregistré.", loaded.status());
                assertEquals(mine, loaded.notebook());

                assertFalse(second.saveCurrent(notebook("overwritten"), DisplayMode.AUTO), "no write, and no error");
            }
            assertArrayEquals(before, Files.readAllBytes(directory.resolve(CURRENT)));
            assertFalse(first.isReadOnly());
        }
        try (NotebookStore later = NotebookStore.open(directory)) {
            assertFalse(later.isReadOnly(), "closing releases the lock");
        }
    }

    @Test
    void theNotebookAndTheHistoryShareOneLockSoNeitherFormatCanBeWrittenTwice(@TempDir Path directory) throws Exception {
        try (NotebookStore notebooks = NotebookStore.open(directory); JsonHistoryStore history = JsonHistoryStore.open(directory)) {
            assertFalse(notebooks.isReadOnly());
            assertTrue(history.isReadOnly(), "an old-style store of the same directory is a second instance");
        }
        try (JsonHistoryStore history = JsonHistoryStore.open(directory); NotebookStore notebooks = NotebookStore.open(directory)) {
            assertFalse(history.isReadOnly());
            assertTrue(notebooks.isReadOnly());
        }
    }

    // ------------------------------------------------------------------------------------------------ versions

    @Test
    void aNotebookFromANewerVersionIsShownReadOnlyAndNeverRewritten(@TempDir Path directory) throws Exception {
        String newer = "{\"format\":\"calc-notebook\",\"version\":4,\"future\":{\"x\":1},\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\",\"style\":\"bold\"}]}";
        Files.writeString(directory.resolve(CURRENT), newer);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.NEWER_VERSION_READ_ONLY, loaded.condition());
            assertEquals("Carnet créé par une version plus récente de Calc : ouvert en lecture seule.", loaded.status());
            assertEquals(List.of("1 + 1"), loaded.notebook().texts());
            assertTrue(store.isReadOnly());

            assertFalse(store.saveCurrent(notebook("typed afterwards"), DisplayMode.AUTO), "not even after an edit");
            assertFalse(store.saveCurrent(Notebook.empty(), DisplayMode.AUTO), "not even after clearing everything");
        }

        assertEquals(newer, Files.readString(directory.resolve(CURRENT)), "the file is byte for byte what it was");
        assertEquals(List.of(CURRENT, "history.lock"), names(directory), "no copy, no temporary file");
    }

    @Test
    void aNotebookFromANewerVersionThatCannotBeReadIsNotShownAndNotTouched(@TempDir Path directory) throws Exception {
        String newer = "{\"format\":\"calc-notebook\",\"version\":99,\"blocks\":[{\"text\":\"1 + 1\"}]}";
        Files.writeString(directory.resolve(CURRENT), newer);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.NEWER_VERSION_NOT_LOADED, loaded.condition());
            assertTrue(loaded.notebook().isEmpty());
            assertFalse(store.saveCurrent(notebook("x"), DisplayMode.AUTO));
        }
        assertEquals(newer, Files.readString(directory.resolve(CURRENT)));
        assertEquals(List.of(CURRENT, "history.lock"), names(directory));
    }

    // ------------------------------------------------------------------------------------------------ damaged files

    @Test
    void aDamagedFileIsCopiedThenReplacedByTheNextWrite(@TempDir Path directory) throws Exception {
        byte[] damaged = "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[{\"source\"".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(CURRENT), damaged);

        try (NotebookStore store = NotebookStore.open(directory, null, CLOCK, Files::copy, Files::move)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.UNREADABLE, loaded.condition());
            assertTrue(loaded.notebook().isEmpty());
            Path backup = directory.resolve(CURRENT + ".20261027-101530.bak");
            assertEquals(backup, loaded.backup());
            assertTrue(loaded.status().startsWith("Le carnet courant est illisible : une copie a été conservée ("));
            assertArrayEquals(damaged, Files.readAllBytes(backup), "the copy is the damaged file, byte for byte");

            assertTrue(store.saveCurrent(notebook("1 + 1"), DisplayMode.AUTO), "now rewritable");
        }
        assertEquals(List.of("1 + 1"), NotebookCodec.read(directory.resolve(CURRENT)).notebook().texts());
    }

    @Test
    void aDamagedFileThatCannotBeCopiedIsNeverReplaced(@TempDir Path directory) throws Exception {
        byte[] damaged = "not json at all".getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(CURRENT), damaged);

        try (NotebookStore store = NotebookStore.open(directory, null, CLOCK, (source, target) -> {
            throw new IOException("simulated full disk");
        }, Files::move)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.UNREADABLE, loaded.condition());
            assertNull(loaded.backup());
            assertEquals("Le carnet courant est illisible et n'a pas pu être copié : il n'est pas modifié.", loaded.status());
            assertThrows(NotebookStoreException.class, () -> store.saveCurrent(notebook("x"), DisplayMode.AUTO));
        }
        assertArrayEquals(damaged, Files.readAllBytes(directory.resolve(CURRENT)));
    }

    @Test
    void aFileOverALimitIsNotTouchedAndNothingIsWrittenOverIt(@TempDir Path directory) throws Exception {
        byte[] hostile = ("{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[" + "{\"source\":\"1\"},".repeat(Notebook.MAX_LINES) + "{\"source\":\"1\"}]}")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(directory.resolve(CURRENT), hostile);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.UNREADABLE, loaded.condition());
            assertNull(loaded.backup(), "a file over a limit is not copied");
            assertEquals("Le carnet courant dépasse les limites de lecture : il n'est pas modifié.", loaded.status());
            assertThrows(NotebookStoreException.class, () -> store.saveCurrent(notebook("x"), DisplayMode.AUTO));
        }
        assertArrayEquals(hostile, Files.readAllBytes(directory.resolve(CURRENT)));
        assertEquals(List.of(CURRENT, "history.lock"), names(directory));
    }

    @Test
    void aDirectoryWhereTheFileShouldBeIsAnErrorNotACrash(@TempDir Path directory) throws Exception {
        Files.createDirectory(directory.resolve(CURRENT));

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(Condition.UNREADABLE, loaded.condition());
            assertThrows(NotebookStoreException.class, () -> store.saveCurrent(notebook("x"), DisplayMode.AUTO));
        }
    }

    // ------------------------------------------------------------------------------------------------ failing writes

    @Test
    void aFailedWriteLeavesThePreviousFileIntactAndIsReportedNotThrownAtTheCaller(@TempDir Path directory) throws Exception {
        try (NotebookStore store = NotebookStore.open(directory)) {
            store.saveCurrent(notebook("good"), DisplayMode.AUTO);
        }
        byte[] good = Files.readAllBytes(directory.resolve(CURRENT));

        try (NotebookStore store = NotebookStore.open(directory, null, CLOCK, Files::copy, (source, target, options) -> {
            throw new IOException("simulated disk failure");
        })) {
            NotebookStoreException failure = assertThrows(NotebookStoreException.class, () -> store.saveCurrent(notebook("lost"), DisplayMode.AUTO));
            assertTrue(failure.getMessage().startsWith("Impossible d'enregistrer le carnet : "), failure.getMessage());
            assertNotNull(failure.getCause());
        }

        assertArrayEquals(good, Files.readAllBytes(directory.resolve(CURRENT)), "the old notebook is intact");
        assertEquals(List.of(CURRENT, "history.lock"), names(directory), "no temporary file is left");
        try (NotebookStore store = NotebookStore.open(directory)) {
            assertEquals(List.of("good"), store.load().notebook().texts());
        }
    }

    @Test
    void aNotebookThatTheFormatCannotHoldIsNotWrittenAndTheReasonIsFrench(@TempDir Path directory) throws Exception {
        try (NotebookStore store = NotebookStore.open(directory)) {
            store.saveCurrent(notebook("kept"), DisplayMode.AUTO);
            Notebook tooLong = notebook("kept", "9".repeat(StorageLimits.MAX_STRING_LENGTH + 1));

            NotebookStoreException failure = assertThrows(NotebookStoreException.class, () -> store.saveCurrent(tooLong, DisplayMode.AUTO));

            assertEquals("Une ligne dépasse 16 384 caractères : ce carnet ne peut pas être enregistré.", failure.getMessage());
            assertEquals(List.of("kept"), store.load().notebook().texts(), "the file on disk is still the previous notebook");
        }
    }

    @Test
    void theLargestNotebookIsSavedAndReloaded(@TempDir Path directory) throws Exception {
        Notebook full = NotebookGenerators.notebook(Collections.nCopies(Notebook.MAX_LINES, "x = 1234567,891 * 3"));
        try (NotebookStore store = NotebookStore.open(directory)) {
            store.saveCurrent(full, DisplayMode.SCIENTIFIC);
        }
        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();
            assertEquals(Notebook.MAX_LINES, loaded.notebook().size());
            assertEquals(DisplayMode.SCIENTIFIC, loaded.displayMode());
        }
    }

    @Test
    void reservedFilesAreRecognised(@TempDir Path directory) {
        try (NotebookStore store = NotebookStore.open(directory)) {
            assertTrue(store.isReserved(directory.resolve(CURRENT)));
            assertTrue(store.isReserved(directory.resolve("history.json")));
            assertTrue(store.isReserved(directory.resolve("history.tsv")));
            assertTrue(store.isReserved(directory.resolve("history.lock")));
            assertTrue(store.isReserved(directory.resolve("sub").resolve("..").resolve(CURRENT)), "paths are normalised");
            assertFalse(store.isReserved(directory.resolve("devis.calc.json")));
            assertFalse(store.isReserved(directory.resolve("sub").resolve(CURRENT)));
        }
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
