package calc.expression;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Renumbers the line references of the ORIGINAL text of a line, leaving every other character untouched (spaces, the
 * notation of numbers, the comment). A reference is what the lexer reads as one: {@code $} followed by digits, before
 * the first {@code #}. A reference to a deleted line becomes {@code $?}, which reads as « Référence vers une ligne
 * supprimée ». The text does not have to parse: a line that is still being fixed keeps its references up to date.
 */
public final class ReferenceRewriter {
    /** What a reference to a deleted line becomes. */
    public static final String DELETED_REFERENCE = "$?";

    private ReferenceRewriter() {
    }

    /**
     * {@code line} with each reference {@code $n} replaced according to {@code renumbering}. A reference whose number
     * does not change, or that the lexer refuses ({@code $0}, a number beyond an {@code int}), is kept as written.
     */
    public static String rewrite(String line, LineRenumbering renumbering) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(renumbering, "renumbering");
        StringBuilder text = new StringBuilder(line.length());
        int length = line.length();
        int index = 0;
        while (index < length) {
            char c = line.charAt(index);
            if (c == '#') {
                break;
            }
            if (c != '$' || index + 1 >= length || !Lexer.isDigit(line.charAt(index + 1))) {
                text.append(c);
                index++;
                continue;
            }
            int end = index + 1;
            while (end < length && Lexer.isDigit(line.charAt(end))) {
                end++;
            }
            String reference = line.substring(index, end);
            text.append(replacement(reference, renumbering));
            index = end;
        }
        return text.append(line, index, length).toString();
    }

    private static String replacement(String reference, LineRenumbering renumbering) {
        int number = Lexer.referenceNumber(reference);
        if (number < 1) {
            return reference;
        }
        OptionalInt renumbered = renumbering.newNumber(number);
        if (renumbered.isEmpty()) {
            return DELETED_REFERENCE;
        }
        int target = renumbered.getAsInt();
        if (target < 1) {
            throw new IllegalArgumentException("line numbers start at 1: " + number + " -> " + target);
        }
        return target == number ? reference : "$" + target;
    }
}
