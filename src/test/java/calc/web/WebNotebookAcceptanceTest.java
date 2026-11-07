package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.expression.ParsedLine;
import calc.expression.Span;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineError;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookEditor;
import calc.notebook.NotebookException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The notebook driven from JavaScript, on the JVM: the snapshot is compared as a whole string, because the order of the
 * keys is part of the contract and the page reads it as it is.
 *
 * <p>Three choices the contract leaves open, fixed here so that the JVM and Node agree: a text that does not exist is the
 * JSON {@code null} and never an empty string (as {@code exactFraction} and {@code error} in the example of the contract);
 * {@code \r\n} received by a command may give one or two spaces; only control characters whose escape has no letter are
 * pinned, as the case of the hexadecimal digits does not matter to a JSON reader.
 */
@Tag("acceptance")
@DisplayName("WebNotebook : le carnet piloté depuis JavaScript, sur la JVM")
class WebNotebookAcceptanceTest {
    private static final Path WEB_SOURCES = Path.of("web", "src", "main", "java", "calc", "web");
    private static final Path WEB_NOTEBOOK = WEB_SOURCES.resolve("WebNotebook.java");
    private static final Path WEB_EXPORTS = WEB_SOURCES.resolve("WebExports.java");
    private static final Path GUARD = Path.of("src", "test", "java", "calc", "web", "WebDependenciesTest.java");
    private static final List<String> FORBIDDEN_IMPORTS = List.of("java.nio", "java.awt", "javax.swing", "calc.persistence",
            "calc.document", "calc.export", "calc.ui");
    private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+)", Pattern.MULTILINE);

    private static final String ROOT_AUTO = "≈ 1,41421356237…";
    private static final String ROOT_SCIENTIFIC = "≈ 1,41421356237… × 10⁰";
    private static final String ROOT_BOUND = "± 2,1 × 10⁻²⁵";
    private static final String THIRD_AUTO = "0,333333333333…";
    private static final String THIRD_SCIENTIFIC = "3,33333333333… × 10⁻¹";
    private static final String MISSING_OPERAND = "Opérande manquant après « + ».";
    private static final String DELETED_REFERENCE = "Référence vers une ligne supprimée.";
    private static final int MAX_LINE_LENGTH = 16384;
    private static final String LONGEST_LINE = "# " + "x".repeat(MAX_LINE_LENGTH - 2);
    private static final String TOO_LONG_LINE = LONGEST_LINE + "x";

    private static final Pattern MESSAGE = Pattern.compile("\"message\":(null|\"((?:[^\"\\\\]|\\\\.)*)\"),\"lines\":\\[");
    /** What a message taken as it is from an exception of the core or of the JDK says, instead of a French sentence. */
    private static final Pattern NOT_A_FRENCH_SENTENCE = Pattern.compile(
            "Exception|Error\\b|java\\.|\\bnull\\b|\\bno line\\b|\\bcannot\\b|\\bLineId\\b|\\bIndex\\b|\\binvalid\\b");

    // ---- The snapshot ------------------------------------------------------------------------------------------------

    @Test
    void theExampleOfTheContractIsReproducedToTheCharacter() {
        assertEquals("{\"mode\":\"AUTO\",\"canUndo\":false,\"canRedo\":false,\"message\":null,\"lines\":[{\"number\":1,"
                + "\"text\":\"√2\",\"status\":\"APPROXIMATE\",\"result\":\"≈ 1,41421356237…\",\"exactFraction\":null,"
                + "\"errorBound\":\"± 2,1 × 10⁻²⁵\",\"error\":null,\"errorStart\":-1,\"errorEnd\":-1}]}",
                new WebNotebook().load("√2", "AUTO"));
    }

    @Test
    void aNewNotebookIsEmptyInTheAutoMode() {
        WebNotebook notebook = new WebNotebook();
        assertEquals(snapshot("AUTO", false, false, null), notebook.snapshot());
        assertEquals("", notebook.texts());
    }

    @Test
    void oneThirdTimesThreeIsExactlyOne() {
        assertEquals("{\"mode\":\"AUTO\",\"canUndo\":false,\"canRedo\":false,\"message\":null,\"lines\":[{\"number\":1,"
                + "\"text\":\"(1/3)*3\",\"status\":\"EXACT\",\"result\":\"1\",\"exactFraction\":null,\"errorBound\":null,"
                + "\"error\":null,\"errorStart\":-1,\"errorEnd\":-1}]}",
                new WebNotebook().load("(1/3)*3", "AUTO"));
    }

    @Test
    void oneThirdIsExactWithItsFraction() {
        assertEquals(snapshot("AUTO", false, false, null, value(1, "1/3", "EXACT", THIRD_AUTO, "1/3", null)),
                new WebNotebook().load("1/3", "AUTO"));
    }

    @Test
    void emptyLinesAndCommentsHaveNoResult() {
        assertEquals(snapshot("AUTO", false, false, null,
                row(1, "# Devis", "COMMENT", null, null, null, null, -1, -1),
                row(2, "", "EMPTY", null, null, null, null, -1, -1),
                row(3, "   ", "EMPTY", null, null, null, null, -1, -1),
                value(4, "2 # deux", "EXACT", "2", null, null)),
                new WebNotebook().load("# Devis\n\n   \n2 # deux", "AUTO"));
    }

    @Test
    void theJsonEscapesQuotesBackslashesAndControlCharactersAndNothingElse() {
        assertEquals("{\"number\":1,\"text\":\"\\\"\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":\"Caractère inattendu « \\\" ».\",\"errorStart\":0,\"errorEnd\":1}",
                onlyRow(new WebNotebook().load("\"", "AUTO")));
        assertEquals("{\"number\":1,\"text\":\"\\\\\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":\"Caractère inattendu « \\\\ ».\",\"errorStart\":0,\"errorEnd\":1}",
                onlyRow(new WebNotebook().load("\\", "AUTO")));
        assertEquals("{\"number\":1,\"text\":\"1\\u0009+ 1\",\"status\":\"EXACT\",\"result\":\"2\",\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":null,\"errorStart\":-1,\"errorEnd\":-1}",
                onlyRow(new WebNotebook().load("1\t+ 1", "AUTO")));
        assertTrue(new WebNotebook().load("\u0001", "AUTO").contains("\"text\":\"\\u0001\""));
        assertTrue(new WebNotebook().load("\u0019", "AUTO").contains("\"text\":\"\\u0019\""));
        assertTrue(new WebNotebook().load("# é / ≈ √ … ⁻ ²", "AUTO").contains("\"text\":\"# é / ≈ √ … ⁻ ²\""),
                "Other characters pass as they are.");
    }

    @Test
    void surrogatesPassAsTheyAreAndTheSpanCountsUtf16Units() {
        assertEquals("{\"number\":1,\"text\":\"1 😀 2\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":\"Caractère inattendu « 😀 ».\",\"errorStart\":2,\"errorEnd\":4}",
                onlyRow(new WebNotebook().load("1 😀 2", "AUTO")));
        assertEquals("{\"number\":1,\"text\":\"\uD83D\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":\"Caractère inattendu « U+D83D ».\",\"errorStart\":0,\"errorEnd\":1}",
                onlyRow(new WebNotebook().load("\uD83D", "AUTO")));
    }

    // ---- The display modes -------------------------------------------------------------------------------------------

    @Test
    void setModeShowsTheRootInEachMode() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("√2", "AUTO");
        for (Map.Entry<String, String> shown : Map.of("AUTO", ROOT_AUTO, "DECIMAL", ROOT_AUTO, "FRACTION", ROOT_AUTO,
                "SCIENTIFIC", ROOT_SCIENTIFIC).entrySet()) {
            assertEquals(snapshot(shown.getKey(), false, false, null,
                    value(1, "√2", "APPROXIMATE", shown.getValue(), null, ROOT_BOUND)), notebook.setMode(shown.getKey()));
            assertEquals("√2", notebook.texts());
        }
    }

    @Test
    void setModeShowsOneThirdInEachMode() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1/3", "AUTO");
        for (Map.Entry<String, String> shown : Map.of("AUTO", THIRD_AUTO, "DECIMAL", THIRD_AUTO, "FRACTION", "1/3",
                "SCIENTIFIC", THIRD_SCIENTIFIC).entrySet()) {
            assertEquals(snapshot(shown.getKey(), false, false, null,
                    value(1, "1/3", "EXACT", shown.getValue(), "1/3", null)), notebook.setMode(shown.getKey()));
        }
    }

    @Test
    void loadChoosesTheModeOfTheNotebook() {
        assertEquals(snapshot("SCIENTIFIC", false, false, null, value(1, "√2", "APPROXIMATE", ROOT_SCIENTIFIC, null, ROOT_BOUND)),
                new WebNotebook().load("√2", "SCIENTIFIC"));
    }

    @Test
    void changingTheModeIsNotACommandOfTheNotebook() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1", "AUTO");
        notebook.append("2");
        notebook.undo();

        String shown = notebook.setMode("FRACTION");
        assertTrue(shown.startsWith("{\"mode\":\"FRACTION\",\"canUndo\":false,\"canRedo\":true,\"message\":null,"), shown);
        assertTrue(notebook.redo().startsWith("{\"mode\":\"FRACTION\",\"canUndo\":true,\"canRedo\":false,\"message\":null,"),
                "Redo keeps the mode.");
        assertEquals("1\n2", notebook.texts());
    }

    @Test
    void anUnknownModeIsRefusedWithoutChangingTheModeNorTheLines() {
        for (String mode : new String[] {"BOGUS", "", "auto", "Fraction", null}) {
            WebNotebook notebook = new WebNotebook();
            notebook.load("1/3", "FRACTION");
            String before = notebook.snapshot();

            assertRefused(before, notebook, notebook.setMode(mode));
            assertRefused(before, notebook, notebook.load("2", mode));
            assertEquals("1/3", notebook.texts());
        }
    }

    @Test
    void everyModeGivesWhatTheCoreFormatsLineByLine() throws NotebookException {
        List<String> lines = List.of("# Devis", "prix = 12,5", "prix * 3", "(1/3)*3", "1/3", "√2", "√2-√2", "10^40", "1/7", "",
                "2 +", "1 / 0", "$99", "$?", "inconnue");
        WebNotebook notebook = new WebNotebook();
        for (DisplayMode mode : DisplayMode.values()) {
            String expected = fromCore(mode, false, false, new NotebookEditor(Notebook.of(lines)).current());
            assertEquals(expected, notebook.load(String.join("\n", lines), mode.name()));
            assertEquals(expected, notebook.setMode(mode.name()));
            assertEquals(expected, notebook.snapshot());
        }
    }

    // ---- Errors ------------------------------------------------------------------------------------------------------

    @Test
    void aLineInErrorHasTheMessageAndTheSpanOfTheCore() throws NotebookException {
        LineError error = onlyError("2 +");
        assertEquals(MISSING_OPERAND, error.message());
        assertEquals(new Span(2, 3), error.span());

        assertEquals(snapshot("AUTO", false, false, null,
                row(1, "2 +", "ERROR", null, null, null, error.message(), error.span().start(), error.span().end())),
                new WebNotebook().load("2 +", "AUTO"));
    }

    /**
     * Applies the rule of NotebookPresenter.row to what the core gives. As far as a scan of 60 016 lines shows, no text
     * reaches an empty span or one outside the line: the -1 of that branch only shows on lines without an error here.
     */
    @Test
    void theFaultyPartFollowsTheRuleOfThePresenterOnEveryLine() throws NotebookException {
        String[] tokens = {"1", "0", "2,5", "x", "ans", "=", "+", "-", "*", "/", "^", "(", ")", "√", "$1", "$?", "#", " ", "-1",
                "√(-1)", "%", "€", "😀", "|", ",", "$", "$0", "$99"};
        Random random = new Random(20261107);
        WebNotebook notebook = new WebNotebook();
        for (int count = 0; count < 2000; count++) {
            StringBuilder line = new StringBuilder();
            for (int token = random.nextInt(5); token >= 0; token--) {
                line.append(tokens[random.nextInt(tokens.length)]);
            }
            String text = line.toString();
            assertEquals(fromCore(DisplayMode.AUTO, false, false, new NotebookEditor(Notebook.of(List.of(text))).current()),
                    notebook.load(text, "AUTO"), text);
        }
    }

    // ---- Move, undo, redo --------------------------------------------------------------------------------------------

    @Test
    void movingALineRenumbersItsReferencesThenUndoAndRedoRestoreThem() {
        WebNotebook notebook = new WebNotebook();
        String loaded = snapshot("AUTO", false, false, null, value(1, "10", "EXACT", "10", null, null),
                value(2, "3", "EXACT", "3", null, null), value(3, "$1 + 1", "EXACT", "11", null, null));
        assertEquals(loaded, notebook.load("10\n3\n$1 + 1", "AUTO"));

        String moved = snapshot("AUTO", true, false, null, value(1, "3", "EXACT", "3", null, null),
                value(2, "10", "EXACT", "10", null, null), value(3, "$2 + 1", "EXACT", "11", null, null));
        assertEquals(moved, notebook.move(2, 1));
        assertEquals("3\n10\n$2 + 1", notebook.texts());

        String undone = snapshot("AUTO", false, true, null, value(1, "10", "EXACT", "10", null, null),
                value(2, "3", "EXACT", "3", null, null), value(3, "$1 + 1", "EXACT", "11", null, null));
        assertEquals(undone, notebook.undo());
        assertEquals("10\n3\n$1 + 1", notebook.texts());

        assertEquals(moved, notebook.redo());
        assertEquals("3\n10\n$2 + 1", notebook.texts());
    }

    @Test
    void moveTakesLineNumbersInBothDirections() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("10\n3\n$1 + 1", "AUTO");

        notebook.move(1, 3);
        assertEquals("3\n$3 + 1\n10", notebook.texts());

        notebook.move(3, 1);
        assertEquals("10\n3\n$1 + 1", notebook.texts());
    }

    @Test
    void aCommandThatChangesNothingLeavesNoHistory() {
        WebNotebook notebook = new WebNotebook();
        String loaded = notebook.load("1\n2", "AUTO");

        assertEquals(loaded, notebook.move(2, 2));
        assertEquals(loaded, notebook.edit(1, "1"));
        assertEquals(snapshot("AUTO", false, false, null), new WebNotebook().clear());
    }

    @Test
    void undoAndRedoWithoutHistoryChangeNothingAndThrowNothing() {
        WebNotebook empty = new WebNotebook();
        assertEquals(empty.snapshot(), empty.undo());
        assertEquals(empty.snapshot(), empty.redo());

        WebNotebook loaded = new WebNotebook();
        String before = loaded.load("1\n$1 + 1", "AUTO");
        assertEquals(before, loaded.undo());
        assertEquals(before, loaded.redo());
        assertEquals(before, loaded.snapshot());

        loaded.append("3");
        String withOneCommand = loaded.undo();
        assertEquals(withOneCommand, loaded.undo(), "the only command is already undone");
        String redone = loaded.redo();
        assertEquals(redone, loaded.redo(), "the only command is already redone");
    }

    @Test
    void loadReplacesTheNotebookAndStartsANewHistory() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1", "AUTO");
        notebook.append("2");
        notebook.append("3");
        notebook.undo();

        assertEquals(snapshot("FRACTION", false, false, null, value(1, "7", "EXACT", "7", null, null)),
                notebook.load("7", "FRACTION"));
        assertEquals("7", notebook.texts());
    }

    @Test
    void loadSplitsOnLineFeedsAndTheEmptyTextHasNoLine() {
        WebNotebook notebook = new WebNotebook();

        assertEquals(snapshot("AUTO", false, false, null), notebook.load("", "AUTO"));
        assertEquals("", notebook.texts());

        assertEquals(snapshot("AUTO", false, false, null, value(1, "1", "EXACT", "1", null, null),
                row(2, "", "EMPTY", null, null, null, null, -1, -1)), notebook.load("1\n", "AUTO"));
        assertEquals("1\n", notebook.texts());

        assertEquals(snapshot("AUTO", false, false, null, row(1, "", "EMPTY", null, null, null, null, -1, -1),
                row(2, "", "EMPTY", null, null, null, null, -1, -1)), notebook.load("\n", "AUTO"));
        assertEquals("\n", notebook.texts());
    }

    @Test
    void whatTextsGivesLoadsBackTheSameNotebook() {
        WebNotebook notebook = new WebNotebook();
        String first = notebook.load("# a\n\n1/3\n$3 * 3\n2 +\n√2", "AUTO");
        assertEquals(first, new WebNotebook().load(notebook.texts(), "AUTO"));
    }

    // ---- The commands, against NotebookEditor ------------------------------------------------------------------------

    @Test
    void theCommandsGiveTheTextsOfTheNotebookWithItsRenumbering() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1\n$1 + 1\n$2 + $1", "AUTO");

        notebook.insertAfter(2, "7");
        assertEquals("1\n$1 + 1\n7\n$2 + $1", notebook.texts());

        notebook.insertBefore(1, "8");
        assertEquals("8\n1\n$2 + 1\n7\n$3 + $2", notebook.texts());

        notebook.append("$1");
        assertEquals("8\n1\n$2 + 1\n7\n$3 + $2\n$1", notebook.texts());

        notebook.edit(4, "9");
        assertEquals("8\n1\n$2 + 1\n9\n$3 + $2\n$1", notebook.texts());

        notebook.undo();
        assertEquals("8\n1\n$2 + 1\n7\n$3 + $2\n$1", notebook.texts());
    }

    @Test
    void deletingALineLeavesADeletedReferenceInTheLinesThatUsedIt() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1\n2\n$1 + $2", "AUTO");

        assertEquals(snapshot("AUTO", true, false, null, value(1, "2", "EXACT", "2", null, null),
                row(2, "$? + $1", "ERROR", null, null, null, DELETED_REFERENCE, 0, 2)), notebook.delete(1));
        assertEquals("2\n$? + $1", notebook.texts());

        notebook.undo();
        assertEquals("1\n2\n$1 + $2", notebook.texts());
    }

    @Test
    void clearLeavesNoLineAndUndoGivesThemBack() {
        WebNotebook notebook = new WebNotebook();
        String loaded = notebook.load("1\n$1 + 1", "AUTO");

        assertEquals(snapshot("AUTO", true, false, null), notebook.clear());
        assertEquals("", notebook.texts());

        assertTrue(notebook.undo().endsWith(loaded.substring(loaded.indexOf("\"lines\":"))));
        assertEquals("1\n$1 + 1", notebook.texts());
    }

    @Test
    void anAppendOnAnEmptyNotebookGivesItsFirstLine() {
        WebNotebook notebook = new WebNotebook();
        assertEquals(snapshot("AUTO", true, false, null, value(1, "1", "EXACT", "1", null, null)), notebook.append("1"));
    }

    /** Every command, numbers from 1 on one side and identifiers and an index from 0 on the other: same text, same snapshot. */
    @Test
    void aLongSequenceOfCommandsMatchesNotebookEditorAtEveryStep() throws NotebookException {
        String[] texts = {"1", "2,5", "$1 + 1", "$2 * $1", "$3", "x = 2", "x * 3", "# note", "", "√2", "ans + 1", "$? + 1", "2 +",
                "1 / 0", "1/3"};
        Random random = new Random(20261107);
        NotebookEditor editor = new NotebookEditor();
        WebNotebook notebook = new WebNotebook();
        for (int step = 0; step < 1500; step++) {
            int size = editor.notebook().size();
            int number = 1 + random.nextInt(Math.max(size, 1));
            String text = texts[random.nextInt(texts.length)];
            int command = size == 0 ? random.nextInt(2) : size > 25 ? 4 : random.nextInt(9);
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
                    editor.apply(new NotebookCommand.InsertBefore(editor.notebook().line(number).id(), text));
                    snapshot = notebook.insertBefore(number, text);
                }
                case 3 -> {
                    editor.apply(new NotebookCommand.InsertAfter(editor.notebook().line(number).id(), text));
                    snapshot = notebook.insertAfter(number, text);
                }
                case 4 -> {
                    editor.apply(new NotebookCommand.Delete(editor.notebook().line(number).id()));
                    snapshot = notebook.delete(number);
                }
                case 5 -> {
                    editor.apply(new NotebookCommand.Edit(editor.notebook().line(number).id(), text));
                    snapshot = notebook.edit(number, text);
                }
                case 6 -> {
                    int to = 1 + random.nextInt(size);
                    editor.apply(new NotebookCommand.Move(editor.notebook().line(number).id(), to - 1));
                    snapshot = notebook.move(number, to);
                }
                case 7 -> {
                    if (editor.canRedo()) {
                        editor.redo();
                    }
                    snapshot = notebook.redo();
                }
                default -> {
                    if (random.nextInt(5) == 0) {
                        editor.apply(new NotebookCommand.Clear());
                        snapshot = notebook.clear();
                    } else {
                        snapshot = notebook.snapshot();
                    }
                }
            }
            assertEquals(fromCore(DisplayMode.AUTO, editor.canUndo(), editor.canRedo(), editor.current()), snapshot, "step " + step);
            assertEquals(String.join("\n", editor.notebook().texts()), notebook.texts(), "step " + step);
        }
    }

    // ---- Refusals ----------------------------------------------------------------------------------------------------

    @Test
    void anUnknownLineNumberIsRefusedInFrenchAndChangesNothing() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1\n2\n$1 + 1", "AUTO");
        notebook.append("4");
        notebook.undo();
        String before = notebook.snapshot();

        for (int number : new int[] {0, -1, 4, 99, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertRefused(before, notebook, notebook.insertBefore(number, "5"));
            assertRefused(before, notebook, notebook.insertAfter(number, "5"));
            assertRefused(before, notebook, notebook.edit(number, "5"));
            assertRefused(before, notebook, notebook.delete(number));
            assertRefused(before, notebook, notebook.move(number, 1));
            assertRefused(before, notebook, notebook.move(1, number));
        }
        assertEquals("1\n2\n$1 + 1", notebook.texts());
    }

    @Test
    void anEmptyNotebookHasNoLineToDesignate() {
        WebNotebook notebook = new WebNotebook();
        String before = notebook.snapshot();

        assertRefused(before, notebook, notebook.insertBefore(1, "5"));
        assertRefused(before, notebook, notebook.insertAfter(1, "5"));
        assertRefused(before, notebook, notebook.edit(1, "5"));
        assertRefused(before, notebook, notebook.delete(1));
        assertRefused(before, notebook, notebook.move(1, 1));
    }

    @Test
    void aLineOfMaximalLengthIsAcceptedAndOneMoreCharacterIsRefused() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1\n2", "AUTO");
        String before = notebook.snapshot();

        assertRefused(before, notebook, notebook.append(TOO_LONG_LINE));
        assertRefused(before, notebook, notebook.insertBefore(1, TOO_LONG_LINE));
        assertRefused(before, notebook, notebook.insertAfter(1, TOO_LONG_LINE));
        assertRefused(before, notebook, notebook.edit(1, TOO_LONG_LINE));
        assertRefused(before, notebook, notebook.load("7\n" + TOO_LONG_LINE, "FRACTION"));
        assertEquals("1\n2", notebook.texts());

        assertNull(messageOf(notebook.append(LONGEST_LINE)));
        assertNull(messageOf(notebook.edit(1, LONGEST_LINE)));
        assertNull(messageOf(notebook.insertBefore(1, LONGEST_LINE)));
        assertNull(messageOf(notebook.insertAfter(1, LONGEST_LINE)));
        assertEquals(LONGEST_LINE + "\n" + LONGEST_LINE + "\n" + LONGEST_LINE + "\n2\n" + LONGEST_LINE, notebook.texts());
        assertNull(messageOf(notebook.load("7\n" + LONGEST_LINE, "AUTO")));
    }

    @Test
    void the5001stLineIsRefusedWithTheMessageOfTheCore() throws NotebookException {
        String fullText = copies(Notebook.MAX_LINES);
        List<String> full = List.of(fullText.split("\n"));
        NotebookEditor core = new NotebookEditor(Notebook.of(full));
        String message = assertThrows(NotebookException.class, () -> core.apply(new NotebookCommand.Append("1"))).getMessage();
        assertEquals("Le carnet ne peut pas dépasser 5000 lignes.", message);

        WebNotebook notebook = new WebNotebook();
        String before = notebook.load(fullText, "AUTO");
        assertNull(messageOf(before), "5000 lines are accepted.");

        assertEquals(message, assertRefused(before, notebook, notebook.append("2")));
        assertEquals(message, assertRefused(before, notebook, notebook.insertBefore(1, "2")));
        assertEquals(message, assertRefused(before, notebook, notebook.insertAfter(5000, "2")));
        assertEquals(fullText, notebook.texts());

        WebNotebook small = new WebNotebook();
        String smallBefore = small.load("7", "FRACTION");
        assertEquals(message, assertRefused(smallBefore, small, small.load(copies(Notebook.MAX_LINES + 1), "AUTO")));
        assertEquals("7", small.texts());
    }

    @Test
    void aMessageIsForTheCommandThatWasRefusedOnly() {
        WebNotebook notebook = new WebNotebook();
        String before = notebook.load("1", "AUTO");

        assertRefused(before, notebook, notebook.delete(9));
        assertEquals(before, notebook.snapshot());

        assertRefused(before, notebook, notebook.setMode("BOGUS"));
        assertNull(messageOf(notebook.append("2")));
    }

    @Test
    void aRefusedCommandKeepsWhatCanBeRedone() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1", "AUTO");
        notebook.append("2");
        String undone = notebook.undo();

        assertRefused(undone, notebook, notebook.delete(9));
        assertRefused(undone, notebook, notebook.append(TOO_LONG_LINE));
        notebook.redo();
        assertEquals("1\n2", notebook.texts());
    }

    // ---- Line breaks -------------------------------------------------------------------------------------------------

    @Test
    void lineBreaksReceivedByACommandBecomeSpaces() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("9", "AUTO");

        assertEquals(snapshot("AUTO", true, false, null, value(1, "1 + 1", "EXACT", "2", null, null)), notebook.edit(1, "1\n+\r1"));
        assertEquals(snapshot("AUTO", true, false, null, value(1, "1 + 1", "EXACT", "2", null, null),
                value(2, "2 + 2", "EXACT", "4", null, null)), notebook.append("2\n+\r2"));
        notebook.insertBefore(1, "3\n+\r3");
        notebook.insertAfter(1, "4\n+\r4");
        assertEquals("3 + 3\n4 + 4\n1 + 1\n2 + 2", notebook.texts());
    }

    @Test
    void aWindowsLineBreakReceivedByACommandStaysOnOneLine() {
        WebNotebook notebook = new WebNotebook();
        notebook.append("1\r\n+ 1");
        String text = notebook.texts();
        assertTrue(text.equals("1 + 1") || text.equals("1  + 1"), text);
    }

    @Test
    void loadSplitsOnLineFeedsAndTurnsCarriageReturnsIntoSpaces() {
        WebNotebook notebook = new WebNotebook();
        assertEquals(snapshot("AUTO", false, false, null, value(1, "1 ", "EXACT", "1", null, null),
                value(2, "2 + 2", "EXACT", "4", null, null)), notebook.load("1\r\n2\r+ 2", "AUTO"));
        assertEquals("1 \n2 + 2", notebook.texts());
    }

    // ---- Nothing gets out of the class -------------------------------------------------------------------------------

    @Test
    void aFailureInsideACommandGivesAMessageAndTheNextCommandSucceeds() {
        Map<String, Function<WebNotebook, String>> commands = Map.ofEntries(
                Map.entry("load", notebook -> notebook.load("5", "FRACTION")),
                Map.entry("append", notebook -> notebook.append("5")),
                Map.entry("insertBefore", notebook -> notebook.insertBefore(1, "5")),
                Map.entry("insertAfter", notebook -> notebook.insertAfter(1, "5")),
                Map.entry("edit", notebook -> notebook.edit(1, "5")),
                Map.entry("delete", notebook -> notebook.delete(1)),
                Map.entry("move", notebook -> notebook.move(2, 1)),
                Map.entry("clear", WebNotebook::clear),
                Map.entry("undo", WebNotebook::undo),
                Map.entry("redo", WebNotebook::redo),
                Map.entry("setMode", notebook -> notebook.setMode("FRACTION")));
        List<Throwable> failures = List.of(new RuntimeException(), new StackOverflowError(), new OutOfMemoryError(),
                new IllegalStateException("détail technique"));

        for (Throwable failure : failures) {
            for (Map.Entry<String, Function<WebNotebook, String>> command : commands.entrySet()) {
                String label = command.getKey() + " with " + failure;
                WebNotebook notebook = new WebNotebook();
                notebook.load("1\n2\n$1 + 1", "AUTO");
                notebook.append("8");
                notebook.append("9");
                String before = notebook.undo();
                assertTrue(before.startsWith("{\"mode\":\"AUTO\",\"canUndo\":true,\"canRedo\":true,"), before);
                String texts = notebook.texts();

                notebook.failNextCommandWith(failure);
                String answer = command.getValue().apply(notebook);

                assertNotNull(answer, label);
                String message = messageOf(answer);
                assertNotNull(message, label + ": " + answer);
                assertFalse(message.isBlank(), label);
                assertFalse(message.equals("null"), label);
                assertEquals(before, withoutMessage(answer), label + ": same mode, same history, same lines");
                assertEquals(texts, notebook.texts(), label);
                assertEquals(before, notebook.snapshot(), label + ": the failure is over");

                String next = command.getValue().apply(notebook);
                assertNull(messageOf(next), label + ": the same command now succeeds, " + next);
            }
        }
    }

    @Test
    void theNotebookStaysUsableAfterAFailure() {
        for (Throwable failure : List.of(new RuntimeException(), new StackOverflowError())) {
            WebNotebook notebook = new WebNotebook();
            notebook.load("10\n3\n$1 + 1", "AUTO");

            notebook.failNextCommandWith(failure);
            assertNotNull(messageOf(notebook.move(2, 1)));
            assertEquals("10\n3\n$1 + 1", notebook.texts());

            assertNull(messageOf(notebook.move(2, 1)));
            assertEquals("3\n10\n$2 + 1", notebook.texts());
            notebook.undo();
            assertEquals("10\n3\n$1 + 1", notebook.texts());
            notebook.redo();
            assertEquals("3\n10\n$2 + 1", notebook.texts());
        }
    }

    @Test
    void aMissingTextIsRefusedAndThrowsNothing() {
        WebNotebook notebook = new WebNotebook();
        String before = notebook.load("1", "AUTO");

        assertRefused(before, notebook, notebook.append(null));
        assertRefused(before, notebook, notebook.insertBefore(1, null));
        assertRefused(before, notebook, notebook.insertAfter(1, null));
        assertRefused(before, notebook, notebook.edit(1, null));
        assertRefused(before, notebook, notebook.load(null, "AUTO"));
        assertEquals("1", notebook.texts());
    }

    // ---- The guard of the dependencies -------------------------------------------------------------------------------

    @Test
    void theClassesOfTheWebPackageImportNothingThatCannotRunInABrowser() throws IOException {
        List<String> imports = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> listed = Files.list(WEB_SOURCES)) {
            files = listed.filter(path -> path.toString().endsWith(".java")).toList();
        }
        assertTrue(files.contains(WEB_NOTEBOOK), "The scan must see " + WEB_NOTEBOOK + ": " + files);
        assertTrue(files.contains(WEB_EXPORTS), "The scan must see " + WEB_EXPORTS + ": " + files);

        for (Path file : files) {
            Matcher matcher = IMPORT.matcher(Files.readString(file, UTF_8));
            while (matcher.find()) {
                imports.add(file.getFileName() + ": " + matcher.group(1));
            }
        }
        List<String> forbidden = imports.stream()
                .filter(entry -> FORBIDDEN_IMPORTS.stream()
                        .anyMatch(prefix -> entry.substring(entry.indexOf(": ") + 2).startsWith(prefix + ".")))
                .toList();
        assertEquals(List.of(), forbidden);
    }

    @Test
    void aGuardTestKeepsTheseImportsOutInTheVerificationOfTheProject() throws IOException {
        assertTrue(Files.isRegularFile(GUARD), GUARD + " must exist: it runs in check.");
        // The guard itself needs java.nio to read the files: its own imports say nothing of what it refuses.
        String guard = Files.readString(GUARD, UTF_8).replaceAll("(?m)^import\\s.*$", "");
        for (String prefix : FORBIDDEN_IMPORTS) {
            assertTrue(guard.contains(prefix), "The guard must refuse " + prefix);
        }
        assertTrue(guard.contains("@Test"), "The guard must be a test.");
        assertTrue(guard.contains("Files.list"), "The guard reads the files of the package, without its subpackages.");
        assertFalse(guard.contains("Files.walk"), "The subpackages compat and tools are not part of what it checks.");
        assertTrue(guard.contains("\"web"), "The guard must read web/src/main/java/calc/web, relative to the root.");
    }

    @Test
    void webNotebookDrivesTheCoreAndKnowsNothingOfTeaVm() throws IOException {
        String source = Files.readString(WEB_NOTEBOOK, UTF_8);
        assertTrue(source.contains("NotebookEditor"), "WebNotebook drives NotebookEditor.");
        assertTrue(source.contains("ValueFormatter"), "Every value goes through ValueFormatter.");
        assertFalse(source.contains("org.teavm"), "WebNotebook stays loadable on the JVM without TeaVM.");
        assertFalse(source.contains("JSExport"), "Only WebExports exports to JavaScript.");
    }

    // ---- Helpers -----------------------------------------------------------------------------------------------------

    /** Checks a refusal, with a French message, that leaves the notebook as {@code before} says; gives the message. */
    private static String assertRefused(String before, WebNotebook notebook, String answer) {
        assertNotNull(answer);
        String message = messageOf(answer);
        assertNotNull(message, () -> "A refusal has a message: " + answer);
        assertFalse(message.isBlank(), "A refusal has a message.");
        assertFalse(NOT_A_FRENCH_SENTENCE.matcher(message).find(), () -> "A refusal is a French sentence: " + message);
        assertEquals(before, withoutMessage(answer), "A refusal changes neither the mode, nor the history, nor the lines.");
        assertEquals(before, notebook.snapshot(), "A refusal changes nothing, and the message does not stay.");
        return message;
    }

    /** The message of a snapshot as a JSON text without its quotes, null when the snapshot has none. */
    private static String messageOf(String snapshot) {
        Matcher matcher = MESSAGE.matcher(snapshot);
        if (!matcher.find()) {
            return fail("A snapshot has a message then its lines: " + snapshot);
        }
        return matcher.group(2);
    }

    private static String withoutMessage(String snapshot) {
        return MESSAGE.matcher(snapshot).replaceFirst("\"message\":null,\"lines\":[");
    }

    private static String onlyRow(String snapshot) {
        String prefix = "{\"mode\":\"AUTO\",\"canUndo\":false,\"canRedo\":false,\"message\":null,\"lines\":[";
        assertTrue(snapshot.startsWith(prefix) && snapshot.endsWith("]}"), snapshot);
        return snapshot.substring(prefix.length(), snapshot.length() - 2);
    }

    private static LineError onlyError(String text) throws NotebookException {
        return new NotebookEditor(Notebook.of(List.of(text))).current().results().get(0).error();
    }

    private static String copies(int lines) {
        return String.join("\n", Collections.nCopies(lines, "1"));
    }

    private static String snapshot(String mode, boolean canUndo, boolean canRedo, String message, String... rows) {
        StringJoiner lines = new StringJoiner(",", "[", "]");
        for (String row : rows) {
            lines.add(row);
        }
        return "{\"mode\":\"" + mode + "\",\"canUndo\":" + canUndo + ",\"canRedo\":" + canRedo + ",\"message\":" + json(message)
                + ",\"lines\":" + lines + "}";
    }

    private static String value(int number, String text, String status, String result, String fraction, String bound) {
        return row(number, text, status, result, fraction, bound, null, -1, -1);
    }

    private static String row(int number, String text, String status, String result, String fraction, String bound, String error,
            int start, int end) {
        return "{\"number\":" + number + ",\"text\":" + json(text) + ",\"status\":\"" + status + "\",\"result\":" + json(result)
                + ",\"exactFraction\":" + json(fraction) + ",\"errorBound\":" + json(bound) + ",\"error\":" + json(error)
                + ",\"errorStart\":" + start + ",\"errorEnd\":" + end + "}";
    }

    /** The snapshot that the core gives, by the rules of NotebookPresenter.row. */
    private static String fromCore(DisplayMode mode, boolean canUndo, boolean canRedo, EvaluatedNotebook evaluated) {
        List<String> rows = new ArrayList<>();
        for (LineResult result : evaluated.results()) {
            rows.add(rowFromCore(result, mode));
        }
        return snapshot(mode.name(), canUndo, canRedo, null, rows.toArray(String[]::new));
    }

    private static String rowFromCore(LineResult result, DisplayMode mode) {
        if (result.isError()) {
            Span span = result.error().span();
            int end = Math.min(span.end(), result.text().length());
            boolean marked = span.length() > 0 && span.start() < end;
            return row(result.number(), result.text(), "ERROR", null, null, null, result.error().message(),
                    marked ? span.start() : -1, marked ? end : -1);
        }
        if (result.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(result.value(), mode);
            return value(result.number(), result.text(), formatted.exact() ? "EXACT" : "APPROXIMATE", formatted.text(),
                    formatted.exactFraction().orElse(null), formatted.errorBound().orElse(null));
        }
        return row(result.number(), result.text(), result.kind() == ParsedLine.Kind.COMMENT ? "COMMENT" : "EMPTY", null, null, null,
                null, -1, -1);
    }

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
