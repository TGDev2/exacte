package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.NotebookEvaluator;
import calc.number.CertifiedValue;
import calc.number.Rational;
import calc.persistence.NotebookCodec;
import calc.testing.DisplayTexts;
import calc.testing.FractionOracle;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookRow.Status;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Idea 1 of docs/RECETTE.md, the exact engine, from the keyboard to the file: a value is exact, or approximate with a
 * guaranteed bound; a value that is not zero is never shown as 0; reusing a result never degrades it.
 */
@Tag("acceptance")
class ExactEngineAcceptanceTest {
    private static final long BUDGET_MILLIS = 1_000;
    private static final MathContext ORACLE_PRECISION = new MathContext(1_000);

    @TempDir
    Path data;

    private AcceptanceSession session;
    private NotebookDriver d;

    @BeforeEach
    void start() {
        session = AcceptanceSession.start(data);
        d = session.d;
    }

    @AfterEach
    void stop() {
        session.close();
    }

    // What the automatic save holds, calculated again: the guaranteed intervals behind what the window shows.
    private EvaluatedNotebook saved() throws Exception {
        return NotebookEvaluator.evaluate(NotebookCodec.read(session.store.currentFile()).notebook());
    }

    @Test
    void oneThirdThenAnsTimesThreeIsExactlyOneInEveryMode() throws Exception {
        d.line("1 / 3").line("ans * 3").line("(1 ÷ 3) × 3");

        assertEquals(List.of(Status.EXACT, Status.EXACT, Status.EXACT), session.statuses());
        assertEquals("1", d.row(2).result());
        assertEquals("1", d.row(3).result());
        for (DisplayMode mode : DisplayMode.values()) {
            d.mode(mode);
            assertEquals(Status.EXACT, d.row(2).status(), mode.name());
            assertTrue(DisplayTexts.value(d.row(2).result()).hasValue(BigInteger.ONE, BigInteger.ONE), mode + ": " + d.row(2).result());
        }
        assertEquals(CertifiedValue.ONE, saved().result(2).value());
        assertEquals(CertifiedValue.ONE, saved().result(3).value());
    }

    @Test
    void theSquareRootOfTwoIsApproximateWithABoundThatHoldsTheTrueValue() throws Exception {
        d.line("√2");

        NotebookRow row = d.row(1);
        assertEquals(Status.APPROXIMATE, row.status());
        assertTrue(row.result().startsWith("≈ "), row.result());
        assertTrue(row.detail().contains("±"), "the tooltip gives the bound: " + row.detail());

        CertifiedValue value = saved().result(1).value();
        assertFalse(value.isExact());
        // A proof with integers: lower² <= 2 <= upper².
        assertTrue(square(value.lower()).compareTo(Rational.of(2)) <= 0, "lower bound " + value.lower());
        assertTrue(square(value.upper()).compareTo(Rational.of(2)) >= 0, "upper bound " + value.upper());

        // A second opinion from an oracle of 1000 digits that the production code does not use.
        BigDecimal root = BigDecimal.valueOf(2).sqrt(ORACLE_PRECISION);
        assertTrue(decimal(value.lower(), RoundingMode.FLOOR).compareTo(root) <= 0);
        assertTrue(decimal(value.upper(), RoundingMode.CEILING).compareTo(root) >= 0);

        // The text is the true value, correctly rounded to the digits shown.
        BigDecimal shown = DisplayTexts.decimal(row.result());
        assertEquals(root.setScale(shown.scale(), RoundingMode.HALF_UP), shown, "shown " + row.result());

        // The announced bound really bounds the error of the centre.
        FormattedValue formatted = ValueFormatter.format(value, DisplayMode.AUTO);
        BigDecimal bound = DisplayTexts.decimal(formatted.errorBound().orElseThrow().substring(2));
        BigDecimal error = decimal(value.midpoint(), RoundingMode.HALF_EVEN).subtract(root).abs();
        assertTrue(error.compareTo(bound) <= 0, "error " + error + " over the announced " + bound);

        d.line("ans * ans");
        assertEquals(Status.APPROXIMATE, d.row(2).status(), "an approximate value never turns exact by chance");
        assertTrue(saved().result(2).value().contains(Rational.of(2)), "reusing it keeps the true value in the interval");
    }

