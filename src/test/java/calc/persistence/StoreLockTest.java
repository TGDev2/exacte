package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.HistoryLoadResult;
import calc.engine.HistoryLoadResult.Condition;
import calc.engine.Operation;
import calc.testing.RecordGenerators;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreLockTest {
    private static final CalculationRecord ONE_PLUS_ONE = RecordGenerators.calculated(Operation.ADDITION, "1", "1");
    private static final CalculationRecord TWO_PLUS_TWO = RecordGenerators.calculated(Operation.ADDITION, "2", "2");

    @Test
    void theSecondStoreOnTheSameDirectoryIsReadOnlyAndWritesNothing(@TempDir Path directory) throws Exception {
        try (JsonHistoryStore first = JsonHistoryStore.open(directory)) {
            first.saveHistory(List.of(ONE_PLUS_ONE));
            byte[] written = Files.readAllBytes(directory.resolve("history.json"));
            assertFalse(first.isReadOnly());

            try (JsonHistoryStore second = JsonHistoryStore.open(directory)) {
                assertTrue(second.isReadOnly());
                HistoryLoadResult loaded = second.loadHistory();
                assertEquals(Condition.IN_USE_READ_ONLY, loaded.condition());
                assertEquals(List.of("1 + 1 = 2"), loaded.records().stream().map(CalculationRecord::toString).toList(),
                        "The second window can load the history.");

                second.saveHistory(List.of(TWO_PLUS_TWO));
                second.saveHistory(List.of());

                assertArrayEquals(written, Files.readAllBytes(directory.resolve("history.json")), "Nothing was written.");
                assertEquals(List.of("history.json", "history.lock"), fileNames(directory), "No temporary file, no backup.");
            }

            first.saveHistory(List.of(ONE_PLUS_ONE, TWO_PLUS_TWO));
            assertEquals(2, HistoryCodec.decode(Files.readAllBytes(directory.resolve("history.json"))).records().size(),
                    "The first instance still writes, and closing the second released nothing it did not hold.");
        }
    }

    @Test
    void aReadOnlyStoreDoesNotMigrateNorBackUp(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("history.tsv"), "ADDITION\t1\t2\t3\nbad\n", StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(directory.resolve("history.tsv"));

        try (JsonHistoryStore first = JsonHistoryStore.open(directory);
                JsonHistoryStore second = JsonHistoryStore.open(directory)) {
            HistoryLoadResult loaded = second.loadHistory();

            assertEquals(Condition.IN_USE_READ_ONLY, loaded.condition());
            assertEquals(1, loaded.records().size(), "It reads the version 1 file.");
            assertEquals(1, loaded.rejections().size());
            assertArrayEquals(original, Files.readAllBytes(directory.resolve("history.tsv")), "It does not rename it.");
            assertEquals(List.of("history.lock", "history.tsv"), fileNames(directory), "It creates no JSON file, no backup.");
            assertFalse(first.isReadOnly());
        }
    }

    @Test
    void closingTheStoreReleasesTheLock(@TempDir Path directory) throws Exception {
        JsonHistoryStore first = JsonHistoryStore.open(directory);
        assertFalse(first.isReadOnly());
        first.close();

        try (JsonHistoryStore second = JsonHistoryStore.open(directory)) {
            assertFalse(second.isReadOnly(), "The lock is free again.");
        }
    }

    @Test
    void anOtherProcessHoldsTheLockAndTheLockDiesWithItWhenItIsKilled(@TempDir Path directory) throws Exception {
        Process holder = new ProcessBuilder(javaCommand(), "-cp", System.getProperty("java.class.path"),
                LockHolder.class.getName(), directory.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertEquals("LOCKED", firstLine(holder), "The other process took the lock.");

            try (JsonHistoryStore blocked = JsonHistoryStore.open(directory)) {
                assertTrue(blocked.isReadOnly(), "An other process holds the history: read only.");
                blocked.saveHistory(List.of(ONE_PLUS_ONE));
                assertFalse(Files.exists(directory.resolve("history.json")), "And it wrote nothing.");
            }

            // A crash: the process is killed without any chance to release anything.
            holder.destroyForcibly();
            assertTrue(holder.waitFor(30, TimeUnit.SECONDS), "The process ended.");

            // The system closes the handles of a dead process a moment after the process ends: wait for it, but not for long.
            long start = System.nanoTime();
            JsonHistoryStore afterTheCrash = JsonHistoryStore.open(directory);
            while (afterTheCrash.isReadOnly() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(10)) {
                afterTheCrash.close();
                Thread.sleep(50);
                afterTheCrash = JsonHistoryStore.open(directory);
            }
            try (JsonHistoryStore reopened = afterTheCrash) {
                System.out.println("[lock] taken again " + (System.nanoTime() - start) / 1_000_000 + " ms after the killed process ended");
                assertFalse(reopened.isReadOnly(), "An orphan lock file does not block: the system released the lock.");
                reopened.saveHistory(List.of(ONE_PLUS_ONE));
                assertTrue(Files.exists(directory.resolve("history.json")));
            }
            assertTrue(Files.exists(directory.resolve("history.lock")), "The lock file itself was left behind by the crash.");
        } finally {
            holder.destroyForcibly();
        }
    }

    private static String firstLine(Process process) throws Exception {
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.readLine();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        String text = line.get(60, TimeUnit.SECONDS);
        assertNotNull(text, "The other process printed nothing.");
        return text;
    }

    private static String javaCommand() {
        return Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
                .toString();
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
