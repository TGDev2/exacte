package calc.ui;

import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.persistence.JsonHistoryStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorHistoryPersistenceTest {
    // A session is one window: it holds the lock of the history until it ends, as the application does.
    private static CalculatorPresenter session(JsonHistoryStore store) {
        return new CalculatorPresenter(new CalculatorEngine(), store);
    }

    @Test
    void testHistoryPersistsAcrossSessions(@TempDir Path tempDirectory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            session(store).onCalculate("2", "3");
        }

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter secondSession = session(store);
            CalculatorViewModel restoredView = secondSession.createInitialView();

            assertEquals(StatusTone.SUCCESS, restoredView.statusTone(), "Restoring history should expose a success status.");
            assertEquals(1, restoredView.historyEntries().size(), "Restored sessions should recover persisted history entries.");
            assertEquals("2 + 3 = 5", restoredView.historyEntries().get(0).toString(), "Restored history should remain readable.");

            CalculatorViewModel reusedView = secondSession.onReuseLastResult("", "");
            assertEquals("5", reusedView.firstInput(), "The restored latest result should be reusable immediately.");
            assertTrue(reusedView.clearHistoryEnabled(), "Restored history should keep the clear action available.");

            CalculatorViewModel clearedView = secondSession.onClearHistory();
            assertEquals(0, clearedView.historyEntries().size(), "Clearing history should empty the current session.");
            assertFalse(clearedView.clearHistoryEnabled(), "Clearing history should disable the clear action.");
        }

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorViewModel emptyView = session(store).createInitialView();
            assertEquals(0, emptyView.historyEntries().size(), "Cleared history should stay empty after a relaunch.");
            assertEquals(StatusTone.INFO, emptyView.statusTone(), "Empty history after relaunch should fall back to the informational state.");
        }
    }

    @Test
    void testInvalidPersistedHistoryDoesNotBlockStartup(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.json");
        Files.writeString(historyFile, "bad-data", StandardCharsets.UTF_8);

        CalculatorViewModel viewModel;
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            viewModel = session(store).createInitialView();
        }

        assertEquals(0, viewModel.historyEntries().size(), "Invalid persisted history should be ignored at startup.");
        assertEquals(StatusTone.ERROR, viewModel.statusTone(), "Invalid persisted history should surface as a non-blocking error.");
        try (Stream<Path> files = Files.list(tempDirectory)) {
            Path backup = files.filter(path -> path.getFileName().toString().endsWith(".bak")).findFirst().orElseThrow();
            assertEquals(
                    "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage."
                            + " Copie de sauvegarde : " + backup,
                    viewModel.statusMessage(),
                    "The startup message should say that the history is unavailable and where the copy is.");
        }
    }

    @Test
    void testExactResultStaysExactAcrossPersistence(@TempDir Path tempDirectory) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter firstSession = session(store);
            firstSession.onOperationChanged(Operation.DIVISION, "1", "3");
            firstSession.onCalculate("1", "3");
        }

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter secondSession = session(store);
            CalculatorViewModel restoredView = secondSession.createInitialView();

            assertEquals(
                    "1 / 3 = 0,333333333333…",
                    restoredView.historyEntries().get(0).toString(),
                    "The entry reads the same after relaunch.");

            CalculatorViewModel reusedView = secondSession.onReuseLastResult("", "");
            assertEquals("1/3", reusedView.firstInput(), "The reused value after relaunch is still the exact value.");
            secondSession.onOperationChanged(Operation.MULTIPLICATION, reusedView.firstInput(), "");
            assertEquals("1", secondSession.onCalculate(reusedView.firstInput(), "3").resultValue(), "(1 / 3) * 3 is still exactly 1.");
        }
    }

    @Test
    void testApproximateResultKeepsItsIntervalAcrossPersistence(@TempDir Path tempDirectory) throws Exception {
        CalculatorViewModel squaredBefore;
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter firstSession = session(store);
            firstSession.onOperationChanged(Operation.ROOT, "2", "");
            CalculatorViewModel root = firstSession.onCalculate("2", "");
            CalculatorViewModel power = firstSession.onOperationChanged(Operation.POWER, root.firstInput(), "");
            squaredBefore = firstSession.onCalculate(power.firstInput(), "2");
        }
        assertFalse(squaredBefore.resultExact(), "The square of the remembered root is an interval.");

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter secondSession = session(store);
            CalculatorViewModel restoredView = secondSession.createInitialView();
            assertEquals(2, restoredView.historyEntries().size());
            assertFalse(restoredView.historyEntries().get(0).isExact(), "The restored root is still an interval.");

            // The restored root is reused as a value, not as its text: the square is the same interval as before the relaunch.
            secondSession.onHistorySelectionChanged(0);
            CalculatorViewModel reused = secondSession.onReuseHistoryResult(ReuseTarget.FIRST_NUMBER, "", "");
            CalculatorViewModel power = secondSession.onOperationChanged(Operation.POWER, reused.firstInput(), "");
            CalculatorViewModel squaredAfter = secondSession.onCalculate(power.firstInput(), "2");
            assertEquals(squaredBefore.resultValue(), squaredAfter.resultValue());
            assertEquals(squaredBefore.resultExact(), squaredAfter.resultExact());
            assertEquals(squaredBefore.resultDetail(), squaredAfter.resultDetail(), "Same error bound.");
        }
    }

    @Test
    void testLegacyFloatingHistoryIsNormalizedOnRestore(@TempDir Path tempDirectory) throws Exception {
        Files.writeString(tempDirectory.resolve("history.tsv"), "ADDITION\t0,1\t0,2\t0.30000000000000004", StandardCharsets.UTF_8);

        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            CalculatorPresenter presenter = session(store);
            CalculatorViewModel viewModel = presenter.createInitialView();

            assertEquals(1, viewModel.historyEntries().size(), "Legacy floating histories should still restore.");
            assertEquals(
                    "0,1 + 0,2 = 0,3",
                    viewModel.historyEntries().get(0).toString(),
                    "Legacy floating artifacts should be normalized on restore.");

            CalculatorViewModel reusedView = presenter.onReuseLastResult("", "");
            assertEquals("0,3", reusedView.firstInput(), "Reused values should follow the normalized precision policy.");
        }
    }
}
