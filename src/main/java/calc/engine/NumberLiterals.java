package calc.engine;

import calc.number.NumericException;
import calc.number.Rational;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a number literal as the user types it (docs/adr/0004-regles-affichage.md, section 7): the no-break spaces that
 * group thousands are removed, then the text is read by {@link Rational#parse(String)} (decimal with a comma or a
 * point, scientific exponent, fraction {@code p/q}). Shared by the operand fields of the engine and by the expression
 * language ({@code calc.expression}), so that both accept exactly the same numbers.
 */
public final class NumberLiterals {
    /** U+00A0, a thousands separator inside a number. */
    public static final char NO_BREAK_SPACE = (char) 0x00A0;
    /** U+202F, a thousands separator inside a number. */
    public static final char NARROW_NO_BREAK_SPACE = (char) 0x202F;

    private static final String SEPARATORS = "[" + NO_BREAK_SPACE + NARROW_NO_BREAK_SPACE + "]";
    // In the integer part of a member, groups of three digits separated by a no-break or a narrow no-break space.
    private static final Pattern GROUPED_MEMBER =
            Pattern.compile("([+-]?)(\\d{1,3}(?:" + SEPARATORS + "\\d{3})+)((?:[.,]\\d*)?(?:[eE][+-]?\\d+)?)");
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile(SEPARATORS);

    private NumberLiterals() {
    }

    /** True for the two characters that may group the digits of a number. */
    public static boolean isThousandsSeparator(char c) {
        return c == NO_BREAK_SPACE || c == NARROW_NO_BREAK_SPACE;
    }

    /**
     * The exact value of a literal without edge spaces. Errors are those of {@link Rational#parse(String)}, read after
     * the thousands separators are removed; a separator anywhere else than between groups of three digits of an integer
     * part is left in place, so the literal is malformed.
     */
    public static Rational parse(String literal) throws NumericException {
        return Rational.parse(removeThousandsSeparators(literal));
    }

    // Each member of a fraction may group its own integer part; any other use of a space is left for parse to refuse.
    private static String removeThousandsSeparators(String input) {
        int slash = input.indexOf('/');
        if (slash < 0) {
            return ungroup(input);
        }
        return ungroup(input.substring(0, slash)) + "/" + ungroup(input.substring(slash + 1));
    }

    private static String ungroup(String member) {
        Matcher matcher = GROUPED_MEMBER.matcher(member);
        if (!matcher.matches()) {
            return member;
        }
        return matcher.group(1) + THOUSANDS_SEPARATOR.matcher(matcher.group(2)).replaceAll("") + matcher.group(3);
    }
}
