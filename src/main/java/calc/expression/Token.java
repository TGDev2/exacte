package calc.expression;

import calc.number.Rational;
import java.util.Objects;

/**
 * One token of a line: its type, its source text and its span. A {@link Type#NUMBER} also carries its exact value and a
 * {@link Type#REFERENCE} its line number; both are read by the lexer, which reports their errors with their position.
 *
 * @param number    the value of a number, {@code null} for any other type
 * @param reference the line number of a reference ({@code $3} gives 3), 0 for any other type
 */
public record Token(Type type, String text, Span span, Rational number, int reference) {
    public enum Type {
        NUMBER,
        IDENTIFIER,
        /** The reserved word {@code ans}, in any case. */
        ANS,
        /** The reserved words {@code sqrt} and {@code racine}, in any case. */
        SQRT_WORD,
        /** {@code $} followed by a line number. */
        REFERENCE,
        PLUS,
        /** {@code -} or the minus sign U+2212. */
        MINUS,
        /** {@code *} or the multiplication sign U+00D7. */
        TIMES,
        /** {@code /} or the division sign U+00F7. */
        DIVIDE,
        POWER,
        /** The root sign U+221A. */
        ROOT,
        LEFT_PARENTHESIS,
        RIGHT_PARENTHESIS,
        EQUALS,
        /** From {@code #} to the end of the line. */
        COMMENT
    }

    public Token {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(span, "span");
    }

    static Token of(Type type, String line, int start, int end) {
        return new Token(type, line.substring(start, end), new Span(start, end), null, 0);
    }
}
