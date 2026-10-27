package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.Line;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookFormatException.Reason;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The version 3 file: the documented shape, what a reader keeps, ignores, regenerates and refuses. */
class NotebookCodecTest {
    private static final String EXAMPLE = """
            {
              "format": "calc-notebook",
              "version": 3,
              "displayMode": "auto",
              "lines": [
                { "id": "1", "source": "# Devis cuisine" },
                { "id": "2", "source": "surface = 12,5" },
                { "id": "3", "source": "surface * 42" }
              ]
            }
            """;

    private static Notebook notebook(String... texts) {
        try {
            return Notebook.of(List.of(texts));
        } catch (calc.notebook.NotebookException exception) {
            throw new AssertionError(exception);
        }
    }

    private static DecodedNotebook decode(String json) throws NotebookFormatException {
        return NotebookCodec.decode(json.getBytes(StandardCharsets.UTF_8));
    }

    private static Reason refusal(String json) {
        return assertThrows(NotebookFormatException.class, () -> decode(json)).reason();
    }

    private static String lines(String... elements) {
        return "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[" + String.join(",", elements) + "]}";
    }

    @Test
    void theDocumentedExampleIsExactlyWhatIsWritten() throws Exception {
        Notebook notebook = notebook("# Devis cuisine", "surface = 12,5", "surface * 42");

        String written = new String(NotebookCodec.encode(notebook, DisplayMode.AUTO).bytes(), StandardCharsets.UTF_8);

        assertEquals(EXAMPLE, written);
        DecodedNotebook read = decode(EXAMPLE);
        assertEquals(notebook, read.notebook());
        assertEquals(DisplayMode.AUTO, read.displayMode());
        assertEquals(3, read.version());
        assertFalse(read.isNewerVersion());
    }

    @Test
    void theFileHoldsSourcesOnlyNeverAResult() throws Exception {
        String written = new String(NotebookCodec.encode(notebook("1/3 + 1/6", "√2", "x ="), DisplayMode.FRACTION).bytes(),
                StandardCharsets.UTF_8);

        assertFalse(written.contains("1/2"), "the value of line 1 is not stored");
        assertFalse(written.contains("lower") || written.contains("upper") || written.contains("exact"), written);
        assertTrue(written.contains("\"displayMode\": \"fraction\""));
        assertEquals(List.of("format", "version", "displayMode", "lines"), List.copyOf(new java.util.LinkedHashSet<>(
                java.util.regex.Pattern.compile("^  \"(\\w+)\"", java.util.regex.Pattern.MULTILINE).matcher(written).results()
                        .map(match -> match.group(1)).toList())));
    }

    @Test
    void anEmptyNotebookIsWrittenAndReadBack() throws Exception {
        NotebookCodec.Encoded encoded = NotebookCodec.encode(Notebook.empty(), DisplayMode.AUTO);

        assertEquals(0, encoded.written());
        DecodedNotebook read = NotebookCodec.decode(encoded.bytes());
        assertTrue(read.notebook().isEmpty());
        assertTrue(read.linesRead());
    }

    @Test
    void everyDisplayModeIsKeptAndAnUnknownOneIsAuto() throws Exception {
        for (DisplayMode mode : DisplayMode.values()) {
            assertEquals(mode, NotebookCodec.decode(NotebookCodec.encode(Notebook.empty(), mode).bytes()).displayMode());
        }
        assertEquals(DisplayMode.AUTO, decode("{\"format\":\"calc-notebook\",\"version\":3,\"displayMode\":\"hex\",\"lines\":[]}").displayMode());
        assertEquals(DisplayMode.AUTO, decode("{\"format\":\"calc-notebook\",\"version\":3,\"displayMode\":7,\"lines\":[]}").displayMode());
        assertEquals(DisplayMode.AUTO, decode(lines()).displayMode(), "absent");
    }

