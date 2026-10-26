package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.testing.FractionOracle;
import calc.testing.RationalGenerators;
import calc.testing.RationalGenerators.Enclosure;
import java.math.BigInteger;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The guarantees of {@link CertifiedValue}. The fundamental one is the inclusion property: for a point {@code x} in an
 * interval {@code X} and {@code y} in {@code Y}, the exact {@code x op y} (computed with {@link Rational}) lies in
 * {@code X op Y}. The six inclusion properties run 2000 tries each (12 000 in total), whatever the default number of
 * tries. Every refusal is checked against the condition that justifies it.
 */
@Tag("property")
class CertifiedValueProperties {
    private static final int INCLUSION_TRIES = 2000;
    private static final int BUDGET = NumericLimits.BOUND_SIGNIFICANT_BITS;
    private static final int MAX_BITS = NumericLimits.MAX_COMPONENT_BITS;

    @Provide
    Arbitrary<Enclosure> enclosures() {
        return RationalGenerators.enclosures(160);
    }

    @Provide
    Arbitrary<Enclosure> powerBases() {
        return RationalGenerators.enclosures(40);
    }

    @Provide
    Arbitrary<Enclosure> rootArguments() {
        return RationalGenerators.enclosures(RationalGenerators.nonNegativeRationals(160), 160);
    }

    @Provide
    Arbitrary<Integer> powerExponents() {
        return Arbitraries.integers().between(-12, 12);
    }

    @Provide
    Arbitrary<Integer> precisions() {
        return Arbitraries.integers().between(1, NumericLimits.MAX_PRECISION_BITS);
    }

    @Property(tries = INCLUSION_TRIES)
    void additionIsInclusive(@ForAll("enclosures") Enclosure x, @ForAll("enclosures") Enclosure y) throws NumericException {
        CertifiedValue sum = x.interval().add(y.interval());

        assertEncloses(x.point().add(y.point()), sum);
        assertExactWhenOperandsAre(sum, x, y);
    }

    @Property(tries = INCLUSION_TRIES)
    void subtractionIsInclusive(@ForAll("enclosures") Enclosure x, @ForAll("enclosures") Enclosure y) throws NumericException {
        CertifiedValue difference = x.interval().subtract(y.interval());

        assertEncloses(x.point().subtract(y.point()), difference);
        assertExactWhenOperandsAre(difference, x, y);
        assertEquals(x.interval().add(y.interval().negate()), difference);
    }

    @Property(tries = INCLUSION_TRIES)
    void multiplicationIsInclusive(@ForAll("enclosures") Enclosure x, @ForAll("enclosures") Enclosure y) throws NumericException {
        CertifiedValue product = x.interval().multiply(y.interval());

        assertEncloses(x.point().multiply(y.point()), product);
        assertExactWhenOperandsAre(product, x, y);
    }

    @Property(tries = INCLUSION_TRIES)
    void divisionIsInclusive(@ForAll("enclosures") Enclosure x, @ForAll("enclosures") Enclosure y) throws NumericException {
        CertifiedValue divisor = y.interval();
        boolean exactZero = divisor.isExact() && divisor.lower().signum() == 0;
        boolean containsZero = divisor.lower().signum() <= 0 && divisor.upper().signum() >= 0;
        try {
            CertifiedValue quotient = x.interval().divide(divisor);

            assertFalse(containsZero, "a divisor that may be zero was accepted: " + divisor);
            assertEncloses(x.point().divide(y.point()), quotient);
            assertExactWhenOperandsAre(quotient, x, y);
        } catch (NumericException refused) {
            assertTrue(containsZero, () -> "refused " + refused.error() + " for divisor " + divisor);
            assertEquals(exactZero ? NumericError.DIVISION_BY_ZERO : NumericError.DIVISOR_MAY_BE_ZERO, refused.error());
        }
    }

    @Property(tries = INCLUSION_TRIES)
    void powerIsInclusive(@ForAll("powerBases") Enclosure x, @ForAll("powerExponents") int exponent) throws NumericException {
        CertifiedValue base = x.interval();
        boolean containsZero = base.lower().signum() <= 0 && base.upper().signum() >= 0;
        try {
            CertifiedValue power = base.pow(exponent);

            assertEncloses(x.point().pow(exponent), power);
            if (base.isExact()) {
                assertTrue(power.isExact(), () -> "an exact power became approximate: " + power);
            }
            assertEquals(power, base.pow(CertifiedValue.exact(Rational.of(exponent))));
        } catch (NumericException refused) {
            assertTrue(exponent < 0 && containsZero, () -> "refused " + refused.error() + " for " + base + " ^ " + exponent);
            assertEquals(base.isExact() ? NumericError.DIVISION_BY_ZERO : NumericError.DIVISOR_MAY_BE_ZERO, refused.error());
        }
    }

