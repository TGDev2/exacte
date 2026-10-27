package calc.persistence;

import calc.format.DisplayMode;
import calc.notebook.Line;
import calc.notebook.LineId;
import calc.notebook.Notebook;
import calc.notebook.NotebookException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Reads and writes the version 3 notebook file (docs/FORMAT-FICHIER.md, decided in docs/adr/0009-documents-et-exports.md).
 *
 * <p>Only the <em>source text</em> of each line is stored: a result is a function of the texts and is recalculated when the
 * notebook is loaded. Reading never trusts the file: the size is checked before the file is read, the JSON reader of
 * version 2 enforces its bounds while scanning, and the number of lines is checked before any line is looked at. A line
 * whose text the language would refuse is <strong>kept as it is</strong>; only a structure that is not a notebook refuses
 * the file. Unknown fields are ignored at every level.
 */
public final class NotebookCodec {
    private static final Logger LOGGER = Logger.getLogger(NotebookCodec.class.getName());

    public static final String FORMAT = "calc-notebook";
    public static final int VERSION = 3;

    private static final String INDENT = "  ";
    // A stable identifier as written in a file: decimal digits, no leading zero, small enough for a long.
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

    /** The bytes to write and how many lines they hold. */
    public record Encoded(byte[] bytes, int written) {
    }

    private NotebookCodec() {
    }

    // ------------------------------------------------------------------------------------------------ writing