    @Test
    void unknownFieldsAreIgnoredAtEveryLevel() throws Exception {
        DecodedNotebook read = decode("""
                {"format":"calc-notebook","version":3,"author":{"name":"x","tags":[1,2,{"deep":true}]},
                 "lines":[{"id":"7","source":"1 + 1","color":"red","meta":[null,false]},{"source":"2 * 3","id":"8","x":1.5e3}]}""");

        assertEquals(List.of("1 + 1", "2 * 3"), read.notebook().texts());
        assertEquals(List.of(new LineId(7), new LineId(8)), read.notebook().lines().stream().map(Line::id).toList());
    }

    @Test
    void aLineTheLanguageRefusesIsKeptAsItIs() throws Exception {
        List<String> texts = List.of("1 +", "((", "$99", "x = ", "1,5,3", "@", "=", "x".repeat(1500), "\"quoted\"", "tab\there", "naïve ≈");
        List<String> elements = new ArrayList<>();
        for (String text : texts) {
            elements.add("{\"source\":" + HistoryCodec.quote(text) + "}");
        }

        DecodedNotebook read = decode(lines(elements.toArray(String[]::new)));

        assertEquals(texts, read.notebook().texts(), "no line is dropped, repaired or reordered");
        List<LineResult> results = NotebookEvaluator.evaluate(read.notebook()).results();
        assertEquals(texts.size(), results.size());
        assertTrue(results.stream().limit(8).allMatch(LineResult::isError), "they show as errors in the notebook");
    }

    @Test
    void identifiersAbsentMalformedOrDuplicatedAreRegeneratedDeterministically() throws Exception {
        String json = lines("{\"id\":\"5\",\"source\":\"a\"}", "{\"source\":\"b\"}", "{\"id\":\"5\",\"source\":\"c\"}",
                "{\"id\":\"x9\",\"source\":\"d\"}", "{\"id\":7,\"source\":\"e\"}", "{\"id\":\"007\",\"source\":\"f\"}",
                "{\"id\":\"0\",\"source\":\"g\"}", "{\"id\":\"3\",\"source\":\"h\"}", "{\"id\":\"12345678901234567890\",\"source\":\"i\"}");

        DecodedNotebook read = decode(json);

        assertEquals(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"), read.notebook().texts());
        // 5 and 3 are the valid identifiers (the first 5 wins); the seven others take 6, 7, 8... in file order.
        assertEquals(List.of(5L, 6L, 7L, 8L, 9L, 10L, 11L, 3L, 12L),
                read.notebook().lines().stream().map(line -> line.id().value()).toList());
        assertEquals(7, read.regeneratedIds());
        assertEquals(read, decode(json), "the same file always gives the same identifiers");
        assertTrue(read.notebook().lines().stream().map(Line::id).distinct().count() == 9);
    }

    @Test
    void newLinesTakeIdentifiersAfterTheLargestOneRead() throws Exception {
        DecodedNotebook read = decode(lines("{\"id\":\"40\",\"source\":\"a\"}", "{\"id\":\"2\",\"source\":\"b\"}"));

        Notebook grown = read.notebook().append("c");

        assertEquals(41, grown.line(3).id().value());
    }

    @Test
    void aLineBreakInASourceBecomesASpaceAndTheLineIsNotSplit() throws Exception {
        DecodedNotebook read = decode(lines("{\"source\":\"1 +\\n2\"}", "{\"source\":\"a\\r\\nb\"}", "{\"source\":\"3\"}"));

        assertEquals(List.of("1 + 2", "a  b", "3"), read.notebook().texts(), "numbers of the lines are those of the file");
        assertEquals(2, read.splitBreaks());
    }

    @Test
    void aLoneSurrogateSurvivesTheRoundTrip() throws Exception {
        String lone = "a\ud800b\udc00c";
        Notebook notebook = notebook(lone, "😀");

        byte[] bytes = NotebookCodec.encode(notebook, DisplayMode.AUTO).bytes();

        assertEquals(notebook.texts(), NotebookCodec.decode(bytes).notebook().texts());
        assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("\\ud800"));
    }

