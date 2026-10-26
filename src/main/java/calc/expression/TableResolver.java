package calc.expression;

import calc.number.CertifiedValue;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An immutable {@link Resolver} backed by tables, for the tests and for simple callers: what is not in a table is
 * {@link Resolution.Reason#UNKNOWN}.
 */
public final class TableResolver implements Resolver {
    private static final TableResolver EMPTY = new TableResolver(Map.of(), Map.of(), Resolution.unknown());

    private final Map<String, Resolution> variables;
    private final Map<Integer, Resolution> lines;
    private final Resolution ans;

    private TableResolver(Map<String, Resolution> variables, Map<Integer, Resolution> lines, Resolution ans) {
        this.variables = variables;
        this.lines = lines;
        this.ans = ans;
    }

    /** Knows nothing. */
    public static TableResolver empty() {
        return EMPTY;
    }

    public TableResolver withVariable(String name, CertifiedValue value) {
        return withVariable(name, Resolution.of(value));
    }

    public TableResolver withVariable(String name, Resolution resolution) {
        Objects.requireNonNull(name, "name");
        Map<String, Resolution> copy = new HashMap<>(variables);
        copy.put(name, Objects.requireNonNull(resolution, "resolution"));
        return new TableResolver(Map.copyOf(copy), lines, ans);
    }

    public TableResolver withAns(CertifiedValue value) {
        return withAns(Resolution.of(value));
    }

    public TableResolver withAns(Resolution resolution) {
        return new TableResolver(variables, lines, Objects.requireNonNull(resolution, "resolution"));
    }

    public TableResolver withLine(int number, CertifiedValue value) {
        return withLine(number, Resolution.of(value));
    }

    public TableResolver withLine(int number, Resolution resolution) {
        if (number < 1) {
            throw new IllegalArgumentException("line numbers start at 1: " + number);
        }
        Map<Integer, Resolution> copy = new HashMap<>(lines);
        copy.put(number, Objects.requireNonNull(resolution, "resolution"));
        return new TableResolver(variables, Map.copyOf(copy), ans);
    }

    @Override
    public Resolution variable(String name) {
        return variables.getOrDefault(name, Resolution.unknown());
    }

    @Override
    public Resolution ans() {
        return ans;
    }

    @Override
    public Resolution line(int number) {
        return lines.getOrDefault(number, Resolution.unknown());
    }
}
