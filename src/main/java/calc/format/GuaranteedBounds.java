package calc.format;

import java.util.Objects;

/**
 * The interval of an approximate value as two texts that can be read, rounded outwards: the true value lies between
 * {@code lower} and {@code upper}, both included. See {@link ValueFormatter#guaranteedBounds}.
 *
 * @param lower the lower bound rounded down, for instance {@code 1,41421356237}
 * @param upper the upper bound rounded up, for instance {@code 1,41421356238}
 */
public record GuaranteedBounds(String lower, String upper) {

    public GuaranteedBounds {
        Objects.requireNonNull(lower, "lower");
        Objects.requireNonNull(upper, "upper");
    }
}
