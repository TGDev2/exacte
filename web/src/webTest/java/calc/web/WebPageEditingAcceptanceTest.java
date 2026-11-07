package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.web.BrowserDriver.Json;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The page in a real headless browser, edited the way a visitor does: the example notebook of a first visit, the keyboard
 * shortcuts of the desktop, the buttons of a phone, the notebook kept in the browser and a storage that fails.
 *
 * <p>The driver page ({@code page-editing-driver.html}) sends real keyboard events to the focused field, clicks the buttons
 * by their text and reloads the iframe. The expected texts come from the core: the moves and the deletions were run through
 * {@code WebNotebook} (a reference to a deleted line reads {@code $?}), and the page adds only its labels.
 */
@DisplayName("La page : édition au clavier, renumérotation et carnet gardé dans le navigateur")
class WebPageEditingAcceptanceTest {
    private static final int DESKTOP = 1024;
    private static final int PHONE = 360;
    private static final int MINIMUM_TOUCH_SIZE = 44;

    private static final String KEY = "calc.web.notebook.v1";
    private static final String UNREADABLE = "Carnet enregistré illisible : carnet d'exemple chargé.";
    private static final String NOT_KEPT = "Carnet non conservé : le stockage du navigateur est indisponible.";
    private static final String MISSING_OPERAND = "Erreur : Opérande manquant après « + ».";
    private static final String DELETED_REFERENCE = "Erreur : Référence vers une ligne supprimée.";
    private static final String ROOT_RESULT = "≈ 1,41421356237…";
    private static final String ROOT_BOUND = "Erreur maximale : ± 2,1 × 10⁻²⁵";

    /** The example notebook of SPEC.md, as the page shows it on a first visit. */
    private static final List<String> EXAMPLE = List.of("# Carnet d'exemple : des résultats exacts", "(1/3)*3", "√2",
            "prix = 12,5", "prix * 3", "$2 + $5", "2 +");
    /** Ctrl+Shift+↓ on line 2: the square root goes up, and the 6th line follows the line it refers to. */
    private static final List<String> MOVED = List.of(EXAMPLE.get(0), "√2", "(1/3)*3", "prix = 12,5", "prix * 3", "$3 + $5", "2 +");
    private static final List<String> BELOW = List.of(EXAMPLE.get(0), "(1/3)*3", "", "√2", "prix = 12,5", "prix * 3", "$2 + $6", "2 +");
    private static final List<String> ABOVE = List.of(EXAMPLE.get(0), "", "(1/3)*3", "√2", "prix = 12,5", "prix * 3", "$3 + $6", "2 +");
    private static final List<String> WITHOUT_SECOND = List.of(EXAMPLE.get(0), "√2", "prix = 12,5", "prix * 3", "$? + $4", "2 +");
    private static final List<String> BUTTONS = List.of("Annuler", "Rétablir", "Insérer", "Monter", "Descendre", "Supprimer",
            "Effacer le carnet", "Carnet d'exemple");

    private static final Map<String, Observation> PLAYED = new HashMap<>();

    // ---- The first visit -----------------------------------------------------------------------------------------------------

    /** Without the key calc.web.notebook.v1 the page shows the 7 lines of the example notebook, and nothing to apologise for. */
    @Test
    void aFirstVisitShowsTheSevenLinesOfTheExampleNotebook() throws Exception {
        Observation page = firstVisit();

        assertExampleNotebook(page, "load");
        assertEquals(List.of(), page.messages("load"), "Une première visite n'a rien à signaler");
        assertEquals("AUTO", page.state("load").get("mode").text());
        assertEquals(List.of(), page.root().get("pageErrors").items());
    }

    @Test
    void theExampleNotebookGivesTheResultsOfThePromise() throws Exception {
        Observation page = firstVisit();

        Json one = page.row("load", 2);
        assertEquals("1", one.get("result").text());
        assertEquals("Exact", one.get("badge").text());
        Json root = page.row("load", 3);
        assertEquals(ROOT_RESULT, root.get("result").text());
        assertEquals("≈ Approché", root.get("badge").text());
        assertEquals(ROOT_BOUND, root.get("detail").text());
        assertEquals("12,5", page.row("load", 4).get("result").text());
        assertEquals("37,5", page.row("load", 5).get("result").text());
        Json sum = page.row("load", 6);
        assertEquals("38,5", sum.get("result").text());
        assertEquals("Exact", sum.get("badge").text());
        Json broken = page.row("load", 7);
        assertEquals(MISSING_OPERAND, broken.get("error").text());
        assertEquals("", broken.get("result").text());
        assertEquals(List.of("+"), strings(broken.get("marks")));
    }

    @Test
    void withNothingToUndoTheButtonsAreOffAtFirst() throws Exception {
        assertUndoRedo(firstVisit(), "load", true, true);
    }

    // ---- Moving a line, undo and redo -------------------------------------------------------------------------------------------

    @Test
    void ctrlShiftDownMovesTheLineAndRenumbersTheReferences() throws Exception {
        Observation page = moveWithKeys();

        assertEquals(MOVED, page.values("moved"));
        assertEquals("(1/3)*3", page.row("moved", 3).get("value").text());
        assertEquals("1", page.row("moved", 3).get("result").text());
        assertEquals("$3 + $5", page.row("moved", 6).get("value").text());
        assertEquals("38,5", page.row("moved", 6).get("result").text(), "Le résultat doit rester le même");
        assertEquals(MISSING_OPERAND, page.row("moved", 7).get("error").text());
        assertTrue(page.activeLine("moved") > 0, "Le focus doit rester dans une ligne après une commande");
    }

