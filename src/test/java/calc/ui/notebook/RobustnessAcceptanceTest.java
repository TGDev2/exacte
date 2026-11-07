package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.document.DocumentException;
import calc.document.DocumentService;
import calc.engine.AtomicFileWriter;
import calc.persistence.DecodedNotebook;
import calc.persistence.LoadedNotebook;
import calc.persistence.LockHolder;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookRow.Status;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Robustness and security of docs/RECETTE.md, from the File menu and from the start of the application: hostile files are
 * refused, or give a bounded error, in less than a second and without exhausting the memory; a file from a future version is
 * never written; a second instance writes nothing; a write that fails corrupts nothing.
 *
 * <p>The times are taken after a warm-up, best of three: they fail on a hang or an unbounded work, not on a busy machine.
 */
@Tag("acceptance")
@Tag("robustness")
class RobustnessAcceptanceTest {
    private static final long BUDGET_MILLIS = 1_000;
    private static final String HEAD = "{\"format\": \"calc-notebook\", \"version\": 3, ";
    private static final String CURRENT = "carnet-courant.calc.json";
    private static final String TYPED_BEFORE = "1 + 1";

    private enum Hostile {
        TEN_THOUSAND_LEVELS(file -> Files.writeString(file, "[".repeat(10_000) + "]".repeat(10_000))),
        TEN_THOUSAND_LEVELS_IN_THE_LINES(file -> Files.writeString(file, HEAD + "\"lines\": " + "[".repeat(10_000) + "]".repeat(10_000) + "}")),
        FIFTY_MEGABYTES(RobustnessAcceptanceTest::writeFiftyMegabytes),
        A_MILLION_LINES(file -> Files.writeString(file, HEAD + "\"lines\": [" + "{\"source\":\"1\"},".repeat(999_999) + "{\"source\":\"1\"}]}")),
        A_LINE_OF_TEN_MEGABYTES(file -> writeLine(file, "1".repeat(10_000_000))),
        A_LITERAL_OF_A_MILLION_DIGITS(file -> writeLine(file, "9".repeat(1_000_000))),
        A_GIGANTIC_POWER(file -> writeLine(file, "2^999^999")),
        TEN_THOUSAND_PAIRS_OF_PARENTHESES(file -> writeLine(file, "(".repeat(10_000) + "1" + ")".repeat(10_000))),
        TEN_THOUSAND_PARENTHESES(file -> writeLine(file, "(".repeat(5_000) + "1" + ")".repeat(5_000)));

        final Writer writer;

        Hostile(Writer writer) {
            this.writer = writer;
        }

        Path writeIn(Path directory) throws IOException {
            Path file = directory.resolve(name() + ".calc.json");
            writer.write(file);
            return file;
        }
    }

    @FunctionalInterface
    private interface Writer {
        void write(Path file) throws IOException;
    }

    private static void writeLine(Path file, String source) throws IOException {
        Files.writeString(file, HEAD + "\"lines\": [{\"source\": \"" + source + "\"}]}");
    }

