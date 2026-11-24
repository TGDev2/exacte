package calc.notebook;

import calc.engine.NumberLiterals;
import calc.expression.Evaluator;
import calc.expression.ExpressionError;
import calc.expression.ExpressionException;
import calc.expression.ExpressionParser;
import calc.expression.Node;
import calc.expression.ParsedLine;
import calc.expression.Resolution;
import calc.expression.Resolver;
import calc.number.CertifiedValue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates the lines of a notebook from top to bottom (docs/adr/0008-semantique-carnet.md, sections 2, 3 and 7): each
 * line is read by {@link ExpressionParser} and evaluated by {@link Evaluator} with a {@link Resolver} built from the
 * results above it, so references only go backwards and no cycle can exist. The result of a line depends only on the
 * lines above it and on itself, so after a change the results are kept up to the first line that changed.
 */
public final class NotebookEvaluator {
    // The blanks of the grammar (ADR 0007).
    private static final String BLANKS = " \t" + NumberLiterals.NO_BREAK_SPACE + NumberLiterals.NARROW_NO_BREAK_SPACE;
    // The start of a line that tries to assign a name: what is before the first "=" must still read as a variable.
    private static final Pattern ASSIGNMENT_HEAD =
            Pattern.compile("[" + BLANKS + "]*([^=#" + BLANKS + "]+)[" + BLANKS + "]*=");

    private NotebookEvaluator() {
    }

    /** Every line of {@code notebook}, evaluated. */
    public static EvaluatedNotebook evaluate(Notebook notebook) {
        return reevaluate(new EvaluatedNotebook(Notebook.empty(), List.of()), notebook);
    }

    /**
     * The lines of {@code next}, evaluated, reusing the results of {@code previous} above the first line whose identifier
     * or text differs: the same as {@link #evaluate(Notebook)}, for the cost of the lines from that one down.
     */
    public static EvaluatedNotebook reevaluate(EvaluatedNotebook previous, Notebook next) {
        LineByLine evaluation = lineByLine(next, reusableResults(previous.notebook(), previous.results(), next));
        while (!evaluation.isFinished()) {
            evaluation.evaluateNext();
        }
        return new EvaluatedNotebook(next, evaluation.scope.results);
    }

    /**
     * The first {@code known} results of the lines of {@code before} that still hold for {@code next}: those above the first
     * line whose identifier or text differs, since the result of a line only depends on the lines above it and on itself.
     */
    public static List<LineResult> reusableResults(Notebook before, List<LineResult> known, Notebook next) {
        List<Line> old = before.lines();
        List<Line> now = next.lines();
        int common = 0;
        int shortest = Math.min(known.size(), Math.min(old.size(), now.size()));
        while (common < shortest && old.get(common).equals(now.get(common))) {
            common++;
        }
        return known.subList(0, common);
    }

    /** The lines of {@code notebook} under the results {@code kept}, to evaluate one at a time. */
    public static LineByLine lineByLine(Notebook notebook, List<LineResult> kept) {
        return new LineByLine(notebook, kept);
    }

    /**
     * An evaluation that stops between two lines when its caller wants: docs/adr/0014-calcul-hors-interface.md. Its results are
     * those of {@link #evaluate(Notebook)}. It belongs to one thread at a time.
     */
    public static final class LineByLine {
        private final List<Line> lines;
        private final Scope scope;

        private LineByLine(Notebook notebook, List<LineResult> kept) {
            lines = notebook.lines();
            scope = new Scope(lines.size());
            kept.forEach(scope::add);
        }

        public boolean isFinished() {
            return scope.results.size() == lines.size();
        }

        /** The text of the next line to evaluate. */
        public String nextText() {
            return lines.get(scope.results.size()).text();
        }

        /** Evaluates the next line and returns its result. */
        public LineResult evaluateNext() {
            int index = scope.results.size();
            LineResult result = evaluateLine(lines.get(index), index + 1, scope);
            scope.add(result);
            return result;
        }
    }

