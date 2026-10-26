package calc.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.RejectedLine.Cause;
import calc.testing.HistoryFileGenerators;
import calc.testing.HistoryFileGenerators.ExpectedRejection;
import calc.testing.HistoryFileGenerators.HistoryFile;
import calc.testing.TempDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Fuzzing of the history loader. Whatever the content of the file:
 * <ul>
 *   <li>the only exception is the one announced for an I/O error ({@link CalculationHistoryException});
 *   <li>the valid lines are restored, in order, and every rejected line is reported with its number and its cause;
 *   <li>loading never alters the file, and the original is copied before anything can be written over it;
 *   <li>when no copy could be made, or the file is unreadable or newer, writing is refused.
 * </ul>
 */
@Tag("robustness")
class HistoryLoaderFuzzProperties {
    private static final String FILE_NAME = "history.tsv";

    @Provide
    Arbitrary<HistoryFile> structured() {
        return HistoryFileGenerators.structuredFiles();
    }

    @Provide
    Arbitrary<HistoryFile> anyFile() {
        return HistoryFileGenerators.anyFile();
    }

    @Provide
    Arbitrary<HistoryFile> futureVersion() {
        return HistoryFileGenerators.futureVersionFiles();
    }

    @Property(tries = 400)
    void validLinesAreRestoredAndEveryRejectionIsReported(@ForAll("structured") HistoryFile content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = write(directory, content);

            HistoryLoadResult result = new FileCalculationHistoryStore(file).loadHistory();

            assertEquals(content.expectedEntries(), result.records().stream().map(CalculationRecord::toString).toList(),
                    "The valid lines, in order.");
            assertEquals(content.expectedRejections(),
                    result.rejections().stream().map(rejection -> new ExpectedRejection(rejection.lineNumber(), rejection.cause())).toList(),
                    "Line number and cause of each rejected line.");
            assertEquals(content.expectedRejections().size(), result.skippedLines());
            assertUntouchedAndBackedUpIfRejected(directory, file, content, result.rejections().isEmpty(), result.backupFile());
        });
    }

    @Property(tries = 400)
    void anyContentGivesAResultOrTheAnnouncedIoException(@ForAll("anyFile") HistoryFile content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = write(directory, content);
            FileCalculationHistoryStore store = new FileCalculationHistoryStore(file);

            HistoryLoadResult result = null;
            CalculationHistoryException ioFailure = null;
            try {
                result = store.loadHistory();
            } catch (CalculationHistoryException exception) {
                ioFailure = exception;
            }

            if (result != null) {
                assertUntouchedAndBackedUpIfRejected(directory, file, content, result.rejections().isEmpty(), result.backupFile());
                for (RejectedLine rejection : result.rejections()) {
                    assertNotNull(rejection.cause());
                    assertTrue(rejection.lineNumber() >= 1);
                    assertFalse(rejection.detail().isEmpty());
                }
                assertWritingNeverLosesTheOriginal(file, content, result);
            } else {
                assertNotNull(ioFailure.getBackupFile(), "An unreadable file is copied before anything else happens.");
                assertArrayEquals(content.bytes(), Files.readAllBytes(ioFailure.getBackupFile()), "The copy is the original.");
                assertArrayEquals(content.bytes(), Files.readAllBytes(file), "Loading never alters the file.");
                assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()),
                        "Nothing is written over a file that could not be read.");
                assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(new CalculatorEngine().calculate(Operation.ADDITION, "1", "2"))));
                assertArrayEquals(content.bytes(), Files.readAllBytes(file), "A refused write leaves the file as it was.");
            }
        });
    }

    @Property(tries = 200)
    void aNewerFormatIsKeptAndNeverOverwritten(@ForAll("futureVersion") HistoryFile content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = write(directory, content);
            FileCalculationHistoryStore store = new FileCalculationHistoryStore(file);

            HistoryLoadResult result = store.loadHistory();

            assertTrue(result.records().isEmpty(), "Nothing is read from a format this program does not know.");
            assertEquals(content.expectedRejections(),
                    result.rejections().stream().map(rejection -> new ExpectedRejection(rejection.lineNumber(), rejection.cause())).toList());
            assertTrue(result.rejections().stream().allMatch(rejection -> rejection.cause() == Cause.UNSUPPORTED_FORMAT_VERSION));
            assertNotNull(result.backupFile());
            assertArrayEquals(content.bytes(), Files.readAllBytes(result.backupFile()));
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()), "Writing is refused.");
            assertThrows(CalculationHistoryException.class,
                    () -> store.saveHistory(List.of(new CalculatorEngine().calculate(Operation.ADDITION, "1", "2"))));
            assertArrayEquals(content.bytes(), Files.readAllBytes(file), "The newer file is untouched.");
        });
    }

    /**
     * Regression found by {@code aNewerFormatIsKeptAndNeverOverwritten}: the smallest version that does not fit in an
     * int made the loader throw a NumberFormatException instead of treating the file as a newer format.
     */
    @Example
    void aVersionTooLargeForAnIntIsJustANewerVersion() throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve(FILE_NAME);
            byte[] original = "#calc-history v2147483648\nADDITION\t1\t2\t3\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file, original);
            FileCalculationHistoryStore store = new FileCalculationHistoryStore(file);

            HistoryLoadResult result = store.loadHistory();

            assertTrue(result.records().isEmpty());
            assertEquals(List.of(new ExpectedRejection(2, Cause.UNSUPPORTED_FORMAT_VERSION)),
                    result.rejections().stream().map(rejection -> new ExpectedRejection(rejection.lineNumber(), rejection.cause())).toList());
            assertArrayEquals(original, Files.readAllBytes(result.backupFile()));
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()));
            assertArrayEquals(original, Files.readAllBytes(file));
        });
    }

    @Property(tries = 300)
    void whenTheCopyFailsNothingIsEverWritten(@ForAll("structured") HistoryFile content) throws Exception {
        Assume.that(!content.expectedRejections().isEmpty());
        TempDirectories.with(directory -> {
            Path file = write(directory, content);
            FileCalculationHistoryStore store = new FileCalculationHistoryStore(
                    file, Clock.systemDefaultZone(), (source, target) -> {
                        throw new IOException("disk full");
                    });

            HistoryLoadResult result = store.loadHistory();

            assertNull(result.backupFile(), "No copy could be made.");
            assertEquals(content.expectedRejections().size(), result.skippedLines());
            CalculationRecord record = new CalculatorEngine().calculate(Operation.ADDITION, "1", "2");
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(record)), "Writing is refused without a copy.");
            assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of()));
            assertArrayEquals(content.bytes(), Files.readAllBytes(file), "The original is intact.");
            assertEquals(List.of(FILE_NAME), names(directory), "No copy, no temporary file.");
        });
    }

    /** Loading wrote nothing but the copy, and the copy holds the original exactly when something was rejected. */
    private static void assertUntouchedAndBackedUpIfRejected(
            Path directory, Path file, HistoryFile content, boolean nothingRejected, Path backupFile) throws IOException {
        assertArrayEquals(content.bytes(), Files.readAllBytes(file), "Loading never alters the file.");
        if (nothingRejected) {
            assertNull(backupFile, "A clean file is not copied.");
            assertEquals(List.of(FILE_NAME), names(directory));
        } else {
            assertNotNull(backupFile, "A file with rejected lines is copied first.");
            assertArrayEquals(content.bytes(), Files.readAllBytes(backupFile), "The copy is the original, byte for byte.");
            assertEquals(2, names(directory).size(), "The file and its copy, nothing else.");
        }
    }

    /** Saving after a load either keeps the original in a copy or leaves nothing changed. */
    private static void assertWritingNeverLosesTheOriginal(Path file, HistoryFile content, HistoryLoadResult result) throws Exception {
        FileCalculationHistoryStore store = new FileCalculationHistoryStore(file);
        HistoryLoadResult reloaded = store.loadHistory();
        try {
            store.saveHistory(reloaded.records());
        } catch (CalculationHistoryException refused) {
            assertArrayEquals(content.bytes(), Files.readAllBytes(file), "A refused write leaves the file as it was.");
            return;
        }
        if (!result.rejections().isEmpty()) {
            assertNotNull(reloaded.backupFile(), "Overwriting a file with rejected lines needs a copy.");
            assertArrayEquals(content.bytes(), Files.readAllBytes(reloaded.backupFile()), "The original survives in the copy.");
        }
    }

    private static Path write(Path directory, HistoryFile content) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        Files.write(file, content.bytes());
        return file;
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
