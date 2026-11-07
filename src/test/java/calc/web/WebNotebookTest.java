package calc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.expression.ExpressionError;
import calc.expression.ParsedLine;
import calc.expression.Span;
import calc.notebook.LineError;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What no text reaches through the commands: no line of the core has an empty span, nor fails with a checked exception. */
class WebNotebookTest {
    private static final String TEXT = "2 +";

    @Test
    void anEmptySpanMarksNothing() {
        assertEquals(List.of(-1, -1), faultyPart(Span.NONE));
        assertEquals(List.of(-1, -1), faultyPart(new Span(1, 1)));
        assertEquals(List.of(-1, -1), faultyPart(new Span(3, 3)));
    }

    @Test
    void aSpanOutsideTheTextMarksNothing() {
        assertEquals(List.of(-1, -1), faultyPart(new Span(3, 4)));
        assertEquals(List.of(-1, -1), faultyPart(new Span(9, 12)));
    }

    @Test
    void aSpanThatPassesTheEndOfTheTextIsCutThere() {
        assertEquals(List.of(2, 3), faultyPart(new Span(2, 9)));
    }

    @Test
    void aSpanInsideTheTextIsKept() {
        assertEquals(List.of(0, 1), faultyPart(new Span(0, 1)));
        assertEquals(List.of(2, 3), faultyPart(new Span(2, 3)));
    }

    @Test
    void aCheckedFailureIsHandledLikeAnyOther() {
        WebNotebook notebook = new WebNotebook();
        String before = notebook.load("1", "AUTO");

        notebook.failNextCommandWith(new Exception());
        String answer = notebook.append("2");

        assertTrue(answer.contains("\"message\":\""), answer);
        assertEquals("1", notebook.texts());
        assertTrue(answer.endsWith(before.substring(before.indexOf("\"lines\":"))), answer);
    }

    /** The editor has no way back but undo: what fails after being applied takes the place of what could be redone. */
    @Test
    void aCommandTakenBackAfterAFailureCanBeRedone() {
        WebNotebook notebook = new WebNotebook();
        notebook.load("1", "AUTO");

        notebook.failNextCommandWith(new StackOverflowError());
        String answer = notebook.append("2");
        assertTrue(answer.startsWith("{\"mode\":\"AUTO\",\"canUndo\":false,\"canRedo\":true,\"message\":\""), answer);
        assertEquals("1", notebook.texts());

        notebook.redo();
        assertEquals("1\n2", notebook.texts());
    }

    private static List<Integer> faultyPart(Span span) {
        LineError error = new LineError(ExpressionError.UNEXPECTED_CHARACTER, span, "Message.");
        LineResult result = new LineResult(1, new LineId(1), TEXT, ParsedLine.Kind.EXPRESSION, null, error, null, List.of());
        StringBuilder json = new StringBuilder();
        new WebNotebook().appendLine(json, result);

        String prefix = "{\"number\":1,\"text\":\"2 +\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,\"errorBound\":null,"
                + "\"error\":\"Message.\",\"errorStart\":";
        String line = json.toString();
        assertTrue(line.startsWith(prefix) && line.endsWith("}"), line);
        String[] part = line.substring(prefix.length(), line.length() - 1).split(",\"errorEnd\":");
        return List.of(Integer.valueOf(part[0]), Integer.valueOf(part[1]));
    }
}
