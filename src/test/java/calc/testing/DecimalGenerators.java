package calc.testing;

import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Generators of decimal operands, shared by the property tests.
 *
 * <p>Every valid generator returns a {@link DecimalText}: the text the user would type and the value it denotes, built
 * from the same parts. The value therefore never comes from the production parser, so it can serve as an oracle.
 * The generators only know the input grammar documented in the README (sign, digits, comma or point, thousands
 * separators, edge spaces) and the public length bound.
 */
public final class DecimalGenerators {
    /** The longest operand the calculator accepts, in characters (the public bound of the input length). */
    public static final int MAX_LENGTH = 1005;

    public static final char NO_BREAK_SPACE = (char) 0x00A0;
    public static final char NARROW_NO_BREAK_SPACE = (char) 0x202F;

    private static final char[] GROUPING_SEPARATORS = {' ', NO_BREAK_SPACE, NARROW_NO_BREAK_SPACE};
    private static final char[] EDGE_SPACES = {' ', '\t', NO_BREAK_SPACE, NARROW_NO_BREAK_SPACE};
    private static final int SHORT_DIGITS = 30;
    private static final int OPERAND_DIGITS = 40;

    /** A typed decimal and the value it stands for. */
    public record DecimalText(String text, BigDecimal value) {
        @Override
        public String toString() {
            if (text.length() <= 100) {
                return "'" + text + "'";
            }
            return "'" + text.substring(0, 50) + "...' (" + text.length() + " characters)";
        }
    }

    private DecimalGenerators() {
    }

    /** Valid decimals of any length up to the input bound, with the edge lengths (1 digit, the maximum) over-represented. */
    public static Arbitrary<DecimalText> decimals() {
        return Arbitraries.frequencyOf(Tuple.of(8, decimals(MAX_LENGTH)), Tuple.of(1, atTheBound()));
    }

    /** Valid decimals of at most {@code maxLength} characters, canonical text included (at least 4). */
    public static Arbitrary<DecimalText> decimals(int maxLength) {
        if (maxLength < 4) {
            throw new IllegalArgumentException("maxLength must be at least 4");
        }
        // Room for a sign, a separator and the "0" the canonical text puts before a bare decimal point.
        int maxDigits = maxLength - 3;
        Arbitrary<Integer> digitCount = Arbitraries.frequencyOf(
                Tuple.of(10, Arbitraries.integers().between(1, Math.min(SHORT_DIGITS, maxDigits))),
                Tuple.of(2, Arbitraries.integers().between(1, maxDigits)),
                Tuple.of(1, Arbitraries.just(maxDigits)));
        return digitCount.flatMap(DecimalGenerators::decimalsWithDigits);
    }

    /**
     * The longest valid decimals, in every shape (with or without sign, integer part, decimal separator): the text
     * is {@link #MAX_LENGTH} characters long, or one less when it has no integer digit, so that its canonical text
     * (which gains a "0" before the bare decimal point) is {@link #MAX_LENGTH} characters long at most.
     */
    public static Arbitrary<DecimalText> atTheBound() {
        return Combinators.combine(signs(), separators(), Arbitraries.of(true, true, false), Arbitraries.integers().between(0, MAX_LENGTH),
                        Arbitraries.strings().withCharRange('0', '9').ofLength(MAX_LENGTH))
                .as((sign, separator, withSeparator, split, allDigits) -> {
                    boolean bare = withSeparator && split == 0;
                    int digitCount = MAX_LENGTH - sign.length() - (withSeparator ? 1 : 0) - (bare ? 1 : 0);
                    String digits = allDigits.substring(0, digitCount);
                    int integerLength = withSeparator ? (bare ? 0 : Math.min(split, digitCount)) : digitCount;
                    String fraction = withSeparator ? digits.substring(integerLength) : "";
                    String text = sign + digits.substring(0, integerLength) + (withSeparator ? String.valueOf(separator) : "") + fraction;
                    return parsed(text, sign, digits.substring(0, integerLength), fraction);
                });
    }

    /**
     * Valid decimals of {@link #MAX_LENGTH} characters with no integer digit (".5", "-,5"): they are the one shape whose
     * canonical text ("0,5") is longer than what was typed, so the calculator refuses them as too long.
     */
    public static Arbitrary<DecimalText> bareDecimalPointAtTheBound() {
        // No "+" sign: the canonical text drops it, which pays for the added "0".
        return Combinators.combine(Arbitraries.of("", "-"), separators(), Arbitraries.strings().withCharRange('0', '9').ofLength(MAX_LENGTH),
                        Arbitraries.chars().range('1', '9'))
                .as((sign, separator, allDigits, lastDigit) -> {
                    // The last digit is not a zero: trailing zeros are dropped from the canonical text, which would shorten it.
                    String fraction = allDigits.substring(0, MAX_LENGTH - sign.length() - 2) + lastDigit;
                    return parsed(sign + separator + fraction, sign, "", fraction);
                });
    }

