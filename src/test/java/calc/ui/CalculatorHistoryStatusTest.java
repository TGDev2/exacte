package calc.ui;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
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

        assertEquals(StatusTone.SUCCESS, view.statusTone());
        assertEquals(
                "Historique restauré. Vous pouvez réutiliser un résultat précédent ou lancer un nouveau calcul.",
                view.statusMessage());
    }

    @Test
    void testEmptyHistoryKeepsTheExistingInformationMessage() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(), 0, null));

        assertEquals(StatusTone.INFO, view.statusTone());
        assertEquals(
                "Historique vide. Saisissez un calcul puis appuyez sur Entrée ou cliquez sur Calculer.",
                view.statusMessage());
    }

    @Test
    void testPartialRestoreReportsSingularSkippedEntryAndBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), 1, BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "Historique restauré partiellement : 1 entrée ignorée. Une copie du fichier d'origine a été conservée.",
                view.statusMessage());
        assertEquals(1, view.historyEntries().size());
    }

    @Test
    void testPartialRestoreReportsPluralSkippedEntries() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), 3, BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "Historique restauré partiellement : 3 entrées ignorées. Une copie du fichier d'origine a été conservée.",
                view.statusMessage());
    }

    @Test
    void testNothingRestorableReportsUnavailableHistoryAndBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(), 2, BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage."
                        + " Une copie du fichier d'origine a été conservée.",
                view.statusMessage());
        assertEquals(0, view.historyEntries().size());
    }

    @Test
    void testUnreadableHistoryReportsUnavailableHistoryWithoutBackupWhenNoCopyExists() {
        CalculatorViewModel view = startWith(new CalculationHistoryException("Impossible de lire l'historique", null, null));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage.",
                view.statusMessage());
    }

    @Test
    void testUnreadableHistoryMentionsTheBackupWhenOneExists() {
        CalculatorViewModel view = startWith(new CalculationHistoryException("Impossible de lire l'historique", null, BACKUP));

        assertEquals(
                "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage."
                        + " Une copie du fichier d'origine a été conservée.",
                view.statusMessage());
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
