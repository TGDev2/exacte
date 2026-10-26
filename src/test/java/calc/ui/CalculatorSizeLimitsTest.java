package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.FileCalculationHistoryStore;
import calc.engine.InputField;
import calc.engine.Operation;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorSizeLimitsTest {
    private static final String RESULT_TOO_LONG = "Le résultat dépasse 1000 chiffres (réduisez les nombres saisis).";
    private static final String POWER_TOO_LONG = "Le résultat dépasse 1000 chiffres (réduisez l'exposant ou la base).";
    private static final Duration ACCEPTED_CASE_LIMIT = Duration.ofSeconds(5);

    private final CalculatorEngine engine = new CalculatorEngine();

    @Test
    void testFirstInputOfMaximumLengthIsAcceptedAndLongerIsRefused() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, paddedOne(1005), "1").getFormattedResult(),
                "A 1005-character input should pass the length check.");

        engine.calculate(Operation.ADDITION, "1", "1");
        CalculationException exception = assertTimeoutPreemptively(Duration.ofMillis(100), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, paddedOne(1006), "1"),
                "A 1006-character input should be refused."));
        assertEquals("Le premier nombre est trop long (1005 caractères maximum).", exception.getMessage(),
                "The message should state the limit.");
        assertEquals(InputField.FIRST, exception.getField(), "The first operand is the faulty field.");
    }

    @Test
    void testSecondInputOfMaximumLengthIsAcceptedAndLongerIsRefused() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, "1", paddedOne(1005)).getFormattedResult(),
                "A 1005-character input should pass the length check.");

        CalculationException exception = assertTimeoutPreemptively(Duration.ofMillis(100), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, "1", paddedOne(1006)),
                "A 1006-character input should be refused."));
        assertEquals("Le second nombre est trop long (1005 caractères maximum).", exception.getMessage(),
                "The message should state the limit.");
        assertEquals(InputField.SECOND, exception.getField(), "The second operand is the faulty field.");
    }

    @Test
    void testSurroundingSpacesDoNotCountTowardsTheInputLength() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, "  " + paddedOne(1005) + "  ", "1").getFormattedResult(),
                "The length is measured after trim.");
    }

    @Test
    void testPowerOfTwoIsAcceptedAndItsResultCanBeChained() throws Exception {
        CalculationRecord power = assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, "2", "999"));
        BigInteger expected = BigInteger.TWO.pow(999);
        assertEquals(expected.toString(), power.getFormattedResult(), "2^999 should be exact.");
        assertEquals(301, power.getFormattedResult().length(), "2^999 has 301 digits.");

        CalculationRecord chained = engine.calculate(Operation.ADDITION, power.getReusableValue(), "1");
        assertEquals(expected.add(BigInteger.ONE).toString(), chained.getFormattedResult(),
                "The reused result should chain into the next calculation.");
    }

    @Test
    void testPowerOfAHugeResultIsRefusedImmediately() throws Exception {
        String twoPower999 = engine.calculate(Operation.POWER, "2", "999").getReusableValue();

        CalculationException exception = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, twoPower999, "999"),
                "(2^999)^999 should be refused before any computation."));
        assertEquals(POWER_TOO_LONG, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "10", "0,0001"})
    void testPowerEstimateRefusesBeforeComputing(String base) {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, base, "999"),
                base + " ^ 999 should be refused.");
        assertEquals(POWER_TOO_LONG, exception.getMessage(), "The overflow message should be shown for " + base + ".");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @Test
    void testPowerEstimateAlsoCoversNegativeExponents() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, "99", "-999"),
                "Negative exponents use the same estimate.");
        assertEquals(POWER_TOO_LONG, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,5", "9", "2", "-2"})
    void testPowersWithinTheLimitAreAccepted(String base) {
        CalculationRecord record = assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, base, "999"));
        assertTrue(record.getFormattedResult().length() <= 1002, base + "^999 should fit in the display limit.");
    }

    @Test
    void testNegativeExponentWithinTheLimitIsAccepted() throws Exception {
        CalculationRecord record = assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, "2", "-999"));
        assertEquals(1001, record.getFormattedResult().length(), "2^-999 has 999 decimals after the leading \"0,\".");
    }

    @Test
    void testAdditionResultOfExactlyTheLimitIsAccepted() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, repeat('9', 999), "1");
        assertEquals("1" + repeat('0', 999), record.getFormattedResult(), "A 1000-digit result is allowed.");
    }

    @Test
    void testAdditionResultBeyondTheLimitIsRefused() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, repeat('9', 1000), "1"),
                "A 1001-digit result should be refused.");
        assertEquals(RESULT_TOO_LONG, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.NONE, exception.getField(), "No single operand is to blame.");
    }

    @Test
    void testProductOfTwoSixHundredDigitNumbersIsRefused() {
        String sixHundredNines = repeat('9', 600);
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.MULTIPLICATION, sixHundredNines, sixHundredNines),
                "A 1200-digit product should be refused.");
        assertEquals(RESULT_TOO_LONG, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.NONE, exception.getField(), "No single operand is to blame.");
    }

    @Test
    void testBoundaryCasesStayFast() {
        String thousandOnes = repeat('1', 1000);
        CalculationRecord sum = assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.ADDITION, thousandOnes, thousandOnes));
        assertEquals(repeat('2', 1000), sum.getFormattedResult(), "Two 1000-digit numbers add up to 1000 digits.");

        assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.POWER, "2", "999"));
        assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.POWER, "9", "999"));
        assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.ROOT, thousandOnes, ""));
        assertTimeoutPreemptively(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.DIVISION, repeat('1', 900), "7"));
    }

    @Test
    void testPresenterShowsInputLengthErrorOnTheRightFieldAndKeepsHistory() {
        CalculatorPresenter presenter = new CalculatorPresenter(engine);
        presenter.onCalculate("1", "2");

        CalculatorViewModel first = presenter.onCalculate(paddedOne(1006), "2");
        assertEquals(StatusTone.ERROR, first.getStatusTone(), "An oversized input is an error.");
        assertEquals("Le premier nombre est trop long (1005 caractères maximum).", first.getStatusMessage(),
                "The limit should be explained.");
        assertEquals(FocusTarget.FIRST_NUMBER, first.getFocusTarget(), "Focus returns to the faulty first operand.");
        assertEquals(1, first.getHistoryEntries().size(), "A refused calculation must not touch the history.");

        CalculatorViewModel second = presenter.onCalculate("1", paddedOne(1006));
        assertEquals("Le second nombre est trop long (1005 caractères maximum).", second.getStatusMessage(),
                "The limit should be explained.");
        assertEquals(FocusTarget.SECOND_NUMBER, second.getFocusTarget(), "Focus returns to the faulty second operand.");
        assertEquals(1, second.getHistoryEntries().size(), "A refused calculation must not touch the history.");
    }

    @Test
    void testPresenterRefusesChainedPowerWithoutFreezing() {
        CalculatorPresenter presenter = new CalculatorPresenter(engine);
        presenter.onOperationChanged(Operation.POWER, "2", "999");
        CalculatorViewModel first = presenter.onCalculate("2", "999");

        CalculatorViewModel second = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> presenter.onCalculate(first.getFirstInput(), "999"));
        assertEquals(StatusTone.ERROR, second.getStatusTone(), "The chained power should be refused.");
        assertEquals(POWER_TOO_LONG, second.getStatusMessage(), "The overflow message should be shown.");
        assertEquals(FocusTarget.SECOND_NUMBER, second.getFocusTarget(), "Focus goes to the exponent.");
        assertEquals(1, second.getHistoryEntries().size(), "A refused calculation must not touch the history.");
        assertEquals(first.getFirstInput(), second.getFirstInput(), "The inputs stay available for correction.");
    }

    @Test
    void testPresenterFocusesFirstOperandWhenTheResultIsTooLong() {
        CalculatorPresenter presenter = new CalculatorPresenter(engine);

        CalculatorViewModel viewModel = presenter.onCalculate(repeat('9', 1000), "1");

        assertEquals(RESULT_TOO_LONG, viewModel.getStatusMessage(), "The overflow message should be shown.");
        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.getFocusTarget(), "A NONE error falls back to the first operand.");
        assertEquals(0, viewModel.getHistoryEntries().size(), "A refused calculation must not touch the history.");
    }

    @Test
    void testOversizedHistoryLineIsSkippedWithBackupAndOthersLoad(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        String content = "ADDITION\t1\t2\t3\n"
                + "ADDITION\t" + paddedOne(1006) + "\t2\t3\n"
                + "MULTIPLICATION\t4\t5\t20\n";
        Files.writeString(historyFile, content, StandardCharsets.UTF_8);

        CalculatorPresenter presenter = new CalculatorPresenter(engine, new FileCalculationHistoryStore(historyFile));
        CalculatorViewModel viewModel = assertTimeoutPreemptively(Duration.ofSeconds(2), presenter::createInitialView);

        assertEquals(2, viewModel.getHistoryEntries().size(), "The valid lines around the oversized one should load.");
        assertEquals("1 + 2 = 3", viewModel.getHistoryEntries().get(0).toString(), "First valid line restored.");
        assertEquals("4 * 5 = 20", viewModel.getHistoryEntries().get(1).toString(), "Last valid line restored.");
        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "A skipped line is reported.");

        List<Path> backups;
        try (Stream<Path> files = Files.list(tempDirectory)) {
            backups = files.filter(path -> path.getFileName().toString().endsWith(".bak")).collect(Collectors.toList());
        }
        assertEquals(1, backups.size(), "A safety copy should be kept.");
        assertEquals(content, Files.readString(backups.get(0), StandardCharsets.UTF_8), "The copy is the original file.");
    }

    private static String paddedOne(int totalLength) {
        return repeat('0', totalLength - 1) + "1";
    }

    private static String repeat(char character, int count) {
        return String.valueOf(character).repeat(count);
    }
}