    /** Valid decimals short enough that the product of two of them stays under 200 digits. */
    public static Arbitrary<DecimalText> operands() {
        return decimals(OPERAND_DIGITS + 2);
    }

    /** Valid decimals denoting an integer (no fractional digit), at most {@code maxDigits} digits. */
    public static Arbitrary<DecimalText> integers(int maxDigits) {
        Arbitrary<String> digits = Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(maxDigits);
        return Combinators.combine(signs(), digits, Arbitraries.of(true, false), separators())
                .as((sign, integerDigits, trailingSeparator, separator) -> {
                    String text = sign + integerDigits + (trailingSeparator ? String.valueOf(separator) : "");
                    return new DecimalText(text, new BigDecimal(("-".equals(sign) ? "-" : "") + integerDigits));
                });
    }

    /** Valid decimals whose integer part is written with thousands separators (space, no-break or narrow no-break space). */
    public static Arbitrary<DecimalText> grouped() {
        Arbitrary<Integer> groups = Arbitraries.integers().between(1, 6);
        Arbitrary<String> head = Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(3);
        Arbitrary<String> fraction = Arbitraries.strings().withCharRange('0', '9').ofMaxLength(12);
        Arbitrary<Character> groupSeparator = Arbitraries.of(GROUPING_SEPARATORS[0], GROUPING_SEPARATORS[1], GROUPING_SEPARATORS[2]);
        return groups.flatMap(count -> Combinators.combine(
                        signs(),
                        head,
                        Arbitraries.strings().withCharRange('0', '9').ofLength(3).list().ofSize(count),
                        groupSeparator.list().ofSize(count),
                        separators(),
                        fraction)
                .as((sign, first, triples, spaces, separator, fractionDigits) -> {
                    StringBuilder text = new StringBuilder(sign).append(first);
                    StringBuilder digits = new StringBuilder(first);
                    for (int index = 0; index < triples.size(); index++) {
                        text.append(spaces.get(index)).append(triples.get(index));
                        digits.append(triples.get(index));
                    }
                    String value = ("-".equals(sign) ? "-" : "") + digits;
                    if (!fractionDigits.isEmpty()) {
                        text.append(separator).append(fractionDigits);
                        value += "." + fractionDigits;
                    }
                    return new DecimalText(text.toString(), new BigDecimal(value));
                }));
    }

    /** The same decimals, surrounded by up to three edge spaces (space, tab, no-break or narrow no-break space) on each side. */
    public static Arbitrary<DecimalText> padded(Arbitrary<DecimalText> decimals) {
        Arbitrary<String> padding = Arbitraries.of(EDGE_SPACES[0], EDGE_SPACES[1], EDGE_SPACES[2], EDGE_SPACES[3])
                .list().ofMaxSize(3)
                .map(characters -> {
                    StringBuilder builder = new StringBuilder();
                    characters.forEach(builder::append);
                    return builder.toString();
                });
        return Combinators.combine(padding, decimals, padding)
                .as((left, decimal, right) -> new DecimalText(left + decimal.text() + right, decimal.value()));
    }

    /** Valid decimals as typed by a user: plain, grouped or padded. */
    public static Arbitrary<DecimalText> typedDecimals() {
        return Arbitraries.frequencyOf(
                Tuple.of(8, decimals()),
                Tuple.of(2, grouped()),
                Tuple.of(2, padded(decimals(60))),
                Tuple.of(1, padded(grouped())));
    }

    /** Valid decimals denoting zero ("0", "-0,000", "+.0"...). */
    public static Arbitrary<DecimalText> zeros() {
        Arbitrary<String> zeros = Arbitraries.strings().withChars('0').ofMinLength(1).ofMaxLength(20);
        return Combinators.combine(signs(), zeros, zeros, separators(), Arbitraries.of(true, false))
                .as((sign, integerZeros, fractionZeros, separator, withInteger) -> {
                    String text = withInteger
                            ? sign + integerZeros + separator + fractionZeros
                            : sign + separator + fractionZeros;
                    return new DecimalText(text, BigDecimal.ZERO);
                });
    }

