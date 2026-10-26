package calc.testing;

import calc.testing.DecimalGenerators.DecimalText;
import calc.ui.ReuseTarget;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Tuple;
import net.jqwik.api.state.Action;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.state.Transformer;

/**
 * Random sequences of what a user does in the window: type, change the operation, calculate, clear the fields, reuse a
 * result, select an entry of the history, clear the history. The checks live in {@link PresenterSession}, so every
 * action is verified where it happens.
 */
public final class PresenterActions {
    private static final int MAX_ACTIONS = 40;

    private PresenterActions() {
    }

    /** Sequences of 1 to {@value #MAX_ACTIONS} actions on a fresh session. */
    public static Arbitrary<ActionChain<PresenterSession>> sessions() {
        return ActionChain.startWith(PresenterSession::new)
                .withAction(4, typeAndCalculate())
                .withAction(2, type())
                .withAction(2, calculate())
                .withAction(4, changeOperation())
                .withAction(1, plain("clearFields", PresenterSession::clearFields))
                .withAction(2, plain("reuseLast", PresenterSession::reuseLast))
                .withAction(2, reuseSelected())
                .withAction(3, select())
                .withAction(1, plain("clearHistory", PresenterSession::clearHistory))
                .withMaxTransformations(MAX_ACTIONS);
    }

    /** What a user types in a field: mostly plausible numbers and fractions, sometimes nothing, sometimes rubbish. */
    public static Arbitrary<String> typedText() {
        Arbitrary<String> number = DecimalGenerators.decimals(14).map(DecimalText::text);
        Arbitrary<String> exponent = DecimalGenerators.integers(2).map(DecimalText::text);
        Arbitrary<String> fraction = DecimalGenerators.fractions().map(DecimalGenerators.FractionText::text);
        Arbitrary<String> rubbish = Arbitraries.oneOf(DecimalGenerators.invalidDecimals(), DecimalGenerators.blanks(), TextGenerators.numberLike());
        return Arbitraries.frequencyOf(Tuple.of(6, number), Tuple.of(3, exponent), Tuple.of(2, fraction), Tuple.of(2, rubbish));
    }

    private static Action<PresenterSession> typeAndCalculate() {
        return independent(() -> Combinators.combine(typedText(), typedText())
                .as((first, second) -> Transformer.<PresenterSession>mutate("type+calculate", session -> {
                    session.type(first, second);
                    session.calculate();
                })));
    }

    private static Action<PresenterSession> type() {
        return independent(() -> Combinators.combine(typedText(), typedText())
                .as((first, second) -> Transformer.<PresenterSession>mutate("type", session -> session.type(first, second))));
    }

    private static Action<PresenterSession> calculate() {
        return plain("calculate", PresenterSession::calculate);
    }

    private static Action<PresenterSession> changeOperation() {
        return independent(() -> RecordGenerators.operations()
                .map(operation -> Transformer.<PresenterSession>mutate("changeOperation", session -> session.changeOperation(operation))));
    }

    private static Action<PresenterSession> reuseSelected() {
        return independent(() -> Arbitraries.of(ReuseTarget.class)
                .map(target -> Transformer.<PresenterSession>mutate("reuseSelected", session -> session.reuseSelected(target))));
    }

    private static Action<PresenterSession> select() {
        return independent(() -> Arbitraries.integers().between(-3, 12)
                .map(index -> Transformer.<PresenterSession>mutate("select", session -> session.select(index))));
    }

    private static Action<PresenterSession> plain(String description, Consumer<PresenterSession> step) {
        return Action.just(description, Transformer.mutate(description, step));
    }

    private static Action<PresenterSession> independent(Supplier<Arbitrary<Transformer<PresenterSession>>> transformers) {
        return new Action.Independent<>() {
            @Override
            public Arbitrary<Transformer<PresenterSession>> transformer() {
                return transformers.get();
            }
        };
    }
}