    /**
     * The file for {@code notebook}. The application never writes a file it would refuse to read, and never shortens a
     * notebook to make it fit: a line over {@link StorageLimits#MAX_STRING_LENGTH} characters or a file over
     * {@link StorageLimits#MAX_NOTEBOOK_FILE_BYTES} bytes is a {@link NotebookFormatException}.
     */
    public static Encoded encode(Notebook notebook, DisplayMode displayMode) throws NotebookFormatException {
        StringBuilder text = new StringBuilder("{\n")
                .append(INDENT).append("\"format\": ").append(HistoryCodec.quote(FORMAT)).append(",\n")
                .append(INDENT).append("\"version\": ").append(VERSION).append(",\n")
                .append(INDENT).append("\"displayMode\": ").append(HistoryCodec.quote(displayModeId(displayMode))).append(",\n")
                .append(INDENT).append("\"lines\": [");
        List<Line> lines = notebook.lines();
        for (int index = 0; index < lines.size(); index++) {
            Line line = lines.get(index);
            if (line.text().length() > StorageLimits.MAX_STRING_LENGTH) {
                throw new NotebookFormatException(NotebookFormatException.Reason.LINE_TOO_LONG);
            }
            text.append(index == 0 ? "\n" : ",\n").append(INDENT).append(INDENT)
                    .append("{ \"id\": ").append(HistoryCodec.quote(Long.toString(line.id().value())))
                    .append(", \"source\": ").append(HistoryCodec.quote(line.text())).append(" }");
            // The text is never shorter in characters than in bytes: this stops a huge notebook before it is all built.
            if (text.length() > StorageLimits.MAX_NOTEBOOK_FILE_BYTES) {
                throw new NotebookFormatException(NotebookFormatException.Reason.TOO_LARGE);
            }
        }
        text.append(lines.isEmpty() ? "" : "\n" + INDENT).append("]\n}\n");
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > StorageLimits.MAX_NOTEBOOK_FILE_BYTES) {
            throw new NotebookFormatException(NotebookFormatException.Reason.TOO_LARGE);
        }
        return new Encoded(bytes, lines.size());
    }

    static String displayModeId(DisplayMode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------------ reading

    /**
     * Reads a file from disk without ever holding more than the limit in memory: only a regular file is read, its size is
     * checked first and the read itself is bounded, so neither a huge file nor one that grows while it is read can use more.
     */
    public static DecodedNotebook read(Path file) throws IOException, NotebookFormatException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new NotebookFormatException(NotebookFormatException.Reason.NOT_A_FILE);
        }
        if (attributes.size() > StorageLimits.MAX_NOTEBOOK_FILE_BYTES) {
            throw new NotebookFormatException(NotebookFormatException.Reason.TOO_LARGE);
        }
        try (InputStream in = Files.newInputStream(file)) {
            return decode(in.readNBytes(StorageLimits.MAX_NOTEBOOK_FILE_BYTES + 1));
        }
    }

    /**
     * Reads a whole file. A file that cannot be read as a whole is a {@link NotebookFormatException}. A file of a newer
     * version is read on a best-effort basis with the rules of version 3 and, when that fails, reported as not read.
     */
    public static DecodedNotebook decode(byte[] bytes) throws NotebookFormatException {
        if (bytes.length > StorageLimits.MAX_NOTEBOOK_FILE_BYTES) {
            throw new NotebookFormatException(NotebookFormatException.Reason.TOO_LARGE);
        }
        Object root = parseJson(decodeUtf8(bytes));
        if (!(root instanceof Map<?, ?> document) || !FORMAT.equals(document.get("format"))) {
            throw new NotebookFormatException(NotebookFormatException.Reason.NOT_A_NOTEBOOK);
        }
        int version = readVersion(document.get("version"));
        DisplayMode mode = readDisplayMode(document.get("displayMode"));
        if (version <= VERSION) {
            return decodeLines(document.get("lines"), version, mode);
        }
        try {
            return decodeLines(document.get("lines"), version, mode);
        } catch (NotebookFormatException unreadable) {
            return new DecodedNotebook(version, Notebook.empty(), mode, false, 0, 0);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws NotebookFormatException {
        try {
            return StrictUtf8.decode(bytes);
        } catch (CharacterCodingException exception) {
            throw new NotebookFormatException(NotebookFormatException.Reason.NOT_UTF8, exception);
        }
    }

    private static Object parseJson(String text) throws NotebookFormatException {
        try {
            return JsonParser.parse(text, StorageLimits.MAX_NOTEBOOK_JSON_VALUES);
        } catch (JsonException exception) {
            throw new NotebookFormatException(exception.reason().isLimit()
                    ? NotebookFormatException.Reason.JSON_LIMIT
                    : NotebookFormatException.Reason.MALFORMED_JSON, exception);
        }
    }

    private static int readVersion(Object node) throws NotebookFormatException {
        if (node instanceof JsonParser.JsonNumber number && number.literal().matches("[1-9][0-9]*") && number.asInt().isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Optional<Integer> version = node instanceof JsonParser.JsonNumber number ? number.asInt() : Optional.empty();
        if (version.isEmpty() || version.get() < VERSION) {
            throw new NotebookFormatException(NotebookFormatException.Reason.INVALID_VERSION);
        }
        return version.get();
    }

    // An absent or unknown mode is not an error: the notebook is the data, the mode is a preference.
    private static DisplayMode readDisplayMode(Object node) {
        if (node instanceof String id) {
            for (DisplayMode mode : DisplayMode.values()) {
                if (displayModeId(mode).equals(id)) {
                    return mode;
                }
            }
            LOGGER.warning("Unknown display mode in a notebook file, using auto");
        }
        return DisplayMode.AUTO;
    }

    private static DecodedNotebook decodeLines(Object node, int version, DisplayMode mode) throws NotebookFormatException {
        if (!(node instanceof List<?> elements)) {
            throw new NotebookFormatException(NotebookFormatException.Reason.MALFORMED_STRUCTURE);
        }
        if (elements.size() > Notebook.MAX_LINES) {
            throw new NotebookFormatException(NotebookFormatException.Reason.TOO_MANY_LINES);
        }
        long[] ids = new long[elements.size()];
        String[] sources = new String[elements.size()];
        Set<Long> taken = new HashSet<>();
        long largest = 0;
        int splitBreaks = 0;
        for (int index = 0; index < elements.size(); index++) {
            if (!(elements.get(index) instanceof Map<?, ?> element) || !(element.get("source") instanceof String source)) {
                throw new NotebookFormatException(NotebookFormatException.Reason.MALFORMED_STRUCTURE);
            }
            if (source.indexOf('\n') >= 0 || source.indexOf('\r') >= 0) {
                source = source.replace('\r', ' ').replace('\n', ' ');
                splitBreaks++;
            }
            sources[index] = source;
            long id = element.get("id") instanceof String text && ID.matcher(text).matches() ? Long.parseLong(text) : -1;
            ids[index] = id > 0 && taken.add(id) ? id : -1;
            largest = Math.max(largest, ids[index]);
        }
        // The first holder keeps its identifier; every other line, in file order, takes the next number: deterministic.
        int regenerated = 0;
        List<Line> lines = new ArrayList<>(elements.size());
        for (int index = 0; index < ids.length; index++) {
            if (ids[index] < 0) {
                ids[index] = ++largest;
                regenerated++;
            }
            lines.add(new Line(new LineId(ids[index]), sources[index]));
        }
        if (regenerated > 0) {
            LOGGER.warning(regenerated + " line identifiers were absent, malformed or duplicated and have been regenerated");
        }
        if (splitBreaks > 0) {
            LOGGER.warning(splitBreaks + " line texts held a line break, replaced with a space");
        }
        try {
            return new DecodedNotebook(version, Notebook.restore(lines), mode, true, regenerated, splitBreaks);
        } catch (NotebookException tooMany) {
            throw new NotebookFormatException(NotebookFormatException.Reason.TOO_MANY_LINES);
        }
    }
}
