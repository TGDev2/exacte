package calc.format;

import java.util.Objects;
import java.util.Optional;

/**
 * A value as {@link ValueFormatter} shows it in one {@link DisplayMode}.
 *
 * @param text             the main text, for instance {@code 0,333333333333…} or {@code ≈ 1,41421356237…}
 * @param exactFraction    {@code p/q} for an exact value that is not an integer, empty otherwise
 * @param exact            whether the value is exact (otherwise it is only known within an interval)
 * @param truncated        whether the main text leaves out part of the value (always the case for an approximate value)
 * @param notationSwitched whether the requested notation could not be used and another one was chosen
 * @param errorBound       {@code ± r} for an approximate value, the bound of the error around its centre; empty if exact
 * @param canonical        a text read back by the input grammar; for an exact value it gives back exactly that value
 */
public record FormattedValue(
        String text,
        Optional<String> exactFraction,
        boolean exact,
        boolean truncated,
        boolean notationSwitched,
        Optional<String> errorBound,
        String canonical) {

    public FormattedValue {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(exactFraction, "exactFraction");
        Objects.requireNonNull(errorBound, "errorBound");
        Objects.requireNonNull(canonical, "canonical");
    }
}
