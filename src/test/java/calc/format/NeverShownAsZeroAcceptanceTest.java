package calc.format;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.NumericMessages;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.export.NotebookExporter;
import calc.expression.ExpressionError;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.Line;
import calc.notebook.LineResult;
import calc.notebook.NotebookEvaluator;
import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import calc.number.Rational;
import calc.persistence.DecodedHistory;
import calc.persistence.HistoryCodec;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.DisplayTexts;
import calc.testing.FractionOracle;
import calc.testing.NotebookGenerators;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Defects D1 and D7 of docs/RECETTE.md: a value that is not zero is never shown as 0. Outward rounding never brings a bound
 * that is not zero to 0: such a value is refused like the exact {@code (1/16)^4096}. An interval that contains 0 reads
 * « |x| ≤ b » in every mode. The random cases are in {@link NeverShownAsZeroProperties}.
 *
 * <p>No test here touches the window, so they all run without a display.
 */
@Tag("acceptance")
class NeverShownAsZeroAcceptanceTest {
    static final String BOUND_PREFIX = "|x| ≤ ";
    // The writing of the « ± » bounds: one digit, or two with a comma, then a power of ten in superscript digits.
    private static final Pattern BOUND_TEXT = Pattern.compile("\\|x\\| ≤ [1-9](,[0-9])? × 10⁻?[⁰¹²³⁴⁵⁶⁷⁸⁹]+");
    private static final FractionOracle ONE_AND_A_TENTH = new FractionOracle(BigInteger.valueOf(11), BigInteger.TEN);

    private static final String ROOT_LOWER = "1709679290002018430137083/1208925819614629174706176";
    private static final String ROOT_UPPER = "3419358580004036860274167/2417851639229258349412352";
    private static final String CURRENT_NOTEBOOK = "carnet-courant.calc.json";
    private static final String TEST_NAME = "NeverShownAsZeroAcceptanceTest";

    // ------------------------------------------------------------------------------------------------ shared helpers

    /** The b of a text « |x| ≤ b », read back independently of the production code. */
    static FractionOracle boundShown(String text) {
        assertTrue(BOUND_TEXT.matcher(text).matches(), () -> "not a text « |x| ≤ b » written like the « ± » bounds: " + text);
        return DisplayTexts.value(text.substring(BOUND_PREFIX.length()));
    }

    static FractionOracle oracle(Rational value) {
        return new FractionOracle(value.numerator(), value.denominator());
    }

    /**
     * {@code value} contains 0: every mode shows the same « |x| ≤ b », where b is the largest absolute value of the two bounds
     * rounded up to two significant digits, so it holds the interval and is at most a tenth above that value.
     */
    static void assertShownAsBound(CertifiedValue value) {
        String shown = ValueFormatter.format(value, DisplayMode.AUTO).text();
        FractionOracle bound = boundShown(shown);
        FractionOracle largest = largestMagnitude(value);

        assertTrue(bound.compareTo(largest) >= 0, () -> shown + " does not hold " + value);
        assertTrue(bound.compareTo(largest.multiply(ONE_AND_A_TENTH)) <= 0, () -> shown + " is too far above " + value);
        for (DisplayMode mode : DisplayMode.values()) {
            FormattedValue formatted = ValueFormatter.format(value, mode);
            assertEquals(shown, formatted.text(), mode.name());
            assertFalse(formatted.exact(), mode.name());
        }
    }

    private static FractionOracle largestMagnitude(CertifiedValue value) {
        FractionOracle lower = magnitude(value.lower());
        FractionOracle upper = magnitude(value.upper());
        return lower.compareTo(upper) >= 0 ? lower : upper;
    }

    private static FractionOracle magnitude(Rational value) {
        return value.signum() < 0 ? oracle(value).negate() : oracle(value);
    }

    private static CertifiedValue interval(String lower, String upper) throws NumericException {
        return CertifiedValue.between(Rational.parse(lower), Rational.parse(upper));
    }

    private static String text(CertifiedValue value, DisplayMode mode) {
        return ValueFormatter.format(value, mode).text();
    }

