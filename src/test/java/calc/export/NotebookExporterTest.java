package calc.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.NotebookEvaluator;
import calc.testing.NotebookGenerators;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The Markdown and CSV exports, compared to reference files for a representative notebook (exact and approximate values,
 * an error, comments, variables, references, special characters), and the rules one by one: escaping, quoting, byte order
 * mark, line ends and the neutralisation of formulas.
 */
public class NotebookExporterTest {
    /** Exact, approximate, error, comment, variables, reference, spreadsheet-looking and Markdown-looking lines. */
    public static final List<String> REPRESENTATIVE = List.of(
            "# Devis cuisine",
            "surface = 12,5",
            "prix = 42",
            "surface * prix",
            "",
            "1 / 3",
            "√2",
            "ans ^ 2",
            "2 +",
            "# Note : a|b *gras* _it_ [lien](x) <b> $1 ~~x~~ & \\ `code`",
            "-5 + 2",
            "=1+2",
            "@x",
            "1000000 * 1000000 * 1000000 * 1000000",
            "surface * 3 # sur trois côtés",
            "$4 / 7",
            "# =HYPERLINK(\"http://example.org\";\"clic\")",
            "0,000001 / 3",
            "x = ");

    static EvaluatedNotebook representative() {
        return NotebookEvaluator.evaluate(NotebookGenerators.notebook(REPRESENTATIVE));
    }

    // ------------------------------------------------------------------------------------------------ reference files

    @ParameterizedTest(name = "{0}")
    @CsvSource({"AUTO,carnet-auto", "FRACTION,carnet-fraction", "SCIENTIFIC,carnet-scientifique", "DECIMAL,carnet-decimal"})
    void markdownMatchesTheReferenceFile(DisplayMode mode, String reference) throws IOException {
        String actual = NotebookExporter.markdown(representative(), "Devis cuisine", mode);

        byte[] bytes = actual.getBytes(StandardCharsets.UTF_8);
        assertEquals(new String(resource(reference + ".md", bytes), StandardCharsets.UTF_8).replace("\r\n", "\n"), actual,
                () -> saved(bytes, reference + ".md"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"AUTO,carnet-auto", "FRACTION,carnet-fraction", "SCIENTIFIC,carnet-scientifique", "DECIMAL,carnet-decimal"})
    void csvMatchesTheReferenceFileByteForByte(DisplayMode mode, String reference) throws IOException {
        byte[] actual = NotebookExporter.csv(representative(), mode);

        assertArrayEquals(resource(reference + ".csv", actual), actual, () -> saved(actual, reference + ".csv"));
    }

    // Where the actual output was written, to be reviewed by eye and copied over a reference file when the change is wanted.
    private static String saved(byte[] actual, String name) {
        try {
            Path directory = Path.of("build", "actual-exports");
            Files.createDirectories(directory);
            Files.write(directory.resolve(name), actual);
            return "export differs from the reference; actual output saved in " + directory.resolve(name).toAbsolutePath();
        } catch (IOException exception) {
            return "export differs from the reference (" + exception + ")";
        }
    }

    private static byte[] resource(String name, byte[] actual) throws IOException {
        try (InputStream stream = NotebookExporterTest.class.getResourceAsStream("/export/" + name)) {
            assertNotNull(stream, () -> "missing reference file export/" + name + "; " + saved(actual, name));
            return stream.readAllBytes();
        }
    }

    // ------------------------------------------------------------------------------------------------ CSV rules

    @Test
    void theFileStartsWithTheByteOrderMarkAndEveryRowEndsWithCrlf() {
        byte[] csv = NotebookExporter.csv(representative(), DisplayMode.AUTO);

        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
        String text = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.startsWith("n°;Expression;Résultat affiché;Valeur exacte;Statut\r\n"));
        assertTrue(text.endsWith("\r\n"));
        assertFalse(text.replace("\r\n", "").contains("\n"), "no bare line feed");
        assertFalse(text.replace("\r\n", "").contains("\r"), "no bare carriage return");
        assertEquals(5, text.lines().filter(line -> !line.isEmpty()).findFirst().orElseThrow().split(";", -1).length, "five columns");
    }

