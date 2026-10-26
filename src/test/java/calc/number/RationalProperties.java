package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.FractionOracle;
import calc.testing.RationalGenerators;
import calc.testing.RationalGenerators.DecimalLiteral;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * {@link Rational} against the laws of a field, its canonical form after every operation, and an independent oracle:
 * {@link FractionOracle} (never-reduced BigInteger pairs) for arithmetic, {@link BigDecimal} for the reading of decimals.
 */
@Tag("property")
class RationalProperties {
    @Provide
    Arbitrary<Rational> rationals() {
        return RationalGenerators.rationals(256);
    }

    @Provide
    Arbitrary<FractionOracle> fractions() {
        return RationalGenerators.fractions(256);
    }

    @Provide
    Arbitrary<FractionOracle> smallFractions() {
        return RationalGenerators.fractions(40);
    }

    @Provide
    Arbitrary<Integer> exponents() {
        return Arbitraries.integers().between(-12, 12);
    }

    @Provide
    Arbitrary<DecimalLiteral> decimalLiterals() {
        return RationalGenerators.decimalLiterals();
    }

    @Property
    void additionAndMultiplicationAreAssociativeAndCommutative(@ForAll("rationals") Rational x, @ForAll("rationals") Rational y,
            @ForAll("rationals") Rational z) {
        assertEquals(x.add(y).add(z), x.add(y.add(z)));
        assertEquals(x.multiply(y).multiply(z), x.multiply(y.multiply(z)));
        assertEquals(x.add(y), y.add(x));
        assertEquals(x.multiply(y), y.multiply(x));
    }

    @Property
    void multiplicationDistributesOverAddition(@ForAll("rationals") Rational x, @ForAll("rationals") Rational y,
            @ForAll("rationals") Rational z) {
        assertEquals(x.multiply(y.add(z)), x.multiply(y).add(x.multiply(z)));
        assertEquals(x.subtract(y).multiply(z), x.multiply(z).subtract(y.multiply(z)));
    }

    @Property
    void neutralElementsAndInversesBehave(@ForAll("rationals") Rational x) throws NumericException {
        assertEquals(x, x.add(Rational.ZERO));
        assertEquals(x, x.multiply(Rational.ONE));
        assertEquals(Rational.ZERO, x.add(x.negate()));
        assertEquals(Rational.ZERO, x.subtract(x));
        assertEquals(x, x.negate().negate());
        if (x.signum() != 0) {
            assertEquals(Rational.ONE, x.multiply(x.reciprocal()));
            assertEquals(Rational.ONE, x.divide(x));
            assertEquals(x, x.reciprocal().reciprocal());
        }
    }

    @Property
    void everyOperationReturnsTheCanonicalForm(@ForAll("fractions") FractionOracle a, @ForAll("fractions") FractionOracle b,
            @ForAll("exponents") int exponent) throws NumericException {
        Rational x = Rational.of(a.numerator(), a.denominator());
        Rational y = Rational.of(b.numerator(), b.denominator());
        List<Rational> results = new ArrayList<>(List.of(x, y, x.add(y), x.subtract(y), x.multiply(y), x.negate(), x.abs(),
                Rational.parse(x.toString())));
        if (y.signum() != 0) {
            results.add(x.divide(y));
            results.add(y.reciprocal());
        }
        if (x.signum() != 0 || exponent >= 0) {
            Rational small = Rational.of(a.numerator().mod(BigInteger.valueOf(1000)), BigInteger.valueOf(7));
            results.add(small.pow(Math.abs(exponent)));
            if (x.bitLength() <= 300) {
                results.add(x.pow(exponent));
            }
        }
        for (Rational result : results) {
            assertCanonical(result);
        }
    }

    @Property
    void arithmeticMatchesTheOracle(@ForAll("fractions") FractionOracle a, @ForAll("fractions") FractionOracle b)
            throws NumericException {
        Rational x = Rational.of(a.numerator(), a.denominator());
        Rational y = Rational.of(b.numerator(), b.denominator());

        assertSameValue(a.add(b), x.add(y));
        assertSameValue(a.subtract(b), x.subtract(y));
        assertSameValue(a.multiply(b), x.multiply(y));
        assertSameValue(a.negate(), x.negate());
        if (b.signum() != 0) {
            assertSameValue(a.divide(b), x.divide(y));
        }
    }

    @Property
    void powersMatchTheOracle(@ForAll("smallFractions") FractionOracle a, @ForAll("exponents") int exponent) throws NumericException {
        Assume.that(a.signum() != 0 || exponent >= 0);
        assertSameValue(a.pow(exponent), Rational.of(a.numerator(), a.denominator()).pow(exponent));
    }

