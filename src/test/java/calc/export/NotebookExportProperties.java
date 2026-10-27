package calc.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.ParsedLine;
import calc.format.DisplayMode;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.testing.NotebookGenerators;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Whatever the notebook, the exports are well formed: the CSV parses back (with a reader written here, from RFC 4180) to
 * one row per non-empty line, five cells each, with no cell a spreadsheet could run as a formula and the source recoverable;
 * the Markdown table keeps three cells per row and lets no line break or tag through.
 */
@Tag("property")
class NotebookExportProperties {
    private static final String FORMULA_STARTS = "=+-@\t\r";

    @Provide
    Arbitrary<Notebook> notebooks() {
        return NotebookGenerators.notebooks(30);
    }

    /** An independent reader of the format: ';' separator, quotes doubled, CRLF row ends. */
    static List<List<String>> parseCsv(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '"' && index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else if (current == '"') {
                    quoted = false;
                } else {
                    cell.append(current);
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == ';') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
                index++;
            } else {
                cell.append(current);
            }
        }
        assertFalse(quoted, "a quote is left open");
        assertTrue(cell.length() == 0 && row.isEmpty(), "the file ends with a row end");
        return rows;
    }

    @Property(tries = 300)
    void theCsvParsesBackToOneSafeRowPerNonEmptyLine(@ForAll("notebooks") Notebook notebook, @ForAll DisplayMode mode) {
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(notebook);

        byte[] bytes = NotebookExporter.csv(evaluated, mode);

        assertEquals((byte) 0xEF, bytes[0]);
        assertEquals((byte) 0xBB, bytes[1]);
        assertEquals((byte) 0xBF, bytes[2]);
        List<List<String>> rows = parseCsv(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8));
        List<LineResult> shown = evaluated.results().stream().filter(line -> line.kind() != ParsedLine.Kind.EMPTY).toList();
        assertEquals(shown.size() + 1, rows.size(), "the header and one row per non-empty line");
        assertEquals(List.of("n°", "Expression", "Résultat affiché", "Valeur exacte", "Statut"), rows.get(0));
        for (int index = 0; index < shown.size(); index++) {
            LineResult line = shown.get(index);
            List<String> cells = rows.get(index + 1);
            assertEquals(5, cells.size(), cells.toString());
            assertEquals(Integer.toString(line.number()), cells.get(0));
            for (String cell : cells) {
                assertFalse(!cell.isEmpty() && FORMULA_STARTS.indexOf(cell.charAt(0)) >= 0, "a formula could start here: " + cell);
            }
            String source = cells.get(1);
            assertEquals(line.text(), source.startsWith("'") && !line.text().startsWith("'") ? source.substring(1) : source,
                    "the source is recovered by removing the apostrophe the neutralisation added");
            String status = cells.get(4);
            if (line.isError()) {
                assertEquals("erreur", status);
                assertEquals("", cells.get(3));
            } else if (line.hasValue()) {
                assertEquals(line.value().isExact() ? "exact" : "approché", status);
                assertEquals(line.value().isExact(), !cells.get(3).isEmpty(), "an exact value, and only an exact value, is given");
                assertFalse(cells.get(2).isEmpty());
            } else {
                assertEquals("", status);
                assertEquals("", cells.get(2));
            }
        }
    }

    @Property(tries = 300)
    void theMarkdownTableKeepsThreeCellsPerRowAndOneRowPerLine(@ForAll("notebooks") Notebook notebook, @ForAll DisplayMode mode) {
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(notebook);

        String text = NotebookExporter.markdown(evaluated, "Titre", mode);

        List<String> rows = text.lines().toList();
        long expected = evaluated.results().stream().filter(line -> line.kind() != ParsedLine.Kind.EMPTY).count();
        assertEquals(4 + expected, rows.size(), "title, blank, header, separator and one row per non-empty line");
        assertTrue(text.endsWith("|\n") || expected == 0 && text.endsWith("---|\n"));
        for (String row : rows.subList(4, rows.size())) {
            assertTrue(row.startsWith("| ") && row.endsWith(" |"), row);
            assertEquals(4, NotebookExporterTest.unescapedPipes(row), "three cells: " + row);
            assertFalse(row.contains("<script") || row.matches(".*(?<!\\\\)<[a-zA-Z/].*"), "no tag gets through: " + row);
        }
    }
}