    @Test
    void aTextCellStartingWithAFormulaCharacterIsPrefixedWithAnApostrophe() {
        for (String cell : List.of("=1+2", "+1", "-5", "@SUM(A1)", "\tcmd", "\rcmd", "=cmd|' /C calc'!A0", "-2+3+cmd|' /C calc'!A0", "@x", "==", "--")) {
            assertEquals("'" + cell, NotebookExporter.neutralizeFormula(cell), cell.replace("\t", "\\t").replace("\r", "\\r"));
        }
        for (String cell : List.of("", "1", "a=1", " =1", "x+1", "'=1", "#=1", "≈ 1", "é", "0,5", "(−1)", "'", "1-1")) {
            assertEquals(cell, NotebookExporter.neutralizeFormula(cell), cell);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    void everyOneOfTheSixStartsIsNeutralisedInTheFileItself(String start) {
        String row = NotebookExporter.csvRow(List.of("1", start + "SUM(1;2)", "", "", ""));

        assertTrue(row.startsWith("1;\"'" + start + "SUM(1;2)\""), row);
    }

    @Test
    void injectionIsNeutralisedInEveryColumnOfTheRealFile() {
        // Sources that start with each character, as the user could have typed or received them.
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(
                List.of("=1+2", "+1+1", "-1-1", "@x", "\t1+1", "# =cmd", "-(1)")));

        String text = new String(NotebookExporter.csv(notebook, DisplayMode.AUTO), StandardCharsets.UTF_8);

        for (String row : text.substring(1).split("\r\n")) {
            String[] cells = row.split(";", -1);
            for (String cell : cells) {
                String bare = cell.startsWith("\"") ? cell.substring(1) : cell;
                assertFalse(!bare.isEmpty() && "=+-@\t\r".indexOf(bare.charAt(0)) >= 0, "a cell starts with a formula character: " + row);
            }
        }
        assertTrue(text.contains("\r\n2;'+1+1;"), text);
        assertTrue(text.contains("\r\n7;'-(1);"), text);
        assertTrue(text.contains("\r\n6;# =cmd;"), "# first: nothing to neutralise");
    }

    @Test
    void fieldsAreQuotedAsInRfc4180() {
        assertEquals("a;b;c", NotebookExporter.csvRow(List.of("a", "b", "c")));
        assertEquals("\"a;b\";c", NotebookExporter.csvRow(List.of("a;b", "c")));
        assertEquals("\"say \"\"hi\"\"\";c", NotebookExporter.csvRow(List.of("say \"hi\"", "c")));
        assertEquals("\"line1\nline2\";c", NotebookExporter.csvRow(List.of("line1\nline2", "c")));
        assertEquals("\"a\rb\";c", NotebookExporter.csvRow(List.of("a\rb", "c")));
        assertEquals("\"'=a;b\"", NotebookExporter.csvRow(List.of("=a;b")), "neutralised first, then quoted");
        assertEquals(";;", NotebookExporter.csvRow(List.of("", "", "")));
        assertEquals("a b;é;≈ 1,4", NotebookExporter.csvRow(List.of("a b", "é", "≈ 1,4")), "spaces and accents need no quotes");
    }

    @Test
    void theColumnsAreNumberExpressionDisplayedCanonicalAndStatus() {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("1 / 3", "√2", "2 +", "# note", "", "2 * 3")));

        String text = new String(NotebookExporter.csv(notebook, DisplayMode.AUTO), StandardCharsets.UTF_8).substring(1);
        List<String> rows = text.lines().toList();

