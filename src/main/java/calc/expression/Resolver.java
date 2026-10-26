package calc.expression;

/**
 * Gives the values of the names and references of a line; implemented by the caller (the notebook decides which line
 * {@code ans} and {@code $n} designate and which definition of a variable applies). Never returns {@code null}.
 */
public interface Resolver {
    /** The value of the variable {@code name} (names are case sensitive). */
    Resolution variable(String name);

    /** The value of {@code ans}. */
    Resolution ans();

    /** The value of the line {@code number} ({@code $3}); {@code number} is at least 1. */
    Resolution line(int number);
}
