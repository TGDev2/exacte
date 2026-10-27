package calc.engine;

import calc.persistence.JsonHistoryStore;
import calc.testing.DisplayTexts;
import calc.testing.FractionOracle;
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

/**
 * The display rules of docs/adr/0004-regles-affichage.md on the results of the engine: 12 significant digits rounded
 * half up, « … » when digits are left out, « ≈ » and only guaranteed digits for an approximate root, scientific notation
 * outside {@code [10^-6, 10^15)}. Before E06 these cases were rounded to 12 decimals (CalculatorRoundingPolicyTest).
 */
public class DisplayRulesTest {
    private static final MathContext ORACLE = new MathContext(80, RoundingMode.DOWN);

    private final CalculatorEngine engine = new CalculatorEngine();

    static Stream<Arguments> referenceValues() {
        return Stream.of(
                Arguments.of(Operation.DIVISION, "1", "3", "0,333333333333…"),
                Arguments.of(Operation.DIVISION, "2", "3", "0,666666666667…"),
                Arguments.of(Operation.DIVISION, "1", "30", "0,0333333333333…"),
                Arguments.of(Operation.DIVISION, "1", "3000000000000", "3,33333333333… × 10⁻¹³"),
                Arguments.of(Operation.DIVISION, "1", "7000000000000", "1,42857142857… × 10⁻¹³"),
                Arguments.of(Operation.POWER, "3", "-30", "4,85693574962… × 10⁻¹⁵"),
                Arguments.of(Operation.ROOT, "2", "", "≈ 1,41421356237…"),
                Arguments.of(Operation.ROOT, "0,5", "", "≈ 0,707106781187…"),
                Arguments.of(Operation.ROOT, "0,00000000000000000002", "", "≈ 1,41421356237… × 10⁻¹⁰"),
                Arguments.of(Operation.ROOT, "0,00000000000000000000000001", "", "1 × 10⁻¹³"),
                Arguments.of(Operation.ROOT, "9/4", "", "1,5"),
                Arguments.of(Operation.DIVISION, "1000000000000000000000", "3", "3,33333333333… × 10²⁰"),
                Arguments.of(Operation.DIVISION, "1", "1048576", "9,53674316406… × 10⁻⁷"),
                Arguments.of(Operation.DIVISION, "1", "8", "0,125"),
                Arguments.of(Operation.ADDITION, "123456789012345", "0", "123456789012345"),
                Arguments.of(Operation.ADDITION, "1000000000000000", "0", "1 × 10¹⁵"));
    }

    @ParameterizedTest(name = "{0} {1} {2} = {3}")
    @MethodSource("referenceValues")
    void testReferenceValuesFollowTheDisplayRules(Operation operation, String first, String second, String expected)
            throws Exception {
        CalculationRecord record = engine.calculate(operation, first, second);
        assertEquals(expected, record.getFormattedResult(), operation + " " + first + " " + second + " should follow the display rules.");
        assertEquals(!expected.startsWith("≈"), record.isExact(), "Only a root that is not exact is approximate.");
    }

    @Test
    void testRootsJustBelowARoundingMidpointAreRoundedDown() throws Exception {
        // True roots: 20000.0000000000004999... and 300000000000000.0000000000004999...
        assertEquals("≈ 20000,0000000…", engine.calculate(Operation.ROOT, "400000000,00000002", "").getFormattedResult(),
                "A root just below a midpoint must round down.");
        // The units of 3 × 10^14 are not among the 12 guaranteed digits: scientific notation.
        assertEquals("≈ 3,00000000000… × 10¹⁴", engine.calculate(Operation.ROOT, "90000000000000000000000000300", "").getFormattedResult(),
                "A root just below a midpoint must round down.");
    }