    @Test
    void ctrlZRestoresTheTextAndCtrlYAppliesTheMoveAgain() throws Exception {
        Observation page = moveWithKeys();

        assertEquals(EXAMPLE, page.values("undone"));
        assertEquals("$2 + $5", page.row("undone", 6).get("value").text());
        assertEquals("38,5", page.row("undone", 6).get("result").text());
        assertEquals(MOVED, page.values("redone"));
        assertEquals("38,5", page.row("redone", 6).get("result").text());
    }

    @Test
    void ctrlShiftZDoesWhatCtrlYDoes() throws Exception {
        Observation page = moveWithKeys();

        assertEquals(MOVED, page.values("redoneWithShift"));
        assertEquals("$3 + $5", page.row("redoneWithShift", 6).get("value").text());
        assertEquals("38,5", page.row("redoneWithShift", 6).get("result").text());
    }

    @Test
    void theMoveAndUndoKeysAreTakenFromTheBrowser() throws Exception {
        Observation page = moveWithKeys();

        assertAllPrevented(page, List.of("ctrl+shift+ArrowDown", "ctrl+z", "ctrl+y", "ctrl+z", "ctrl+shift+Z"));
    }

    @Test
    void theMoveButtonsGiveTheSameResultAsTheKeys() throws Exception {
        Observation page = moveWithButtons();

        assertEquals(MOVED, page.values("down"), "Descendre sur la ligne 2");
        assertEquals("$3 + $5", page.row("down", 6).get("value").text());
        assertEquals("38,5", page.row("down", 6).get("result").text());
        assertEquals(EXAMPLE, page.values("undone"), "Annuler");
        assertEquals(MOVED, page.values("up"), "Monter sur la ligne 3");
        assertEquals("38,5", page.row("up", 6).get("result").text());
        assertEquals(EXAMPLE, page.values("upUndone"), "Annuler après Monter");
        assertEquals(MOVED, page.values("upRedone"), "Rétablir");
    }

    // ---- The keys of a line ---------------------------------------------------------------------------------------------------------

    @Test
    void escapeBringsBackTheValidatedText() throws Exception {
        Observation page = editKeys();

        assertEquals("prix = 12,5", page.row("escaped", 4).get("value").text());
        assertEquals("12,5", page.row("escaped", 4).get("result").text());
        assertEquals(EXAMPLE, page.values("escaped"), "Rien n'était validé");
        assertEquals(4, page.activeLine("escaped"), "Échap laisse le focus dans la ligne");
    }

    @Test
    void enterValidatesTheLineAndMovesTheFocusToTheNextOne() throws Exception {
        Observation page = editKeys();

        assertEquals("prix = 20", page.row("entered", 4).get("value").text());
        assertEquals("20", page.row("entered", 4).get("result").text());
        assertEquals("60", page.row("entered", 5).get("result").text(), "Les lignes qui s'en servent suivent");
        assertEquals(5, page.activeLine("entered"));
        assertEquals(8, page.rows("entered").size(), "Valider une ligne du milieu n'ajoute rien");
    }

    @Test
    void enterOnTheLastLinesAddsALineAtTheEndOfTheNotebook() throws Exception {
        Observation page = editKeys();

        assertEquals(8, page.activeLine("lastEnter"), "Entrée sur la dernière ligne va à la ligne de brouillon");
        assertEquals(8, page.rows("lastEnter").size(), "Une ligne inchangée n'ajoute rien");
        Json added = page.row("appended", 8);
        assertEquals("7*6", added.get("value").text());
        assertEquals("42", added.get("result").text());
        assertEquals(9, page.rows("appended").size(), "Une ligne vide suit la ligne ajoutée");
        assertEquals("", page.row("appended", 9).get("value").text());
        assertEquals(9, page.activeLine("appended"));
    }

    @Test
    void theArrowsMoveTheFocusBetweenTheLines() throws Exception {
        Observation page = editKeys();

        assertEquals(4, page.activeLine("up"), "↑ depuis la ligne 5");
        assertEquals(5, page.activeLine("down"), "↓ depuis la ligne 4");
        assertEquals(1, page.activeLine("top"), "↑ sur la première ligne");
        assertEquals(8, page.activeLine("bottom"), "↓ sur la dernière ligne");
        assertEquals(page.values("down"), page.values("up"), "Les flèches ne changent aucun texte");
    }

    @Test
    void theKeysOfALineAreTakenFromTheBrowserWhenTheyDoSomething() throws Exception {
        Observation page = editKeys();

        List<Json> keys = page.root().get("keys").items();
        assertEquals(List.of("Escape", "Enter", "ArrowUp", "ArrowDown", "ArrowUp", "ArrowDown", "Enter"),
                keys.stream().map(key -> key.get("name").text()).toList(), "Les touches du scénario");
        for (int sent : List.of(0, 1, 2, 3, 6)) {
            assertTrue(keys.get(sent).get("prevented").bool(), "La page n'appelle pas preventDefault pour " + keys.get(sent).get("name").text());
        }
    }

    // ---- Inserting and deleting ----------------------------------------------------------------------------------------------------

    @Test
    void ctrlEnterInsertsALineBelowAndRenumbersTheReferences() throws Exception {
        Observation page = insertAndDelete();

        assertEquals(BELOW, page.values("below"));
        assertEquals("38,5", page.row("below", 7).get("result").text());
        assertEquals(3, page.activeLine("below"), "Le focus va à la ligne insérée");
        assertEquals(EXAMPLE, page.values("belowUndone"));
    }

