package calc.notebook;

import java.util.Objects;

/**
 * One line of a {@link Notebook}: its stable identifier and its source text, the only data a notebook persists (its
 * results are always recalculated). The text holds no line break: a caller splits a pasted block into lines.
 */
public record Line(LineId id, String text) {
    public Line {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a line text holds no line break");
        }
    }
}
