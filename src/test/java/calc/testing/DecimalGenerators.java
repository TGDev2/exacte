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
 * The generators only know the input grammar of docs/adr/0004-regles-affichage.md (sign, digits, comma or point,
 * no-break spaces between groups of thousands, edge spaces, scientific exponent, fraction) and the public bounds.
 */
public final class DecimalGenerators {
    /** The longest operand the calculator accepts, in characters (the public bound of the input length). */
    public static final int MAX_LENGTH = 4100;

    /**
     * The most digits a decimal may have, whatever the place of its separator, and still fit the exact core: below
     * {@code 10^1233} and with at most 1233 decimals, its numerator and its denominator stay within 4096 bits.
     */
    public static final int MAX_DIGITS = 1233;

    public static final char NO_BREAK_SPACE = (char) 0x00A0;
    public static final char NARROW_NO_BREAK_SPACE = (char) 0x202F;

    private static final char[] GROUPING_SEPARATORS = {NO_BREAK_SPACE, NARROW_NO_BREAK_SPACE};
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

    /** Valid decimals of any size the core accepts, with the edge lengths (1 digit, the input bound) over-represented. */
    public static Arbitrary<DecimalText> decimals() {
        return Arbitraries.frequencyOf(Tuple.of(8, decimals(MAX_DIGITS + 3)), Tuple.of(1, atTheBound()));
    }

    /** Valid decimals of at most {@code maxLength} characters, canonical text included (at least 4). */
    public static Arbitrary<DecimalText> decimals(int maxLength) {
        if (maxLength < 4) {
            throw new IllegalArgumentException("maxLength must be at least 4");
        }
        // Room for a sign, a separator and the "0" the canonical text puts before a bare decimal point.
        int maxDigits = Math.min(maxLength - 3, MAX_DIGITS);
        Arbitrary<Integer> digitCount = Arbitraries.frequencyOf(
                Tuple.of(10, Arbitraries.integers().between(1, Math.min(SHORT_DIGITS, maxDigits))),
                Tuple.of(2, Arbitraries.integers().between(1, maxDigits)),
                Tuple.of(1, Arbitraries.just(maxDigits)));
        return digitCount.flatMap(DecimalGenerators::decimalsWithDigits);
    }

