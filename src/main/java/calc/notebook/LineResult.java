package calc.notebook;

import calc.expression.ExpressionException;
import calc.expression.ExpressionParser;
import calc.expression.ParsedLine;
import calc.number.CertifiedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A line as evaluated in its notebook. An expression or an assignment has a value or an error; an empty or comment line
 * has neither. A line that does not parse is an expression in error, or an assignment in error when it starts with
 * {@code name =} (it still defines its name, docs/adr/0008-semantique-carnet.md, section 2).
 *
 * @param number      the number shown, from 1
 * @param id          the stable identifier of the line
 * @param text        the source text
 * @param kind        empty, comment, expression or assignment
 * @param value       the value, {@code null} without one
 * @param error       the error, {@code null} without one
 * @param definedName the name assigned by the line, {@code null} if it is not an assignment
 * @param references  the lines above designated by the {@code $n} of the line, in source order, without repetition;
 *                    empty for a line that does not parse
 * @param sources     the variables, {@code $n} and {@code ans} of the line that designate a line above, in the order of
 *                    their first appearance in the text, without repetition, even when the line is in error; empty for an
 *                    empty or comment line and for a line that does not parse
 */
public record LineResult(int number, LineId id, String text, ParsedLine.Kind kind, CertifiedValue value, LineError error,
                         String definedName, List<LineId> references, List<Source> sources) {
    public LineResult {
        if (number < 1) {
            throw new IllegalArgumentException("line numbers start at 1: " + number);
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
        boolean computes = computes(kind);
        if (computes ? (value == null) == (error == null) : value != null || error != null) {
            throw new IllegalArgumentException(kind + " with value " + value + " and error " + error);
        }
        if ((definedName != null) != (kind == ParsedLine.Kind.ASSIGNMENT)) {
            throw new IllegalArgumentException(kind + " with the name " + definedName);
        }
        references = List.copyOf(references);
        sources = List.copyOf(sources);
        if (!computes && !sources.isEmpty()) {
            throw new IllegalArgumentException(kind + " with the sources " + sources);
        }
    }

    /**
     * A result whose sources are its references, as written before the sources existed: each one a
     * {@link Source.Kind#LINE_REFERENCE} labelled with its {@code $n} of the text, or {@code $?} when the text does not
     * name it. An empty or comment line has no source.
     */
    public LineResult(int number, LineId id, String text, ParsedLine.Kind kind, CertifiedValue value, LineError error,
                      String definedName, List<LineId> references) {
        this(number, id, text, kind, value, error, definedName, references,
                computes(kind) ? referenceSources(text, number, references) : List.of());
    }

    private static boolean computes(ParsedLine.Kind kind) {
        return kind == ParsedLine.Kind.EXPRESSION || kind == ParsedLine.Kind.ASSIGNMENT;
    }

    // The references in the order of the distinct "$n" above the line, as the evaluator lists them.
    private static List<Source> referenceSources(String text, int number, List<LineId> references) {
        List<Integer> lines = new ArrayList<>();
        try {
            for (int line : ExpressionParser.parseLine(text).lineReferences()) {
                if (line < number && !lines.contains(line)) {
                    lines.add(line);
                }
            }
        } catch (ExpressionException unreadable) {
            // No number to show: every label is "$?".
        }
        List<Source> sources = new ArrayList<>(references.size());
        for (int index = 0; index < references.size(); index++) {
            String label = index < lines.size() ? "$" + lines.get(index) : "$?";
            sources.add(new Source(Source.Kind.LINE_REFERENCE, label, references.get(index)));
        }
        return sources;
    }

    /** The value of an expression or an assignment evaluated without error. */
    public Optional<CertifiedValue> result() {
        return Optional.ofNullable(value);
    }

    /** The error of an expression or an assignment that has no value. */
    public Optional<LineError> failure() {
        return Optional.ofNullable(error);
    }

    /** The name assigned by an assignment, even one in error. */
    public Optional<String> assignedName() {
        return Optional.ofNullable(definedName);
    }

    public boolean hasValue() {
        return value != null;
    }

    public boolean isError() {
        return error != null;
    }
}