    @ParameterizedTest(name = "{0} then {1}")
    @CsvSource(delimiter = '|', value = {"1 / 3000000000000 | ans * 3000000000000", "3 ^ -30 | ans * 3 ^ 30"})
    void aTinyValueIsNeverShownAsZeroAndItsReuseStaysExact(String tiny, String reuse) throws Exception {
        d.line(tiny);

        for (DisplayMode mode : DisplayMode.values()) {
            d.mode(mode);
            String shown = d.row(1).result();
            assertEquals(Status.EXACT, d.row(1).status(), mode.name());
            assertFalse(shown.isBlank(), mode.name());
            assertTrue(DisplayTexts.value(shown).signum() > 0, mode + " shows a value that reads 0: " + shown);
        }

        d.mode(DisplayMode.AUTO).line(reuse);
        assertEquals(Status.EXACT, d.row(2).status());
        assertEquals("1", d.row(2).result());
        assertEquals(CertifiedValue.ONE, saved().result(2).value());
    }

    @Test
    void aRootThatFallsRightIsExact() throws Exception {
        d.line("√(9/4)");

        assertEquals(Status.EXACT, d.row(1).status());
        assertEquals("1,5", d.row(1).result());
        d.mode(DisplayMode.FRACTION);
        assertEquals("3/2", d.row(1).result());
        assertEquals(CertifiedValue.exact(Rational.of(3, 2)), saved().result(1).value());
    }

    @Test
    void aChainOfFiftyExactOperationsStaysExactAndEqualsAnIndependentOracle() throws Exception {
        d.line("1/7");
        FractionOracle expected = new FractionOracle(BigInteger.ONE, BigInteger.valueOf(7));
        for (int step = 0; step < 50; step++) {
            switch (step % 4) {
                case 0 -> {
                    d.line("ans * 3");
                    expected = expected.multiply(FractionOracle.of(3));
                }
                case 1 -> {
                    d.line("ans / 2");
                    expected = expected.divide(FractionOracle.of(2));
                }
                case 2 -> {
                    d.line("ans + 1/11");
                    expected = expected.add(new FractionOracle(BigInteger.ONE, BigInteger.valueOf(11)));
                }
                default -> {
                    d.line("ans - 2/13");
                    expected = expected.subtract(new FractionOracle(BigInteger.TWO, BigInteger.valueOf(13)));
                }
            }
            assertEquals(Status.EXACT, d.row(step + 2).status(), "step " + step);
        }

        CertifiedValue last = saved().result(51).value();
        assertTrue(last.isExact());
        assertTrue(expected.hasValue(last.lower().numerator(), last.lower().denominator()), "got " + last.lower());
    }

    static Stream<String> hugeInputs() {
        return Stream.of(
                "9".repeat(1_000_000),
                "9".repeat(10_000),
                "1e99999999",
                "2^999^999",
                "9^9^9^9^9^9^9^9^9^9",
                "(1/3)^999999",
                "(".repeat(10_000) + "1" + ")".repeat(10_000),
                "√(".repeat(1_000) + "2" + ")".repeat(1_000));
    }

    /** Timed after a warm-up, best of three: it fails on a hang or an unbounded calculation, not on a busy machine. */
    @ParameterizedTest(name = "[{index}]")
    @MethodSource("hugeInputs")
    @Tag("robustness")
    void aHugeInputIsRefusedQuicklyAndWithoutAValue(String input) {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 4; run++) {
            long start = System.nanoTime();
            d.type(input).enter();
            long millis = (System.nanoTime() - start) / 1_000_000;
            best = run == 0 ? best : Math.min(best, millis);

            boolean refusedByTheWindow = d.view.statusTone() == StatusTone.ERROR && session.lines().isEmpty();
            boolean lineInError = !session.lines().isEmpty() && d.row(1).status() == Status.ERROR && !d.row(1).errorMessage().isBlank();
            assertTrue(refusedByTheWindow || lineInError, () -> input.length() + " characters: neither refused nor in error, status "
                    + d.view.statusMessage() + ", results " + session.results());

            d.escape();
            if (d.view.canUndo()) {
                d.undo();
            }
        }
        System.out.println("[acceptance] input of " + input.length() + " characters refused in " + best + " ms");
        assertTrue(best < BUDGET_MILLIS, "an input of " + input.length() + " characters took " + best + " ms");
    }

    private static Rational square(Rational value) {
        return value.multiply(value);
    }

    private static BigDecimal decimal(Rational value, RoundingMode rounding) {
        return new BigDecimal(value.numerator()).divide(new BigDecimal(value.denominator()), ORACLE_PRECISION.getPrecision(), rounding);
    }
}
