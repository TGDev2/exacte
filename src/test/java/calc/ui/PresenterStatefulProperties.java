package calc.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.PresenterActions;
import calc.testing.PresenterSession;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.state.ActionChain;
import net.jqwik.api.Tag;

/**
 * Stateful test of the presenter: random sequences of what a user can do (change the operation, type, calculate,
 * clear the fields, reuse a result, select an entry, clear the history). The checks are made by
 * {@link PresenterSession} after every action; see its documentation for the list of invariants.
 */
@Tag("robustness")
class PresenterStatefulProperties {
    @Provide
    Arbitrary<ActionChain<PresenterSession>> sessions() {
        return PresenterActions.sessions();
    }

    @Property
    void invariantsHoldAfterEveryAction(@ForAll("sessions") ActionChain<PresenterSession> sessions) {
        PresenterSession finalState = sessions.run();
        assertTrue(finalState.view().historyEntries().size() <= sessions.transformations().size(),
                "The history never grows faster than one entry per action.");
    }
}
