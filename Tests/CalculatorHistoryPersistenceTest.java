package Tests;

import Calcul.CalculatorEngine;
import Calcul.FileCalculationHistoryStore;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorViewModel;
import Graphique.StatusTone;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class CalculatorHistoryPersistenceTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        testHistoryPersistsAcrossSessions();
        testInvalidPersistedHistoryDoesNotBlockStartup();

        System.out.println("All history persistence tests passed (" + assertions + " assertions).");
    }

    private static void testHistoryPersistsAcrossSessions() throws Exception {
        Path tempDirectory = Files.createTempDirectory("calc-history");
        try {
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
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private static void testInvalidPersistedHistoryDoesNotBlockStartup() throws Exception {
        Path tempDirectory = Files.createTempDirectory("calc-history-invalid");
        try {
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
                    "L'historique precedent n'a pas pu etre restaure. Vous pouvez continuer sans blocage.",
                    viewModel.getStatusMessage(),
                    "The startup message should explain that the user can continue without the previous history.");
        } finally {
            deleteRecursively(tempDirectory);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        Files.walk(path)
                .sorted(Comparator.reverseOrder())
                .forEach(currentPath -> {
                    try {
                        Files.deleteIfExists(currentPath);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " Expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        assertions++;
        if (condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
