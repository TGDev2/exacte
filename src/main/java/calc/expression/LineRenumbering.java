package calc.expression;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;

/** How the lines of a notebook were renumbered: the new number of each old line, or nothing if it was deleted. */
@FunctionalInterface
public interface LineRenumbering {
    /** The new number (at least 1) of the old line {@code line}, or empty if the line was deleted. */
    OptionalInt newNumber(int line);

    /** Nothing moves. */
    static LineRenumbering identity() {
        return OptionalInt::of;
    }

    /**
     * A table: {@code moved} gives new numbers ({@code 3 -> 4}), {@code deleted} the lines removed; every other line
     * keeps its number.
     */
    static LineRenumbering table(Map<Integer, Integer> moved, Set<Integer> deleted) {
        Map<Integer, Integer> moves = new HashMap<>(moved);
        Set<Integer> removed = Set.copyOf(deleted);
        moves.forEach((from, to) -> {
            if (from < 1 || to < 1) {
                throw new IllegalArgumentException("line numbers start at 1: " + from + " -> " + to);
            }
            if (removed.contains(from)) {
                throw new IllegalArgumentException("line " + from + " is both moved and deleted");
            }
        });
        return line -> removed.contains(line) ? OptionalInt.empty()
                : OptionalInt.of(Objects.requireNonNullElse(moves.get(line), line));
    }
}
