package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.notebook.NotebookRow.Kind;
import calc.ui.notebook.NotebookRow.Sign;
import calc.ui.notebook.NotebookRow.Status;
import org.junit.jupiter.api.Test;

/** What a row gives its view, with no Swing: its kind, its sign, what a screen reader hears, its result cut on the comma. */
class NotebookRowTest {

    private static NotebookRow row(String text, Status status, String result, String name, String description) {
        boolean error = status == Status.ERROR;
        return new NotebookRow(3, text, status, result, "", error ? "Variable inconnue « remise »" : "", error ? 8 : -1,
                error ? 14 : -1, name, description);
    }

    @Test
    void aLineThatStartsWithAHashIsATitleAnotherCommentIsNot() {
        assertEquals(Kind.TITLE, row("# Devis cuisine", Status.COMMENT, "", "", "Commentaire").kind());
        assertEquals(Kind.TITLE, row("#", Status.COMMENT, "", "", "Commentaire").kind());
        assertEquals(Kind.COMMENT, row("  # remarque", Status.COMMENT, "", "", "Commentaire").kind());
        assertEquals(Kind.EMPTY, row("", Status.EMPTY, "", "", "Ligne vide").kind());
        assertEquals(Kind.ASSIGNMENT, row("total = 2", Status.EXACT, "2", "total", "Résultat : 2, exact, définit la variable total")
                .kind());
        assertEquals(Kind.CALCULATION, row("total * 2", Status.EXACT, "4", "", "Résultat : 4, exact").kind());
        assertEquals(Kind.CALCULATION, row("a * 3", Status.PENDING, "Calcul en cours…", "", "Calcul en cours…").kind());
        assertEquals(Kind.ASSIGNMENT, row("x = 1 / 0", Status.ERROR, "", "x", "Erreur : division").kind());
    }

    @Test
    void theSignFollowsTheStatusAndSaysItInWords() {
        assertEquals(Sign.EXACT, row("2", Status.EXACT, "2", "", "Résultat : 2, exact").sign());
        assertEquals(Sign.APPROXIMATE, row("√2", Status.APPROXIMATE, "≈ 1,41421356237…", "", "").sign());
        assertEquals(Sign.ERROR, row("total - remise", Status.ERROR, "", "", "Erreur : x").sign());
        assertEquals(Sign.NONE, row("# titre", Status.COMMENT, "", "", "Commentaire").sign());
        assertEquals(Sign.NONE, row("", Status.EMPTY, "", "", "Ligne vide").sign());
        assertEquals(Sign.NONE, row("a * 3", Status.PENDING, "Calcul en cours…", "", "Calcul en cours…").sign());
        assertEquals("=", Sign.EXACT.text());
        assertEquals("≈", Sign.APPROXIMATE.text());
        assertEquals("!", Sign.ERROR.text());
        assertEquals("", Sign.NONE.text());
        assertEquals("valeur exacte", Sign.EXACT.spoken());
        assertEquals("valeur approchée", Sign.APPROXIMATE.spoken());
        assertEquals("erreur", Sign.ERROR.spoken());
        assertEquals("", Sign.NONE.spoken());
    }

    @Test
    void aScreenReaderHearsTheExactnessOfAValueAndTheDescriptionOfAnythingElse() {
        assertEquals("Résultat : 5167,5, valeur exacte, définit la variable total",
                row("total = 5167,5", Status.EXACT, "5167,5", "total", "Résultat : 5167,5, exact, définit la variable total")
                        .accessibleDescription());
        assertEquals("Résultat : ≈ 1,41421356237…, valeur approchée",
                row("√2", Status.APPROXIMATE, "≈ 1,41421356237…", "", "Résultat : ≈ 1,41421356237…, approché").accessibleDescription());
        assertEquals("Erreur : Variable inconnue « remise »",
                row("total - remise", Status.ERROR, "", "", "Erreur : Variable inconnue « remise »").accessibleDescription());
        assertEquals("Commentaire", row("# titre", Status.COMMENT, "", "", "Commentaire").accessibleDescription());
        assertEquals("Calcul en cours…", row("a * 3", Status.PENDING, "Calcul en cours…", "", "Calcul en cours…")
                .accessibleDescription());
    }

    @Test
    void theResultIsCutOnItsFirstCommaWithoutBeingFormattedAgain() {
        NotebookRow decimal = row("x", Status.EXACT, "5167,5", "", "");
        assertEquals("5167", decimal.resultIntegerPart());
        assertEquals(",5", decimal.resultDecimalPart());
        NotebookRow scientific = row("x", Status.EXACT, "5,35754303593… × 10³⁰⁰", "", "");
        assertEquals("5", scientific.resultIntegerPart());
        assertEquals(",35754303593… × 10³⁰⁰", scientific.resultDecimalPart());
        NotebookRow integer = row("x", Status.EXACT, "6201", "", "");
        assertEquals("6201", integer.resultIntegerPart());
        assertEquals("", integer.resultDecimalPart());
        NotebookRow fraction = row("x", Status.EXACT, "1/3", "", "");
        assertEquals("1/3", fraction.resultIntegerPart());
        assertEquals("", fraction.resultDecimalPart());
        NotebookRow none = row("", Status.EMPTY, "", "", "Ligne vide");
        assertEquals("", none.resultIntegerPart());
        assertEquals("", none.resultDecimalPart());
    }

    @Test
    void aSourceOfTheActiveLineShowsTheMarkAndSaysIt() {
        NotebookRow plain = row("plan = 1250", Status.EXACT, "1 250", "plan", "Résultat : 1 250, exact, définit la variable plan");
        assertFalse(plain.source());
        assertEquals("", plain.sourceMark());
        assertSame(plain, plain.withSource(false), "nothing changes, the same row");

        NotebookRow source = plain.withSource(true);
        assertTrue(source.source());
        assertEquals("◆", source.sourceMark());
        assertEquals("Résultat : 1 250, valeur exacte, définit la variable plan, source de la ligne active", source.accessibleDescription());
        assertEquals(plain.description(), source.description(), "the sentence of the line itself does not change");
        assertEquals(plain, source.withSource(false));

        NotebookRow failing = row("x = 1 / 0", Status.ERROR, "", "x", "Erreur : division").withSource(true);
        assertEquals("Erreur : division, source de la ligne active", failing.accessibleDescription());
    }
}
