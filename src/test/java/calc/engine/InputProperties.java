package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.ArithmeticOracle;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import calc.testing.DecimalGenerators.FractionText;
import calc.testing.ResultValues;
import calc.testing.RecordGenerators;
import calc.testing.RecordGenerators.Expression;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Assume;
import net.jqwik.api.Tag;

/**
 * What the calculator reads: every valid typed number (decimal, scientific notation, fraction) is accepted and means what
 * it says, and the reusable text of a result can be typed back into the calculator without changing its value.
 */
@Tag("property")
class InputProperties {
    private static final String TOO_BIG = "Le résultat dépasse";

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<DecimalText> typed() {
        return DecimalGenerators.typedDecimals();
    }

    @Provide
    Arbitrary<DecimalText> zeros() {
        return DecimalGenerators.zeros();
    }

    @Provide
    Arbitrary<DecimalText> atTheBound() {
        return DecimalGenerators.atTheBound();
    }

    @Provide
    Arbitrary<DecimalText> scientific() {
        return DecimalGenerators.scientific();
    }

    @Provide
    Arbitrary<FractionText> fractions() {
        return DecimalGenerators.fractions();
    }

    @Provide
    Arbitrary<Expression> expressions() {
        return RecordGenerators.expressions();
    }

    @Provide
    Arbitrary<Expression> longExpressions() {
        return RecordGenerators.longExpressions();
    }

    @Property
    void everyValidInputIsAcceptedAsFirstOperand(@ForAll("typed") DecimalText input) {
        assertAcceptedAs(input, Operation.ADDITION, input.text(), "0");
    }

    @Property
    void everyValidInputIsAcceptedAsSecondOperand(@ForAll("typed") DecimalText input) {
        assertAcceptedAs(input, Operation.ADDITION, "0", input.text());
    }

    @Property
    void everyValidInputIsAcceptedAsRadicand(@ForAll("typed") DecimalText input) {
        Assume.that(input.value().signum() >= 0);
        try {
            CalculationRecord record = engine.calculate(Operation.ROOT, input.text(), "");
            assertEquals(ArithmeticOracle.canonical(input.value()), record.getFirstValue(), "The operand is written in canonical form.");
        } catch (CalculationException exception) {
            assertEquals(InputField.NONE, exception.getField(), "A valid number is never blamed on the first field: " + exception.getMessage());
        }
    }

    @Property
    void everyZeroSpellingIsAccepted(@ForAll("zeros") DecimalText zero) throws CalculationException {
        CalculationRecord record = engine.calculate(Operation.ADDITION, zero.text(), zero.text());
        assertEquals("0", record.getFormattedResult(), "Every spelling of zero adds up to the same zero.");
        assertEquals("0", record.getFirstValue());
    }

    @Property
    void everyValidInputOfTheMaximumLengthIsAccepted(@ForAll("atTheBound") DecimalText input) {
        assertEquals(DecimalGenerators.MAX_LENGTH, input.text().length());
        assertAcceptedAs(input, Operation.ADDITION, input.text(), "0");
    }

    @Property
    void scientificNotationIsReadExactly(@ForAll("scientific") DecimalText input) throws CalculationException {
        CalculationRecord record = engine.calculate(Operation.ADDITION, input.text(), "0");
        assertEquals(0, input.value().compareTo(ResultValues.exactDecimal(record)), () -> "Value of " + input);
        assertEquals(ArithmeticOracle.canonical(input.value()), record.getFirstValue(), "Canonical text of " + input);
    }

    @Property
    void fractionsAreReadExactly(@ForAll("fractions") FractionText input) throws CalculationException {
        CalculationRecord record = engine.calculate(Operation.ADDITION, input.text(), "0");
        assertTrue(record.isExact(), () -> "A fraction is exact: " + input);
        assertTrue(input.value().hasValue(record.getResult().lower().numerator(), record.getResult().lower().denominator()),
                () -> "Value of " + input + ": " + record.getResult());
    }

    @Property
    void theGeneratorsAndTheOracleReadTheSameValue(@ForAll("typed") DecimalText input) {
        assertEquals(0, ArithmeticOracle.parse(input.text()).compareTo(input.value()), "Generator and oracle must agree on " + input);
    }

    @Property
    void theReusableValueOfAResultReadsBackToTheSameValue(@ForAll("expressions") Expression expression) throws CalculationException {
        CalculationRecord record = RecordGenerators.tryCalculate(expression).orElse(null);
        Assume.that(record != null);
        assertReusable(record);
    }

    @Property
    void theReusableValueOfAResultReadsBackForLongOperandsToo(@ForAll("longExpressions") Expression expression) throws CalculationException {
        CalculationRecord record = RecordGenerators.tryCalculate(expression).orElse(null);
        Assume.that(record != null);
        assertReusable(record);
    }

    private void assertReusable(CalculationRecord record) throws CalculationException {
        String reusable = record.getReusableValue();
        CalculationRecord again = engine.calculate(Operation.ADDITION, reusable, "0");
        assertTrue(again.isExact(), "A reusable text is read as an exact value: " + reusable);
        if (!record.isExact()) {
            assertTrue(record.getResult().contains(again.getResult().lower()), "The reusable text of " + record + " lies in its interval.");
            return;
        }
        assertEquals(record.getResult(), again.getResult(), "Reading back " + reusable);
        assertEquals(reusable, again.getReusableValue(), "The reusable text of a result is stable.");
        assertEquals(reusable, again.getFirstValue());
        assertEquals(record.getFormattedResult(), again.getFormattedResult());
    }

    /**
     * A valid number is accepted. The only refusal allowed is that the result of the operation is too large, which is
     * about the result, never about the field: it names no field.
     */
    private void assertAcceptedAs(DecimalText input, Operation operation, String first, String second) {
        boolean resultFits = ArithmeticOracle.fitsTheCore(input.value());
        try {
            CalculationRecord record = engine.calculate(operation, first, second);
            assertTrue(resultFits, "A result longer than the bound must be refused: " + input);
            assertEquals(0, input.value().compareTo(ResultValues.exactDecimal(record)), "Value of " + input);
            assertEquals(ArithmeticOracle.auto(input.value()), record.getFormattedResult());
            String shown = first.equals(input.text()) ? record.getFirstValue() : record.getSecondValue();
            assertEquals(ArithmeticOracle.canonical(input.value()), shown, "Canonical text of " + input);
        } catch (CalculationException exception) {
            assertEquals(InputField.NONE, exception.getField(), "Valid input refused for itself: " + exception.getMessage());
            assertTrue(exception.getMessage().startsWith(TOO_BIG), exception.getMessage());
            assertFalse(resultFits, "Refused although the result fits: " + input);
            assertNotNull(exception.getMessage());
        }
    }
}
