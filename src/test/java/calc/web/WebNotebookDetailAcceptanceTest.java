package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.detail.LineDetailText;
import calc.expression.ParsedLine;
import calc.expression.Span;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookEditor;
import calc.notebook.NotebookException;
import calc.notebook.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The snapshot of {@link WebNotebook} gives each line its name, its sources, the lines that use it and its detail (SPEC
 * 4.4): four fields added at the end of every line object, after the nine that were there, nothing else moved.
 *
 * <p>The expected strings are built from the core ({@link EvaluatedNotebook}, {@link LineDetailText}) with the escaping the
 * snapshot already had, and a few rows are pinned as literals so that a wrong reading of the core cannot agree with
 * itself. Two choices the contract leaves open are fixed here as the core says them: the name of an assignment in error is
 * still its name ({@link LineResult#definedName()}), and the lines of {@code sources} and {@code usedBy} are listed in
 * ascending order once each, as the sentences of the detail do. The order of {@code sources} is only pinned where it does
 * not depend on that choice (see {@code theSourcesAreTheSameLinesWhateverTheirOrder}).
 */
@Tag("acceptance")
@DisplayName("WebNotebook : nom, sources, utilisations et détail de chaque ligne")
class WebNotebookDetailAcceptanceTest {
    private static final Path WEB_NOTEBOOK = Path.of("web", "src", "main", "java", "calc", "web", "WebNotebook.java");

    private static final List<String> OLD_ROW_KEYS = List.of("number", "text", "status", "result", "exactFraction", "errorBound",
            "error", "errorStart", "errorEnd");
    private static final List<String> NEW_ROW_KEYS = List.of("name", "sources", "usedBy", "detail");
    private static final List<String> SNAPSHOT_KEYS = List.of("mode", "canUndo", "canRedo", "message", "lines");

    private static final List<String> DEVIS_CUISINE = List.of("# Devis cuisine", "plan = 1\u00a0250,00", "meubles = 3\u00a0480",
            "pose = 35 * 12,5", "total = plan + meubles + pose", "taux = 0,2", "total * (1 + taux)", "ans / 12");

    /** A comment with a quote, a backslash and a no-break space, then every kind of line the snapshot has. */
    private static final List<String> EVERY_KIND = List.of("# Devis cuisine", "plan = 1\u00a0250,00", "meubles = 3\u00a0480",
            "pose = 35 * 12,5", "total = plan + meubles + pose", "taux = 0,2", "total * (1 + taux)", "ans / 12", "", "   ",
            "√2", "√2-√2", "1/3", "(1/3)*3", "10^40", "2 +", "x = 2 +", "1 / 0", "$99", "$?", "inconnue", "y = $2 * 2",
            "$3 + 1", "2 # a \"b\\", "# note \"q\" \\ a b", "1\t+ 1");

    private static final Pattern SOURCES = Pattern.compile("\"sources\":\\[([0-9,]*)\\]");

    // ---- The fields, their order, their values ----------------------------------------------------------------------

    @Test
    void everyLineObjectKeepsItsNineFieldsInOrderThenGetsTheFourNewOnes() {
        List<String> expected = new ArrayList<>(OLD_ROW_KEYS);
        expected.addAll(NEW_ROW_KEYS);
        for (DisplayMode mode : DisplayMode.values()) {
            String snapshot = new WebNotebook().load(String.join("\n", EVERY_KIND), mode.name());
            assertEquals(SNAPSHOT_KEYS, keysOf(snapshot), "the snapshot itself keeps its keys");
            List<String> rows = rowsOf(snapshot);
            assertEquals(EVERY_KIND.size(), rows.size());
            for (String row : rows) {
                assertEquals(expected, keysOf(row), row);
            }
        }
    }

    @Test
    void theSnapshotIsTheCoreOneWithTheFourFieldsAddedInEveryModeAfterEveryEntryPoint() throws NotebookException {
        EvaluatedNotebook evaluated = new NotebookEditor(Notebook.of(EVERY_KIND)).current();
        WebNotebook notebook = new WebNotebook();
        for (DisplayMode mode : DisplayMode.values()) {
            String expected = fromCore(mode, false, false, evaluated);
            assertEquals(expected, notebook.load(String.join("\n", EVERY_KIND), mode.name()));
            assertEquals(expected, notebook.setMode(mode.name()));
            assertEquals(expected, notebook.snapshot());
        }
    }

    @Test
    void theTotalOfTheKitchenQuoteHasItsNameItsSourcesItsUsersAndItsDetail() {
        String snapshot = new WebNotebook().load(String.join("\n", DEVIS_CUISINE), "AUTO");
        List<String> rows = rowsOf(snapshot);

        assertEquals("{\"number\":5,\"text\":\"total = plan + meubles + pose\",\"status\":\"EXACT\",\"result\":\"5167,5\","
                + "\"exactFraction\":\"10335/2\",\"errorBound\":null,\"error\":null,\"errorStart\":-1,\"errorEnd\":-1,"
                + "\"name\":\"total\",\"sources\":[2,3,4],\"usedBy\":[7],\"detail\":[\"= 1250 + 3480 + 437,5\",\"= 5167,5\","
                + "\"Sources : lignes 2, 3 et 4\",\"Utilisée par 1 ligne\"]}", rows.get(4));
        assertTrue(rows.get(4).contains("\"detail\":[\"= 1250 + 3480 + 437,5\",\"= 5167,5\",\"Sources : lignes 2, 3 et 4\","
                + "\"Utilisée par 1 ligne\"]"), "phrases 3 and 4 of the detail");

        // The quote around it, as far as the lines say: who reads whom.
        assertTrue(rows.get(1).contains("\"name\":\"plan\",\"sources\":[],\"usedBy\":[5],"), rows.get(1));
        assertTrue(rows.get(5).contains("\"name\":\"taux\",\"sources\":[],\"usedBy\":[7],"), rows.get(5));
        assertTrue(rows.get(6).contains("\"name\":null,\"sources\":[5,6],\"usedBy\":[8],"), rows.get(6));
        assertTrue(rows.get(7).contains("\"name\":null,\"sources\":[7],\"usedBy\":[],"), rows.get(7));
    }

    @Test
    void theDetailIsTheSentencesOfLineDetailTextOnTheTwoLinesTheirTestPins() {
        String snapshot = new WebNotebook().load("a = 2\nb = a * 3", "AUTO");
        assertTrue(rowsOf(snapshot).get(1).endsWith("\"name\":\"b\",\"sources\":[1],\"usedBy\":[],\"detail\":[\"= 2 * 3\",\"= 6\","
                + "\"Sources : ligne 1\",\"Utilisée par aucune ligne\"]}"), snapshot);
        assertTrue(rowsOf(snapshot).get(0).contains("\"name\":\"a\",\"sources\":[],\"usedBy\":[2],\"detail\":["), snapshot);
    }

    @Test
    void anApproximateLineGivesTheBoundsInItsDetail() throws NotebookException {
        String snapshot = new WebNotebook().load("√2", "AUTO");
        EvaluatedNotebook evaluated = new NotebookEditor(Notebook.of(List.of("√2"))).current();
        List<String> sentences = LineDetailText.of(evaluated, evaluated.result(1).id(), DisplayMode.AUTO);
        assertTrue(sentences.size() >= 3, sentences.toString());
        assertTrue(sentences.get(sentences.size() - 1).startsWith("Valeur garantie entre "), sentences.toString());
        assertTrue(snapshot.endsWith("\"errorStart\":-1,\"errorEnd\":-1,\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":"
                + jsonArray(sentences) + "}]}"), snapshot);
    }

    @Test
    void aCommentAnEmptyLineAndAnErrorHaveNoDetailAndNoNameUnlessTheyAreAssignments() {
        String snapshot = new WebNotebook().load("# note\n\n   \n2 +\n$99\nx = 2 +\ninconnue", "AUTO");
        List<String> rows = rowsOf(snapshot);

        assertTrue(rows.get(0).endsWith("\"errorStart\":-1,\"errorEnd\":-1,\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"),
                rows.get(0));
        assertTrue(rows.get(1).endsWith("\"errorEnd\":-1,\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(1));
        assertTrue(rows.get(2).endsWith("\"errorEnd\":-1,\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(2));
        assertTrue(rows.get(3).endsWith("\"errorStart\":2,\"errorEnd\":3,\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"),
                rows.get(3));
        assertTrue(rows.get(4).endsWith("\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(4));
        assertTrue(rows.get(5).endsWith("\"name\":\"x\",\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(5));
        assertTrue(rows.get(6).endsWith("\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(6));
    }

    @Test
    void theDetailFollowsTheCurrentMode() {
        WebNotebook notebook = new WebNotebook();
        String fraction = rowsOf(notebook.load("1/3", "FRACTION")).get(0);
        assertTrue(fraction.contains("\"detail\":[") && fraction.contains("\"= 1/3\""), fraction);
        assertFalse(fraction.contains("0,333"), fraction);

        String automatic = rowsOf(notebook.setMode("AUTO")).get(0);
        assertTrue(automatic.contains("\"= 0,333333333333…\""), automatic);
        assertFalse(automatic.contains("\"= 1/3\""), automatic);

        String scientific = rowsOf(notebook.setMode("SCIENTIFIC")).get(0);
        assertTrue(scientific.contains("\"= 3,33333333333… × 10⁻¹\""), scientific);
    }

    @Test
    void theSourcesAreTheSameLinesWhateverTheirOrder() {
        String snapshot = new WebNotebook().load("a = 1\nb = 2\nc = 3\nc + a + b + a + $2", "AUTO");
        List<String> rows = rowsOf(snapshot);

        Matcher sources = SOURCES.matcher(rows.get(3));
        assertTrue(sources.find(), rows.get(3));
        List<String> listed = new ArrayList<>(List.of(sources.group(1).split(",")));
        listed.sort(null);
        assertEquals(List.of("1", "2", "3"), listed, "each line once, whatever the order: " + rows.get(3));
        for (int index = 0; index < 3; index++) {
            assertTrue(rows.get(index).contains("\"usedBy\":[4],"), rows.get(index));
        }
        assertTrue(rows.get(3).contains("\"Sources : lignes 1, 2 et 3\""), rows.get(3));
    }

    // ---- The snapshot stays the one of the core after commands --------------------------------------------------------

    @Test
    void theNewFieldsAreCalculatedAgainAfterEveryCommandAndEveryUndo() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("a = 2\nb = a * 3\n√b", "AUTO");

        String appended = notebook.append("$3 + 1");
        assertTrue(rowsOf(appended).get(2).contains("\"usedBy\":[4],"), appended);
        assertTrue(rowsOf(appended).get(3).contains("\"sources\":[3],"), appended);

        String moved = notebook.move(1, 3);
        assertTrue(rowsOf(moved).get(2).contains("\"name\":\"a\","), moved);
        assertTrue(rowsOf(moved).get(0).contains("\"name\":\"b\",\"sources\":[],"), "b lost its source above: " + rowsOf(moved).get(0));

        String undone = notebook.undo();
        assertTrue(rowsOf(undone).get(0).contains("\"name\":\"a\",\"sources\":[],\"usedBy\":[2],"), undone);

        String deleted = notebook.delete(1);
        assertTrue(rowsOf(deleted).get(0).contains("\"name\":\"b\",\"sources\":[],"), deleted);
        assertTrue(rowsOf(deleted).get(0).contains("\"status\":\"ERROR\""), deleted);
    }

    /** Commands in every order, one notebook driven by the core on the side: same snapshot at every step. */
    @Test
    void aLongSequenceOfCommandsGivesTheSnapshotOfTheCoreAtEveryStep() throws NotebookException {
        // One source per line at most, so that the order of the sources never matters.
        String[] texts = {"1", "2,5", "$1 + 1", "$2 * 2", "x = 2", "x * 3", "# note", "", "√2", "ans + 1", "$? + 1", "2 +",
                "1 / 0", "1/3", "y = x + 1"};
        Random random = new Random(20261124);
        NotebookEditor editor = new NotebookEditor();
        WebNotebook notebook = new WebNotebook();
        DisplayMode[] modes = DisplayMode.values();
        for (int step = 0; step < 400; step++) {
            int size = editor.notebook().size();
            int number = 1 + random.nextInt(Math.max(size, 1));
            String text = texts[random.nextInt(texts.length)];
            int command = size == 0 ? random.nextInt(2) : size > 20 ? 4 : random.nextInt(9);
            String snapshot;
            switch (command) {
                case 0 -> {
                    editor.apply(new NotebookCommand.Append(text));
                    snapshot = notebook.append(text);
                }
                case 1 -> {
                    if (editor.canUndo()) {
                        editor.undo();
                    }
                    snapshot = notebook.undo();
                }
                case 2 -> {
                    editor.apply(new NotebookCommand.InsertAfter(editor.notebook().line(number).id(), text));
                    snapshot = notebook.insertAfter(number, text);
                }
                case 3 -> {
                    editor.apply(new NotebookCommand.Edit(editor.notebook().line(number).id(), text));
                    snapshot = notebook.edit(number, text);
                }
                case 4 -> {
                    editor.apply(new NotebookCommand.Delete(editor.notebook().line(number).id()));
                    snapshot = notebook.delete(number);
                }
                case 5 -> {
                    int to = 1 + random.nextInt(size);
                    editor.apply(new NotebookCommand.Move(editor.notebook().line(number).id(), to - 1));
                    snapshot = notebook.move(number, to);
                }
                case 6 -> {
                    if (editor.canRedo()) {
                        editor.redo();
                    }
                    snapshot = notebook.redo();
                }
                default -> snapshot = notebook.setMode(modes[random.nextInt(modes.length)].name());
            }
            DisplayMode mode = DisplayMode.valueOf(modeOf(snapshot));
            assertEquals(fromCore(mode, editor.canUndo(), editor.canRedo(), editor.current()), snapshot, "step " + step);
        }
    }

    // ---- The strings are escaped as they were ---------------------------------------------------------------------------

    @Test
    void quotesBackslashesControlCharactersAndNoBreakSpacesAreEscapedAsBefore() {
        String snapshot = new WebNotebook().load("2 # a \"b\\\n1\t+ 1\n# c\u0001d e", "AUTO");
        List<String> rows = rowsOf(snapshot);

        assertTrue(rows.get(0).startsWith("{\"number\":1,\"text\":\"2 # a \\\"b\\\\\",\"status\":\"EXACT\",\"result\":\"2\","),
                rows.get(0));
        assertTrue(rows.get(0).endsWith("\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":" + jsonArray(detailOf("2 # a \"b\\"))
                + "}"), rows.get(0));
        assertTrue(rows.get(1).contains("\"text\":\"1\\u0009+ 1\""), rows.get(1));
        assertTrue(rows.get(2).contains("\"text\":\"# c\\u0001d e\""), rows.get(2));
        assertTrue(rows.get(2).endsWith("\"name\":null,\"sources\":[],\"usedBy\":[],\"detail\":[]}"), rows.get(2));
        assertTrue(snapshot.contains(" "), "no-break spaces are kept, not escaped");
        assertFalse(snapshot.contains("\\u00a0") || snapshot.contains("\\u00A0"), snapshot);
    }

    // ---- The dependencies ----------------------------------------------------------------------------------------------

    @Test
    void webNotebookGetsTheDetailFromLineDetailTextAndImportsNothingOfTheDesktop() throws IOException {
        String source = Files.readString(WEB_NOTEBOOK, UTF_8);
        assertTrue(source.contains("LineDetailText"), "Every sentence of the detail comes from LineDetailText.");
        assertTrue(source.contains("calc.detail."), "WebNotebook imports calc.detail.");
        for (String forbidden : List.of("import calc.ui.", "import java.awt.", "import javax.swing.", "import calc.persistence.",
                "import calc.document.", "import calc.export.", "import java.nio.", "org.teavm", "JSExport")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------------

    /** The line objects of a snapshot, as strings. */
    private static List<String> rowsOf(String snapshot) {
        int start = snapshot.indexOf("\"lines\":[") + "\"lines\":[".length();
        assertTrue(start >= "\"lines\":[".length(), snapshot);
        List<String> rows = new ArrayList<>();
        int depth = 0;
        int begin = -1;
        boolean inString = false;
        for (int index = start; index < snapshot.length(); index++) {
            char character = snapshot.charAt(index);
            if (inString) {
                if (character == '\\') {
                    index++;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                inString = true;
            } else if (character == '{') {
                if (depth++ == 0) {
                    begin = index;
                }
            } else if (character == '}') {
                if (--depth == 0) {
                    rows.add(snapshot.substring(begin, index + 1));
                }
            } else if (character == ']' && depth == 0) {
                break;
            }
        }
        return rows;
    }

    /** The keys of a JSON object, in the order written; the values (strings, arrays, objects) are skipped. */
    private static List<String> keysOf(String object) {
        List<String> keys = new ArrayList<>();
        int index = 1;
        while (index < object.length() && object.charAt(index) != '}') {
            assertEquals('"', object.charAt(index), "a key at " + index + " of " + object);
            int end = endOfString(object, index);
            keys.add(object.substring(index + 1, end));
            assertEquals(':', object.charAt(end + 1), object);
            index = endOfValue(object, end + 2);
            if (object.charAt(index) == ',') {
                index++;
            }
        }
        return keys;
    }

    private static int endOfString(String text, int quote) {
        int index = quote + 1;
        while (text.charAt(index) != '"') {
            index += text.charAt(index) == '\\' ? 2 : 1;
        }
        return index;
    }

    /** The index of the character that follows the value starting at {@code start}. */
    private static int endOfValue(String text, int start) {
        char first = text.charAt(start);
        if (first == '"') {
            return endOfString(text, start) + 1;
        }
        if (first != '[' && first != '{') {
            int index = start;
            while (text.charAt(index) != ',' && text.charAt(index) != '}') {
                index++;
            }
            return index;
        }
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '"') {
                index = endOfString(text, index);
            } else if (character == '[' || character == '{') {
                depth++;
            } else if ((character == ']' || character == '}') && --depth == 0) {
                return index + 1;
            }
        }
        throw new AssertionError("unterminated value in " + text);
    }

    private static String modeOf(String snapshot) {
        Matcher matcher = Pattern.compile("^\\{\"mode\":\"([A-Z]+)\"").matcher(snapshot);
        assertTrue(matcher.find(), snapshot);
        return matcher.group(1);
    }

    private static List<String> detailOf(String line) {
        try {
            EvaluatedNotebook evaluated = new NotebookEditor(Notebook.of(List.of(line))).current();
            return LineDetailText.of(evaluated, evaluated.result(1).id(), DisplayMode.AUTO);
        } catch (NotebookException unexpected) {
            throw new AssertionError(unexpected);
        }
    }

    /** The snapshot that the core gives, the four new fields last. */
    private static String fromCore(DisplayMode mode, boolean canUndo, boolean canRedo, EvaluatedNotebook evaluated) {
        StringJoiner lines = new StringJoiner(",", "[", "]");
        for (LineResult result : evaluated.results()) {
            lines.add(rowFromCore(result, mode, evaluated));
        }
        return "{\"mode\":\"" + mode.name() + "\",\"canUndo\":" + canUndo + ",\"canRedo\":" + canRedo + ",\"message\":null,\"lines\":"
                + lines + "}";
    }

    private static String rowFromCore(LineResult result, DisplayMode mode, EvaluatedNotebook evaluated) {
        String status;
        String shown = null;
        String fraction = null;
        String bound = null;
        String error = null;
        int errorStart = -1;
        int errorEnd = -1;
        if (result.isError()) {
            Span span = result.error().span();
            int end = Math.min(span.end(), result.text().length());
            boolean marked = span.length() > 0 && span.start() < end;
            status = "ERROR";
            error = result.error().message();
            errorStart = marked ? span.start() : -1;
            errorEnd = marked ? end : -1;
        } else if (result.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(result.value(), mode);
            status = formatted.exact() ? "EXACT" : "APPROXIMATE";
            shown = formatted.text();
            fraction = formatted.exactFraction().orElse(null);
            bound = formatted.errorBound().orElse(null);
        } else {
            status = result.kind() == ParsedLine.Kind.COMMENT ? "COMMENT" : "EMPTY";
        }
        TreeSet<Integer> sources = new TreeSet<>();
        for (Source source : result.sources()) {
            sources.add(evaluated.result(source.target()).number());
        }
        TreeSet<Integer> users = new TreeSet<>();
        evaluated.usedBy(result.id()).forEach(user -> users.add(evaluated.result(user).number()));
        return "{\"number\":" + result.number() + ",\"text\":" + json(result.text()) + ",\"status\":\"" + status + "\",\"result\":"
                + json(shown) + ",\"exactFraction\":" + json(fraction) + ",\"errorBound\":" + json(bound) + ",\"error\":"
                + json(error) + ",\"errorStart\":" + errorStart + ",\"errorEnd\":" + errorEnd + ",\"name\":" + json(result.definedName())
                + ",\"sources\":" + numbers(sources) + ",\"usedBy\":" + numbers(users) + ",\"detail\":"
                + jsonArray(LineDetailText.of(evaluated, result.id(), mode)) + "}";
    }

    private static String numbers(Iterable<Integer> numbers) {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        numbers.forEach(number -> joined.add(Integer.toString(number)));
        return joined.toString();
    }

    private static String jsonArray(List<String> texts) {
        StringJoiner joined = new StringJoiner(",", "[", "]");
        texts.forEach(text -> joined.add(json(text)));
        return joined.toString();
    }

    /** Only what JSON forbids is escaped, as the snapshot did before. */
    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            if (character == '"' || character == '\\') {
                text.append('\\').append(character);
            } else if (character < 0x20) {
                text.append(String.format("\\u%04x", (int) character));
            } else {
                text.append(character);
            }
        }
        return text.append('"').toString();
    }
}
