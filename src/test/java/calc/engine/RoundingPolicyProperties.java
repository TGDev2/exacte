package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.ArithmeticOracle;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import java.math.BigDecimal;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Properties that describe the CURRENT rounding policy of the decimal engine: a quotient or a root that is not exact
 * is rounded half up to 12 significant digits, with at least 12 decimals, so its error is at most 0.5e-12.
 *
 * <p><strong>To be revised in E06.</strong> The exact engine will replace this policy; these are the only properties
 * of the suite that depend on it. Everything else in the suite (arithmetic, errors, persistence, presenter) is stated
 * on the public behaviour and must survive the change unchanged. When E06 lands, rewrite the bounds below to the
 * guarantees of the new engine (certified intervals) or delete the properties that no longer apply.
 */
@Tag("property")
@Tag("rounding-policy")
class RoundingPolicyProperties {
    /** Half a unit of the 12th decimal: the largest rounding error of the current policy. */
    private static final BigDecimal HALF_UNIT = new BigDecimal("0.5E-12");

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<DecimalText> operands() {
        return DecimalGenerators.operands();
    }

    @Provide
    Arbitrary<DecimalText> nonZeroOperands() {
        return DecimalGenerators.nonZero(DecimalGenerators.operands());
    }

    @Provide
    Arbitrary<DecimalText> nonNegativeOperands() {
        return DecimalGenerators.operands().filter(operand -> operand.value().signum() >= 0);
    }

    /** |q * b - a| <= |b| * 0.5e-12: the quotient is the true quotient rounded to the 12th decimal. */
    @Property
    void divisionIsWithinHalfAUnitOfTheTwelfthDecimal(@ForAll("operands") DecimalText a, @ForAll("nonZeroOperands") DecimalText b)
            throws CalculationException {
        BigDecimal quotient = engine.calculate(Operation.DIVISION, a.text(), b.text()).getResultValue();
        BigDecimal error = quotient.multiply(b.value()).subtract(a.value()).abs();
        BigDecimal allowed = b.value().abs().multiply(HALF_UNIT);
        assertTrue(error.compareTo(allowed) <= 0, () -> a + " / " + b + " = " + quotient + ": |q*b - a| = " + error + " > " + allowed);
    }

    /** r >= 0 and (r - 0.5e-12)^2 <= x <= (r + 0.5e-12)^2 (the lower square is taken at 0 when r is smaller than the tolerance). */
    @Property
    void squareRootIsWithinHalfAUnitOfTheTwelfthDecimal(@ForAll("nonNegativeOperands") DecimalText x) throws CalculationException {
        BigDecimal root = engine.calculate(Operation.ROOT, x.text(), "").getResultValue();
        assertTrue(root.signum() >= 0, "The root is not negative: " + root);
        BigDecimal low = root.subtract(HALF_UNIT).max(BigDecimal.ZERO);
        BigDecimal high = root.add(HALF_UNIT);
        assertTrue(low.multiply(low).compareTo(x.value()) <= 0, () -> "root(" + x + ") = " + root + " is too big");
        assertTrue(x.value().compareTo(high.multiply(high)) <= 0, () -> "root(" + x + ") = " + root + " is too small");
    }

    @Property
    void exactQuotientsAreNotRounded(@ForAll("nonZeroOperands") DecimalText b, @ForAll("operands") DecimalText q) throws CalculationException {
        BigDecimal product = b.value().multiply(q.value());
        CalculationRecord record = engine.calculate(Operation.DIVISION, ArithmeticOracle.display(product), b.text());
        assertEquals(0, q.value().compareTo(record.getResultValue()), () -> product + " / " + b + " should be exactly " + q);
    }

    @Property
    void rootsOfExactSquaresAreNotRounded(@ForAll("operands") DecimalText r) throws CalculationException {
        BigDecimal square = r.value().multiply(r.value());
        CalculationRecord record = engine.calculate(Operation.ROOT, ArithmeticOracle.display(square), "");
        assertEquals(0, r.value().abs().compareTo(record.getResultValue()), () -> "root(" + square + ") should be exactly " + r.value().abs());
    }

    @Example
    void oneThirdIsRoundedToTwelveDecimals() throws CalculationException {
        assertEquals("0,333333333333", engine.calculate(Operation.DIVISION, "1", "3").getFormattedResult());
        assertEquals("0,666666666667", engine.calculate(Operation.DIVISION, "2", "3").getFormattedResult());
    }
}
