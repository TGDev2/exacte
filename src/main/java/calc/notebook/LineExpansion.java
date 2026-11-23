package calc.notebook;

import calc.expression.CanonicalPrinter;
import calc.expression.ExpressionException;
import calc.expression.ExpressionParser;
import calc.expression.Node;
import calc.expression.Span;
import calc.number.CertifiedValue;
import calc.number.Rational;
import java.util.Optional;

/**
 * The expansion of a line, one level deep: the right-hand side of the line, where each source whose value is exact is
 * replaced by that value, written by {@link CanonicalPrinter}. A source whose value is approximate keeps its writing. Read
 * back and evaluated without any variable or reference, the expansion of an exact line whose sources are exact gives
 * exactly the value of the line.
 */
public final class LineExpansion {
    private LineExpansion() {
    }

    /**
     * The expansion of the line {@code id} of {@code notebook}; empty for an empty line, a comment, a line in error and a
     * line without source.
     */
    public static Optional<String> expand(EvaluatedNotebook notebook, LineId id) {
        LineResult line = notebook.result(id);
        if (!line.hasValue() || line.sources().isEmpty()) {
            return Optional.empty();
        }
        Node tree;
        try {
            tree = ExpressionParser.parseLine(line.text()).node();
        } catch (ExpressionException refused) {
            throw new IllegalStateException("a line that has a value was read by the same parser: " + line.text(), refused);
        }
        Node value = tree instanceof Node.Assignment assignment ? assignment.value() : tree;
        return Optional.of(CanonicalPrinter.print(expanded(value, line, notebook)));
    }

    // A copy of the tree where each leaf whose source has an exact value becomes that value. The tree of a line that has a
    // value has at most ExpressionLimits.DEFAULT.maxNodes() nodes, which bounds the recursion.
    private static Node expanded(Node node, LineResult line, EvaluatedNotebook notebook) {
        return switch (node) {
            case Node.Variable variable -> valueOf(variable, Source.Kind.VARIABLE, line, notebook);
            case Node.Ans ans -> valueOf(ans, Source.Kind.ANS, line, notebook);
            case Node.LineRef reference -> valueOf(reference, Source.Kind.LINE_REFERENCE, line, notebook);
            case Node.Unary unary -> new Node.Unary(unary.sign(), expanded(unary.operand(), line, notebook), unary.span());
            case Node.Binary binary -> new Node.Binary(binary.operator(), expanded(binary.left(), line, notebook),
                    expanded(binary.right(), line, notebook), binary.span());
            case Node.Sqrt sqrt -> new Node.Sqrt(expanded(sqrt.operand(), line, notebook), sqrt.span());
            case Node.NumberLiteral number -> number;
            case Node.Assignment assignment -> throw new IllegalArgumentException("an assignment is never an operand");
        };
    }

    private static Node valueOf(Node leaf, Source.Kind kind, LineResult line, EvaluatedNotebook notebook) {
        String label = NotebookEvaluator.label(line.text(), leaf);
        for (Source source : line.sources()) {
            if (source.kind() == kind && source.label().equals(label)) {
                CertifiedValue value = notebook.result(source.target()).value();
                if (value.isExact()) {
                    return node(value.lower());
                }
            }
        }
        // An approximate value: the leaf keeps its writing.
        return leaf;
    }

    /** {@code value} as a tree: a decimal as a number, any other rational as {@code p / q}, after a minus sign if negative. */
    static Node node(Rational value) {
        Rational magnitude = value.abs();
        Node node = magnitude.toExactDecimal().isPresent()
                ? new Node.NumberLiteral(magnitude, Span.NONE)
                : new Node.Binary(Node.Operator.DIVIDE, new Node.NumberLiteral(Rational.of(magnitude.numerator()), Span.NONE),
                        new Node.NumberLiteral(Rational.of(magnitude.denominator()), Span.NONE), Span.NONE);
        return value.signum() < 0 ? new Node.Unary(Node.Sign.MINUS, node, Span.NONE) : node;
    }
}
