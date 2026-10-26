package calc.format;

/** The notations of {@link ValueFormatter}. The rules are in docs/adr/0004-regles-affichage.md. */
public enum DisplayMode {
    /** Positional notation for {@code 10^-6 <= |x| < 10^15}, scientific notation otherwise. */
    AUTO,
    /** Positional notation, unless it would read as 0 or cut the integer part of an approximate value. */
    DECIMAL,
    /** Reduced fraction {@code p/q}, or the Auto text when the fraction is too long. */
    FRACTION,
    /** {@code m × 10ⁿ}. */
    SCIENTIFIC
}