    @Test
    void readingThenWritingChangesNoByte() throws Exception {
        byte[] written = NotebookCodec.encode(notebook("# a", "b = 1/3", "b * 3", "\"q\" \\ \t é"), DisplayMode.SCIENTIFIC).bytes();

        DecodedNotebook read = NotebookCodec.decode(written);

        assertArrayEquals(written, NotebookCodec.encode(read.notebook(), read.displayMode()).bytes());
    }

    @Test
    void aByteOrderMarkAtTheStartIsAccepted() throws Exception {
        byte[] body = EXAMPLE.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertEquals(3, NotebookCodec.decode(withBom).notebook().size());
    }

    // ------------------------------------------------------------------------------------------------ refusals

    @Test
    void whatIsNotANotebookIsRefusedWithAClearReason() {
        assertEquals(Reason.NOT_A_NOTEBOOK, refusal("{\"format\":\"calc-history\",\"version\":2,\"entries\":[]}"));
        assertEquals(Reason.NOT_A_NOTEBOOK, refusal("{\"version\":3,\"lines\":[]}"));
        assertEquals(Reason.NOT_A_NOTEBOOK, refusal("[]"));
        assertEquals(Reason.NOT_A_NOTEBOOK, refusal("\"calc-notebook\""));
        assertEquals(Reason.MALFORMED_JSON, refusal("{\"format\":"));
        assertEquals(Reason.MALFORMED_JSON, refusal(""));
        assertEquals(Reason.MALFORMED_JSON, refusal(EXAMPLE + "garbage"));
        assertEquals(Reason.MALFORMED_JSON, refusal("{\"format\":\"calc-notebook\",\"format\":\"calc-notebook\",\"version\":3,\"lines\":[]}"));
        assertEquals("Ce fichier n'est pas un carnet Calc.", assertThrows(NotebookFormatException.class, () -> decode("[]")).userMessage());
    }

