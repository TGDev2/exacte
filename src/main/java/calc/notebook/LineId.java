package calc.notebook;

/**
 * The stable identifier of a line of a {@link Notebook}: it survives every edit and every move of its line and is never
 * reused within a notebook. Internal: the user only sees line numbers (docs/adr/0008-semantique-carnet.md, section 1).
 */
public record LineId(long value) {
    public LineId {
        if (value < 1) {
            throw new IllegalArgumentException("line identifiers start at 1: " + value);
        }
    }

    @Override
    public String toString() {
        return "#" + value;
    }
}