    /**
     * Texts that are certainly not a valid operand and not blank: a letter, a non-ASCII digit, a scientific exponent,
     * two decimal separators, a misplaced sign or a lone sign or separator.
     */
    public static Arbitrary<String> invalidDecimals() {
        Arbitrary<String> valid = decimals(40).map(DecimalText::text);
        Arbitrary<Character> letters = Arbitraries.chars().alpha();
        // Arabic-Indic, Extended Arabic-Indic, Devanagari, fullwidth and mathematical double-struck digits.
        Arbitrary<String> nonAsciiDigit = Arbitraries.of(0x0663, 0x06F5, 0x0967, 0xFF15, 0x1D7D8)
                .map(codePoint -> new String(Character.toChars(codePoint)));
        Arbitrary<String> letterInserted = Combinators.combine(valid, letters, Arbitraries.integers().between(0, 40))
                .as((text, letter, position) -> insert(text, letter.toString(), position));
        Arbitrary<String> digitInserted = Combinators.combine(valid, nonAsciiDigit, Arbitraries.integers().between(0, 40))
                .as(DecimalGenerators::insert);
        Arbitrary<String> exponent = Combinators.combine(valid, Arbitraries.of("e", "E"), Arbitraries.integers().between(-9, 99))
                .as((text, marker, power) -> text + marker + power);
        Arbitrary<String> twoSeparators = Combinators.combine(Arbitraries.of(".", ","), Arbitraries.of(".", ","))
                .as((first, second) -> "1" + first + "2" + second + "3");
        Arbitrary<String> trailingSign = valid.map(text -> text + "-");
        Arbitrary<String> doubledSign = Arbitraries.of("+-5", "--5", "++5", "-+5", "5+", "+ 5", "- 5");
        Arbitrary<String> lone = Arbitraries.of("+", "-", ".", ",", "+.", "-,", ".,", "..", ",,", "+-", "e", "NaN", "Infinity");
        return Arbitraries.oneOf(letterInserted, digitInserted, exponent, twoSeparators, trailingSign, doubledSign, lone);
    }

    /**
     * Texts that mean "no number typed": empty, or made only of the edge spaces (space, tab, no-break and narrow
     * no-break space), or made only of control characters up to U+0020 (which a text field can hold after a paste).
     * The two kinds are not mixed: a control character between two no-break spaces is not an edge space.
     */
    public static Arbitrary<String> blanks() {
        Arbitrary<String> edgeSpaces = Arbitraries.of(' ', '\t', NO_BREAK_SPACE, NARROW_NO_BREAK_SPACE)
                .list().ofMaxSize(8)
                .map(DecimalGenerators::join);
        Arbitrary<String> controls = Arbitraries.chars().range((char) 0, ' ')
                .list().ofMaxSize(8)
                .map(DecimalGenerators::join);
        return Arbitraries.oneOf(edgeSpaces, controls);
    }

    private static String join(List<Character> characters) {
        StringBuilder builder = new StringBuilder();
        characters.forEach(builder::append);
        return builder.toString();
    }

    /** Non-zero decimals. */
    public static Arbitrary<DecimalText> nonZero(Arbitrary<DecimalText> decimals) {
        return decimals.filter(decimal -> decimal.value().signum() != 0);
    }

    /** All the sign forms a user can type. */
    public static Arbitrary<String> signs() {
        return Arbitraries.frequencyOf(
                Tuple.of(6, Arbitraries.just("")),
                Tuple.of(2, Arbitraries.just("+")),
                Tuple.of(3, Arbitraries.just("-")));
    }

    public static Arbitrary<Character> separators() {
        return Arbitraries.of(',', '.');
    }

    private static Arbitrary<DecimalText> decimalsWithDigits(int count) {
        Arbitrary<String> digits = Arbitraries.oneOf(
                Arbitraries.strings().withCharRange('0', '9').ofLength(count),
                Arbitraries.strings().withChars('0', '1').ofLength(count),
                Arbitraries.strings().withChars('0', '9').ofLength(count));
        return Combinators.combine(signs(), digits, Arbitraries.integers().between(0, count), separators(), Arbitraries.of(true, true, false))
                .as(DecimalGenerators::assemble);
    }

    private static DecimalText assemble(String sign, String digits, int split, char separator, boolean withSeparator) {
        String integerPart = digits.substring(0, split);
        String fractionPart = digits.substring(split);
        boolean negative = "-".equals(sign);
        String text;
        String effectiveFraction;
        if (integerPart.isEmpty()) {
            text = sign + separator + fractionPart;
            effectiveFraction = fractionPart;
        } else if (withSeparator) {
            text = sign + integerPart + separator + fractionPart;
            effectiveFraction = fractionPart;
        } else {
            text = sign + integerPart;
            effectiveFraction = "";
        }
        String value = (negative ? "-" : "")
                + (integerPart.isEmpty() ? "0" : integerPart)
                + "."
                + (effectiveFraction.isEmpty() ? "0" : effectiveFraction);
        return new DecimalText(text, new BigDecimal(value));
    }

    private static DecimalText parsed(String text, String sign, String integerPart, String fractionPart) {
        String value = ("-".equals(sign) ? "-" : "")
                + (integerPart.isEmpty() ? "0" : integerPart)
                + "."
                + (fractionPart.isEmpty() ? "0" : fractionPart);
        return new DecimalText(text, new BigDecimal(value));
    }

    private static String insert(String text, String piece, int position) {
        int index = Math.min(position, text.length());
        return text.substring(0, index) + piece + text.substring(index);
    }

}
