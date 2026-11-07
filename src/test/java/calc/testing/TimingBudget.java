package calc.testing;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;

/**
 * The time budgets of the tests are promises measured on the reference machine and they do not change; a slower shared machine
 * applies a tolerance factor, read from the environment variable {@code CALC_TIMING_FACTOR} (see "Budgets de temps" in
 * docs/TESTS.md). Only durations measured on a clock go through this class, never the bounds of a computation.
 *
 * <p>The variable is read at each use: a bad value fails each test that reads it with the same clear message, where a static
 * initialiser would leave a {@code NoClassDefFoundError} to the following tests.
 */
public final class TimingBudget {
    private static final String VARIABLE = "CALC_TIMING_FACTOR";

    private TimingBudget() {
    }

    /** The factor of this machine. */
    public static double factor() {
        return parseFactor(System.getenv(VARIABLE));
    }

    /**
     * Reads the text of the variable: absent or empty gives 1. Below 1 the factor would make a budget stricter, and infinity or
     * NaN would switch every budget off, so they fail like a text that is not a number.
     */
    public static double parseFactor(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        double factor;
        try {
            factor = Double.parseDouble(text);
        } catch (NumberFormatException notANumber) {
            throw new IllegalArgumentException(VARIABLE + " must be a number from 1 upwards, not '" + text + "'", notANumber);
        }
        if (Double.isNaN(factor) || Double.isInfinite(factor) || factor < 1) {
            throw new IllegalArgumentException(VARIABLE + " must be a finite number from 1 upwards, not '" + text + "'");
        }
        return factor;
    }

    /** The budget multiplied by the factor, rounded up. */
    public static long scaled(long budget) {
        return (long) Math.ceil(budget * factor());
    }

    /** The budget multiplied by the factor. */
    public static Duration scaled(Duration budget) {
        return Duration.ofNanos(scaled(budget.toNanos()));
    }

    /** The text of an exceeded budget. The measure and the budget are in the given unit; nanoseconds are shown as milliseconds. */
    public static String message(String what, double measured, long budget, TimeUnit unit) {
        boolean inNanos = unit == TimeUnit.NANOSECONDS;
        double shown = inNanos ? 1e6 : 1;
        String symbol = inNanos ? "ms" : symbol(unit);
        return what + ": measured " + number(measured / shown) + " " + symbol + ", reference budget " + number(budget / shown) + " " + symbol
                + ", factor " + number(factor()) + " (limit " + number(scaled(budget) / shown) + " " + symbol + ")";
    }

    /** Fails unless the measure is strictly below the scaled budget. */
    public static void assertWithin(String what, double measured, long budget, TimeUnit unit) {
        if (!(measured < scaled(budget))) {
            fail(message(what, measured, budget, unit));
        }
    }

    /** {@code assertTimeoutPreemptively} on the scaled budget, whose failure also names the reference budget and the factor. */
    public static <T> T assertCompletesWithin(Duration budget, ThrowingSupplier<T> action) {
        return assertTimeoutPreemptively(scaled(budget), action, () -> timeoutMessage(budget));
    }

    /** {@code assertTimeoutPreemptively} on the scaled budget, whose failure also names the reference budget and the factor. */
    public static void assertCompletesWithin(Duration budget, Executable action) {
        assertTimeoutPreemptively(scaled(budget), action, () -> timeoutMessage(budget));
    }

    // JUnit adds the scaled budget at which the action was stopped.
    private static String timeoutMessage(Duration budget) {
        return "reference budget " + budget.toMillis() + " ms, factor " + number(factor());
    }

    private static String symbol(TimeUnit unit) {
        return switch (unit) {
            case MILLISECONDS -> "ms";
            case SECONDS -> "s";
            default -> unit.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String number(double value) {
        return new BigDecimal(value).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
