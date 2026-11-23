package calc.notebook;

import static calc.notebook.NotebookTestSupport.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.Evaluator;
import calc.expression.ExpressionException;
import calc.expression.ExpressionLimits;
import calc.expression.ExpressionParser;
import calc.expression.Node;
import calc.expression.ParsedLine;
import calc.expression.Resolution;
import calc.expression.Resolver;
import calc.expression.Span;
import calc.number.CertifiedValue;
import calc.testing.NotebookGenerators;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

/**
 * The sources of every line, the lines that use each line and the one-level expansion of a line (task E06, SPEC 4.3,
 * ADR-D): {@link LineResult#sources()}, {@link EvaluatedNotebook#usedBy(LineId)} and
 * {@link LineExpansion#expand(EvaluatedNotebook, LineId)}.
 */
@Tag("acceptance")
@Tag("property")
class NotebookSourcesAcceptanceTest {
    // The "Devis cuisine" notebook of the README, line for line, comments and no-break spaces included.
    private static final String[] QUOTE = {
        "# Devis cuisine",
        "plan = 1 250,00          # plan de travail (espace insécable)",
        "meubles = 3 480",
        "pose = 35 * 12,5",
        "total = plan + meubles + pose     # 5 167,5",
        "taux = 0,2",
        "total * (1 + taux)       # TTC : 6 201",
        "ans / 12                 # mensualité sur un an : 516,75",
    };

    // The parser of the expansions: long lines allowed, no name or reference left to resolve.
    private static final ExpressionParser EXPANSION_PARSER = new ExpressionParser(new ExpressionLimits(100_000, 100, 500));

    private static final Resolver NOTHING = new Resolver() {
        @Override
        public Resolution variable(String name) {
            return Resolution.unknown();
        }

        @Override
        public Resolution ans() {
            return Resolution.unknown();
        }

        @Override
        public Resolution line(int number) {
            return Resolution.unknown();
        }
    };

    // --- Sources ---------------------------------------------------------------------------------------------------

    @Example
    void theQuoteNotebookNamesTheSourcesOfItsLines() {
        EvaluatedNotebook quote = evaluated(QUOTE);

        assertEquals(List.of(variable("plan", id(quote, 2)), variable("meubles", id(quote, 3)), variable("pose", id(quote, 4))),
                quote.result(5).sources());
        assertEquals(List.of(variable("total", id(quote, 5)), variable("taux", id(quote, 6))), quote.result(7).sources());
        assertEquals(List.of(ans(id(quote, 7))), quote.result(8).sources());
        for (int number : new int[] {1, 2, 3, 4, 6}) {
            assertEquals(List.of(), quote.result(number).sources(), quote.result(number).text());
        }
    }

    @Example
    void sourcesFollowTheTextInOrderOfFirstAppearanceWithoutRepetition() {
        EvaluatedNotebook notebook = evaluated("a = 2", "b = 3", "4", "b * a + $3 - a + ans + b + $3",
                "-(ans ^ b) / (a + $1)");

        assertEquals(List.of(variable("b", id(notebook, 2)), variable("a", id(notebook, 1)), reference("$3", id(notebook, 3)),
                ans(id(notebook, 3))), notebook.result(4).sources());
        assertEquals(List.of(ans(id(notebook, 4)), variable("b", id(notebook, 2)), variable("a", id(notebook, 1)),
                reference("$1", id(notebook, 1))), notebook.result(5).sources());
    }

    @Example
    void theLabelIsTheTextAsWritten() {
        EvaluatedNotebook notebook = evaluated("5", "ANS * $01 + 1", "Ans - $2");

        assertEquals(List.of(new Source(Source.Kind.ANS, "ANS", id(notebook, 1)), reference("$01", id(notebook, 1))),
                notebook.result(2).sources());
        assertEquals(List.of(new Source(Source.Kind.ANS, "Ans", id(notebook, 2)), reference("$2", id(notebook, 2))),
                notebook.result(3).sources());
    }

