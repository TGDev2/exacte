package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The temporary file of an atomic write never takes the place of a file that was already there. */
class TemporaryNameCollisionTest {
    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    private static AtomicFileWriter.Content text(String text) {
        return out -> out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aFileOfTheUserNamedLikeTheTemporaryFileSurvivesTheWrite(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("doc.calc.json");
        Path mine = Files.writeString(directory.resolve("doc.calc.json.tmp"), "my notes");

        AtomicFileWriter.write(target, text("document"), Files::move);

        assertEquals("document", Files.readString(target));
        assertEquals("my notes", Files.readString(mine));
        assertEquals(List.of("doc.calc.json", "doc.calc.json.tmp"), names(directory));
    }

    @Test
    void aFailedWriteRemovesOnlyItsOwnTemporaryFile(@TempDir Path directory) throws IOException {
        Path target = directory.resolve("doc.calc.json");
        Path mine = Files.writeString(directory.resolve("doc.calc.json.tmp"), "my notes");

        assertThrows(IOException.class, () -> AtomicFileWriter.write(target, text("document"), (source, destination, options) -> {
            throw new IOException("simulated disk failure");
        }));

        assertEquals("my notes", Files.readString(mine));
        assertEquals(List.of("doc.calc.json.tmp"), names(directory));
    }
}
