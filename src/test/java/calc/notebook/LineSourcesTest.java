package calc.notebook;

import static calc.notebook.NotebookTestSupport.evaluated;
import static calc.notebook.NotebookTestSupport.exact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.expression.CanonicalPrinter;
import calc.expression.ParsedLine;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The details of the sources, of {@code usedBy} and of the expansion that the acceptance test leaves open. */
class LineSourcesTest {
    private static final LineId FIRST = new LineId(7);
    private static final LineId SECOND = new LineId(9);

    @Test
    void aSourceNeedsItsKindItsLabelAndItsTarget() {
        assertThrows(NullPointerException.class, () -> new Source(null, "a", FIRST));
        assertThrows(NullPointerException.class, () -> new Source(Source.Kind.VARIABLE, null, FIRST));
        assertThrows(NullPointerException.class, () -> new Source(Source.Kind.VARIABLE, "a", null));
    }

    @Test
    void theConstructorWithoutSourcesLabelsEachReferenceWithItsNumberAboveTheLine() {
        LineResult result = new LineResult(4, new LineId(4), "$4 + $2 + $2 + $1", ParsedLine.Kind.EXPRESSION, exact("1"), null,
                null, List.of(FIRST, SECOND));

        assertEquals(List.of(reference("$2", FIRST), reference("$1", SECOND)), result.sources());
    }

    @Test
    void aReferenceTheTextDoesNotNameIsLabelledWithAQuestionMark() {
        assertEquals(List.of(reference("$?", FIRST)),
                new LineResult(2, new LineId(2), "2 + 2", ParsedLine.Kind.EXPRESSION, exact("4"), null, null, List.of(FIRST))
                        .sources());
        assertEquals(List.of(reference("$1", FIRST), reference("$?", SECOND)),
                new LineResult(3, new LineId(3), "x = $1", ParsedLine.Kind.ASSIGNMENT, exact("4"), null, "x",
                        List.of(FIRST, SECOND)).sources());
        LineError error = NotebookTestSupport.evaluated("2 +").result(1).error();
        assertEquals(List.of(reference("$?", FIRST)),
                new LineResult(2, new LineId(2), "$1 +", ParsedLine.Kind.EXPRESSION, null, error, null, List.of(FIRST)).sources());
    }

    @Test
    void theConstructorWithoutSourcesGivesNoneToALineThatDoesNotCompute() {
        LineResult comment = new LineResult(2, new LineId(2), "# $1", ParsedLine.Kind.COMMENT, null, null, null, List.of(FIRST));

        assertEquals(List.of(FIRST), comment.references());
        assertEquals(List.of(), comment.sources());
    }

    @Test
    void theSourcesAreCopied() {
        List<Source> sources = new ArrayList<>(List.of(reference("$1", FIRST)));
        LineResult result = new LineResult(2, new LineId(2), "$1", ParsedLine.Kind.EXPRESSION, exact("1"), null, null,
                List.of(FIRST), sources);
        sources.clear();

        assertEquals(List.of(reference("$1", FIRST)), result.sources());
        assertThrows(UnsupportedOperationException.class, () -> result.sources().add(reference("$1", SECOND)));
        assertThrows(NullPointerException.class, () -> new LineResult(2, new LineId(2), "$1", ParsedLine.Kind.EXPRESSION,
                exact("1"), null, null, List.of(FIRST), null));
    }

    @Test
    void aNameWhoseLineIsInErrorIsStillASource() {
        EvaluatedNotebook notebook = evaluated("a = 1 / 0", "a + 1", "$1 * 2", "5");

        assertEquals(List.of(new Source(Source.Kind.VARIABLE, "a", id(notebook, 1))), notebook.result(2).sources());
        assertEquals(List.of(reference("$1", id(notebook, 1))), notebook.result(3).sources());
        assertEquals(List.of(id(notebook, 2), id(notebook, 3)), notebook.usedBy(id(notebook, 1)));
        assertEquals(List.of(), notebook.usedBy(id(notebook, 4)));
    }

