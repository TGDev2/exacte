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
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A text typed and not yet validated, then a command: the command validates it first, and from then on the field shows the
 * text the notebook holds, so that leaving the field, Escape, Ctrl+Z or Ctrl+Y act on what the notebook holds. Annuler and
 * Rétablir treat a text not yet validated like Ctrl+Z and Ctrl+Y, although a tap moves the focus to them. Each group starts
 * from the example notebook of a first visit. Uses page-editing-driver.html as it is.
 */
@DisplayName("La page : une saisie non validée suivie d'une commande")
class WebPagePendingTextTest {
    private static final String COMMENT = "# Carnet d'exemple : des résultats exacts";
    private static final String REDO_REFUSED = "Validez la saisie en cours (Entrée) ou annulez-la (Échap) avant de rétablir.";

    private static Json page;

    @BeforeAll
    static void playTheScenario() throws Exception {
        List<String> steps = new ArrayList<>(List.of(storeNothing(), "{\"load\":\"normal\"}"));
        steps.addAll(List.of(focus(3), type("9"), key("K", "ctrl", "shift"), snapshot("deleted"), focus(1), snapshot("deletedLeft")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(2), type("5"), key("ArrowDown", "ctrl", "shift"), snapshot("moved"), focus(1), snapshot("movedLeft")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(2), type("5"), key("Enter", "ctrl", "shift"), snapshot("inserted"), focus(1), snapshot("insertedLeft")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(2), type("5"), key("Enter"), key("ArrowUp"), key("Escape"), snapshot("escaped"),
                focus(1), snapshot("escapedLeft")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(8), type("2+2"), key("Enter"), key("ArrowUp"), key("Escape"), key("ArrowUp"), snapshot("draftKept")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(2), type("5"), key("Enter"), key("ArrowUp"), key("z", "ctrl"), snapshot("undone"),
                key("y", "ctrl"), snapshot("redone")));
        steps.addAll(reset());
        steps.addAll(List.of(focus(2), type("5"), key("Enter"), focus(3), type("6"), key("Enter"), key("z", "ctrl"),
                focus(4), type("prix = 7"), "{\"click\":\"Rétablir\"}", snapshot("redoButtonRefused"),
                "{\"click\":\"Annuler\"}", snapshot("undoButtonTookTheText")));
        page = play(steps);
    }

    // ---- A command after a text not yet validated ----------------------------------------------------------------------

    @Test
    void deleteValidatesTheTextFirstAndLeavingTheFieldWritesNothingElse() {
        List<String> expected = List.of(COMMENT, "(1/3)*3", "prix = 12,5", "prix * 3", "$2 + $4", "2 +");
        assertEquals(expected, values("deleted"), "Le champ montre ce que le carnet contient");
        assertEquals(expected, stored("deleted"));
        assertEquals(expected, stored("deletedLeft"), "Quitter le champ ne doit écraser aucune ligne");
        assertEquals(expected, values("deletedLeft"));
    }

    @Test
    void moveValidatesTheTextFirstAndNoLineIsLost() {
        List<String> expected = List.of(COMMENT, "√2", "5", "prix = 12,5", "prix * 3", "$3 + $5", "2 +");
        assertEquals(expected, values("moved"));
        assertEquals(expected, stored("movedLeft"), "√2 doit rester, et 5 n'apparaître qu'une fois");
        assertEquals(expected, values("movedLeft"));
    }

    @Test
    void insertValidatesTheTextFirstAndTheNewLineStaysEmpty() {
        List<String> expected = List.of(COMMENT, "", "5", "√2", "prix = 12,5", "prix * 3", "$3 + $6", "2 +");
        assertEquals(expected, values("inserted"));
        assertEquals(expected, stored("insertedLeft"), "La ligne insérée ne doit pas recevoir le texte saisi");
        assertEquals(expected, values("insertedLeft"));
    }

    // ---- Coming back to a line validated by Enter ----------------------------------------------------------------------

    @Test
    void escapeOnALineValidatedByEnterKeepsTheValidatedText() {
        assertEquals("5", values("escaped").get(1), "Échap rétablit le texte validé, ici 5");
        assertEquals("5", stored("escapedLeft").get(1));
        assertEquals("5", values("escapedLeft").get(1));
    }

    @Test
    void aLineAppendedByEnterIsNotEmptiedByEscape() {
        assertEquals("2+2", values("draftKept").get(7));
        assertEquals(8, stored("draftKept").size(), "La ligne ajoutée doit rester dans le carnet");
        assertEquals("2+2", stored("draftKept").get(7));
    }