    @Test
    void testSeededDivisionsAreExactNeverZeroAndCorrectlyRounded() throws Exception {
        Random random = new Random(20261025L);
        int cut = 0;
        for (int index = 0; index < 3000; index++) {
            BigDecimal dividend = randomDecimal(random, 1 + random.nextInt(15), random.nextInt(7));
            if (random.nextBoolean()) {
                dividend = dividend.negate();
            }
            BigDecimal divisor = randomDecimal(random, 1 + random.nextInt(30), random.nextInt(4));
            String label = dividend.toPlainString() + " / " + divisor.toPlainString();

            CalculationRecord record = engine.calculate(Operation.DIVISION, input(dividend), input(divisor));
            assertTrue(record.isExact(), label + " is exact.");
            assertTrue(FractionOracle.of(dividend).divide(FractionOracle.of(divisor))
                    .hasValue(record.getResult().lower().numerator(), record.getResult().lower().denominator()), label + " value.");
            assertNotEquals(0, DisplayTexts.value(record.getFormattedResult()).signum(), label + " is not shown as zero.");

            BigDecimal shown = DisplayTexts.decimal(record.getFormattedResult());
            assertEquals(0, dividend.divide(divisor, shown.scale(), RoundingMode.HALF_UP).compareTo(shown),
                    label + " is shown as " + record.getFormattedResult() + ", the rounding half up of the quotient.");
            if (record.getFormatted().truncated()) {
                cut++;
                assertTrue(record.getFormattedResult().contains("…"), label + " leaves digits out and says so.");
            }
        }
        assertTrue(cut >= 2000, "The series should contain at least 2000 quotients with digits left out: " + cut);
    }

    @Test
    void testSeededRootsContainTheTrueRootAndShowItsRounding() throws Exception {
        Random random = new Random(1048576L);
        for (int index = 0; index < 2000; index++) {
            BigDecimal value = randomDecimal(random, 1 + random.nextInt(25), random.nextInt(40));
            if (index % 4 == 0) {
                value = value.multiply(value);
            }
            String label = "sqrt(" + value.toPlainString() + ")";

            CalculationRecord record = engine.calculate(Operation.ROOT, input(value), "");
            BigDecimal root = value.sqrt(ORACLE);
            assertTrue(record.isExact() || index % 4 != 0, label + " of a square is exact.");
            if (record.isExact()) {
                assertEquals(0, root.compareTo(record.getResult().lower().toExactDecimal().orElseThrow()), label);
                continue;
            }
            assertTrue(FractionOracle.of(root).compareTo(oracle(record.getResult().upper())) <= 0, label + " below its upper bound.");
            assertTrue(oracle(record.getResult().lower()).compareTo(FractionOracle.of(root.add(root.ulp()))) <= 0, label + " above its lower bound.");
            BigDecimal shown = DisplayTexts.decimal(record.getFormattedResult());
            assertEquals(0, root.setScale(shown.scale(), RoundingMode.HALF_UP).compareTo(shown),
                    label + " is shown as " + record.getFormattedResult() + ", the rounding of " + root);
            assertTrue(record.getFormatted().errorBound().isPresent(), label + " has an error bound.");
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

        try (JsonHistoryStore writer = JsonHistoryStore.open(tempDirectory)) {
            writer.saveHistory(records);
        }
        HistoryLoadResult restored;
        try (JsonHistoryStore reader = JsonHistoryStore.open(tempDirectory)) {
            restored = reader.loadHistory();
        }

        assertEquals(0, restored.skippedLines(), "Every persisted entry should reload.");
        assertEquals(records.size(), restored.records().size(), "Every persisted entry should reload.");
        for (int index = 0; index < records.size(); index++) {
            CalculationRecord original = records.get(index);
            CalculationRecord reloaded = restored.records().get(index);
            assertEquals(original.toString(), reloaded.toString(), "The reloaded entry should read the same.");
            assertEquals(original.getResult(), reloaded.getResult(), original + " should reload the same value.");

            CalculationRecord recomputed = engine.calculate(reloaded.getOperation(), reloaded.getFirstValue(), reloaded.getSecondValue());
            assertEquals(original.getFormattedResult(), recomputed.getFormattedResult(), original + " should recompute from its operands.");
        }
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

    private static FractionOracle oracle(calc.number.Rational value) {
        return new FractionOracle(value.numerator(), value.denominator());
    }
}
