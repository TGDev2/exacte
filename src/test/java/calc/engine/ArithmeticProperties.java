package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.ArithmeticOracle;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import calc.testing.ResultValues;
import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The exact operations (addition, subtraction, multiplication and integer powers) against an independent oracle, and
 * the algebraic laws that follow. The oracle ({@link ArithmeticOracle}) shares no code with the engine: it has its own
 * reading of the text and its own display rules (Auto text and canonical text, docs/adr/0004-regles-affichage.md).
 */
@Tag("property")
class ArithmeticProperties {
    private static final String TOO_BIG = "Le résultat dépasse";

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<DecimalText> operands() {
        return DecimalGenerators.operands();
    }

    @Provide
    Arbitrary<DecimalText> midSizeOperands() {
        return DecimalGenerators.decimals(400);
    }

    @Provide
    Arbitrary<DecimalText> longOperands() {
        return DecimalGenerators.decimals();
    }

    @Provide
    Arbitrary<DecimalText> typedOperands() {
        return Arbitraries.oneOf(DecimalGenerators.grouped(), DecimalGenerators.padded(DecimalGenerators.operands()));
    }

    @Provide
    Arbitrary<DecimalText> powerBases() {
        return DecimalGenerators.decimals(14);
    }

    @Provide
    Arbitrary<Operation> exactOperations() {
        return Arbitraries.of(Operation.ADDITION, Operation.SUBTRACTION, Operation.MULTIPLICATION);
    }

    @Provide
    Arbitrary<Integer> exponents() {
        return Arbitraries.integers().between(0, 20);
    }

    @Property
    void additionMatchesTheOracle(@ForAll("operands") DecimalText a, @ForAll("operands") DecimalText b) throws CalculationException {
        assertMatchesOracle(Operation.ADDITION, a, b, ArithmeticOracle.add(ArithmeticOracle.parse(a.text()), ArithmeticOracle.parse(b.text())), a.value().add(b.value()));
    }

    @Property
    void subtractionMatchesTheOracle(@ForAll("operands") DecimalText a, @ForAll("operands") DecimalText b) throws CalculationException {
        assertMatchesOracle(Operation.SUBTRACTION, a, b, ArithmeticOracle.subtract(ArithmeticOracle.parse(a.text()), ArithmeticOracle.parse(b.text())), a.value().subtract(b.value()));
    }

    @Property
    void multiplicationMatchesTheOracle(@ForAll("operands") DecimalText a, @ForAll("operands") DecimalText b) throws CalculationException {
        assertMatchesOracle(Operation.MULTIPLICATION, a, b, ArithmeticOracle.multiply(ArithmeticOracle.parse(a.text()), ArithmeticOracle.parse(b.text())), a.value().multiply(b.value()));
    }

    @Property
    void integerPowerMatchesTheOracle(@ForAll("powerBases") DecimalText base, @ForAll("exponents") int exponent) throws CalculationException {
        BigDecimal expected = ArithmeticOracle.power(ArithmeticOracle.parse(base.text()), exponent);
        CalculationRecord record = engine.calculate(Operation.POWER, base.text(), String.valueOf(exponent));
        assertEquals(0, expected.compareTo(ResultValues.exactDecimal(record)), () -> base + " ^ " + exponent);
        assertEquals(ArithmeticOracle.auto(expected), record.getFormattedResult());
    }

    @Property
    void typedOperandsAreAddedLikePlainOnes(@ForAll("typedOperands") DecimalText a, @ForAll("typedOperands") DecimalText b)
            throws CalculationException {
        assertMatchesOracle(Operation.ADDITION, a, b, ArithmeticOracle.add(ArithmeticOracle.parse(a.text()), ArithmeticOracle.parse(b.text())), a.value().add(b.value()));
        assertMatchesOracle(Operation.MULTIPLICATION, a, b, ArithmeticOracle.multiply(ArithmeticOracle.parse(a.text()), ArithmeticOracle.parse(b.text())), a.value().multiply(b.value()));
    }