    @Property(tries = INCLUSION_TRIES)
    void squareRootIsInclusive(@ForAll("rootArguments") Enclosure x, @ForAll("precisions") int precision) {
        CertifiedValue argument = x.interval();
        try {
            CertifiedValue root = argument.sqrt(precision);

            assertTrue(argument.lower().signum() >= 0, "the root of a possibly negative value was accepted");
            assertTrue(root.lower().signum() >= 0);
            assertCertifiedRoot(x.point(), root);
            assertWithinLimits(root);
        } catch (NumericException refused) {
            assertTrue(argument.lower().signum() < 0, () -> "refused " + refused.error() + " for " + argument);
            assertEquals(argument.upper().signum() < 0 ? NumericError.ROOT_OF_NEGATIVE : NumericError.ROOT_OF_POSSIBLY_NEGATIVE,
                    refused.error());
        }
    }

    @Provide
    Arbitrary<Rational> rootRadicands() {
        return Arbitraries.oneOf(RationalGenerators.nonNegativeRationals(1000), RationalGenerators.nonNegativeRationals(24));
    }

    @Property
    void theRootOfAnExactValueIsCertifiedAtTheRequestedPrecision(@ForAll("rootRadicands") Rational x,
            @ForAll("precisions") int precision) throws NumericException {
        CertifiedValue root = CertifiedValue.exact(x).sqrt(precision);
        boolean perfectSquare = x.numerator().sqrtAndRemainder()[1].signum() == 0
                && x.denominator().sqrtAndRemainder()[1].signum() == 0;

        assertCertifiedRoot(x, root);
        assertEquals(perfectSquare, root.isExact());
        Rational width = root.upper().subtract(root.lower());
        Rational allowed = root.lower().multiply(RationalGenerators.inversePowerOfTwo(precision));
        assertTrue(width.compareTo(allowed) <= 0, () -> "width " + width + " above 2^-" + precision + " * " + root.lower());
    }

    @Property
    void perfectSquaresHaveExactRoots(@ForAll("rootRadicands") Rational r) throws NumericException {
        CertifiedValue root = CertifiedValue.exact(r.multiply(r)).sqrt();

        assertTrue(root.isExact());
        assertEquals(r, root.lower());
    }

    @Provide
    Arbitrary<Rational> anyMagnitudes() {
        // Components of up to twice the limit: the size of the exact intermediate results that are rounded.
        Arbitrary<BigInteger> numerators = RationalGenerators.integers(2 * MAX_BITS);
        Arbitrary<BigInteger> denominators = Arbitraries.oneOf(
                RationalGenerators.integers(2 * MAX_BITS).filter(value -> value.signum() != 0),
                Arbitraries.integers().between(0, 2 * MAX_BITS).map(BigInteger.ONE::shiftLeft));
        return Combinators.combine(numerators, denominators)
                .as((numerator, denominator) -> RationalGenerators.toRational(new FractionOracle(numerator, denominator)));
    }

