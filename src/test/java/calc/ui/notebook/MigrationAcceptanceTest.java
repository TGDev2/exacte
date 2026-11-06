package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.AtomicFileWriter;
import calc.format.DisplayMode;
import calc.persistence.DecodedNotebook;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookRow.Status;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Migrations of docs/RECETTE.md: for every reference file of {@code legacy/v1}, a data directory holding only that file, a start
 * of the application, then a notebook of version 3 with the right lines and exact values, the copies of versions 1 and 2 kept,
 * and a second start that changes nothing.
 */
@Tag("acceptance")
class MigrationAcceptanceTest {
    private static final String LEGACY_FILE = "history.tsv";
    private static final String FUTURE_VERSION = "future-version.tsv";

    /** The lines of the notebook made from a file, and the exact value of each as the Fraction mode shows it. */
    private record Expected(List<String> lines, List<String> fractions) {
    }

    private static final Map<String, Expected> EXPECTED = expected();

    private static Map<String, Expected> expected() {
        Map<String, Expected> map = new TreeMap<>();
        map.put("nominal.tsv", new Expected(List.of("0,1 + 0,2", "10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)"),
                List.of("3/10", "15/2", "6", "7/2", "1024", "4")));
        map.put("rounded.tsv", new Expected(List.of("1 / 3"), List.of("1/3")));
        map.put("legacy-float.tsv", new Expected(List.of("0,1 + 0,2"), List.of("3/10")));
        map.put("crlf.tsv", new Expected(List.of("1 + 2", "2 * 4", "√(9)"), List.of("3", "8", "3")));
        map.put("bom.tsv", new Expected(List.of("1 + 2", "5 - 2"), List.of("3", "3")));
        map.put("bom-header.tsv", new Expected(List.of("1 + 2"), List.of("3")));
        map.put("empty.tsv", new Expected(List.of(), List.of()));
        map.put("one-corrupted-line.tsv", new Expected(List.of("1 + 2", "3 * 4"), List.of("3", "12")));
        map.put("inconsistent.tsv", new Expected(List.of(), List.of()));
        map.put("wrong-result.tsv", new Expected(List.of(), List.of()));
        return Collections.unmodifiableMap(map);
    }

    private static Path referenceDirectory() throws Exception {
        URL marker = MigrationAcceptanceTest.class.getResource("/legacy/v1/" + FUTURE_VERSION);
        assertNotNull(marker, "the reference files of the old format are missing");
        return Path.of(marker.toURI()).getParent();
    }

    static Stream<String> migratedFiles() throws Exception {
        try (Stream<Path> files = Files.list(referenceDirectory())) {
            return files.map(file -> file.getFileName().toString())
                    .filter(name -> name.endsWith(".tsv") && !name.equals(FUTURE_VERSION)).sorted().toList().stream();
        }
    }

    private static byte[] install(String reference, Path data) throws Exception {
        byte[] bytes = Files.readAllBytes(referenceDirectory().resolve(reference));
        Files.write(data.resolve(LEGACY_FILE), bytes);
        return bytes;
    }