    private static LineResult evaluateLine(Line line, int number, Scope scope) {
        ParsedLine parsed;
        try {
            parsed = ExpressionParser.parseLine(line.text());
        } catch (ExpressionException refused) {
            String name = attemptedAssignment(line.text()).orElse(null);
            ParsedLine.Kind kind = name == null ? ParsedLine.Kind.EXPRESSION : ParsedLine.Kind.ASSIGNMENT;
            return new LineResult(number, line.id(), line.text(), kind, null, LineError.of(refused), name, List.of(),
                    List.of());
        }
        if (parsed.node() == null) {
            return new LineResult(number, line.id(), line.text(), parsed.kind(), null, null, null, List.of(), List.of());
        }
        Set<LineId> references = new LinkedHashSet<>();
        for (int reference : parsed.lineReferences()) {
            if (reference < number) {
                references.add(scope.results.get(reference - 1).id());
            }
        }
        String name = parsed.assignedName().orElse(null);
        LineResolver resolver = scope.resolverFor(number);
        List<Source> sources = resolver.sources(parsed, line.text());
        try {
            CertifiedValue value = Evaluator.evaluate(parsed.node(), resolver);
            return new LineResult(number, line.id(), line.text(), parsed.kind(), value, null, name, List.copyOf(references),
                    sources);
        } catch (ExpressionException refused) {
            return new LineResult(number, line.id(), line.text(), parsed.kind(), null, resolver.explain(refused), name,
                    List.copyOf(references), sources);
        }
    }

    /** The name a line in error tries to assign ({@code x = 2 +}), if what comes before {@code =} reads as a variable. */
    static Optional<String> attemptedAssignment(String text) {
        Matcher head = ASSIGNMENT_HEAD.matcher(text);
        if (!head.lookingAt()) {
            return Optional.empty();
        }
        try {
            // The name alone, as the parser requires it: "(x) =" is not an assignment.
            return ExpressionParser.parseLine(head.group(1)).node() instanceof Node.Variable variable
                    && variable.name().equals(head.group(1))
                    ? Optional.of(variable.name())
                    : Optional.empty();
        } catch (ExpressionException notAName) {
            return Optional.empty();
        }
    }

