package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.DecimalGenerators;
import calc.testing.TextGenerators;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.BeforeAll;
import net.jqwik.api.Tag;

/**
 * Fuzzing of the operand parser through the public engine: whatever the text, the answer is a result or the
 * engine's own exception, never another exception, and it comes in well under 100 ms.
 */
@Tag("robustness")
class InputFuzzProperties {
    private static final long BUDGET_NANOS = 100_000_000L;

    private final CalculatorEngine engine = new CalculatorEngine();

    @BeforeAll
    static void warmUp() {
        // The first calculations of a fresh JVM are slow (class loading, interpreter); they are not what is measured.
        CalculatorEngine warm = new CalculatorEngine();
        for (Operation operation : Operation.values()) {
            for (int round = 0; round < 50; round++) {
                try {
                    warm.calculate(operation, "12,5", "3");
                    warm.calculate(operation, "abc", "3");
                } catch (CalculationException expected) {
                    // refusals are part of the warm-up
                }
            }
        }
    }

    @Provide
    Arbitrary<String> anyInput() {
        return TextGenerators.anyInput();
    }

    @Provide
    Arbitrary<String> overlong() {
        return TextGenerators.overlong();
    }

    @Provide
    Arbitrary<String> invalid() {
        return DecimalGenerators.invalidDecimals();
    }

    @Provide
    Arbitrary<String> blanks() {
        return DecimalGenerators.blanks().injectNull(0.1);
    }

    @Provide
    Arbitrary<Operation> operations() {
        return Arbitraries.of(Operation.class);
    }

    @Property
    void anyTextAsFirstOperandGivesAResultOrABusinessError(@ForAll("operations") Operation operation, @ForAll("anyInput") String text) {
        answerWithinBudget(operation, text, "3");
    }

    @Property
    void anyTextAsSecondOperandGivesAResultOrABusinessError(@ForAll("operations") Operation operation, @ForAll("anyInput") String text) {
        answerWithinBudget(operation, "7", text);
    }

    @Property
    void anyTextInBothOperandsGivesAResultOrABusinessError(
            @ForAll("operations") Operation operation, @ForAll("anyInput") String first, @ForAll("anyInput") String second) {
        answerWithinBudget(operation, first, second);
    }

    @Property(tries = 200)
    void textsFarLongerThanTheBoundAreRefusedQuickly(@ForAll("operations") Operation operation, @ForAll("overlong") String text) {
        CalculationException refusal = answerWithinBudget(operation, text, "2");
        assertNotNull(refusal, "A text over " + CalculationLimits.MAX_INPUT_LENGTH + " characters cannot be accepted.");
        assertEquals(InputField.FIRST, refusal.getField());
        assertTrue(refusal.getMessage().contains("trop long") || refusal.getMessage().contains("invalide"), refusal.getMessage());
    }

    @Property
    void textsThatAreNotNumbersAreRefusedAndNameTheField(@ForAll("operations") Operation operation, @ForAll("invalid") String text) {
        CalculationException refusal = answerWithinBudget(operation, text, "3");
        assertNotNull(refusal, "Not a number: " + text);
        assertEquals(InputField.FIRST, refusal.getField());
        assertEquals("Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.", refusal.getMessage());
    }

    @Property
    void blankTextsAreReportedAsMissing(@ForAll("operations") Operation operation, @ForAll("blanks") String text) {
        CalculationException firstMissing = answerWithinBudget(operation, text, "3");
        assertNotNull(firstMissing);
        assertEquals(InputField.FIRST, firstMissing.getField());
        assertEquals("Saisissez le premier nombre.", firstMissing.getMessage());
        if (operation.requiresSecondOperand()) {
            CalculationException secondMissing = answerWithinBudget(operation, "3", text);
            assertNotNull(secondMissing);
            assertEquals(InputField.SECOND, secondMissing.getField());
            assertEquals("Saisissez le second nombre.", secondMissing.getMessage());
        }
    }

    /** Runs the calculation, requires the answer within the budget, returns the refusal if there was one. */
    private CalculationException answerWithinBudget(Operation operation, String first, String second) {
        CalculationException refusal = null;
        long best = Long.MAX_VALUE;
        // Best of two: a garbage collection or a scheduling pause must not fail a property that is not about speed.
        for (int attempt = 0; attempt < 2 && best >= BUDGET_NANOS; attempt++) {
            long start = System.nanoTime();
            try {
                CalculationRecord record = engine.calculate(operation, first, second);
                assertNotNull(record, "A successful calculation returns a record.");
                refusal = null;
            } catch (CalculationException exception) {
                assertNotNull(exception.getMessage(), "A refusal always says why.");
                assertNotNull(exception.getField(), "A refusal always names a field (or NONE).");
                refusal = exception;
            }
            best = Math.min(best, System.nanoTime() - start);
        }
        assertTrue(best < BUDGET_NANOS, "Took " + best / 1_000_000 + " ms for " + operation + " " + abbreviate(first) + " " + abbreviate(second));
        return refusal;
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 40 ? "'" + text + "'" : "'" + text.substring(0, 20) + "...' (" + text.length() + " characters)";
    }
}
