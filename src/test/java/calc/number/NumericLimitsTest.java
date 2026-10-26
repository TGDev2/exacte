package calc.number;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The limits of {@link NumericLimits}: each one is checked before the costly work, so a huge input is refused in a
 * fraction of a millisecond, and any operation within the limits finishes in under 10 ms. Durations are the best of
 * several runs after a warm-up, as in the engine's size-limit test; docs/adr/0003-representation-numerique.md reports
 * the measured values (cold and warm).
 */
class NumericLimitsTest {
    private static final long BUDGET_NANOS = Duration.ofMillis(10).toNanos();
    private static final int ATTEMPTS = 8;
    private static final int MAX_BITS = NumericLimits.MAX_COMPONENT_BITS;
    private static final BigInteger TWO_POWER_4095 = BigInteger.ONE.shiftLeft(MAX_BITS - 1);

    interface NumericAction {
        Object run() throws NumericException;
    }

    @BeforeAll
    static void warmUp() throws NumericException {
        Random random = new Random(1);
        for (int round = 0; round < 200; round++) {
            Rational x = Rational.of(new BigInteger(512, random), new BigInteger(512, random).add(BigInteger.ONE));
            CertifiedValue value = CertifiedValue.exact(x);
            value.add(value).multiply(value).divide(CertifiedValue.exact(Rational.of(3))).sqrt();
            Rational.parse(x.toString());
        }
    }

