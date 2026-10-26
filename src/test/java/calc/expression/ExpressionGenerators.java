package calc.expression;

import calc.number.NumericException;
import calc.number.Rational;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/** Generators of syntax trees and of lines for the expression properties. */
final class ExpressionGenerators {
    /** Valid variable names: ASCII, mixed case, digits, underscore, a precomposed and a decomposed accent. */
    static final List<String> NAMES = List.of("prix", "taux", "x", "Prix", "_y2", "TVA_2026", "ann" + (char) 0x00E9 + "e",
            "cafe" + (char) 0x0301, "answer", "sqrt2");

    private ExpressionGenerators() {
    }

    /** Non-negative finite decimals, from 0 to large and tiny ones, as a number literal holds them. */
    static Arbitrary<Rational> literalValues() {
        Arbitrary<BigInteger> unscaled = Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.valueOf(1000))),
                Tuple.of(2, Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.TEN.pow(25))));
        Arbitrary<Integer> scale = Arbitraries.frequencyOf(
                Tuple.of(4, Arbitraries.integers().between(0, 3)),
                Tuple.of(1, Arbitraries.integers().between(-40, 40)));
        return Combinators.combine(unscaled, scale).as((digits, exponent) -> rational(new BigDecimal(digits, exponent)));
    }

    /** Small non-negative decimals: their powers and products stay cheap for the differential property. */
    static Arbitrary<Rational> smallLiteralValues() {
        return Combinators.combine(Arbitraries.integers().between(0, 30), Arbitraries.integers().between(0, 2))
                .as((digits, scale) -> rational(BigDecimal.valueOf(digits, scale)));
    }

    private static Rational rational(BigDecimal value) {
        try {
            return Rational.parse(value.toPlainString());
        } catch (NumericException exception) {
            throw new IllegalStateException(value.toPlainString(), exception);
        }
    }

    /** Any leaf: number, variable, {@code ans}, line reference. */
    static Arbitrary<Node> leaves() {
        return Arbitraries.frequencyOf(
                Tuple.of(5, literalValues().map(value -> new Node.NumberLiteral(value, Span.NONE))),
                Tuple.of(2, Arbitraries.of(NAMES).map(name -> new Node.Variable(name, Span.NONE))),
                Tuple.of(1, Arbitraries.just(new Node.Ans(Span.NONE))),
                Tuple.of(1, Arbitraries.integers().between(1, 999).map(line -> new Node.LineRef(line, Span.NONE))));
    }

    /** Trees of every shape up to {@code depth} levels above the leaves. */
    static Arbitrary<Node> trees(Arbitrary<Node> leaves, int depth) {
        if (depth == 0) {
            return leaves;
        }
        Arbitrary<Node> operand = Arbitraries.lazy(() -> trees(leaves, depth - 1));
        return Arbitraries.frequencyOf(
                Tuple.of(3, leaves),
                Tuple.of(2, Combinators.combine(Arbitraries.of(Node.Sign.class), operand)
                        .as((sign, node) -> new Node.Unary(sign, node, Span.NONE))),
                Tuple.of(5, Combinators.combine(Arbitraries.of(Node.Operator.class), operand, operand)
                        .as((operator, left, right) -> new Node.Binary(operator, left, right, Span.NONE))),
                Tuple.of(1, operand.map(node -> new Node.Sqrt(node, Span.NONE))));
    }

    /** A whole line: an expression tree, or an assignment of one. */
    static Arbitrary<Node> lines() {
        Arbitrary<Node> expression = trees(leaves(), 5);
        Arbitrary<Node> assignment = Combinators.combine(Arbitraries.of(NAMES), expression)
                .as((name, value) -> new Node.Assignment(name, value, Span.NONE));
        return Arbitraries.frequencyOf(Tuple.of(4, expression), Tuple.of(1, assignment));
    }
}
