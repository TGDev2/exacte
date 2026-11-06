package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first version 1 writer computed in {@code double} and stored {@code Double.toString} of the result: its calculations are
 * legitimate even where that text has more digits than the later rounding policies, and must reach the notebook.
 */
class LegacyDoubleResultTest {
    private static List<String> migrated(Path data, String... lines) throws Exception {
        Files.write(data.resolve("history.tsv"), List.of(lines));
        try (NotebookStore store = NotebookStore.open(data)) {
            store.load();
        }
        return NotebookCodec.read(data.resolve("carnet-courant.calc.json")).notebook().texts();
    }

    @Test
    void resultsWrittenAsDoublesAreMigrated(@TempDir Path data) throws Exception {
        List<String> texts = migrated(data,
                "ADDITION\t0,1\t0,2\t0.30000000000000004",
                "DIVISION\t1000000\t3\t333333.3333333333",
                "MULTIPLICATION\t123456789\t987654321\t1.2193263111263526E17",
                "SUBTRACTION\t1234567,1\t1234567\t0.10000000009313226",
                "ROOT\t2\t\t1.4142135623730951",
                "POWER\t1,1\t3\t1.3310000000000004");

        assertEquals(6, texts.size(), texts.toString());
        assertTrue(texts.get(1).contains("1000000"), texts.get(1));
        assertTrue(texts.get(2).contains("123456789"), texts.get(2));
    }

    @Test
    void aResultThatTheDoubleWriterCouldNotHaveWrittenIsStillRefused(@TempDir Path data) throws Exception {
        List<String> texts = migrated(data,
                "ADDITION\t1\t1\t2.0",
                "DIVISION\t1000000\t3\t333333.3333333334",
                "MULTIPLICATION\t123456789\t987654321\t1.2193263111263527E17");

        assertEquals(1, texts.size(), texts.toString());
    }
}
