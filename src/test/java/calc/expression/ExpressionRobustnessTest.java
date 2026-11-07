package calc.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.Rational;
import calc.testing.TimingBudget;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/**
 * Hostile lines end in a typed refusal, quickly, and never in a {@link StackOverflowError}: the limits are checked
 * explicitly before the work they protect (docs/adr/0007-grammaire-expressions.md, section 3).
 */
class ExpressionRobustnessTest {
    /** Lines of any length: only the depth and the node count protect the parser. */
    private static final ExpressionParser UNBOUNDED_LENGTH =
            new ExpressionParser(new ExpressionLimits(Integer.MAX_VALUE, 100, 500));

    @Test
    void tenThousandNestedParenthesesAreRefusedByTheDepthCheck() {
        String line = "(".repeat(10_000) + "1" + ")".repeat(10_000);

        ExpressionException refusal = assertThrows(ExpressionException.class, () -> UNBOUNDED_LENGTH.parse(line));

        assertEquals(ExpressionError.TOO_DEEP, refusal.error());
        assertEquals(new Span(100, 101), refusal.span());
        assertEquals("Trop d'imbrications (100 niveaux maximum).", refusal.getMessage());
    }

    @Test
    void tenThousandNestedParenthesesWithTheDefaultLimitsAreAnOverlongLine() {
        String line = "(".repeat(10_000) + "1" + ")".repeat(10_000);

        ExpressionException refusal = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine(line));

        assertEquals(ExpressionError.LINE_TOO_LONG, refusal.error());
        assertEquals(new Span(1000, 20_001), refusal.span());
    }

    @Test
    void everyNestingConstructIsCounted() {
        for (String line : new String[] {"-".repeat(10_000) + "1", "+-".repeat(5_000) + "1", "2^".repeat(10_000) + "2",
                String.valueOf((char) 0x221A).repeat(10_000) + "4", "sqrt(".repeat(10_000) + "4" + ")".repeat(10_000),
                "(-".repeat(10_000) + "1" + ")".repeat(10_000)}) {
            ExpressionException refusal = assertThrows(ExpressionException.class, () -> UNBOUNDED_LENGTH.parse(line));
            assertEquals(ExpressionError.TOO_DEEP, refusal.error(), line.substring(0, 20));
        }
    }

    @Test
    void aOneMegabyteLineIsRefusedAtOnce() {
        String line = "1+".repeat(512 * 1024);
        long best = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 3; attempt++) {
            long start = System.nanoTime();
            ExpressionException refusal = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine(line));
            best = Math.min(best, System.nanoTime() - start);
            assertEquals(ExpressionError.LINE_TOO_LONG, refusal.error());
            assertEquals(new Span(1000, line.length()), refusal.span());
        }
        System.out.println("1 MB line refused in " + best / 1000 + " microseconds (best of 3)");
        TimingBudget.assertWithin("a 1 MB line refused, best of 3", best, 5_000_000L, TimeUnit.NANOSECONDS);
    }

    @Test
    void aLongSumIsRefusedByTheNodeCountNotTheLength() {
        String line = "1+".repeat(5_000) + "1";

        ExpressionException refusal = assertThrows(ExpressionException.class, () -> UNBOUNDED_LENGTH.parse(line));

        assertEquals(ExpressionError.TOO_MANY_NODES, refusal.error());
    }

    @Test
    void parsingAtTheDepthCapAndWalkingVeryDeepTreesFitASmallStack() throws Throwable {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                parseAtTheDepthCap();
                evaluateAndPrintVeryDeepTrees();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "small-stack", 512 * 1024);
        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw failure.get();
        }
    }

    private static void parseAtTheDepthCap() throws ExpressionException {
        int depth = ExpressionLimits.MAX_SAFE_DEPTH;
        ExpressionParser deepest = new ExpressionParser(new ExpressionLimits(Integer.MAX_VALUE, depth, 10_000));
        String root = String.valueOf((char) 0x221A);
        List<IntFunction<String>> nestings = List.of(
                n -> "(".repeat(n) + "1" + ")".repeat(n),
                n -> "-".repeat(n) + "1",
                n -> "1^".repeat(n) + "1",
                n -> root.repeat(n) + "4",
                n -> "sqrt(".repeat(n) + "4" + ")".repeat(n),
                n -> "(-".repeat(n / 2) + "-".repeat(n % 2) + "1" + ")".repeat(n / 2));
        for (IntFunction<String> nesting : nestings) {
            Node tree = deepest.parse(nesting.apply(depth)).tree().orElseThrow();
            Evaluator.evaluate(tree, TableResolver.empty());
            CanonicalPrinter.print(tree);
            ExpressionException refusal = assertThrows(ExpressionException.class,
                    () -> deepest.parse(nesting.apply(depth + 1)));
            assertEquals(ExpressionError.TOO_DEEP, refusal.error(), nesting.apply(3));
        }
    }

    // Trees built by hand, far deeper than any parsed tree: the evaluator and the printer use an explicit stack.
    private static void evaluateAndPrintVeryDeepTrees() throws ExpressionException {
        int depth = 100_000;
        Node one = new Node.NumberLiteral(Rational.ONE, Span.NONE);
        Node signs = one;
        Node sums = one;
        Node powers = one;
        for (int level = 1; level < depth; level++) {
            signs = new Node.Unary(Node.Sign.MINUS, signs, Span.NONE);
            sums = new Node.Binary(Node.Operator.ADD, sums, one, Span.NONE);
            powers = new Node.Binary(Node.Operator.POWER, one, powers, Span.NONE);
        }
        assertEquals(ExpressionTestSupport.exact("-1"), Evaluator.evaluate(signs, TableResolver.empty()));
        assertEquals(ExpressionTestSupport.exact("100000"), Evaluator.evaluate(sums, TableResolver.empty()));
        assertEquals(ExpressionTestSupport.exact("1"), Evaluator.evaluate(powers, TableResolver.empty()));
        assertEquals("-".repeat(depth - 1) + "1", CanonicalPrinter.print(signs));
        assertEquals("1" + " + 1".repeat(depth - 1), CanonicalPrinter.print(sums));
        assertEquals("1^".repeat(depth - 1) + "1", CanonicalPrinter.print(powers));
    }

    @Test
    void limitsThatWouldRiskTheStackAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(1000, ExpressionLimits.MAX_SAFE_DEPTH + 1, 500));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(1000, 0, 500));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(1000, 100, 0));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(0, 100, 500));
        assertEquals(new ExpressionLimits(1000, 100, 500), ExpressionLimits.DEFAULT);
    }
}
