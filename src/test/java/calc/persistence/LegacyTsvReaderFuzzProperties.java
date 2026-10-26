package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.testing.HistoryFileGenerators;
import calc.testing.HistoryFileGenerators.ExpectedRejection;
import calc.testing.HistoryFileGenerators.HistoryFile;
import calc.testing.TempDirectories;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Fuzzing of the version 1 reader, kept for the migration. Whatever the content of the file:
 * <ul>
 *   <li>the only exceptions are {@link HistoryFormatException} (not UTF-8, too large) and the I/O exception;
 *   <li>the valid lines are restored, in order, and every rejected line is reported with its number and its cause;
 *   <li>a header newer than 1 reads nothing and rejects nothing, it only reports the version;
 *   <li>reading never alters the file and writes nothing next to it.
 * </ul>
 */
@Tag("robustness")
class LegacyTsvReaderFuzzProperties {
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

            LegacyTsvReader.LegacyHistory result = LegacyTsvReader.read(file);

            assertEquals(content.expectedEntries(), result.records().stream().map(CalculationRecord::toString).toList(),
                    "The valid lines, in order.");
            assertEquals(content.expectedRejections(),
                    result.rejections().stream().map(rejection -> new ExpectedRejection(rejection.lineNumber(), rejection.cause())).toList(),
                    "Line number and cause of each rejected line.");
            assertTrue(result.newerVersion().isEmpty());
            assertUntouched(directory, file, content);
        });
    }

    @Property(tries = 400)
    void anyContentGivesAResultOrAnAnnouncedException(@ForAll("anyFile") HistoryFile content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = write(directory, content);

            try {
                LegacyTsvReader.LegacyHistory result = LegacyTsvReader.read(file);
                for (var rejection : result.rejections()) {
                    assertNotNull(rejection.cause());
                    assertTrue(rejection.lineNumber() >= 1);
                    assertFalse(rejection.detail().isEmpty());
                }
            } catch (HistoryFormatException expected) {
                assertNotNull(expected.reason());
            }
            assertUntouched(directory, file, content);
        });
    }

    @Property(tries = 200)
    void aNewerVersionReadsNothingAndRejectsNothing(@ForAll("futureVersion") HistoryFile content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = write(directory, content);

            LegacyTsvReader.LegacyHistory result = LegacyTsvReader.read(file);

            assertTrue(result.newerVersion().isPresent(), content.shape());
            assertTrue(result.records().isEmpty());
            assertTrue(result.rejections().isEmpty());
            assertUntouched(directory, file, content);
        });
    }

    /** A version too large for an int is just a newer version, not an exception (found by the former loader's fuzzing). */
    @Example
    void aVersionTooLargeForAnIntIsJustANewerVersion() throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve(FILE_NAME);
            Files.write(file, "#calc-history v2147483648\nADDITION\t1\t2\t3\n".getBytes(StandardCharsets.UTF_8));

            LegacyTsvReader.LegacyHistory result = LegacyTsvReader.read(file);

            assertEquals(Integer.MAX_VALUE, result.newerVersion().getAsInt());
            assertTrue(result.records().isEmpty());
        });
    }

    private static void assertUntouched(Path directory, Path file, HistoryFile content) throws IOException {
        assertArrayEquals(content.bytes(), Files.readAllBytes(file), "Reading never alters the file.");
        try (Stream<Path> files = Files.list(directory)) {
            assertEquals(List.of(FILE_NAME), files.map(path -> path.getFileName().toString()).toList(), "Reading writes nothing.");
        }
    }

    private static Path write(Path directory, HistoryFile content) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        Files.write(file, content.bytes());
        return file;
    }
}