    @Test
    void undoAndRedoAfterEnterActOnTheNotebook() {
        assertEquals("(1/3)*3", values("undone").get(1), "Ctrl+Z annule la validation");
        assertEquals("(1/3)*3", stored("undone").get(1));
        assertEquals("5", values("redone").get(1), "Ctrl+Y la rétablit");
        assertEquals("5", stored("redone").get(1));
        assertEquals(List.of(), messages("redone"), "Rien n'était en attente");
    }

    // ---- The buttons with a text not yet validated -----------------------------------------------------------------------

    @Test
    void theRedoButtonIsRefusedWhileATextIsNotYetValidated() {
        assertEquals("prix = 7", values("redoButtonRefused").get(3), "La saisie doit rester");
        assertEquals(List.of(REDO_REFUSED), messages("redoButtonRefused"));
        List<String> kept = stored("redoButtonRefused");
        assertEquals("prix = 12,5", kept.get(3), "La saisie ne doit pas être validée");
        assertEquals("√2", kept.get(2), "Rien n'a été rétabli");
        assertEquals("5", kept.get(1));
        assertFalse(button("redoButtonRefused", "Rétablir").get("disabled").bool(), "Rétablir doit rester possible");
    }

    @Test
    void theUndoButtonTakesTheTextOutBeforeUndoingACommand() {
        assertEquals("prix = 12,5", values("undoButtonTookTheText").get(3));
        List<String> kept = stored("undoButtonTookTheText");
        assertEquals("prix = 12,5", kept.get(3));
        assertEquals("5", kept.get(1), "Aucune commande n'a été annulée");
        assertFalse(button("undoButtonTookTheText", "Rétablir").get("disabled").bool());
        assertTrue(page.get("error").isNull());
        assertEquals(List.of(), page.get("pageErrors").items());
    }

    // ---- Reading the observation ---------------------------------------------------------------------------------------

    private static Json state(String snapshot) {
        return page.get("snapshots").get(snapshot);
    }

    /** The texts of the fields, without the blank line that always ends the sheet. */
    private static List<String> values(String snapshot) {
        List<String> values = new ArrayList<>(state(snapshot).get("rows").items().stream().map(row -> row.get("value").text()).toList());
        assertEquals("", values.remove(values.size() - 1), "La page doit finir par une ligne de saisie vide (" + snapshot + ")");
        return values;
    }

    private static List<String> stored(String snapshot) {
        Json stored = state(snapshot).get("stored");
        assertFalse(stored.isNull(), "Rien dans le stockage (" + snapshot + ")");
        return BrowserDriver.parseJson(stored.text()).get("lines").items().stream().map(Json::text).toList();
    }

    private static List<String> messages(String snapshot) {
        return state(snapshot).get("messages").items().stream().map(Json::text).toList();
    }

    private static Json button(String snapshot, String label) {
        return state(snapshot).get("buttons").items().stream().filter(button -> label.equals(button.get("text").text()))
                .findFirst().orElseGet(() -> fail("Pas de bouton " + label));
    }

    // ---- Steps -------------------------------------------------------------------------------------------------------------

    private static String storeNothing() {
        return "{\"store\":null}";
    }

    /** Back to a first visit: no kept notebook, a reloaded page, an empty history. */
    private static List<String> reset() {
        return List.of(storeNothing(), "{\"reload\":true}");
    }

    private static String focus(int number) {
        return "{\"focus\":" + number + "}";
    }

    private static String type(String text) {
        return "{\"type\":\"" + text + "\"}";
    }

    private static String key(String key, String... modifiers) {
        StringBuilder step = new StringBuilder("{\"key\":\"").append(key).append('"');
        for (String modifier : modifiers) {
            step.append(",\"").append(modifier).append("\":true");
        }
        return step.append('}').toString();
    }

    private static String snapshot(String name) {
        return "{\"snapshot\":\"" + name + "\"}";
    }

    private static Json play(List<String> steps) throws IOException, InterruptedException {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        steps.forEach(joined::add);
        String scenario = "{\"width\":1024,\"steps\":" + joined + "}";
        Json root = BrowserDriver.parseJson(BrowserDriver.run("page-editing-driver.html", "scenario=" + URLEncoder.encode(scenario, UTF_8)));
        if (!root.get("error").isNull()) {
            fail("La page pilote s'est arrêtée : " + root.get("error").text());
        }
        return root;
    }
}