    @Example
    void aRedefinedVariableComesFromTheClosestDefinitionAbove() {
        EvaluatedNotebook notebook = evaluated("x = 2", "x = x + 1", "x * 10", "y = x", "x = 7", "x + y");

        assertEquals(List.of(variable("x", id(notebook, 1))), notebook.result(2).sources());
        assertEquals(List.of(variable("x", id(notebook, 2))), notebook.result(3).sources());
        assertEquals(List.of(variable("x", id(notebook, 2))), notebook.result(4).sources());
        assertEquals(List.of(variable("x", id(notebook, 5)), variable("y", id(notebook, 4))), notebook.result(6).sources());
    }

    @Example
    void aReferenceComesFromTheLineItNames() {
        EvaluatedNotebook notebook = evaluated("10", "# commentaire", "$1 * 2 + $1", "$3 - $1");

        assertEquals(List.of(reference("$1", id(notebook, 1))), notebook.result(3).sources());
        assertEquals(List.of(reference("$3", id(notebook, 3)), reference("$1", id(notebook, 1))), notebook.result(4).sources());
    }

    @Example
    void ansComesFromTheClosestLineAboveThatHasAValue() {
        EvaluatedNotebook notebook = evaluated("5", "1 / 0", "# note", "", "ans + 1");

        assertEquals(List.of(ans(id(notebook, 1))), notebook.result(5).sources());
    }

    @Example
    void anUnresolvedNameOrReferenceIsNotListed() {
        EvaluatedNotebook notebook = evaluated("ans + 1", "", "# total", "$2 + 1", "1 + $3", "inconnue * 2", "$8 - 1", "3");

        for (int number = 1; number <= 7; number++) {
            assertTrue(notebook.result(number).kind() == ParsedLine.Kind.EMPTY || notebook.result(number).kind() == ParsedLine.Kind.COMMENT
                    || notebook.result(number).isError(), notebook.result(number).text());
            assertEquals(List.of(), notebook.result(number).sources(), notebook.result(number).text());
        }
        // A resolved source is listed next to an unresolved one.
        EvaluatedNotebook mixed = evaluated("# total", "5", "$1 + $2 + inconnue");
        assertEquals(List.of(reference("$2", id(mixed, 2))), mixed.result(3).sources());
    }