    @Property
    void comparisonAgreesWithEqualsHashCodeAndTheOracle(@ForAll("fractions") FractionOracle a, @ForAll("fractions") FractionOracle b)
            throws NumericException {
        Rational x = Rational.of(a.numerator(), a.denominator());
        Rational y = Rational.of(b.numerator(), b.denominator());
        Rational sameAsX = Rational.of(a.numerator().multiply(BigInteger.valueOf(-6)), a.denominator().multiply(BigInteger.valueOf(-6)));

        assertEquals(Integer.signum(a.compareTo(b)), Integer.signum(x.compareTo(y)));
        assertEquals(-Integer.signum(x.compareTo(y)), Integer.signum(y.compareTo(x)));
        assertEquals(x.compareTo(y) == 0, x.equals(y));
        assertEquals(x, sameAsX);
        assertEquals(x.hashCode(), sameAsX.hashCode());
        assertEquals(0, x.compareTo(sameAsX));
        assertEquals(a.signum(), x.signum());
    }

    @Property
    void integersAreRecognisedLikeTheOracle(@ForAll("fractions") FractionOracle a) throws NumericException {
        assertEquals(a.isInteger(), Rational.of(a.numerator(), a.denominator()).isInteger());
    }

    @Property
    void theExactDecimalExistsExactlyForPowersOfTwoAndFiveAndHasTheValue(@ForAll("smallFractions") FractionOracle a,
            @ForAll("decimalDenominators") BigInteger denominator) throws NumericException {
        FractionOracle fraction = new FractionOracle(a.numerator(), a.denominator().multiply(denominator));
        Optional<BigDecimal> decimal = Rational.of(fraction.numerator(), fraction.denominator()).toExactDecimal();

        assertEquals(fraction.isTerminatingDecimal(), decimal.isPresent());
        decimal.ifPresent(value -> assertSameValue(FractionOracle.of(value), fraction));
    }

    @Provide
    Arbitrary<BigInteger> decimalDenominators() {
        return Arbitraries.integers().between(0, 60).flatMap(twos -> Arbitraries.integers().between(0, 40)
                .map(fives -> BigInteger.ONE.shiftLeft(twos).multiply(BigInteger.valueOf(5).pow(fives))));
    }

    @Property
    void decimalsAreReadLikeBigDecimalReadsThem(@ForAll("decimalLiterals") DecimalLiteral literal) throws NumericException {
        Rational value = Rational.parse(literal.text());

        assertSameValue(FractionOracle.of(literal.value()), value);
        assertCanonical(value);
    }

    @Property
    void fractionsOfDecimalsAreReadLikeTheOracle(@ForAll("decimalLiterals") DecimalLiteral top,
            @ForAll("decimalLiterals") DecimalLiteral bottom) throws NumericException {
        Assume.that(bottom.value().signum() != 0);
        Rational value = Rational.parse(top.text() + "/" + bottom.text());

        assertSameValue(FractionOracle.of(top.value()).divide(FractionOracle.of(bottom.value())), value);
    }

    @Property
    void theCanonicalTextAndTheExactDecimalAreReadBack(@ForAll("rationals") Rational x) throws NumericException {
        assertEquals(x, Rational.parse(x.toString()));
        Optional<BigDecimal> decimal = x.toExactDecimal();
        if (decimal.isPresent()) {
            String text = decimal.get().toPlainString();
            assertEquals(x, Rational.parse(text));
            assertEquals(x, Rational.parse(text.replace('.', ',')));
        }
    }

    @Property
    void dyadicDecimalsAreReadBack(@ForAll("smallFractions") FractionOracle a, @ForAll("decimalDenominators") BigInteger denominator)
            throws NumericException {
        Rational x = Rational.of(a.numerator(), denominator);

        assertEquals(x, Rational.parse(x.toExactDecimal().orElseThrow().toPlainString()));
        assertEquals(x, Rational.parse(x.toExactDecimal().orElseThrow().toString()));
    }

    private static void assertSameValue(FractionOracle expected, Rational actual) {
        assertTrue(expected.hasValue(actual.numerator(), actual.denominator()),
                () -> "expected " + expected.numerator() + "/" + expected.denominator() + " but was " + actual);
    }

    private static void assertSameValue(FractionOracle expected, FractionOracle actual) {
        assertTrue(expected.hasValue(actual.numerator(), actual.denominator()),
                () -> "expected " + expected + " but was " + actual);
    }

    static void assertCanonical(Rational value) {
        assertTrue(value.denominator().signum() > 0, () -> "denominator not positive: " + value);
        assertEquals(BigInteger.ONE, value.numerator().gcd(value.denominator()), () -> "not reduced: " + value);
        if (value.signum() == 0) {
            assertEquals(BigInteger.ONE, value.denominator(), "zero must be 0/1");
        }
    }
}
