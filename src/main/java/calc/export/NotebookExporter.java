package calc.export;

import calc.engine.AtomicFileWriter;
import calc.expression.ParsedLine;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shares an evaluated notebook as text: Markdown for people, CSV for spreadsheets (docs/adr/0009-documents-et-exports.md,
 * sections 8 and 9). The result of every line is shown in the display mode passed by the caller. The native JSON export is
 * the notebook file itself ({@code NotebookCodec}); there is no other.
 *
 * <p>Empty lines are left out; the other lines keep the number they have in the notebook, so that a {@code $n} in an
 * expression still designates the row numbered {@code n}.
 */
public final class NotebookExporter {
    /** The statuses of the CSV column; a comment has none. */
    public static final String STATUS_EXACT = "exact";
    public static final String STATUS_APPROXIMATE = "approché";
    public static final String STATUS_ERROR = "erreur";

    static final String CSV_HEADER = "n°;Expression;Résultat affiché;Valeur exacte;Statut";
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CSV_SEPARATOR = ";";
    private static final String CRLF = "\r\n";
    // What a spreadsheet runs as a formula when a cell starts with it.
    private static final String FORMULA_STARTS = "=+-@\t\r";
    // What Markdown or a renderer could read as markup in a table cell ($ because some renderers read $...$ as maths).
    private static final String MARKDOWN_SPECIAL = "\\`*_[]<>|~&$";
    private static final String DEFAULT_TITLE = "Carnet";

    private NotebookExporter() {
    }

    // ------------------------------------------------------------------------------------------------ Markdown

    /** The notebook as a Markdown document: the title, then the table {@code | n° | Expression | Résultat |}. */
    public static String markdown(EvaluatedNotebook notebook, String title, DisplayMode mode) {
        Objects.requireNonNull(notebook, "notebook");
        Objects.requireNonNull(mode, "mode");
        String heading = title == null || title.isBlank() ? DEFAULT_TITLE : title;
        StringBuilder text = new StringBuilder("# ").append(escapeMarkdown(heading)).append("\n\n")
                .append("| n° | Expression | Résultat |\n| --- | --- | --- |\n");
        for (LineResult line : notebook.results()) {
            if (line.kind() == ParsedLine.Kind.EMPTY) {
                continue;
            }
            text.append("| ").append(line.number()).append(" | ")
                    .append(escapeMarkdown(line.kind() == ParsedLine.Kind.COMMENT ? commentText(line.text()) : line.text()))
                    .append(" | ").append(escapeMarkdown(markdownResult(line, mode))).append(" |\n");
        }
        return text.toString();
    }

    private static String markdownResult(LineResult line, DisplayMode mode) {
        if (line.isError()) {
            return "Erreur : " + line.error().message();
        }
        return line.hasValue() ? displayed(line, mode) : "";
    }

    // The comment as plain text: without its # marker.
    private static String commentText(String source) {
        String text = source.substring(source.indexOf('#') + 1);
        while (text.startsWith("#")) {
            text = text.substring(1);
        }
        return text.strip();
    }

    /** {@code text} safe in a table cell: Markdown characters escaped with a backslash, a line break written {@code <br>}. */
    static String escapeMarkdown(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '\r' || current == '\n') {
                escaped.append("<br>");
                if (current == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                if (MARKDOWN_SPECIAL.indexOf(current) >= 0) {
                    escaped.append('\\');
                }
                escaped.append(current);
            }
        }
        return escaped.toString();
    }

    // ------------------------------------------------------------------------------------------------ CSV

    /**
     * The notebook as a CSV file: UTF-8 with a byte order mark, {@code ;} as separator, CRLF line ends, fields quoted as in RFC 4180.
     * Columns: number, expression, displayed result, canonical exact value (exact results only), status.
     */
    public static byte[] csv(EvaluatedNotebook notebook, DisplayMode mode) {
        Objects.requireNonNull(notebook, "notebook");
        Objects.requireNonNull(mode, "mode");
        StringBuilder text = new StringBuilder(CSV_HEADER).append(CRLF);
        for (LineResult line : notebook.results()) {
            if (line.kind() == ParsedLine.Kind.EMPTY) {
                continue;
            }
            text.append(csvRow(csvCells(line, mode))).append(CRLF);
        }
        byte[] body = text.toString().getBytes(StandardCharsets.UTF_8);
        byte[] file = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, file, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, file, UTF8_BOM.length, body.length);
        return file;
    }

    private static List<String> csvCells(LineResult line, DisplayMode mode) {
        String number = Integer.toString(line.number());
        if (line.isError()) {
            return List.of(number, line.text(), line.error().message(), "", STATUS_ERROR);
        }
        if (!line.hasValue()) {
            return List.of(number, line.text(), "", "", "");
        }
        FormattedValue formatted = ValueFormatter.format(line.value(), mode);
        return List.of(number, line.text(), displayed(line, mode), formatted.exact() ? formatted.canonical() : "",
                formatted.exact() ? STATUS_EXACT : STATUS_APPROXIMATE);
    }

    static String csvRow(List<String> cells) {
        List<String> fields = new ArrayList<>(cells.size());
        for (String cell : cells) {
            fields.add(csvField(neutralizeFormula(cell)));
        }
        return String.join(CSV_SEPARATOR, fields);
    }

    /** {@code cell} prefixed with an apostrophe when a spreadsheet would run it as a formula (ADR 0009, section 9). */
    static String neutralizeFormula(String cell) {
        return !cell.isEmpty() && FORMULA_STARTS.indexOf(cell.charAt(0)) >= 0 ? "'" + cell : cell;
    }

    // RFC 4180: a field with the separator, a quote, or a line break is quoted, and its quotes are doubled.
    private static String csvField(String cell) {
        boolean quoted = cell.indexOf(';') >= 0 || cell.indexOf('"') >= 0 || cell.indexOf('\r') >= 0 || cell.indexOf('\n') >= 0;
        return quoted ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    // ------------------------------------------------------------------------------------------------ shared

    // The value as shown: an approximate value keeps its « ≈ » marker and gets the bound of its error.
    private static String displayed(LineResult line, DisplayMode mode) {
        FormattedValue formatted = ValueFormatter.format(line.value(), mode);
        if (formatted.exact()) {
            return formatted.text();
        }
        String text = formatted.text().startsWith("≈") ? formatted.text() : "≈ " + formatted.text();
        return text + formatted.errorBound().map(bound -> " (" + bound + ")").orElse("");
    }

    // ------------------------------------------------------------------------------------------------ files

    /** Writes {@link #markdown} to {@code file}, atomically, in UTF-8 without a byte order mark. */
    public static void writeMarkdown(Path file, EvaluatedNotebook notebook, String title, DisplayMode mode) throws IOException {
        AtomicFileWriter.write(file, markdown(notebook, title, mode).getBytes(StandardCharsets.UTF_8));
    }

    /** Writes {@link #csv} to {@code file}, atomically. */
    public static void writeCsv(Path file, EvaluatedNotebook notebook, DisplayMode mode) throws IOException {
        AtomicFileWriter.write(file, csv(notebook, mode));
    }
}
