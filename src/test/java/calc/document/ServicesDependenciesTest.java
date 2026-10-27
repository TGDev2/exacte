package calc.document;

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

/** E11 is services only: no interface in the persistence, document and export packages, and the notebook model stays below them. */
class ServicesDependenciesTest {
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    private static List<String> importsOf(String packageName) throws IOException {
        List<String> imports = new ArrayList<>();
        try (Stream<Path> files = Files.list(Path.of("src", "main", "java", "calc", packageName))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = IMPORT.matcher(source);
                while (matcher.find()) {
                    imports.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertTrue(imports.size() > 5, "the scan must see the imports of " + packageName + ": " + imports);
        return imports;
    }

    private static List<String> forbidden(String packageName, List<String> prefixes) throws IOException {
        return importsOf(packageName).stream()
                .filter(entry -> prefixes.stream().anyMatch(prefix -> entry.substring(entry.indexOf(": ") + 2).startsWith(prefix)))
                .toList();
    }

    @Test
    void noServicePackageDependsOnAnInterface() throws IOException {
        List<String> interfaces = List.of("javax.swing", "java.awt", "calc.ui");
        assertEquals(List.of(), forbidden("persistence", interfaces));
        assertEquals(List.of(), forbidden("document", interfaces));
        assertEquals(List.of(), forbidden("export", interfaces));
    }

    @Test
    void theLayersOnlyPointDownward() throws IOException {
        // export knows neither the files nor the documents; the document service does not reach into the evaluator or the parser.
        assertEquals(List.of(), forbidden("export", List.of("calc.persistence", "calc.document")));
        assertEquals(List.of(), forbidden("document", List.of("calc.expression", "calc.export")));
        assertEquals(List.of(), forbidden("notebook", List.of("calc.persistence", "calc.document", "calc.export")));
    }
}
