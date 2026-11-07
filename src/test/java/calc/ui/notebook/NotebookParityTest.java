package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.persistence.NotebookStore;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookRow.Status;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parity with the two-field form that E13 removed (the table is in docs/adr/0011-fichiers-dans-l-interface.md): one test per
 * function of the old window, written in the notebook (a line instead of two fields and an operation selector, {@code ans}
 * instead of « Reprendre dernier résultat », {@code $n} instead of « Vers nombre 1 / 2 », Escape instead of « Effacer », Tout
 * effacer instead of « Effacer historique »). The number of the row of the table is in the display name. What the notebook
 * already proved elsewhere is cited in the ADR, not repeated here.
 */
class NotebookParityTest {
    @TempDir
    Path data;

    private NotebookStore store;
    private final List<DisplayMode> saved = new ArrayList<>();
    private NotebookDriver d;

    @BeforeEach
    void start() {
        store = NotebookStore.open(data);
        d = new NotebookDriver(NotebookPresenter.open(store, modes()));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    private DisplayModeStore modes() {
        return new DisplayModeStore() {
            @Override
            public DisplayMode load() {
                return DisplayMode.AUTO;
            }

            @Override
            public void save(DisplayMode mode) {
                saved.add(mode);
            }
        };
    }

    @Test
    @DisplayName("P1 calcul binaire : + - × ÷ ^ sur deux nombres")
    void p1BinaryCalculation() {
        d.line("1,5 + 2,5").line("10 - 3").line("6 × 7").line("10 ÷ 4").line("2^10");

        assertEquals(List.of("4", "7", "42", "2,5", "1024", ""), d.results());
        assertTrue(List.of(1, 2, 3, 4, 5).stream().allMatch(n -> d.row(n).status() == Status.EXACT));
    }

    @Test
    @DisplayName("P2 calcul unaire : racine carrée, exacte ou approchée")
    void p2UnaryCalculation() {
        d.line("√9").line("racine(16)").line("sqrt(2)");

        assertEquals("3", d.row(1).result());
        assertEquals("4", d.row(2).result());
        assertEquals(Status.APPROXIMATE, d.row(3).status());
        assertTrue(d.row(3).result().startsWith("≈ 1,41421356237"), d.row(3).result());
        assertTrue(d.row(3).detail().startsWith("Erreur maximale : "), d.row(3).detail());
    }

    @Test
    @DisplayName("P3 saisie avec virgule ou point décimal")
    void p3CommaOrPoint() {
        d.line("1,5 + 2.5").line(",5 + .5").line("1.5 + 2,5");

        assertEquals(List.of("4", "1", "4", ""), d.results());
    }

    @Test
    @DisplayName("P4 enchaînement de décimaux sans dérive : 0,1 + 0,2 puis + 0,3")
    void p4ChainedDecimalsDoNotDrift() {
        d.line("0,1 + 0,2").line("ans + 0,3");

        assertEquals("0,3", d.row(1).result());
        assertEquals("0,6", d.row(2).result());
        assertEquals(Status.EXACT, d.row(2).status());
    }

    @Test
    @DisplayName("P5 reprise du dernier résultat (Ctrl+R) : ans, qui saute les lignes en erreur")
    void p5ReuseOfTheLastResultIsAns() {
        d.line("8 / 2").line("ans * 3").line("1 / 0").line("ans + 1");

        assertEquals(List.of("4", "12", "", "13", ""), d.results(), "ans is the closest line above that has a value");
        d.escape();
        assertEquals(Status.ERROR, d.row(3).status());
    }

    @Test
    @DisplayName("P6 réutiliser une entrée d'historique comme premier ou second nombre : $n")
    void p6ReuseOfAnEntryAsFirstOrSecondNumber() {
        d.line("10 / 4").line("7 - 2").line("$1 + 1").line("1 + $2");

        assertEquals("3,5", d.row(3).result(), "line 1 as the first number");
        assertEquals("6", d.row(4).result(), "line 2 as the second number");
    }

    @Test
    @DisplayName("P7 un tiers réutilisé puis × 3 vaut exactement 1")
    void p7OneThirdReusedTimesThreeIsExactlyOne() {
        d.line("1 / 3").line("ans * 3");

        assertEquals("0,333333333333…", d.row(1).result());
        assertEquals("Valeur exacte : 1/3 (affichage arrondi)", d.row(1).detail());
        assertEquals("1", d.row(2).result());
        assertEquals(Status.EXACT, d.row(2).status());
    }

    @Test
    @DisplayName("P8 une valeur approchée réutilisée reste un intervalle")
    void p8AnApproximateValueStaysAnInterval() {
        d.line("√2").line("ans * ans").line("2");

        assertEquals(Status.APPROXIMATE, d.row(2).status());
        assertTrue(d.row(2).result().startsWith("≈ 2,0000000000"), d.row(2).result());
        assertEquals(Status.EXACT, d.row(3).status(), "retyping the number gives an exact value again");
    }

    @Test
    @DisplayName("P9 Effacer les champs : Échap abandonne la saisie en cours")
    void p9ClearFieldsIsEscape() {
        d.line("2 + 2").type("9 * 9").escape();

        assertEquals("", d.field(), "the new line is empty again");
        assertEquals(List.of("2 + 2", ""), d.texts());
    }

    @Test
    @DisplayName("P10 Effacer l'historique : Tout effacer, avec Annuler pour le rétablir")
    void p10ClearHistoryIsClearAndUndoRestoresIt() {
        d.line("1 + 1").line("ans * 5");

        d.clear();
        assertEquals(List.of(""), d.texts());
        d.undo();
        assertEquals(List.of("2", "10", ""), d.results());
    }

    @Test
    @DisplayName("P11 persistance : le carnet revient identique au démarrage suivant, exact et approché")
    void p11PersistenceAcrossSessions() {
        d.line("1 / 3").line("ans * 3").line("√2").line("ans * ans");
        List<String> before = d.results();
        store.close();

        store = NotebookStore.open(data);
        d = new NotebookDriver(NotebookPresenter.open(store, modes()));

        assertEquals(before, d.results());
        assertEquals(Status.EXACT, d.row(2).status());
        assertEquals(Status.APPROXIMATE, d.row(4).status());
    }

    @Test
    @DisplayName("P12 messages d'erreur ciblés : la partie fautive de la ligne est désignée")
    void p12TargetedErrors() {
        List<String> lines = List.of("8 / 0", "1,5,5 + 1", "2^99999999999", "√(0 - 4)", "0^(0 - 1)", "2^1,5", "2 +", "inconnu * 2");
        for (String line : lines) {
            d.line(line);
        }

        for (int i = 1; i <= lines.size(); i++) {
            NotebookRow row = d.row(i);
            assertEquals(Status.ERROR, row.status(), lines.get(i - 1));
            assertFalse(row.errorMessage().isBlank(), lines.get(i - 1));
            assertEquals("Erreur : " + row.errorMessage(), row.description());
            assertTrue(row.errorStart() >= 0 && row.errorEnd() > row.errorStart() && row.errorEnd() <= row.text().length(),
                    lines.get(i - 1) + " " + row.errorStart() + ".." + row.errorEnd());
        }
        assertTrue(d.row(3).errorMessage().contains("4096"), d.row(3).errorMessage());
    }

    @Test
    @DisplayName("P13 une erreur ne bloque rien : la ligne est gardée, les autres se calculent")
    void p13AnErrorBlocksNothing() {
        d.line("1 + 1").line("8 / 0").line("3 * 3");

        assertEquals(List.of("2", "", "9", ""), d.results());
        assertEquals(Status.ERROR, d.row(2).status());
        assertEquals(List.of("1 + 1", "8 / 0", "3 * 3", ""), d.texts(), "the old form kept no failed calculation; the notebook keeps the line");
    }

    @Test
    @DisplayName("P14 bornes de taille : une puissance énorme enchaînée est refusée vite, sans geler")
    void p14HugePowersAreRefusedWithoutFreezing() {
        d.line("2^999");
        TimingBudget.assertCompletesWithin(Duration.ofSeconds(1), () -> d.line("ans^999"));

        assertEquals(Status.EXACT, d.row(1).status());
        assertEquals("5,35754303593… × 10³⁰⁰", d.row(1).result());
        assertEquals(Status.ERROR, d.row(2).status());
        assertFalse(d.row(2).errorMessage().isBlank());
    }

    @Test
    @DisplayName("P15 modes d'affichage : un changement identique n'écrit rien, le cycle Ctrl+M boucle")
    void p15DisplayModes() {
        d.line("1 / 3");

        d.mode(DisplayMode.FRACTION);
        d.mode(DisplayMode.FRACTION);
        assertEquals(List.of(DisplayMode.FRACTION), saved, "the same mode again is not remembered twice");
        d.presenter.onDisplayModeChanged(null, d.field());
        assertEquals(List.of(DisplayMode.FRACTION), saved, "a null mode is ignored");

        d.mode(DisplayMode.AUTO);
        List<DisplayMode> cycle = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            d.nextMode();
            cycle.add(d.view.displayMode());
        }
        assertEquals(List.of(DisplayMode.DECIMAL, DisplayMode.FRACTION, DisplayMode.SCIENTIFIC, DisplayMode.AUTO), cycle);
    }

    @Test
    @DisplayName("P16 le détail d'exactitude : vide pour une valeur exacte et complète, ou en mode Fraction")
    void p16TheExactnessDetail() {
        d.line("1 / 4").line("1 / 3");

        assertEquals("", d.row(1).detail(), "0,25 is exact and complete: nothing to add");
        assertEquals("Valeur exacte : 1/3 (affichage arrondi)", d.row(2).detail());
        d.mode(DisplayMode.FRACTION);
        assertEquals("1/3", d.row(2).result());
        assertEquals("", d.row(2).detail(), "in Fraction mode the text is the exact value");
        d.mode(DisplayMode.SCIENTIFIC);
        assertTrue(d.row(2).detail().startsWith("Valeur exacte : 1/3"), d.row(2).detail());
    }
}
