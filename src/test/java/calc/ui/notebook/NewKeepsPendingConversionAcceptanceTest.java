package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.document.DocumentService;
import calc.engine.Operation;
import calc.format.DisplayMode;
import calc.persistence.JsonHistoryStore;
import calc.persistence.LoadedNotebook;
import calc.persistence.LoadedNotebook.Condition;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.NotebookGenerators;
import calc.testing.RecordGenerators;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Defect D13 of docs/RECETTE.md: when the history could not be converted, the status promises a new conversion at the next
 * start « tant que vous n'aurez rien saisi ». File > New, a change of mode followed by New and the first save as of an empty
 * notebook must keep that promise: an empty notebook is never written while the conversion is waiting. A line the user
 * validated does end it, and that stays true once the line is gone. Without a conversion waiting, New still writes its empty
 * notebook. The store, the presenter and the document service are the real ones, the keyboard is played by
 * {@link NotebookDriver}, the dialogs by {@link FakeDialogs}; a restart closes the first store, whose lock would make the second
 * one read only.
 */
@Tag("acceptance")
class NewKeepsPendingConversionAcceptanceTest {
    private static final String CURRENT = "carnet-courant.calc.json";
    private static final String HISTORY = "history.json";
    private static final List<String> HISTORY_LINES = List.of("40 + 2", "7 / 2");
    private static final String TYPED = "1 + 1";
    private static final String OTHER_TYPED = "2 + 2";
    private static final String PROMISE = "tant que vous n'aurez rien saisi";
    private static final String AUTOSAVE_FAILED = "Enregistrement automatique impossible";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private Path current;
    private byte[] historyBytes;
    private final AtomicBoolean diskBroken = new AtomicBoolean();
    private AcceptanceSession session;
    private NotebookDriver d;

