package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link NotebookEditor} driven by random commands, with an independent record of what every reference designated
 * when its line was typed. After every step it checks the invariants of docs/adr/0008-semantique-carnet.md:
 *
 * <ol>
 *   <li>every reference still designates the line it was bound to when typed (its number is the current number of that
 *       line), or is explicitly in error ({@code $?} for a deleted line, a number past the last line for a line that
 *       did not exist), and a line that has a value uses exactly the lines its references were bound to;</li>
 *   <li>no valid reference points down: a line with a reference to itself, to a line below, past the end or deleted is
 *       in error, and every line referenced by a line that has a value is above it;</li>
 *   <li>evaluation is deterministic: two evaluations of the same notebook are equal, and the editor's incremental
 *       evaluation equals a full one;</li>
 *   <li>undo then redo gives back an equal notebook with equal results ({@link #undoThenRedo()});</li>
 *   <li>undoing every command gives back the initial notebook ({@link #undoEverything()}).</li>
 * </ol>
 *
 * The record of the bindings is kept by the session itself, from the texts it types, with its own scan of the
 * references; it never asks the notebook which line a reference designates.
 */
final class NotebookSession {
    // A reference as the lexer reads it, before the first "#": "$" and digits, or the "$?" of a deleted line.
    private static final Pattern REFERENCE = Pattern.compile("\\$([0-9]+|\\?)");

    /** What a reference designated when it was typed. */
    sealed interface Target {
    }

    /** The line {@code id}. */
    record Bound(LineId id, int typed) implements Target {
    }

    /** No line: the number was past the last line. */
    record PastTheEnd() implements Target {
    }

    /** A token the rewriting never changes: {@code $?}, {@code $0}, a number beyond {@code int}. */
    record Literal(String token) implements Target {
    }

    private final NotebookEditor editor;
    private final Notebook initial;
    private final Deque<Map<LineId, List<Target>>> undone = new ArrayDeque<>();
    private final Deque<Map<LineId, List<Target>>> redone = new ArrayDeque<>();
    private Map<LineId, List<Target>> bindings = new HashMap<>();
    // How often each situation was checked, for the statistics of the property.
    private final Map<String, Integer> seen = new TreeMap<>();

    NotebookSession(List<String> texts) {
        initial = NotebookTestSupport.notebook(texts.toArray(String[]::new));
        editor = new NotebookEditor(initial);
        for (Line line : initial.lines()) {
            bindings.put(line.id(), bind(line.text(), initial));
        }
        check();
    }

    EvaluatedNotebook current() {
        return editor.current();
    }

    /** The line at {@code index} modulo the size, or null for an empty notebook. */
    LineId lineAt(int index) {
        Notebook notebook = editor.notebook();
        return notebook.isEmpty() ? null : notebook.lines().get(Math.floorMod(index, notebook.size())).id();
    }

    void apply(NotebookCommand command, LineId typed) {
        Notebook before = editor.notebook();
        try {
            editor.apply(command);
        } catch (NotebookException refused) {
            assertEquals(before, editor.notebook(), "a refused command changes nothing");
            check();
            return;
        }
        Notebook after = editor.notebook();
        if (!after.equals(before)) {
            undone.push(bindings);
            redone.clear();
            Map<LineId, List<Target>> next = new HashMap<>();
            for (Line line : after.lines()) {
                next.put(line.id(), bindings.get(line.id()));
            }
            LineId touched = typed != null ? typed : newLine(before, after);
            if (touched != null) {
                next.put(touched, bind(after.line(after.numberOf(touched).orElseThrow()).text(), after));
            }
            bindings = next;
        }
        check();
    }

    void undo() {
        if (editor.canUndo()) {
            editor.undo();
            redone.push(bindings);
            bindings = undone.pop();
        }
        check();
    }

    void redo() {
        if (editor.canRedo()) {
            editor.redo();
            undone.push(bindings);
            bindings = redone.pop();
        }
        check();
    }

    /** Invariant 4. */
    void undoThenRedo() {
        if (editor.canUndo()) {
            EvaluatedNotebook before = editor.current();
            editor.undo();
            assertEquals(before, editor.redo(), "undo then redo gives back an equal notebook");
        }
        check();
    }

    /** Invariant 5. */
    void undoEverything() {
        while (editor.canUndo()) {
            undo();
        }
        assertEquals(initial, editor.notebook(), "undoing every command gives back the initial notebook");
        assertTrue(undone.isEmpty());
    }

    private void check() {
        Notebook notebook = editor.notebook();
        EvaluatedNotebook evaluated = editor.current();
        // Invariant 3.
        EvaluatedNotebook full = NotebookEvaluator.evaluate(notebook);
        assertEquals(full, NotebookEvaluator.evaluate(notebook), "two evaluations of the same notebook are equal");
        assertEquals(full, evaluated, "the incremental evaluation equals a full one");
        assertEquals(!undone.isEmpty(), editor.canUndo());
        assertEquals(!redone.isEmpty(), editor.canRedo());

        for (LineResult result : evaluated.results()) {
            List<String> tokens = tokens(result.text());
            List<Target> targets = bindings.get(result.id());
            assertEquals(targets.size(), tokens.size(), () -> "references of " + result);
            Set<LineId> used = new LinkedHashSet<>();
            boolean pointsElsewhere = false;
            for (int index = 0; index < tokens.size(); index++) {
                String token = tokens.get(index);
                Target target = targets.get(index);
                // Invariant 1: the same line, or explicitly in error.
                switch (target) {
                    case Bound bound when notebook.contains(bound.id()) -> {
                        int number = notebook.numberOf(bound.id()).getAsInt();
                        assertEquals(number, number(token), () -> "the reference keeps its line in " + result);
                        if (number < result.number()) {
                            used.add(bound.id());
                            see(number == bound.typed() ? "reference above" : "renumbered reference above");
                        } else {
                            pointsElsewhere = true;
                            see("reference not above");
                        }
                    }
                    case Bound deleted -> {
                        assertEquals("$?", token, () -> "a reference to a deleted line is $? in " + result);
                        pointsElsewhere = true;
                        see("reference to a deleted line");
                    }
                    case PastTheEnd past -> {
                        assertTrue(number(token) > notebook.size(),
                                () -> "a reference past the end stays past it in " + result);
                        pointsElsewhere = true;
                        see("reference past the end");
                    }
                    case Literal literal -> {
                        assertEquals(literal.token(), token);
                        pointsElsewhere = true;
                        see("literal reference");
                    }
                }
            }
            // Invariant 2: nothing valid points down, and a value only uses the lines bound above.
            if (pointsElsewhere) {
                assertTrue(result.isError(), () -> "a reference that is not above must be an error: " + result);
            }
            if (result.hasValue()) {
                see("line with a value");
                assertEquals(List.copyOf(used), result.references(), () -> "the lines used by " + result);
                for (LineId id : result.references()) {
                    assertTrue(notebook.numberOf(id).getAsInt() < result.number(), () -> "points down: " + result);
                }
            }
        }
    }

    Map<String, Integer> seen() {
        return seen;
    }

    private void see(String situation) {
        seen.merge(situation, 1, Integer::sum);
    }

    /** What each reference of {@code text} designates in {@code notebook}, where the line was just typed. */
    private static List<Target> bind(String text, Notebook notebook) {
        List<Target> targets = new ArrayList<>();
        for (String token : tokens(text)) {
            String digits = token.substring(1);
            if (digits.equals("?") || digits.length() > 10 || Long.parseLong(digits) > Integer.MAX_VALUE
                    || Long.parseLong(digits) == 0) {
                targets.add(new Literal(token));
            } else if (Long.parseLong(digits) > notebook.size()) {
                targets.add(new PastTheEnd());
            } else {
                targets.add(new Bound(notebook.line(Integer.parseInt(digits)).id(), Integer.parseInt(digits)));
            }
        }
        return targets;
    }

    // The number of a reference token, -1 for $? or a number beyond int.
    private static long number(String token) {
        String digits = token.substring(1);
        return digits.equals("?") || digits.length() > 10 ? -1 : Long.parseLong(digits);
    }

    private static List<String> tokens(String text) {
        int comment = text.indexOf('#');
        Matcher matcher = REFERENCE.matcher(comment < 0 ? text : text.substring(0, comment));
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private static LineId newLine(Notebook before, Notebook after) {
        return after.lines().stream().map(Line::id).filter(id -> !before.contains(id)).findFirst().orElse(null);
    }
}