    @Example
    void anEmptyACommentOrAnUnparsedLineHasNoSource() {
        EvaluatedNotebook notebook = evaluated("a = 1", "", "# a + $1 + ans", "a +", "b = a *", "a a", "(a + $1", "2 + a");

        for (int number = 4; number <= 7; number++) {
            String text = notebook.result(number).text();
            assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine(text), text);
        }
        for (int number = 2; number <= 7; number++) {
            assertEquals(List.of(), notebook.result(number).sources(), notebook.result(number).text());
        }
        assertEquals(List.of(id(notebook, 8)), notebook.usedBy(id(notebook, 1)));
    }

    @Example
    void aLineInEvaluationErrorListsEveryResolvedSourceWhateverItsPlace() {
        for (String line : List.of("total - remise", "remise + total")) {
            EvaluatedNotebook quote = evaluated(withLine(line));

            assertTrue(quote.result(9).isError(), line);
            assertEquals(List.of(variable("total", id(quote, 5))), quote.result(9).sources(), line);
            assertEquals(List.of(id(quote, 7), id(quote, 9)), quote.usedBy(id(quote, 5)), line);
        }
        EvaluatedNotebook divided = evaluated(withLine("1 / (pose - pose) + plan"));
        assertTrue(divided.result(9).isError());
        assertEquals(List.of(variable("pose", id(divided, 4)), variable("plan", id(divided, 2))), divided.result(9).sources());
        assertEquals(List.of(id(divided, 5), id(divided, 9)), divided.usedBy(id(divided, 4)));
        assertEquals(List.of(id(divided, 5), id(divided, 9)), divided.usedBy(id(divided, 2)));
    }

    // --- LineResult constructors -----------------------------------------------------------------------------------

    @Example
    void theConstructorWithoutSourcesStillExistsAndDerivesThemFromTheReferences() {
        LineId first = new LineId(1);
        LineId second = new LineId(2);

        LineResult result = new LineResult(3, new LineId(3), "$1 + $2", ParsedLine.Kind.EXPRESSION, exact("3"), null, null,
                List.of(first, second));

        assertEquals(List.of(first, second), result.references());
        for (Source source : result.sources()) {
            assertEquals(Source.Kind.LINE_REFERENCE, source.kind(), source::toString);
        }
        assertEquals(List.of(first, second), result.sources().stream().map(Source::target).toList());
        assertEquals(List.of(), new LineResult(1, new LineId(4), "# note", ParsedLine.Kind.COMMENT, null, null, null, List.of())
                .sources());
    }

    @Example
    void theCanonicalConstructorKeepsTheSourcesOfALineThatComputes() {
        List<Source> sources = List.of(variable("a", new LineId(1)), reference("$2", new LineId(2)), ans(new LineId(2)));

        LineResult result = new LineResult(3, new LineId(3), "a + $2 + ans", ParsedLine.Kind.EXPRESSION, exact("6"), null, null,
                List.of(new LineId(2)), sources);

        assertEquals(sources, result.sources());
        assertThrows(IllegalArgumentException.class, () -> new LineResult(1, new LineId(5), "# a", ParsedLine.Kind.COMMENT, null,
                null, null, List.of(), List.of(variable("a", new LineId(1)))));
        assertThrows(IllegalArgumentException.class, () -> new LineResult(1, new LineId(5), "", ParsedLine.Kind.EMPTY, null,
                null, null, List.of(), List.of(ans(new LineId(1)))));
    }

    // --- usedBy ----------------------------------------------------------------------------------------------------

    @Example
    void theQuoteNotebookKnowsWhichLinesUseEachLine() {
        EvaluatedNotebook quote = evaluated(QUOTE);

        assertEquals(List.of(id(quote, 7)), quote.usedBy(id(quote, 5)));
        assertEquals(List.of(id(quote, 8)), quote.usedBy(id(quote, 7)));
        assertEquals(List.of(id(quote, 5)), quote.usedBy(id(quote, 2)));
        assertEquals(List.of(id(quote, 7)), quote.usedBy(id(quote, 6)));
        assertEquals(List.of(), quote.usedBy(id(quote, 8)));
        assertEquals(List.of(), quote.usedBy(id(quote, 1)));
    }

    @Example
    void askingForTheUsersKeepsTheEvaluatedNotebookEqualToAnother() {
        Notebook notebook = NotebookTestSupport.notebook(QUOTE);
        EvaluatedNotebook asked = NotebookEvaluator.evaluate(notebook);
        EvaluatedNotebook untouched = NotebookEvaluator.evaluate(notebook);

        assertEquals(List.of(id(asked, 7)), asked.usedBy(id(asked, 5)));
        assertEquals(List.of(id(asked, 7)), asked.usedBy(id(asked, 5)));

        assertEquals(untouched, asked);
        assertEquals(asked, untouched);
        assertEquals(untouched.hashCode(), asked.hashCode());
    }

    @Example
    void incrementalReevaluationGivesTheSourcesOfAFullEvaluation() throws NotebookException {
        EvaluatedNotebook before = evaluated(QUOTE);
        assertEquals(List.of(id(before, 5)), before.usedBy(id(before, 2)));

        // A new definition of plan above total: total is evaluated again and takes plan from it.
        Notebook redefined = before.notebook().insertAfter(id(before, 4), "plan = 2000");
        EvaluatedNotebook after = NotebookEvaluator.reevaluate(before, redefined);

        assertEquals(NotebookEvaluator.evaluate(redefined), after);
        assertEquals(List.of(variable("plan", id(after, 5)), variable("meubles", id(after, 3)), variable("pose", id(after, 4))),
                after.result(6).sources());
        assertEquals(List.of(id(after, 6)), after.usedBy(id(after, 5)));
        assertEquals(List.of(), after.usedBy(id(after, 2)));

        // An edit of the last line reuses every line above, with its sources.
        Notebook edited = after.notebook().edit(id(after, 9), "ans / 6");
        EvaluatedNotebook reused = NotebookEvaluator.reevaluate(after, edited);
        assertEquals(NotebookEvaluator.evaluate(edited), reused);
        assertEquals(List.of(variable("total", id(reused, 6)), variable("taux", id(reused, 7))), reused.result(8).sources());
        assertEquals(List.of(ans(id(reused, 8))), reused.result(9).sources());
        assertEquals(List.of(id(reused, 9)), reused.usedBy(id(reused, 8)));
    }

    // --- Expansion -------------------------------------------------------------------------------------------------

    @Example
    void theTotalOfTheQuoteExpandsToItsAmounts() throws ExpressionException {
        EvaluatedNotebook quote = evaluated(QUOTE);

        assertSameTree("1250 + 3480 + 437,5", LineExpansion.expand(quote, id(quote, 5)).orElseThrow());
        assertSameTree("5167,5 * (1 + 0,2)", LineExpansion.expand(quote, id(quote, 7)).orElseThrow());
        assertSameTree("6201 / 12", LineExpansion.expand(quote, id(quote, 8)).orElseThrow());
    }

    @Example
    void aLineWithoutValueOrWithoutSourceHasNoExpansion() {
        EvaluatedNotebook quote = evaluated(QUOTE);
        for (int number : new int[] {1, 2, 3, 4, 6}) {
            assertEquals(Optional.empty(), LineExpansion.expand(quote, id(quote, number)), quote.result(number).text());
        }
        EvaluatedNotebook notebook = evaluated("x = 4", "", "# x + 1", "x / 0", "x +", "inconnue + x", "2 + 3");
        for (int number = 1; number <= 7; number++) {
            assertEquals(Optional.empty(), LineExpansion.expand(notebook, id(notebook, number)), notebook.result(number).text());
        }
        EvaluatedNotebook errors = evaluated(withLine("total - remise"));
        assertEquals(Optional.empty(), LineExpansion.expand(errors, id(errors, 9)));
    }

    @Example
    void anApproximateSourceKeepsItsWritingAndAnExactOneBecomesItsValue() throws ExpressionException {
        EvaluatedNotebook notebook = evaluated("r = √2", "a = 3", "a * r + $1 - ans");

        assertSameTree("3 * r + $1 - 3", LineExpansion.expand(notebook, id(notebook, 3)).orElseThrow());
    }

    @Example
    void negativeAndFractionalValuesExpandToTheSameExactValue() throws ExpressionException {
        EvaluatedNotebook notebook = evaluated("a = 1 / 3", "b = -2,5", "c = -4", "a + b * c - $1 ^ 2", "ans * a");

        for (int number : new int[] {4, 5}) {
            LineResult line = notebook.result(number);
            String expansion = LineExpansion.expand(notebook, line.id()).orElseThrow();
            assertEquals(line.value(), evaluateAlone(expansion), expansion);
        }
    }

    // --- Properties over generated notebooks ----------------------------------------------------------------------

    @Provide
    Arbitrary<Notebook> notebooks() {
        return NotebookGenerators.notebooks(12);
    }

    /**
     * Notebooks of {@link NotebookGenerators} lines, mixed with expressions over the names {@code a}, {@code b},
     * {@code surface}, {@code ans}, {@code $1} and {@code $2}, under a few lines that define those names or give a
     * value: most sources resolve, to exact or approximate values, negative, decimal or not.
     */
    @Provide
    Arbitrary<Notebook> notebooksWithDefinitions() {
        Arbitrary<String> definition = Arbitraries.of("a = 1/3", "b = -2,5", "surface = 12,5 * 4", "a = 2", "b = √(9/4)",
                "surface = √2", "10", "(-3)", "0,1", "1 / 7", "a = -1/3 + 2", "b = 0");
        Arbitrary<String> operand = Arbitraries.of("a", "b", "surface", "ans", "$1", "$2", "2", "1/3", "(-3)", "0,5");
        Arbitrary<String> resolving = Combinators.combine(operand, Arbitraries.of(" + ", " - ", " * ", " / ", "^"), operand)
                .as((left, operator, right) -> left + operator + right);
        Arbitrary<String> line = Arbitraries.frequencyOf(Tuple.of(1, NotebookGenerators.lineTexts()), Tuple.of(1, resolving));
        return Combinators.combine(definition.list().ofMinSize(1).ofMaxSize(4), line.list().ofMaxSize(8))
                .as((head, tail) -> {
                    List<String> texts = new ArrayList<>(head);
                    texts.addAll(tail);
                    return NotebookGenerators.notebook(texts);
                });
    }

    @Property(tries = 300)
    void sourcesAndUsersHoldForEveryGeneratedNotebook(@ForAll("notebooks") Notebook notebook) throws ExpressionException {
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(notebook);
        for (LineResult line : evaluated.results()) {
            checkSources(evaluated, line);
            checkExpansion(evaluated, line);
        }
        boolean used = checkUsers(evaluated);
        Statistics.label("line used by another").collect(used);
        Statistics.label("line used by another").coverage(coverage -> coverage.check(true).percentage(p -> p >= 20));
    }

    @Property(tries = 300)
    void anExactExpansionEvaluatesToTheValueOfItsLine(@ForAll("notebooksWithDefinitions") Notebook notebook)
            throws ExpressionException {
        EvaluatedNotebook evaluated = NotebookEvaluator.evaluate(notebook);
        int expanded = 0;
        for (LineResult line : evaluated.results()) {
            checkSources(evaluated, line);
            if (checkExpansion(evaluated, line)) {
                expanded++;
            }
        }
        checkUsers(evaluated);
        Statistics.label("exact expansion evaluated").collect(expanded > 0);
        Statistics.label("exact expansion evaluated").coverage(coverage -> coverage.check(true).percentage(p -> p >= 25));
    }

    /** {@code b} is in {@code usedBy(a)}, in the order of the notebook, if and only if a source of {@code b} targets {@code a}. */
    private static boolean checkUsers(EvaluatedNotebook evaluated) {
        boolean used = false;
        for (LineResult line : evaluated.results()) {
            List<LineId> users = evaluated.results().stream()
                    .filter(user -> user.sources().stream().anyMatch(source -> source.target().equals(line.id())))
                    .map(LineResult::id)
                    .toList();
            assertEquals(users, evaluated.usedBy(line.id()), () -> "the users of " + line);
            used |= !users.isEmpty();
        }
        return used;
    }

    /**
     * The sources of {@code line} against an oracle built from its tree and the lines above, independently of the
     * evaluator: each name and reference, in the order of the text, with the line that gives its value. A source whose
     * line exists but is in error may be listed or not; every other one that resolves must be.
     */
    private static void checkSources(EvaluatedNotebook evaluated, LineResult line) {
        List<Source> sources = line.sources();
        assertEquals(new LinkedHashSet<>(sources).size(), sources.size(), () -> "repeated source in " + line);
        for (Source source : sources) {
            int target = evaluated.notebook().numberOf(source.target()).orElseThrow(() -> new AssertionError(source + " in " + line));
            assertTrue(target < line.number(), () -> source + " is not above " + line);
        }
        List<Source> candidates = new ArrayList<>();
        Set<Source> required = new HashSet<>();
        Set<String> kindsAndTargets = new HashSet<>();
        for (Node leaf : leaves(line.text())) {
            String label = line.text().substring(leaf.span().start(), leaf.span().end());
            Optional<LineResult> target = switch (leaf) {
                case Node.Variable variable -> closestDefinition(evaluated, line.number(), variable.name());
                case Node.Ans ignored -> closestValue(evaluated, line.number());
                case Node.LineRef reference -> reference.line() < line.number()
                        && computes(evaluated.result(reference.line())) ? Optional.of(evaluated.result(reference.line())) : Optional.empty();
                default -> throw new AssertionError(leaf);
            };
            if (target.isEmpty()) {
                continue;
            }
            Source.Kind kind = switch (leaf) {
                case Node.Variable variable -> Source.Kind.VARIABLE;
                case Node.Ans ignored -> Source.Kind.ANS;
                default -> Source.Kind.LINE_REFERENCE;
            };
            Source source = new Source(kind, label, target.get().id());
            if (candidates.contains(source)) {
                continue;
            }
            candidates.add(source);
            // Two spellings of one source ($1 and $01, ans and ANS) may count once.
            if (kindsAndTargets.add(kind + " " + source.target()) && target.get().hasValue()) {
                required.add(source);
            }
        }
        int next = 0;
        for (Source source : sources) {
            int at = candidates.subList(next, candidates.size()).indexOf(source);
            assertTrue(at >= 0, () -> source + " is not a source of " + line + ", in this order, among " + candidates);
            next += at + 1;
        }
        assertTrue(sources.containsAll(required), () -> line + " misses some of " + required + ": " + sources);
    }

    /** Checks the expansion of {@code line}; true if it was evaluated again and compared with the line. */
    private static boolean checkExpansion(EvaluatedNotebook evaluated, LineResult line) throws ExpressionException {
        Optional<String> expansion = LineExpansion.expand(evaluated, line.id());
        if (!line.hasValue() || line.sources().isEmpty()) {
            assertEquals(Optional.empty(), expansion, () -> "expansion of " + line);
            return false;
        }
        assertTrue(expansion.isPresent(), () -> "no expansion of " + line);
        ParsedLine parsed = EXPANSION_PARSER.parse(expansion.get());
        assertEquals(ParsedLine.Kind.EXPRESSION, parsed.kind(), expansion.get());
        // What is left to resolve is exactly what has no exact value.
        Set<String> approximateNames = new HashSet<>();
        Set<Integer> approximateLines = new HashSet<>();
        boolean approximateAns = false;
        for (Source source : line.sources()) {
            LineResult target = evaluated.result(source.target());
            if (target.value().isExact()) {
                continue;
            }
            switch (source.kind()) {
                case VARIABLE -> approximateNames.add(source.label());
                case LINE_REFERENCE -> approximateLines.add(target.number());
                case ANS -> approximateAns = true;
            }
        }
        assertEquals(approximateNames, parsed.variables(), () -> expansion.get() + " for " + line);
        assertEquals(approximateLines, new HashSet<>(parsed.lineReferences()), () -> expansion.get() + " for " + line);
        assertEquals(approximateAns, parsed.usesAns(), () -> expansion.get() + " for " + line);
        if (!line.value().isExact() || !approximateNames.isEmpty() || !approximateLines.isEmpty() || approximateAns) {
            return false;
        }
        assertEquals(line.value(), Evaluator.evaluate(parsed.node(), NOTHING), () -> expansion.get() + " for " + line);
        return true;
    }

    /** The variables, {@code ans} and references of a line, in the order of the text; none if it does not parse. */
    private static List<Node> leaves(String text) {
        ParsedLine parsed;
        try {
            parsed = ExpressionParser.parseLine(text);
        } catch (ExpressionException refused) {
            return List.of();
        }
        List<Node> leaves = new ArrayList<>();
        Deque<Node> nodes = new ArrayDeque<>();
        parsed.tree().ifPresent(nodes::push);
        while (!nodes.isEmpty()) {
            switch (nodes.pop()) {
                case Node.Variable variable -> leaves.add(variable);
                case Node.Ans ans -> leaves.add(ans);
                case Node.LineRef reference -> leaves.add(reference);
                case Node.Unary unary -> nodes.push(unary.operand());
                case Node.Binary binary -> {
                    nodes.push(binary.left());
                    nodes.push(binary.right());
                }
                case Node.Sqrt sqrt -> nodes.push(sqrt.operand());
                case Node.Assignment assignment -> nodes.push(assignment.value());
                case Node.NumberLiteral number -> {
                    // No source.
                }
            }
        }
        leaves.sort(Comparator.comparingInt(leaf -> leaf.span().start()));
        return leaves;
    }

    private static Optional<LineResult> closestDefinition(EvaluatedNotebook evaluated, int number, String name) {
        for (int above = number - 1; above >= 1; above--) {
            if (name.equals(evaluated.result(above).definedName())) {
                return Optional.of(evaluated.result(above));
            }
        }
        return Optional.empty();
    }

    private static Optional<LineResult> closestValue(EvaluatedNotebook evaluated, int number) {
        for (int above = number - 1; above >= 1; above--) {
            if (evaluated.result(above).hasValue()) {
                return Optional.of(evaluated.result(above));
            }
        }
        return Optional.empty();
    }

    private static boolean computes(LineResult line) {
        return line.kind() == ParsedLine.Kind.EXPRESSION || line.kind() == ParsedLine.Kind.ASSIGNMENT;
    }

    // --- Helpers ---------------------------------------------------------------------------------------------------

    private static EvaluatedNotebook evaluated(String... texts) {
        return NotebookTestSupport.evaluated(texts);
    }

    private static String[] withLine(String text) {
        String[] lines = Arrays.copyOf(QUOTE, QUOTE.length + 1);
        lines[QUOTE.length] = text;
        return lines;
    }

    private static LineId id(EvaluatedNotebook notebook, int number) {
        return notebook.result(number).id();
    }

    private static Source variable(String name, LineId target) {
        return new Source(Source.Kind.VARIABLE, name, target);
    }

    private static Source reference(String label, LineId target) {
        return new Source(Source.Kind.LINE_REFERENCE, label, target);
    }

    private static Source ans(LineId target) {
        return new Source(Source.Kind.ANS, "ans", target);
    }

    private static CertifiedValue evaluateAlone(String expansion) throws ExpressionException {
        ParsedLine parsed = EXPANSION_PARSER.parse(expansion);
        assertEquals(ParsedLine.Kind.EXPRESSION, parsed.kind(), expansion);
        assertTrue(parsed.variables().isEmpty() && parsed.lineReferences().isEmpty() && !parsed.usesAns(), expansion);
        return Evaluator.evaluate(parsed.node(), NOTHING);
    }

    private static void assertSameTree(String expected, String expansion) throws ExpressionException {
        assertNotEquals("", expansion);
        assertEquals(withoutPositions(EXPANSION_PARSER.parse(expected).node()),
                withoutPositions(EXPANSION_PARSER.parse(expansion).node()), expansion);
    }

    private static Node withoutPositions(Node node) {
        return switch (node) {
            case Node.NumberLiteral number -> new Node.NumberLiteral(number.value(), Span.NONE);
            case Node.Variable variable -> new Node.Variable(variable.name(), Span.NONE);
            case Node.Ans ans -> new Node.Ans(Span.NONE);
            case Node.LineRef reference -> new Node.LineRef(reference.line(), Span.NONE);
            case Node.Unary unary -> new Node.Unary(unary.sign(), withoutPositions(unary.operand()), Span.NONE);
            case Node.Binary binary -> new Node.Binary(binary.operator(), withoutPositions(binary.left()),
                    withoutPositions(binary.right()), Span.NONE);
            case Node.Sqrt sqrt -> new Node.Sqrt(withoutPositions(sqrt.operand()), Span.NONE);
            case Node.Assignment assignment -> new Node.Assignment(assignment.name(), withoutPositions(assignment.value()),
                    Span.NONE);
        };
    }
}
