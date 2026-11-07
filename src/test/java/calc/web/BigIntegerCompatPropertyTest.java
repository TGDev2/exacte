package calc.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.web.compat.BigIntegerCompat;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The complement of {@code BigInteger.sqrtAndRemainder()}, absent from the class library of TeaVM, gives exactly what the
 * JDK gives. The JVM is the reference: the core calls this method in {@code OutwardRounding}.
 */
@Tag("property")
class BigIntegerCompatPropertyTest {
    private static final int MAX_BITS = 8192;
    private static final int TRIES = 1000;

    @Provide
    Arbitrary<BigInteger> upTo8192Bits() {
        Arbitrary<BigInteger> anyLength = exactLengths(0, MAX_BITS);
        // The root and the remainder change at a perfect square: its neighbours are the cases an off-by-one would miss.
        // The largest root has 4096 bits, so that root * root + 1 stays within 8192 bits.
        Arbitrary<BigInteger> aroundSquares = exactLengths(1, MAX_BITS / 2).flatMap(root -> Arbitraries.of(-1, 0, 1)
                .map(delta -> root.multiply(root).add(BigInteger.valueOf(delta))));
        return Arbitraries.oneOf(anyLength, aroundSquares);
    }

    @Provide
    Arbitrary<BigInteger> negativeUpTo8192Bits() {
        return exactLengths(1, MAX_BITS).map(BigInteger::negate);
    }

    @Property(tries = TRIES)
    void givesTheSameArrayAsTheJdkFromZeroTo8192Bits(@ForAll("upTo8192Bits") BigInteger x) {
        assertArrayEquals(x.sqrtAndRemainder(), BigIntegerCompat.sqrtAndRemainder(x));
    }

    @Property(tries = TRIES)
    void refusesEveryNegativeValueLikeTheJdk(@ForAll("negativeUpTo8192Bits") BigInteger x) {
        assertThrows(ArithmeticException.class, () -> BigIntegerCompat.sqrtAndRemainder(x));
    }

    @Example
    void givesTheSameArrayAsTheJdkOnTheBoundaries() {
        BigInteger largest = BigInteger.ONE.shiftLeft(MAX_BITS).subtract(BigInteger.ONE);
        BigInteger largestRoot = BigInteger.ONE.shiftLeft(MAX_BITS / 2).subtract(BigInteger.ONE);
        for (BigInteger x : List.of(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(3), BigInteger.valueOf(4),
                BigInteger.ONE.shiftLeft(MAX_BITS - 1), largest, largestRoot.multiply(largestRoot))) {
            assertArrayEquals(x.sqrtAndRemainder(), BigIntegerCompat.sqrtAndRemainder(x));
        }
    }

    @Example
    void refusesMinusOne() {
        assertThrows(ArithmeticException.class, () -> BigIntegerCompat.sqrtAndRemainder(BigInteger.ONE.negate()));
    }

    private static Arbitrary<BigInteger> exactLengths(int minBits, int maxBits) {
        return Arbitraries.integers().between(minBits, maxBits)
                .flatMap(bits -> Arbitraries.randomValue(random -> withBitLength(bits, random)));
    }

    private static BigInteger withBitLength(int bits, Random random) {
        return bits == 0 ? BigInteger.ZERO : new BigInteger(bits, random).setBit(bits - 1);
    }
}
