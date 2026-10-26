package calc.engine;

import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an operand typed by the user into an exact value (see docs/adr/0004-regles-affichage.md, section 7): the text is
 * trimmed, bounded, freed of the no-break spaces that group thousands, then read by {@link Rational#parse(String)}
 * (decimal with a comma or a point, scientific exponent, fraction {@code p/q}). Every refusal is a
 * {@link CalculationException} with a French message naming the operand.
 */
final class OperandParser {
    private static final char NO_BREAK_SPACE = (char) 0x00A0;
    private static final char NARROW_NO_BREAK_SPACE = (char) 0x202F;
    private static final String EDGE_SPACES = " \t" + NO_BREAK_SPACE + NARROW_NO_BREAK_SPACE;
    private static final String SEPARATORS = "[" + NO_BREAK_SPACE + NARROW_NO_BREAK_SPACE + "]";
    // In the integer part of a member, groups of three digits separated by a no-break or a narrow no-break space.
    private static final Pattern GROUPED_MEMBER =
            Pattern.compile("([+-]?)(\\d{1,3}(?:" + SEPARATORS + "\\d{3})+)((?:[.,]\\d*)?(?:[eE][+-]?\\d+)?)");
    private static final Pattern THOUSANDS_SEPARATOR = Pattern.compile(SEPARATORS);

    private OperandParser() {
    }

    static CertifiedValue parse(String input, InputField field) throws CalculationException {
        String name = field == InputField.FIRST ? "premier" : "second";
        if (input == null || input.trim().isEmpty()) {
            throw new CalculationException("Saisissez le " + name + " nombre.", field);
        }
        String trimmed = input.trim();
        if (trimmed.length() > CalculationLimits.MAX_INPUT_LENGTH) {
            throw new CalculationException(
                    "Le " + name + " nombre est trop long (" + CalculationLimits.MAX_INPUT_LENGTH + " caractères maximum).",
                    field);
        }
        String compact = stripEdgeSpaces(trimmed);
        if (compact.isEmpty()) {
            throw new CalculationException("Saisissez le " + name + " nombre.", field);
        }
        try {
            return CertifiedValue.exact(Rational.parse(removeThousandsSeparators(compact)));
        } catch (NumericException exception) {
            throw new CalculationException(message(exception.error(), compact, name), field);
        }
    }

    private static String message(NumericError error, String compact, String name) {
        return switch (error) {
            case MALFORMED_INPUT -> isGroupedWithOrdinarySpaces(compact)
                    ? "Le " + name + " nombre contient une espace : séparez les milliers par une espace insécable"
                            + " ou supprimez l'espace."
                    : "Le " + name + " nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.";
            case EXPONENT_OUT_OF_RANGE -> "L'exposant de la notation scientifique du " + name
                    + " nombre doit être compris entre -" + CalculationLimits.MAX_SCIENTIFIC_EXPONENT
                    + " et " + CalculationLimits.MAX_SCIENTIFIC_EXPONENT + ".";
            case SIZE_LIMIT_EXCEEDED -> "Le " + name + " nombre est trop grand ou a trop de décimales pour un calcul exact.";
            case DIVISION_BY_ZERO -> "Le " + name + " nombre est une fraction dont le dénominateur vaut 0.";
            // Rational.parse raises no other code; kept total so that a new code cannot go unnoticed silently.
            default -> "Le " + name + " nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.";
        };
    }

    // "1 000" with ordinary spaces: refused (a space separates tokens in expressions), but worth a precise message.
    private static boolean isGroupedWithOrdinarySpaces(String compact) {
        if (compact.indexOf(' ') < 0) {
            return false;
        }
        try {
            Rational.parse(removeThousandsSeparators(compact.replace(' ', NO_BREAK_SPACE)));
            return true;
        } catch (NumericException exception) {
            return exception.error() != NumericError.MALFORMED_INPUT;
        }
    }

    private static String stripEdgeSpaces(String input) {
        int start = 0;
        int end = input.length();
        while (start < end && EDGE_SPACES.indexOf(input.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE_SPACES.indexOf(input.charAt(end - 1)) >= 0) {
            end--;
        }
        return input.substring(start, end);
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
