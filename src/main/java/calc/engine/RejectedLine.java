package calc.engine;

import java.util.Objects;

/**
 * A history line that was not restored, with the reason. The detail never repeats the content of the line.
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
        /** The file was written by a newer version of the format. */
        UNSUPPORTED_FORMAT_VERSION
    }

    public RejectedLine {
        Objects.requireNonNull(cause);
        Objects.requireNonNull(detail);
    }
}
