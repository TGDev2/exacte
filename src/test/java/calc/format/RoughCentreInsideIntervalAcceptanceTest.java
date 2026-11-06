package calc.format;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.notebook.EvaluatedNotebook;
import calc.notebook.Notebook;
import calc.notebook.NotebookEditor;
import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.Rational;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Defect D6 of docs/RECETTE.md: when the bounds of an interval have no digit in common, the text is its centre with the fewest
 * significant digits that place it between the bounds, and « ± r » runs from that text to the farther bound.
 *
 * <p>No test here touches the window, so they all run without a display.
 */
@Tag("acceptance")
class RoughCentreInsideIntervalAcceptanceTest {
    private static final Path REPORT = Path.of("docs", "RECETTE.md");
    private static final String TEST_NAME = "RoughCentreInsideIntervalAcceptanceTest";
    private static final String DEFECT_EXPRESSION = "1,5+(sqrt(2)-sqrt(2))*2e23";
    private static final String DEFECT_LOWER = "141728/100000";
    private static final String DEFECT_UPPER = "158272/100000";

    private static CertifiedValue interval(String lower, String upper) throws NumericException {
        return CertifiedValue.between(Rational.parse(lower), Rational.parse(upper));
    }

    private static String text(String lower, String upper, DisplayMode mode) throws NumericException {
        return ValueFormatter.format(interval(lower, upper), mode).text();
    }

    private static Optional<String> bound(String lower, String upper, DisplayMode mode) throws NumericException {
        return ValueFormatter.format(interval(lower, upper), mode).errorBound();
    }

