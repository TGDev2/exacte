package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.persistence.DecodedNotebook;
import calc.persistence.NotebookCodec;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs.ExportFormat;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import calc.ui.notebook.NotebookRow.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Idea 2 of docs/RECETTE.md, the notebook, through scenarios a user would really play: a quote with variables and cascades, a
 * budget with references that survive insertions, deletions and moves, undo and redo of everything, documents reopened in a new
 * session, exports read back.
 */
@Tag("acceptance")
class NotebookScenariosAcceptanceTest {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CSV_HEADER = "n°;Expression;Résultat affiché;Valeur exacte;Statut";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private AcceptanceSession session;
    private NotebookDriver d;

    @BeforeEach
    void start() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        session = AcceptanceSession.start(data);
        d = session.d;
    }

    @AfterEach
    void stop() {
        session.close();
    }

    private void restart() {
        session.close();
        session = AcceptanceSession.start(data);
        d = session.d;
    }

    private void type(String... lines) {
        for (String line : lines) {
            d.line(line);
        }
    }

    // ------------------------------------------------------------------------------------------------ the quote

    @Test
    void theQuoteRecalculatesInCascadeWhenALineHighUpChanges() {
        type("# Devis cuisine",
                "surface = 12,5",
                "prix = 42",
                "surface * prix   # hors taxes",
                "tva = ans * 0,2",
                "$4 + tva",
                "ans / 12");

        assertEquals(List.of(Status.COMMENT, Status.EXACT, Status.EXACT, Status.EXACT, Status.EXACT, Status.EXACT, Status.EXACT),
                session.statuses());
        assertEquals(List.of("", "12,5", "42", "525", "105", "630", "52,5"), session.results());
        assertEquals("surface", d.row(2).definedName());
        assertEquals("tva", d.row(5).definedName());

        d.click(1).line("surface = 20");

        assertEquals(List.of("", "20", "42", "840", "168", "1008", "84"), session.results());

        // A variable in error takes its dependants with it, in words: nothing keeps a stale value.
        d.click(2).line("prix = 1/0");
        assertEquals(Status.ERROR, d.row(3).status());
        assertEquals(Status.ERROR, d.row(4).status());
        assertEquals(Status.ERROR, d.row(6).status());
        assertFalse(d.row(4).errorMessage().isBlank());
        assertEquals("", d.row(4).result());

        d.undo();
        assertEquals(List.of("", "20", "42", "840", "168", "1008", "84"), session.results());
        d.undo();
        assertEquals(List.of("", "12,5", "42", "525", "105", "630", "52,5"), session.results());
    }

    // ------------------------------------------------------------------------------------------------ the budget

    private record State(List<String> lines, List<String> results, List<Status> statuses) {
    }

    private State state() {
        return new State(session.lines(), session.results(), session.statuses());
    }

    @Test
    void theBudgetKeepsItsReferencesThroughInsertDeleteAndMoveAndEverythingCanBeUndoneAndRedone() {
        List<State> history = new ArrayList<>();
        history.add(state());
        for (String line : List.of("1000", "250", "$1 + $2", "$3 * 12")) {
            d.line(line);
            history.add(state());
        }
        assertEquals(List.of("1000", "250", "1250", "15000"), session.results());

        // Insertion above: every reference still designates the same line, under its new number.
        d.click(1).insertAbove();
        history.add(state());
        d.line("50");
        history.add(state());
        assertEquals(List.of("1000", "50", "250", "$1 + $3", "$4 * 12"), session.lines());
        assertEquals(List.of("1000", "50", "250", "1250", "15000"), session.results());

        // Moves: same thing.
        d.click(1).moveLineDown();
        history.add(state());
        assertEquals(List.of("1000", "250", "50", "$1 + $2", "$4 * 12"), session.lines());
        assertEquals(List.of("1000", "250", "50", "1250", "15000"), session.results());
        d.click(3).moveLineUp();
        history.add(state());
        assertEquals(List.of("1000", "250", "$1 + $2", "50", "$3 * 12"), session.lines());
        assertEquals(List.of("1000", "250", "1250", "50", "15000"), session.results());

        // Deletion: the reference becomes $? and an error. It is never silently redirected to the line that took the place.
        d.click(1).delete();
        history.add(state());
        assertEquals(List.of("1000", "$1 + $?", "50", "$2 * 12"), session.lines());
        assertEquals(List.of(Status.EXACT, Status.ERROR, Status.EXACT, Status.ERROR), session.statuses());
        assertTrue(d.row(2).errorMessage().contains("supprimée"), d.row(2).errorMessage());
        assertEquals("", d.row(2).result());
        assertEquals("", d.row(4).result());

        // The deleted reference stays deleted when the lines move again.
        d.click(2).moveLineUp();
        history.add(state());
        assertEquals(List.of("1000", "50", "$1 + $?", "$3 * 12"), session.lines());
        assertEquals(Status.ERROR, d.row(3).status());

        // Undo everything, one step at a time and back through every state; then redo everything.
        for (int step = history.size() - 2; step >= 0; step--) {
            assertTrue(d.view.canUndo(), "step " + step);
            d.undo();
            assertEquals(history.get(step), state(), "after undoing back to step " + step);
        }
        assertFalse(d.view.canUndo());
        for (int step = 1; step < history.size(); step++) {
            assertTrue(d.view.canRedo(), "step " + step);
            d.redo();
            assertEquals(history.get(step), state(), "after redoing up to step " + step);
        }
        assertFalse(d.view.canRedo());
    }

    // ------------------------------------------------------------------------------------------------ documents

    private static final List<String> MIXED = List.of(
            "# Devis cuisine", "surface = 12,5", "surface * 42", "1 / 3", "√2", "ans ^ 2", "2 +", "$3 / 7", "3 ^ -30");

    @Test
    void saveAsThenReopenInANewSessionGivesTheSameLinesAndTheSameResultsInEveryMode() throws Exception {
        type(MIXED.toArray(String[]::new));
        List<List<String>> shown = new ArrayList<>();
        for (DisplayMode mode : DisplayMode.values()) {
            d.mode(mode);
            shown.add(session.results());
        }
        List<Status> statuses = session.statuses();
        d.mode(DisplayMode.FRACTION);

        session.dialogs.willSaveTo(documents.resolve("devis"));
        d.saveAs();
        Path file = documents.resolve("devis.calc.json");
        assertEquals("devis.calc.json — Calc", d.view.title());
        assertEquals(StatusTone.SUCCESS, d.view.statusTone());
        DecodedNotebook written = NotebookCodec.read(file);
        assertEquals(NotebookCodec.VERSION, written.version());
        assertEquals(MIXED, written.notebook().texts());

        restart();
        session.dialogs.willOpen(file);
        d.open();

        assertEquals("devis.calc.json — Calc", d.view.title());
        assertEquals(MIXED, session.lines());
        assertEquals(statuses, session.statuses());
        assertEquals(DisplayMode.FRACTION, d.view.displayMode(), "the file brings its display mode");
        for (DisplayMode mode : DisplayMode.values()) {
            d.mode(mode);
            assertEquals(shown.get(mode.ordinal()), session.results(), mode.name());
        }
        session.dialogs.assertAllUsed();
    }

    @Test
    void anOpenDocumentChangesItsFileOnlyWhenSaved() throws Exception {
        type("a = 2", "a * 3");
        session.dialogs.willSaveTo(documents.resolve("devis"));
        d.saveAs();
        Path file = documents.resolve("devis.calc.json");
        byte[] saved = Files.readAllBytes(file);

        d.click(0).line("a = 5");
        assertEquals("• devis.calc.json — Calc", d.view.title());
        assertArrayEquals(saved, Files.readAllBytes(file), "no automatic save over a document with a file");

        d.save();
        assertEquals("devis.calc.json — Calc", d.view.title());
        assertEquals(List.of("a = 5", "a * 3"), NotebookCodec.read(file).notebook().texts());
    }

    @Test
    void theCurrentNotebookIsSavedAutomaticallyAndComesBackAfterARestart() {
        type("a = 2", "a * 3", "ans + 1");
        State before = state();

        restart();

        assertEquals(before, state());
        assertEquals("Carnet sans titre — Calc", d.view.title());
    }

    @Test
    void aNewDocumentAsksBeforeReplacingTheCurrentNotebookAndStartsEmpty() {
        type("1 + 1");

        session.dialogs.willAnswer(Unsaved.CANCEL);
        d.newDocument();
        assertEquals(List.of("1 + 1"), session.lines(), "cancelled: nothing is lost");

        session.dialogs.willAnswer(Unsaved.DISCARD);
        d.newDocument();
        assertEquals(List.of(), session.lines());
        assertEquals("Carnet sans titre — Calc", d.view.title());
        session.dialogs.assertAllUsed();
    }

    // ------------------------------------------------------------------------------------------------ exports

    // A blank line cannot be typed at the end of a notebook: it is inserted afterwards, at index EMPTY_LINE.
    private static final List<String> TO_EXPORT = List.of(
            "# Note ; a \"b\" | c",
            "a = 2",
            "1 / 3",
            "√2",
            "-5 + 2",
            "+1 + 1",
            "=1+2",
            "@x",
            "2 +",
            "$2 * 3");
    private static final int EMPTY_LINE = 2;

    private void typeTheLinesToExport() {
        type(TO_EXPORT.toArray(String[]::new));
        d.click(EMPTY_LINE).insertAbove();
    }

    @Test
    void theCsvExportReadsBackWithItsSeparatorItsBomAndNoFormulaALive() throws Exception {
        typeTheLinesToExport();
        session.dialogs.willExportTo(documents.resolve("budget"));
        d.export(ExportFormat.CSV);

        assertEquals(StatusTone.SUCCESS, d.view.statusTone(), d.view.statusMessage());
        byte[] bytes = Files.readAllBytes(documents.resolve("budget.csv"));
        assertArrayEquals(UTF8_BOM, Arrays.copyOf(bytes, 3), "a byte order mark, for Excel");
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertTrue(text.startsWith(CSV_HEADER + "\r\n"), text);
        assertFalse(text.replace("\r\n", "").contains("\n"), "the line ends are CRLF");
        assertFalse(text.contains("﻿"), "a single byte order mark");

        List<List<String>> records = readCsv(text);
        assertEquals(List.of("n°", "Expression", "Résultat affiché", "Valeur exacte", "Statut"), records.get(0));
        List<List<String>> rows = records.subList(1, records.size());
        assertEquals(TO_EXPORT.size(), rows.size(), "the empty line is left out");

        for (List<String> row : rows) {
            assertEquals(5, row.size(), row.toString());
            for (String cell : row) {
                assertTrue(cell.isEmpty() || "=+-@\t\r".indexOf(cell.charAt(0)) < 0, "a spreadsheet would run " + cell);
            }
        }
        assertEquals(List.of("1", "# Note ; a \"b\" | c", "", "", ""), rows.get(0), "separator and quotes survive the quoting");
        assertEquals(List.of("2", "a = 2", "2", "2", "exact"), rows.get(1));
        assertEquals("4", rows.get(2).get(0), "the numbers of the notebook are kept");
        assertEquals("1/3", rows.get(2).get(3));
        assertEquals("exact", rows.get(2).get(4));
        assertEquals("approché", rows.get(3).get(4));
        assertEquals("", rows.get(3).get(3), "no exact value for an approximate result");
        assertTrue(rows.get(3).get(2).startsWith("≈ ") && rows.get(3).get(2).contains("±"), rows.get(3).toString());
        assertEquals(List.of("6", "'-5 + 2", "'-3", "'-3", "exact"), rows.get(4), "neutralised with an apostrophe");
        assertEquals("'+1 + 1", rows.get(5).get(1));
        assertEquals("'=1+2", rows.get(6).get(1));
        assertEquals("erreur", rows.get(6).get(4));
        assertEquals("'@x", rows.get(7).get(1));
        assertEquals("erreur", rows.get(8).get(4));
        assertEquals("6", rows.get(9).get(2));

        // The export shows the mode in force; replacing a file asks first.
        d.mode(DisplayMode.FRACTION);
        session.dialogs.willExportTo(documents.resolve("budget.csv")).willConfirmOverwrite(false);
        d.export(ExportFormat.CSV);
        assertArrayEquals(bytes, Files.readAllBytes(documents.resolve("budget.csv")), "refused: not replaced");
        session.dialogs.willExportTo(documents.resolve("budget.csv")).willConfirmOverwrite(true);
        d.export(ExportFormat.CSV);
        List<List<String>> again = readCsv(new String(Files.readAllBytes(documents.resolve("budget.csv")), StandardCharsets.UTF_8).substring(1));
        assertEquals("1/3", again.get(3).get(2));
        session.dialogs.assertAllUsed();
    }

    @Test
    void theMarkdownExportIsATableOfThreeCellsThatKeepsTheNumbersAndEscapesTheMarkup() throws Exception {
        typeTheLinesToExport();
        session.dialogs.willSaveTo(documents.resolve("budget"));
        d.saveAs();
        session.dialogs.willExportTo(documents.resolve("budget"));
        d.export(ExportFormat.MARKDOWN);

        assertEquals(StatusTone.SUCCESS, d.view.statusTone(), d.view.statusMessage());
        List<String> lines = Files.readAllLines(documents.resolve("budget.md"), StandardCharsets.UTF_8);
        assertEquals("# budget", lines.get(0), "the title is the name of the document");
        assertEquals("| n° | Expression | Résultat |", lines.get(2));
        List<String> rows = lines.subList(4, lines.size());
        assertEquals(TO_EXPORT.size(), rows.size(), "the empty line is left out");
        for (String row : rows) {
            assertEquals(3, row.replace("\\|", "").split("\\|", -1).length - 2, row);
        }
        assertEquals("| 1 | Note ; a \"b\" \\| c |  |", rows.get(0), "the comment without its mark, the pipe escaped");
        assertTrue(rows.get(2).startsWith("| 4 | 1 / 3 | 0,333333333333…"), rows.get(2));
        assertTrue(rows.get(3).contains("≈ ") && rows.get(3).contains("±"), rows.get(3));
        assertTrue(rows.get(8).startsWith("| 10 | 2 + | Erreur : "), rows.get(8));
        assertTrue(rows.get(9).startsWith("| 11 | \\$2 \\* 3 | 6 |"), rows.get(9));
    }

    // RFC 4180, written for the test: a quoted field may hold the separator, a doubled quote or a line break.
    private static List<List<String>> readCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (current != '"') {
                    cell.append(current);
                } else if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                    cell.append('"');
                    index++;
                } else {
                    quoted = false;
                }
            } else if (current == '"') {
                quoted = true;
            } else if (current == ';') {
                record.add(cell.toString());
                cell.setLength(0);
            } else if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                record.add(cell.toString());
                cell.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                index++;
            } else {
                cell.append(current);
            }
        }
        assertFalse(quoted, "an unterminated quoted field");
        assertTrue(record.isEmpty() && cell.isEmpty(), "the last record ends with a line end");
        return records;
    }
}
