package calc.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.document.DocumentException.Kind;
import calc.format.DisplayMode;
import calc.notebook.LineId;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookException;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The documents: untitled (the current notebook, saved automatically) and associated with a file (saved only when asked,
 * with a modified indicator), the extension, read only, reserved paths and files that cannot be opened.
 */
class DocumentServiceTest {
    private static final String CURRENT = "carnet-courant.calc.json";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private NotebookStore store;
    private DocumentService service;
    // How many times the automatic save replaced the current notebook file.
    private final AtomicInteger autosaves = new AtomicInteger();
    private boolean failAutosave;
    private boolean failSave;

    @BeforeEach
    void start() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        store = openStore();
        service = new DocumentService(store, store.load(), mover());
    }

    // The final move of the explicit saves, which a test can make fail.
    private calc.engine.AtomicFileWriter.Mover mover() {
        return (source, target, options) -> {
            if (failSave) {
                throw new IOException("simulated disk failure");
            }
            Files.move(source, target, options);
        };
    }

    private static calc.persistence.LoadedNotebook loaded(Notebook notebook) {
        return new calc.persistence.LoadedNotebook(notebook, DisplayMode.AUTO, calc.persistence.LoadedNotebook.Condition.NORMAL,
                null, 0, 0, 0, null);
    }

    @AfterEach
    void stop() {
        store.close();
    }

    private NotebookStore openStore() {
        return NotebookStore.open(data, null, Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            if (target.getFileName().toString().equals(CURRENT)) {
                if (failAutosave) {
                    throw new IOException("simulated disk failure");
                }
                autosaves.incrementAndGet();
            }
            Files.move(source, target, options);
        });
    }

    private void append(String... texts) throws NotebookException {
        for (String text : texts) {
            service.apply(new NotebookCommand.Append(text));
        }
    }

    private List<String> draft() throws Exception {
        return NotebookCodec.read(data.resolve(CURRENT)).notebook().texts();
    }

    private static List<String> texts(DocumentService service) {
        return service.current().notebook().texts();
    }

    // ------------------------------------------------------------------------------------------------ untitled

    @Test
    void theStartingDocumentIsUntitledEmptyAndUnmodified() {
        assertTrue(service.isUntitled());
        assertEquals(java.util.Optional.empty(), service.path());
        assertFalse(service.isModified());
        assertFalse(service.wouldLoseWork());
        assertFalse(service.isStoreReadOnly());
        assertEquals(DisplayMode.AUTO, service.displayMode());
        assertTrue(service.current().notebook().isEmpty());
    }

    @Test
    void theUntitledNotebookIsSavedAfterEveryCommandThatChangesIt() throws Exception {
        append("# Devis", "surface = 12,5");
        assertEquals(2, autosaves.get(), "once per command");
        assertEquals(List.of("# Devis", "surface = 12,5"), draft());

        service.apply(new NotebookCommand.Edit(service.current().notebook().line(2).id(), "surface = 13"));
        service.apply(new NotebookCommand.InsertAfter(service.current().notebook().line(1).id(), "surface * 2"));
        service.apply(new NotebookCommand.Move(service.current().notebook().line(1).id(), 2));
        service.apply(new NotebookCommand.Delete(service.current().notebook().line(1).id()));
        assertEquals(6, autosaves.get());
        assertEquals(texts(service), draft());

        service.undo();
        assertEquals(texts(service), draft(), "undo is saved too");
        service.redo();
        assertEquals(texts(service), draft(), "and redo");
        service.apply(new NotebookCommand.Clear());
        assertEquals(List.of(), draft());
        assertEquals(9, autosaves.get());
        assertFalse(service.isModified(), "the untitled document is never « modified »: it is always saved");
        assertTrue(service.autosaveProblem().isEmpty());
    }

    @Test
    void aCommandThatChangesNothingWritesNothing() throws Exception {
        append("a = 1");
        int before = autosaves.get();
        LineId line = service.current().notebook().line(1).id();

        service.apply(new NotebookCommand.Edit(line, "a = 1"));
        service.apply(new NotebookCommand.Move(line, 0));
        service.apply(new NotebookCommand.Delete(line));
        service.apply(new NotebookCommand.Clear());
        service.apply(new NotebookCommand.Clear());

        assertEquals(before + 1, autosaves.get(), "only the delete wrote: the clears of an empty notebook changed nothing");
    }

    @Test
    void aRefusedCommandChangesAndWritesNothing() throws Exception {
        Notebook full = calc.testing.NotebookGenerators.notebook(java.util.Collections.nCopies(Notebook.MAX_LINES, "1"));
        service = new DocumentService(store, loaded(full), mover());
        int before = autosaves.get();

        assertThrows(NotebookException.class, () -> service.apply(new NotebookCommand.Append("one too many")));

        assertEquals(before, autosaves.get());
        assertEquals(Notebook.MAX_LINES, service.current().notebook().size());
    }

    @Test
    void theDisplayModeIsSavedWithTheNotebook() throws Exception {
        append("1/3");
        service.setDisplayMode(DisplayMode.FRACTION);
        service.setDisplayMode(DisplayMode.FRACTION);

        assertEquals(DisplayMode.FRACTION, NotebookCodec.read(data.resolve(CURRENT)).displayMode());
        assertEquals(2, autosaves.get(), "one for the line, one for the mode: the second call changes nothing");
    }

    @Test
    void theNotebookComesBackAtTheNextStart() throws Exception {
        append("# Devis", "x = 2", "x * 21");
        store.close();

        store = openStore();
        service = new DocumentService(store, store.load());

        assertEquals(List.of("# Devis", "x = 2", "x * 21"), texts(service));
        assertTrue(service.isUntitled());
    }

    @Test
    void aFailingDiskIsReportedNotThrownAndTheEditIsKept() throws Exception {
        append("1");
        failAutosave = true;

        append("2");

        assertEquals(List.of("1", "2"), texts(service), "the edit is applied");
        assertTrue(service.autosaveProblem().orElseThrow().startsWith("Impossible d'enregistrer le carnet"));
        assertEquals(List.of("1"), draft(), "the file is still the previous notebook");

        failAutosave = false;
        append("3");
        assertTrue(service.autosaveProblem().isEmpty(), "the next write repairs it");
        assertEquals(List.of("1", "2", "3"), draft());
    }

    @Test
    void aSecondInstanceNeverWritesTheCurrentNotebookButCanSaveAFile() throws Exception {
        append("mine = 1");
        byte[] mine = Files.readAllBytes(data.resolve(CURRENT));
        try (NotebookStore second = NotebookStore.open(data)) {
            DocumentService other = new DocumentService(second, second.load());
            assertTrue(other.isStoreReadOnly(), "the state is exposed");
            assertEquals(List.of("mine = 1"), texts(other));

            other.apply(new NotebookCommand.Append("typed in the second window"));
            other.newDocument();
            other.apply(new NotebookCommand.Append("again"));

            assertArrayEquals(mine, Files.readAllBytes(data.resolve(CURRENT)), "not one byte written");
            assertTrue(other.autosaveProblem().isEmpty(), "and it is not a failure");
            other.saveAs(documents.resolve("elsewhere"));
            assertEquals(List.of("again"), NotebookCodec.read(documents.resolve("elsewhere.calc.json")).notebook().texts());
        }
    }

    // ------------------------------------------------------------------------------------------------ save as, save

    @Test
    void theExtensionIsAddedWhenItIsMissing() {
        assertEquals(documents.resolve("devis.calc.json"), DocumentService.withExtension(documents.resolve("devis")));
        assertEquals(documents.resolve("devis.calc.json"), DocumentService.withExtension(documents.resolve("devis.calc.json")));
        assertEquals(documents.resolve("DEVIS.CALC.JSON"), DocumentService.withExtension(documents.resolve("DEVIS.CALC.JSON")));
        assertEquals(documents.resolve("devis.json.calc.json"), DocumentService.withExtension(documents.resolve("devis.json")));
        assertEquals(documents.resolve("devis.calc.calc.json"), DocumentService.withExtension(documents.resolve("devis.calc")));
        assertEquals(documents.resolve("devis.calc.json.txt.calc.json"), DocumentService.withExtension(documents.resolve("devis.calc.json.txt")));
    }

    @Test
    void saveAsAssociatesTheDocumentWithItsFileAndEmptiesTheDraft() throws Exception {
        append("# Devis", "surface = 12,5");

        Path written = service.saveAs(documents.resolve("devis"));

        assertEquals(documents.resolve("devis.calc.json"), written);
        assertEquals(java.util.Optional.of(written), service.path());
        assertFalse(service.isUntitled());
        assertFalse(service.isModified());
        assertEquals(List.of("# Devis", "surface = 12,5"), NotebookCodec.read(written).notebook().texts());
        assertEquals(List.of(), draft(), "the content now lives in the file: the draft is emptied");
        assertEquals(List.of("devis.calc.json"), names(documents), "no temporary file");
    }

    @Test
    void aDocumentWithAFileIsNeverSavedAutomaticallyAndTracksItsModifiedState() throws Exception {
        append("a = 1", "a + 1");
        Path file = service.saveAs(documents.resolve("calc"));
        int draftWrites = autosaves.get();
        byte[] saved = Files.readAllBytes(file);

        append("a + 2");
        service.apply(new NotebookCommand.Edit(service.current().notebook().line(1).id(), "a = 10"));

        assertTrue(service.isModified(), "differs from the file");
        assertTrue(service.wouldLoseWork());
        assertArrayEquals(saved, Files.readAllBytes(file), "the file is not touched by an edit");
        assertEquals(draftWrites, autosaves.get(), "and the draft is not written either");

        service.undo();
        assertTrue(service.isModified());
        service.undo();
        assertFalse(service.isModified(), "back to the saved notebook: no longer modified");
        service.redo();
        assertTrue(service.isModified());

        service.save();
        assertFalse(service.isModified());
        assertEquals(List.of("a = 1", "a + 1", "a + 2"), NotebookCodec.read(file).notebook().texts(), "what is on screen: the redone line, not the undone edit");
        assertEquals(draftWrites, autosaves.get());
    }

    @Test
    void changingTheDisplayModeModifiesAnAssociatedDocument() throws Exception {
        append("1/3");
        Path file = service.saveAs(documents.resolve("m"));

        service.setDisplayMode(DisplayMode.SCIENTIFIC);
        assertTrue(service.isModified());
        service.setDisplayMode(DisplayMode.AUTO);
        assertFalse(service.isModified());
        service.setDisplayMode(DisplayMode.DECIMAL);
        service.save();

        assertEquals(DisplayMode.DECIMAL, NotebookCodec.read(file).displayMode());
        assertFalse(service.isModified());
    }

    @Test
    void saveAsAnotherNameKeepsTheFirstFileAndMovesTheAssociation() throws Exception {
        append("1");
        Path first = service.saveAs(documents.resolve("first"));
        append("2");

        Path second = service.saveAs(documents.resolve("second.calc.json"));

        assertEquals(List.of("1"), NotebookCodec.read(first).notebook().texts());
        assertEquals(List.of("1", "2"), NotebookCodec.read(second).notebook().texts());
        assertEquals(java.util.Optional.of(second), service.path());
        assertFalse(service.isModified());
    }

    @Test
    void saveOfAnUntitledDocumentIsAMistakeOfTheCaller() {
        assertThrows(IllegalStateException.class, () -> service.save());
    }

    @Test
    void aFailedSaveKeepsTheFileAndTheModifiedState() throws Exception {
        append("1");
        Path file = service.saveAs(documents.resolve("f"));
        byte[] saved = Files.readAllBytes(file);
        append("2");
        failSave = true;

        DocumentException failure = assertThrows(DocumentException.class, () -> service.save());

        assertEquals(Kind.INPUT_OUTPUT, failure.kind());
        assertEquals("Impossible d'enregistrer le fichier : f.calc.json", failure.getMessage());
        assertTrue(service.isModified(), "the document is still not saved");
        assertArrayEquals(saved, Files.readAllBytes(file), "the old file is intact");
        assertEquals(List.of("f.calc.json"), names(documents), "no temporary file");

        failSave = false;
        service.save();
        assertFalse(service.isModified());
    }

    @Test
    void aFailedFirstSaveAsLeavesTheDocumentUntitledAndTheDraftAsItWas() throws Exception {
        append("1");
        failSave = true;

        assertEquals(Kind.INPUT_OUTPUT, assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("x"))).kind());

        assertTrue(service.isUntitled());
        assertEquals(List.of("1"), draft(), "the draft is emptied only once the file is written");
        assertEquals(List.of(), names(documents));
    }

    // ------------------------------------------------------------------------------------------------ open

    @Test
    void openingAFileLeavesTheDraftAloneAndShowsTheFile() throws Exception {
        append("draft line");
        Path file = documents.resolve("received.calc.json");
        Files.write(file, NotebookCodec.encode(calc.testing.NotebookGenerators.notebook(List.of("# Reçu", "x = 3", "x * x")), DisplayMode.DECIMAL).bytes());
        byte[] draftBefore = Files.readAllBytes(data.resolve(CURRENT));

        service.open(file);

        assertEquals(List.of("# Reçu", "x = 3", "x * x"), texts(service));
        assertEquals(DisplayMode.DECIMAL, service.displayMode());
        assertEquals(java.util.Optional.of(file), service.path());
        assertFalse(service.isModified());
        assertFalse(service.canUndo(), "the undo history of the previous document is forgotten");
        assertEquals("9", service.current().result(3).value().lower().toString(), "the results are recalculated");
        assertArrayEquals(draftBefore, Files.readAllBytes(data.resolve(CURRENT)), "nothing the user typed is lost by opening a file");
    }

    @Test
    void anOpenedFileIsKeptLineForLineEvenWhenSomeLinesAreInError() throws Exception {
        Path file = documents.resolve("wrong.calc.json");
        Files.writeString(file, "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[{\"id\":\"1\",\"source\":\"1 +\"},{\"id\":\"2\",\"source\":\"$9\"},{\"id\":\"3\",\"source\":\"2 * 3\"}]}");

        service.open(file);

        assertEquals(List.of("1 +", "$9", "2 * 3"), texts(service));
        assertTrue(service.current().result(1).isError());
        assertTrue(service.current().result(2).isError());
        assertEquals("6", service.current().result(3).value().lower().toString());
        assertFalse(service.isModified());
    }

    @Test
    void whatCannotBeOpenedLeavesTheCurrentDocumentAsItWas() throws Exception {
        append("mine");
        Path notANotebook = documents.resolve("history.json.copy");
        Files.writeString(notANotebook, "{\"format\":\"calc-history\",\"version\":2,\"entries\":[]}");
        Path hostile = documents.resolve("hostile.calc.json");
        Files.writeString(hostile, "[".repeat(1_000_000));
        Path text = documents.resolve("letter.txt");
        Files.writeString(text, "Dear Calc, please open me.");

        DocumentException refusal = assertThrows(DocumentException.class, () -> service.open(notANotebook));
        assertEquals(Kind.INVALID_FILE, refusal.kind());
        assertEquals("Ce fichier n'est pas un carnet Calc.", refusal.getMessage());
        long start = System.nanoTime();
        assertEquals(Kind.INVALID_FILE, assertThrows(DocumentException.class, () -> service.open(hostile)).kind());
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "a hostile file is refused quickly");
        assertEquals(Kind.INVALID_FILE, assertThrows(DocumentException.class, () -> service.open(text)).kind());
        assertEquals(Kind.INPUT_OUTPUT, assertThrows(DocumentException.class, () -> service.open(documents.resolve("missing.calc.json"))).kind());
        assertEquals(Kind.INVALID_FILE, assertThrows(DocumentException.class, () -> service.open(documents)).kind(), "a directory");

        assertEquals(List.of("mine"), texts(service));
        assertTrue(service.isUntitled());
    }

    @Test
    void aFileFromANewerVersionIsReadOnlyAndNeverRewrittenNotEvenToTheSamePath() throws Exception {
        Path file = documents.resolve("future.calc.json");
        String newer = "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\",\"later\":true}]}";
        Files.writeString(file, newer);

        service.open(file);
        assertTrue(service.isReadOnlyFile());
        assertEquals(List.of("1 + 1"), texts(service));
        append("2 + 2");
        assertTrue(service.isModified());

        assertEquals(Kind.READ_ONLY_VERSION, assertThrows(DocumentException.class, () -> service.save()).kind());
        assertEquals(Kind.READ_ONLY_VERSION, assertThrows(DocumentException.class, () -> service.saveAs(file)).kind());
        assertEquals(Kind.READ_ONLY_VERSION, assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("sub").resolve("..").resolve("future.calc.json"))).kind());
        assertEquals(newer, Files.readString(file), "the file is what it was, byte for byte");

        Path copy = service.saveAs(documents.resolve("copy"));
        assertFalse(service.isReadOnlyFile(), "a copy is a version 3 file");
        assertEquals(List.of("1 + 1", "2 + 2"), NotebookCodec.read(copy).notebook().texts());
        assertEquals(newer, Files.readString(file));
    }

    @Test
    void aFileFromANewerVersionThatCannotBeReadIsRefused() throws Exception {
        Path file = documents.resolve("future.calc.json");
        Files.writeString(file, "{\"format\":\"calc-notebook\",\"version\":9,\"blocks\":[]}");

        assertEquals(Kind.UNREADABLE_NEWER_VERSION, assertThrows(DocumentException.class, () -> service.open(file)).kind());
        assertTrue(service.isUntitled());
    }

    // ------------------------------------------------------------------------------------------------ new, reserved

    @Test
    void newDocumentIsEmptyUntitledAndSavedAsTheDraft() throws Exception {
        append("old");
        assertTrue(service.wouldLoseWork(), "the presenter is told that something would be replaced");

        service.newDocument();

        assertTrue(service.isUntitled());
        assertTrue(service.current().notebook().isEmpty());
        assertFalse(service.canUndo());
        assertEquals(List.of(), draft());
        assertFalse(service.wouldLoseWork());
    }

    @Test
    void newAfterOpenWarnsThatTheDraftOffScreenWouldBeReplaced() throws Exception {
        append("draft 1", "draft 2");
        Path file = documents.resolve("received.calc.json");
        Files.write(file, NotebookCodec.encode(calc.testing.NotebookGenerators.notebook(List.of("1 + 1")), DisplayMode.AUTO).bytes());
        service.open(file);

        assertFalse(service.isModified());
        assertTrue(service.wouldLoseWork(), "the draft is no longer on screen, and new would overwrite it");
        assertEquals(List.of("draft 1", "draft 2"), draft(), "until then it is intact");

        service.open(file);
        assertTrue(service.wouldLoseWork(), "opening again does not forget it");
        service.newDocument();
        assertEquals(List.of(), draft());
        assertFalse(service.wouldLoseWork());
    }

    @Test
    void anEmptyDraftIsNotWorthAWarningAfterOpen() throws Exception {
        Path file = documents.resolve("received.calc.json");
        Files.write(file, NotebookCodec.encode(calc.testing.NotebookGenerators.notebook(List.of("1 + 1")), DisplayMode.AUTO).bytes());

        service.open(file);

        assertFalse(service.wouldLoseWork());
    }

    @Test
    void aPathWithoutAFileNameIsRefusedNotACrash() {
        Path root = documents.getRoot();

        assertEquals(Kind.INPUT_OUTPUT, assertThrows(DocumentException.class, () -> service.saveAs(root)).kind());
    }

    @Test
    void newDocumentAfterAnAssociatedOneLeavesItsFileAlone() throws Exception {
        append("1");
        Path file = service.saveAs(documents.resolve("kept"));
        append("2");
        byte[] saved = Files.readAllBytes(file);
        assertTrue(service.wouldLoseWork());

        service.newDocument();

        assertArrayEquals(saved, Files.readAllBytes(file), "an unsaved edit is lost only if the caller chose to, the file never changes");
        assertTrue(service.isUntitled());
        append("fresh");
        assertEquals(List.of("fresh"), draft());
    }

    @Test
    void theFilesCalcKeepsForItselfCannotBeOpenedNorSavedOver() throws Exception {
        append("precious");
        byte[] draft = Files.readAllBytes(data.resolve(CURRENT));

        for (String name : List.of(CURRENT, "history.json", "history.lock", "history.tsv")) {
            assertEquals(Kind.RESERVED_PATH, assertThrows(DocumentException.class, () -> service.open(data.resolve(name))).kind(), name);
        }
        // The extension is added first, so only the name of the draft itself can be asked for as a destination.
        assertEquals(Kind.RESERVED_PATH, assertThrows(DocumentException.class, () -> service.saveAs(data.resolve(CURRENT))).kind());
        assertEquals(Kind.RESERVED_PATH, assertThrows(DocumentException.class, () -> service.saveAs(data.resolve("carnet-courant"))).kind());

        assertArrayEquals(draft, Files.readAllBytes(data.resolve(CURRENT)));
        assertTrue(service.isUntitled());
    }

    @Test
    void aNotebookTooLargeToBeWrittenIsReportedAndNothingIsCreated() throws Exception {
        // 5000 lines of 16 384 two-byte characters: each line is fine, the file is over the size limit.
        Notebook huge = calc.testing.NotebookGenerators.notebook(java.util.Collections.nCopies(Notebook.MAX_LINES, "é".repeat(16_384)));
        service = new DocumentService(store, loaded(huge), mover());

        service.apply(new NotebookCommand.Edit(service.current().notebook().line(1).id(), "short"));
        assertTrue(service.autosaveProblem().isPresent(), "the automatic save cannot write it, and says so");
        DocumentException refusal = assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("huge")));

        assertEquals(Kind.TOO_LARGE_TO_SAVE, refusal.kind());
        assertEquals("Ce fichier dépasse la taille maximale d'un carnet (16 Mio).", refusal.getMessage());
        assertEquals(List.of(), names(documents), "no file, no temporary file");
        assertTrue(service.isUntitled());
    }

    private static List<String> names(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
