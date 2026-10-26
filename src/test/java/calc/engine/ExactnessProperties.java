package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import calc.number.Rational;
import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import calc.testing.DecimalGenerators.FractionText;
import calc.testing.DisplayTexts;
import calc.testing.FractionOracle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The guarantees of the exact engine (E06), which replace the 12-decimal rounding policy of version 1 (the former
 * {@code RoundingPolicyProperties}): a quotient is exact, so multiplying it back gives the dividend exactly, whether the
 * quotient is reused as a value or as its canonical text; a root is exact when it can be, and otherwise its interval
 * contains the true root, checked against a 200-digit oracle; the digits shown for a root are the rounding of the
 * true root.
 */
@Tag("property")
class ExactnessProperties {
    private static final MathContext ORACLE = new MathContext(200, RoundingMode.DOWN);

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<String> dividends() {
        return Arbitraries.oneOf(DecimalGenerators.operands().map(DecimalText::text), DecimalGenerators.fractions().map(FractionText::text));
    }

    @Provide
    Arbitrary<String> divisors() {
        return Arbitraries.oneOf(
                DecimalGenerators.nonZero(DecimalGenerators.operands()).map(DecimalText::text),
                DecimalGenerators.fractions().filter(fraction -> fraction.value().signum() != 0).map(FractionText::text));
    }

    @Provide
    Arbitrary<DecimalText> nonNegativeOperands() {
        return DecimalGenerators.operands().filter(operand -> operand.value().signum() >= 0);
    }

    @Provide
    Arbitrary<DecimalText> operands() {
        return DecimalGenerators.operands();
    }

    @Property
    void aQuotientTimesTheDivisorGivesBackTheDividend(@ForAll("dividends") String a, @ForAll("divisors") String b)
            throws CalculationException {
        CertifiedValue dividend = engine.calculate(Operation.ADDITION, a, "0").getResult();
        CalculationRecord quotient = engine.calculate(Operation.DIVISION, a, b);
        assertTrue(quotient.isExact(), () -> a + " / " + b + " is exact");

        CalculationRecord byValue = engine.calculate(Operation.MULTIPLICATION, Operand.known(quotient.getResult()), Operand.typed(b));
        assertEquals(dividend, byValue.getResult(), () -> "(" + a + " / " + b + ") * " + b + ", quotient reused as a value");

        CalculationRecord byText = engine.calculate(Operation.MULTIPLICATION, quotient.getReusableValue(), b);
        assertEquals(dividend, byText.getResult(), () -> "(" + a + " / " + b + ") * " + b + ", quotient typed as " + quotient.getReusableValue());
    }

    /** lower <= sqrt(x) <= upper: exactly (squares of the bounds) and against a 200-digit oracle. */
    @Property
    void theIntervalOfARootContainsTheTrueRoot(@ForAll("nonNegativeOperands") DecimalText x) throws CalculationException {
        CalculationRecord record = engine.calculate(Operation.ROOT, x.text(), "");
        Rational lower = record.getResult().lower();
        Rational upper = record.getResult().upper();
        FractionOracle value = FractionOracle.of(x.value());
        assertTrue(square(lower).compareTo(value) <= 0, () -> "lower bound too high for the root of " + x);
        assertTrue(value.compareTo(square(upper)) <= 0, () -> "upper bound too low for the root of " + x);

        // sqrt(x) lies in [root, root + ulp]: that interval must meet [lower, upper].
        BigDecimal root = x.value().sqrt(ORACLE);
        BigDecimal above = root.add(root.ulp());
        assertTrue(FractionOracle.of(root).compareTo(oracle(upper)) <= 0, () -> "the oracle root of " + x + " is above " + upper);
        assertTrue(oracle(lower).compareTo(FractionOracle.of(above)) <= 0, () -> "the oracle root of " + x + " is below " + lower);

        if (!record.isExact()) {
            // The digits shown are the rounding of the true root (the oracle has 200 digits, far beyond the 12 shown).
            BigDecimal shown = DisplayTexts.decimal(record.getFormattedResult());
            assertEquals(0, root.setScale(shown.scale(), RoundingMode.HALF_UP).compareTo(shown),
                    () -> record.getFormattedResult() + " is not the rounding of " + root);
        }
    }

    @Property
    void theRootOfAnExactSquareIsExact(@ForAll("operands") DecimalText r) throws CalculationException {
        BigDecimal square = r.value().multiply(r.value());
        CalculationRecord record = engine.calculate(Operation.ROOT, square.toPlainString(), "");
        assertTrue(record.isExact(), () -> "root(" + square + ") is exact");
        assertTrue(FractionOracle.of(r.value().abs()).hasValue(record.getResult().lower().numerator(), record.getResult().lower().denominator()),
                () -> "root(" + square + ") should be " + r.value().abs());
    }

    @Property
    void theRootOfANonSquareIsApproximateAndNeverZero(@ForAll("nonNegativeOperands") DecimalText x) throws CalculationException {
        CalculationRecord record = engine.calculate(Operation.ROOT, x.text(), "");
        if (x.value().signum() > 0) {
            assertFalse(record.getFormattedResult().equals("0") || record.getFormattedResult().equals("≈ 0"),
                    () -> "the root of " + x + " is shown as " + record.getFormattedResult());
        }
        assertEquals(record.isExact(), record.getFormatted().errorBound().isEmpty(), "An error bound comes with every approximate root.");
    }

    private static FractionOracle square(Rational value) {
        return new FractionOracle(value.numerator().multiply(value.numerator()), value.denominator().multiply(value.denominator()));
    }

    private static FractionOracle oracle(Rational value) {
        return new FractionOracle(value.numerator(), value.denominator());
    }
}
