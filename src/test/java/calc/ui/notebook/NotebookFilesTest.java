package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.export.NotebookExporterTest;
import calc.format.DisplayMode;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.NotebookGenerators;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs.ExportFormat;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The File menu without Swing (docs/adr/0011-fichiers-dans-l-interface.md): new, open, save, save as, the exports, the title,
 * the confirmations and the messages of the opening, with the dialogs played by {@link FakeDialogs} (no test opens a real
 * dialog; a question that was not queued fails the test). The model, the documents and the files are the real ones, on
 * temporary folders.
 */
class NotebookFilesTest {
    private static final String CURRENT = "carnet-courant.calc.json";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private NotebookStore store;
    private FakeDialogs dialogs;
    private NotebookDriver d;

    @BeforeEach
    void start() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        store = NotebookStore.open(data);
        dialogs = new FakeDialogs();
        d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Path write(String name, String content) throws IOException {
        Path file = documents.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    private Path writeNotebook(String name, String... lines) throws Exception {
        Path file = documents.resolve(name);
        Files.write(file, NotebookCodec.encode(NotebookGenerators.notebook(List.of(lines)), DisplayMode.AUTO).bytes());
        return file;
    }

    private List<String> savedTexts(Path file) throws Exception {
        return NotebookCodec.read(file).notebook().texts();
    }

    // Types the lines and leaves the document untitled, as the user does at the first start.
    private void type(String... lines) {
        for (String line : lines) {
            d.line(line);
        }
    }

    // A document with a file: two lines typed, then saved as documents/name.
    private Path saveTwoLinesAs(String name) {
        type("a = 2", "a * 3");
        Path file = documents.resolve(name);
        dialogs.willSaveTo(file);
        d.saveAs();
        return documents.resolve(name.endsWith(".calc.json") ? name : name + ".calc.json");
    }

    // Adds a line at the end of the notebook (an opened document starts on its first line).
    private void append(String text) {
        d.click(d.texts().size() - 1);
        d.line(text);
    }

    private static List<String> lines(NotebookDriver driver) {
        return driver.texts().subList(0, driver.texts().size() - 1);
    }

    // ------------------------------------------------------------------------------------------------ title and modified marker

    @Test
    void theTitleNamesTheDocumentAndMarksAnAssociatedDocumentThatChanged() throws Exception {
        assertEquals("Carnet sans titre — Calc", d.view.title());

        type("1 + 1");
        assertEquals("Carnet sans titre — Calc", d.view.title(), "the untitled notebook is autosaved: never marked");
        saveTwoLinesAs("devis");
        assertEquals("devis.calc.json — Calc", d.view.title());

        append("ans + 1");
        assertEquals("• devis.calc.json — Calc", d.view.title(), "an edit of a document with a file is marked");
        d.undo();
        assertEquals("devis.calc.json — Calc", d.view.title(), "undoing back to the saved state clears the marker");
        d.redo();
        assertTrue(d.view.title().startsWith("• "));
        d.save();
        assertEquals("devis.calc.json — Calc", d.view.title(), "saving clears the marker");
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ save and save as

    @Test
    void saveAsAddsTheExtensionWritesTheFileAndAssociatesTheDocument() throws Exception {
        Path expected = saveTwoLinesAs("devis");

        assertEquals(List.of("a = 2", "a * 3"), savedTexts(expected));
        assertEquals("devis.calc.json — Calc", d.view.title());
        assertEquals(StatusTone.SUCCESS, d.view.statusTone());
        assertTrue(d.view.statusMessage().contains(expected.toString()), d.view.statusMessage());
        assertEquals(List.of("save as"), dialogs.asked, "a new name is not an overwrite: no question");

        // A name that already ends with the extension (whatever its case) gets no second one.
        dialogs.willSaveTo(documents.resolve("autre.CALC.JSON"));
        d.saveAs();
        assertTrue(Files.exists(documents.resolve("autre.CALC.JSON")));
        assertFalse(Files.exists(documents.resolve("autre.CALC.JSON.calc.json")));
        assertEquals("autre.CALC.JSON — Calc", d.view.title());
        dialogs.assertAllUsed();
    }

    @Test
    void saveAsAsksBeforeReplacingAFileAndKeepsItWhenRefused() throws Exception {
        Path existing = writeNotebook("existing.calc.json", "precious");
        byte[] before = Files.readAllBytes(existing);
        type("1 + 1");

        dialogs.willSaveTo(documents.resolve("existing")).willConfirmOverwrite(false);
        d.saveAs();
        assertArrayEquals(before, Files.readAllBytes(existing), "refused: the file is untouched");
        assertEquals("Carnet sans titre — Calc", d.view.title(), "and the document is still the untitled one");
        assertEquals(List.of("save as", "overwrite existing.calc.json"), dialogs.asked);

        dialogs.willSaveTo(documents.resolve("existing")).willConfirmOverwrite(true);
        d.saveAs();
        assertEquals(List.of("1 + 1"), savedTexts(existing));
        assertEquals("existing.calc.json — Calc", d.view.title());

        // Saving as the file the document already has is not a replacement of someone else's file.
        append("2 + 2");
        dialogs.willSaveTo(existing);
        d.saveAs();
        assertEquals(List.of("1 + 1", "2 + 2"), savedTexts(existing));
        dialogs.assertAllUsed();
    }

    @Test
    void cancellingTheChooserChangesNothing() throws Exception {
        type("1 + 1");
        dialogs.willSaveTo(null);
        d.saveAs();
        dialogs.willSaveTo(null);
        d.save();

        assertEquals("Carnet sans titre — Calc", d.view.title());
        assertEquals(List.of("1 + 1"), lines(d));
        assertEquals(List.of("1 + 1"), savedTexts(data.resolve(CURRENT)), "the autosave is still the safety net");
        dialogs.assertAllUsed();
    }

    @Test
    void saveOnAnUntitledNotebookAsksForANameAndSaveOnADocumentWithAFileDoesNot() throws Exception {
        type("1 + 1");
        dialogs.willSaveTo(documents.resolve("one"));
        d.save();
        Path file = documents.resolve("one.calc.json");
        assertEquals(List.of("1 + 1"), savedTexts(file));

        append("2 + 2");
        d.save();
        assertEquals(List.of("1 + 1", "2 + 2"), savedTexts(file));
        assertEquals("« one.calc.json » enregistré.", d.view.statusMessage());
        assertEquals(List.of("save as"), dialogs.asked, "the second save asked nothing");
        dialogs.assertAllUsed();
    }

    @Test
    void savingValidatesTheLineBeingTypedFirst() throws Exception {
        Path file = saveTwoLinesAs("typed");

        d.type("ans + 1");
        d.save();

        assertEquals(List.of("a = 2", "a * 3", "ans + 1"), savedTexts(file));
    }

    @Test
    void aFailedSaveSaysSoAndKeepsTheDocumentModified() throws Exception {
        Path file = saveTwoLinesAs("fragile");
        append("ans + 1");
        Files.delete(file);
        Files.createDirectory(file); // a directory where the file was: the move cannot replace it

        d.save();

        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertTrue(d.view.statusMessage().startsWith("Impossible d'enregistrer le fichier"), d.view.statusMessage());
        assertTrue(d.view.title().startsWith("• "), "still modified");
    }

    @Test
    void theFilesCalcKeepsForItselfAreRefusedAsADestination() throws Exception {
        type("1 + 1");
        byte[] draft = Files.readAllBytes(data.resolve(CURRENT));

        dialogs.willSaveTo(data.resolve(CURRENT));
        d.saveAs();

        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertEquals(NotebookPresenter.RESERVED_FILE_STATUS, d.view.statusMessage());
        assertArrayEquals(draft, Files.readAllBytes(data.resolve(CURRENT)));
        assertEquals(List.of("save as"), dialogs.asked, "no question about replacing a file that is refused anyway");
    }

    // ------------------------------------------------------------------------------------------------ new

    @Test
    void newOnAnEmptyNotebookAsksNothingAndNewOnAnUntitledNotebookWithLinesAsksFirst() throws Exception {
        d.newDocument();
        assertEquals(List.of(), dialogs.asked);
        assertEquals("Nouveau carnet.", d.view.statusMessage());

        type("1 + 1", "ans * 2");
        dialogs.willAnswer(Unsaved.CANCEL);
        d.newDocument();
        assertEquals(List.of("1 + 1", "ans * 2"), lines(d), "cancel: nothing happened");

        dialogs.willAnswer(Unsaved.DISCARD);
        d.newDocument();
        assertEquals(List.of(""), d.texts(), "discard: an empty notebook");
        assertEquals(List.of(), savedTexts(data.resolve(CURRENT)), "and the autosaved file is the empty one");
        assertEquals(List.of("unsaved Carnet sans titre NEW", "unsaved Carnet sans titre NEW"), dialogs.asked);
        dialogs.assertAllUsed();
    }

    @Test
    void newWithSaveWritesTheFileFirstAndCancellingItsChooserKeepsTheNotebook() throws Exception {
        type("1 + 1");
        dialogs.willAnswer(Unsaved.SAVE).willSaveTo(null);
        d.newDocument();
        assertEquals(List.of("1 + 1"), lines(d), "save, then no name given: the user changed their mind");

        dialogs.willAnswer(Unsaved.SAVE).willSaveTo(documents.resolve("kept"));
        d.newDocument();
        assertEquals(List.of(), lines(d));
        assertEquals("Carnet sans titre — Calc", d.view.title());
        assertEquals(List.of("1 + 1"), savedTexts(documents.resolve("kept.calc.json")));
        dialogs.assertAllUsed();
    }

    @Test
    void newOnAnUnchangedDocumentWithAFileAsksNothingAndLeavesTheFileAlone() throws Exception {
        Path file = saveTwoLinesAs("work");
        byte[] saved = Files.readAllBytes(file);

        d.newDocument();

        assertEquals(List.of(), lines(d));
        assertEquals("Carnet sans titre — Calc", d.view.title());
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertEquals(List.of("save as"), dialogs.asked, "no question about the unchanged file");
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ confirmations

    @Test
    void theThreeChoicesOfTheConfirmationForNewOpenAndQuit() throws Exception {
        Path file = saveTwoLinesAs("work");
        Path other = writeNotebook("other.calc.json", "9 + 9");
        byte[] saved = Files.readAllBytes(file);

        // NEW
        append("ans + 1");
        dialogs.willAnswer(Unsaved.CANCEL);
        d.newDocument();
        assertEquals("• work.calc.json — Calc", d.view.title(), "new, cancel: the document and its changes stay");
        assertArrayEquals(saved, Files.readAllBytes(file));

        dialogs.willAnswer(Unsaved.DISCARD);
        d.newDocument();
        assertEquals("Carnet sans titre — Calc", d.view.title(), "new, discard: replaced");
        assertArrayEquals(saved, Files.readAllBytes(file), "the file keeps what was saved");

        // OPEN: back to the file, change it, then three answers
        dialogs.willOpen(file);
        d.open();
        assertEquals("work.calc.json — Calc", d.view.title());
        append("ans + 1");
        dialogs.willOpen(other).willAnswer(Unsaved.CANCEL);
        d.open();
        assertEquals("• work.calc.json — Calc", d.view.title(), "open, cancel: stays on the document");
        assertEquals(List.of("a = 2", "a * 3", "ans + 1"), lines(d));

        dialogs.willOpen(other).willAnswer(Unsaved.SAVE);
        d.open();
        assertEquals("other.calc.json — Calc", d.view.title(), "open, save: the file is written, then the other one is open");
        assertEquals(List.of("a = 2", "a * 3", "ans + 1"), savedTexts(file));
        assertEquals(List.of("9 + 9"), lines(d));

        append("ans + 1");
        dialogs.willOpen(file).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("work.calc.json — Calc", d.view.title(), "open, discard: the other one is dropped");
        assertEquals(List.of("9 + 9"), savedTexts(other), "and its file keeps what was saved");

        // QUIT
        append("ans + 2");
        dialogs.willAnswer(Unsaved.CANCEL);
        d.close();
        assertFalse(d.view.closeWindow(), "quit, cancel: the window stays");

        dialogs.willAnswer(Unsaved.DISCARD);
        d.close();
        assertTrue(d.view.closeWindow(), "quit, discard: closes");
        assertEquals(List.of("a = 2", "a * 3", "ans + 1"), savedTexts(file), "without writing the file");

        append("ans + 3");
        dialogs.willAnswer(Unsaved.SAVE);
        d.close();
        assertTrue(d.view.closeWindow(), "quit, save: writes, then closes");
        assertTrue(savedTexts(file).contains("ans + 3"));
        dialogs.assertAllUsed();
    }

    @Test
    void anUntitledNotebookNeverAsksToOpenOrToQuitBecauseItIsSavedAutomatically() throws Exception {
        type("1 + 1");
        Path other = writeNotebook("other.calc.json", "9 + 9");

        dialogs.willOpen(other);
        d.open();
        assertEquals(List.of("open"), dialogs.asked, "only the chooser");
        assertEquals(List.of("1 + 1"), savedTexts(data.resolve(CURRENT)), "the draft is still on disk");

        d.close();
        assertTrue(d.view.closeWindow());
        assertEquals(List.of("open"), dialogs.asked);
    }

    @Test
    void quitWithACancelledSaveStaysOpen() throws Exception {
        saveTwoLinesAs("work");
        append("ans + 1");

        dialogs.willAnswer(Unsaved.SAVE);
        // the document has a file: the save needs no chooser; make it fail instead by replacing the file with a directory
        Path file = documents.resolve("work.calc.json");
        Files.delete(file);
        Files.createDirectory(file);
        d.close();

        assertFalse(d.view.closeWindow(), "a failed save must not close the window on the unsaved work");
        assertEquals(StatusTone.ERROR, d.view.statusTone());
        dialogs.assertAllUsed();
    }

    @Test
    void closingValidatesTheLineBeingTypedBeforeAskingAnything() throws Exception {
        Path file = saveTwoLinesAs("work");
        d.type("ans + 1");

        dialogs.willAnswer(Unsaved.SAVE);
        d.close();

        assertTrue(d.view.closeWindow());
        assertEquals(List.of("a = 2", "a * 3", "ans + 1"), savedTexts(file), "the typed line was part of what is saved");
    }

    @Test
    void newReplacesTheDraftLeftOffScreenByAnOpenedFileOnlyAfterAConfirmation() throws Exception {
        type("my draft");
        Path other = writeNotebook("other.calc.json", "9 + 9");
        dialogs.willOpen(other);
        d.open();

        dialogs.willConfirmReplaceDraft(false);
        d.newDocument();
        assertEquals("other.calc.json — Calc", d.view.title(), "refused: still on the opened file");
        assertEquals(List.of("my draft"), savedTexts(data.resolve(CURRENT)));

        dialogs.willConfirmReplaceDraft(true);
        d.newDocument();
        assertEquals("Carnet sans titre — Calc", d.view.title());
        assertEquals(List.of(), savedTexts(data.resolve(CURRENT)));
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ open: messages

    @Test
    void openingAFileShowsItAndSaysHowManyLinesAreInError() throws Exception {
        Path file = writeNotebook("devis.calc.json", "2 + 2", "3 +", "ans * 2", "inconnu");

        dialogs.willOpen(file);
        d.open();

        assertEquals(List.of("2 + 2", "3 +", "ans * 2", "inconnu"), lines(d));
        assertEquals("devis.calc.json — Calc", d.view.title());
        assertEquals("« devis.calc.json » ouvert (4 lignes). 2 lignes contiennent une erreur.", d.view.statusMessage());

        dialogs.willOpen(writeNotebook("one.calc.json", "3 +"));
        d.open();
        assertEquals("« one.calc.json » ouvert (1 ligne). 1 ligne contient une erreur.", d.view.statusMessage());

        dialogs.willOpen(writeNotebook("fine.calc.json", "1 + 1", "# note"));
        d.open();
        assertEquals("« fine.calc.json » ouvert (2 lignes).", d.view.statusMessage());
        assertEquals(StatusTone.INFO, d.view.statusTone());
    }

    @Test
    void anOpenedFileBringsItsDisplayModeAndEverythingItHoldsIsKeptLineForLine() throws Exception {
        Path file = documents.resolve("fraction.calc.json");
        Files.write(file, NotebookCodec.encode(NotebookGenerators.notebook(List.of("1 / 3")), DisplayMode.FRACTION).bytes());

        dialogs.willOpen(file);
        d.open();

        assertEquals(DisplayMode.FRACTION, d.view.displayMode());
        assertEquals("1/3", d.row(1).result());
    }

    @Test
    void whatCannotBeOpenedSaysWhyAndNeverCostsTheCurrentDocument() throws Exception {
        saveTwoLinesAs("mine");
        append("ans + 1");
        Path invalid = write("not-json.calc.json", "ceci n'est pas du JSON");
        Path other = write("history.json", "{\"format\":\"calc-history\",\"version\":2,\"entries\":[]}");
        Path huge = documents.resolve("huge.calc.json");
        Files.write(huge, new byte[16 * 1024 * 1024 + 1]);
        Path future = write("future.calc.json", "{\"format\":\"calc-notebook\",\"version\":9,\"blocks\":[]}");
        Path missing = documents.resolve("missing.calc.json");

        for (Path file : List.of(invalid, other, huge, future, missing, documents, data.resolve(CURRENT))) {
            // The document has changes, so the user is asked first; "do not save" then fails to open: nothing was lost.
            dialogs.willOpen(file).willAnswer(Unsaved.DISCARD);
            d.open();
            assertEquals(StatusTone.ERROR, d.view.statusTone(), file.toString());
            assertEquals(List.of("a = 2", "a * 3", "ans + 1"), lines(d), "the document is still there: " + file);
            assertEquals("• mine.calc.json — Calc", d.view.title(), "with its changes: " + file);
        }
        // The reasons, in words.
        dialogs.willOpen(invalid).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("Impossible d'ouvrir « not-json.calc.json » : Ce fichier n'est pas un document JSON valide.", d.view.statusMessage());
        dialogs.willOpen(huge).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("Impossible d'ouvrir « huge.calc.json » : Ce fichier dépasse la taille maximale d'un carnet (16 Mio).",
                d.view.statusMessage());
        dialogs.willOpen(future).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("Impossible d'ouvrir « future.calc.json » : Ce carnet a été créé par une version plus récente de Calc : "
                + "il ne peut pas être lu.", d.view.statusMessage());
        dialogs.willOpen(missing).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("Impossible de lire le fichier : missing.calc.json", d.view.statusMessage());
        dialogs.willOpen(data.resolve(CURRENT)).willAnswer(Unsaved.DISCARD);
        d.open();
        assertEquals("Impossible d'ouvrir « carnet-courant.calc.json » : " + NotebookPresenter.RESERVED_FILE_STATUS,
                d.view.statusMessage());
        dialogs.assertAllUsed();
    }

    @Test
    void anInvalidFileLeavesTheUntitledNotebookAndItsAutosaveAsTheyWere() throws Exception {
        type("precious");
        byte[] draft = Files.readAllBytes(data.resolve(CURRENT));

        dialogs.willOpen(write("bad.calc.json", "{"));
        d.open();

        assertEquals(List.of("precious"), lines(d));
        assertEquals("Carnet sans titre — Calc", d.view.title());
        assertArrayEquals(draft, Files.readAllBytes(data.resolve(CURRENT)));
    }

    @Test
    void aFileFromANewerVersionOpensReadOnlyAndSaveAsStillWorks() throws Exception {
        String newer = "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\",\"later\":true}]}";
        Path file = write("future.calc.json", newer);

        dialogs.willOpen(file);
        d.open();

        assertEquals(List.of("1 + 1"), lines(d));
        assertEquals("future.calc.json (lecture seule) — Calc", d.view.title());
        assertFalse(d.view.canSave(), "Save is disabled");
        assertEquals(NotebookPresenter.READ_ONLY_FILE_STATUS, d.view.statusMessage());
        assertEquals(StatusTone.ERROR, d.view.statusTone());

        append("2 + 2");
        assertEquals(NotebookPresenter.READ_ONLY_FILE_STATUS, d.view.statusMessage(), "the warning is not replaced by an information");
        d.save();
        assertEquals(NotebookPresenter.READ_ONLY_FILE_STATUS, d.view.statusMessage(), "Save is refused with the reason");
        assertEquals(newer, Files.readString(file), "the file is what it was, byte for byte");

        Path copy = documents.resolve("copy.calc.json");
        dialogs.willSaveTo(copy);
        d.saveAs();
        assertEquals(List.of("1 + 1", "2 + 2"), savedTexts(copy), "Save as is allowed");
        assertTrue(d.view.canSave(), "and the copy is an ordinary document");
        assertEquals("copy.calc.json — Calc", d.view.title());
        assertEquals(newer, Files.readString(file));
        dialogs.assertAllUsed();
    }

    @Test
    void leavingAModifiedReadOnlyDocumentOffersSaveAsAndACancelledChooserKeepsTheWindow() throws Exception {
        Path file = write("future.calc.json", "{\"format\":\"calc-notebook\",\"version\":7,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\"}]}");
        dialogs.willOpen(file);
        d.open();
        append("2 + 2");

        dialogs.willAnswer(Unsaved.SAVE).willSaveTo(null);
        d.close();

        assertFalse(d.view.closeWindow());
        assertEquals(List.of("open", "unsaved future.calc.json QUIT", "save as"), dialogs.asked);
        dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ exports

    @ParameterizedTest(name = "{0}")
    @CsvSource({"AUTO,carnet-auto", "FRACTION,carnet-fraction", "SCIENTIFIC,carnet-scientifique", "DECIMAL,carnet-decimal"})
    void theExportsAreTheReferenceFilesOfTheCurrentDisplayMode(DisplayMode mode, String reference) throws Exception {
        Path file = writeNotebook("Devis cuisine.calc.json", NotebookExporterTest.REPRESENTATIVE.toArray(String[]::new));
        dialogs.willOpen(file);
        d.open();
        d.mode(mode);
        Path markdown = documents.resolve("sortie.md");
        Path csv = documents.resolve("sortie.csv");

        dialogs.willExportTo(markdown);
        d.export(ExportFormat.MARKDOWN);
        dialogs.willExportTo(csv);
        d.export(ExportFormat.CSV);

        assertEquals(resource(reference + ".md").replace("\r\n", "\n"),
                new String(Files.readAllBytes(markdown), StandardCharsets.UTF_8));
        assertArrayEquals(resourceBytes(reference + ".csv"), Files.readAllBytes(csv));
        assertEquals("Exporté vers " + csv, d.view.statusMessage());
        assertEquals(StatusTone.SUCCESS, d.view.statusTone());
        assertEquals(List.of("Devis cuisine.md", "Devis cuisine.csv"), dialogs.suggestedExportNames);
        dialogs.assertAllUsed();
    }

    @Test
    void anExportAddsItsExtensionAndTheUntitledNotebookProposesAGenericName() throws Exception {
        type("12,5 * 3");

        dialogs.willExportTo(documents.resolve("rapport"));
        d.export(ExportFormat.MARKDOWN);
        dialogs.willExportTo(documents.resolve("tableur.CSV"));
        d.export(ExportFormat.CSV);

        assertTrue(Files.readString(documents.resolve("rapport.md")).startsWith("# Carnet\n"));
        assertTrue(Files.exists(documents.resolve("tableur.CSV")));
        assertFalse(Files.exists(documents.resolve("tableur.CSV.csv")));
        assertEquals(List.of("carnet.md", "carnet.csv"), dialogs.suggestedExportNames);
        assertEquals("Carnet sans titre — Calc", d.view.title(), "an export does not change the document");
    }

    @Test
    void anExportAsksBeforeReplacingAFileAndKeepsItWhenRefused() throws Exception {
        type("1 + 1");
        Path target = write("sortie.md", "ancien contenu");

        dialogs.willExportTo(target).willConfirmOverwrite(false);
        d.export(ExportFormat.MARKDOWN);
        assertEquals("ancien contenu", Files.readString(target), "refused: untouched");

        dialogs.willExportTo(target).willConfirmOverwrite(true);
        d.export(ExportFormat.MARKDOWN);
        assertTrue(Files.readString(target).contains("| 1 | 1 + 1 | 2 |"));
        assertEquals(List.of("export MARKDOWN", "overwrite sortie.md", "export MARKDOWN", "overwrite sortie.md"), dialogs.asked);

        dialogs.willExportTo(documents.resolve("nouveau.md"));
        d.export(ExportFormat.MARKDOWN);
        assertEquals(5, dialogs.asked.size(), "a new file is not an overwrite");
        dialogs.assertAllUsed();
    }

    @Test
    void anExportCancelledInTheChooserWritesNothingAndSaysNothing() throws Exception {
        type("1 + 1");
        String before = d.view.statusMessage();

        dialogs.willExportTo(null);
        d.export(ExportFormat.CSV);

        assertEquals(before, d.view.statusMessage());
        try (var files = Files.list(documents)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void anExportNamedLikeAFileCalcKeepsNeverTouchesItAndAnUnwritableDestinationIsReported() throws Exception {
        type("1 + 1");
        byte[] draft = Files.readAllBytes(data.resolve(CURRENT));

        // The extension of the export is added to the name, so the current notebook is not what is written.
        dialogs.willExportTo(data.resolve(CURRENT));
        d.export(ExportFormat.CSV);
        assertTrue(Files.exists(data.resolve(CURRENT + ".csv")));
        assertArrayEquals(draft, Files.readAllBytes(data.resolve(CURRENT)));

        dialogs.willExportTo(documents.resolve("absent").resolve("sortie.csv"));
        d.export(ExportFormat.CSV);
        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertTrue(d.view.statusMessage().startsWith("Impossible d'exporter vers "), d.view.statusMessage());
        assertEquals(List.of("1 + 1"), lines(d), "the notebook is untouched");
    }

    @Test
    void anExportValidatesTheLineBeingTypedFirst() throws Exception {
        d.type("5 * 5");
        Path target = documents.resolve("typed.csv");

        dialogs.willExportTo(target);
        d.export(ExportFormat.CSV);

        assertTrue(Files.readString(target, StandardCharsets.UTF_8).contains("5 * 5;25;25;exact"), Files.readString(target));
    }

    // ------------------------------------------------------------------------------------------------ layering

    @Test
    void theDialogsInterfaceAndThePresenterUseNoSwing() throws IOException {
        for (String file : List.of("NotebookDialogs", "NotebookPresenter")) {
            String source = Files.readString(Path.of("src", "main", "java", "calc", "ui", "notebook", file + ".java"));
            assertFalse(source.contains("javax.swing") || source.contains("java.awt"), file);
        }
    }

    // ------------------------------------------------------------------------------------------------ resources

    private static String resource(String name) throws IOException {
        return new String(resourceBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] resourceBytes(String name) throws IOException {
        try (InputStream stream = NotebookFilesTest.class.getResourceAsStream("/export/" + name)) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        }
    }

}