    /** Reads « ≈ 1,5… » as the exact number 1,5 and checks that the interval holds it, an equal bound included. */
    private static void assertShownNumberIsInside(CertifiedValue value, String text) throws NumericException {
        assertTrue(text.startsWith("≈ ") && text.endsWith("…"), () -> "not an approximate positional text: " + text);
        Rational shown = Rational.parse(text.substring(2, text.length() - 1).replace(',', '.'));
        assertTrue(shown.compareTo(value.lower()) >= 0, () -> text + " is below " + value.lower());
        assertTrue(shown.compareTo(value.upper()) <= 0, () -> text + " is above " + value.upper());
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "|", value = {
        "AUTO       | ≈ 1,5…",
        "DECIMAL    | ≈ 1,5…",
        "SCIENTIFIC | ≈ 1,5… × 10⁰",
        "FRACTION   | ≈ 3/2"})
    void theIntervalOfTheDefectReadsInsideItselfInEveryMode(DisplayMode mode, String expected) throws NumericException {
        assertEquals(expected, text(DEFECT_LOWER, DEFECT_UPPER, mode));
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void theIntervalOfTheDefectHasTheBoundOfItsNewText(DisplayMode mode) throws NumericException {
        assertEquals(Optional.of("± 8,3 × 10⁻²"), bound(DEFECT_LOWER, DEFECT_UPPER, mode), "1,5 - 1,41728 = 0,08272");
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "|", value = {
        "14/10   | 15/10   | AUTO    | ≈ 1,5…",
        "14/10   | 15/10   | DECIMAL | ≈ 1,5…",
        "-15/10  | -14/10  | AUTO    | ≈ -1,5…",
        "-15/10  | -14/10  | DECIMAL | ≈ -1,5…"})
    void aNumberEqualToABoundIsInsideTheInterval(String lower, String upper, DisplayMode mode, String expected)
            throws NumericException {
        assertEquals(expected, text(lower, upper, mode));
        assertEquals(Optional.of("± 1,0 × 10⁻¹"), bound(lower, upper, mode), "from the text to the other bound");
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "|", value = {
        "145/100 | 156/100",
        "144/100 | 155/100",
        "-156/100 | -145/100",
        "-155/100 | -144/100"})
    void theBoundRunsToTheSideFarthestFromTheText(String lower, String upper) throws NumericException {
        String expected = lower.startsWith("-") ? "≈ -1,5…" : "≈ 1,5…";

        assertEquals(expected, text(lower, upper, DisplayMode.AUTO));
        assertEquals(Optional.of("± 6,0 × 10⁻²"), bound(lower, upper, DisplayMode.AUTO), "the radius is only 0,055");
    }

    @ParameterizedTest
    @CsvSource(delimiterString = "|", value = {
        "141728/100000  | 158272/100000  | ≈ 1,5…",
        "-158272/100000 | -141728/100000 | ≈ -1,5…",
        "15/10          | 25/10          | ≈ 2…",
        "145/100        | 156/100        | ≈ 1,5…",
        "95/100         | 124/100        | ≈ 1…",
        "14/1000        | 16/1000        | ≈ 0,015…"})
    void theNumberShownInDecimalModeIsInsideTheInterval(String lower, String upper, String expected) throws NumericException {
        String shown = text(lower, upper, DisplayMode.DECIMAL);

        assertShownNumberIsInside(interval(lower, upper), shown);
        assertEquals(expected, shown, "the fewest digits that stay inside");
    }

    @Test
    void aCentreOfOneDigitAlreadyInsideKeepsOneDigit() throws NumericException {
        assertEquals("≈ 2…", text("15/10", "25/10", DisplayMode.AUTO));
        assertEquals("≈ 2…", text("15/10", "25/10", DisplayMode.DECIMAL));
        assertEquals("≈ 2… × 10⁰", text("15/10", "25/10", DisplayMode.SCIENTIFIC));
        assertEquals(Optional.of("± 5,0 × 10⁻¹"), bound("15/10", "25/10", DisplayMode.AUTO));
    }

    @Test
    void aCentreWhoseOneDigitRoundingCarriesKeepsItsTwoDigits() throws NumericException {
        // 9,695 rounded to one digit is 10, above the upper bound; to two digits it is 9,7.
        assertEquals("≈ 9,7…", text("94/10", "999/100", DisplayMode.DECIMAL));
        assertEquals("≈ 9,7…", text("94/10", "999/100", DisplayMode.AUTO));
        assertEquals("≈ 9,7… × 10⁰", text("94/10", "999/100", DisplayMode.SCIENTIFIC));
        assertEquals(Optional.of("± 3,0 × 10⁻¹"), bound("94/10", "999/100", DisplayMode.AUTO));
    }

    @Test
    void aCentreAboveTheUnitsStaysInsideInScientificNotation() throws NumericException {
        // Positional notation does not cut the integer part: 1,5 x 10^5 does not guarantee the units.
        assertEquals("≈ 1,5… × 10⁵", text("140000", "160000", DisplayMode.AUTO));
        assertEquals("≈ 1,5… × 10⁵", text("140000", "160000", DisplayMode.DECIMAL));
        assertEquals(Optional.of("± 1,0 × 10⁴"), bound("140000", "160000", DisplayMode.AUTO));
    }

    @Test
    void textsWithCommonDigitsDoNotChange() throws NumericException {
        assertEquals("≈ 1,4142…", text("141421/100000", "141423/100000", DisplayMode.AUTO));
        assertEquals(Optional.of("± 1,0 × 10⁻⁵"), bound("141421/100000", "141423/100000", DisplayMode.AUTO));
        assertEquals("≈ 3,33333333333… × 10⁻¹³", text("1/3000000000000", "1/2999999999999", DisplayMode.AUTO));
    }

    @ParameterizedTest
    @EnumSource(DisplayMode.class)
    void anIntervalThatContainsZeroKeepsItsBoundText(DisplayMode mode) throws NumericException {
        assertEquals("|x| ≤ 1,1 × 10¹", text("-1", "11", mode));
    }

    @Test
    void theExpressionOfTheDefectNoLongerReadsOutsideItsValue() throws Exception {
        EvaluatedNotebook evaluated = new NotebookEditor(Notebook.of(List.of(DEFECT_EXPRESSION))).current();

        assertTrue(evaluated.results().get(0).hasValue(), DEFECT_EXPRESSION);
        CertifiedValue value = evaluated.results().get(0).value();
        String shown = ValueFormatter.format(value, DisplayMode.AUTO).text();
        assertNotEquals("≈ 2…", shown);
        assertShownNumberIsInside(value, shown);
    }

    @Test
    void theAcceptanceReportRecordsTheCorrection() throws IOException {
        String report = Files.readString(REPORT, StandardCharsets.UTF_8);
        assertFalse(report.contains("Reste D6"), "section 7 still says D6 remains");

        String defect = report.lines().filter(line -> line.startsWith("| D6 |")).findFirst().orElse("");
        assertTrue(defect.contains("Corrigé après la recette"), defect);
        assertTrue(defect.contains(TEST_NAME), defect);
    }
}