    /**
     * Valid decimals of exactly {@link #MAX_LENGTH} characters, in every shape (with or without sign, integer part,
     * decimal separator): {@link #MAX_DIGITS} digits padded with zeros that do not change the size of the value, leading
     * zeros in the integer part or trailing zeros in the fractional part.
     */
    public static Arbitrary<DecimalText> atTheBound() {
        return Combinators.combine(signs(), separators(), Arbitraries.of(true, true, false), Arbitraries.integers().between(0, MAX_DIGITS),
                        Arbitraries.strings().withCharRange('0', '9').ofLength(MAX_DIGITS), Arbitraries.of(true, false))
                .as((sign, separator, withSeparator, split, digits, padTheIntegerPart) -> {
                    String integerDigits = withSeparator ? digits.substring(0, split) : digits;
                    String fractionDigits = withSeparator ? digits.substring(split) : "";
                    String zeros = "0".repeat(MAX_LENGTH - sign.length() - digits.length() - (withSeparator ? 1 : 0));
                    boolean leadingZeros = padTheIntegerPart || !withSeparator;
                    String integerPart = leadingZeros ? zeros + integerDigits : integerDigits;
                    String fractionPart = leadingZeros ? fractionDigits : fractionDigits + zeros;
                    String text = sign + integerPart + (withSeparator ? String.valueOf(separator) : "") + fractionPart;
                    return parsed(text, sign, integerPart, fractionPart);
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

    /** Valid decimals whose integer part is written with thousands separators (no-break or narrow no-break space). */
    public static Arbitrary<DecimalText> grouped() {
        return grouped(Arbitraries.of(GROUPING_SEPARATORS[0], GROUPING_SEPARATORS[1]));
    }

    /**
     * Decimals grouped like {@link #grouped()} but with at least one ordinary space between the groups: refused, since
     * a space separates the tokens of an expression (docs/adr/0004-regles-affichage.md, section 7).
     */
    public static Arbitrary<String> groupedWithOrdinarySpaces() {
        return grouped(Arbitraries.of(' ', ' ', NO_BREAK_SPACE, NARROW_NO_BREAK_SPACE))
                .map(DecimalText::text)
                .filter(text -> text.indexOf(' ') >= 0);
    }

    private static Arbitrary<DecimalText> grouped(Arbitrary<Character> groupSeparator) {
        Arbitrary<Integer> groups = Arbitraries.integers().between(1, 6);
        Arbitrary<String> head = Arbitraries.strings().withCharRange('0', '9').ofMinLength(1).ofMaxLength(3);
        Arbitrary<String> fraction = Arbitraries.strings().withCharRange('0', '9').ofMaxLength(12);
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
     * Texts that are certainly not a valid operand and not blank: a letter, a non-ASCII digit, an exponent without
     * digits, two decimal separators, a misplaced sign or fraction bar, or a lone sign or separator.
     */
    public static Arbitrary<String> invalidDecimals() {
        Arbitrary<String> valid = decimals(40).map(DecimalText::text);
        // Not e or E: inserted in a number, they make a valid scientific notation.
        Arbitrary<Character> letters = Arbitraries.chars().alpha().filter(letter -> letter != 'e' && letter != 'E');
        // Arabic-Indic, Extended Arabic-Indic, Devanagari, fullwidth and mathematical double-struck digits.
        Arbitrary<String> nonAsciiDigit = Arbitraries.of(0x0663, 0x06F5, 0x0967, 0xFF15, 0x1D7D8)
                .map(codePoint -> new String(Character.toChars(codePoint)));
        Arbitrary<String> letterInserted = Combinators.combine(valid, letters, Arbitraries.integers().between(0, 40))
                .as((text, letter, position) -> insert(text, letter.toString(), position));
        Arbitrary<String> digitInserted = Combinators.combine(valid, nonAsciiDigit, Arbitraries.integers().between(0, 40))
                .as(DecimalGenerators::insert);
        Arbitrary<String> exponent = Combinators.combine(valid, Arbitraries.of("e", "E", "e+", "E-", "e1,5", "e."))
                .as((text, marker) -> text + marker);
        Arbitrary<String> misplacedBar = Combinators.combine(valid, Arbitraries.of("/", "//", "/1/2", "/,"))
                .as((text, bar) -> text + bar);
        Arbitrary<String> twoSeparators = Combinators.combine(Arbitraries.of(".", ","), Arbitraries.of(".", ","))
                .as((first, second) -> "1" + first + "2" + second + "3");
        Arbitrary<String> trailingSign = valid.map(text -> text + "-");
        Arbitrary<String> doubledSign = Arbitraries.of("+-5", "--5", "++5", "-+5", "5+", "+ 5", "- 5");
        Arbitrary<String> lone = Arbitraries.of("+", "-", ".", ",", "+.", "-,", ".,", "..", ",,", "+-", "e", "e5", "/", "/2", "NaN", "Infinity");
        return Arbitraries.oneOf(letterInserted, digitInserted, exponent, misplacedBar, twoSeparators, trailingSign, doubledSign, lone);
    }

    /** Decimals in scientific notation ({@code -1,5e-7}, {@code 3E+2}), with an exponent small enough for any mantissa. */
    public static Arbitrary<DecimalText> scientific() {
        return Combinators.combine(decimals(30), Arbitraries.of("e", "E"), Arbitraries.of("", "+", "-"), Arbitraries.integers().between(0, 300))
                .as((mantissa, marker, sign, exponent) -> new DecimalText(
                        mantissa.text() + marker + sign + exponent,
                        mantissa.value().scaleByPowerOfTen("-".equals(sign) ? -exponent : exponent)));
    }

    /** A typed fraction and the value it stands for. */
    public record FractionText(String text, FractionOracle value) {
        @Override
        public String toString() {
            return "'" + text + "'";
        }
    }

    /** Fractions {@code p/q} of two decimals ({@code 1/3}, {@code -0,5/1,25}), the denominator not zero. */
    public static Arbitrary<FractionText> fractions() {
        return Combinators.combine(decimals(20), nonZero(decimals(20)))
                .as((top, bottom) -> new FractionText(top.text() + "/" + bottom.text(),
                        FractionOracle.of(top.value()).divide(FractionOracle.of(bottom.value()))));
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
