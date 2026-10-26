package calc.number;

import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.TextGenerators;
import java.util.EnumSet;
import java.util.Set;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Fuzzing of {@link Rational#parse(String)}: whatever the text, the answer is a canonical rational within the
 * component limit or a {@link NumericException} with a parsing code, never another exception.
 */
@Tag("robustness")
class RationalParseFuzzProperties {
    private static final Set<NumericError> PARSING_ERRORS = EnumSet.of(NumericError.MALFORMED_INPUT,
            NumericError.SIZE_LIMIT_EXCEEDED, NumericError.EXPONENT_OUT_OF_RANGE, NumericError.DIVISION_BY_ZERO);

    @Provide
    Arbitrary<String> anyText() {
        return TextGenerators.anyInput().filter(text -> text != null);
    }

    @Property
    void anyTextGivesARationalOrAParsingError(@ForAll("anyText") String text) {
        try {
            Rational value = Rational.parse(text);
            RationalProperties.assertCanonical(value);
            assertTrue(value.bitLength() <= NumericLimits.MAX_COMPONENT_BITS, () -> "too large: " + value.bitLength());
        } catch (NumericException refused) {
            assertTrue(PARSING_ERRORS.contains(refused.error()), () -> "unexpected code " + refused.error());
        }
    }
}