    /**
     * The label of a variable, {@code ans} or {@code $n} as written in {@code text}: the name of a variable, the token
     * itself for the others. The span of a leaf written between parentheses covers them and the blanks inside
     * ({@code ( $1 )}), which are not part of the label: {@code (ans)} and {@code ans} name the same source.
     */
    static String label(String text, Node leaf) {
        if (leaf instanceof Node.Variable variable) {
            return variable.name();
        }
        int start = leaf.span().start();
        int end = leaf.span().end();
        // The token itself holds no parenthesis and no blank.
        while (aroundToken(text.charAt(start))) {
            start++;
        }
        while (aroundToken(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean aroundToken(char c) {
        return c == '(' || c == ')' || BLANKS.indexOf(c) >= 0;
    }

    /** What the lines evaluated so far make visible to the next one. */
    private static final class Scope {
        private final List<LineResult> results;
        // The index of the closest definition of each name, and of the closest line that has a value.
        private final Map<String, Integer> definitions = new HashMap<>();
        private int lastValue = -1;

        Scope(int lines) {
            this.results = new ArrayList<>(lines);
        }

        void add(LineResult result) {
            int index = results.size();
            results.add(result);
            if (result.definedName() != null) {
                definitions.put(result.definedName(), index);
            }
            if (result.hasValue()) {
                lastValue = index;
            }
        }

        LineResolver resolverFor(int number) {
            return new LineResolver(this, number);
        }
    }

    /**
     * Resolves the names and references of the line {@code number} against the lines above it, and words its failures
     * itself: the evaluator stops at the first failure, so the last one recorded is the one it reports.
     */
    private static final class LineResolver implements Resolver {
        private final Scope scope;
        private final int number;
        private String failure;

        LineResolver(Scope scope, int number) {
            this.scope = scope;
            this.number = number;
        }

        /**
         * The sources of the line: each variable, {@code ans} and {@code $n} of {@code parsed} that designates a line above,
         * as this resolver would resolve it, in the order of the text and without repetition. They are read from the tree,
         * not from the evaluation, which stops at the first failure: a line in error still lists every line it names. A
         * name whose line is in error is a source too.
         */
        List<Source> sources(ParsedLine parsed, String text) {
            if (parsed.variables().isEmpty() && parsed.lineReferences().isEmpty() && !parsed.usesAns()) {
                return List.of();
            }
            List<Source> sources = new ArrayList<>();
            Deque<Node> nodes = new ArrayDeque<>();
            nodes.push(parsed.node());
            // The left operand is taken before the right one, so the leaves come in the order of the text.
            while (!nodes.isEmpty()) {
                Source source = null;
                switch (nodes.pop()) {
                    case Node.Variable variable -> {
                        Integer index = scope.definitions.get(variable.name());
                        if (index != null) {
                            source = new Source(Source.Kind.VARIABLE, variable.name(), scope.results.get(index).id());
                        }
                    }
                    case Node.Ans ans -> {
                        if (scope.lastValue >= 0) {
                            source = new Source(Source.Kind.ANS, label(text, ans), scope.results.get(scope.lastValue).id());
                        }
                    }
                    case Node.LineRef reference -> {
                        if (reference.line() < number && hasValueOrError(scope.results.get(reference.line() - 1))) {
                            source = new Source(Source.Kind.LINE_REFERENCE, label(text, reference),
                                    scope.results.get(reference.line() - 1).id());
                        }
                    }
                    case Node.Unary unary -> nodes.push(unary.operand());
                    case Node.Binary binary -> {
                        nodes.push(binary.right());
                        nodes.push(binary.left());
                    }
                    case Node.Sqrt sqrt -> nodes.push(sqrt.operand());
                    case Node.Assignment assignment -> nodes.push(assignment.value());
                    case Node.NumberLiteral literal -> {
                        // A number names no line.
                    }
                }
                if (source != null && !sources.contains(source)) {
                    sources.add(source);
                }
            }
            return sources;
        }

        // An expression or an assignment, whether it has a value or an error: what $n can designate.
        private static boolean hasValueOrError(LineResult target) {
            return target.kind() == ParsedLine.Kind.EXPRESSION || target.kind() == ParsedLine.Kind.ASSIGNMENT;
        }

        @Override
        public Resolution variable(String name) {
            Integer index = scope.definitions.get(name);
            if (index == null) {
                return fail(Resolution.unknown(), "Variable inconnue « " + name + " ».");
            }
            return valueOf(index);
        }

        @Override
        public Resolution ans() {
            if (scope.lastValue < 0) {
                return fail(Resolution.unknown(), "Aucun résultat précédent : « ans » n'a pas de valeur.");
            }
            return Resolution.of(scope.results.get(scope.lastValue).value());
        }

        @Override
        public Resolution line(int line) {
            if (line >= number) {
                return fail(Resolution.invalid(), "Référence vers une ligne qui n'est pas au-dessus (ligne " + line + ").");
            }
            LineResult target = scope.results.get(line - 1);
            return switch (target.kind()) {
                case EMPTY -> fail(Resolution.unknown(), "La ligne " + line + " est vide : elle n'a pas de valeur.");
                case COMMENT -> fail(Resolution.unknown(),
                        "La ligne " + line + " est un commentaire : elle n'a pas de valeur.");
                case EXPRESSION, ASSIGNMENT -> valueOf(line - 1);
            };
        }

        private Resolution valueOf(int index) {
            LineResult target = scope.results.get(index);
            if (target.hasValue()) {
                return Resolution.of(target.value());
            }
            return fail(Resolution.inError(), "Dépend de la ligne " + (index + 1) + ", qui est en erreur.");
        }

        private Resolution fail(Resolution resolution, String message) {
            failure = message;
            return resolution;
        }

        LineError explain(ExpressionException refused) {
            boolean resolution = refused.error() == ExpressionError.UNKNOWN_NAME
                    || refused.error() == ExpressionError.REFERENCE_IN_ERROR
                    || refused.error() == ExpressionError.INVALID_USE;
            if (resolution && failure != null) {
                return new LineError(refused.error(), refused.span(), failure);
            }
            return LineError.of(refused);
        }
    }
}
