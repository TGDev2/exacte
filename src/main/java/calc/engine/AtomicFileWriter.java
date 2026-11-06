package calc.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Replaces a file without ever exposing a half-written version of it.
 *
 * <p>The content goes to {@code <name>.tmp} in the same directory (so the final move stays on one file system), is
 * forced to disk, then moved over the target with {@code ATOMIC_MOVE | REPLACE_EXISTING}. When the file system cannot
 * move atomically the move falls back to {@code REPLACE_EXISTING} and the fallback is logged. On any error the
 * temporary file is deleted and the target is left exactly as it was.
 *
 * <p>Reusable by every persistence format; it knows nothing about the history.
 */
public final class AtomicFileWriter {
    private static final Logger LOGGER = Logger.getLogger(AtomicFileWriter.class.getName());
    private static final String TMP_SUFFIX = ".tmp";

    /** Produces the new content of the file. */
    @FunctionalInterface
    public interface Content {
        void writeTo(OutputStream out) throws IOException;
    }

    /** The move step, replaceable so that tests can simulate a failing or non-atomic file system. */
    @FunctionalInterface
    public interface Mover {
        void move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private AtomicFileWriter() {
    }

    public static void write(Path target, byte[] content) throws IOException {
        Objects.requireNonNull(content);
        write(target, out -> out.write(content), Files::move);
    }

    public static void write(Path target, Content content, Mover mover) throws IOException {
        Objects.requireNonNull(target);
        Objects.requireNonNull(content);
        Objects.requireNonNull(mover);
        Path tmpFile = createTemporaryFile(target);
        try {
            try (FileChannel channel = FileChannel.open(
                    tmpFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                    OutputStream out = Channels.newOutputStream(channel)) {
                content.writeTo(out);
                out.flush();
                channel.force(true);
            }
            moveIntoPlace(tmpFile, target, mover);
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(tmpFile, exception);
            throw exception;
        }
    }

    static Path temporaryFileFor(Path target) {
        return target.resolveSibling(target.getFileName() + TMP_SUFFIX);
    }

    // Creates it: a file already there, of the user or left by a crash, is never overwritten or deleted. A directory under
    // that name is not worked around: the write fails, and the target stays as it is.
    private static Path createTemporaryFile(Path target) throws IOException {
        Path candidate = temporaryFileFor(target);
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.createFile(candidate);
            } catch (FileAlreadyExistsException taken) {
                if (!Files.isRegularFile(candidate)) {
                    throw taken;
                }
                candidate = target.resolveSibling(target.getFileName() + "." + attempt + TMP_SUFFIX);
            }
        }
    }


    private static void moveIntoPlace(Path tmpFile, Path target, Mover mover) throws IOException {
        try {
            mover.move(tmpFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            LOGGER.log(Level.WARNING, "Atomic move not supported for " + target + ", replacing it non-atomically", exception);
            mover.move(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path tmpFile, Exception failure) {
        try {
            Files.deleteIfExists(tmpFile);
        } catch (IOException suppressed) {
            failure.addSuppressed(suppressed);
        }
    }
}
