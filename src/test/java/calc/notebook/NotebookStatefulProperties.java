package calc.notebook;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;
import net.jqwik.api.statistics.Statistics;

/**
 * Stateful test of the notebook: random sequences of commands (edit, insert before or after, append, delete, move,
 * clear, undo, redo) on a notebook whose lines refer to each other. {@link NotebookSession} checks the five invariants
 * of the brief after every step; the sequence ends by undoing everything.
 */
@Tag("robustness")
class NotebookStatefulProperties {
    private static final int MAX_ACTIONS = 40;
    private static final List<String> SITUATIONS = List.of("line with a value", "reference above",
            "renumbered reference above", "reference not above", "reference to a deleted line", "reference past the end",
            "literal reference");

    @Provide
    Arbitrary<ActionChain<NotebookSession>> sessions() {
        // A fresh session for each run of the chain: jqwik runs it again while shrinking.
        return lineText().list().ofMaxSize(8).flatMap(texts -> ActionChain.startWith(() -> new NotebookSession(texts))
                .withAction(5, command("edit", (s, index, text) -> new NotebookCommand.Edit(s.lineAt(index), text), true))
                .withAction(3, command("insertBefore", (s, index, text) -> new NotebookCommand.InsertBefore(s.lineAt(index), text), true))
                .withAction(3, command("insertAfter", (s, index, text) -> new NotebookCommand.InsertAfter(s.lineAt(index), text), true))
                .withAction(3, command("append", (s, index, text) -> new NotebookCommand.Append(text), false))
                .withAction(3, command("delete", (s, index, text) -> new NotebookCommand.Delete(s.lineAt(index)), true))
                .withAction(4, move())
                .withAction(1, command("clear", (s, index, text) -> new NotebookCommand.Clear(), false))
                .withAction(3, plain("undo", NotebookSession::undo))
                .withAction(2, plain("redo", NotebookSession::redo))
                .withAction(2, plain("undoThenRedo", NotebookSession::undoThenRedo))
                .withMaxTransformations(MAX_ACTIONS));
    }

    @Property(tries = 300)
    void everyInvariantHoldsAfterEveryCommand(@ForAll("sessions") ActionChain<NotebookSession> chain) {
        NotebookSession session = chain.run();
        session.undoEverything();
        for (String situation : SITUATIONS) {
            Statistics.label(situation).collect(session.seen().containsKey(situation));
        }
    }

    /**
     * What a user types on a line: expressions and assignments mixing numbers, variables, {@code ans} and references
     * {@code $1} to {@code $12} (often past the end), comments that contain references, empty lines, and lines in
     * error ({@code $?}, {@code $0}, a number beyond {@code int}, syntax errors).
     */
    static Arbitrary<String> lineText() {
        Arbitrary<String> reference = Arbitraries.integers().between(1, 12).map(n -> "$" + n);
        Arbitrary<String> operand = Arbitraries.frequencyOf(
                Tuple.of(6, reference),
                Tuple.of(3, Arbitraries.of("1", "2,5", "10", "0", "1/3", "√2")),
                Tuple.of(2, Arbitraries.of("a", "b", "x")),
                Tuple.of(2, Arbitraries.just("ans")),
                Tuple.of(1, Arbitraries.of("$?", "$0", "$2147483648")));
        Arbitrary<String> operator = Arbitraries.of(" + ", " - ", " * ", " / ", "^");
        Arbitrary<String> expression = Combinators.combine(operand, operator.list().ofMaxSize(3), operand.list().ofSize(3))
                .as((first, operators, others) -> {
                    StringBuilder text = new StringBuilder(first);
                    for (int index = 0; index < operators.size(); index++) {
                        text.append(operators.get(index)).append(others.get(index));
                    }
                    return text.toString();
                });
        Arbitrary<String> assignment = Combinators.combine(Arbitraries.of("a", "b", "x"), expression)
                .as((name, value) -> name + " = " + value);
        Arbitrary<String> comment = Combinators.combine(reference, Arbitraries.of("", " # note $3", "#$1"))
                .as((ref, tail) -> "# total " + ref + tail);
        Arbitrary<String> commented = Combinators.combine(expression, reference).as((value, ref) -> value + "  # voir " + ref);
        Arbitrary<String> broken = Combinators.combine(expression, Arbitraries.of(" +", " (", " $", "1,5,3 ")).as(String::concat);
        return Arbitraries.frequencyOf(
                Tuple.of(8, expression),
                Tuple.of(4, assignment),
                Tuple.of(2, commented),
                Tuple.of(1, comment),
                Tuple.of(1, Arbitraries.just("")),
                Tuple.of(1, broken));
    }

    @FunctionalInterface
    private interface CommandMaker {
        NotebookCommand make(NotebookSession session, int index, String text);
    }

    // A command on the line at a random index; `needsLine` commands do nothing on an empty notebook.
    private static Action<NotebookSession> command(String name, CommandMaker maker, boolean needsLine) {
        return independent(() -> Combinators.combine(Arbitraries.integers().between(0, 50), lineText())
                .as((index, text) -> Transformer.<NotebookSession>mutate(name + " " + index + " [" + text + "]", session -> {
                    if (needsLine && session.lineAt(index) == null) {
                        return;
                    }
                    NotebookCommand command = maker.make(session, index, text);
                    session.apply(command, command instanceof NotebookCommand.Edit edit ? edit.line() : null);
                })));
    }

    private static Action<NotebookSession> move() {
        return independent(() -> Combinators.combine(Arbitraries.integers().between(0, 50), Arbitraries.integers().between(0, 50))
                .as((index, target) -> Transformer.<NotebookSession>mutate("move " + index + " to " + target, session -> {
                    LineId line = session.lineAt(index);
                    if (line != null) {
                        int size = session.current().notebook().size();
                        session.apply(new NotebookCommand.Move(line, target % size), null);
                    }
                })));
    }

    private static Action<NotebookSession> plain(String description, Consumer<NotebookSession> step) {
        return Action.just(description, Transformer.mutate(description, step));
    }

    private static Action<NotebookSession> independent(Supplier<Arbitrary<Transformer<NotebookSession>>> transformers) {
        return new Action.Independent<>() {
            @Override
            public Arbitrary<Transformer<NotebookSession>> transformer() {
                return transformers.get();
            }
        };
    }
}
