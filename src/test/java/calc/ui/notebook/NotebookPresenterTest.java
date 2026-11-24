package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookRow.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The logic of the notebook window with no Swing: the keyboard of the user is played by {@link NotebookDriver}, the model,
 * the automatic save and the migration are the real ones, on a temporary data directory.
 */
class NotebookPresenterTest {
    @TempDir
    Path data;

    private NotebookStore store;
    private final RecordingModeStore modes = new RecordingModeStore();
    private NotebookDriver d;

    @BeforeEach
    void start() {
        store = NotebookStore.open(data);
        d = new NotebookDriver(NotebookPresenter.open(store, modes));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    // ------------------------------------------------------------------------------------------------ the preview

    @Test
    void typingShowsThePreviewOfTheActiveLineOnlyAndValidatesNothing() {
        d.line("a = 2").line("a * 10").up();
        d.typing("a * 7");

        assertEquals("14", d.row(2).preview(), "the result the typed text would have");
        assertEquals("20", d.row(2).result(), "the validated result stays the result");
        assertEquals("", d.row(1).preview(), "only the active line has a preview");
        assertEquals("", d.row(3).preview());
        assertEquals(List.of("a = 2", "a * 10"), savedTexts(), "nothing is saved");

        d.typing("a * 10");
        assertEquals("", d.row(2).preview(), "the validated text has no preview: its result is shown");
    }

    @Test
    void aBlankTextACommentOrATextInErrorHasNoPreview() {
        d.line("a = 2");
        for (String text : List.of("   ", "# titre", "2 +", "b * 2")) {
            d.typing(text);
            assertEquals("", d.row(2).preview(), "« " + text + " »");
            assertEquals("", d.row(2).errorMessage(), "« " + text + " » is not in error until it is validated");
            assertEquals(StatusTone.INFO, d.view.statusTone(), "« " + text + " »");
        }
        d.typing("a + 1");
        assertEquals("3", d.row(2).preview());
    }

    @Test
    void theDisplayModeInForceFormatsThePreviewAndEnterGivesTheSameText() {
        d.line("a = 1").mode(DisplayMode.DECIMAL);
        d.typing("a / 3");
        String decimal = d.row(2).preview();
        assertFalse(decimal.isEmpty());

        d.mode(DisplayMode.FRACTION);
        assertEquals("a / 3", d.field(), "a change of mode keeps the text typed");
        String fraction = d.row(2).preview();
        assertFalse(fraction.equals(decimal), "the preview follows the mode: " + decimal + " / " + fraction);
        d.enter();
        assertEquals(fraction, d.row(2).result(), "Enter gives the result the preview showed");
        assertEquals("", d.row(2).preview());
    }

    @Test
    void undoTakesThePreviewOutWithTheDraftAndRedoBringsBothBack() {
        d.line("a = 2").line("a * 10").up().up();
        d.typing("a = 3");
        assertEquals("3", d.row(1).preview());

        d.undo();
        assertEquals("a = 2", d.field());
        assertEquals("", d.row(1).preview());
        assertFalse(d.row(2).hasPreview());

        d.redo();
        assertEquals("a = 3", d.field(), "the draft comes back, still a draft");
        assertEquals("3", d.row(1).preview());
        assertEquals("20", d.row(2).result(), "the line below keeps the validated value");
    }

    @Test
    void anIntentionThatValidatesTheDraftReplacesThePreviewByTheResult() {
        d.line("a = 2").line("a * 10").up();
        d.typing("a * 4");
        assertEquals("8", d.row(2).preview());

        d.up();
        assertEquals(List.of("2", "8", ""), d.results(), "moving away validates the line");
        assertEquals(List.of("", "", ""), d.view.rows().stream().map(NotebookRow::preview).toList());
        assertEquals(List.of("a = 2", "a * 4"), savedTexts());
    }

    @Test
    void theActiveLineCarriesItsDetailAndMarksItsSources() {
        d.line("a = 2").line("b = 3").line("a * b + a").line("1 / 3");
        d.click(2);
        assertEquals(List.of(true, true, false, false, false), d.view.rows().stream().map(NotebookRow::source).toList());
        assertEquals(List.of("= 2 * 3 + 2", "= 8", "Sources : lignes 1 et 2", "Utilisée par aucune ligne"), detail(d.view));

        d.click(0);
        assertEquals(List.of(false, false, false, false, false), d.view.rows().stream().map(NotebookRow::source).toList());
        assertEquals(List.of("= 2", "Utilisée par 1 ligne"), detail(d.view));

        // The result of the detail follows the display mode.
        d.click(3).mode(DisplayMode.FRACTION);
        assertEquals(List.of("= 1/3", "Utilisée par aucune ligne"), detail(d.view));

        // The new line at the end has no detail.
        d.click(4);
        assertEquals(List.of(), d.view.activeDetail());
    }

    private static List<String> detail(NotebookViewModel view) {
        return view.activeDetail().stream().map(calc.detail.LineDetailText.Sentence::text).toList();
    }

    /** A mode store that remembers in memory what was saved. */
    private static final class RecordingModeStore implements DisplayModeStore {
        DisplayMode stored = DisplayMode.AUTO;
        final List<DisplayMode> saves = new ArrayList<>();

        @Override
        public DisplayMode load() {
            return stored;
        }

        @Override
        public void save(DisplayMode mode) {
            stored = mode;
            saves.add(mode);
        }
    }

    // ------------------------------------------------------------------------------------------------ the start

    @Test
    void anEmptyNotebookShowsOneBlankRowAndTheWatermarkWithThreeExamplesThatAreNotSaved() {
        assertEquals(1, d.view.rows().size());
        assertEquals(0, d.view.activeIndex());
        assertEquals(NotebookFocus.ACTIVE_LINE, d.view.focus());
        assertFalse(d.view.watermark().isEmpty());
        for (String example : NotebookPresenter.EXAMPLES) {
            assertTrue(d.view.watermark().contains(example), example);
        }
        assertEquals(List.of("prix = 12,5", "prix * 3", "ans / 2"), NotebookPresenter.EXAMPLES);
        assertFalse(d.view.canUndo());
        assertFalse(d.view.canRedo());
        assertEquals(NotebookPresenter.DEFAULT_STATUS, d.view.statusMessage());
        assertFalse(Files.exists(store.currentFile()), "showing the help writes nothing");

        d.line("1 + 1");
        assertEquals("", d.view.watermark(), "the help goes away as soon as there is a line");
        d.close();
        assertEquals(List.of("1 + 1"), savedTexts());
    }

    // ------------------------------------------------------------------------------------------------ evaluation

    @Test
    void threeLinesGiveTwoSixAndSevenAndEditingTheFirstRecalculatesTheOthers() {
        d.line("a = 2").line("a * 3").line("ans + 1");

        assertEquals(List.of("2", "6", "7", ""), d.results());
        assertEquals(List.of(Status.EXACT, Status.EXACT, Status.EXACT, Status.EMPTY),
                d.view.rows().stream().map(NotebookRow::status).toList());
        assertEquals("a", d.row(1).definedName());
        assertEquals("", d.row(2).definedName());
        assertEquals(4, d.active(), "Enter after the last line goes to the new line");

        d.up().up().up();
        assertEquals(1, d.active());
        d.type("a = 10").enter();

        assertEquals(List.of("10", "30", "31", ""), d.results(), "the lines below are recalculated");
        assertEquals(2, d.active());
        assertEquals(List.of("a = 10", "a * 3", "ans + 1", ""), d.texts());
    }

    @Test
    void aResultIsFormattedInTheDisplayModeWithItsExactnessAndAccessibleDescription() {
        d.line("12,5 * 3").line("√2").line("1 / 3");

        NotebookRow exact = d.row(1);
        assertEquals("37,5", exact.result());
        assertEquals(Status.EXACT, exact.status());
        assertEquals("Résultat : 37,5, exact", exact.description());
        assertEquals("Ligne 1", exact.name());

        NotebookRow root = d.row(2);
        assertEquals(Status.APPROXIMATE, root.status());
        assertTrue(root.result().startsWith("≈ 1,41421356237"), root.result());
        assertTrue(root.detail().startsWith("Erreur maximale : "), root.detail());
        assertEquals("Résultat : " + root.result() + ", approché", root.description());

        NotebookRow third = d.row(3);
        assertEquals("0,333333333333…", third.result());
        assertEquals("Valeur exacte : 1/3 (affichage arrondi)", third.detail());
    }

    @Test
    void aLineInErrorHasItsMessageAsTextAndTheFaultyPart() {
        d.line("1 / 0").line("2 +").line("x = 2 +").line("x * 2");

        NotebookRow division = d.row(1);
        assertEquals(Status.ERROR, division.status());
        assertFalse(division.errorMessage().isBlank());
        assertEquals("", division.result());
        assertEquals("Erreur : " + division.errorMessage(), division.description());
        assertTrue(division.errorStart() >= 0 && division.errorEnd() > division.errorStart()
                && division.errorEnd() <= "1 / 0".length(), division.errorStart() + ".." + division.errorEnd());

        assertEquals(Status.ERROR, d.row(2).status());
        assertEquals("x", d.row(3).definedName(), "an assignment in error still defines its name");
        assertEquals(Status.ERROR, d.row(4).status());
        assertTrue(d.row(4).errorMessage().contains("ligne 3"), d.row(4).errorMessage());
    }

    @Test
    void emptyLinesAndCommentsHaveNoResult() {
        d.line("# Devis").insertAbove().down().line("2 # deux");

        assertEquals(Status.COMMENT, d.row(1).status());
        assertEquals("Commentaire", d.row(1).description());
        assertEquals(Status.EMPTY, d.row(2).status());
        assertEquals("Ligne vide", d.row(2).description());
        assertEquals("2", d.row(3).result());
    }

    // ------------------------------------------------------------------------------------------------ keyboard

    @Test
    void enterOnTheNewLineWithNothingTypedStaysAndNothingIsCreated() {
        d.line("1 + 1");
        assertEquals(2, d.active());
        d.enter().enter();

        assertEquals(2, d.active());
        assertEquals(2, d.view.rows().size(), "one line and the new line");
        assertEquals(List.of("1 + 1"), savedTexts());
    }

    @Test
    void arrowsMoveBetweenLinesValidateTheLineLeftAndStopAtTheEnds() {
        d.line("1").line("2");
        assertEquals(3, d.active());
        d.down();
        assertEquals(3, d.active(), "no row below the new line");
        d.up();
        assertEquals(2, d.active());
        d.type("20").down();
        assertEquals(3, d.active());
        assertEquals("20", d.row(2).text(), "moving away validates the line");
        d.up().up().up();
        assertEquals(1, d.active(), "stops on the first line");
    }

    @Test
    void escapeDropsTheUnvalidatedEditAndKeepsTheValidatedText() {
        d.line("5 * 5").up();
        d.type("5 * 6");
        assertEquals("5 * 5", d.row(1).text());

        d.escape();

        assertEquals("5 * 5", d.field());
        assertEquals("25", d.row(1).result());
        assertFalse(d.view.activeDraftPending());
    }

    @Test
    void escapeOnTheNewLineEmptiesWhatWasTyped() {
        d.type("12 +").escape();

        assertEquals("", d.field());
        assertEquals(1, d.view.rows().size());
        assertTrue(Files.notExists(store.currentFile()));
    }

    @Test
    void clickingAnotherRowValidatesTheLineLeft() {
        d.line("1").line("2").line("3");
        d.click(0);
        assertEquals(1, d.active());
        d.type("10").click(2);

        assertEquals(3, d.active());
        assertEquals("10", d.row(1).text());
        assertEquals(NotebookFocus.NONE, d.view.focus(), "the focus is already where the user clicked");
    }

    @Test
    void enterKeepsTheFocusRequestOnTheActiveLine() {
        d.line("1");
        assertEquals(NotebookFocus.ACTIVE_LINE, d.view.focus());
    }

    // ------------------------------------------------------------------------------------------------ undo and redo

    @Test
    void undoAndRedoActOnValidatedCommandsAndRecalculate() {
        d.line("a = 2").line("a * 3");
        assertEquals(List.of("2", "6", ""), d.results());

        d.undo();
        assertEquals(List.of("2", ""), d.results(), "the last validated command is undone");
        assertTrue(d.view.canRedo());
        d.undo();
        assertEquals(List.of(""), d.results());
        assertFalse(d.view.canUndo());

        d.redo().redo();
        assertEquals(List.of("2", "6", ""), d.results());
        assertFalse(d.view.canRedo());
        assertEquals(List.of("a = 2", "a * 3"), savedTexts(), "the saved notebook follows");
    }

    @Test
    void undoWithAPendingDraftDropsTheDraftFirstAndRedoRefusesToRun() {
        d.line("1").line("2");
        d.up();
        d.type("99");

        d.undo();
        assertEquals("2", d.field(), "the draft is dropped, like Escape");
        assertEquals(List.of("1", "2", ""), d.texts(), "nothing was undone yet");

        d.undo();
        assertEquals(List.of("1", ""), d.texts(), "the next undo reverts the last command");

        d.up().type("77").redo();
        assertEquals("77", d.field(), "the typing is never overwritten");
        assertTrue(d.view.statusMessage().contains("avant de rétablir"), d.view.statusMessage());
        assertEquals(List.of("1", ""), d.texts());
    }

    @Test
    void undoAndRedoWithNothingToDoSayIt() {
        d.undo();
        assertEquals("Il n'y a rien à annuler.", d.view.statusMessage());
        d.redo();
        assertEquals("Il n'y a rien à rétablir.", d.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ structure

    @Test
    void insertingAboveAReferencedLineRewritesTheReferenceAndKeepsTheValue() {
        d.line("6").line("$1 * 2");
        assertEquals(List.of("6", "12", ""), d.results());

        d.click(0).insertAbove();

        assertEquals(List.of("", "6", "$2 * 2", ""), d.texts(), "the reference now designates line 2");
        assertEquals(List.of("", "6", "12", ""), d.results(), "same value");
        assertEquals(1, d.active(), "the new line is active");
        assertEquals(Status.EMPTY, d.row(1).status());
    }

    @Test
    void insertingBelowPutsTheNewLineAfterTheActiveOne() {
        d.line("6").line("$1 * 2");
        d.click(0).insertBelow();

        assertEquals(List.of("6", "", "$1 * 2", ""), d.texts());
        assertEquals(2, d.active());
        assertEquals("12", d.row(3).result());
    }

    @Test
    void deletingAReferencedLineMakesTheReferenceAnExplicitError() {
        d.line("6").line("$1 * 2");

        d.click(0).delete();

        assertEquals(List.of("$? * 2", ""), d.texts());
        NotebookRow row = d.row(1);
        assertEquals(Status.ERROR, row.status());
        assertTrue(row.errorMessage().contains("supprimée"), row.errorMessage());
        assertEquals(1, d.active());
        assertTrue(d.view.statusMessage().contains("Ligne 1 supprimée"), d.view.statusMessage());

        d.undo();
        assertEquals(List.of("6", "$1 * 2", ""), d.texts(), "undo restores the line and the reference");
        assertEquals("12", d.row(2).result());
    }

    @Test
    void movingALineKeepsItsReferences() {
        d.line("2").line("$1 + 1").line("10");
        assertEquals(List.of("2", "3", "10", ""), d.results());

        d.click(2).moveLineUp();
        assertEquals(List.of("2", "10", "$1 + 1", ""), d.texts());
        assertEquals(2, d.active(), "the active line follows its text");

        d.moveLineUp();
        assertEquals(List.of("10", "2", "$2 + 1", ""), d.texts(), "the reference follows the line it designates");
        assertEquals(List.of("10", "2", "3", ""), d.results());
        assertEquals(1, d.active());

        d.moveLineUp();
        assertEquals("La ligne est déjà la première.", d.view.statusMessage());
        d.moveLineDown().moveLineDown();
        assertEquals(List.of("2", "$1 + 1", "10", ""), d.texts());
        d.moveLineDown();
        assertEquals("La ligne est déjà la dernière.", d.view.statusMessage());
    }

    @Test
    void clearEmptiesTheNotebookAndUndoRestoresIt() {
        d.line("1").line("2");

        d.clear();
        assertEquals(List.of(""), d.texts());
        assertFalse(d.view.watermark().isEmpty());
        assertEquals(List.of(), savedTexts());

        d.undo();
        assertEquals(List.of("1", "2", ""), d.texts());
    }

    @Test
    void deletingTheLastLineActivatesTheOneAbove() {
        d.line("1").line("2");
        d.click(1).delete();

        assertEquals(List.of("1", ""), d.texts());
        assertEquals(1, d.active());
    }

    @Test
    void movingOrDeletingTheNewLineIsRefusedWithAMessage() {
        d.line("1");
        d.delete();
        assertTrue(d.view.statusMessage().contains("pas encore validée"), d.view.statusMessage());
        d.moveLineUp();
        assertTrue(d.view.statusMessage().contains("pas encore validée"), d.view.statusMessage());
        assertEquals(List.of("1", ""), d.texts());
    }

    @Test
    void insertOnTheNewLineAddsABlankLineAtTheEnd() {
        d.line("1").insertAbove();

        assertEquals(List.of("1", "", ""), d.texts());
        assertEquals(2, d.active());
    }

    // ------------------------------------------------------------------------------------------------ mode

    @Test
    void changingTheModeReformatsTheResultsRemembersItAndSavesItWithTheNotebook() {
        d.line("1 / 3").line("0,25").line("2 ^ 40");
        assertEquals(List.of("0,333333333333…", "0,25", "1099511627776", ""), d.results());

        d.mode(DisplayMode.FRACTION);
        assertEquals(List.of("1/3", "1/4", "1099511627776", ""), d.results());
        assertEquals(DisplayMode.FRACTION, d.view.displayMode());
        assertEquals(List.of(DisplayMode.FRACTION), modes.saves);
        assertEquals(NotebookFocus.NONE, d.view.focus(), "the focus does not move");

        d.mode(DisplayMode.SCIENTIFIC);
        assertTrue(d.row(1).result().contains("×"), d.row(1).result());

        d.presenter.onNextDisplayMode(d.field());
        assertEquals(DisplayMode.AUTO, d.presenter.currentView().displayMode(), "Scientifique is followed by Auto");

        d.mode(DisplayMode.DECIMAL).line("1");
        assertEquals(DisplayMode.DECIMAL, readSaved().displayMode(), "the notebook file keeps the mode");
    }

    @Test
    void theRememberedModeIsUsedAtStartAndATypedDraftSurvivesAModeChange() {
        modes.stored = DisplayMode.FRACTION;
        NotebookDriver again = new NotebookDriver(NotebookPresenter.open(store, modes));
        assertEquals(DisplayMode.FRACTION, again.view.displayMode());

        again.type("1 +");
        again.mode(DisplayMode.AUTO);
        assertEquals("1 +", again.field(), "changing the mode does not touch what is being typed");
    }

    // ------------------------------------------------------------------------------------------------ bounds

    @Test
    void aLineLongerThanTheFileFormatIsRefusedAndKeptAsADraft() {
        String tooLong = "1".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1);
        d.type(tooLong).enter();

        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertTrue(d.view.statusMessage().contains("caractères"), d.view.statusMessage());
        assertEquals(tooLong, d.view.activeDraft(), "nothing typed is lost");
        assertEquals(1, d.view.rows().size());

        String longest = "1".repeat(NotebookPresenter.MAX_LINE_LENGTH);
        d.type(longest).enter();
        assertEquals(2, d.view.rows().size(), "the longest line the file reads is accepted");
        assertEquals(Status.ERROR, d.row(1).status(), "the language itself reports a line over 1000 characters");
    }

    @Test
    void aPastedTextOfSeveralLinesBecomesThatManyLinesInOneCommand() {
        d.line("10");
        d.type("1 +").paste(3, 3, " 2\n$1 * 2\r\nans + 1\n");

        assertEquals(List.of("10", "1 + 2", "$1 * 2", "ans + 1", ""), d.texts());
        assertEquals(List.of("10", "3", "20", "21", ""), d.results());
        assertEquals(4, d.active(), "the last pasted line is active");
        assertEquals("ans + 1", d.field());
        assertEquals("3 lignes collées. Annuler retire tout le collage.", d.view.statusMessage());
        assertEquals(StatusTone.INFO, d.view.statusTone());
        assertEquals(List.of("10", "1 + 2", "$1 * 2", "ans + 1"), savedTexts());

        d.undo();
        assertEquals(List.of("10", ""), d.texts());
        assertEquals(List.of("10"), savedTexts());
    }

    @Test
    void aPasteEndingWithItsOnlyLineBreakValidatesTheLine() {
        d.line("2 +");
        d.up();

        d.paste(3, 3, " 3\r\n");

        assertEquals(List.of("2 + 3", ""), d.texts());
        assertEquals("5", d.row(1).result());
        assertEquals(1, d.active());
        assertEquals("Ligne collée. Annuler retire le collage.", d.view.statusMessage());
        d.undo();
        assertEquals(List.of("2 +", ""), d.texts());
    }

    @Test
    void aPasteIntoAnotherRowValidatesTheLineLeftThenPastesThere() {
        d.line("1").line("2").type("5");

        d.pasteInto(0, 1, 1, " + 1\n3");

        assertEquals(List.of("1 + 1", "3", "2", "5", ""), d.texts(), "« 5 » typed in the line left is validated first");
        assertEquals(2, d.active());
    }

    @Test
    void aPasteIntoARowThatDoesNotExistChangesNothing() {
        d.line("1");

        d.pasteInto(5, 0, 0, "2\n3");
        d.pasteInto(-1, 0, 0, "2\n3");

        assertEquals(List.of("1", ""), d.texts());
        assertFalse(d.view.statusMessage().startsWith(NotebookPresenter.PASTE_REFUSED));
    }

    @Test
    void aLineBreakAloneOnTheNewLineAddsNothing() {
        d.line("1");

        d.paste(0, 0, "\n");

        assertEquals(List.of("1", ""), d.texts());
        assertEquals(List.of("1"), savedTexts());
        assertEquals(2, d.active());
    }

    @Test
    void aPastedTextWithoutLineBreakGoesIntoTheDraftWithoutBeingValidated() {
        d.line("1");
        d.type("a = b");

        d.paste(4, 5, "2 * 3");

        assertEquals("a = 2 * 3", d.view.activeDraft());
        assertEquals(List.of("1", ""), d.texts(), "nothing is validated");
        assertEquals(List.of("1"), savedTexts());
    }

    @Test
    void aSelectionOutsideTheTextIsAProgrammingError() {
        d.type("12");

        assertThrows(IndexOutOfBoundsException.class, () -> d.paste(1, 3, "a\nb"));
        assertThrows(IndexOutOfBoundsException.class, () -> d.paste(2, 1, "a\nb"));
        assertThrows(NullPointerException.class, () -> d.paste(0, 0, null));
    }

    @Test
    void aPasteRefusedForALongLineSaysWhichLineAndChangesNothing() {
        d.line("1");

        d.paste(0, 0, "2\n" + "3".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1));

        assertEquals(NotebookPresenter.PASTE_REFUSED + "la ligne 2 dépasserait "
                + NumberFormat.getIntegerInstance(Locale.FRANCE).format(NotebookPresenter.MAX_LINE_LENGTH)
                + " caractères. Le carnet n'a pas changé.", d.view.statusMessage());
        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertEquals(List.of("1", ""), d.texts());
    }

    @Test
    void aPasteRefusedForTooManyLinesSaysHowManyAndChangesNothing() {
        d.line("1");

        d.paste(0, 0, "2\n".repeat(Notebook.MAX_LINES) + "3");

        NumberFormat format = NumberFormat.getIntegerInstance(Locale.FRANCE);
        assertEquals(NotebookPresenter.PASTE_REFUSED + "le carnet aurait " + format.format(Notebook.MAX_LINES + 2)
                + " lignes, il ne peut pas dépasser " + format.format(Notebook.MAX_LINES) + ". Le carnet n'a pas changé.",
                d.view.statusMessage());
        assertEquals(StatusTone.ERROR, d.view.statusTone());
        assertEquals(List.of("1", ""), d.texts());
    }

    @Test
    void aLineBreakThatReachesTheDraftBecomesASpace() {
        d.type("1 +\n2\r\n+ 3").enter();

        assertEquals("1 + 2 + 3", d.row(1).text());
        assertEquals("6", d.row(1).result());
    }

    @Test
    void closingValidatesTheLineBeingTyped() {
        d.line("1").type("2 + 2").close();

        assertEquals(List.of("1", "2 + 2"), savedTexts());
    }

    // ------------------------------------------------------------------------------------------------ files and status

    @Test
    void theNotebookIsSavedAfterEveryValidatedCommandAndReloadedAtTheNextStart() {
        d.line("a = 2").line("a * 3");

        assertEquals(List.of("a = 2", "a * 3"), savedTexts());
        store.close();

        store = NotebookStore.open(data);
        NotebookDriver reopened = new NotebookDriver(NotebookPresenter.open(store, modes));
        assertEquals(List.of("2", "6", ""), reopened.results());
        assertEquals(0, reopened.view.activeIndex());
        assertEquals(NotebookPresenter.DEFAULT_STATUS, reopened.view.statusMessage());
    }

    @Test
    void aFailedAutomaticSaveIsReportedAndTheNotebookStaysInMemoryThenIsRepaired() throws IOException {
        store.close();
        Path locked = Files.createDirectories(data.resolve("other"));
        boolean[] failing = {true};
        store = NotebookStore.open(locked, null, Clock.systemDefaultZone(), Files::copy, (source, target, options) -> {
            if (failing[0]) {
                throw new IOException("simulated full disk");
            }
            Files.move(source, target, options);
        });
        NotebookDriver failed = new NotebookDriver(NotebookPresenter.open(store, modes));

        failed.line("2 + 2").line("$1 * 3");

        assertEquals(List.of("4", "12", ""), failed.results(), "the state in memory is complete");
        assertEquals(StatusTone.ERROR, failed.view.statusTone());
        assertTrue(failed.view.statusMessage().startsWith("Enregistrement automatique impossible"), failed.view.statusMessage());
        assertTrue(failed.view.statusMessage().contains("reste disponible"), failed.view.statusMessage());
        assertTrue(failed.view.canUndo(), "editing goes on");

        failing[0] = false;
        failed.line("5");
        assertEquals(NotebookPresenter.DEFAULT_STATUS, failed.view.statusMessage(), "the next successful save repairs it");
        assertEquals(List.of("2 + 2", "$1 * 3", "5"), savedTexts());
    }

    @Test
    void aSecondInstanceIsReadOnlyAndTheMessageStaysForTheWholeSession() throws IOException {
        d.line("7 * 6");
        Path elsewhere = Files.createDirectories(data.resolve("shared"));
        try (NotebookStore first = NotebookStore.open(elsewhere); NotebookStore second = NotebookStore.open(elsewhere)) {
            assertFalse(first.isReadOnly());
            assertTrue(second.isReadOnly());
            NotebookDriver readOnly = new NotebookDriver(NotebookPresenter.open(second, modes));
            assertEquals(StatusTone.ERROR, readOnly.view.statusTone());
            String message = readOnly.view.statusMessage();
            assertEquals("Calc est déjà ouvert : le carnet de cette fenêtre ne sera pas enregistré.", message);

            readOnly.line("1 + 1").line("2 + 2");

            assertEquals(List.of("2", "4", ""), readOnly.results(), "the window still calculates");
            assertEquals(message, readOnly.view.statusMessage(), "the warning does not go away");
            assertFalse(Files.exists(second.currentFile()), "nothing is written by the read only instance");
        }
    }

    @Test
    void theHistoryIsMigratedAtStartAndSaidSoThenTheNotebookIsAutosaved() throws Exception {
        store.close();
        Path legacy = Files.createDirectories(data.resolve("v1"));
        try (var stream = NotebookPresenterTest.class.getResourceAsStream("/legacy/v1/nominal.tsv")) {
            Files.write(legacy.resolve("history.tsv"), stream.readAllBytes());
        }
        store = NotebookStore.open(legacy);

        NotebookDriver migrated = new NotebookDriver(NotebookPresenter.open(store, modes));

        assertEquals("Votre historique a été converti en carnet (6 lignes).", migrated.view.statusMessage());
        assertEquals(StatusTone.SUCCESS, migrated.view.statusTone());
        assertEquals(List.of("0,1 + 0,2", "10 - 2,5", "1,5 * 4", "7 / 2", "2 ^ 10", "√(16)", ""), migrated.texts());
        assertEquals(List.of("0,3", "7,5", "6", "3,5", "1024", "4", ""), migrated.results());
        assertTrue(Files.exists(legacy.resolve("carnet-courant.calc.json")));

        migrated.click(5).type("√(25)").enter();
        assertEquals("5", migrated.row(6).result());
        assertEquals("√(25)", NotebookCodec.read(store.currentFile()).notebook().texts().get(5), "the first command is saved");
        assertFalse(migrated.view.statusMessage().contains("converti"), "the migration message gives way to the next intention");
    }

    // ------------------------------------------------------------------------------------------------ review findings

    @Test
    void aChangeOfModeAloneNeverWritesTheNotebookSoAHistoryToConvertIsNotLost() {
        d.mode(DisplayMode.DECIMAL).mode(DisplayMode.FRACTION);

        assertFalse(Files.exists(store.currentFile()), "no write: it would end the retry of an unconverted history");
        assertEquals(List.of(DisplayMode.DECIMAL, DisplayMode.FRACTION), modes.saves, "the choice is remembered anyway");
        d.line("1 / 3");
        assertEquals(DisplayMode.FRACTION, readSaved().displayMode(), "the mode follows at the next command");
    }

    @Test
    void theReadOnlyWarningIsNeverReplacedByAnInformation() throws IOException {
        Path elsewhere = Files.createDirectories(data.resolve("shared2"));
        try (NotebookStore first = NotebookStore.open(elsewhere); NotebookStore second = NotebookStore.open(elsewhere)) {
            assertFalse(first.isReadOnly());
            NotebookDriver readOnly = new NotebookDriver(NotebookPresenter.open(second, modes));
            String warning = readOnly.view.statusMessage();

            readOnly.type("1 +").escape();
            assertEquals(warning, readOnly.view.statusMessage());
            readOnly.undo().redo().mode(DisplayMode.DECIMAL).delete().clear();
            assertEquals(warning, readOnly.view.statusMessage());
            assertEquals(StatusTone.ERROR, readOnly.view.statusTone());
        }
    }

    @Test
    void deletingOrClearingNeverErasesWhatIsBeingTypedOnTheNewLine() {
        d.type("12 * 3").delete();
        assertEquals("12 * 3", d.view.activeDraft());
        d.clear();
        assertEquals("12 * 3", d.view.activeDraft());
        d.escape();
        assertEquals("", d.view.activeDraft(), "only Escape abandons the typing");
    }

    @Test
    void aRefusalIsShownEvenWhileTheAutomaticSaveIsFailing() throws IOException {
        store.close();
        store = NotebookStore.open(Files.createDirectories(data.resolve("failing2")), null, Clock.systemDefaultZone(),
                Files::copy, (source, target, options) -> {
                    throw new IOException("simulated full disk");
                });
        NotebookDriver failing = new NotebookDriver(NotebookPresenter.open(store, modes));
        failing.line("1");
        assertTrue(failing.view.statusMessage().startsWith("Enregistrement automatique impossible"));

        failing.type("1".repeat(NotebookPresenter.MAX_LINE_LENGTH + 1)).enter();

        assertTrue(failing.view.statusMessage().contains("caractères"), failing.view.statusMessage());
    }

    // ------------------------------------------------------------------------------------------------ the layering

    @Test
    void thePresenterAndItsViewModelUseNoSwing() throws IOException {
        for (String file : List.of("NotebookPresenter", "NotebookViewModel", "NotebookRow", "NotebookFocus")) {
            String source = Files.readString(Path.of("src", "main", "java", "calc", "ui", "notebook", file + ".java"));
            assertFalse(source.contains("javax.swing") || source.contains("java.awt"), file);
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private List<String> savedTexts() {
        if (Files.notExists(store.currentFile())) {
            return List.of();
        }
        return readSaved().notebook().texts();
    }

    private calc.persistence.DecodedNotebook readSaved() {
        try {
            return NotebookCodec.read(store.currentFile());
        } catch (IOException | calc.persistence.NotebookFormatException exception) {
            throw new AssertionError(exception);
        }
    }
}
