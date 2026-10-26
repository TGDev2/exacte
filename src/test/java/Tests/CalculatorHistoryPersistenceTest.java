package Tests;

import Calcul.CalculatorEngine;
import Calcul.FileCalculationHistoryStore;
import Calcul.Operation;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorViewModel;
import Graphique.StatusTone;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorHistoryPersistenceTest {
    @Test
    void testHistoryPersistsAcrossSessions(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");

        CalculatorPresenter firstSession = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        firstSession.onCalculate("2", "3");

        CalculatorPresenter secondSession = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel restoredView = secondSession.createInitialView();

        assertEquals(StatusTone.SUCCESS, restoredView.getStatusTone(), "Restoring history should expose a success status.");
        assertEquals(1, restoredView.getHistoryEntries().size(), "Restored sessions should recover persisted history entries.");
        assertEquals("2 + 3 = 5", restoredView.getHistoryEntries().get(0).toString(), "Restored history should remain readable.");

        CalculatorViewModel reusedView = secondSession.onReuseLastResult("", "");
        assertEquals("5", reusedView.getFirstInput(), "The restored latest result should be reusable immediately.");
        assertTrue(reusedView.isClearHistoryEnabled(), "Restored history should keep the clear action available.");

        CalculatorViewModel clearedView = secondSession.onClearHistory();
        assertEquals(0, clearedView.getHistoryEntries().size(), "Clearing history should empty the current session.");
        assertFalse(clearedView.isClearHistoryEnabled(), "Clearing history should disable the clear action.");

        CalculatorPresenter thirdSession = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel emptyView = thirdSession.createInitialView();
        assertEquals(0, emptyView.getHistoryEntries().size(), "Cleared history should stay empty after a relaunch.");
        assertEquals(StatusTone.INFO, emptyView.getStatusTone(), "Empty history after relaunch should fall back to the informational state.");
    }

    @Test
    void testInvalidPersistedHistoryDoesNotBlockStartup(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, "bad-data", StandardCharsets.UTF_8);

        CalculatorPresenter presenter = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel viewModel = presenter.createInitialView();

        assertEquals(0, viewModel.getHistoryEntries().size(), "Invalid persisted history should be ignored at startup.");
        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "Invalid persisted history should surface as a non-blocking error.");
        assertEquals(
                "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage."
                        + " Une copie du fichier d'origine a été conservée.",
                viewModel.getStatusMessage(),
                "The startup message should explain that the user can continue without the previous history.");
    }

    @Test
    void testRoundedResultStaysStableAcrossPersistence(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");

        CalculatorPresenter firstSession = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        firstSession.onOperationChanged(Operation.DIVISION, "1", "3");
        firstSession.onCalculate("1", "3");

        CalculatorPresenter secondSession = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel restoredView = secondSession.createInitialView();

        assertEquals(
                "1 / 3 = 0,333333333333",
                restoredView.getHistoryEntries().get(0).toString(),
                "Rounded values should keep the same readable precision after relaunch.");

        CalculatorViewModel reusedView = secondSession.onReuseLastResult("", "");
        assertEquals(
                "0,333333333333",
                reusedView.getFirstInput(),
                "The reused value after relaunch should match the displayed rounded result exactly.");
    }

    @Test
    void testLegacyFloatingHistoryIsNormalizedOnRestore(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        Files.createDirectories(historyFile.getParent());
        Files.writeString(
                historyFile,
                "ADDITION\t0,1\t0,2\t0.30000000000000004",
                StandardCharsets.UTF_8);

        CalculatorPresenter presenter = new CalculatorPresenter(
                new CalculatorEngine(),
                new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel viewModel = presenter.createInitialView();

        assertEquals(1, viewModel.getHistoryEntries().size(), "Legacy floating histories should still restore.");
        assertEquals(
                "0,1 + 0,2 = 0,3",
                viewModel.getHistoryEntries().get(0).toString(),
                "Legacy floating artifacts should be normalized on restore.");

        CalculatorViewModel reusedView = presenter.onReuseLastResult("", "");
        assertEquals("0,3", reusedView.getFirstInput(), "Reused values should follow the normalized precision policy.");
    }
}