    private static void writeFiftyMegabytes(Path file) throws IOException {
        byte[] line = "{\"source\":\"1\"},".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            out.write((HEAD + "\"lines\": [").getBytes(StandardCharsets.UTF_8));
            for (long written = 0; written < 50L * 1024 * 1024; written += line.length) {
                out.write(line);
            }
            out.write("{\"source\":\"1\"}]}".getBytes(StandardCharsets.UTF_8));
        }
    }

    // ------------------------------------------------------------------------------------------------ hostile files

    @ParameterizedTest(name = "{0}")
    @EnumSource(Hostile.class)
    void aHostileFileOpenedFromTheMenuIsRefusedOrInErrorInLessThanASecond(Hostile hostile, @TempDir Path root) throws Exception {
        Path file = hostile.writeIn(root);
        String hash = AcceptanceSession.sha256(file);

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 4; run++) {
            try (AcceptanceSession session = AcceptanceSession.start(Files.createDirectories(root.resolve("data" + run)))) {
                session.d.line(TYPED_BEFORE);
                session.dialogs.willOpen(file);

                long start = System.nanoTime();
                session.d.open();
                long millis = (System.nanoTime() - start) / 1_000_000;
                best = run == 0 ? best : Math.min(best, millis);

                boolean refused = session.lines().equals(List.of(TYPED_BEFORE));
                if (refused) {
                    assertEquals(StatusTone.ERROR, session.d.view.statusTone(), hostile.name());
                    assertTrue(session.d.view.statusMessage().contains("Impossible d'ouvrir"), session.d.view.statusMessage());
                    assertEquals("2", session.results().get(0), "the document in the window is still there");
                } else {
                    assertEquals(1, session.lines().size(), hostile.name());
                    assertEquals(Status.ERROR, session.d.row(1).status(), hostile + " gave a value: " + session.results());
                    assertFalse(session.d.row(1).errorMessage().isBlank());
                }
            }
        }

        System.out.println("[acceptance] " + hostile + " (" + Files.size(file) + " bytes) opened in " + best + " ms");
        TimingBudget.assertWithin(hostile + " opened", best, BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        assertEquals(hash, AcceptanceSession.sha256(file), "opening a file never changes it");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Hostile.class)
    void aHostileCurrentNotebookAtStartIsBoundedAndNeverLost(Hostile hostile, @TempDir Path root) throws Exception {
        Path source = hostile.writeIn(root);
        String hash = AcceptanceSession.sha256(source);

        long best = Long.MAX_VALUE;
        for (int run = 0; run < 4; run++) {
            Path data = Files.createDirectories(root.resolve("data" + run));
            Files.copy(source, data.resolve(CURRENT));

            long start = System.nanoTime();
            AcceptanceSession session = AcceptanceSession.start(data);
            long millis = (System.nanoTime() - start) / 1_000_000;
            best = run == 0 ? best : Math.min(best, millis);

            try (session) {
                if (session.lines().isEmpty()) {
                    assertEquals(StatusTone.ERROR, session.d.view.statusTone(), hostile.name());
                    session.d.line(TYPED_BEFORE);
                    assertTrue(AcceptanceSession.contentOf(data).containsValue(hash),
                            hostile + ": the original file is kept in place or copied, even after a line is typed");
                } else {
                    assertEquals(Status.ERROR, session.d.row(1).status(), hostile + " gave a value: " + session.results());
                }
            }
        }

        System.out.println("[acceptance] " + hostile + " at start in " + best + " ms");
        TimingBudget.assertWithin(hostile + " at start", best, BUDGET_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Opens each file of a folder under a small heap; an {@link OutOfMemoryError} ends the process with an error. Not a test. */
    static final class Opener {
        private Opener() {
        }

        public static void main(String[] arguments) throws Exception {
            try (NotebookStore store = NotebookStore.open(Path.of(arguments[1]))) {
                try (var files = Files.list(Path.of(arguments[0]))) {
                    for (Path file : files.filter(path -> path.toString().endsWith(".calc.json")).sorted().toList()) {
                        DocumentService document = new DocumentService(store, store.load());
                        try {
                            document.open(file);
                            System.out.println("LOADED " + file.getFileName());
                        } catch (DocumentException exception) {
                            System.out.println("REFUSED " + file.getFileName());
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyHostileFileIsHandledUnderASmallHeap(@TempDir Path root) throws Exception {
        Path hostile = Files.createDirectories(root.resolve("hostile"));
        for (Hostile each : Hostile.values()) {
            each.writeIn(hostile);
        }

        Process process = new ProcessBuilder(javaCommand(), "-Xmx128m", "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                Opener.class.getName(), hostile.toString(), Files.createDirectories(root.resolve("data")).toString())
                .redirectErrorStream(true).start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                return exception.toString();
            }
        });
        try {
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "the process hangs");
            assertEquals(0, process.exitValue(), () -> output.join());
        } finally {
            process.destroyForcibly();
        }
        for (Hostile each : Hostile.values()) {
            assertTrue(output.get(30, TimeUnit.SECONDS).contains(each.name() + ".calc.json"), each + " was not handled: " + output.get());
        }
        assertFalse(output.get().contains("OutOfMemoryError"), output.get());
    }

    // ------------------------------------------------------------------------------------------------ future version

    private static final String FUTURE = "{\"format\": \"calc-notebook\", \"version\": 99, \"lines\": [{\"source\": \"1 + 1\"}]}";

    @Test
    void aCurrentNotebookFromAFutureVersionIsNeverRewritten(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve(CURRENT), FUTURE);
        byte[] original = Files.readAllBytes(data.resolve(CURRENT));

        try (AcceptanceSession session = AcceptanceSession.start(data)) {
            NotebookDriver d = session.d;
            assertEquals(List.of(TYPED_BEFORE), session.lines(), "its lines are shown");
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertTrue(d.view.statusMessage().contains("version plus récente"), d.view.statusMessage());

            d.click(1).line("2 + 2");
            d.click(0).delete();
            d.undo();
            d.clear();
            assertArrayEquals(original, Files.readAllBytes(data.resolve(CURRENT)), "never rewritten");
        }
        assertArrayEquals(original, Files.readAllBytes(data.resolve(CURRENT)), "not when closing either");
        assertEquals(List.of(CURRENT, "history.lock"), List.copyOf(AcceptanceSession.contentOf(data).keySet()), "no other file");
    }

    @Test
    void aDocumentFromAFutureVersionIsReadOnlyAndOnlyACopyCanBeSaved(@TempDir Path root) throws Exception {
        Path file = root.resolve("futur.calc.json");
        Files.writeString(file, FUTURE);
        byte[] original = Files.readAllBytes(file);

        try (AcceptanceSession session = AcceptanceSession.start(Files.createDirectories(root.resolve("data")))) {
            NotebookDriver d = session.d;
            session.dialogs.willOpen(file);
            d.open();
            assertEquals(List.of(TYPED_BEFORE), session.lines());
            assertTrue(d.view.title().contains("lecture seule"), d.view.title());
            assertFalse(d.view.canSave());

            d.click(1).line("2 + 2");
            d.save();
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertArrayEquals(original, Files.readAllBytes(file), "save refuses");

            session.dialogs.willSaveTo(file);
            d.saveAs();
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertArrayEquals(original, Files.readAllBytes(file), "save as refuses the same file");

            Path copy = root.resolve("copie");
            session.dialogs.willSaveTo(copy);
            d.saveAs();
            DecodedNotebook written = NotebookCodec.read(root.resolve("copie.calc.json"));
            assertEquals(NotebookCodec.VERSION, written.version());
            assertEquals(List.of(TYPED_BEFORE, "2 + 2"), written.notebook().texts());
            assertArrayEquals(original, Files.readAllBytes(file));
            session.dialogs.assertAllUsed();
        }
    }

    // ------------------------------------------------------------------------------------------------ two instances

    private static void assertASecondInstanceWritesNothing(Path data) throws Exception {
        Map<String, String> before = AcceptanceSession.contentOf(data);
        try (AcceptanceSession second = AcceptanceSession.start(data)) {
            NotebookDriver d = second.d;
            assertTrue(second.store.isReadOnly());
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertTrue(d.view.statusMessage().contains("déjà ouvert"), d.view.statusMessage());

            d.line("9 + 9");
            d.undo();
            d.redo();
            d.clear();
            assertEquals(StatusTone.ERROR, d.view.statusTone(), "the warning stays for the whole session");
            assertEquals(before, AcceptanceSession.contentOf(data), "not a byte");
        }
        assertEquals(before, AcceptanceSession.contentOf(data));
    }

    @Test
    void aSecondInstanceInTheSameProcessWritesNothing(@TempDir Path data) throws Exception {
        try (AcceptanceSession first = AcceptanceSession.start(data)) {
            first.d.line(TYPED_BEFORE);

            assertASecondInstanceWritesNothing(data);

            first.d.line("2 + 2");
            assertEquals(List.of(TYPED_BEFORE, "2 + 2"), NotebookCodec.read(data.resolve(CURRENT)).notebook().texts(), "the first one still saves");
        }
    }

    @Test
    void aSecondInstanceAlongsideAnotherProcessWritesNothing(@TempDir Path data) throws Exception {
        Process holder = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"), LockHolder.class.getName(), data.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            CompletableFuture<String> firstLine = CompletableFuture.supplyAsync(() -> {
                try {
                    return new BufferedReader(new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8)).readLine();
                } catch (IOException exception) {
                    return exception.toString();
                }
            });
            assertEquals("LOCKED", firstLine.get(60, TimeUnit.SECONDS));

            assertASecondInstanceWritesNothing(data);
        } finally {
            holder.destroyForcibly();
            holder.waitFor(30, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------------------------------------ failing disk

    private static AtomicFileWriter.Mover failingWhile(AtomicBoolean failing) {
        return (source, target, options) -> {
            if (failing.get()) {
                throw new IOException("simulated disk failure");
            }
            Files.move(source, target, options);
        };
    }

    @Test
    void anAutomaticSaveThatFailsKeepsTheFileIntactAndTheLineOnTheScreen(@TempDir Path data) throws Exception {
        try (AcceptanceSession session = AcceptanceSession.start(data)) {
            session.d.line(TYPED_BEFORE);
        }
        Map<String, String> before = AcceptanceSession.contentOf(data);
        AtomicBoolean failing = new AtomicBoolean(true);
        NotebookStore store = NotebookStore.open(data, null, Clock.systemDefaultZone(), Files::copy, failingWhile(failing));

        try (AcceptanceSession session = AcceptanceSession.start(store)) {
            NotebookDriver d = session.d;
            d.click(1).line("2 + 2");

            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertTrue(d.view.statusMessage().contains("Enregistrement automatique impossible"), d.view.statusMessage());
            assertEquals(List.of(TYPED_BEFORE, "2 + 2"), session.lines(), "the work stays in the window");
            assertEquals(before, AcceptanceSession.contentOf(data), "the old file is intact, no temporary file");

            failing.set(false);
            d.line("3 + 3");
            assertEquals(List.of(TYPED_BEFORE, "2 + 2", "3 + 3"), NotebookCodec.read(data.resolve(CURRENT)).notebook().texts(), "the next save catches up");
        }
    }

    @Test
    void anExplicitSaveThatFailsLeavesNoFileAndNoBrokenFile(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("data"));
        Path documents = Files.createDirectories(root.resolve("documents"));
        AtomicBoolean failing = new AtomicBoolean(true);
        NotebookStore store = NotebookStore.open(data);
        LoadedNotebook loaded = store.load();
        FakeDialogs dialogs = new FakeDialogs();
        NotebookPresenter presenter = new NotebookPresenter(new DocumentService(store, loaded, failingWhile(failing)), loaded,
                DisplayModeStore.noop(), dialogs);

        try (AcceptanceSession session = AcceptanceSession.of(store, dialogs, presenter)) {
            NotebookDriver d = session.d;
            d.line(TYPED_BEFORE);

            // Save as: nothing appears on the disk, the document stays untitled.
            dialogs.willSaveTo(documents.resolve("devis"));
            d.saveAs();
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertEquals(Map.of(), AcceptanceSession.contentOf(documents), "no file, no temporary file");
            assertEquals("Carnet sans titre — Calc", d.view.title());

            // Now with a file: a failing save leaves the previous content and the modified marker.
            failing.set(false);
            dialogs.willSaveTo(documents.resolve("devis"));
            d.saveAs();
            Map<String, String> saved = AcceptanceSession.contentOf(documents);
            assertEquals(List.of("devis.calc.json"), List.copyOf(saved.keySet()));

            failing.set(true);
            d.line("2 + 2");
            d.save();
            assertEquals(StatusTone.ERROR, d.view.statusTone());
            assertEquals(saved, AcceptanceSession.contentOf(documents), "the previous content, no temporary file");
            assertTrue(d.view.title().startsWith("• "), "still marked as modified: " + d.view.title());

            failing.set(false);
            d.save();
            assertEquals(List.of(TYPED_BEFORE, "2 + 2"), NotebookCodec.read(documents.resolve("devis.calc.json")).notebook().texts());
            dialogs.assertAllUsed();
        }
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }
}
