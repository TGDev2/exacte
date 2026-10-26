package calc.ui;

import calc.engine.CalculatorEngine;
import calc.persistence.JsonHistoryStore;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opens the window on every reference file of {@code legacy/v1} and pins the status the user reads after the migration. */
public class CalculatorHistoryLegacyFilesTest {
    private static final String CONVERTED = "Historique converti au nouveau format (";

    @ParameterizedTest(name = "{0}: {1} entries, status {2}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
        "nominal.tsv            | 6 | SUCCESS | Historique converti au nouveau format (6 entrées).",
        "rounded.tsv            | 1 | SUCCESS | Historique converti au nouveau format (1 entrée).",
        "legacy-float.tsv       | 1 | SUCCESS | Historique converti au nouveau format (1 entrée).",
        "crlf.tsv               | 3 | SUCCESS | Historique converti au nouveau format (3 entrées).",
        "bom.tsv                | 2 | SUCCESS | Historique converti au nouveau format (2 entrées).",
        "bom-header.tsv         | 1 | SUCCESS | Historique converti au nouveau format (1 entrée).",
        "empty.tsv              | 0 | SUCCESS | Historique converti au nouveau format (0 entrée).",
        "one-corrupted-line.tsv | 2 | ERROR   | Historique converti au nouveau format (2 entrées), 1 entrée illisible ignorée. Copie de sauvegarde : <BAK>",
        "inconsistent.tsv       | 0 | ERROR   | Historique converti au nouveau format (0 entrée), 1 entrée illisible ignorée. Copie de sauvegarde : <BAK>",
        "wrong-result.tsv       | 0 | ERROR   | Historique converti au nouveau format (0 entrée), 1 entrée incohérente ignorée. Copie de sauvegarde : <BAK>",
        "future-version.tsv     | 0 | ERROR   | Historique créé par une version plus récente de Calc : il n'a pas pu être ouvert et ne sera pas modifié.",
    })
    void testEveryReferenceFileGivesTheExpectedStatus(
            String fileName, int restored, StatusTone tone, String message, @TempDir Path directory) throws Exception {
        copyFixture(fileName, directory);

        CalculatorViewModel view;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            view = new CalculatorPresenter(new CalculatorEngine(), store).createInitialView();
        }

        assertEquals(restored, view.historyEntries().size(), "entries shown");
        assertEquals(tone, view.statusTone());
        assertEquals(message.replace("<BAK>", directory.resolve("history.tsv.v1.bak").toString()), view.statusMessage());
    }

    @Test
    void testMigratedHistoryKeepsWorkingAfterTheConversion(@TempDir Path directory) throws Exception {
        byte[] original = copyFixture("one-corrupted-line.tsv", directory);

        CalculatorViewModel afterCalculation;
        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), store);
            presenter.createInitialView();
            afterCalculation = presenter.onCalculate("10", "5");
        }

        assertEquals(StatusTone.SUCCESS, afterCalculation.statusTone());
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv.v1.bak")),
                "the backup holds the untouched original, corrupted line included");
        String json = Files.readString(directory.resolve("history.json"), StandardCharsets.UTF_8);
        assertEquals(3, json.lines().filter(line -> line.contains("\"op\":")).count(), "the two valid lines and the new calculation");
        assertTrue(json.contains("\"operands\": [\"10\", \"5\"]"));
        assertFalse(json.contains("not a calculation"));

        try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
            CalculatorViewModel reopened = new CalculatorPresenter(new CalculatorEngine(), store).createInitialView();
            assertEquals(List.of("1 + 2 = 3", "3 * 4 = 12", "10 + 5 = 15"),
                    reopened.historyEntries().stream().map(Object::toString).toList());
            assertEquals("Historique restauré. Vous pouvez réutiliser un résultat précédent ou lancer un nouveau calcul.",
                    reopened.statusMessage());
        }
    }

    private static byte[] copyFixture(String fileName, Path directory) throws IOException {
        try (InputStream stream = CalculatorHistoryLegacyFilesTest.class.getResourceAsStream("/legacy/v1/" + fileName)) {
            assertNotNull(stream, "missing reference file " + fileName);
            byte[] bytes = stream.readAllBytes();
            Files.write(directory.resolve("history.tsv"), bytes);
            return bytes;
        }
    }
}
