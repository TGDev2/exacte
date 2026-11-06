package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The notebook examples of the README are run as written: what the comments announce is what the application shows. */
class ReadmeExamplesTest {
    private static final Path README = Path.of("README.md");

    private static List<List<String>> examples() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        int section = readme.indexOf("## Exemples de carnet");
        int end = readme.indexOf("## Téléchargement");
        assertTrue(section >= 0 && end > section, "the README has no notebook examples section");
        List<List<String>> blocks = new ArrayList<>();
        int from = section;
        while (true) {
            int start = readme.indexOf("```text", from);
            if (start < 0 || start > end) {
                return blocks;
            }
            start += "```text".length();
            int stop = readme.indexOf("```", start);
            blocks.add(readme.substring(start, stop).strip().lines().toList());
            from = stop + 3;
        }
    }

    /** The line as typed (without its comment) and the comment. */
    private static String typed(String line) {
        int hash = line.indexOf('#');
        return hash <= 0 ? line : line.substring(0, hash).stripTrailing();
    }

    private static String comment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? "" : line.substring(hash + 1);
    }

    private static List<String> typedLines(List<String> block) {
        return block.stream().map(ReadmeExamplesTest::typed).toList();
    }

    @Test
    void theQuoteExampleGivesTheAnnouncedAmounts() throws IOException {
        List<String> block = examples().get(0);
        // The example keeps its comments: the full line, comment included, is what the user types.
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookTestSupport.notebook(block.toArray(String[]::new)));
        notebook.results().forEach(result -> assertTrue(!result.isError(), () -> result.text() + " : " + result.failure()));
        NotebookTestSupport.assertValue("5167.5", notebook.result(5));
        NotebookTestSupport.assertValue("6201", notebook.result(7));
        NotebookTestSupport.assertValue("516.75", notebook.result(8));
        assertTrue(comment(block.get(4)).contains("5 167,5"), block.get(4));
        assertTrue(comment(block.get(6)).contains("6 201"), block.get(6));
        assertTrue(comment(block.get(7)).contains("516,75"), block.get(7));
    }

    @Test
    void theExactnessExampleShowsWhatTheCommentsAnnounce() throws IOException {
        List<String> block = examples().get(1);
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookTestSupport.notebook(typedLines(block).toArray(String[]::new)));

        assertShows("0,333333333333…", true, block.get(0), notebook.result(1));
        assertEquals("1/3", ValueFormatter.format(notebook.result(1).value(), DisplayMode.AUTO).exactFraction().orElseThrow());
        assertShows("1", true, block.get(1), notebook.result(2));
        FormattedValue root = ValueFormatter.format(notebook.result(3).value(), DisplayMode.AUTO);
        assertEquals("≈ 1,41421356237…", root.text());
        assertEquals("± 2,1 × 10⁻²⁵", root.errorBound().orElseThrow());
        assertTrue(comment(block.get(2)).contains(root.text().substring(2)) && comment(block.get(2)).contains("2,1 × 10⁻²⁵"));
        assertShows("1,5", true, block.get(3), notebook.result(4));
        assertShows("0,3", true, block.get(4), notebook.result(5));
        assertEquals("Division par zéro : le diviseur vaut 0.", notebook.result(6).error().message());
        assertTrue(block.get(5).contains(notebook.result(6).error().message()), block.get(5));
    }

    private static void assertShows(String text, boolean exact, String readmeLine, LineResult result) {
        FormattedValue shown = ValueFormatter.format(result.value(), DisplayMode.AUTO);
        assertEquals(text, shown.text(), result.text());
        assertEquals(exact, shown.exact(), result.text());
        assertTrue(readmeLine.contains(text), readmeLine);
    }
}