    @BeforeEach
    void folders() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        current = data.resolve(CURRENT);
    }

    @AfterEach
    void stop() {
        if (session != null) {
            session.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void givenAHistory() throws Exception {
        try (JsonHistoryStore history = JsonHistoryStore.open(data)) {
            history.saveHistory(List.of(RecordGenerators.calculated(Operation.ADDITION, "40", "2"),
                    RecordGenerators.calculated(Operation.DIVISION, "7", "2")));
        }
        historyBytes = Files.readAllBytes(data.resolve(HISTORY));
    }

    // A first start whose backup copy fails: the conversion waits for the next start. The disk works until the test breaks it.
    private void startWithAFailedConversion() throws Exception {
        givenAHistory();
        NotebookStore store = NotebookStore.open(data, null, Clock.systemDefaultZone(), (source, target) -> {
            throw new IOException("simulated full disk");
        }, (source, target, options) -> {
            if (diskBroken.get()) {
                throw new IOException("simulated disk failure");
            }
            Files.move(source, target, options);
        });
        LoadedNotebook loaded = start(store, Condition.HISTORY_NOT_CONVERTED);
        assertTrue(loaded.notebook().isEmpty());
        assertTrue(loaded.status().contains(PROMISE), loaded.status());
        assertFalse(Files.exists(current), "the failed conversion wrote no notebook");
    }

    private LoadedNotebook startNormally(Condition expected) {
        return start(NotebookStore.open(data), expected);
    }

    private LoadedNotebook start(NotebookStore store, Condition expected) {
        LoadedNotebook loaded = store.load();
        FakeDialogs dialogs = new FakeDialogs();
        session = AcceptanceSession.of(store, dialogs,
                new NotebookPresenter(new DocumentService(store, loaded), loaded, DisplayModeStore.noop(), dialogs));
        d = session.d;
        assertEquals(expected, loaded.condition());
        return loaded;
    }

    // Closes the running application, then what a new start of it finds, with a copier and a disk that work.
    private LoadedNotebook restart() {
        closeSession();
        try (NotebookStore store = NotebookStore.open(data)) {
            return store.load();
        }
    }

    private void closeSession() {
        session.dialogs.assertAllUsed();
        session.close();
        session = null;
    }

    private void assertNoNotebookYet() {
        assertFalse(Files.exists(current), "no notebook written: it would end the conversion");
    }

    private void assertHistoryUntouched() throws IOException {
        assertArrayEquals(historyBytes, Files.readAllBytes(data.resolve(HISTORY)), "the history stays identical byte for byte");
    }

    // The promise was kept: the next start converts the history, whose lines are now in the notebook.
    private void assertTheNextStartConverts() throws Exception {
        assertNoNotebookYet();
        assertHistoryUntouched();
        LoadedNotebook loaded = restart();
        assertEquals(Condition.MIGRATED, loaded.condition());
        assertEquals(HISTORY_LINES, loaded.notebook().texts());
        assertEquals(HISTORY_LINES, savedLines(current));
    }

    // The user typed something: the conversion is over, and the next start finds what the notebook file holds.
    private void assertTheNextStartKeeps(List<String> lines) throws Exception {
        assertEquals(lines, savedLines(current));
        assertHistoryUntouched();
        LoadedNotebook loaded = restart();
        assertEquals(Condition.NORMAL, loaded.condition());
        assertEquals(lines, loaded.notebook().texts());
        assertHistoryUntouched();
    }

    private static List<String> savedLines(Path file) throws Exception {
        return NotebookCodec.read(file).notebook().texts();
    }

    private Path aNotebookFile(String name, String... lines) throws Exception {
        Path file = documents.resolve(name);
        Files.write(file, NotebookCodec.encode(NotebookGenerators.notebook(List.of(lines)), DisplayMode.AUTO).bytes());
        return file;
    }

    // ------------------------------------------------------------------------------------------------ File > New

    @Test
    void newWithNothingTypedWritesNoNotebookAndChangesNoFile() throws Exception {
        startWithAFailedConversion();
        Map<String, String> before = AcceptanceSession.contentOf(data);

        d.newDocument();

        assertNoNotebookYet();
        assertHistoryUntouched();
        assertEquals(before, AcceptanceSession.contentOf(data), "no file created, removed or modified");
        assertEquals(List.of(), session.lines());
        assertEquals(List.of(), session.dialogs.asked, "nothing to lose, nothing to ask");
        assertEquals("Nouveau carnet.", d.view.statusMessage());
    }

    @Test
    void afterThatNewTheNextStartConvertsTheHistory() throws Exception {
        startWithAFailedConversion();

        d.newDocument();

        assertTheNextStartConverts();
    }

    @Test
    void newSeveralTimesInARowStillLeavesTheConversionWaiting() throws Exception {
        startWithAFailedConversion();

        d.newDocument().newDocument().newDocument();

        assertTheNextStartConverts();
    }

    @Test
    void theNextStartAfterNewShowsTheConvertedLinesInTheWindow() throws Exception {
        startWithAFailedConversion();
        d.newDocument();
        closeSession();

        startNormally(Condition.MIGRATED);

        assertEquals(HISTORY_LINES, session.lines());
        assertEquals(StatusTone.SUCCESS, d.view.statusTone());
    }

    // ------------------------------------------------------------------------------------------------ change of mode

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = DisplayMode.class, names = "AUTO", mode = EnumSource.Mode.EXCLUDE)
    void aChangeOfModeThenNewWritesNoNotebook(DisplayMode mode) throws Exception {
        startWithAFailedConversion();

        d.mode(mode).newDocument();

        assertTheNextStartConverts();
    }

    @Test
    void aChangeOfModeBetweenTwoNewWritesNoNotebook() throws Exception {
        startWithAFailedConversion();

        d.newDocument().mode(DisplayMode.FRACTION).newDocument().nextMode().newDocument();

        assertTheNextStartConverts();
    }

    // ------------------------------------------------------------------------------------------------ save as

    @Test
    void theSaveAsOfAnEmptyNotebookWritesTheFileAskedForAndNoNotebook() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("vide.calc.json");
        session.dialogs.willSaveTo(target);

        d.saveAs();

        assertTrue(Files.exists(target), "the file asked for is written");
        assertEquals(List.of(), savedLines(target));
        assertTheNextStartConverts();
    }

    @Test
    void theSaveAsOfAnEmptyNotebookAfterAChangeOfModeWritesNoNotebook() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("vide.calc.json");
        session.dialogs.willSaveTo(target);

        d.mode(DisplayMode.DECIMAL).saveAs();

        assertEquals(List.of(), savedLines(target));
        assertTheNextStartConverts();
    }

    @Test
    void theSaveAsThenNewStillLeavesTheConversionWaiting() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("vide.calc.json");
        session.dialogs.willSaveTo(target);

        d.saveAs().newDocument();

        assertTrue(Files.exists(target));
        assertTheNextStartConverts();
    }

    @Test
    void whatIsTypedInTheFileSavedAsDoesNotEndTheConversionWaiting() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("mien.calc.json");
        session.dialogs.willSaveTo(target);

        d.saveAs().line(TYPED).save().newDocument();

        assertEquals(List.of(TYPED), savedLines(target));
        assertTheNextStartConverts();
    }

    // ------------------------------------------------------------------------------------------------ opened documents

    @Test
    void openingAFileDoesNotEndTheConversionWaiting() throws Exception {
        startWithAFailedConversion();
        session.dialogs.willOpen(aNotebookFile("ouvert.calc.json", "3 + 3"));

        d.open().newDocument();

        assertTheNextStartConverts();
    }

    @Test
    void openingAFileDoesNotBringBackAConversionAlreadyEnded() throws Exception {
        startWithAFailedConversion();
        d.line(TYPED);
        session.dialogs.willOpen(aNotebookFile("ouvert.calc.json", "3 + 3")).willConfirmReplaceDraft(true);

        d.open().newDocument();

        assertTheNextStartKeeps(List.of());
    }

    // ------------------------------------------------------------------------------------------------ a line ends it

    @Test
    void aLineValidatedAfterTheFailureWritesTheNotebookAndEndsTheConversion() throws Exception {
        startWithAFailedConversion();

        d.line(TYPED);

        assertTheNextStartKeeps(List.of(TYPED));
    }

    @Test
    void aLineValidatedAfterNewWritesTheNotebookAndEndsTheConversion() throws Exception {
        startWithAFailedConversion();
        d.newDocument();
        assertNoNotebookYet();

        d.line(TYPED);

        assertTheNextStartKeeps(List.of(TYPED));
    }

    @Test
    void aLineValidatedThenClearedLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();

        d.line(TYPED).clear();

        assertTheNextStartKeeps(List.of());
    }

    @Test
    void aLineValidatedThenDeletedLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();

        d.line(TYPED).up().delete();

        assertEquals(List.of(), session.lines());
        assertTheNextStartKeeps(List.of());
    }

    @Test
    void aLineValidatedThenUndoneLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();

        d.line(TYPED).undo();

        assertEquals(List.of(), session.lines());
        assertTheNextStartKeeps(List.of());
    }

    @Test
    void aLineValidatedAfterNewThenClearedLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();
        d.newDocument();
        assertNoNotebookYet();

        d.line(TYPED).clear();

        assertTheNextStartKeeps(List.of());
    }

    @Test
    void aLineValidatedThenReplacedByNewLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();
        d.line(TYPED);
        session.dialogs.willAnswer(Unsaved.DISCARD);

        d.newDocument();

        assertTheNextStartKeeps(List.of());
    }

    @Test
    void aLineValidatedThenSavedAsStillEmptiesTheDraftAndLeavesTheConversionAbandoned() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("mien.calc.json");
        d.line(TYPED);
        session.dialogs.willSaveTo(target);

        d.saveAs();

        assertEquals(List.of(TYPED), savedLines(target));
        assertTheNextStartKeeps(List.of());
    }

    // ------------------------------------------------------------------------------------------------ nothing waiting

    @Test
    void withoutAHistoryNewWritesAnEmptyNotebook() throws Exception {
        startNormally(Condition.NORMAL);
        assertFalse(Files.exists(current));

        d.newDocument();

        assertTrue(Files.exists(current));
        assertEquals(List.of(), savedLines(current));
    }

    @Test
    void withANotebookNewWritesAnEmptyNotebook() throws Exception {
        startNormally(Condition.NORMAL);
        d.line(TYPED);
        closeSession();
        startNormally(Condition.NORMAL);
        assertEquals(List.of(TYPED), session.lines());
        session.dialogs.willAnswer(Unsaved.DISCARD);

        d.newDocument();

        assertEquals(List.of(), savedLines(current));
    }

    @Test
    void afterAConversionNewWritesAnEmptyNotebookAndTheHistoryIsNotConvertedAgain() throws Exception {
        givenAHistory();
        startNormally(Condition.MIGRATED);
        assertEquals(HISTORY_LINES, session.lines());
        session.dialogs.willAnswer(Unsaved.DISCARD);

        d.newDocument();

        assertTheNextStartKeeps(List.of());
    }

    @Test
    void theWaitOfAFailedStartDoesNotOutliveItsConversion() throws Exception {
        startWithAFailedConversion();
        d.newDocument();
        closeSession();
        startNormally(Condition.MIGRATED);
        session.dialogs.willAnswer(Unsaved.DISCARD);

        d.newDocument();

        assertTheNextStartKeeps(List.of());
    }

    @Test
    void withoutAHistoryTheSaveAsOfAnEmptyNotebookStillWritesTheEmptyDraft() throws Exception {
        startNormally(Condition.NORMAL);
        Path target = documents.resolve("vide.calc.json");
        session.dialogs.willSaveTo(target);

        d.saveAs();

        assertEquals(List.of(), savedLines(target));
        assertTrue(Files.exists(current));
        assertEquals(List.of(), savedLines(current));
    }

    // ------------------------------------------------------------------------------------------------ a failed write

    @Test
    void aWriteThatFailsIsToldAndALineValidatedOnceTheDiskIsBackWritesTheNotebook() throws Exception {
        startWithAFailedConversion();
        diskBroken.set(true);

        d.line(TYPED);

        assertTrue(d.view.statusMessage().contains(AUTOSAVE_FAILED), d.view.statusMessage());
        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertNoNotebookYet();

        diskBroken.set(false);
        d.line(OTHER_TYPED);

        assertFalse(d.view.statusMessage().contains(AUTOSAVE_FAILED), d.view.statusMessage());
        assertTheNextStartKeeps(List.of(TYPED, OTHER_TYPED));
    }

    @Test
    void aWriteThatFailsKeepsTheWaitWhenTheLineIsThenUndone() throws Exception {
        startWithAFailedConversion();
        diskBroken.set(true);
        d.line(TYPED);
        assertNoNotebookYet();
        diskBroken.set(false);

        d.undo();

        assertEquals(List.of(), session.lines());
        assertFalse(d.view.statusMessage().contains(AUTOSAVE_FAILED), "nothing is left to save: " + d.view.statusMessage());
        assertTheNextStartConverts();
    }

    @Test
    void aWriteThatFailsKeepsTheWaitWhenTheLineIsThenDiscardedByNew() throws Exception {
        startWithAFailedConversion();
        diskBroken.set(true);
        d.line(TYPED);
        assertNoNotebookYet();
        diskBroken.set(false);
        session.dialogs.willAnswer(Unsaved.DISCARD);

        d.newDocument();

        assertTheNextStartConverts();
    }

    @Test
    void aWriteThatFailsKeepsTheWaitWhenTheLineIsThenSavedAs() throws Exception {
        startWithAFailedConversion();
        Path target = documents.resolve("mien.calc.json");
        diskBroken.set(true);
        d.line(TYPED);
        assertNoNotebookYet();
        diskBroken.set(false);
        session.dialogs.willSaveTo(target);

        d.saveAs();

        assertEquals(List.of(TYPED), savedLines(target));
        assertTheNextStartConverts();
    }

    @Test
    void aWriteThatFailsThenALineValidatedAfterNewWritesTheNotebook() throws Exception {
        startWithAFailedConversion();
        diskBroken.set(true);
        d.line(TYPED);
        session.dialogs.willAnswer(Unsaved.DISCARD);
        d.newDocument();
        assertNoNotebookYet();
        diskBroken.set(false);

        d.line(OTHER_TYPED);

        assertTheNextStartKeeps(List.of(OTHER_TYPED));
    }
}
