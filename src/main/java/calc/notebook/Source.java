package calc.notebook;

import java.util.Objects;

/**
 * What a line takes its value from: a variable, a {@code $n} reference or {@code ans} that it names and that designates a
 * line above it. The target is the closest definition above for a variable, the line {@code n} for {@code $n} and the
 * closest line above that has a value for {@code ans} (docs/adr/0008-semantique-carnet.md, section 2).
 *
 * @param kind   what the line names
 * @param label  the text as written in the line: the variable name, {@code $3}, {@code ans}
 * @param target the line that gives the value
 */
public record Source(Kind kind, String label, LineId target) {
    /** What a source names. */
    public enum Kind {
        /** A variable, its target is its closest definition above. */
        VARIABLE,
        /** A {@code $n} reference, its target is the line {@code n}. */
        LINE_REFERENCE,
        /** {@code ans}, its target is the closest line above that has a value. */
        ANS
    }

    public Source {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(target, "target");
    }
}