    @Test
    void ctrlShiftEnterInsertsALineAbove() throws Exception {
        Observation page = insertAndDelete();

        assertEquals(ABOVE, page.values("above"));
        assertEquals("38,5", page.row("above", 7).get("result").text());
        assertEquals(2, page.activeLine("above"), "Le focus va à la ligne insérée");
    }

    @Test
    void ctrlShiftKDeletesTheLineAndTheReferencesToItBecomeQuestionMarks() throws Exception {
        Observation page = insertAndDelete();

        assertEquals(WITHOUT_SECOND, page.values("deleted"));
        Json broken = page.row("deleted", 5);
        assertEquals("$? + $4", broken.get("value").text());
        assertEquals(DELETED_REFERENCE, broken.get("error").text());
        assertEquals("37,5", page.row("deleted", 4).get("result").text(), "Une référence à une autre ligne est renumérotée");
        assertEquals("12,5", page.row("deleted", 3).get("result").text());
        assertTrue(page.activeLine("deleted") > 0, "Le focus doit rester dans une ligne après une suppression");
        assertEquals(EXAMPLE, page.values("deletedUndone"), "Ctrl+Z rend la ligne et ses références");
        assertEquals("38,5", page.row("deletedUndone", 6).get("result").text());
    }

    @Test
    void theInsertAndDeleteKeysAreTakenFromTheBrowser() throws Exception {
        Observation page = insertAndDelete();

        assertAllPrevented(page, List.of("ctrl+Enter", "ctrl+z", "ctrl+shift+Enter", "ctrl+z", "ctrl+shift+K", "ctrl+z"));
    }

    @Test
    void metaDoesWhatCtrlDoesForTheMenuShortcuts() throws Exception {
        Observation page = withMeta();

        assertEquals(BELOW, page.values("metaBelow"), "Cmd+Entrée");
        assertEquals(EXAMPLE, page.values("metaUndone"), "Cmd+Z");
        assertEquals(BELOW, page.values("metaRedone"), "Cmd+Y");
        assertEquals(BELOW, page.values("metaShiftRedone"), "Cmd+Maj+Z");
        assertEquals(ABOVE, page.values("metaAbove"), "Cmd+Maj+Entrée");
        assertEquals(WITHOUT_SECOND, page.values("metaDeleted"), "Cmd+Maj+K");
        assertAllPrevented(page, List.of("meta+Enter", "meta+z", "meta+y", "meta+z", "meta+shift+Z", "meta+z", "meta+shift+Enter",
                "meta+z", "meta+shift+K"));
    }

    // ---- The display modes -----------------------------------------------------------------------------------------------------------

    @Test
    void ctrlMGoesToTheNextDisplayModeAndKeepsTheFocusAndTheCaret() throws Exception {
        Observation page = modes();

        assertEquals("DECIMAL", page.state("decimal").get("mode").text());
        assertEquals("FRACTION", page.state("fraction").get("mode").text());
        assertEquals("SCIENTIFIC", page.state("scientific").get("mode").text());
        assertEquals("AUTO", page.state("auto").get("mode").text(), "Après Scientifique, on revient à Auto");
        assertEquals("75/2", page.row("fraction", 5).get("result").text());
        assertEquals("3,75 × 10¹", page.row("scientific", 5).get("result").text());
        assertEquals("37,5", page.row("auto", 5).get("result").text());
        assertAllPrevented(page, List.of("ctrl+m", "ctrl+m", "ctrl+m", "ctrl+m"));
        for (String state : List.of("decimal", "fraction", "scientific", "auto")) {
            assertEquals(3, page.activeLine(state), "Le focus quitte la ligne 3 après Ctrl+M (" + state + ")");
            assertEquals(1, page.state(state).get("active").get("start").number(), "Le curseur a bougé (" + state + ")");
            assertEquals(1, page.state(state).get("active").get("end").number(), "La sélection a changé (" + state + ")");
        }
    }

    @Test
    void aTextNotYetValidatedSurvivesAChangeOfMode() throws Exception {
        Observation page = modes();

        assertEquals("prix = 99", page.row("pending", 4).get("value").text());
        assertEquals("FRACTION", page.state("pending").get("mode").text());
        assertEquals("Fraction", page.state("pending").get("modeLabel").text());
    }

    @Test
    void theOtherKeysAreLeftToTheBrowserAndChangeNothing() throws Exception {
        Observation page = otherKeys();

        for (Json key : page.root().get("keys").items()) {
            assertFalse(key.get("prevented").bool(), "La page a pris " + key.get("name").text() + " au navigateur");
        }
        assertEquals(EXAMPLE, page.values("untouched"));
        assertEquals("38,5", page.row("untouched", 6).get("result").text());
    }

    // ---- A text not yet validated, undo and redo ---------------------------------------------------------------------------------------

    /** Like the desktop (NotebookPresenter.onUndo): undo takes the pending text out first, and only then a command. */
    @Test
    void undoTakesTheNotYetValidatedTextOutBeforeUndoingACommand() throws Exception {
        Observation page = withAPendingText();

        assertEquals("prix = 12,5", page.row("draftRemoved", 4).get("value").text());
        assertEquals("12,5", page.row("draftRemoved", 4).get("result").text());
        assertEquals(MOVED, page.values("draftRemoved"), "La saisie part, la commande reste");
        assertEquals(EXAMPLE, page.values("moveUndone"), "Le deuxième Ctrl+Z annule la commande");
    }