    private static LineResult evaluated(String line) {
        return NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of(line))).results().get(0);
    }

    private static CertifiedValue valueOf(String line) {
        LineResult result = evaluated(line);
        assertTrue(result.hasValue(), () -> "« " + line + " » should have a value: " + result.failure());
        return result.value();
    }

    private static CertifiedValue root() throws NumericException {
        return CertifiedValue.exact(Rational.of(2)).sqrt();
    }

    // ------------------------------------------------------------------------------------------------ a value refused

    @Test
    void theExampleOfTheDefectIsRefusedWithTheMessageOfItsExactTwin() {
        LineResult approximate = evaluated("(sqrt(2) / 16) ^ 4096");
        LineResult exact = evaluated("(1/16) ^ 4096");

        assertTrue(exact.isError(), "the exact twin stays refused");
        assertTrue(approximate.isError(), () -> "(sqrt(2) / 16) ^ 4096 should be refused, not " + approximate.result().map(
                value -> text(value, DisplayMode.AUTO)));
        assertEquals(ExpressionError.NUMERIC, approximate.error().code());
        assertEquals(exact.error().code(), approximate.error().code());
        assertEquals(exact.error().message(), approximate.error().message());
        assertEquals(NumericMessages.POWER_TOO_LARGE, approximate.error().message());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "(√2 * 2 ^ -2100) ^ 2                 | power",
        "(-√2 / 16) ^ 4095                    | power",
        "√2 / 2 * 2 ^ -4095                   | other",
        "-√2 / 2 * 2 ^ -4095                  | other",
        "(-√2 * 2 ^ -2100) * (√2 * 2 ^ -2100) | other",
        "√2 / 2 ^ 4000 / 2 ^ 2000             | other"})
    void aValueWhoseBoundWouldBecomeZeroIsRefusedOnEitherSide(String line, String kind) {
        LineResult result = evaluated(line);

        assertTrue(result.isError(), () -> line + " should be refused, not " + result.result().map(
                value -> text(value, DisplayMode.AUTO) + " in " + value));
        assertEquals(ExpressionError.NUMERIC, result.error().code());
        assertEquals(kind.equals("power") ? NumericMessages.POWER_TOO_LARGE : NumericMessages.RESULT_TOO_LARGE, result.error().message());
    }

    @Test
    void theNumericCoreRefusesWithTheSizeLimitAndNeverBringsABoundToZero() throws NumericException {
        CertifiedValue base = root().divide(CertifiedValue.exact(Rational.of(16)));
        CertifiedValue tiny = root().multiply(CertifiedValue.exact(Rational.of(BigInteger.ONE, BigInteger.TWO.pow(2100))));

        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> base.pow(4096)).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> base.negate().pow(4095)).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> tiny.multiply(tiny)).error());
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, assertThrows(NumericException.class, () -> tiny.negate().multiply(tiny)).error());
    }

    @Test
    void aSmallValueThatTheFinestGridCanStillBoundIsNotRefused() throws NumericException {
        CertifiedValue atTheFinestGrid = valueOf("√2 * 2 ^ -4095");
        CertifiedValue oppositeSide = valueOf("-√2 * 2 ^ -4095");

        assertEquals(1, atTheFinestGrid.lower().signum());
        assertEquals(1, atTheFinestGrid.upper().signum());
        assertEquals(-1, oppositeSide.lower().signum());
        assertEquals(-1, oppositeSide.upper().signum());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"(√2 - √2) * 2 ^ -3000", "(√2 - √2) * 2 ^ -4095", "(√2 - √2) / 2 ^ 4000"})
    void anIntervalAroundZeroIsNotRefusedBecauseItsBoundsGoAwayFromZero(String line) {
        assertShownAsBound(valueOf(line));
    }

    // ------------------------------------------------------------------------------------------------ « |x| ≤ b »

    @ParameterizedTest(name = "[{0} ; {1}] -> {2}")
    @CsvSource(delimiter = ';', value = {
        "-1 ; 11 ; |x| ≤ 1,1 × 10¹",
        "-11 ; 1 ; |x| ≤ 1,1 × 10¹",
        "0 ; 12 ; |x| ≤ 1,2 × 10¹",
        "-12 ; 0 ; |x| ≤ 1,2 × 10¹",
        "-1/3 ; 0 ; |x| ≤ 3,4 × 10⁻¹",
        "0 ; 1/3 ; |x| ≤ 3,4 × 10⁻¹",
        "-5 ; 5 ; |x| ≤ 5,0 × 10⁰"})
    void anIntervalThatContainsZeroShowsItsBoundInEveryMode(String lower, String upper, String expected) throws NumericException {
        CertifiedValue value = interval(lower, upper);

        for (DisplayMode mode : DisplayMode.values()) {
            assertEquals(expected, text(value, mode), mode.name());
        }
        assertShownAsBound(value);
    }

    @Test
    void theExampleOfTheBriefIsWrittenLikeTheOtherBounds() throws NumericException {
        CertifiedValue value = interval("-6e-41", "1e-41");

        // The brief writes « 6 × 10⁻⁴¹ » while a « ± » bound keeps its second digit (« ± 6,0 × 10⁻⁵ »): both are the same number.
        assertTrue(Set.of("|x| ≤ 6 × 10⁻⁴¹", "|x| ≤ 6,0 × 10⁻⁴¹").contains(text(value, DisplayMode.AUTO)), text(value, DisplayMode.AUTO));
        assertShownAsBound(value);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
        "√2 - √2 ; |x| ≤ 4,2 × 10⁻²⁵",
        "√2 - √2 + 1e-40 ; |x| ≤ 4,2 × 10⁻²⁵",
        "5+(sqrt(2)-sqrt(2))*1e26 ; |x| ≤ 4,7 × 10¹"})
    void theExamplesOfTheDefectsReadTheSameInEveryMode(String line, String expected) {
        CertifiedValue value = valueOf(line);

        for (DisplayMode mode : DisplayMode.values()) {
            FormattedValue formatted = ValueFormatter.format(value, mode);
            assertEquals(expected, formatted.text(), mode.name());
            assertFalse(formatted.text().contains("≈ 0"), mode.name());
            assertFalse(formatted.exact(), mode + ": the value stays approximate");
        }
        assertShownAsBound(value);
    }

    // ------------------------------------------------------------------------------------------------ what does not change

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"0", "1 - 1", "√4 - √4", "0 * √2", "2 ^ 10 - 1024"})
    void anExactZeroIsStillShownZero(String line) {
        CertifiedValue value = valueOf(line);

        assertTrue(value.isExact(), line);
        for (DisplayMode mode : DisplayMode.values()) {
            assertEquals("0", text(value, mode), mode.name());
        }
    }

    @ParameterizedTest(name = "[{0} ; {1}]")
    @CsvSource(delimiter = '|', value = {
        "141421/100000   | 141423/100000   | ≈ 1,4142…                 | ≈ 1,4142…                   | ≈ 70711/50000                                | ≈ 1,4142… × 10⁰",
        "-141423/100000  | -141421/100000  | ≈ -1,4142…                | ≈ -1,4142…                  | ≈ -70711/50000                               | ≈ -1,4142… × 10⁰",
        "141728/100000   | 158272/100000   | ≈ 2…                      | ≈ 2…                        | ≈ 3/2                                        | ≈ 2… × 10⁰",
        "1/3000000000000 | 1/2999999999999 | ≈ 3,33333333333… × 10⁻¹³  | ≈ 0,000000000000333333333333… | ≈ 5999999999999/17999999999994000000000000 | ≈ 3,33333333333… × 10⁻¹³"})
    void anIntervalWithoutZeroKeepsItsTextInEachMode(String lower, String upper, String auto, String decimal, String fraction, String scientific)
            throws NumericException {
        assertKeepsItsText(interval(lower, upper), auto, decimal, fraction, scientific);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "√2                | ≈ 1,41421356237…            | ≈ 1,41421356237…            | ≈ 1,41421356237…            | ≈ 1,41421356237… × 10⁰",
        "-√2               | ≈ -1,41421356237…           | ≈ -1,41421356237…           | ≈ -1,41421356237…           | ≈ -1,41421356237… × 10⁰",
        "1/√2              | ≈ 0,707106781187…           | ≈ 0,707106781187…           | ≈ 0,707106781187…           | ≈ 7,07106781187… × 10⁻¹",
        "(sqrt(2)/16)^1000 | ≈ 2,48321987787… × 10⁻¹⁰⁵⁴  | ≈ 2,48321987787… × 10⁻¹⁰⁵⁴  | ≈ 2,48321987787… × 10⁻¹⁰⁵⁴  | ≈ 2,48321987787… × 10⁻¹⁰⁵⁴",
        "√2 * 2 ^ -4095    | ≈ 3… × 10⁻¹²³³              | ≈ 3… × 10⁻¹²³³              | ≈ 3… × 10⁻¹²³³              | ≈ 3… × 10⁻¹²³³",
        "-√2 * 2 ^ -4095   | ≈ -3… × 10⁻¹²³³             | ≈ -3… × 10⁻¹²³³             | ≈ -3… × 10⁻¹²³³             | ≈ -3… × 10⁻¹²³³"})
    void aCalculatedValueWithoutZeroKeepsItsTextInEachMode(String line, String auto, String decimal, String fraction, String scientific) {
        assertKeepsItsText(valueOf(line), auto, decimal, fraction, scientific);
    }

    private static void assertKeepsItsText(CertifiedValue value, String auto, String decimal, String fraction, String scientific) {
        assertEquals(auto, text(value, DisplayMode.AUTO));
        assertEquals(decimal, text(value, DisplayMode.DECIMAL));
        assertEquals(fraction, text(value, DisplayMode.FRACTION));
        assertEquals(scientific, text(value, DisplayMode.SCIENTIFIC));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DisplayMode.class)
    void theValuesClosestToZeroNeverReadAsZero(DisplayMode mode) throws NumericException {
        BigInteger finest = BigInteger.TWO.pow(4095);
        CertifiedValue positive = CertifiedValue.between(Rational.of(BigInteger.ONE, finest), Rational.of(BigInteger.TWO, finest));

        assertEquals(1, DisplayTexts.value(text(positive, mode)).signum(), () -> text(positive, mode));
        assertEquals(-1, DisplayTexts.value(text(positive.negate(), mode)).signum(), () -> text(positive.negate(), mode));
    }

    // ------------------------------------------------------------------------------------------------ exports and files

    @ParameterizedTest(name = "{0}")
    @EnumSource(DisplayMode.class)
    void theExportsTakeTheNewTextAsItIsShown(DisplayMode mode) {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("√2 - √2", "√2")));
        String shown = text(notebook.results().get(0).value(), mode);
        boundShown(shown);

        String markdownRow = row(NotebookExporter.markdown(notebook, "Carnet", mode).split("\n"), "| 1 |");
        assertTrue(markdownRow.startsWith("| 1 | √2 - √2 | " + shown.replace("|", "\\|")), markdownRow);

        String file = new String(NotebookExporter.csv(notebook, mode), StandardCharsets.UTF_8);
        String csvRow = row(file.split("\r\n"), "1;");
        assertTrue(csvRow.startsWith("1;√2 - √2;" + shown), csvRow);
        assertTrue(csvRow.endsWith(";;" + NotebookExporter.STATUS_APPROXIMATE), csvRow);

        assertFalse(markdownRow.contains("≈"), markdownRow);
        assertFalse(csvRow.contains("≈"), csvRow);
    }

    private static String row(String[] rows, String start) {
        return Stream.of(rows).filter(row -> row.startsWith(start)).findFirst()
                .orElseThrow(() -> new AssertionError("no row starts with " + start + " in " + List.of(rows)));
    }

    @Test
    void anExportedValueWithoutZeroKeepsItsText() {
        EvaluatedNotebook notebook = NotebookEvaluator.evaluate(NotebookGenerators.notebook(List.of("√2")));

        String file = new String(NotebookExporter.csv(notebook, DisplayMode.AUTO), StandardCharsets.UTF_8);
        assertEquals("1;√2;≈ 1,41421356237… (± 2,1 × 10⁻²⁵);;" + NotebookExporter.STATUS_APPROXIMATE, row(file.split("\r\n"), "1;"));
    }

    @Test
    void theHistoryEntryTakesTheNewText() throws Exception {
        CalculationRecord record = new CalculatorEngine().calculate(Operation.SUBTRACTION, Operand.known(root()), Operand.known(root()));

        boundShown(record.getFormattedResult());
        assertTrue(record.toString().contains(record.getFormattedResult()), record.toString());
        assertFalse(record.toString().contains("≈ 0"), record.toString());
    }

    @Test
    void anExistingNotebookFileOpensWithoutMigrationAndStaysTheSame(@TempDir Path directory) throws Exception {
        byte[] file = ("""
                {
                  "format": "calc-notebook",
                  "version": 3,
                  "displayMode": "fraction",
                  "lines": [
                    { "id": "1", "source": "√2 - √2" },
                    { "id": "2", "source": "(sqrt(2) / 16) ^ 4096" },
                    { "id": "3", "source": "√2" }
                  ]
                }
                """).getBytes(StandardCharsets.UTF_8);
        Path current = directory.resolve(CURRENT_NOTEBOOK);
        Files.write(current, file);

        try (NotebookStore store = NotebookStore.open(directory)) {
            LoadedNotebook loaded = store.load();

            assertEquals(LoadedNotebook.Condition.NORMAL, loaded.condition());
            assertNull(loaded.status());
            assertEquals(0, loaded.migratedLines());
            assertNull(loaded.backup());
            assertEquals(DisplayMode.FRACTION, loaded.displayMode());
            assertEquals(List.of("√2 - √2", "(sqrt(2) / 16) ^ 4096", "√2"), loaded.notebook().lines().stream().map(Line::text).toList());
            assertArrayEquals(file, Files.readAllBytes(current), "opening does not rewrite the file");

            assertEquals(3, NotebookCodec.VERSION);
            assertEquals("calc-notebook", NotebookCodec.FORMAT);
            assertArrayEquals(file, NotebookCodec.encode(loaded.notebook(), DisplayMode.FRACTION).bytes(), "the same bytes are written back");

            List<LineResult> results = NotebookEvaluator.evaluate(loaded.notebook()).results();
            assertShownAsBound(results.get(0).value());
            assertTrue(results.get(1).isError(), "the line is kept and reads as an error");
            assertEquals("≈ 1,41421356237…", text(results.get(2).value(), DisplayMode.AUTO));
        }
        assertEquals(List.of(CURRENT_NOTEBOOK, "history.lock"), names(directory), "no backup, no converted copy");
    }

    @Test
    void anExistingHistoryFileKeepsItsBoundsAndShowsTheNewText() throws Exception {
        String lower = "-1/2417851639229258349412352";
        String upper = "1/2417851639229258349412352";
        String operand = "{ \"exact\": false, \"lower\": \"" + ROOT_LOWER + "\", \"upper\": \"" + ROOT_UPPER + "\" }";
        String entry = "    { \"op\": \"subtraction\", \"operands\": [" + operand + ", " + operand + "], \"result\": { \"exact\": false, \"lower\": \""
                + lower + "\", \"upper\": \"" + upper + "\" } }";
        byte[] file = ("{\n  \"format\": \"calc-history\",\n  \"version\": 2,\n  \"entries\": [\n" + entry + "\n  ]\n}\n").getBytes(StandardCharsets.UTF_8);

        DecodedHistory decoded = HistoryCodec.decode(file);

        assertEquals(2, HistoryCodec.VERSION);
        assertEquals(List.of(), decoded.rejections());
        CalculationRecord record = decoded.records().get(0);
        assertEquals(interval(lower, upper), record.getResult(), "the recorded bounds stay the same");
        boundShown(record.getFormattedResult());
        assertArrayEquals(file, HistoryCodec.encode(decoded.records()).bytes(), "the same bounds are written back: no text of the display goes to the file");
    }

    @Test
    void theNewTextNeverGoesToAFile() throws Exception {
        CalculationRecord record = new CalculatorEngine().calculate(Operation.SUBTRACTION, Operand.known(root()), Operand.known(root()));

        String history = new String(HistoryCodec.encode(List.of(record)).bytes(), StandardCharsets.UTF_8);
        assertFalse(history.contains("|x|") || history.contains("≤"), history);
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }

    // ------------------------------------------------------------------------------------------------ documentation

    private static String normal(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    // A table cell of Markdown has to write \| for a bar.
    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\\|", "|").replace("\r\n", "\n");
    }

    /** Whether one paragraph of {@code text} both describes the refusal and says what is refused, whatever the wrapping. */
    private static boolean describesTheRefusal(String text) {
        for (String paragraph : text.split("\n\\s*\\*?\\s*\n")) {
            String flat = normal(paragraph).replaceAll("\\s+\\*?\\s*", " ");
            if ((flat.contains("refus") || flat.contains("reject"))
                    && (flat.contains("too small") || flat.contains("trop petit") || flat.contains("size_limit_exceeded") || flat.contains("finest"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theJavadocOfTheFormatterStatesTheRuleAndTheRefusal() throws IOException {
        String source = read(Path.of("src", "main", "java", "calc", "format", "ValueFormatter.java"));
        String javadoc = source.substring(0, source.indexOf("public final class ValueFormatter"));

        assertTrue(javadoc.contains("|x| ≤"), "the rule « |x| ≤ b » is in the javadoc of the display rules");
        assertTrue(describesTheRefusal(javadoc), "the refusal of the values too small is in the javadoc of the display rules");
        assertFalse(javadoc.contains("≈ 0"), "the javadoc still announces « ≈ 0 »");
    }

    @Test
    void theDecisionRecordStatesTheRuleAndTheRefusal() throws IOException {
        String decision = read(Path.of("docs", "adr", "0004-regles-affichage.md"));

        assertTrue(decision.contains("|x| ≤"), "the rule « |x| ≤ b » is in the ADR");
        assertTrue(describesTheRefusal(decision), "the refusal of the values too small is in the ADR");
        assertFalse(decision.contains("is shown `≈ 0`"), "the ADR still decides that such a value is shown « ≈ 0 »");
    }

    @Test
    void theUserGuideExplainsTheBoundInOneSentence() throws IOException {
        List<String> lines = read(Path.of("docs", "GUIDE-UTILISATEUR.md")).lines().filter(line -> line.contains("|x| ≤")).toList();

        assertFalse(lines.isEmpty(), "the guide does not mention « |x| ≤ … »");
        assertTrue(lines.stream().map(NeverShownAsZeroAcceptanceTest::normal).anyMatch(line -> line.contains("signe") && line.contains("valeur absolue")),
                () -> "no line says that the sign is not established and that the value is at most b in absolute value: " + lines);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"D1", "D7"})
    void theReportSaysTheDefectIsFixedAndNamesTheTestThatProvesIt(String defect) throws IOException {
        Pattern named = Pattern.compile("(?<![A-Za-z0-9])" + defect + "(?![A-Za-z0-9])");
        List<String> proofs = new ArrayList<>();
        List<String> stillOpen = new ArrayList<>();
        String heading = "";
        boolean inDefects = false;
        for (String line : read(Path.of("docs", "RECETTE.md")).lines().toList()) {
            if (line.startsWith("## ")) {
                inDefects = line.startsWith("## 4.");
                heading = "";
            } else if (line.startsWith("### ")) {
                heading = normal(line);
            } else if (inDefects && named.matcher(line).find()) {
                String text = normal(line);
                boolean fixed = !text.contains("non corrig") && (text.contains("corrig") || heading.contains("corrig") && !heading.contains("non corrig"));
                if (fixed && line.contains(TEST_NAME)) {
                    proofs.add(line);
                }
                if (heading.contains("non corrig") && line.startsWith("**" + defect)) {
                    stillOpen.add(line);
                }
            }
        }

        assertFalse(proofs.isEmpty(), defect + " is not reported as fixed, with " + TEST_NAME + " as its proof, in section 4 of the report");
        assertEquals(List.of(), stillOpen, defect + " is still among the defects not fixed");
    }

    @Test
    void theReportNoLongerSaysThePromiseIsNotKept() throws IOException {
        List<String> open = read(Path.of("docs", "RECETTE.md")).lines().map(NeverShownAsZeroAcceptanceTest::normal)
                .filter(line -> line.contains("jamais affichee") && line.contains("non tenu")).toList();

        assertEquals(List.of(), open);
    }
}
