package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The blank line that ends the sheet is not in the notebook yet, and the page opens with the focus on it. Like the desktop
 * (NotebookPresenter.onDelete, moveLine and insert), delete and move have nothing to act on there, and an insertion appends;
 * Cmd does what Ctrl does for Ctrl+M too. Uses page-editing-driver.html as it is.
 */
@DisplayName("La page : la ligne de saisie vide, et Cmd+M")
class WebPageDraftLineTest {
    private static final List<String> EXAMPLE = List.of("# Carnet d'exemple : des résultats exacts", "(1/3)*3", "√2",
            "prix = 12,5", "prix * 3", "$2 + $5", "2 +");
    private static final String NOTHING_TO_DELETE = "Cette ligne n'est pas encore validée : il n'y a rien à supprimer (Échap abandonne la saisie).";
    private static final String CANNOT_MOVE = "Cette ligne n'est pas encore validée : elle ne peut pas être déplacée.";

    private static Json page;

    @BeforeAll
    static void playTheScenario() throws Exception {
        page = play(
                "{\"store\":null}", "{\"load\":\"normal\"}",
                "{\"focus\":8}", "{\"key\":\"K\",\"ctrl\":true,\"shift\":true}", "{\"snapshot\":\"deleteByKey\"}",
                "{\"click\":\"Supprimer\"}", "{\"snapshot\":\"deleteByButton\"}",
                "{\"focus\":8}", "{\"key\":\"ArrowUp\",\"ctrl\":true,\"shift\":true}", "{\"snapshot\":\"moveByKey\"}",
                "{\"focus\":8}", "{\"click\":\"Monter\"}", "{\"snapshot\":\"moveByButton\"}",
                "{\"focus\":8}", "{\"key\":\"Enter\",\"ctrl\":true,\"shift\":true}", "{\"snapshot\":\"insertAbove\"}",
                "{\"key\":\"m\",\"meta\":true}", "{\"snapshot\":\"metaM\"}",
                "{\"click\":\"Effacer le carnet\"}", "{\"click\":\"Annuler\"}", "{\"focus\":3}",
                "{\"key\":\"y\",\"ctrl\":true}", "{\"snapshot\":\"redoneClear\"}");
    }

    private static List<String> values(String snapshot) {
        return rows(snapshot).stream().map(row -> row.get("value").text()).toList();
    }

    private static List<Json> rows(String snapshot) {
        return page.get("snapshots").get(snapshot).get("rows").items();
    }

    private static List<String> messages(String snapshot) {
        return page.get("snapshots").get(snapshot).get("messages").items().stream().map(Json::text).toList();
    }

    private static List<String> exampleAndBlank() {
        List<String> expected = new ArrayList<>(EXAMPLE);
        expected.add("");
        return expected;
    }

    @Test
    void deleteOnTheBlankLineChangesNothingAndSaysWhy() {
        assertEquals(exampleAndBlank(), values("deleteByKey"), "Ctrl+Maj+K");
        assertEquals(List.of(NOTHING_TO_DELETE), messages("deleteByKey"));
        assertEquals(exampleAndBlank(), values("deleteByButton"), "Supprimer");
        assertEquals(List.of(NOTHING_TO_DELETE), messages("deleteByButton"));
    }

    @Test
    void moveOnTheBlankLineChangesNothingAndSaysWhy() {
        assertEquals(exampleAndBlank(), values("moveByKey"), "Ctrl+Maj+↑");
        assertEquals(List.of(CANNOT_MOVE), messages("moveByKey"));
        assertEquals(exampleAndBlank(), values("moveByButton"), "Monter");
        assertEquals(List.of(CANNOT_MOVE), messages("moveByButton"));
    }

    @Test
    void insertOnTheBlankLineAppendsALineAtTheEnd() {
        List<String> expected = new ArrayList<>(EXAMPLE);
        expected.add("");
        expected.add("");
        assertEquals(expected, values("insertAbove"), "La ligne 7 doit rester la dernière ligne écrite");
        assertEquals("2 +", rows("insertAbove").get(6).get("value").text());
        assertEquals(8, page.get("snapshots").get("insertAbove").get("active").get("line").number(), "Le focus va à la ligne ajoutée");
    }

    @Test
    void cmdMGoesToTheNextDisplayModeLikeCtrlM() {
        assertEquals("DECIMAL", page.get("snapshots").get("metaM").get("mode").text());
    }

    @Test
    void redoingAClearWhileALineHoldsTheFocusLeavesTheNotebookEmpty() {
        assertEquals(List.of(""), values("redoneClear"));
        assertEquals(List.of(), page.get("pageErrors").items());
        assertTrue(page.get("error").isNull());
    }

    private static Json play(String... steps) throws IOException, InterruptedException {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        for (String step : steps) {
            joined.add(step);
        }
        String scenario = "{\"width\":1024,\"steps\":" + joined + "}";
        Json root = BrowserDriver.parseJson(BrowserDriver.run("page-editing-driver.html", "scenario=" + URLEncoder.encode(scenario, UTF_8)));
        if (!root.get("error").isNull()) {
            fail("La page pilote s'est arrêtée : " + root.get("error").text());
        }
        return root;
    }
}