    /** Like the desktop (NotebookPresenter.onRedo): redo is refused while a text is not validated, so typing is never overwritten. */
    @Test
    void redoIsRefusedWhileATextIsNotYetValidated() throws Exception {
        Observation page = withAPendingText();

        assertEquals("prix = 8", page.row("redoRefused", 4).get("value").text(), "La saisie doit rester");
        List<String> notRedone = new ArrayList<>(EXAMPLE);
        notRedone.set(3, "prix = 8");
        assertEquals(notRedone, page.values("redoRefused"), "Rien n'a été rétabli");
        assertUndoRedo(page, "redoRefused", true, false);
        assertEquals(MOVED, page.values("redoDone"), "Une fois la saisie abandonnée, Ctrl+Y rétablit la commande");
        assertEquals("prix = 12,5", page.row("redoDone", 4).get("value").text());
    }

    // ---- The buttons ---------------------------------------------------------------------------------------------------------------------

    @Test
    void insertAndDeleteButtonsGiveTheSameCommandsAsTheShortcuts() throws Exception {
        Observation page = buttons();

        assertEquals(BELOW, page.values("inserted"), "Insérer sur la ligne 2 comme Ctrl+Entrée");
        assertEquals(WITHOUT_SECOND, page.values("deleted"), "Supprimer sur la ligne 2 comme Ctrl+Maj+K");
        assertEquals(DELETED_REFERENCE, page.row("deleted", 5).get("error").text());
    }

    @Test
    void undoAndRedoButtonsFollowTheHistory() throws Exception {
        Observation page = buttons();

        assertUndoRedo(page, "inserted", false, true);
        assertEquals(EXAMPLE, page.values("undone"));
        assertUndoRedo(page, "undone", true, false);
        assertEquals(BELOW, page.values("redone"));
        assertUndoRedo(page, "redone", false, true);
    }

    @Test
    void theClearButtonEmptiesTheNotebookAndUndoBringsItBack() throws Exception {
        Observation page = buttons();

        assertEquals(List.of(), page.values("cleared"));
        assertEquals(1, page.rows("cleared").size(), "Un carnet vide montre une ligne de saisie vide");
        assertEquals("", page.row("cleared", 1).get("value").text());
        assertUndoRedo(page, "cleared", false, true);
        assertEquals(EXAMPLE, page.values("clearUndone"));
        assertEquals("38,5", page.row("clearUndone", 6).get("result").text());
    }

    @Test
    void theExampleButtonLoadsTheExampleNotebook() throws Exception {
        Observation page = buttons();

        assertExampleNotebook(page, "example");
    }

    @Test
    void eachButtonHasATouchSizeAndStaysOnAPhoneScreen() throws Exception {
        Observation page = phone();

        assertExampleNotebook(page, "load");
        Json layout = page.state("load").get("layout");
        assertEquals(PHONE, layout.get("innerWidth").number(), "L'iframe n'a pas la largeur d'un téléphone");
        assertTrue(layout.get("scrollWidth").number() <= layout.get("clientWidth").number(),
                "Défilement horizontal à 360 px : scrollWidth " + layout.get("scrollWidth").number()
                        + ", clientWidth " + layout.get("clientWidth").number());
        for (String label : BUTTONS) {
            Json button = page.button("load", label);
            assertTrue(button.get("shown").bool(), "Le bouton " + label + " n'est pas visible à 360 px");
            assertTrue(button.get("width").number() >= MINIMUM_TOUCH_SIZE,
                    "Le bouton " + label + " fait " + button.get("width").number() + " px de large");
            assertTrue(button.get("height").number() >= MINIMUM_TOUCH_SIZE,
                    "Le bouton " + label + " fait " + button.get("height").number() + " px de haut");
            assertTrue(button.get("right").number() <= layout.get("clientWidth").number() + 0.5,
                    "Le bouton " + label + " sort de l'écran");
        }
    }

    // ---- Keeping the notebook ---------------------------------------------------------------------------------------------------------

    @Test
    void afterAnEditAReloadFindsTheLinesAndTheMode() throws Exception {
        Observation page = kept();

        List<String> expected = new ArrayList<>(EXAMPLE);
        expected.set(6, "2 + 1");
        expected.add("x = 1/3");
        assertEquals(expected, page.values("before"));
        assertEquals(expected, page.values("after"));
        assertEquals("FRACTION", page.state("after").get("mode").text());
        assertEquals("Fraction", page.state("after").get("modeLabel").text());
        assertEquals(List.of(), page.messages("after"), "Un carnet relu n'a rien à signaler");
        assertEquals(results(page, "before"), results(page, "after"), "Les résultats sont recalculés à l'identique");
        assertEquals("1/3", page.row("after", 8).get("result").text());
        assertEquals("3", page.row("after", 7).get("result").text());
        assertUndoRedo(page, "after", true, true);
    }

    @Test
    void theStoredValueHoldsTheVersionTheModeAndTheLinesOnly() throws Exception {
        Observation page = kept();

        String expected = "{\"version\":1,\"mode\":\"FRACTION\",\"lines\":[" + json(EXAMPLE.get(0)) + "," + json("(1/3)*3") + ","
                + json("√2") + "," + json("prix = 12,5") + "," + json("prix * 3") + "," + json("$2 + $5") + "," + json("2 + 1")
                + "," + json("x = 1/3") + "]}";
        assertEquals(expected, page.state("before").get("stored").text(), "Valeur de " + KEY + " avant le rechargement");
        assertEquals(expected, page.state("after").get("stored").text(), "Valeur de " + KEY + " après le rechargement");
    }

