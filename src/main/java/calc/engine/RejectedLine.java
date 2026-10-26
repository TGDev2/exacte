package calc.engine;

import java.util.Objects;

/**
 * A history line that was not restored, with the reason. The detail never repeats the content of the line.
 * {@code lineNumber} is the line of a version 1 file, and the 1-based position in {@code entries} of a version 2 file.
 */
public record RejectedLine(int lineNumber, Cause cause, String detail) {
    public enum Cause {
        /** Not four tab-separated columns. */
        WRONG_COLUMN_COUNT,
        /** The first column is not a known operation. */
        UNKNOWN_OPERATION,
        /** The engine refuses the operands (not a number, too long, division by zero...). */
        INVALID_OPERAND,
        /** The stored result column is not a usable number. */
        UNREADABLE_RESULT,
        /** The stored result differs from the recalculation. */
        INCONSISTENT_RESULT,
        /** Version 2 file: the entry is not an object with the expected fields. */
        MALFORMED_ENTRY
    }

    public RejectedLine {
        Objects.requireNonNull(cause);
        Objects.requireNonNull(detail);
    }
}
