import calc.number.CertifiedValue;
import calc.number.NumericException;
import calc.number.NumericLimits;
import calc.number.Rational;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Measures what justifies the values of {@code calc.number.NumericLimits}: first the raw BigInteger cost of a rational
 * sum at several candidate component sizes, then the slowest operations and the refusals of the numeric core at the
 * configured limits. Prints Markdown tables (milliseconds). See README.md next to this file.
 */
public final class NumericLimitsBenchmark {
    private static final int WARM_RUNS = 30;

    interface Action {
        Object run() throws NumericException;
    }

    public static void main(String[] args) throws NumericException {
        Random random = new Random(20261026);
        candidateSizes(random);
        coreOperations(random);
    }

    // The sum of two rationals with N-bit components: 3 products, then a gcd of 2N-bit numbers, which dominates.
    private static void candidateSizes(Random random) {
        System.out.println("| Component bits | p/q + r/s (gcd of 2N bits), best of " + WARM_RUNS + " |");
        System.out.println("|---|---|");
        for (int bits : new int[] {2048, 4096, 8192, 16384, 32768}) {
            BigInteger p = odd(bits, random);
            BigInteger q = odd(bits, random);
            BigInteger r = odd(bits, random);
            BigInteger s = odd(bits, random);
            Action sum = () -> {
                BigInteger numerator = p.multiply(s).add(r.multiply(q));
                BigInteger denominator = q.multiply(s);
                BigInteger gcd = numerator.gcd(denominator);
                return numerator.divide(gcd).add(denominator.divide(gcd));
            };
            measure(sum, 5);
            System.out.printf(Locale.ROOT, "| %d | %.3f |%n", bits, measure(sum, WARM_RUNS));
        }
        System.out.println();
    }

    private static void coreOperations(Random random) throws NumericException {
        int maxBits = NumericLimits.MAX_COMPONENT_BITS;
        BigInteger twoPower4095 = BigInteger.ONE.shiftLeft(maxBits - 1);
        Rational a = Rational.of(odd(maxBits, random), odd(maxBits, random));
        Rational b = Rational.of(odd(maxBits, random), odd(maxBits, random));
        CertifiedValue exactA = CertifiedValue.exact(a);
        CertifiedValue exactB = CertifiedValue.exact(b);
        CertifiedValue wideA = CertifiedValue.between(a, Rational.of(odd(maxBits, random)));
        CertifiedValue wideB = CertifiedValue.between(b.negate(), b);
        Rational nonSquare = Rational.of(odd(maxBits, random), odd(maxBits, random));
        Rational dyadic = Rational.of(twoPower4095.shiftLeft(1).subtract(BigInteger.ONE).negate(), twoPower4095);
        String longestDecimal = dyadic.toExactDecimal().orElseThrow().toPlainString();
        String fraction = a.negate().toString();
        CertifiedValue nearOne = CertifiedValue.between(Rational.ONE,
                Rational.ONE.add(Rational.of(BigInteger.ONE, BigInteger.ONE.shiftLeft(100))));
        Rational twoPower999 = Rational.of(2).pow(999);
        CertifiedValue nearTwoPower999 = CertifiedValue.between(twoPower999, twoPower999.add(Rational.ONE));
        Rational maximal = Rational.of(twoPower4095.shiftLeft(1).subtract(BigInteger.ONE));
        String hundredThousandDigits = "7".repeat(100_000);
        String longestDigits = "7".repeat(NumericLimits.MAX_LITERAL_LENGTH);
        String tinyDecimal = "0," + "0".repeat(4095) + "1";

        Map<String, Action> cases = new LinkedHashMap<>();
        cases.put("Rational a + b, four 4096-bit components", () -> a.add(b));
        cases.put("Rational a * b", () -> a.multiply(b));
        cases.put("Rational a / b", () -> a.divide(b));
        cases.put("Rational a compared with b", () -> a.compareTo(b));
        cases.put("exact a + b (refused: 8192 bits)", () -> exactA.add(exactB));
        cases.put("exact a * b (refused)", () -> exactA.multiply(exactB));
        cases.put("approximate [a, c] * [-b, b]", () -> wideA.multiply(wideB));
        cases.put("approximate [-b, b] / [a, c]", () -> wideB.divide(wideA));
        cases.put("sqrt of a 4096-bit non-square at 120 bits", () -> CertifiedValue.exact(nonSquare).sqrt(NumericLimits.MAX_PRECISION_BITS));
        cases.put("sqrt of [a, c]", () -> wideA.sqrt());
        cases.put("parse of the longest decimal (" + longestDecimal.length() + " chars)", () -> Rational.parse(longestDecimal));
        cases.put("parse of a 4096-bit fraction (" + fraction.length() + " chars)", () -> Rational.parse(fraction));
        cases.put("exact decimal of -(2^4096 - 1) / 2^4095", () -> dyadic.toExactDecimal());
        cases.put("3 ^ 2584 (4096 bits)", () -> Rational.of(3).pow(2584));
        cases.put("[1, 1 + 2^-100] ^ 4096", () -> nearOne.pow(NumericLimits.MAX_POWER_EXPONENT));
        cases.put("refused: 2^999 ^ 999", () -> twoPower999.pow(999));
        cases.put("refused: [2^999, 2^999 + 1] ^ 999", () -> nearTwoPower999.pow(999));
        cases.put("refused: literal of 100 000 digits", () -> Rational.parse(hundredThousandDigits));
        cases.put("refused: literal of 4100 digits", () -> Rational.parse(longestDigits));
        cases.put("refused: 1e9999", () -> Rational.parse("1e9999"));
        cases.put("refused: 0,(4095 zeros)1", () -> Rational.parse(tinyDecimal));
        cases.put("refused: exact (2^4096 - 1)^2", () -> CertifiedValue.exact(maximal).multiply(CertifiedValue.exact(maximal)));

        System.out.println("| Operation at the limits | first call | best of " + WARM_RUNS + " | outcome |");
        System.out.println("|---|---|---|---|");
        for (Map.Entry<String, Action> operation : cases.entrySet()) {
            double first = measure(operation.getValue(), 1);
            double best = measure(operation.getValue(), WARM_RUNS);
            System.out.printf(Locale.ROOT, "| %s | %.3f | %.3f | %s |%n", operation.getKey(), first, best, outcome(operation.getValue()));
        }
    }

    private static double measure(Action action, int runs) {
        long best = Long.MAX_VALUE;
        for (int run = 0; run < runs; run++) {
            long start = System.nanoTime();
            try {
                action.run();
            } catch (NumericException refused) {
                // a refusal is measured like a result
            }
            best = Math.min(best, System.nanoTime() - start);
        }
        return best / 1e6;
    }

    private static String outcome(Action action) {
        try {
            action.run();
            return "computed";
        } catch (NumericException refused) {
            return refused.error().name();
        }
    }

    private static BigInteger odd(int bits, Random random) {
        return new BigInteger(bits, random).setBit(bits - 1).setBit(0);
    }
}