    @Test
    void anEmptyNotebookStaysEmptyAfterAReloadAndAcceptsALine() throws Exception {
        Observation page = emptied();

        assertEquals("{\"version\":1,\"mode\":\"AUTO\",\"lines\":[]}", page.state("cleared").get("stored").text());
        assertEquals(List.of(), page.values("reloaded"));
        assertEquals(1, page.rows("reloaded").size(), "Un carnet vide montre une ligne de saisie vide");
        assertEquals(List.of(), page.messages("reloaded"), "Un carnet vide n'est pas un carnet illisible");
        assertEquals(List.of("1 + 1"), page.values("typed"));
        assertEquals("2", page.row("typed", 1).get("result").text());
        assertEquals(2, page.rows("typed").size());
        assertEquals(List.of(), page.values("emptyScientific"));
        assertEquals("SCIENTIFIC", page.state("emptyScientific").get("mode").text());
    }

    // ---- An unreadable stored value ----------------------------------------------------------------------------------------------------

    @Test
    void everyUnreadableOrWrongValueGivesTheExampleNotebookAndTheMessage() throws Exception {
        Observation page = unreadable();

        for (String name : UNREADABLE_CASES.keySet()) {
            assertEquals(EXAMPLE, page.values(name), "Valeur « " + name + " » : le carnet d'exemple est attendu");
            assertTrue(page.messages(name).contains(UNREADABLE),
                    "Valeur « " + name + " » : le message est attendu, la page montre " + page.messages(name));
            assertEquals("AUTO", page.state(name).get("mode").text(), "Valeur « " + name + " »");
            assertEquals("38,5", page.row(name, 6).get("result").text(), "Valeur « " + name + " »");
        }
        assertEquals(List.of(), page.root().get("pageErrors").items());
        assertEquals(0.0, page.root().get("alerts").number());
    }

    @Test
    void aReadableValueIsLoadedAsItIsWithNewlinesTurnedIntoSpaces() throws Exception {
        Observation page = unreadable();

        assertEquals(List.of("1 + 1", "2 3"), page.values("readable"), "\\n et \\r d'une ligne lue deviennent des espaces");
        assertEquals("FRACTION", page.state("readable").get("mode").text());
        assertEquals("2", page.row("readable", 1).get("result").text());
        assertEquals(List.of(), page.messages("readable"));
    }

    // ---- A storage that fails ----------------------------------------------------------------------------------------------------------

    @Test
    void withMethodsOfTheStorageThatThrowThePageWorksAndSaysItKeepsNothing() throws Exception {
        assertWorksWithABrokenStorage(brokenStorage("methods"));
    }

    @Test
    void withALocalStoragePropertyThatThrowsThePageWorksAndSaysItKeepsNothing() throws Exception {
        assertWorksWithABrokenStorage(brokenStorage("property"));
    }

    private static void assertWorksWithABrokenStorage(Observation page) {
        assertExampleNotebook(page, "load");
        assertTrue(page.messages("load").contains(NOT_KEPT), "Au chargement : " + page.messages("load"));
        assertFalse(page.messages("load").contains(UNREADABLE), "Un stockage en panne n'est pas un carnet illisible");
        assertEquals("42", page.row("edited", 8).get("result").text());
        assertTrue(page.messages("edited").contains(NOT_KEPT), "Après une saisie : " + page.messages("edited"));
        assertEquals(MOVED.subList(0, 3), page.values("moved").subList(0, 3));
        assertEquals("$3 + $5", page.row("moved", 6).get("value").text());
        assertEquals("38,5", page.row("moved", 6).get("result").text());
        assertTrue(page.messages("moved").contains(NOT_KEPT), "Après un déplacement : " + page.messages("moved"));
        assertEquals(EXAMPLE, page.values("undone").subList(0, 7), "Annuler fonctionne");
        assertEquals(List.of(), page.root().get("pageErrors").items());
        assertEquals(0.0, page.root().get("alerts").number());
    }

    // ---- A refusal of the core ------------------------------------------------------------------------------------------------------------

    @Test
    void aRefusedLineShowsTheFrenchMessageAndTheNotebookStaysUsable() throws Exception {
        Observation page = refusal();

        List<String> messages = page.messages("refused");
        assertEquals(1, messages.size(), "Un seul message est attendu : " + messages);
        assertTrue(messages.get(0).contains("La ligne dépasse") && messages.get(0).contains("raccourcissez-la pour la valider"),
                messages.get(0));
        assertEquals(EXAMPLE, page.firstValues("refused", 7), "Le carnet ne doit pas changer");
        assertEquals("38,5", page.row("refused", 6).get("result").text());
        assertEquals(16385, page.row("refused", 8).get("length").number(), "Le texte refusé doit rester à corriger");

        assertEquals(List.of(), page.messages("recovered"), "Le message part quand la ligne est acceptée");
        assertEquals("3", page.row("recovered", 8).get("result").text());
        assertEquals(EXAMPLE, page.values("recovered").subList(0, 7));
    }

