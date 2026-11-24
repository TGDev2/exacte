package calc.detail;

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

/**
 * calc.detail writes the detail of a line for the desktop and for the web page (ADR-D, docs/ARCHITECTURE.md): it stands on
 * the notebook, the expressions and the formatter, and on nothing of an interface, of the files or of the documents.
 */
class DetailDependenciesTest {
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final List<String> FORBIDDEN = List.of("javax.swing", "java.awt", "java.nio", "calc.ui", "calc.persistence",
            "calc.document", "calc.export", "calc.engine");

    @Test
    void theDetailDependsOnlyOnTheCoreBelowIt() throws IOException {
        List<String> imports = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src", "main", "java", "calc", "detail"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    imports.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertTrue(imports.size() > 5, "the scan must see the imports of calc.detail: " + imports);
        List<String> forbidden = imports.stream()
                .filter(entry -> FORBIDDEN.stream().anyMatch(prefix -> entry.substring(entry.indexOf(": ") + 2).startsWith(prefix)))
                .toList();
        assertEquals(List.of(), forbidden);
    }
}
