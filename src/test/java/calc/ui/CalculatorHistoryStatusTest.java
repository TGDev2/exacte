package calc.ui;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import calc.engine.RejectedLine.Cause;
import calc.testing.RecordGenerators;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class CalculatorHistoryStatusTest {
    private static final Path BACKUP = Path.of("history.json.20240101-120000.bak");
    private static final String BACKUP_NOTICE = " Copie de sauvegarde : " + BACKUP;
    private static final CalculationRecord RECORD =
            RecordGenerators.calculated(Operation.ADDITION, "1", "2");

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
        CalculatorViewModel view = startWith(new HistoryLoadResult(records(12), rejections(Cause.MALFORMED_ENTRY), BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals("12 calculs restaurés, 1 entrée illisible ignorée." + BACKUP_NOTICE, view.statusMessage());
        assertEquals(12, view.historyEntries().size());
    }

    @Test
    void testSingleRestoredEntryAndPluralUnreadableEntries() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(RECORD), rejections(Cause.UNKNOWN_OPERATION, Cause.INVALID_OPERAND, Cause.UNREADABLE_RESULT), BACKUP));

        assertEquals("1 calcul restauré, 3 entrées illisibles ignorées." + BACKUP_NOTICE, view.statusMessage());
    }

    @Test
    void testInconsistentEntriesAreNamedSeparatelyFromUnreadableOnes() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(RECORD),
                rejections(Cause.INCONSISTENT_RESULT, Cause.MALFORMED_ENTRY, Cause.INCONSISTENT_RESULT),
                BACKUP));

        assertEquals(
                "1 calcul restauré, 1 entrée illisible ignorée et 2 entrées incohérentes ignorées." + BACKUP_NOTICE,
                view.statusMessage());
    }

    @Test
    void testNothingRestorableReportsTheIgnoredEntriesAndTheBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(), rejections(Cause.INCONSISTENT_RESULT, Cause.INCONSISTENT_RESULT), BACKUP));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "Aucun calcul restauré, 2 entrées incohérentes ignorées. Vous pouvez continuer sans blocage." + BACKUP_NOTICE,
                view.statusMessage());
        assertEquals(0, view.historyEntries().size());
    }

    @Test
    void testNewerFormatShowsItsEntriesInReadOnlyMode() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                records(2), List.of(), null, HistoryLoadResult.Condition.NEWER_VERSION_READ_ONLY));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals("Historique créé par une version plus récente de Calc : ouvert en lecture seule.", view.statusMessage());
        assertEquals(2, view.historyEntries().size());
    }

    @Test
    void testNewerFormatThatCannotBeReadIsReportedAsNotOpened() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                List.of(), List.of(), null, HistoryLoadResult.Condition.NEWER_VERSION_NOT_LOADED));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(
                "Historique créé par une version plus récente de Calc : il n'a pas pu être ouvert et ne sera pas modifié.",
                view.statusMessage());
    }

    @Test
    void testASecondInstanceIsToldItsHistoryWillNotBeSaved() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                records(3), List.of(), null, HistoryLoadResult.Condition.IN_USE_READ_ONLY));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals("Calc est déjà ouvert : l'historique de cette fenêtre ne sera pas enregistré.", view.statusMessage());
        assertEquals(3, view.historyEntries().size(), "The history is still shown.");
    }

    @Test
    void testMigrationReportsTheNumberOfConvertedEntries() {
        assertEquals("Historique converti au nouveau format (6 entrées).",
                startWith(new HistoryLoadResult(records(6), List.of(), BACKUP, HistoryLoadResult.Condition.MIGRATED)).statusMessage());
        assertEquals("Historique converti au nouveau format (1 entrée).",
                startWith(new HistoryLoadResult(records(1), List.of(), BACKUP, HistoryLoadResult.Condition.MIGRATED)).statusMessage());
        assertEquals(StatusTone.SUCCESS,
                startWith(new HistoryLoadResult(records(2), List.of(), BACKUP, HistoryLoadResult.Condition.MIGRATED)).statusTone());
    }

    @Test
    void testMigrationWithRejectionsNamesThemAndTheBackup() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(
                records(2), rejections(Cause.INVALID_OPERAND), BACKUP, HistoryLoadResult.Condition.MIGRATED));

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals("Historique converti au nouveau format (2 entrées), 1 entrée illisible ignorée." + BACKUP_NOTICE,
                view.statusMessage());
    }

    @Test
    void testCalculationsInReadOnlyModeSayTheyAreNotSaved() {
        CalculationHistoryStore readOnly = new StubStore(new HistoryLoadResult(List.of(), List.of(), null,
                HistoryLoadResult.Condition.IN_USE_READ_ONLY), null) {
            @Override
            public boolean isReadOnly() {
                return true;
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), readOnly);

        CalculatorViewModel calculated = presenter.onCalculate("1", "2");
        assertEquals(StatusTone.SUCCESS, calculated.statusTone());
        assertEquals("Calcul effectué. Le résultat est prêt pour le calcul suivant, mais l'historique est en lecture seule : "
                + "il ne sera pas enregistré.", calculated.statusMessage());
        assertEquals(1, calculated.historyEntries().size(), "The calculation is still listed in this window.");

        CalculatorViewModel cleared = presenter.onClearHistory();
        assertEquals("Historique effacé dans cette fenêtre. Il est en lecture seule : le fichier n'est pas modifié.",
                cleared.statusMessage());
    }

    @Test
    void testRejectionsWithoutAnyBackupDoNotMentionACopy() {
        CalculatorViewModel view = startWith(new HistoryLoadResult(List.of(RECORD), rejections(Cause.INVALID_OPERAND), null));

        assertEquals("1 calcul restauré, 1 entrée illisible ignorée.", view.statusMessage());
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

    private static class StubStore implements CalculationHistoryStore {
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
