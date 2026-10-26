package calc.ui;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import calc.engine.RejectedLine.Cause;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorHistoryStatusTest {
    private static final Path BACKUP = Path.of("history.tsv.20240101-120000.bak");
    private static final String BACKUP_NOTICE = " Copie de sauvegarde : " + BACKUP;
    private static final CalculationRecord RECORD =
            new CalculationRecord(Operation.ADDITION, "1", "2", new BigDecimal("3"));

    @Test
    void testCleanRestoreKeepsTheExistingSuccessMessage() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), List.of(), null));

        assertEquals(StatusTone.SUCCESS, view.statusTone());
        assertEquals(
                "Historique restauré. Vous pouvez réutiliser un résultat précédent ou lancer un nouveau calcul.",
                view.statusMessage());
    }

    @Test
    void testEmptyHistoryKeepsTheExistingInformationMessage() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(), List.of(), null));

        assertEquals(StatusTone.INFO, view.statusTone());
        assertEquals(
                "Historique vide. Saisissez un calcul puis appuyez sur Entrée ou cliquez sur Calculer.",
                view.statusMessage());
    }

    @Test
    void testPartialRestoreReportsTheCountsAndTheBackupPath() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(records(12), rejections(Cause.WRONG_COLUMN_COUNT), BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals("12 calculs restaurés, 1 ligne illisible ignorée." + BACKUP_NOTICE, view.statusMessage());
        assertEquals(12, view.historyEntries().size());
    }

    @Test
    void testSingleRestoredEntryAndPluralUnreadableLines() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(RECORD), rejections(Cause.UNKNOWN_OPERATION, Cause.INVALID_OPERAND, Cause.UNREADABLE_RESULT), BACKUP));

        assertEquals("1 calcul restauré, 3 lignes illisibles ignorées." + BACKUP_NOTICE, view.statusMessage());
    }

    @Test
    void testInconsistentLinesAreNamedSeparatelyFromUnreadableOnes() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(RECORD),
                rejections(Cause.INCONSISTENT_RESULT, Cause.WRONG_COLUMN_COUNT, Cause.INCONSISTENT_RESULT),
                BACKUP));

        assertEquals(
                "1 calcul restauré, 1 ligne illisible ignorée et 2 lignes incohérentes ignorées." + BACKUP_NOTICE,
                view.statusMessage());
    }

    @Test
    void testNothingRestorableReportsTheIgnoredLinesAndTheBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(), rejections(Cause.INCONSISTENT_RESULT, Cause.INCONSISTENT_RESULT), BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "Aucun calcul restauré, 2 lignes incohérentes ignorées. Vous pouvez continuer sans blocage." + BACKUP_NOTICE,
                view.statusMessage());
        assertEquals(0, view.historyEntries().size());
    }

    @Test
    void testNewerFormatIsReportedAsSuch() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(), rejections(Cause.UNSUPPORTED_FORMAT_VERSION), BACKUP));

        assertEquals(
                "Aucun calcul restauré, 1 ligne d'un format plus récent ignorée. Vous pouvez continuer sans blocage."
                        + BACKUP_NOTICE,
                view.statusMessage());
    }

    @Test
    void testRejectionsWithoutAnyBackupDoNotMentionACopy() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), rejections(Cause.INVALID_OPERAND), null));

        assertEquals("1 calcul restauré, 1 ligne illisible ignorée.", view.statusMessage());
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
    void testUnreadableHistoryMentionsTheBackupPathWhenOneExists() {
        CalculatorViewModel view = startWith(new CalculationHistoryException("Impossible de lire l'historique", null, BACKUP));

        assertEquals(
                "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage." + BACKUP_NOTICE,
                view.statusMessage());
    }

    private static List<CalculationRecord> records(int count) {
        List<CalculationRecord> records = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            records.add(RECORD);
        }
        return records;
    }

    private static List<RejectedLine> rejections(Cause... causes) {
        List<RejectedLine> rejections = new ArrayList<>();
        for (int index = 0; index < causes.length; index++) {
            rejections.add(new RejectedLine(index + 1, causes[index], "test"));
        }
        return rejections;
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
