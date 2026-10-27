package calc.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.util.function.IntFunction;

/** Copies a file next to itself under a name that is not taken yet: an existing copy is never overwritten. */
final class BackupCopies {
    private static final int MAX_ATTEMPTS = 1000;

    private BackupCopies() {
    }

    /**
     * @param nameOfAttempt the file name of the copy for attempt 0, 1, 2... (the first free one is used)
     * @return the copy made
     * @throws IOException when the copy fails, or when {@value #MAX_ATTEMPTS} names are taken
     */
    static Path make(Path file, JsonHistoryStore.FileCopier copier, IntFunction<String> nameOfAttempt) throws IOException {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Path backup = file.resolveSibling(nameOfAttempt.apply(attempt));
            try {
                copier.copy(file, backup);
                return backup;
            } catch (FileAlreadyExistsException exists) {
                // never overwrite an existing copy: try the next name
            }
        }
        throw new IOException("Too many copies of " + file);
    }
}
