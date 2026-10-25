package Tests;

import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.FileCalculationHistoryStore;
import Calcul.HistoryLoadResult;
import Calcul.Operation;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorViewModel;
import Graphique.ReuseTarget;
import Graphique.StatusTone;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorRoundingPolicyTest {
    private static final int MIN_APPROXIMATION_DECIMALS = 12;
    private static final int MIN_SIGNIFICANT_DIGITS = 12;
    private static final MathContext REFERENCE_CONTEXT = new MathContext(60, RoundingMode.HALF_EVEN);
    private static final BigDecimal MAX_RELATIVE_ERROR = new BigDecimal("5E-12");

    private final CalculatorEngine engine = new CalculatorEngine();

    static Stream<Arguments> referenceValues() {
        return Stream.of(
                Arguments.of(Operation.DIVISION, "1", "3", "0,333333333333"),
                Arguments.of(Operation.DIVISION, "2", "3", "0,666666666667"),
                Arguments.of(Operation.DIVISION, "1", "30", "0,0333333333333"),
                Arguments.of(Operation.DIVISION, "1", "3000000000000", "0,000000000000333333333333"),
                Arguments.of(Operation.DIVISION, "1", "7000000000000", "0,000000000000142857142857"),
                Arguments.of(Operation.POWER, "3", "-30", "0,00000000000000485693574962"),
                Arguments.of(Operation.ROOT, "2", "", "1,414213562373"),
                Arguments.of(Operation.ROOT, "0,5", "", "0,707106781187"),
                Arguments.of(Operation.ROOT, "0,00000000000000000002", "", "0,000000000141421356237"),
                Arguments.of(Operation.ROOT, "0,00000000000000000000000001", "", "0,0000000000001"),
                Arguments.of(Operation.DIVISION, "1000000000000000000000", "3", "333333333333333333333,333333333333"),
                Arguments.of(Operation.DIVISION, "1", "1048576", "0,00000095367431640625"));
    }

    @ParameterizedTest(name = "{0} {1} {2} = {3}")
    @MethodSource("referenceValues")
    void testReferenceValuesAreReproducedExactly(Operation operation, String first, String second, String expected)
            throws Exception {
        CalculationRecord record = engine.calculate(operation, first, second);
        assertEquals(expected, record.getFormattedResult(), operation + " " + first + " " + second + " should follow the rounding rule.");
        assertEquals(expected, record.getReusableValue(), "The reused value should be the displayed value.");
    }

    @Test
    void testSeededDivisionsAreNeverZeroAndStayWithinTheRelativeError() throws Exception {
        Random random = new Random(20261025L);
        int approximated = 0;
        for (int index = 0; index < 3000; index++) {
            BigDecimal dividend = randomDecimal(random, 1 + random.nextInt(15), random.nextInt(7));
            if (random.nextBoolean()) {
                dividend = dividend.negate();
            }
            BigDecimal divisor = randomDecimal(random, 1 + random.nextInt(30), random.nextInt(4));
            String label = dividend.toPlainString() + " / " + divisor.toPlainString();

            BigDecimal result = engine.calculate(Operation.DIVISION, input(dividend), input(divisor)).getResultValue();
            assertNotEquals(0, result.signum(), label + " is not zero.");

            if (isTerminating(dividend, divisor)) {
                assertEquals(0, result.compareTo(dividend.divide(divisor)), label + " is exact and must not be rounded.");
                continue;
            }
            approximated++;
            BigDecimal reference = dividend.divide(divisor, REFERENCE_CONTEXT);
            BigDecimal relativeError = result.subtract(reference).abs().divide(reference.abs(), MathContext.DECIMAL64);
            assertTrue(relativeError.compareTo(MAX_RELATIVE_ERROR) <= 0, label + " relative error " + relativeError);
            BigDecimal correctlyRounded = dividend.divide(divisor, ruleScale(reference), RoundingMode.HALF_UP);
            assertEquals(0, result.compareTo(correctlyRounded), label + " should be rounded once, HALF_UP, at the rule's scale.");
        }
        assertTrue(approximated >= 2000, "The series should contain at least 2000 approximate divisions: " + approximated);
    }

    @Test
    void testSeededRootsMatchAnExactlyRoundedOracle() throws Exception {
        Random random = new Random(1048576L);
        for (int index = 0; index < 2000; index++) {
            BigDecimal value = randomDecimal(random, 1 + random.nextInt(25), random.nextInt(40));
            if (index % 4 == 0) {
                value = value.multiply(value);
            }

            BigDecimal result = engine.calculate(Operation.ROOT, input(value), "").getResultValue();
            assertEquals(0, result.compareTo(oracleRoot(value)), "sqrt(" + value.toPlainString() + ") = " + result.toPlainString());
        }
    }

    @Test
    void testHistoryRoundTripRecomputesTheSameValues(@TempDir Path tempDirectory) throws Exception {
        List<CalculationRecord> records = new ArrayList<>();
        for (Arguments arguments : referenceValues().toList()) {
            Object[] values = arguments.get();
            CalculationRecord record = engine.calculate((Operation) values[0], (String) values[1], (String) values[2]);
            records.add(record);
            records.add(engine.calculate(Operation.DIVISION, record.getReusableValue(), "7"));
        }

        Path historyFile = tempDirectory.resolve("history.tsv");
        new FileCalculationHistoryStore(historyFile).saveHistory(records);
        HistoryLoadResult restored = new FileCalculationHistoryStore(historyFile).loadHistory();

        assertEquals(0, restored.skippedLines(), "Every persisted entry should reload.");
        assertEquals(records.size(), restored.records().size(), "Every persisted entry should reload.");
        for (int index = 0; index < records.size(); index++) {
            CalculationRecord original = records.get(index);
            CalculationRecord reloaded = restored.records().get(index);
            assertEquals(original.toString(), reloaded.toString(), "The reloaded entry should read the same.");
            assertEquals(0, original.getResultValue().compareTo(reloaded.getResultValue()), original + " should reload the same value.");

            CalculationRecord recomputed = engine.calculate(reloaded.getOperation(), reloaded.getFirstValue(), reloaded.getSecondValue());
            assertEquals(original.getFormattedResult(), recomputed.getFormattedResult(), original + " should recompute from its displayed operands.");
        }
    }

    @Test
    void testTinyResultDividedByItselfIsNotADivisionByZero() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.DIVISION, "", "");

        CalculatorViewModel tiny = presenter.onCalculate("1", "3000000000000");
        assertEquals("0,000000000000333333333333", tiny.getResultValue(), "A tiny quotient should keep its significant digits.");

        CalculatorViewModel reused = presenter.onReuseHistoryResult(ReuseTarget.SECOND_NUMBER, tiny.getFirstInput(), "");
        CalculatorViewModel chained = presenter.onCalculate(reused.getFirstInput(), reused.getSecondInput());

        assertEquals(StatusTone.SUCCESS, chained.getStatusTone(), chained.getStatusMessage());
        assertEquals("1", chained.getResultValue(), "A non-zero value divided by itself is 1.");
        assertEquals(
                "0,000000000000333333333333 / 0,000000000000333333333333 = 1",
                chained.getHistoryEntries().get(1).toString(),
                "The chained entry should be recorded.");
    }

    private static BigDecimal randomDecimal(Random random, int digits, int scale) {
        StringBuilder unscaled = new StringBuilder().append(1 + random.nextInt(9));
        for (int index = 1; index < digits; index++) {
            unscaled.append(random.nextInt(10));
        }
        return new BigDecimal(new BigInteger(unscaled.toString()), scale);
    }

    private static String input(BigDecimal value) {
        return value.toPlainString().replace('.', ',');
    }

    private static boolean isTerminating(BigDecimal dividend, BigDecimal divisor) {
        try {
            dividend.divide(divisor);
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static int ruleScale(BigDecimal value) {
        int firstDigitExponent = value.precision() - value.scale() - 1;
        return Math.max(MIN_APPROXIMATION_DECIMALS, MIN_SIGNIFICANT_DIGITS - 1 - firstDigitExponent);
    }

    // floor(sqrt(value) * 10^decimals) from the exact integer square root, then a single HALF_UP:
    // a truncated value with digits beyond the final scale rounds exactly like the true root.
    private static BigDecimal oracleRoot(BigDecimal value) {
        int decimals = value.scale() + 30;
        BigInteger scaled = value.unscaledValue().multiply(BigInteger.TEN.pow(2 * decimals - value.scale()));
        BigInteger root = scaled.sqrt();
        BigDecimal truncated = new BigDecimal(root, decimals);
        if (root.multiply(root).equals(scaled)) {
            return truncated;
        }
        return truncated.setScale(ruleScale(truncated), RoundingMode.HALF_UP);
    }
}
