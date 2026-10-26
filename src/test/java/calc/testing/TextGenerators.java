package calc.testing;

import calc.testing.DecimalGenerators.DecimalText;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators of arbitrary text, for the fuzzing of every parser of the project (operands today, file formats later).
 *
 * <p>They aim at the inputs that break hand-written parsers: any Unicode code point (including unpaired surrogates),
 * control characters, digits of other scripts, the many Unicode spaces, near-numbers and valid numbers damaged at one
 * position.
 */
public final class TextGenerators {
    private static final int[] NON_ASCII_DIGITS = {0x0663, 0x06F5, 0x0967, 0x09EB, 0xFF15, 0x1D7D8};
    private static final int[] UNICODE_SPACES = {
        0x00A0, 0x1680, 0x2000, 0x2003, 0x2007, 0x2009, 0x200A, 0x200B, 0x202F, 0x205F, 0x3000, 0xFEFF, 0x0085, 0x2028, 0x2029
    };
    private static final String NUMBER_LIKE_ALPHABET = "0123456789+-.,eE \t";

    private TextGenerators() {
    }

    /** Anything: the union of all the generators below plus valid and damaged operands. Includes {@code null} rarely. */
    public static Arbitrary<String> anyInput() {
        return Arbitraries.frequencyOf(
                        Tuple.of(3, unicode(40)),
                        Tuple.of(2, controlCharacters()),
                        Tuple.of(3, nonAsciiDigits()),
                        Tuple.of(2, unicodeSpaces()),
                        Tuple.of(5, numberLike()),
                        Tuple.of(3, DecimalGenerators.typedDecimals().map(DecimalText::text)),
                        Tuple.of(3, DecimalGenerators.invalidDecimals()),
                        Tuple.of(3, damagedDecimal()),
                        Tuple.of(1, DecimalGenerators.blanks()))
                .injectNull(0.01);
    }

    /** Strings far longer than the input bound, to check that the refusal is fast. */
    public static Arbitrary<String> overlong() {
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofMinLength(4200).ofMaxLength(8000);
        Arbitrary<String> mixed = Arbitraries.strings().withChars(NUMBER_LIKE_ALPHABET.toCharArray()).ofMinLength(4200).ofMaxLength(8000);
        Arbitrary<String> huge = Arbitraries.integers().between(10_000, 60_000).map(length -> "9".repeat(length));
        // A digit at each end: edge spaces are stripped before the length is measured, so a text of spaces is "empty", not "too long".
        return Arbitraries.frequencyOf(Tuple.of(4, digits), Tuple.of(4, mixed), Tuple.of(1, huge)).map(text -> "1" + text + "1");
    }

    /** Any Unicode text, unpaired surrogates included. */
    public static Arbitrary<String> unicode(int maxLength) {
        return Arbitraries.strings().all().ofMaxLength(maxLength);
    }

    /** Text made of control characters (C0, DEL and C1), possibly mixed with digits. */
    public static Arbitrary<String> controlCharacters() {
        Arbitrary<Character> control = Arbitraries.chars().range((char) 0, (char) 0x1F);
        Arbitrary<Character> delAndC1 = Arbitraries.chars().range((char) 0x7F, (char) 0x9F);
        Arbitrary<Character> digit = Arbitraries.chars().range('0', '9');
        return Arbitraries.oneOf(control, delAndC1, digit).list().ofMaxSize(12).map(TextGenerators::join);
    }

    /** ASCII digits mixed with digits of other scripts (Arabic-Indic, Devanagari, fullwidth...). */
    public static Arbitrary<String> nonAsciiDigits() {
        Arbitrary<String> foreign = Arbitraries.of(NON_ASCII_DIGITS[0], NON_ASCII_DIGITS[1], NON_ASCII_DIGITS[2],
                        NON_ASCII_DIGITS[3], NON_ASCII_DIGITS[4], NON_ASCII_DIGITS[5])
                .map(codePoint -> new String(Character.toChars(codePoint)));
        Arbitrary<String> ascii = Arbitraries.chars().range('0', '9').map(String::valueOf);
        Arbitrary<String> separator = Arbitraries.of(",", ".", "-", "");
        return Arbitraries.oneOf(foreign, ascii, separator).list().ofMinSize(1).ofMaxSize(10).map(parts -> String.join("", parts));
    }

    /** Digits and separators interleaved with the many Unicode space characters. */
    public static Arbitrary<String> unicodeSpaces() {
        Arbitrary<String> space = Arbitraries.of(UNICODE_SPACES[0], UNICODE_SPACES[1], UNICODE_SPACES[2], UNICODE_SPACES[3],
                        UNICODE_SPACES[4], UNICODE_SPACES[5], UNICODE_SPACES[6], UNICODE_SPACES[7], UNICODE_SPACES[8],
                        UNICODE_SPACES[9], UNICODE_SPACES[10], UNICODE_SPACES[11], UNICODE_SPACES[12], UNICODE_SPACES[13],
                        UNICODE_SPACES[14])
                .map(codePoint -> new String(Character.toChars(codePoint)));
        Arbitrary<String> group = Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(4);
        return Arbitraries.oneOf(space, group, Arbitraries.of(" ", "\t", ",", ".", "-")).list().ofMinSize(1).ofMaxSize(10)
                .map(parts -> String.join("", parts));
    }

    /** Strings over the alphabet of numbers (digits, signs, separators, exponent letters, spaces) in any order. */
    public static Arbitrary<String> numberLike() {
        return Arbitraries.strings().withChars(NUMBER_LIKE_ALPHABET.toCharArray()).ofMaxLength(24);
    }

    /** A valid decimal with one character deleted, duplicated, replaced or swapped with its neighbour. */
    public static Arbitrary<String> damagedDecimal() {
        Arbitrary<Character> replacement = Arbitraries.of('x', 'e', '+', '-', ',', '.', ' ', '\t', '0', '9', (char) 0x00A0, (char) 0x0663);
        return Combinators.combine(
                        DecimalGenerators.decimals(60).map(DecimalText::text),
                        Arbitraries.integers().between(0, 3),
                        Arbitraries.integers().between(0, 60),
                        replacement)
                .as(TextGenerators::damage);
    }

    private static String damage(String text, int kind, int position, char replacement) {
        int index = Math.min(position, text.length() - 1);
        StringBuilder builder = new StringBuilder(text);
        switch (kind) {
            case 0 -> builder.deleteCharAt(index);
            case 1 -> builder.insert(index, builder.charAt(index));
            case 2 -> builder.setCharAt(index, replacement);
            default -> {
                if (index + 1 < builder.length()) {
                    char swapped = builder.charAt(index);
                    builder.setCharAt(index, builder.charAt(index + 1));
                    builder.setCharAt(index + 1, swapped);
                }
            }
        }
        return builder.toString();
    }

    private static String join(List<Character> characters) {
        StringBuilder builder = new StringBuilder();
        characters.forEach(builder::append);
        return builder.toString();
    }
}