    @Test
    void hugeInputsAreRefusedBeforeAnyCostlyWork() throws NumericException {
        Rational twoPower999 = Rational.of(2).pow(999);
        CertifiedValue exactTwoPower999 = CertifiedValue.exact(twoPower999);
        CertifiedValue nearTwoPower999 = CertifiedValue.between(twoPower999, twoPower999.add(Rational.ONE));
        CertifiedValue exponent999 = CertifiedValue.exact(Rational.of(999));
        Rational maximal = Rational.of(TWO_POWER_4095.shiftLeft(1).subtract(BigInteger.ONE));
        String hundredThousandDigits = "7".repeat(100_000);
        String longestDigits = "7".repeat(NumericLimits.MAX_LITERAL_LENGTH);
        String tinyDecimal = "0," + "0".repeat(4095) + "1";
        Map<String, Refusal> cases = new LinkedHashMap<>();
        cases.put("Rational 2^999 ^ 999", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> twoPower999.pow(999)));
        cases.put("exact 2^999 ^ 999", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> exactTwoPower999.pow(exponent999)));
        cases.put("[2^999, 2^999 + 1] ^ 999", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> nearTwoPower999.pow(999)));
        cases.put("literal of 100 000 digits", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> Rational.parse(hundredThousandDigits)));
        cases.put("literal of 4100 digits", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED,
                () -> Rational.parse(longestDigits)));
        cases.put("1e9999", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> Rational.parse("1e9999")));
        cases.put("1e-9999", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> Rational.parse("1e-9999")));
        cases.put("0,(4095 zeros)1", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED, () -> Rational.parse(tinyDecimal)));
        cases.put("1e(20 digits)", new Refusal(NumericError.EXPONENT_OUT_OF_RANGE, () -> Rational.parse("1e99999999999999999999")));
        cases.put("x ^ 2147483647", new Refusal(NumericError.EXPONENT_OUT_OF_RANGE, () -> Rational.of(3).pow(Integer.MAX_VALUE)));
        cases.put("exact (2^4096 - 1)^2", new Refusal(NumericError.SIZE_LIMIT_EXCEEDED,
                () -> CertifiedValue.exact(maximal).multiply(CertifiedValue.exact(maximal))));

        for (Map.Entry<String, Refusal> refusal : cases.entrySet()) {
            long best = Long.MAX_VALUE;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                long start = System.nanoTime();
                NumericError error = errorOf(refusal.getValue().action());
                best = Math.min(best, System.nanoTime() - start);
                assertEquals(refusal.getValue().error(), error, refusal.getKey());
            }
            assertUnderBudget(refusal.getKey(), best);
        }
    }

    @Test
    void theSlowestOperationsWithinTheLimitsFinishUnderTenMilliseconds() throws NumericException {
        Random random = new Random(20261026);
        Rational a = Rational.of(maximalOdd(random), maximalOdd(random));
        Rational b = Rational.of(maximalOdd(random), maximalOdd(random));
        CertifiedValue exactA = CertifiedValue.exact(a);
        CertifiedValue exactB = CertifiedValue.exact(b);
        CertifiedValue wideA = CertifiedValue.between(a, Rational.of(maximalOdd(random)));
        CertifiedValue wideB = CertifiedValue.between(b.negate(), b);
        Rational nonSquare = Rational.of(maximalOdd(random), maximalOdd(random).add(BigInteger.TWO));
        Rational dyadic = Rational.of(TWO_POWER_4095.shiftLeft(1).subtract(BigInteger.ONE).negate(), TWO_POWER_4095);
        String longestDecimal = dyadic.toExactDecimal().orElseThrow().toPlainString();
        String longestFraction = a.negate().toString();
        CertifiedValue nearOne = CertifiedValue.between(Rational.ONE, Rational.ONE.add(Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(100))));

        assertTrue(!CertifiedValue.exact(nonSquare).sqrt(NumericLimits.MAX_PRECISION_BITS).isExact(), "the root case must not be exact");
        Map<String, NumericAction> cases = new LinkedHashMap<>();
        cases.put("Rational a + b (4096-bit components)", () -> a.add(b));
        cases.put("Rational a * b", () -> a.multiply(b));
        cases.put("Rational a / b", () -> a.divide(b));
        cases.put("Rational compare", () -> a.compareTo(b));
        cases.put("exact a + b", () -> exactA.add(exactB));
        cases.put("exact a * b", () -> exactA.multiply(exactB));
        cases.put("exact a / b", () -> exactA.divide(exactB));
        cases.put("approximate a * b", () -> wideA.multiply(wideB));
        cases.put("approximate a / b", () -> wideB.divide(wideA));
        cases.put("sqrt of a 4096-bit non-square, 120 bits", () -> CertifiedValue.exact(nonSquare).sqrt(NumericLimits.MAX_PRECISION_BITS));
        cases.put("sqrt of a 4096-bit interval", () -> wideA.sqrt());
        cases.put("parse of the longest decimal", () -> Rational.parse(longestDecimal));
        cases.put("parse of a 4096-bit fraction", () -> Rational.parse(longestFraction));
        cases.put("exact decimal of a 4096-bit dyadic", () -> dyadic.toExactDecimal());
        cases.put("text of a 4096-bit fraction", () -> a.toString());
        cases.put("3 ^ 2584 (4096 bits)", () -> Rational.of(3).pow(2584));
        cases.put("[1, 1 + 2^-100] ^ 4096", () -> nearOne.pow(NumericLimits.MAX_POWER_EXPONENT));

        for (Map.Entry<String, NumericAction> operation : cases.entrySet()) {
            long best = Long.MAX_VALUE;
            for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
                long start = System.nanoTime();
                errorOf(operation.getValue());
                best = Math.min(best, System.nanoTime() - start);
            }
            assertUnderBudget(operation.getKey(), best);
        }
    }

    @Test
    void theLongestTextsTheCoreWritesFitTheLiteralBoundAndAreReadBack() throws NumericException {
        BigInteger maximal = TWO_POWER_4095.shiftLeft(1).subtract(BigInteger.ONE);
        Rational longestDecimalValue = Rational.of(maximal.negate(), TWO_POWER_4095);
        Rational smallestDyadic = Rational.of(BigInteger.ONE.negate(), TWO_POWER_4095);
        Rational longestFractionValue = Rational.of(maximal.negate(), maximal.subtract(BigInteger.TWO));

        for (Rational value : new Rational[] {longestDecimalValue, smallestDyadic, longestFractionValue}) {
            assertTrue(value.bitLength() <= MAX_BITS);
            String canonical = value.toString();
            assertTrue(canonical.length() <= NumericLimits.MAX_LITERAL_LENGTH, () -> canonical.length() + " characters");
            assertEquals(value, Rational.parse(canonical));
            if (value.toExactDecimal().isPresent()) {
                String decimal = value.toExactDecimal().get().toPlainString();
                assertTrue(decimal.length() <= NumericLimits.MAX_LITERAL_LENGTH, () -> decimal.length() + " characters");
                assertEquals(value, Rational.parse(decimal));
            }
        }
        // The decimal text of -(2^4096 - 1) / 2^4095: sign, 4096 digits and the point.
        assertEquals(4098, longestDecimalValue.toExactDecimal().orElseThrow().toPlainString().length());
    }

    @Test
    void beyondTheLargestPowerExponentOnlyTrivialBasesCouldBeComputed() throws NumericException {
        int largest = NumericLimits.MAX_POWER_EXPONENT;

        // 2 and 1/2 have the smallest components of any base other than 0, 1 and -1: they already overflow.
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, errorOf(() -> Rational.of(2).pow(largest)));
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, errorOf(() -> Rational.of(1, 2).pow(largest)));
        assertEquals(NumericError.SIZE_LIMIT_EXCEEDED, errorOf(() -> Rational.of(-2).pow(-largest)));
        assertEquals(Rational.ONE, Rational.of(-1).pow(largest));
        assertEquals(Rational.ZERO, Rational.ZERO.pow(largest));
    }

    @Test
    void theApproximationPrecisionCoversTwentySignificantDigitsWithinTheBudget() {
        // 2^-80 < 10^-24: at least 20 significant decimal digits, enough to show 12 guaranteed ones.
        assertTrue(BigInteger.ONE.shiftLeft(NumericLimits.DEFAULT_PRECISION_BITS).compareTo(BigInteger.TEN.pow(20)) > 0);
        assertTrue(NumericLimits.DEFAULT_PRECISION_BITS <= NumericLimits.MAX_PRECISION_BITS);
        // A root bound has at most precision + 3 significant bits: it must fit the budget.
        assertTrue(NumericLimits.MAX_PRECISION_BITS + 3 <= NumericLimits.BOUND_SIGNIFICANT_BITS);
        // Every integer of 1233 decimal digits fits in 4096 bits, so does every 1000-digit result of the engine.
        assertTrue(BigInteger.TEN.pow(1233).subtract(BigInteger.ONE).bitLength() <= MAX_BITS);
        assertTrue(BigInteger.TEN.pow(1234).subtract(BigInteger.ONE).bitLength() > MAX_BITS);
    }

    private record Refusal(NumericError error, NumericAction action) {
    }

    private static NumericError errorOf(NumericAction action) {
        try {
            action.run();
            return null;
        } catch (NumericException refused) {
            return refused.error();
        }
    }

    private static void assertUnderBudget(String name, long bestNanos) {
        assertTrue(bestNanos < BUDGET_NANOS, () -> name + " took " + bestNanos / 1_000_000.0 + " ms");
    }

    // A 4096-bit odd number: the largest components allowed, never sharing a factor 2.
    private static BigInteger maximalOdd(Random random) {
        return new BigInteger(MAX_BITS, random).setBit(MAX_BITS - 1).setBit(0);
    }
}
