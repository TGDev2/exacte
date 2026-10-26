package calc.testing;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Runs a piece of test code in a fresh temporary directory and removes the directory afterwards, whatever happens. */
public final class TempDirectories {
    @FunctionalInterface
    public interface Body {
        void run(Path directory) throws Exception;
    }

    private TempDirectories() {
    }

    public static void with(Body body) throws Exception {
        Path directory = Files.createTempDirectory("calc-properties-");
        try {
            body.run(directory);
        } finally {
            delete(directory);
        }
    }

    private static void delete(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
