package calc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.web.compat.PatternCompat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * The complement of {@code Pattern.compile} changes how a regular expression is spelled, never what it accepts or what its
 * groups capture. The JVM is the reference. Under Node, line 63 of the golden file is the case that fails without it.
 */
@Tag("property")
class PatternCompatPropertyTest {
    private static final String SEPARATORS = "[  ]";
    /** The regular expression of NumberLiterals, the only one of the core with a counted class inside a repeated group. */
    private static final String GROUPED_MEMBER =
            "([+-]?)(\\d{1,3}(?:" + SEPARATORS + "\\d{3})+)((?:[.,]\\d*)?(?:[eE][+-]?\\d+)?)";

    @Provide
    Arbitrary<String> groupedNumbers() {
        Arbitrary<String> digits = Arbitraries.strings().numeric().ofMinLength(1).ofMaxLength(4);
        Arbitrary<String> separator = Arbitraries.of(" ", " ", " ", "");
        Arbitrary<String> group = Combinators.combine(separator, digits).as(String::concat);
        Arbitrary<String> groups = group.list().ofMaxSize(5).map(list -> String.join("", list));
        Arbitrary<String> tail = Arbitraries.of("", ",5", ".25", ",", "e3", "E-2", ",5e+1", "x");
        return Combinators.combine(Arbitraries.of("", "+", "-"), digits, groups, tail)
                .as((sign, first, middle, end) -> sign + first + middle + end);
    }

    @Property
    void acceptsAndCapturesExactlyWhatTheJdkDoes(@ForAll("groupedNumbers") String input) {
        Matcher jdk = Pattern.compile(GROUPED_MEMBER).matcher(input);
        Matcher complement = PatternCompat.compile(GROUPED_MEMBER).matcher(input);

        boolean matches = jdk.matches();
        assertEquals(matches, complement.matches());
        if (matches) {
            for (int group = 1; group <= jdk.groupCount(); group++) {
                assertEquals(jdk.group(group), complement.group(group));
            }
        }
    }

    @Example
    void spellsOutTheCountedDigitsTeaVmGetsWrong() {
        assertEquals("(?:a\\d\\d\\d)+", PatternCompat.compile("(?:a\\d{3})+").pattern());
    }

    @Example
    void leavesEveryOtherRegularExpressionUnchanged() {
        String decimal = "([+-]?)([0-9]*)(?:[.,]([0-9]*))?(?:[eE]([+-]?)([0-9]+))?";
        assertEquals(decimal, PatternCompat.compile(decimal).pattern());
        assertEquals("\\d{1,3}", PatternCompat.compile("\\d{1,3}").pattern());
    }
}
