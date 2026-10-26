package calc.testing;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Independent reference for rational arithmetic, written for the tests: a pair of {@link BigInteger} that is never
 * reduced, with the schoolbook formulas, compared by cross multiplication. It imports nothing from production code, so
 * a mistake in {@code calc.number.Rational} (reduction, sign, cross reduction, comparison) cannot be repeated here.
 */
public record FractionOracle(BigInteger numerator, BigInteger denominator) {
    public FractionOracle {
        if (denominator.signum() == 0) {
            throw new IllegalArgumentException("zero denominator");
        }
    }

    public static FractionOracle of(long value) {
        return new FractionOracle(BigInteger.valueOf(value), BigInteger.ONE);
    }

    /** The exact value of a decimal, from {@link BigDecimal}'s own reading of the text. */
    public static FractionOracle of(BigDecimal value) {
        return value.scale() >= 0
                ? new FractionOracle(value.unscaledValue(), BigInteger.TEN.pow(value.scale()))
                : new FractionOracle(value.unscaledValue().multiply(BigInteger.TEN.pow(-value.scale())), BigInteger.ONE);
    }

    public FractionOracle add(FractionOracle other) {
        return new FractionOracle(numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
                denominator.multiply(other.denominator));
    }

    public FractionOracle subtract(FractionOracle other) {
        return add(other.negate());
    }

    public FractionOracle multiply(FractionOracle other) {
        return new FractionOracle(numerator.multiply(other.numerator), denominator.multiply(other.denominator));
    }

    public FractionOracle divide(FractionOracle other) {
        return new FractionOracle(numerator.multiply(other.denominator), denominator.multiply(other.numerator));
    }

    public FractionOracle negate() {
        return new FractionOracle(numerator.negate(), denominator);
    }

    /** Repeated multiplication, not {@code BigInteger.pow}. */
    public FractionOracle pow(int exponent) {
        FractionOracle base = exponent < 0 ? new FractionOracle(denominator, numerator) : this;
        FractionOracle result = of(1);
        for (int i = 0; i < Math.abs(exponent); i++) {
            result = result.multiply(base);
        }
        return result;
    }

    public int signum() {
        return numerator.signum() * denominator.signum();
    }

    public int compareTo(FractionOracle other) {
        return subtract(other).signum();
    }

    public boolean isInteger() {
        return numerator.mod(denominator.abs()).signum() == 0;
    }

    /** True when {@code p/q} has this value ({@code q != 0}). */
    public boolean hasValue(BigInteger p, BigInteger q) {
        return numerator.multiply(q).equals(p.multiply(denominator));
    }

    /** True when the decimal expansion is finite: the reduced denominator has no prime factor but 2 and 5. */
    public boolean isTerminatingDecimal() {
        BigInteger rest = denominator.abs().divide(numerator.gcd(denominator));
        BigInteger two = BigInteger.TWO;
        BigInteger five = BigInteger.valueOf(5);
        while (rest.mod(two).signum() == 0) {
            rest = rest.divide(two);
        }
        while (rest.mod(five).signum() == 0) {
            rest = rest.divide(five);
        }
        return rest.equals(BigInteger.ONE);
    }
}