    @Test
    void aSourceIsFoundUnderASignButNeverInAnEmptyOrACommentLine() {
        EvaluatedNotebook notebook = evaluated("", "# note", "4", "-ans", "-$1 + $2 + $3");

        assertEquals(List.of(new Source(Source.Kind.ANS, "ans", id(notebook, 3))), notebook.result(4).sources());
        assertEquals(List.of(reference("$3", id(notebook, 3))), notebook.result(5).sources());
        assertEquals(List.of(), notebook.usedBy(id(notebook, 1)));
        assertEquals(List.of(), notebook.usedBy(id(notebook, 2)));
    }

    @Test
    void aLineThatNamesALineTwiceUsesItOnce() {
        EvaluatedNotebook notebook = evaluated("2", "$1 + ans * $1", "x = ans + $1 + ans");

        assertEquals(List.of(reference("$1", id(notebook, 1)), new Source(Source.Kind.ANS, "ans", id(notebook, 1))),
                notebook.result(2).sources());
        assertEquals(List.of(id(notebook, 2), id(notebook, 3)), notebook.usedBy(id(notebook, 1)));
        assertEquals(List.of(id(notebook, 3)), notebook.usedBy(id(notebook, 2)));
    }

    @Test
    void usedByRefusesALineOfAnotherNotebook() {
        EvaluatedNotebook notebook = evaluated("1", "$1");

        assertThrows(IllegalArgumentException.class, () -> notebook.usedBy(new LineId(99)));
        assertThrows(UnsupportedOperationException.class, () -> notebook.usedBy(id(notebook, 1)).add(id(notebook, 1)));
    }

    @Test
    void theResultsAreAnUnmodifiableListLikeAnyOther() {
        EvaluatedNotebook notebook = evaluated("1", "$1 + 1");
        List<LineResult> copy = List.copyOf(notebook.results());

        assertEquals(copy, notebook.results());
        assertEquals(notebook.results(), copy);
        assertEquals(copy.hashCode(), notebook.results().hashCode());
        assertEquals(new EvaluatedNotebook(notebook.notebook(), copy), notebook);
        assertThrows(UnsupportedOperationException.class, () -> notebook.results().set(0, copy.get(1)));
        assertThrows(UnsupportedOperationException.class, () -> notebook.results().add(copy.get(1)));
    }

    @Test
    void aValueBecomesANumberOrAFractionAfterItsSign() throws NumericException {
        assertEquals("2,5", printed("2,5"));
        assertEquals("-2,5", printed("-2,5"));
        assertEquals("0", printed("0"));
        assertEquals("-4", printed("-4"));
        assertEquals("1 / 3", printed("1/3"));
        assertEquals("-(1 / 3)", printed("-1/3"));
        assertEquals("-(7 / 6)", printed("-7/6"));
    }

    @Test
    void anExpansionReplacesEachExactSourceWithItsValue() {
        EvaluatedNotebook notebook = evaluated("b = -2,5", "c = 1/3", "b * c - $2 + ans", "y = c ^ 2", "r = √c", "r * c + $5");

        assertEquals(Optional.of("-2,5 * (1 / 3) - 1 / 3 + 1 / 3"), LineExpansion.expand(notebook, id(notebook, 3)));
        assertEquals(Optional.of("(1 / 3)^2"), LineExpansion.expand(notebook, id(notebook, 4)));
        assertEquals(Optional.of("√(1 / 3)"), LineExpansion.expand(notebook, id(notebook, 5)));
        assertEquals(Optional.of("r * (1 / 3) + $5"), LineExpansion.expand(notebook, id(notebook, 6)));
        assertEquals(Optional.empty(), LineExpansion.expand(notebook, id(notebook, 1)));
    }

    private static String printed(String rational) throws NumericException {
        return CanonicalPrinter.print(LineExpansion.node(Rational.parse(rational)));
    }

    private static Source reference(String label, LineId target) {
        return new Source(Source.Kind.LINE_REFERENCE, label, target);
    }

    private static LineId id(EvaluatedNotebook notebook, int number) {
        return notebook.result(number).id();
    }
}
