package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.NotebookGenerators;
import calc.ui.DisplayModeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Defect D10 of docs/RECETTE.md: Undo takes a pending draft out of the line and Redo puts it back, as a draft, with no command
 * of the model. Escape stays final, and the draft is forgotten as soon as anything else happens. The keyboard is played by
 * {@link NotebookDriver}; the model, the automatic save and the files are the real ones, on a temporary folder.
 */
@Tag("acceptance")
class UndoKeepsDraftAcceptanceTest {
    private static final String DRAFT_REMOVED = "Saisie annulée : Rétablir la remet dans la ligne.";
    private static final String DRAFT_RESTORED = "Saisie rétablie : elle n'est pas encore validée.";
    private static final String NOTHING_TO_REDO = "Il n'y a rien à rétablir.";
    private static final String COMMAND_REDONE = "Modification rétablie.";
    private static final String COMMAND_UNDONE = "Dernière modification annulée.";
    private static final String DRAFT_IN_THE_WAY = "avant de rétablir";

    @TempDir
    Path data;

    @TempDir
    Path documents;

    private NotebookStore store;
    private FakeDialogs dialogs;
    private NotebookDriver d;

    @BeforeEach
    void start() {
        store = NotebookStore.open(data);
        dialogs = new FakeDialogs();
        d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    // Lines 1 and 2 validated, the cursor back on line 2, 99 typed and not validated.
    private void draftOnTheSecondLine() {
        d.line("1").line("2");
        d.up();
        d.type("99");
    }

    // Undo takes the draft out, and says that Redo can put it back.
    private void undoRemovesTheDraft() {
        d.undo();
        assertEquals(DRAFT_REMOVED, d.view.statusMessage());
    }

    private List<String> savedTexts() throws Exception {
        return NotebookCodec.read(store.currentFile()).notebook().texts();
    }

    // ------------------------------------------------------------------------------------------------ taking a draft out

    @Test
    void undoTakesTheDraftOutAndOffersToPutItBack() throws Exception {
        draftOnTheSecondLine();

        d.undo();

        assertEquals("2", d.field());
        assertEquals(List.of("1", "2", ""), d.texts(), "no line of the model changes");
        assertEquals(List.of("1", "2"), savedTexts(), "nothing is written");
        assertTrue(d.view.canRedo(), "the Redo action of the window follows this value");
        assertEquals(DRAFT_REMOVED, d.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ putting it back

    @Test
    void redoPutsTheDraftBackWithoutValidatingIt() throws Exception {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.redo();

        assertEquals("99", d.field());
        assertEquals(2, d.active(), "the draft comes back in its line");
        assertEquals(List.of("1", "2", ""), d.texts(), "the draft is not validated");
        assertEquals(List.of("1", "2"), savedTexts(), "nothing is written");
        assertEquals(DRAFT_RESTORED, d.view.statusMessage());
    }

    @Test
    void enterThenValidatesTheDraftAsACommandThatCanBeUndone() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();
        d.redo();
        assertEquals("99", d.field());

        d.enter();
        assertEquals(List.of("1", "99", ""), d.texts());

        d.undo();
        assertEquals(List.of("1", "2", ""), d.texts());
        assertEquals(COMMAND_UNDONE, d.view.statusMessage());

        d.redo();
        assertEquals(List.of("1", "99", ""), d.texts());
        assertEquals(COMMAND_REDONE, d.view.statusMessage());
    }

    @Test
    void onANewBlankLineUndoEmptiesTheFieldAndRedoPutsTheDraftBack() {
        d.line("1");
        d.type("1 +");

        d.undo();
        assertEquals("", d.field());
        assertEquals(List.of("1", ""), d.texts());
        assertTrue(d.view.canRedo());
        assertEquals(DRAFT_REMOVED, d.view.statusMessage());

        d.redo();
        assertEquals("1 +", d.field());
        assertEquals(List.of("1", ""), d.texts());
        assertEquals(DRAFT_RESTORED, d.view.statusMessage());
    }

    @Test
    void inAnEmptyNotebookUndoEmptiesTheFieldAndRedoPutsTheDraftBack() {
        d.type("1 +");

        d.undo();
        assertEquals("", d.field());
        assertEquals(List.of(""), d.texts());
        assertTrue(d.view.canRedo());

        d.redo();
        assertEquals("1 +", d.field());
        assertEquals(List.of(""), d.texts());
        assertFalse(Files.exists(store.currentFile()), "nothing is written");
    }

    @Test
    void aDraftOverTheLimitOfALineComesBackWholeAndIsOnlyRefusedWhenValidated() {
        String tooLong = "1".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1);
        d.line("1");
        d.type(tooLong);
        undoRemovesTheDraft();
        assertEquals("", d.field());

        d.redo();
        assertEquals(tooLong, d.field());
        assertEquals(DRAFT_RESTORED, d.view.statusMessage());

        d.enter();
        assertEquals(tooLong, d.field(), "the refused line stays in the field");
        assertEquals(List.of("1", ""), d.texts());
        assertTrue(d.view.statusMessage().contains("La ligne dépasse"), d.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ once it is back

    @Test
    void theDraftPutBackIsPendingSoASecondRedoChangesNothing() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();
        d.redo();
        assertEquals("99", d.field());
        assertFalse(d.view.canRedo(), "the draft is back and no command can be redone");

        d.redo();

        assertEquals("99", d.field());
        assertEquals(List.of("1", "2", ""), d.texts());
        assertTrue(d.view.statusMessage().contains(DRAFT_IN_THE_WAY), d.view.statusMessage());
        assertFalse(d.view.canRedo(), "no command can be redone");
    }

    @Test
    void escapeDropsTheDraftPutBackForGood() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();
        d.redo();
        assertEquals("99", d.field());

        d.escape();
        assertEquals("2", d.field());

        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("2", d.field());
        assertFalse(d.view.canRedo());
    }

    // ------------------------------------------------------------------------------------------------ with a command to redo

    @Test
    void theDraftComesBackBeforeACommandThatCanAlsoBeRedoneAndTheCommandAfterEscape() {
        d.line("1").line("2");
        d.undo();
        assertEquals(List.of("1", ""), d.texts());
        assertTrue(d.view.canRedo(), "the command can be redone");

        d.type("5");
        undoRemovesTheDraft();
        assertEquals("", d.field());
        assertTrue(d.view.canRedo());

        d.redo();
        assertEquals("5", d.field(), "what was taken out last comes back first");
        assertEquals(List.of("1", ""), d.texts(), "the command is not redone and the draft not validated");
        assertEquals(DRAFT_RESTORED, d.view.statusMessage());
        assertTrue(d.view.canRedo(), "the command can still be redone");

        d.escape();
        assertEquals("", d.field());
        assertTrue(d.view.canRedo());

        d.redo();
        assertEquals(List.of("1", "2", ""), d.texts(), "this time the command is redone");
        assertEquals(COMMAND_REDONE, d.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ Escape stays final

    @Test
    void escapeIsFinalRedoHasNothingToPutBack() {
        draftOnTheSecondLine();

        d.escape();
        assertEquals("2", d.field());
        assertFalse(d.view.canRedo());

        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("2", d.field());
        assertEquals(List.of("1", "2", ""), d.texts());
    }

    // ------------------------------------------------------------------------------------------------ forgotten

    @Test
    void aNewDraftIsNeverOverwrittenAndTheOldOneIsForgotten() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();
        d.type("77");

        d.redo();
        assertEquals("77", d.field());
        assertTrue(d.view.statusMessage().contains(DRAFT_IN_THE_WAY), d.view.statusMessage());
        assertEquals(List.of("1", "2", ""), d.texts());
        assertFalse(d.view.canRedo(), "the new draft replaced the one taken out");

        d.escape();
        assertEquals("2", d.field());
        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage(), "99 does not come back after 77 was abandoned");
        assertEquals("2", d.field());
    }

    @Test
    void movingToAnotherLineForgetsTheDraftEvenWhenComingBack() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.up();
        assertEquals(1, d.active());
        assertFalse(d.view.canRedo());
        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("1", d.field());
        assertEquals(List.of("1", "2", ""), d.texts(), "no line receives the draft");

        d.down();
        assertEquals(2, d.active());
        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage(), "the draft is forgotten, not only out of place");
        assertEquals("2", d.field());
        assertEquals(List.of("1", "2", ""), d.texts());
    }

    @Test
    void clickingAnotherLineForgetsTheDraft() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.click(0);
        d.redo();

        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("1", d.field());
        assertEquals(List.of("1", "2", ""), d.texts());
    }

    @Test
    void aSecondUndoRevertsTheLastCommandForgetsTheDraftAndRedoRedoesTheCommand() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.undo();
        assertEquals(List.of("1", ""), d.texts(), "the second undo reverts the last validated command");
        assertEquals(COMMAND_UNDONE, d.view.statusMessage());

        d.redo();
        assertEquals(List.of("1", "2", ""), d.texts(), "the command is redone");
        assertEquals(COMMAND_REDONE, d.view.statusMessage());
        assertEquals(d.row(d.active()).text(), d.field(), "the draft does not come back");

        d.click(1);
        assertEquals("2", d.field());
        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("2", d.field());
    }

    @Test
    void validatingAnotherCommandForgetsTheDraft() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.type("3").enter();
        assertEquals(List.of("1", "3", ""), d.texts());
        assertFalse(d.view.canRedo());

        d.redo();

        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals(List.of("1", "3", ""), d.texts());
        assertEquals("", d.field(), "Enter moved to the next line, which stays blank");
    }

    @Test
    void aCommandThatLeavesTheLineWhereItWasStillForgetsTheDraft() {
        draftOnTheSecondLine();
        undoRemovesTheDraft();

        d.moveLineUp();
        d.moveLineDown();
        assertEquals(2, d.active(), "same place");
        assertEquals("2", d.field(), "same validated text");
        assertFalse(d.view.canRedo());

        d.redo();

        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("2", d.field());
        assertEquals(List.of("1", "2", ""), d.texts());
    }

    // The new notebook and the opened file both show a blank first line, the very place the draft was taken from.
    @Test
    void aNewDocumentForgetsTheDraft() {
        d.type("1 +");
        undoRemovesTheDraft();

        d.newDocument();
        assertFalse(d.view.canRedo());

        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("", d.field());
    }

    @Test
    void anOpenedDocumentForgetsTheDraft() throws Exception {
        Path empty = documents.resolve("vide.calc.json");
        Files.write(empty, NotebookCodec.encode(NotebookGenerators.notebook(List.of()), DisplayMode.AUTO).bytes());
        d.type("1 +");
        undoRemovesTheDraft();

        dialogs.willOpen(empty);
        d.open();
        assertFalse(d.view.canRedo());

        d.redo();
        assertEquals(NOTHING_TO_REDO, d.view.statusMessage());
        assertEquals("", d.field());
    }
}
