package calc.detail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.format.DisplayMode;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sentences of {@link LineDetailText}: which ones are formulas, and their text on chosen lines. */
class LineDetailTextTest {

    @Test
    void theExpansionAndTheResultAreFormulasTheOtherSentencesAreNot() {
        EvaluatedNotebook notebook = evaluated("a = 2", "b = a * 3", "√b", "$3 + 1");
        assertEquals(List.of(
                new LineDetailText.Sentence("= 2 * 3", true),
                new LineDetailText.Sentence("= 6", true),
                new LineDetailText.Sentence("Sources : ligne 1", false),
                new LineDetailText.Sentence("Utilisée par 1 ligne", false)), sentences(notebook, 2));
        assertEquals(List.of(
                new LineDetailText.Sentence("= √6", true),
                new LineDetailText.Sentence("≈ 2,44948974278…", true),
                new LineDetailText.Sentence("Sources : ligne 2", false),
                new LineDetailText.Sentence("Utilisée par 1 ligne", false),
                new LineDetailText.Sentence("Valeur garantie entre 2,44948974278 et 2,44948974279", false)), sentences(notebook, 3));
    }

    @Test
    void theTotalOfTheKitchenQuoteAsTheUserGuideShowsIt() {
        EvaluatedNotebook quote = evaluated("# Devis cuisine", "plan = 1 250,00", "meubles = 3 480", "pose = 35 * 12,5",
                "total = plan + meubles + pose", "taux = 0,2", "total * (1 + taux)", "ans / 12");
        assertEquals(List.of("= 1250 + 3480 + 437,5", "= 5167,5", "Sources : lignes 2, 3 et 4", "Utilisée par 1 ligne"),
                LineDetailText.of(quote, quote.result(5).id(), DisplayMode.AUTO));
    }

    @Test
    void ofGivesTheTextsOfTheSentences() {
        EvaluatedNotebook notebook = evaluated("a = 2", "b = a * 3", "√b", "$3 + 1");
        for (int number = 1; number <= 4; number++) {
            List<String> texts = sentences(notebook, number).stream().map(LineDetailText.Sentence::text).toList();
            assertEquals(texts, LineDetailText.of(notebook, notebook.result(number).id(), DisplayMode.AUTO), "line " + number);
        }
    }

    @Test
    void theSourcesAreListedInTheOrderOfTheNotebookOnceEach() {
        EvaluatedNotebook notebook = evaluated("a = 1", "b = 2", "c = 3", "c + a + b + a + $2");
        assertEquals("Sources : lignes 1, 2 et 3", sentences(notebook, 4).get(2).text());
    }

    @Test
    void aSentenceIsAlwaysAText() {
        assertThrows(NullPointerException.class, () -> new LineDetailText.Sentence(null, false));
        EvaluatedNotebook notebook = evaluated("1");
        assertThrows(NullPointerException.class, () -> LineDetailText.of(notebook, notebook.result(1).id(), null));
    }

    private static List<LineDetailText.Sentence> sentences(EvaluatedNotebook notebook, int number) {
        return LineDetailText.sentences(notebook, notebook.result(number).id(), DisplayMode.AUTO);
    }

    private static EvaluatedNotebook evaluated(String... lines) {
        try {
            return NotebookEvaluator.evaluate(Notebook.of(List.of(lines)));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }
}
