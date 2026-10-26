package calc.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The exclusive lock that keeps two instances of Calc from writing the same history: an operating system lock
 * ({@link FileChannel#tryLock()}) on a dedicated file that holds no data. The system releases it when the process ends,
 * however it ends, so a crash leaves no lock behind; the file itself is left in place (deleting it would race with
 * another instance that has just opened it).
 */
final class StoreLock implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(StoreLock.class.getName());

    /** What happened when the lock was requested. */
    enum State {
        /** This instance holds the lock. */
        HELD,
        /** Another instance (or another store of this process) holds it. */
        BUSY,
        /** The lock file could not be opened or the file system does not lock: the store goes on without a lock. */
        UNAVAILABLE
    }

    private final State state;
    private final FileChannel channel;
    private final FileLock lock;

    private StoreLock(State state, FileChannel channel, FileLock lock) {
        this.state = state;
        this.channel = channel;
        this.lock = lock;
    }

    static StoreLock acquire(Path lockFile) {
        FileChannel channel = null;
        try {
            channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                LOGGER.info("History lock " + lockFile + " is held by another instance: read only");
                return new StoreLock(State.BUSY, null, null);
            }
            LOGGER.info("History lock " + lockFile + " acquired");
            return new StoreLock(State.HELD, channel, lock);
        } catch (OverlappingFileLockException sameProcess) {
            closeQuietly(channel);
            LOGGER.info("History lock " + lockFile + " is held by this process already: read only");
            return new StoreLock(State.BUSY, null, null);
        } catch (IOException | RuntimeException failure) {
            closeQuietly(channel);
            LOGGER.log(Level.WARNING, "Cannot lock " + lockFile + ", going on without a lock", failure);
            return new StoreLock(State.UNAVAILABLE, null, null);
        }
    }

    State state() {
        return state;
    }

    @Override
    public void close() {
        if (lock != null) {
            try {
                lock.release();
            } catch (IOException ignored) {
                // closing the channel releases it anyway
            }
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // nothing more can be done
            }
        }
    }
}
