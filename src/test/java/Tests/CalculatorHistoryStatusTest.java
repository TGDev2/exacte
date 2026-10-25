package Tests;

import Calcul.CalculationHistoryException;
import Calcul.CalculationHistoryStore;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.HistoryLoadResult;
import Calcul.Operation;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorViewModel;
import Graphique.StatusTone;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorHistoryStatusTest {
    private static final Path BACKUP = Path.of("history.tsv.20240101-120000.bak");
    private static final CalculationRecord RECORD =
            new CalculationRecord(Operation.ADDITION, "1", "2", new BigDecimal("3"));

    @Test
    void testCleanRestoreKeepsTheExistingSuccessMessage() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), 0, null));

        assertEquals(StatusTone.SUCCESS, view.getStatusTone());
        assertEquals(
                "Historique restaure. Vous pouvez reutiliser un resultat precedent ou lancer un nouveau calcul.",
                view.getStatusMessage());
    }

    @Test
    void testEmptyHistoryKeepsTheExistingInformationMessage() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(), 0, null));

        assertEquals(StatusTone.INFO, view.getStatusTone());
        assertEquals(
                "Historique vide. Saisissez un calcul puis appuyez sur Entree ou cliquez sur Calculer.",
                view.getStatusMessage());
    }

    @Test
    void testPartialRestoreReportsSingularSkippedEntryAndBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), 1, BACKUP));

        assertEquals(StatusTone.ERROR, view.getStatusTone());
        assertEquals(
                "Historique restaure partiellement : 1 entree ignoree. Une copie du fichier d'origine a ete conservee.",
                view.getStatusMessage());
        assertEquals(1, view.getHistoryEntries().size());
    }

    @Test
    void testPartialRestoreReportsPluralSkippedEntries() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), 3, BACKUP));

        assertEquals(StatusTone.ERROR, view.getStatusTone());
        assertEquals(
                "Historique restaure partiellement : 3 entrees ignorees. Une copie du fichier d'origine a ete conservee.",
                view.getStatusMessage());
    }

    @Test
    void testNothingRestorableReportsUnavailableHistoryAndBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(), 2, BACKUP));

        assertEquals(StatusTone.ERROR, view.getStatusTone());
        assertEquals(
                "L'historique precedent n'a pas pu etre restaure. Vous pouvez continuer sans blocage."
                        + " Une copie du fichier d'origine a ete conservee.",
                view.getStatusMessage());
        assertEquals(0, view.getHistoryEntries().size());
    }

    @Test
    void testUnreadableHistoryReportsUnavailableHistoryWithoutBackupWhenNoCopyExists() {
        CalculatorViewModel view = startWith(new CalculationHistoryException("Impossible de lire l'historique", null, null));

        assertEquals(StatusTone.ERROR, view.getStatusTone());
        assertEquals(
                "L'historique precedent n'a pas pu etre restaure. Vous pouvez continuer sans blocage.",
                view.getStatusMessage());
    }

    @Test
    void testUnreadableHistoryMentionsTheBackupWhenOneExists() {
        CalculatorViewModel view = startWith(new CalculationHistoryException("Impossible de lire l'historique", null, BACKUP));

        assertEquals(
                "L'historique precedent n'a pas pu etre restaure. Vous pouvez continuer sans blocage."
                        + " Une copie du fichier d'origine a ete conservee.",
                view.getStatusMessage());
    }

    private static CalculatorViewModel startWith(HistoryLoadResult result) {
        return new CalculatorPresenter(new CalculatorEngine(), new StubStore(result, null)).createInitialView();
    }

    private static CalculatorViewModel startWith(CalculationHistoryException failure) {
        return new CalculatorPresenter(new CalculatorEngine(), new StubStore(null, failure)).createInitialView();
    }

    private static final class StubStore implements CalculationHistoryStore {
        private final HistoryLoadResult result;
        private final CalculationHistoryException failure;

        StubStore(HistoryLoadResult result, CalculationHistoryException failure) {
            this.result = result;
            this.failure = failure;
        }

        @Override
        public HistoryLoadResult loadHistory() throws CalculationHistoryException {
            if (failure != null) {
                throw failure;
            }
            return result;
        }

        @Override
        public void saveHistory(List<CalculationRecord> history) {
        }
    }
}
