package calc.engine;

import calc.persistence.JsonHistoryStore;
import calc.testing.TimingBudget;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bounds of the engine, which are those of the exact core since E06 (docs/adr/0004-regles-affichage.md, section 8):
 * an operand of at most 4100 characters, numerators and denominators of at most 4096 bits, an exponent between -4096
 * and 4096. Everything beyond is refused before any costly work, and every calculation within them stays short.
 */
public class SizeLimitsTest {
    private static final String RESULT_TOO_LARGE = "Le résultat dépasse la taille maximale d'un calcul exact (réduisez les nombres saisis).";
    private static final String POWER_TOO_LARGE = "Le résultat dépasse la taille maximale d'un calcul exact (réduisez l'exposant ou la base).";
    private static final Duration ACCEPTED_CASE_LIMIT = Duration.ofSeconds(5);
    private static final int MAX_LENGTH = 4100;
    // 2^4096 - 1: the largest integer within the 4096-bit limit (1234 digits).
    private static final BigInteger LARGEST = BigInteger.TWO.pow(4096).subtract(BigInteger.ONE);

    private final CalculatorEngine engine = new CalculatorEngine();

    @Test
    void testFirstInputOfMaximumLengthIsAcceptedAndLongerIsRefused() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, paddedOne(MAX_LENGTH), "1").getFormattedResult(),
                "A 4100-character input should pass the length check.");

        engine.calculate(Operation.ADDITION, "1", "1");
        CalculationException exception = TimingBudget.assertCompletesWithin(Duration.ofMillis(100), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, paddedOne(MAX_LENGTH + 1), "1"),
                "A 4101-character input should be refused."));
        assertEquals("Le premier nombre est trop long (4100 caractères maximum).", exception.getMessage(),
                "The message should state the limit.");
        assertEquals(InputField.FIRST, exception.getField(), "The first operand is the faulty field.");
    }

    @Test
    void testSecondInputOfMaximumLengthIsAcceptedAndLongerIsRefused() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, "1", paddedOne(MAX_LENGTH)).getFormattedResult(),
                "A 4100-character input should pass the length check.");

        CalculationException exception = TimingBudget.assertCompletesWithin(Duration.ofMillis(100), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, "1", paddedOne(MAX_LENGTH + 1)),
                "A 4101-character input should be refused."));
        assertEquals("Le second nombre est trop long (4100 caractères maximum).", exception.getMessage(),
                "The message should state the limit.");
        assertEquals(InputField.SECOND, exception.getField(), "The second operand is the faulty field.");
    }

    @Test
    void testSurroundingSpacesDoNotCountTowardsTheInputLength() throws Exception {
        assertEquals("2", engine.calculate(Operation.ADDITION, "  " + paddedOne(MAX_LENGTH) + "  ", "1").getFormattedResult(),
                "The length is measured after trim.");
    }

    @Test
    void testPowerOfTwoIsAcceptedAndItsResultCanBeChained() throws Exception {
        CalculationRecord power = TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, "2", "999"));
        BigInteger expected = BigInteger.TWO.pow(999);
        assertEquals("5,35754303593… × 10³⁰⁰", power.getFormattedResult(), "2^999 is shown with 12 significant digits.");
        assertEquals(expected.toString(), power.getReusableValue(), "2^999 is reused exactly, all 301 digits.");

        CalculationRecord chained = engine.calculate(Operation.ADDITION, power.getReusableValue(), "1");
        assertEquals(expected.add(BigInteger.ONE).toString(), chained.getReusableValue(),
                "The reused result should chain into the next calculation.");
    }

    @Test
    void testPowersUpToTheComponentLimitAreAcceptedAndBeyondRefused() throws Exception {
        assertEquals(BigInteger.TWO.pow(4095).toString(), engine.calculate(Operation.POWER, "2", "4095").getReusableValue(),
                "2^4095 has 4096 bits: accepted.");
        CalculationException exception = assertThrows(CalculationException.class, () -> engine.calculate(Operation.POWER, "2", "4096"));
        assertEquals(POWER_TOO_LARGE, exception.getMessage(), "2^4096 has 4097 bits: refused.");
        assertEquals(InputField.SECOND, exception.getField());
    }

    @Test
    void testPowerOfAHugeResultIsRefusedImmediately() throws Exception {
        String twoPower999 = engine.calculate(Operation.POWER, "2", "999").getReusableValue();

        CalculationException exception = TimingBudget.assertCompletesWithin(Duration.ofSeconds(1), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, twoPower999, "999"),
                "(2^999)^999 should be refused before any computation."));
        assertEquals(POWER_TOO_LARGE, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"99", "10", "0,0001"})
    void testPowerSizeIsRefusedBeforeComputing(String base) {
        CalculationException exception = TimingBudget.assertCompletesWithin(Duration.ofSeconds(1), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, base, "4096"),
                base + " ^ 4096 should be refused."));
        assertEquals(POWER_TOO_LARGE, exception.getMessage(), "The overflow message should be shown for " + base + ".");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @Test
    void testPowerSizeAlsoCoversNegativeExponents() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, "99", "-4096"),
                "Negative exponents use the same estimate.");
        assertEquals(POWER_TOO_LARGE, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.SECOND, exception.getField(), "The exponent is the field to reduce.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0,5", "9", "2", "-2"})
    void testPowersWithinTheLimitAreAcceptedAndExact(String base) {
        CalculationRecord record = TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, base, "999"));
        assertTrue(record.isExact(), base + "^999 is exact.");
        assertTrue(record.getReusableValue().length() <= MAX_LENGTH, base + "^999 can be typed back.");
    }

    @Test
    void testNegativeExponentWithinTheLimitIsAccepted() throws Exception {
        CalculationRecord record = TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.POWER, "2", "-999"));
        assertEquals("1,86652723701… × 10⁻³⁰¹", record.getFormattedResult(), "2^-999 is shown with its significant digits.");
        assertEquals("1/" + BigInteger.TWO.pow(999), record.getReusableValue(), "The fraction is shorter than the 999 decimals.");
    }

    @Test
    void testAdditionResultOfExactlyTheLimitIsAccepted() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, LARGEST.subtract(BigInteger.ONE).toString(), "1");
        assertEquals(LARGEST.toString(), record.getReusableValue(), "A 4096-bit result is allowed.");
    }

    @Test
    void testAdditionResultBeyondTheLimitIsRefused() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, LARGEST.toString(), "1"),
                "A 4097-bit result should be refused.");
        assertEquals(RESULT_TOO_LARGE, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.NONE, exception.getField(), "No single operand is to blame.");
    }

    @Test
    void testProductOfTwoThousandDigitNumbersIsRefused() {
        String thousandNines = repeat('9', 1000);
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.MULTIPLICATION, thousandNines, thousandNines),
                "A 2000-digit product should be refused.");
        assertEquals(RESULT_TOO_LARGE, exception.getMessage(), "The overflow message should be shown.");
        assertEquals(InputField.NONE, exception.getField(), "No single operand is to blame.");
    }

    @Test
    void testSixtyThousandDigitInputIsRefusedImmediatelyOnEitherOperand() {
        String sixtyThousandDigits = repeat('9', 60_000);

        CalculationException first = TimingBudget.assertCompletesWithin(Duration.ofSeconds(2), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, sixtyThousandDigits, "1")));
        CalculationException second = TimingBudget.assertCompletesWithin(Duration.ofSeconds(2), () -> assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.MULTIPLICATION, "1", sixtyThousandDigits)));

        assertEquals("Le premier nombre est trop long (4100 caractères maximum).", first.getMessage());
        assertEquals(InputField.FIRST, first.getField());
        assertEquals("Le second nombre est trop long (4100 caractères maximum).", second.getMessage());
        assertEquals(InputField.SECOND, second.getField());
    }

    /** Parsing, computing and formatting the heaviest calculations the bounds allow, or refusing them. */
    @Test
    void testWorstCaseBoundedCalculationsFinishUnderFiftyMilliseconds() throws Exception {
        String largest = LARGEST.toString();
        String largestFraction = LARGEST + "/" + LARGEST.subtract(BigInteger.TWO);
        String otherFraction = LARGEST.subtract(BigInteger.TEN) + "/" + LARGEST.subtract(BigInteger.valueOf(4));
        String sevens = repeat('7', MAX_LENGTH);
        String twoPower999 = engine.calculate(Operation.POWER, "2", "999").getReusableValue();
        String[][] cases = {
            {"ROOT", largest, ""},
            {"ROOT", largestFraction, ""},
            {"ROOT", sevens, ""},
            {"MULTIPLICATION", repeat('9', 600), repeat('9', 600)},
            {"MULTIPLICATION", largestFraction, otherFraction},
            {"DIVISION", largest, repeat('9', 1232) + "7"},
            {"DIVISION", largestFraction, otherFraction},
            {"ADDITION", largestFraction, otherFraction},
            {"ADDITION", largest, largest},
            {"POWER", "3", "2584"},
            {"POWER", "7", "-1459"},
            {"POWER", "9", "999"},
            {"POWER", repeat('9', 600), "-2"},
            {"POWER", twoPower999, "999"},
            {"ADDITION", repeat('9', 60_000), "1"},
        };

        for (String[] testCase : cases) {
            Operation operation = Operation.valueOf(testCase[0]);
            long best = Long.MAX_VALUE;
            for (int attempt = 0; attempt < 8; attempt++) {
                long start = System.nanoTime();
                try {
                    engine.calculate(operation, testCase[1], testCase[2]);
                } catch (CalculationException refused) {
                    // refusing is as valid as computing: only the duration matters here
                }
                best = Math.min(best, System.nanoTime() - start);
            }
            TimingBudget.assertWithin(operation + " " + abbreviate(testCase[1]) + " " + abbreviate(testCase[2]),
                    best, Duration.ofMillis(50).toNanos(), TimeUnit.NANOSECONDS);
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 12 ? text.substring(0, 6) + "..(" + text.length() + " chars)" : text;
    }

    @Test
    void testBoundaryCasesStayFast() {
        String thousandOnes = repeat('1', 1000);
        CalculationRecord sum = TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT,
                () -> engine.calculate(Operation.ADDITION, thousandOnes, thousandOnes));
        assertEquals(repeat('2', 1000), sum.getReusableValue(), "Two 1000-digit numbers add up to 1000 digits.");
        assertEquals("2,22222222222… × 10⁹⁹⁹", sum.getFormattedResult());

        TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.POWER, "2", "999"));
        TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.POWER, "9", "999"));
        TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.ROOT, thousandOnes, ""));
        TimingBudget.assertCompletesWithin(ACCEPTED_CASE_LIMIT, () -> engine.calculate(Operation.DIVISION, repeat('1', 900), "7"));
    }

    @Test
    void testOversizedHistoryLineIsSkippedWithBackupAndOthersLoad(@TempDir Path tempDirectory) throws Exception {
        Path historyFile = tempDirectory.resolve("history.tsv");
        String content = "ADDITION\t1\t2\t3\n"
                + "ADDITION\t" + paddedOne(MAX_LENGTH + 1) + "\t2\t3\n"
                + "MULTIPLICATION\t4\t5\t20\n";
        Files.writeString(historyFile, content, StandardCharsets.UTF_8);

        HistoryLoadResult loaded;
        try (JsonHistoryStore store = JsonHistoryStore.open(tempDirectory)) {
            loaded = TimingBudget.assertCompletesWithin(Duration.ofSeconds(2), store::loadHistory);
        }

        assertEquals(2, loaded.records().size(), "The valid lines around the oversized one should load.");
        assertEquals("1 + 2 = 3", loaded.records().get(0).toString(), "First valid line restored.");
        assertEquals("4 * 5 = 20", loaded.records().get(1).toString(), "Last valid line restored.");
        assertEquals(1, loaded.rejections().size(), "A skipped line is reported.");

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
