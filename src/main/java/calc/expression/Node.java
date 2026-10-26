package calc.expression;

import calc.number.Rational;
import java.util.Objects;

/**
 * A node of the immutable syntax tree of a line (docs/adr/0007-grammaire-expressions.md). Every node carries the span of
 * its source; a parenthesised expression has no node of its own and keeps the span of its content. Nodes built by hand
 * may use {@link Span#NONE}.
 */
public sealed interface Node
        permits Node.NumberLiteral, Node.Variable, Node.Ans, Node.LineRef, Node.Unary, Node.Binary, Node.Sqrt,
        Node.Assignment {

    Span span();

    /** A number as written in the line: never negative (the minus sign is a {@link Unary}), always a finite decimal. */
    record NumberLiteral(Rational value, Span span) implements Node {
        public NumberLiteral {
            Objects.requireNonNull(value, "value");
            Objects.requireNonNull(span, "span");
            if (value.signum() < 0 || value.toExactDecimal().isEmpty()) {
                throw new IllegalArgumentException("not a number literal: " + value);
            }
        }
    }

    /** A variable, resolved by the caller. */
    record Variable(String name, Span span) implements Node {
        public Variable {
            requireVariableName(name);
            Objects.requireNonNull(span, "span");
        }
    }

    /** {@code ans}: the result of the previous line, resolved by the caller. */
    record Ans(Span span) implements Node {
        public Ans {
            Objects.requireNonNull(span, "span");
        }
    }

    /** {@code $3}: the result of a line, resolved by the caller. */
    record LineRef(int line, Span span) implements Node {
        public LineRef {
            if (line < 1) {
                throw new IllegalArgumentException("line numbers start at 1: " + line);
            }
            Objects.requireNonNull(span, "span");
        }
    }

    /** A sign before an operand. */
    record Unary(Sign sign, Node operand, Span span) implements Node {
        public Unary {
            Objects.requireNonNull(sign, "sign");
            requireExpression(operand);
            Objects.requireNonNull(span, "span");
        }
    }

    /** {@code + - * / ^}; the power is right associative and binds tighter than a sign. */
    record Binary(Operator operator, Node left, Node right, Span span) implements Node {
        public Binary {
            Objects.requireNonNull(operator, "operator");
            requireExpression(left);
            requireExpression(right);
            Objects.requireNonNull(span, "span");
        }
    }

    /** The square root, written {@code √x}, {@code sqrt(x)} or {@code racine(x)}. */
    record Sqrt(Node operand, Span span) implements Node {
        public Sqrt {
            requireExpression(operand);
            Objects.requireNonNull(span, "span");
        }
    }

    /** {@code name = value}: only at the top of a line. */
    record Assignment(String name, Node value, Span span) implements Node {
        public Assignment {
            requireVariableName(name);
            requireExpression(value);
            Objects.requireNonNull(span, "span");
        }
    }

    enum Sign {
        PLUS,
        MINUS
    }

    enum Operator {
        ADD,
        SUBTRACT,
        MULTIPLY,
        DIVIDE,
        POWER
    }

    private static void requireVariableName(String name) {
        if (!Lexer.isVariableName(name)) {
            throw new IllegalArgumentException("not a variable name: " + name);
        }
    }

    // An assignment is a whole line, never an operand.
    private static void requireExpression(Node node) {
        Objects.requireNonNull(node, "operand");
        if (node instanceof Assignment) {
            throw new IllegalArgumentException("an assignment cannot be an operand");
        }
    }
}
