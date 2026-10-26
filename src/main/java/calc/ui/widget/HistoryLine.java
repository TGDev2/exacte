package calc.ui.widget;

import java.util.Objects;

/**
 * One history entry already formatted for display, so that a cell renderer needs no knowledge of the engine.
 *
 * @param expression the calculation without its result, for instance {@code 1 / 3} or {@code √(2)}
 * @param result     the result in the display mode in force, with its « ≈ » or « … » marker
 * @param exact      whether the result is exact (otherwise it is only known within an interval)
 * @param detail     the exactness detail of the result (error bound, exact value behind a cut text), empty if none
 */
public record HistoryLine(String expression, String result, boolean exact, String detail) {
    private static final String APPROXIMATE_PREFIX = "≈ ";

    public HistoryLine {
        Objects.requireNonNull(expression, "expression");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(detail, "detail");
    }

    /** The line as one sentence, {@code 1 / 3 = 0,333333333333…} or {@code √(2) ≈ 1,41421356237…}. */
    public String text() {
        return result.startsWith(APPROXIMATE_PREFIX)
                ? expression + " " + result
                : expression + " = " + result;
    }

    @Override
    public String toString() {
        return text();
    }
}
