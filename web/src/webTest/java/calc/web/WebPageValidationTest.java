package calc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.web.BrowserDriver.Json;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How a line gets validated in the page, beyond the Enter key of WebPageDisplayAcceptanceTest, read in a real browser. */
@DisplayName("La page : validation d'une ligne, refus et valeur exacte")
class WebPageValidationTest {
    private static final int TOO_LONG_LENGTH = 16385;

    private static Json states;

    @BeforeAll
    static void playTheScenario() throws Exception {
        Json root = BrowserDriver.parseJson(BrowserDriver.run("page-validation-driver.html", "run=1"));
        assertTrue(root.get("error").isNull(), () -> "La page pilote s'est arrêtée : " + root.get("error").text());
        states = root.get("states");
    }

    private static List<Json> lines(String state) {
        return states.get(state).get("lines").items();
    }

    @Test
    void theSheetOpensOnAFocusedBlankLineThatIsNotYetInTheNotebook() {
        assertEquals(1, lines("atLoad").size());
        assertEquals("", lines("atLoad").get(0).get("value").text());
        assertTrue(states.get("atLoad").get("focusedIsLast").bool());
    }

    @Test
    void leavingALineValidatesItWithoutEnter() {
        List<Json> lines = lines("afterLeaving");

        assertEquals("2", lines.get(0).get("result").text());
        assertEquals(2, lines.size(), "Une ligne de brouillon doit suivre la ligne validée");
        assertEquals("", lines.get(1).get("value").text());
    }

    @Test
    void aRefusedLineKeepsItsTextShowsTheMessageAndStaysOnTheLine() {
        Json state = states.get("afterRefusal");
        List<Json> lines = lines("afterRefusal");

        assertTrue(state.get("messageShown").bool());
        assertTrue(state.get("message").text().contains("dépasse"), state.get("message").text());
        assertEquals(2, lines.size(), "Le carnet ne doit pas changer");
        assertEquals(TOO_LONG_LENGTH, lines.get(1).get("length").number(), "Le texte refusé doit rester à corriger");
        assertEquals("", lines.get(1).get("result").text());
        assertTrue(state.get("focusedIsLast").bool());
    }

    @Test
    void aCorrectedLineIsAcceptedAndTheMessageGoes() {
        Json state = states.get("afterCorrection");

        assertFalse(state.get("messageShown").bool());
        assertEquals("", state.get("message").text());
        assertEquals("3", lines("afterCorrection").get(1).get("result").text());
        assertEquals(3, lines("afterCorrection").size());
    }

    @Test
    void enterOnTheBlankLineAddsNothing() {
        assertEquals(3, lines("afterEnterOnABlankLine").size());
        assertEquals("", lines("afterEnterOnABlankLine").get(2).get("value").text());
    }

    /** As on the desktop: the exact value follows a cut text only, and 12,5 is complete as it is. */
    @Test
    void theExactValueOnlyFollowsACutText() {
        List<Json> auto = lines("auto");
        assertEquals("12,5", auto.get(2).get("result").text());
        assertEquals("", auto.get(2).get("detail").text());
        assertEquals("0,333333333333…", auto.get(3).get("result").text());
        assertEquals("Valeur exacte : 1/3", auto.get(3).get("detail").text());

        List<Json> fraction = lines("fraction");
        assertEquals("1/3", fraction.get(3).get("result").text());
        assertEquals("", fraction.get(3).get("detail").text());
    }
}
