package calc.testing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Tells whether the file system of a folder ignores the case of names, so that a test does not assume either answer. */
public final class CaseSensitivity {
    private CaseSensitivity() {
    }

    /**
     * Writes a witness file in {@code folder}, then asks whether the same name in capitals designates it. The witness is
     * removed before the method returns, whatever the answer.
     */
    public static boolean ignoresCase(Path folder) throws IOException {
        Path witness = Files.createTempFile(folder, "case-witness-", ".tmp");
        try {
            String capitals = witness.getFileName().toString().toUpperCase(Locale.ROOT);
            return Files.exists(folder.resolve(capitals));
        } finally {
            Files.deleteIfExists(witness);
        }
    }
}
