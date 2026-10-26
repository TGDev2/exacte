package calc.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AtomicFileWriterTest {
    private static final byte[] ORIGINAL = "original\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] REPLACEMENT = "replacement\n".getBytes(StandardCharsets.UTF_8);

    private final List<LogRecord> logs = new ArrayList<>();
    private final Handler handler = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logs.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger logger = Logger.getLogger(AtomicFileWriter.class.getName());

    @BeforeEach
    void attachHandler() {
        logger.addHandler(handler);
    }

    @AfterEach
    void detachHandler() {
        logger.removeHandler(handler);
    }

    @Test
    void testWritesANewFileAndLeavesNoTemporaryFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");

        AtomicFileWriter.write(target, REPLACEMENT);

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(List.of("data.tsv"), fileNames(directory), "No temporary file may survive a successful write.");
    }

    @Test
    void testReplacesAnExistingFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        Files.write(target, ORIGINAL);

        AtomicFileWriter.write(target, REPLACEMENT);

        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(List.of("data.tsv"), fileNames(directory));
    }

    @Test
    void testFailureWhileWritingLeavesTheOriginalIntactAndRemovesTheTemporaryFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        Files.write(target, ORIGINAL);
        IOException crash = new IOException("disk full");

        IOException thrown = assertThrows(IOException.class, () -> AtomicFileWriter.write(
                target,
                out -> {
                    out.write(REPLACEMENT, 0, 4);
                    throw crash;
                },
                Files::move));

        assertSame(crash, thrown, "The cause of the failure must reach the caller.");
        assertArrayEquals(ORIGINAL, Files.readAllBytes(target), "A half-written file must never replace the original.");
        assertEquals(List.of("data.tsv"), fileNames(directory), "The temporary file must be deleted.");
    }

    @Test
    void testFailureWhileMovingLeavesTheOriginalIntactAndRemovesTheTemporaryFile(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        Files.write(target, ORIGINAL);

        assertThrows(IOException.class, () -> AtomicFileWriter.write(
                target,
                out -> out.write(REPLACEMENT),
                (source, destination, options) -> {
                    throw new IOException("access denied");
                }));

        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
        assertEquals(List.of("data.tsv"), fileNames(directory));
    }

    @Test
    void testUnwritableTemporaryFileLeavesTheOriginalIntact(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        Files.write(target, ORIGINAL);
        Files.createDirectory(AtomicFileWriter.temporaryFileFor(target));

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, REPLACEMENT));

        assertArrayEquals(ORIGINAL, Files.readAllBytes(target));
    }

    @Test
    void testFileSystemWithoutAtomicMoveFallsBackToReplaceAndLogsIt(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        Files.write(target, ORIGINAL);
        List<List<CopyOption>> attempts = new ArrayList<>();

        AtomicFileWriter.write(target, out -> out.write(REPLACEMENT), (source, destination, options) -> {
            attempts.add(List.of(options));
            if (List.of(options).contains(StandardCopyOption.ATOMIC_MOVE)) {
                throw new AtomicMoveNotSupportedException(source.toString(), destination.toString(), "not supported");
            }
            Files.move(source, destination, options);
        });

        assertEquals(
                List.of(
                        List.of(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING),
                        List.of(StandardCopyOption.REPLACE_EXISTING)),
                attempts,
                "ATOMIC_MOVE is tried first, then REPLACE_EXISTING alone.");
        assertArrayEquals(REPLACEMENT, Files.readAllBytes(target));
        assertEquals(List.of("data.tsv"), fileNames(directory));
        assertTrue(
                logs.stream().anyMatch(record -> record.getLevel().intValue() >= Level.WARNING.intValue()
                        && record.getMessage().contains(target.toString())),
                "The fallback must be logged with the path.");
    }

    @Test
    void testAtomicMoveIsUsedWhenAvailable(@TempDir Path directory) throws Exception {
        Path target = directory.resolve("data.tsv");
        List<List<CopyOption>> attempts = new ArrayList<>();

        AtomicFileWriter.write(target, out -> out.write(REPLACEMENT), (source, destination, options) -> {
            attempts.add(List.of(options));
            Files.move(source, destination, options);
        });

        assertEquals(1, attempts.size());
        assertTrue(attempts.get(0).contains(StandardCopyOption.ATOMIC_MOVE));
        assertTrue(attempts.get(0).contains(StandardCopyOption.REPLACE_EXISTING));
        assertFalse(logs.stream().anyMatch(record -> record.getLevel().intValue() >= Level.WARNING.intValue()),
                "Nothing to report when the atomic move works.");
    }

    private static List<String> fileNames(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }
}