    @Test
    void theBytesMustBeUtf8() {
        assertEquals(Reason.NOT_UTF8, assertThrows(NotebookFormatException.class,
                () -> NotebookCodec.decode(new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'})).reason());
    }

    @Test
    void theVersionMustBeAnIntegerAtLeastThree() {
        for (String version : List.of("", "\"version\":null,", "\"version\":\"3\",", "\"version\":2,", "\"version\":1,", "\"version\":0,",
                "\"version\":-3,", "\"version\":3.5,", "\"version\":3e0,", "\"version\":true,")) {
            assertEquals(Reason.INVALID_VERSION, refusal("{\"format\":\"calc-notebook\"," + version + "\"lines\":[]}"), version);
        }
    }

    @Test
    void theLinesMustBeAnArrayOfObjectsWithATextSource() {
        for (String body : List.of("", "\"lines\":null", "\"lines\":{}", "\"lines\":\"1+1\"", "\"lines\":[\"1+1\"]", "\"lines\":[[]]",
                "\"lines\":[{}]", "\"lines\":[{\"id\":\"1\"}]", "\"lines\":[{\"source\":3}]", "\"lines\":[{\"source\":null}]",
                "\"lines\":[{\"source\":\"ok\"},{\"source\":[\"x\"]}]")) {
            assertEquals(Reason.MALFORMED_STRUCTURE,
                    refusal("{\"format\":\"calc-notebook\",\"version\":3" + (body.isEmpty() ? "" : "," + body) + "}"), body);
        }
    }

    // ------------------------------------------------------------------------------------------------ versions

    @Test
    void aNewerVersionIsReadWhenItsLinesLookLikeOursAndNeverRefusedForItsContent() throws Exception {
        DecodedNotebook readable = decode("{\"format\":\"calc-notebook\",\"version\":4,\"lines\":[{\"id\":\"1\",\"source\":\"1+1\",\"new\":1}]}");
        assertTrue(readable.isNewerVersion());
        assertTrue(readable.linesRead());
        assertEquals(List.of("1+1"), readable.notebook().texts());

        for (String body : List.of("", "\"lines\":{\"a\":1}", "\"lines\":[1,2]", "\"lines\":[{\"text\":\"1\"}]")) {
            DecodedNotebook unreadable = decode("{\"format\":\"calc-notebook\",\"version\":99" + (body.isEmpty() ? "" : "," + body) + "}");
            assertTrue(unreadable.isNewerVersion(), body);
            assertFalse(unreadable.linesRead(), body);
            assertTrue(unreadable.notebook().isEmpty());
        }
        assertTrue(decode("{\"format\":\"calc-notebook\",\"version\":99999999999999999999,\"lines\":[]}").isNewerVersion(),
                "a version too large for an int is simply a newer one");
    }

    // ------------------------------------------------------------------------------------------------ limits

    @Test
    void exactlyTheMaximumNumberOfLinesIsAcceptedAndOneMoreIsRefused() throws Exception {
        String element = "{\"source\":\"1\"}";
        assertEquals(Notebook.MAX_LINES, decode(lines(java.util.Collections.nCopies(Notebook.MAX_LINES, element).toArray(String[]::new))).notebook().size());
        assertEquals(Reason.TOO_MANY_LINES,
                refusal(lines(java.util.Collections.nCopies(Notebook.MAX_LINES + 1, element).toArray(String[]::new))));
    }

    @Test
    void aSourceOfExactlyTheMaximumLengthIsKeptAndOneMoreCharacterIsRefused() throws Exception {
        String longest = "9".repeat(StorageLimits.MAX_STRING_LENGTH);

        assertEquals(longest, decode(lines("{\"source\":\"" + longest + "\"}")).notebook().line(1).text());
        assertEquals(Reason.JSON_LIMIT, refusal(lines("{\"source\":\"" + longest + "9\"}")));
    }

    @Test
    void aFileWithTooManyValuesIsRefusedBeforeItFillsTheMemory() {
        // 100 001 values in a file of a few hundred kilobytes: [[],[],[]...]
        String hostile = "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[],\"x\":[" + "[],".repeat(StorageLimits.MAX_NOTEBOOK_JSON_VALUES) + "[]]}";
        assertEquals(Reason.JSON_LIMIT, refusal(hostile));
    }

    @Test
    void aNotebookThatCannotBeReadBackIsNotWritten() {
        String tooLong = "9".repeat(StorageLimits.MAX_STRING_LENGTH + 1);
        assertEquals(Reason.LINE_TOO_LONG,
                assertThrows(NotebookFormatException.class, () -> NotebookCodec.encode(notebook("1", tooLong), DisplayMode.AUTO)).reason());

        // 5000 lines of 16 384 two-byte characters are over the size limit, although each line alone is fine.
        String wide = "é".repeat(StorageLimits.MAX_STRING_LENGTH);
        Notebook huge = calc.testing.NotebookGenerators.notebook(java.util.Collections.nCopies(Notebook.MAX_LINES, wide));
        assertEquals(Reason.TOO_LARGE,
                assertThrows(NotebookFormatException.class, () -> NotebookCodec.encode(huge, DisplayMode.AUTO)).reason());
    }

    @Test
    void theLargestNotebookTheFormatAcceptsIsWrittenAndReadBack() throws Exception {
        // 5000 lines of 1000 characters of three bytes each: the case the size limit was chosen for.
        String line = "€".repeat(1000);
        Notebook notebook = calc.testing.NotebookGenerators.notebook(java.util.Collections.nCopies(Notebook.MAX_LINES, line));

        NotebookCodec.Encoded encoded = NotebookCodec.encode(notebook, DisplayMode.AUTO);

        assertTrue(encoded.bytes().length < StorageLimits.MAX_NOTEBOOK_FILE_BYTES, "file of " + encoded.bytes().length + " bytes");
        assertEquals(notebook.texts(), NotebookCodec.decode(encoded.bytes()).notebook().texts());
    }

    @Test
    void aFileOverTheSizeLimitIsRefusedWithoutBeingParsed() {
        byte[] huge = new byte[StorageLimits.MAX_NOTEBOOK_FILE_BYTES + 1];
        assertEquals(Reason.TOO_LARGE, assertThrows(NotebookFormatException.class, () -> NotebookCodec.decode(huge)).reason());
    }
}
