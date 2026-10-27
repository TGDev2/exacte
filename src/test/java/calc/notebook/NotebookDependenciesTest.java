package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** The notebook model depends on no interface and no persistence: E12 and E11 plug onto it, not the reverse. */
class NotebookDependenciesTest {
    private static final Path SOURCES = Path.of("src", "main", "java", "calc", "notebook");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final List<String> ALLOWED = List.of("java.util.", "calc.engine.CalculationRecord",
            "calc.engine.NumberLiterals", "calc.engine.Operation", "calc.expression.", "calc.number.");

    @Test
    void theModelOnlyImportsTheLanguageTheCoreAndTheJavaCollections() throws IOException {
        List<String> imports = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertTrue(!source.contains("javax.swing") && !source.contains("java.awt")
                        && !source.contains("calc.ui") && !source.contains("calc.persistence"), file::toString);
                Matcher matcher = IMPORT.matcher(source);
                while (matcher.find()) {
                    imports.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertTrue(imports.size() > 10, "the scan must see the imports of the package: " + imports);
        List<String> unexpected = imports.stream()
                .filter(entry -> ALLOWED.stream().noneMatch(prefix -> entry.substring(entry.indexOf(": ") + 2).startsWith(prefix)))
                .toList();
        assertEquals(List.of(), unexpected);
    }
}
