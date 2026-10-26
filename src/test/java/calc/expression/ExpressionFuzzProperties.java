package calc.expression;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.TextGenerators;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeProperty;

/**
 * Fuzzing: for any line, reading and evaluating it gives a result or an {@link ExpressionException} with a code, a
 * French message and a span inside the line, never any other exception, in less than 50 ms (best of two after a
 * warm-up).
 */
@Tag("robustness")
class ExpressionFuzzProperties {
    private static final long BUDGET_NANOS = 50_000_000L;
    private static final Resolver NAMES = TableResolver.empty()
            .withVariable("x", ExpressionTestSupport.exact("2"))
            .withVariable("prix", ExpressionTestSupport.exact("1/3"))
            .withVariable("y", Resolution.inError())
            .withAns(ExpressionTestSupport.exact("-1.5"))
            .withLine(1, ExpressionTestSupport.exact("10"))
            .withLine(2, Resolution.invalid());
    /** Pieces of the language, glued without spaces or with any separator, to reach every rule and every error. */
    private static final List<String> PIECES = List.of("1", "0", "2,5", ".5", "1e3", "9e9999", "1e99999",
            "1" + (char) 0x00A0 + "000", "12" + (char) 0x202F + "34", "x", "prix", "y", "z", "ans", "ANS", "$1", "$2", "$3",
            "$0", "$?", "$", "(", ")", "+", "-", String.valueOf((char) 0x2212), "*", String.valueOf((char) 0x00D7), "/",
            String.valueOf((char) 0x00F7), "^", String.valueOf((char) 0x221A), "sqrt(", "racine", "=", "#", " ", ",", "@",
            String.valueOf((char) 0x00A0), "\t", "4096", "3^4000");

    @BeforeProperty
    void warmUp() throws Exception {
        for (int round = 0; round < 200; round++) {
            answer("(12,5 + 3) * 2 / 4 - sqrt(2) ^ 2 + x * $1");
            answer("1 +* 2");
        }
    }

    @Provide
    Arbitrary<String> lines() {
        Arbitrary<String> soup = Arbitraries.of(PIECES).list().ofMaxSize(60).map(pieces -> String.join("", pieces));
        Arbitrary<String> spacedSoup = Arbitraries.of(PIECES).list().ofMaxSize(40).map(pieces -> String.join(" ", pieces));
        Arbitrary<String> longSoup = Arbitraries.of(PIECES).list().ofMinSize(300).ofMaxSize(700)
                .map(pieces -> String.join("", pieces));
        Arbitrary<String> printedTrees = ExpressionGenerators.lines().map(CanonicalPrinter::print);
        return Arbitraries.frequencyOf(
                Tuple.of(3, TextGenerators.anyInput().filter(text -> text != null)),
                Tuple.of(4, soup),
                Tuple.of(2, spacedSoup),
                Tuple.of(1, longSoup),
                Tuple.of(2, printedTrees),
                Tuple.of(1, TextGenerators.overlong()));
    }

    @Property
    void anyLineGivesAResultOrATypedErrorQuickly(@ForAll("lines") String line) {
        long best = Long.MAX_VALUE;
        for (int attempt = 0; attempt < 2 && best >= BUDGET_NANOS; attempt++) {
            long start = System.nanoTime();
            ExpressionException refusal = answer(line);
            best = Math.min(best, System.nanoTime() - start);
            if (refusal != null) {
                assertNotNull(refusal.error());
                assertTrue(refusal.getMessage() != null && !refusal.getMessage().isBlank());
                Span span = refusal.span();
                assertTrue(span.end() <= line.length(), () -> span + " outside a line of " + line.length());
                assertTrue(refusal.error() != ExpressionError.NUMERIC || refusal.numericError().isPresent(),
                        refusal::getMessage);
            }
        }
        long took = best;
        assertTrue(took < BUDGET_NANOS, () -> "Took " + took / 1_000_000 + " ms for '" + abbreviate(line) + "'");
    }

    /** Reads and evaluates the line; returns the refusal if there was one. Any other exception fails the property. */
    private static ExpressionException answer(String line) {
        try {
            ParsedLine parsed = ExpressionParser.parseLine(line);
            if (parsed.tree().isPresent()) {
                assertNotNull(Evaluator.evaluate(parsed.tree().get(), NAMES));
            }
            return null;
        } catch (ExpressionException refusal) {
            return refusal;
        }
    }

    private static String abbreviate(String text) {
        return text.length() <= 60 ? text : text.substring(0, 60) + "... (" + text.length() + " characters)";
    }
}