    @Property
    void longOperandsGiveTheExactResultOrTheSizeRefusal(
            @ForAll("longOperands") DecimalText a, @ForAll("longOperands") DecimalText b, @ForAll("exactOperations") Operation operation) {
        BigDecimal exact = switch (operation) {
            case ADDITION -> a.value().add(b.value());
            case SUBTRACTION -> a.value().subtract(b.value());
            default -> a.value().multiply(b.value());
        };
        boolean fits = ArithmeticOracle.fitsTheCore(exact);
        try {
            CalculationRecord record = engine.calculate(operation, a.text(), b.text());
            assertTrue(fits, "A result beyond 4096 bits must be refused.");
            assertEquals(0, exact.compareTo(ResultValues.exactDecimal(record)), () -> a + " " + operation + " " + b);
            assertEquals(ArithmeticOracle.auto(exact), record.getFormattedResult());
        } catch (CalculationException exception) {
            assertEquals(InputField.NONE, exception.getField(), exception.getMessage());
            assertTrue(exception.getMessage().startsWith(TOO_BIG), exception.getMessage());
            assertFalse(fits, "The result fits 4096 bits and should have been accepted.");
        }
    }

    @Property
    void additionIsCommutative(@ForAll("longOperands") DecimalText a, @ForAll("longOperands") DecimalText b) {
        assertEquals(outcome(Operation.ADDITION, a, b), outcome(Operation.ADDITION, b, a));
    }

    @Property
    void multiplicationIsCommutative(@ForAll("longOperands") DecimalText a, @ForAll("longOperands") DecimalText b) {
        assertEquals(outcome(Operation.MULTIPLICATION, a, b), outcome(Operation.MULTIPLICATION, b, a));
    }

    @Property
    void addingThenSubtractingGivesBackTheStart(@ForAll("midSizeOperands") DecimalText a, @ForAll("midSizeOperands") DecimalText b)
            throws CalculationException {
        CalculationRecord sum = engine.calculate(Operation.ADDITION, a.text(), b.text());
        CalculationRecord back = engine.calculate(Operation.SUBTRACTION, sum.getReusableValue(), b.text());
        assertEquals(0, a.value().compareTo(ResultValues.exactDecimal(back)), () -> "(" + a + " + " + b + ") - " + b);
        assertEquals(ArithmeticOracle.auto(a.value()), back.getFormattedResult());
    }

    @Property
    void subtractingThenAddingGivesBackTheStart(@ForAll("midSizeOperands") DecimalText a, @ForAll("midSizeOperands") DecimalText b)
            throws CalculationException {
        CalculationRecord difference = engine.calculate(Operation.SUBTRACTION, a.text(), b.text());
        CalculationRecord back = engine.calculate(Operation.ADDITION, difference.getReusableValue(), b.text());
        assertEquals(0, a.value().compareTo(ResultValues.exactDecimal(back)), () -> "(" + a + " - " + b + ") + " + b);
    }

    private void assertMatchesOracle(Operation operation, DecimalText a, DecimalText b, BigDecimal oracle, BigDecimal exact)
            throws CalculationException {
        assertEquals(0, oracle.compareTo(exact), "The 200 digits of the oracle must be enough to be exact.");
        CalculationRecord record = engine.calculate(operation, a.text(), b.text());
        assertEquals(0, oracle.compareTo(ResultValues.exactDecimal(record)), () -> a + " " + operation + " " + b);
        assertEquals(ArithmeticOracle.auto(oracle), record.getFormattedResult(), "Display of the result");
        assertEquals(ArithmeticOracle.canonical(a.value()), record.getFirstValue(), "Canonical text of the first operand");
        assertEquals(ArithmeticOracle.canonical(b.value()), record.getSecondValue(), "Canonical text of the second operand");
    }

    private String outcome(Operation operation, DecimalText a, DecimalText b) {
        try {
            return "OK " + engine.calculate(operation, a.text(), b.text()).getFormattedResult();
        } catch (CalculationException exception) {
            return "REFUSED " + exception.getField() + " " + exception.getMessage();
        }
    }
}
