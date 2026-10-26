package calc.expression;

import java.io.Serializable;

/**
 * A range of characters of a line: {@code start} included, {@code end} excluded, counted in UTF-16 characters (Java
 * {@code String} indexes), so {@code line.substring(start, end)} is the source text.
 */
public record Span(int start, int end) implements Serializable {
    private static final long serialVersionUID = 1L;

    /** The span of a node built by hand, which has no source. */
    public static final Span NONE = new Span(0, 0);

    public Span {
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span [" + start + ", " + end + ")");
        }
    }

    /** From the start of {@code first} to the end of {@code last}. */
    public static Span between(Span first, Span last) {
        return new Span(first.start, last.end);
    }

    public int length() {
        return end - start;
    }
}
