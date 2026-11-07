package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The conformity report as a page of the site, on the JVM: {@code ReportPage.render} turns the Markdown into HTML and
 * {@code ReportPageMain.write} puts the page and the Markdown next to {@code index.html}.
 *
 * <p>The classes are reached by reflection: while they do not exist, each test fails with its own message instead of the
 * whole test source set failing to compile. Two choices the contract leaves open, fixed here: the arguments of
 * {@code ReportPageMain.main} come in the order of {@code write} (the report, then the site directory), and a report of
 * zero bytes is the empty one ({@code test -s}, which the workflow runs, has the same idea).
 */
@Tag("acceptance")
@DisplayName("Rapport de conformité : la page du site, sur la JVM")
class ReportPageAcceptanceTest {
    private static final String PAGE = "calc.web.ReportPage";
    private static final String MAIN = "calc.web.tools.ReportPageMain";
    private static final String HTML_FILE = "rapport-conformite.html";
    private static final String MARKDOWN_FILE = "rapport-conformite.md";

    private static final Path WEB_SOURCES = Path.of("web", "src", "main", "java", "calc", "web");
    private static final Path WEB_BUILD = Path.of("web", "build.gradle.kts");

    private static final Pattern PRE = Pattern.compile("<pre(?:\\s[^>]*)?>(.*?)</pre>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern RAW_AMPERSAND = Pattern.compile("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");

    private static final String REPORT = """
            # Rapport de conformité

            | Paquet | Mutations tuées | Seuil |
            |---|---|---|
            | calc.format | 97 % | ≥ 90 % |
            | calc.number | 1 < 2 & 3 > 2 | "ok" 'ok' |

            Résultat : √2 ≈ 1,41421356 — « tout est vert »\tavec une tabulation.
            """;

    // ------------------------------------------------------------------------------------------------ reflection

    private static Method method(String className, String name, Class<?>... parameters) {
        Class<?> type;
        try {
            type = Class.forName(className);
        } catch (ClassNotFoundException absent) {
            throw new AssertionError("the class " + className + " does not exist yet", absent);
        }
        try {
            Method found = type.getMethod(name, parameters);
            assertTrue(java.lang.reflect.Modifier.isStatic(found.getModifiers()), className + "." + name + " must be static");
            return found;
        } catch (NoSuchMethodException absent) {
            throw new AssertionError(className + "." + name + " does not exist", absent);
        }
    }

    private static String render(String markdown) {
        try {
            Object html = method(PAGE, "render", String.class).invoke(null, markdown);
            assertTrue(html instanceof String, "render must return a String, got " + html);
            return (String) html;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new AssertionError("ReportPage.render failed: " + failure, failure);
        }
    }

    /** Calls {@code ReportPageMain.write}; an exception the method throws comes out as it is. */
    private static void write(Path report, Path siteDirectory) throws IOException {
        call(method(MAIN, "write", Path.class, Path.class), report, siteDirectory);
    }

    private static void callMain(String... arguments) throws IOException {
        call(method(MAIN, "main", String[].class), (Object) arguments);
    }

    private static void call(Method method, Object... arguments) throws IOException {
        try {
            method.invoke(null, arguments);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(method + " is not public", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError(cause);
        }
    }

    // --------------------------------------------------------------------------------------------------- helpers

    /** The text of the single {@code pre} element, with the five entities undone: the Markdown as the page shows it. */
    private static String shownInPre(String html) {
        Matcher matcher = PRE.matcher(html);
        assertTrue(matcher.find(), "the page must have a pre element: " + html);
        String content = matcher.group(1);
        assertFalse(matcher.find(), "the page must have only one pre element: " + html);
        assertFalse(content.contains("<"), "no tag may be left inside the pre element: " + content);
        return content.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    private static Path site(Path root) throws IOException {
        return Files.createDirectories(root.resolve("site"));
    }

    private static Path report(Path root, String text) throws IOException {
        return Files.writeString(root.resolve("RAPPORT-CONFORMITE.md"), text, UTF_8);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------------------------------ ReportPage.render

    @Test
    @DisplayName("la page est en français, titrée Rapport de conformité, et renvoie vers index.html")
    void thePageIsFrenchTitledAndLinksBackToTheCalculator() {
        String html = render(REPORT);
        assertTrue(html.contains("lang=\"fr\""), "lang=\"fr\" on the html element: " + html);
        assertTrue(Pattern.compile("<html[^>]*lang=\"fr\"", Pattern.CASE_INSENSITIVE).matcher(html).find(),
                "lang=\"fr\" belongs to the html element: " + html);
        Matcher title = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html);
        assertTrue(title.find(), "a title element: " + html);
        assertTrue(title.group(1).contains("Rapport de conformité"), "the title says Rapport de conformité: " + title.group(1));
        assertTrue(Pattern.compile("<a\\s[^>]*href=\"index\\.html\"", Pattern.CASE_INSENSITIVE).matcher(html).find(),
                "a link to index.html: " + html);
    }

    @Test
    @DisplayName("le Markdown intégral est dans l'élément pre, accents, symboles et tabulation compris")
    void theWholeMarkdownIsInThePreElement() {
        assertEquals(REPORT, shownInPre(render(REPORT)));
        String single = "une seule ligne sans fin de ligne";
        assertEquals(single, shownInPre(render(single)));
        String crlf = "a\r\nb\r\n\r\nc";
        assertEquals(crlf.replace("\r\n", "\n"), shownInPre(render(crlf)).replace("\r\n", "\n"),
                "a report with Windows line ends keeps its lines");
    }

    @Test
    @DisplayName("<, > et & sont échappés : un rapport avec <script> ne produit aucune balise script")
    void markupInTheReportIsEscaped() {
        String hostile = "# Titre\n<script>alert(1)</script>\n<SCRIPT src=x></SCRIPT>\n<img src=x onerror=alert(1)>\n"
                + "a & b && c > d < e\n";
        String html = render(hostile);
        assertFalse(html.toLowerCase(Locale.ROOT).contains("<script"), "no script tag: " + html);
        assertFalse(html.toLowerCase(Locale.ROOT).contains("</script"), "no closing script tag: " + html);
        assertFalse(html.toLowerCase(Locale.ROOT).contains("<img"), "no img tag: " + html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"), "the script is shown as text: " + html);
        assertTrue(html.contains("a &amp; b &amp;&amp; c &gt; d &lt; e"), "& < > are escaped: " + html);
        assertFalse(RAW_AMPERSAND.matcher(html).find(), "every & of the page starts an entity: " + html);
        assertEquals(hostile, shownInPre(html), "the escaped text reads back as the report");
    }

    @Test
    @DisplayName("une entité déjà écrite dans le rapport est échappée une fois de plus, jamais interprétée")
    void anEntityOfTheReportIsNotInterpreted() {
        String html = render("1 &lt; 2 &amp; &copy; &#60;\n");
        assertTrue(html.contains("1 &amp;lt; 2 &amp;amp; &amp;copy; &amp;#60;"), html);
        assertEquals("1 &lt; 2 &amp; &copy; &#60;\n", shownInPre(html));
    }

    // ------------------------------------------------------------------------------------- ReportPageMain.write

    @Test
    @DisplayName("write écrit rapport-conformite.html et rapport-conformite.md, le .md identique au rapport")
    void writeProducesThePageAndACopyOfTheReport(@TempDir Path root) throws IOException {
        Path site = site(root);
        Files.writeString(site.resolve("index.html"), "<html></html>", UTF_8);
        Path report = report(root, REPORT);

        write(report, site);

        assertEquals(List.of("index.html", HTML_FILE, MARKDOWN_FILE).stream().sorted().toList(), names(site),
                "index.html is left alone and the two files are added");
        assertArrayEquals(Files.readAllBytes(report), Files.readAllBytes(site.resolve(MARKDOWN_FILE)),
                "the .md is the report, byte for byte");
        assertEquals(render(REPORT), Files.readString(site.resolve(HTML_FILE), UTF_8),
                "the page is ReportPage.render of the report, written as UTF-8");
        assertEquals("<html></html>", Files.readString(site.resolve("index.html"), UTF_8));
    }

    @Test
    @DisplayName("write remplace une page et un rapport déjà présents")
    void writeReplacesEarlierFiles(@TempDir Path root) throws IOException {
        Path site = site(root);
        write(report(root, "# ancien\n"), site);
        write(report(root, "# nouveau √\n"), site);
        assertEquals("# nouveau √\n", Files.readString(site.resolve(MARKDOWN_FILE), UTF_8));
        assertTrue(Files.readString(site.resolve(HTML_FILE), UTF_8).contains("nouveau √"));
        assertFalse(Files.readString(site.resolve(HTML_FILE), UTF_8).contains("ancien"));
    }

    @Test
    @DisplayName("un rapport absent lève IOException et n'écrit rien")
    void aMissingReportIsRefused(@TempDir Path root) throws IOException {
        Path site = site(root);
        Path missing = root.resolve("conformity").resolve("conformite").resolve("RAPPORT-CONFORMITE.md");
        assertThrows(IOException.class, () -> write(missing, site));
        assertEquals(List.of(), names(site), "nothing was written");
    }

    @Test
    @DisplayName("un rapport vide lève IOException et n'écrit rien, pas même un fichier vide")
    void anEmptyReportIsRefused(@TempDir Path root) throws IOException {
        Path site = site(root);
        Path empty = report(root, "");
        assertEquals(0, Files.size(empty));
        assertThrows(IOException.class, () -> write(empty, site));
        assertEquals(List.of(), names(site), "nothing was written");
    }

    @Test
    @DisplayName("un rapport absent ne remplace pas les fichiers d'un essai précédent")
    void aRefusedReportLeavesEarlierFilesAlone(@TempDir Path root) throws IOException {
        Path site = site(root);
        write(report(root, "# bon\n"), site);
        Path missing = root.resolve("absent.md");
        assertThrows(IOException.class, () -> write(missing, site));
        assertEquals("# bon\n", Files.readString(site.resolve(MARKDOWN_FILE), UTF_8));
        assertTrue(Files.readString(site.resolve(HTML_FILE), UTF_8).contains("bon"));
    }

    @Test
    @DisplayName("main appelle write avec le rapport puis le dossier du site")
    void mainCallsWrite(@TempDir Path root) throws IOException {
        Path site = site(root);
        Path report = report(root, REPORT);
        callMain(report.toString(), site.toString());
        assertEquals(List.of(HTML_FILE, MARKDOWN_FILE), names(site));
        assertEquals(REPORT, Files.readString(site.resolve(MARKDOWN_FILE), UTF_8));
    }

    @Test
    @DisplayName("main refuse un rapport absent comme write")
    void mainRefusesAMissingReport(@TempDir Path root) throws IOException {
        Path site = site(root);
        String missing = root.resolve("absent.md").toString();
        String siteArgument = site.toString();
        assertThrows(IOException.class, () -> callMain(missing, siteArgument));
        assertEquals(List.of(), names(site));
    }

    // ------------------------------------------------------------------------------------- the wiring in the build

    @Test
    @DisplayName("le sous-paquet tools n'est jamais atteint depuis WebExports, et ReportPage reste dans calc.web")
    void theReportIsOutsideTheCompiledCore() throws IOException {
        Path exports = WEB_SOURCES.resolve("WebExports.java");
        String source = Files.readString(exports, UTF_8);
        assertFalse(source.contains("ReportPage"), "WebExports must not reach the report");
        assertFalse(source.contains("calc.web.tools"), "WebExports must not reach calc.web.tools");
        assertTrue(Files.isRegularFile(WEB_SOURCES.resolve("ReportPage.java")), "web/src/main/java/calc/web/ReportPage.java");
        assertTrue(Files.isRegularFile(WEB_SOURCES.resolve("tools").resolve("ReportPageMain.java")),
                "web/src/main/java/calc/web/tools/ReportPageMain.java");
    }

    @Test
    @DisplayName("web/build.gradle.kts : la tâche ReportPageMain n'est attachée à site que si conformityReport est donnée")
    void theBuildAttachesTheReportOnlyWhenThePropertyIsGiven() throws IOException {
        String build = withoutComments(Files.readString(WEB_BUILD, UTF_8).replace("\r\n", "\n"));
        assertTrue(build.contains("conformityReport"), "web/build.gradle.kts must read the conformityReport property");
        assertTrue(build.contains("calc.web.tools.ReportPageMain"), "web/build.gradle.kts must run calc.web.tools.ReportPageMain");
        assertTrue(Pattern.compile("register<JavaExec>\\(\"[^\"]+\"\\)").matcher(build).find(),
                "the report is written by a JavaExec task");
        assertTrue(build.contains("rootProject.file("),
                "the path is relative to the root of the repository: file() alone would look under web/");

        // "attached only if the property is given": the wiring sits under a condition on the property, or the task is
        // disabled without it. The behaviour itself is shown by running :web:site with and without the property.
        Pattern conditional = Pattern.compile(
                "if\\s*\\([^)]*conformityReport[^)]*\\)"
                        + "|hasProperty\\(\"conformityReport\"\\)"
                        + "|findProperty\\(\"conformityReport\"\\)"
                        + "|gradleProperty\\(\"conformityReport\"\\)[^\\n]*\\.(?:isPresent|orNull|map|flatMap)"
                        + "|onlyIf\\s*\\{[^}]*conformityReport");
        assertTrue(conditional.matcher(build).find(), "a condition on the conformityReport property");
        assertTrue(build.contains("finalizedBy"), "the report is written after the copy of the site: site is finalized by it");
        assertFalse(Pattern.compile("conformityReport\"\\)\\s*\\.orElse\\(\\s*\"[^\"]+\"").matcher(build).find(),
                "the property has no default value that would attach the task every time");
    }

    private static String withoutComments(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int comment = line.indexOf("//");
            kept.append(comment >= 0 && !line.substring(0, comment).contains("\"") ? line.substring(0, comment) : line).append('\n');
        }
        return kept.toString();
    }
}
