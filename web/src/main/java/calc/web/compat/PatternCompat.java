package calc.web.compat;

import java.util.regex.Pattern;

/**
 * {@code Pattern.compile}, corrected for a defect of the regular expressions of TeaVM 0.16.0. There, {@code \d} is not a
 * leaf of the matcher, so {@code \d{3}} counts its entries in the match state, and leaving towards the rest of the pattern
 * does not reset that count. Inside a repeated group, the second pass then reads no digit: {@code (?:\d{3})+} refuses
 * {@code 000000}, which the JDK accepts. NumberLiterals groups thousands with exactly this form.
 *
 * <p>{@code \d\d\d} means the same and has no counter. {@link CompatCallRewriter} sends the core here when TeaVM compiles
 * it; any other regular expression passes through unchanged.
 */
public final class PatternCompat {
    private static final String COUNTED_DIGITS = "\\d{3}";
    private static final String SPELLED_DIGITS = "\\d\\d\\d";

    private PatternCompat() {
    }

    public static Pattern compile(String regex) {
        return Pattern.compile(regex.replace(COUNTED_DIGITS, SPELLED_DIGITS));
    }
}
