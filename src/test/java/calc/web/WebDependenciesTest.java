package calc.web;

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
 * What TeaVM compiles stays what a browser can run. Only the files directly in calc.web are read: compat runs inside the
 * compiler and tools on the build machine, neither is reached from WebExports.
 */
class WebDependenciesTest {
    private static final Path SOURCES = Path.of("web", "src", "main", "java", "calc", "web");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);
    private static final List<String> FORBIDDEN = List.of("java.nio.", "java.awt.", "javax.swing.", "calc.persistence.",
            "calc.document.", "calc.export.", "calc.ui.");

    @Test
    void theWebPackageImportsNothingThatCannotRunInABrowser() throws IOException {
        List<String> imports = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = IMPORT.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    imports.add(file.getFileName() + ": " + matcher.group(1));
                }
            }
        }
        assertTrue(imports.size() > 10, "the scan must see the imports of the package: " + imports);
        List<String> forbidden = imports.stream()
                .filter(entry -> FORBIDDEN.stream().anyMatch(prefix -> entry.substring(entry.indexOf(": ") + 2).startsWith(prefix)))
                .toList();
        assertEquals(List.of(), forbidden);
    }
}
