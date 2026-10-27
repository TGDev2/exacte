package calc.testing;

import calc.notebook.Notebook;
import calc.notebook.NotebookException;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;

/**
 * Notebooks for the persistence and export tests: the texts a user types (expressions, assignments, references,
 * comments, accents, quotes, tabs) mixed with texts the language refuses, which a file must keep all the same.
 */
public final class NotebookGenerators {
    private NotebookGenerators() {
    }

    /** One line: usually valid, sometimes broken, sometimes hostile to a file format (quotes, backslashes, controls). */
    public static Arbitrary<String> lineTexts() {
        Arbitrary<String> reference = Arbitraries.integers().between(1, 12).map(n -> "$" + n);
        Arbitrary<String> operand = Arbitraries.frequencyOf(
                Tuple.of(5, reference),
                Tuple.of(4, Arbitraries.of("1", "2,5", "10", "0", "1/3", "√2", "0,1", "12,5", "(-3)")),
                Tuple.of(2, Arbitraries.of("a", "b", "surface")),
                Tuple.of(2, Arbitraries.just("ans")));
        Arbitrary<String> operator = Arbitraries.of(" + ", " - ", " * ", " / ", "^");
        Arbitrary<String> expression = Combinators.combine(operand, operator, operand).as((a, op, b) -> a + op + b);
        Arbitrary<String> assignment = Combinators.combine(Arbitraries.of("a", "b", "surface"), expression)
                .as((name, value) -> name + " = " + value);
        Arbitrary<String> comment = Arbitraries.of("# Devis cuisine", "#", "# = total", "# $1 | note", "  # indenté", "# café « crème »");
        Arbitrary<String> broken = Combinators.combine(expression, Arbitraries.of(" +", " (", " $", "1,5,3 ", " @", " =")).as(String::concat);
        Arbitrary<String> awkward = Arbitraries.of("\"quoted\"", "back\\slash", "tab\there", "ctrl\u0001\u001f", "naïve → ≈ ½", "emoji 😀",
                "{\"id\": \"1\"}", "-", "+", "=", "@", "  ", "", " 1 + 1 ", "x".repeat(1200));
        Arbitrary<String> anything = Arbitraries.strings().ofMaxLength(40).filter(text -> text.indexOf('\n') < 0 && text.indexOf('\r') < 0);
        return Arbitraries.frequencyOf(
                Tuple.of(8, expression),
                Tuple.of(4, assignment),
                Tuple.of(3, comment),
                Tuple.of(2, broken),
                Tuple.of(2, awkward),
                Tuple.of(2, anything));
    }

    /** A notebook of up to {@code maxLines} such lines (new identifiers, 1 to n). */
    public static Arbitrary<Notebook> notebooks(int maxLines) {
        return lineTexts().list().ofMaxSize(maxLines).map(NotebookGenerators::notebook);
    }

    public static Notebook notebook(List<String> texts) {
        try {
            return Notebook.of(texts);
        } catch (NotebookException exception) {
            throw new AssertionError(exception);
        }
    }
}