        assertEquals(6, rows.size(), "the header and the five non-empty lines: the empty line 5 is left out");
        assertEquals("1;1 / 3;0,333333333333…;1/3;exact", rows.get(1));
        assertTrue(rows.get(2).startsWith("2;√2;≈ 1,41421356237"), rows.get(2));
        assertTrue(rows.get(2).endsWith(";;approché"), "no exact value for an approximation: " + rows.get(2));
        assertTrue(rows.get(2).contains(" (± "), "the error bound is there");
        assertTrue(rows.get(3).startsWith("3;2 +;"), rows.get(3));
        assertTrue(rows.get(3).endsWith(";;erreur"), rows.get(3));
        assertEquals("4;# note;;;", rows.get(4), "a comment has no result and no status");
        assertEquals("6;2 * 3;6;6;exact", rows.get(5), "numbers are those of the notebook");
    }

    @Test
    void theModeChangesTheDisplayedResultButNotTheExactValue() {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("1 / 3 * 1000000")));
        String auto = new String(NotebookExporter.csv(notebook, DisplayMode.AUTO), StandardCharsets.UTF_8);
        String fraction = new String(NotebookExporter.csv(notebook, DisplayMode.FRACTION), StandardCharsets.UTF_8);
        String scientific = new String(NotebookExporter.csv(notebook, DisplayMode.SCIENTIFIC), StandardCharsets.UTF_8);

        assertTrue(auto.contains(";333333,333333…;"), auto);
        assertTrue(fraction.contains(";1000000/3;"), fraction);
        assertTrue(scientific.contains(";3,33333333333"), scientific);
        for (String text : List.of(auto, fraction, scientific)) {
            assertTrue(text.contains(";1000000/3;exact"), "the canonical exact value does not depend on the mode");
        }
    }

    // ------------------------------------------------------------------------------------------------ Markdown rules

    @Test
    void theTableHasTheDocumentedHeaderAndATitle() {
        String text = NotebookExporter.markdown(NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("1 + 1"))), "Mon devis", DisplayMode.AUTO);

        assertEquals("# Mon devis\n\n| n° | Expression | Résultat |\n| --- | --- | --- |\n| 1 | 1 + 1 | 2 |\n", text);
    }

    @Test
    void anApproximateValueShowsItsMarkerAndItsErrorBoundAndAnErrorItsMessage() {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("√2", "1 / 0", "# note", "x = 4")));

        String[] rows = NotebookExporter.markdown(notebook, "t", DisplayMode.AUTO).split("\n");

        assertTrue(rows[4].matches("\\| 1 \\| √2 \\| ≈ 1,41421356237… \\(± [0-9,]+ × 10⁻[⁰¹²³⁴⁵⁶⁷⁸⁹]+\\) \\|"), rows[4]);
        assertTrue(rows[5].startsWith("| 2 | 1 / 0 | Erreur : "), rows[5]);
        assertEquals("| 3 | note |  |", rows[6], "a comment is text: no marker, no result");
        assertEquals("| 4 | x = 4 | 4 |", rows[7]);
    }

    @Test
    void theCharactersMarkdownCouldReadAreEscapedInEveryCell() {
        assertEquals("a\\|b", NotebookExporter.escapeMarkdown("a|b"));
        assertEquals("\\*gras\\* \\_it\\_ \\[x\\](y) \\<b\\> \\~\\~z\\~\\~ \\& \\\\ \\`c\\` \\$1",
                NotebookExporter.escapeMarkdown("*gras* _it_ [x](y) <b> ~~z~~ & \\ `c` $1"));
        assertEquals("a<br>b<br>c<br>d", NotebookExporter.escapeMarkdown("a\nb\r\nc\rd"), "a line break cannot end the row");
        assertEquals("1,5 + 2 = é ≈", NotebookExporter.escapeMarkdown("1,5 + 2 = é ≈"), "plain text is untouched");
    }

    @Test
    void aHostileCommentCannotBreakTheTableOrInjectMarkup() {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(
                List.of("# </td></tr> | <script>alert(1)</script> [x](javascript:alert(1)) ![i](http://e/x.png)")));

        String row = NotebookExporter.markdown(notebook, "t", DisplayMode.AUTO).split("\n")[4];

        assertEquals(3, unescapedPipes(row) - 1, "the row keeps its three cells: " + row);
        assertFalse(row.contains("<script>"), row);
        assertFalse(row.contains("[x]("), row);
    }

    @Test
    void theTitleIsEscapedAndABlankTitleBecomesCarnet() {
        EvaluatedNotebook empty = NotebookEvaluator.evaluate(calc.notebook.Notebook.empty());

        assertTrue(NotebookExporter.markdown(empty, "A_B *x*\nC", DisplayMode.AUTO).startsWith("# A\\_B \\*x\\*<br>C\n\n"));
        assertTrue(NotebookExporter.markdown(empty, "  ", DisplayMode.AUTO).startsWith("# Carnet\n"));
        assertTrue(NotebookExporter.markdown(empty, null, DisplayMode.AUTO).startsWith("# Carnet\n"));
    }

    @Test
    void anEmptyNotebookExportsOnlyItsHeader() {
        EvaluatedNotebook empty = NotebookEvaluator.evaluate(calc.notebook.Notebook.empty());

        assertEquals("# Carnet\n\n| n° | Expression | Résultat |\n| --- | --- | --- |\n", NotebookExporter.markdown(empty, "Carnet", DisplayMode.AUTO));
        assertEquals("n°;Expression;Résultat affiché;Valeur exacte;Statut\r\n",
                new String(NotebookExporter.csv(empty, DisplayMode.AUTO), 3, NotebookExporter.csv(empty, DisplayMode.AUTO).length - 3, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------------------------------------ files

    @Test
    void theFilesAreWrittenAtomicallyInUtf8(@TempDir Path directory) throws Exception {
        Path markdown = directory.resolve("devis.md");
        Path csv = directory.resolve("devis.csv");

        NotebookExporter.writeMarkdown(markdown, representative(), "Devis cuisine", DisplayMode.AUTO);
        NotebookExporter.writeCsv(csv, representative(), DisplayMode.AUTO);

        assertEquals(NotebookExporter.markdown(representative(), "Devis cuisine", DisplayMode.AUTO), Files.readString(markdown, StandardCharsets.UTF_8));
        assertArrayEquals(NotebookExporter.csv(representative(), DisplayMode.AUTO), Files.readAllBytes(csv));
        assertEquals(List.of("devis.csv", "devis.md"), Files.list(directory).map(path -> path.getFileName().toString()).sorted().toList(), "no temporary file");
        assertFalse(Files.readString(markdown).startsWith("\uFEFF"), "Markdown has no byte order mark");
    }

    static int unescapedPipes(String row) {
        int count = 0;
        for (int index = 0; index < row.length(); index++) {
            if (row.charAt(index) == '\\') {
                index++;
            } else if (row.charAt(index) == '|') {
                count++;
            }
        }
        return count;
    }
}
