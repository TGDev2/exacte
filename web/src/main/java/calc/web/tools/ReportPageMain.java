package calc.web.tools;

import calc.web.ReportPage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts the conformity report on the site: {@code rapport-conformite.html} (see {@link ReportPage}) and a copy of the
 * Markdown, {@code rapport-conformite.md}, next to {@code index.html}. It runs on the build machine, from the Gradle task
 * of {@code web/build.gradle.kts}, and is never reached from WebExports.
 */
public final class ReportPageMain {
    static final String HTML_FILE = "rapport-conformite.html";
    static final String MARKDOWN_FILE = "rapport-conformite.md";

    private ReportPageMain() {
    }

    /**
     * Writes the two files into {@code siteDirectory}.
     *
     * @throws IOException if the report is missing, empty or unreadable; nothing is written then
     */
    public static void write(Path report, Path siteDirectory) throws IOException {
        if (!Files.isRegularFile(report)) {
            throw new IOException("Conformity report not found: " + report);
        }
        byte[] bytes = Files.readAllBytes(report);
        if (bytes.length == 0) {
            throw new IOException("Conformity report is empty: " + report);
        }
        String page = ReportPage.render(new String(bytes, StandardCharsets.UTF_8));
        Files.write(siteDirectory.resolve(MARKDOWN_FILE), bytes);
        Files.writeString(siteDirectory.resolve(HTML_FILE), page, StandardCharsets.UTF_8);
    }

    /** Arguments: the report, then the directory of the site. */
    public static void main(String[] arguments) throws IOException {
        if (arguments.length != 2) {
            throw new IllegalArgumentException("Usage: ReportPageMain <report> <site directory>");
        }
        write(Path.of(arguments[0]), Path.of(arguments[1]));
    }
}
