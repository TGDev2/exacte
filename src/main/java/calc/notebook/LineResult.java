package calc.notebook;

import calc.expression.ParsedLine;
import calc.number.CertifiedValue;
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
 * @param references  the lines above designated by the {@code $n} of the line, in source order, without repetition
 */
public record LineResult(int number, LineId id, String text, ParsedLine.Kind kind, CertifiedValue value, LineError error,
                         String definedName, List<LineId> references) {
    public LineResult {
        if (number < 1) {
            throw new IllegalArgumentException("line numbers start at 1: " + number);
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(kind, "kind");
        boolean computes = kind == ParsedLine.Kind.EXPRESSION || kind == ParsedLine.Kind.ASSIGNMENT;
        if (computes ? (value == null) == (error == null) : value != null || error != null) {
            throw new IllegalArgumentException(kind + " with value " + value + " and error " + error);
        }
        if ((definedName != null) != (kind == ParsedLine.Kind.ASSIGNMENT)) {
            throw new IllegalArgumentException(kind + " with the name " + definedName);
        }
        references = List.copyOf(references);
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
