package calc.expression;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a line says, as read by {@link ExpressionParser}: its kind, its tree, its comment and what it depends on (the
 * notebook builds its dependency graph from {@link #lineReferences()}, {@link #variables()} and {@link #usesAns()}).
 *
 * @param kind           empty, comment only, expression or assignment
 * @param node           the tree: an {@link Node.Assignment} for an assignment, the expression otherwise; {@code null}
 *                       for an empty or comment line
 * @param comment        the text after {@code #}, {@code null} without a comment
 * @param lineReferences the line numbers of the {@code $n} references, in source order, repeated as often as written
 * @param variables      the variables read by the line, in order of first use (not the assigned name)
 * @param usesAns        whether {@code ans} appears
 */
public record ParsedLine(Kind kind, Node node, String comment, List<Integer> lineReferences, Set<String> variables,
                         boolean usesAns) {
    public enum Kind {
        EMPTY,
        COMMENT,
        EXPRESSION,
        ASSIGNMENT
    }

    public ParsedLine {
        Objects.requireNonNull(kind, "kind");
        if ((node == null) != (kind == Kind.EMPTY || kind == Kind.COMMENT)
                || (kind == Kind.ASSIGNMENT) != (node instanceof Node.Assignment)) {
            throw new IllegalArgumentException(kind + " does not match " + node);
        }
        if ((kind == Kind.COMMENT) && comment == null) {
            throw new IllegalArgumentException("a comment line needs its comment");
        }
        lineReferences = List.copyOf(lineReferences);
        variables = Collections.unmodifiableSet(new LinkedHashSet<>(variables));
    }

    /** The tree, absent for an empty or comment line. */
    public Optional<Node> tree() {
        return Optional.ofNullable(node);
    }

    /** The name assigned by an assignment. */
    public Optional<String> assignedName() {
        return node instanceof Node.Assignment assignment ? Optional.of(assignment.name()) : Optional.empty();
    }

    /** The text after {@code #}. */
    public Optional<String> commentText() {
        return Optional.ofNullable(comment);
    }
}