    @Property
    void outwardRoundingKeepsTheValueInsideAndTheBoundWithinTheBudget(@ForAll("anyMagnitudes") Rational x) {
        long magnitude = x.signum() == 0 ? 0 : OutwardRounding.floorLog2(x);
        try {
            Rational down = OutwardRounding.down(x);
            Rational up = OutwardRounding.up(x);

            assertTrue(down.compareTo(x) <= 0 && x.compareTo(up) <= 0, () -> down + " <= " + x + " <= " + up);
            for (Rational bound : List.of(down, up)) {
                assertTrue(OutwardRounding.fitsBudget(bound), () -> "beyond the budget: " + bound);
                assertTrue(bound.bitLength() <= MAX_BITS, () -> "beyond the limit: " + bound.bitLength());
            }
            assertEquals(down, OutwardRounding.down(down));
            assertEquals(up, OutwardRounding.up(up));
            if (x.signum() != 0 && magnitude >= BUDGET - MAX_BITS) {
                // Each bound is less than one grid step 2^(e + 1 - BUDGET) <= |x| * 2^(1 - BUDGET) away from x.
                Rational allowed = x.abs().multiply(RationalGenerators.inversePowerOfTwo(BUDGET - 2));
                assertTrue(up.subtract(down).compareTo(allowed) <= 0, () -> "too wide around " + x);
            }
            if (OutwardRounding.fitsBudget(x) && x.bitLength() <= MAX_BITS) {
                assertEquals(x, down);
                assertEquals(x, up);
            }
        } catch (NumericException refused) {
            assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, refused.error());
            assertTrue(magnitude >= MAX_BITS - 1, () -> "refused a value of magnitude 2^" + magnitude);
        }
    }

    @Provide
    Arbitrary<List<FractionOracle>> exactOperands() {
        return RationalGenerators.fractions(20).list().ofMinSize(2).ofMaxSize(9);
    }

    @Provide
    Arbitrary<List<Character>> operators() {
        return Arbitraries.of('+', '-', '*', '/').list().ofMinSize(1).ofMaxSize(8);
    }

    @Property
    void everyCombinationOfExactValuesStaysExact(@ForAll("exactOperands") List<FractionOracle> operands,
            @ForAll("operators") List<Character> operators) throws NumericException {
        FractionOracle expected = operands.get(0);
        CertifiedValue value = CertifiedValue.exact(RationalGenerators.toRational(expected));
        for (int i = 1; i < operands.size(); i++) {
            FractionOracle next = operands.get(i);
            CertifiedValue operand = CertifiedValue.exact(RationalGenerators.toRational(next));
            char operator = operators.get((i - 1) % operators.size());
            if (operator == '/' && next.signum() == 0) {
                continue;
            }
            value = switch (operator) {
                case '+' -> value.add(operand);
                case '-' -> value.subtract(operand);
                case '*' -> value.multiply(operand);
                default -> value.divide(operand);
            };
            expected = switch (operator) {
                case '+' -> expected.add(next);
                case '-' -> expected.subtract(next);
                case '*' -> expected.multiply(next);
                default -> expected.divide(next);
            };
            assertTrue(value.isExact(), "an operation on exact values became approximate");
        }
        Rational result = value.lower();
        assertTrue(expected.hasValue(result.numerator(), result.denominator()), () -> "differs from the oracle: " + result);
    }

    @Provide
    Arbitrary<List<Enclosure>> chainOperands() {
        return RationalGenerators.enclosures(48).list().ofMinSize(2).ofMaxSize(10);
    }

    @Property
    void chainsOfOperationsStayInclusiveAndWithinTheBudget(@ForAll("chainOperands") List<Enclosure> operands,
            @ForAll("operators") List<Character> operators) {
        Rational point = operands.get(0).point();
        CertifiedValue value = operands.get(0).interval();
        for (int i = 1; i < operands.size(); i++) {
            Enclosure next = operands.get(i);
            char operator = operators.get((i - 1) % operators.size());
            try {
                value = switch (operator) {
                    case '+' -> value.add(next.interval());
                    case '-' -> value.subtract(next.interval());
                    case '*' -> value.multiply(next.interval());
                    default -> value.divide(next.interval());
                };
                point = switch (operator) {
                    case '+' -> point.add(next.point());
                    case '-' -> point.subtract(next.point());
                    case '*' -> point.multiply(next.point());
                    default -> point.divide(next.point());
                };
            } catch (NumericException refused) {
                assertEquals('/', operator, () -> "unexpected refusal " + refused.error());
                return;
            }
            assertEncloses(point, value);
        }
    }

    private static void assertEncloses(Rational truth, CertifiedValue value) {
        assertTrue(value.contains(truth), () -> value + " does not contain " + truth);
        assertWithinLimits(value);
        if (!value.isExact()) {
            assertTrue(OutwardRounding.fitsBudget(value.lower()) && OutwardRounding.fitsBudget(value.upper()),
                    () -> "a bound is beyond the budget: " + value);
        }
    }

    private static void assertWithinLimits(CertifiedValue value) {
        assertTrue(value.lower().bitLength() <= MAX_BITS && value.upper().bitLength() <= MAX_BITS, "beyond the component limit");
    }

    private static void assertExactWhenOperandsAre(CertifiedValue result, Enclosure x, Enclosure y) {
        if (x.interval().isExact() && y.interval().isExact() && !result.isExact()) {
            fail("an operation on exact values became approximate: " + result);
        }
    }

    // sqrt(x) in [lower, upper] for non-negative bounds, written without square roots.
    private static void assertCertifiedRoot(Rational x, CertifiedValue root) {
        Rational lower = root.lower();
        Rational upper = root.upper();
        assertTrue(lower.multiply(lower).compareTo(x) <= 0, () -> "lower^2 above x: " + root + " for " + x);
        assertTrue(x.compareTo(upper.multiply(upper)) <= 0, () -> "upper^2 below x: " + root + " for " + x);
        if (!root.isExact()) {
            assertTrue(OutwardRounding.fitsBudget(lower) && OutwardRounding.fitsBudget(upper), "a root bound is beyond the budget");
        }
    }
}