    @Test
    void aLineTooLongInTheMiddleIsRefusedToo() throws Exception {
        Observation page = refusalInTheMiddle();

        assertTrue(page.messages("refused").stream().anyMatch(message -> message.contains("La ligne dépasse")),
                "Messages : " + page.messages("refused"));
        // The refused text stays in its field to be corrected; what the core keeps shows in the lines that use line 4.
        assertEquals("1", page.row("afterBlur", 2).get("result").text());
        assertEquals("37,5", page.row("afterBlur", 5).get("result").text(), "La ligne 4 du carnet n'a pas changé");
        assertEquals("38,5", page.row("afterBlur", 6).get("result").text());
    }

    // ---- The scenarios -------------------------------------------------------------------------------------------------------------------------

    private static Observation firstVisit() throws Exception {
        return drive("firstVisit", DESKTOP, storeNothing(), load("normal"), snapshot("load"));
    }

    private static Observation moveWithKeys() throws Exception {
        return drive("moveWithKeys", DESKTOP, storeNothing(), load("normal"),
                focus(2), key("ArrowDown", "ctrl", "shift"), snapshot("moved"),
                key("z", "ctrl"), snapshot("undone"),
                key("y", "ctrl"), snapshot("redone"),
                key("z", "ctrl"), key("Z", "ctrl", "shift"), snapshot("redoneWithShift"));
    }

    private static Observation moveWithButtons() throws Exception {
        return drive("moveWithButtons", DESKTOP, storeNothing(), load("normal"),
                focus(2), click("Descendre"), snapshot("down"),
                click("Annuler"), snapshot("undone"),
                focus(3), click("Monter"), snapshot("up"),
                click("Annuler"), snapshot("upUndone"),
                click("Rétablir"), snapshot("upRedone"));
    }

    private static Observation editKeys() throws Exception {
        return drive("editKeys", DESKTOP, storeNothing(), load("normal"),
                focus(4), type("prix = 20"), key("Escape"), snapshot("escaped"),
                focus(4), type("prix = 20"), key("Enter"), snapshot("entered"),
                key("ArrowUp"), snapshot("up"),
                key("ArrowDown"), snapshot("down"),
                focus(1), key("ArrowUp"), snapshot("top"),
                focus(8), key("ArrowDown"), snapshot("bottom"),
                focus(7), key("Enter"), snapshot("lastEnter"),
                enter("7*6"), snapshot("appended"));
    }

    private static Observation insertAndDelete() throws Exception {
        return drive("insertAndDelete", DESKTOP, storeNothing(), load("normal"),
                focus(2), key("Enter", "ctrl"), snapshot("below"),
                key("z", "ctrl"), snapshot("belowUndone"),
                focus(2), key("Enter", "ctrl", "shift"), snapshot("above"),
                key("z", "ctrl"),
                focus(2), key("K", "ctrl", "shift"), snapshot("deleted"),
                key("z", "ctrl"), snapshot("deletedUndone"));
    }

    private static Observation withMeta() throws Exception {
        return drive("withMeta", DESKTOP, storeNothing(), load("normal"),
                focus(2), key("Enter", "meta"), snapshot("metaBelow"),
                key("z", "meta"), snapshot("metaUndone"),
                key("y", "meta"), snapshot("metaRedone"),
                key("z", "meta"), key("Z", "meta", "shift"), snapshot("metaShiftRedone"),
                key("z", "meta"),
                focus(2), key("Enter", "meta", "shift"), snapshot("metaAbove"),
                key("z", "meta"),
                focus(2), key("K", "meta", "shift"), snapshot("metaDeleted"));
    }

    private static Observation modes() throws Exception {
        return drive("modes", DESKTOP, storeNothing(), load("normal"),
                focus(3, 1, 1), key("m", "ctrl"), snapshot("decimal"),
                key("m", "ctrl"), snapshot("fraction"),
                key("m", "ctrl"), snapshot("scientific"),
                key("m", "ctrl"), snapshot("auto"),
                focus(4), type("prix = 99"), mode("Fraction"), snapshot("pending"));
    }

    private static Observation otherKeys() throws Exception {
        return drive("otherKeys", DESKTOP, storeNothing(), load("normal"),
                focus(3), key("c", "ctrl"), key("a", "ctrl"), key("x", "ctrl"), key("v", "ctrl"), key("k", "ctrl"),
                key("Tab"), key("Home"), snapshot("untouched"));
    }

    private static Observation withAPendingText() throws Exception {
        return drive("withAPendingText", DESKTOP, storeNothing(), load("normal"),
                focus(2), key("ArrowDown", "ctrl", "shift"),
                focus(4), type("prix = 7"), key("z", "ctrl"), snapshot("draftRemoved"),
                key("z", "ctrl"), snapshot("moveUndone"),
                focus(4), type("prix = 8"), key("y", "ctrl"), snapshot("redoRefused"),
                key("Escape"), key("y", "ctrl"), snapshot("redoDone"));
    }

    private static Observation buttons() throws Exception {
        return drive("buttons", DESKTOP, storeNothing(), load("normal"),
                focus(2), click("Insérer"), snapshot("inserted"),
                click("Annuler"), snapshot("undone"),
                click("Rétablir"), snapshot("redone"),
                click("Annuler"),
                click("Effacer le carnet"), snapshot("cleared"),
                click("Annuler"), snapshot("clearUndone"),
                click("Effacer le carnet"),
                click("Carnet d'exemple"), snapshot("example"),
                focus(2), click("Supprimer"), snapshot("deleted"));
    }

    private static Observation phone() throws Exception {
        return drive("phone", PHONE, storeNothing(), load("normal"), snapshot("load"));
    }

