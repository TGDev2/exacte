package calc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.web.tools.CharacterCompatGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The table that is committed is the one the generator gives on the JDK of the core, not an edited copy. */
class CharacterCompatGeneratorTest {
    private static final Path GENERATED = Path.of("web", "src", "main", "java", "calc", "web", "compat", "CharacterCompat.java");

    @Test
    void theCommittedClassIsWhatTheGeneratorGives() throws IOException {
        // The working tree may hold CRLF: line endings say nothing about the table.
        String committed = Files.readString(GENERATED, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertEquals(CharacterCompatGenerator.source(), committed, "Run gradlew :web:generateCharacterCompat.");
    }

    @Test
    void theGeneratorNeedsTheFileToWrite() {
        assertThrows(IllegalArgumentException.class, () -> CharacterCompatGenerator.main(new String[0]));
    }
}