    @Test
    void noReferenceFileEscapesThisTest() throws Exception {
        assertEquals(List.copyOf(EXPECTED.keySet()), migratedFiles().toList(), "every file of legacy/v1 has its expected result here");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("migratedFiles")
    void everyReferenceFileBecomesANotebookWithExactValuesAndItsCopiesAndASecondStartChangesNothing(String reference, @TempDir Path data)
            throws Exception {
        byte[] original = install(reference, data);
        Expected expected = EXPECTED.get(reference);

        try (AcceptanceSession session = AcceptanceSession.start(data)) {
            NotebookDriver d = session.d;
            assertEquals(StatusTone.SUCCESS, d.view.statusTone(), d.view.statusMessage());
            assertTrue(d.view.statusMessage().contains("converti en carnet"), d.view.statusMessage());
            assertEquals(expected.lines(), session.lines());
            assertEquals(Collections.nCopies(expected.lines().size(), Status.EXACT), session.statuses());
            d.mode(DisplayMode.FRACTION);
            assertEquals(expected.fractions(), session.results());
        }

        // Version 3 on disk, and the copies of versions 1 and 2 beside it.
        DecodedNotebook written = NotebookCodec.read(data.resolve("carnet-courant.calc.json"));
        assertEquals(NotebookCodec.VERSION, written.version());
        assertEquals(expected.lines(), written.notebook().texts());
        assertArrayEquals(original, Files.readAllBytes(data.resolve("history.tsv.v1.bak")), "the version 1 file, byte for byte");
        assertArrayEquals(Files.readAllBytes(data.resolve("history.json")), Files.readAllBytes(data.resolve("history.json.v2.bak")),
                "the version 2 file, byte for byte");
        assertFalse(Files.exists(data.resolve(LEGACY_FILE)), "the version 1 file is set aside");

        // Starting again changes nothing, not one byte, and has nothing to announce.
        Map<String, String> before = AcceptanceSession.contentOf(data);
        try (AcceptanceSession again = AcceptanceSession.start(data)) {
            assertEquals(expected.lines(), again.lines());
            assertNotEquals(StatusTone.ERROR, again.d.view.statusTone());
            assertFalse(again.d.view.statusMessage().contains("converti"), again.d.view.statusMessage());
        }
        assertEquals(before, AcceptanceSession.contentOf(data));
    }

    @Test
    void aFileFromAFutureVersionIsNotConvertedAndStaysUntouched(@TempDir Path data) throws Exception {
        byte[] original = install(FUTURE_VERSION, data);

        for (int start = 0; start < 2; start++) {
            try (AcceptanceSession session = AcceptanceSession.start(data)) {
                assertEquals(List.of(), session.lines());
                assertEquals(StatusTone.ERROR, session.d.view.statusTone());
                assertTrue(session.d.view.statusMessage().contains("version plus récente"), session.d.view.statusMessage());
            }
            assertArrayEquals(original, Files.readAllBytes(data.resolve(LEGACY_FILE)));
            assertEquals(List.of("history.lock", LEGACY_FILE), List.copyOf(AcceptanceSession.contentOf(data).keySet()), "nothing is created");
        }
    }

    @Test
    void whatTheUserTypesAfterAMigrationIsNeverOverwrittenByTheOldHistory(@TempDir Path data) throws Exception {
        install("nominal.tsv", data);

        try (AcceptanceSession session = AcceptanceSession.start(data)) {
            session.d.click(6).line("ans + 1");
            session.d.click(0).delete();
        }

        try (AcceptanceSession again = AcceptanceSession.start(data)) {
            assertEquals(List.of("10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)", "ans + 1"), again.lines());
            assertEquals("5", again.results().get(5));
        }
    }

    @Test
    void aMigrationWhoseWriteFailsLeavesNoBrokenFileAndSucceedsAtTheNextStart(@TempDir Path data) throws Exception {
        install("nominal.tsv", data);
        Path notebook = data.resolve("carnet-courant.calc.json");
        AtomicFileWriter.Mover failing = (source, target, options) -> {
            if (target.equals(notebook)) {
                throw new IOException("simulated disk failure");
            }
            Files.move(source, target, options);
        };

        NotebookStore store = NotebookStore.open(data, null, Clock.systemDefaultZone(), Files::copy, failing);
        try (AcceptanceSession session = AcceptanceSession.start(store)) {
            assertEquals(StatusTone.ERROR, session.d.view.statusTone());
            assertEquals(List.of(), session.lines());
        }
        assertFalse(Files.exists(notebook), "no half-written notebook");
        assertTrue(AcceptanceSession.contentOf(data).keySet().stream().noneMatch(name -> name.contains("tmp")),
                () -> "a temporary file was left behind: " + data);

        try (AcceptanceSession next = AcceptanceSession.start(data)) {
            assertEquals(EXPECTED.get("nominal.tsv").lines(), next.lines());
        }
    }
}