    private static Observation kept() throws Exception {
        return drive("kept", DESKTOP, storeNothing(), load("normal"),
                enterIn(7, "2 + 1"), mode("Fraction"), enter("x = 1/3"), snapshot("before"),
                reload(), snapshot("after"));
    }

    private static Observation emptied() throws Exception {
        return drive("emptied", DESKTOP, storeNothing(), load("normal"),
                click("Effacer le carnet"), snapshot("cleared"),
                reload(), snapshot("reloaded"),
                enter("1 + 1"), snapshot("typed"),
                storeJson("{\"version\":1,\"mode\":\"SCIENTIFIC\",\"lines\":[]}"), reload(), snapshot("emptyScientific"));
    }

    private static final Map<String, String> UNREADABLE_CASES = new LinkedHashMap<>();

    static {
        UNREADABLE_CASES.put("notJson", quoted("{not json"));
        UNREADABLE_CASES.put("jsonNull", quoted("null"));
        UNREADABLE_CASES.put("array", quoted("[]"));
        UNREADABLE_CASES.put("string", quoted("\"just text\""));
        UNREADABLE_CASES.put("number", quoted("42"));
        UNREADABLE_CASES.put("emptyText", quoted(""));
        UNREADABLE_CASES.put("version2", "{\"version\":2,\"mode\":\"AUTO\",\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("versionText", "{\"version\":\"1\",\"mode\":\"AUTO\",\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("noVersion", "{\"mode\":\"AUTO\",\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("unknownMode", "{\"version\":1,\"mode\":\"BOGUS\",\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("lowerMode", "{\"version\":1,\"mode\":\"auto\",\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("noMode", "{\"version\":1,\"lines\":[\"1\"]}");
        UNREADABLE_CASES.put("linesText", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":\"1\"}");
        UNREADABLE_CASES.put("linesNull", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":null}");
        UNREADABLE_CASES.put("noLines", "{\"version\":1,\"mode\":\"AUTO\"}");
        UNREADABLE_CASES.put("numberLine", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":[\"1\",2]}");
        UNREADABLE_CASES.put("nullLine", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":[\"1\",null]}");
        UNREADABLE_CASES.put("nestedLine", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":[[\"1\"]]}");
        UNREADABLE_CASES.put("tooManyLines", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":{\"$repeat\":[\"1\",5001]}}");
        UNREADABLE_CASES.put("tooLongLine", "{\"version\":1,\"mode\":\"AUTO\",\"lines\":[\"1\",{\"$string\":[\"1\",16385]}]}");
    }

    private static Observation unreadable() throws Exception {
        List<String> steps = new ArrayList<>(List.of(storeNothing()));
        boolean first = true;
        for (Map.Entry<String, String> value : UNREADABLE_CASES.entrySet()) {
            steps.add("{\"store\":" + value.getValue() + "}");
            steps.add(first ? load("normal") : reload());
            steps.add(snapshot(value.getKey()));
            first = false;
        }
        // Control cases, so that a page refusing everything does not pass: a few values the page has to accept.
        steps.add("{\"store\":{\"version\":1,\"mode\":\"FRACTION\",\"lines\":[\"1 +\\n1\",\"2\\r3\"]}}");
        steps.add(reload());
        steps.add(snapshot("readable"));
        return drive("unreadable", DESKTOP, steps.toArray(String[]::new));
    }

    private static Observation brokenStorage(String kind) throws Exception {
        return drive("broken-" + kind, DESKTOP, storeNothing(), load(kind), snapshot("load"),
                enter("7*6"), snapshot("edited"),
                focus(2), key("ArrowDown", "ctrl", "shift"), snapshot("moved"),
                key("z", "ctrl"), snapshot("undone"));
    }

    private static Observation refusal() throws Exception {
        return drive("refusal", DESKTOP, storeNothing(), load("normal"),
                enterTooLong(), snapshot("refused"),
                enter("3"), snapshot("recovered"));
    }

    private static Observation refusalInTheMiddle() throws Exception {
        return drive("refusalInTheMiddle", DESKTOP, storeNothing(), load("normal"),
                enterTooLongIn(4), snapshot("refused"),
                focus(1), snapshot("afterBlur"));
    }

    // ---- The driver ---------------------------------------------------------------------------------------------------------------------------

    private record Observation(Json root) {
        Json state(String snapshot) {
            return root.get("snapshots").get(snapshot);
        }

        List<Json> rows(String snapshot) {
            return state(snapshot).get("rows").items();
        }

        Json row(String snapshot, int number) {
            for (Json row : rows(snapshot)) {
                if ((int) row.get("line").number() == number) {
                    return row;
                }
            }
            return fail("Pas de ligne " + number + " dans " + state(snapshot).get("rows").value());
        }

        List<String> firstValues(String snapshot, int count) {
            return rows(snapshot).stream().limit(count).map(row -> row.get("value").text()).toList();
        }

        /** The texts of the lines of the notebook: the page always ends with a blank line that is not yet in it. */
        List<String> values(String snapshot) {
            List<Json> rows = rows(snapshot);
            assertFalse(rows.isEmpty(), "Aucune ligne dans " + snapshot);
            List<String> values = new ArrayList<>(rows.stream().map(row -> row.get("value").text()).toList());
            String last = values.remove(values.size() - 1);
            assertEquals("", last, "La page doit finir par une ligne de saisie vide (" + snapshot + ") : " + values);
            return values;
        }

        int activeLine(String snapshot) {
            Json active = state(snapshot).get("active");
            return active.isNull() ? -1 : (int) active.get("line").number();
        }

        List<String> messages(String snapshot) {
            return strings(state(snapshot).get("messages"));
        }

        Json button(String snapshot, String label) {
            List<Json> found = state(snapshot).get("buttons").items().stream().filter(button -> label.equals(button.get("text").text())).toList();
            assertEquals(1, found.size(), "Il faut un seul bouton « " + label + " » : "
                    + state(snapshot).get("buttons").items().stream().map(button -> button.get("text").text()).toList());
            return found.get(0);
        }
    }

    private static List<String> strings(Json list) {
        return list.items().stream().map(Json::text).toList();
    }

    private static List<String> results(Observation page, String snapshot) {
        return page.rows(snapshot).stream().map(row -> row.get("result").text()).toList();
    }

    private static void assertExampleNotebook(Observation page, String snapshot) {
        assertEquals(EXAMPLE, page.values(snapshot));
        assertEquals(8, page.rows(snapshot).size(), "Les 7 lignes du carnet d'exemple, puis la ligne de saisie vide");
        assertEquals("", page.row(snapshot, 1).get("result").text(), "Un commentaire n'a pas de résultat");
        assertEquals("1", page.row(snapshot, 2).get("result").text());
        assertEquals("Exact", page.row(snapshot, 2).get("badge").text());
        assertEquals(ROOT_RESULT, page.row(snapshot, 3).get("result").text());
        assertEquals(ROOT_BOUND, page.row(snapshot, 3).get("detail").text());
        assertEquals("38,5", page.row(snapshot, 6).get("result").text());
        assertEquals(MISSING_OPERAND, page.row(snapshot, 7).get("error").text());
    }

    private static void assertUndoRedo(Observation page, String snapshot, boolean undoDisabled, boolean redoDisabled) {
        assertEquals(undoDisabled, page.button(snapshot, "Annuler").get("disabled").bool(),
                "Annuler doit être " + (undoDisabled ? "désactivé" : "actif") + " (" + snapshot + ")");
        assertEquals(redoDisabled, page.button(snapshot, "Rétablir").get("disabled").bool(),
                "Rétablir doit être " + (redoDisabled ? "désactivé" : "actif") + " (" + snapshot + ")");
    }

    /** The keys the scenario sent, in order, and whether the page took each one from the browser. */
    private static void assertAllPrevented(Observation page, List<String> expectedKeys) {
        List<Json> keys = page.root().get("keys").items();
        assertEquals(expectedKeys, keys.stream().map(key -> key.get("name").text()).toList(), "Les touches du scénario");
        for (Json key : keys) {
            assertTrue(key.get("prevented").bool(), "La page n'appelle pas preventDefault pour " + key.get("name").text());
        }
    }

    // ---- Steps -----------------------------------------------------------------------------------------------------------------------------------

    private static String load(String kind) {
        return "{\"load\":" + quoted(kind) + "}";
    }

    private static String reload() {
        return "{\"reload\":true}";
    }

    private static String storeNothing() {
        return "{\"store\":null}";
    }

    /** An object the driver turns into the JSON text of the value, with its {"$repeat"} and {"$string"} expanded. */
    private static String storeJson(String json) {
        return "{\"store\":" + json + "}";
    }

    private static String focus(int number) {
        return "{\"focus\":" + number + "}";
    }

    private static String focus(int number, int start, int end) {
        return "{\"focus\":" + number + ",\"caret\":[" + start + "," + end + "]}";
    }

    private static String type(String text) {
        return "{\"type\":" + quoted(text) + "}";
    }

    private static String enter(String text) {
        return "{\"enter\":" + quoted(text) + "}";
    }

    private static String enterIn(int number, String text) {
        return "{\"enter\":" + quoted(text) + ",\"line\":" + number + "}";
    }

    /** A text of 16385 characters, built by the driver page: the address of the page does not carry it. */
    private static String enterTooLong() {
        return "{\"enter\":{\"$string\":[\"1\",16385]}}";
    }

    private static String enterTooLongIn(int number) {
        return "{\"enter\":{\"$string\":[\"1\",16385]},\"line\":" + number + "}";
    }

    private static String key(String key, String... modifiers) {
        StringBuilder step = new StringBuilder("{\"key\":").append(quoted(key));
        for (String modifier : modifiers) {
            step.append(",\"").append(modifier).append("\":true");
        }
        return step.append('}').toString();
    }

    private static String click(String label) {
        return "{\"click\":" + quoted(label) + "}";
    }

    private static String mode(String label) {
        return "{\"mode\":" + quoted(label) + "}";
    }

    private static String snapshot(String name) {
        return "{\"snapshot\":" + quoted(name) + "}";
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static String json(String text) {
        return quoted(text);
    }

    /** Plays a scenario once per run of the class, whatever the number of tests that read it. */
    private static Observation drive(String name, int width, String... steps) throws IOException, InterruptedException {
        Observation known = PLAYED.get(name);
        if (known != null) {
            return known;
        }
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (String step : steps) {
            joined.add(step);
        }
        String scenario = "{\"width\":" + width + ",\"steps\":" + joined + "}";
        Json root = BrowserDriver.parseJson(BrowserDriver.run("page-editing-driver.html", "scenario=" + URLEncoder.encode(scenario, UTF_8)));
        if (!root.get("error").isNull()) {
            fail("La page pilote s'est arrêtée dans le scénario " + name + " : " + root.get("error").text());
        }
        Observation played = new Observation(root);
        PLAYED.put(name, played);
        return played;
    }
}
