package calc.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.InputField;
import calc.engine.Operation;
import calc.ui.CalculatorPresenter;
import calc.ui.CalculatorViewModel;
import calc.ui.FocusTarget;
import calc.ui.ReuseTarget;
import calc.ui.StatusTone;
import java.util.ArrayList;
import java.util.List;

/**
 * A presenter driven the way the window drives it, with the checks that must hold after every action.
 *
 * <p>The session keeps the text of the two fields as the window would show it: it is what the user typed, replaced by
 * what the presenter answers. Each action calls the presenter, then checks its own consequences (a successful
 * calculation adds one entry, a refused one adds none, reusing puts the stored result in the field...) and finally the
 * invariants that hold whatever the action was:
 *
 * <ol>
 *   <li>the history only grows on a successful calculation, by exactly one entry, and only an explicit clearing
 *       removes entries;
 *   <li>the reusable value of every entry equals the result displayed for it, and the displayed result of the last
 *       calculation is the text put in the first field;
 *   <li>the second field is enabled if and only if the operation is binary;
 *   <li>the selected history index is -1 or a valid index;
 *   <li>the presenter never throws;
 *   <li>(extra) the persisted history is the displayed one, and the focus never targets a disabled field.
 * </ol>
 */
public final class PresenterSession {
    private final MemoryHistoryStore store = new MemoryHistoryStore();
    private final CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), store);
    private final CalculatorEngine probe = new CalculatorEngine();
    private CalculatorViewModel view = presenter.createInitialView();
    private String firstField = "";
    private String secondField = "";
    private final List<String> trace = new ArrayList<>();

    public PresenterSession() {
        checkInvariants(List.of(), 0);
    }

    public CalculatorViewModel view() {
        return view;
    }

    public List<String> trace() {
        return trace;
    }

    /** The user types in the two fields (the second one only when it is enabled); the presenter is not involved until the next action. */
    public void type(String first, String second) {
        firstField = first;
        secondField = view.secondInputEnabled() ? second : "";
        trace.add("type(" + abbreviate(first) + ", " + abbreviate(second) + ")");
    }

    public void changeOperation(Operation operation) {
        trace.add("changeOperation(" + operation + ")");
        List<CalculationRecord> before = history();
        view = presenter.onOperationChanged(operation, firstField, secondField);
        assertEquals(operation, view.selectedOperation(), "The chosen operation is selected.");
        assertEquals(firstField, view.firstInput(), "Changing the operation keeps the first field.");
        assertEquals(operation.requiresSecondOperand() ? secondField : "", view.secondInput(),
                "Changing the operation keeps the second field, unless the new operation does not use it.");
        finish(before, 0);
    }

    public void calculate() {
        trace.add("calculate(" + abbreviate(firstField) + ", " + abbreviate(secondField) + ")");
        Operation operation = view.selectedOperation();
        List<CalculationRecord> before = history();
        CalculationRecord expected = null;
        CalculationException refusal = null;
        try {
            expected = probe.calculate(operation, firstField, secondField);
        } catch (CalculationException exception) {
            refusal = exception;
        }

        view = presenter.onCalculate(firstField, secondField);

        if (expected != null) {
            List<CalculationRecord> after = history();
            assertEquals(before.size() + 1, after.size(), "A successful calculation adds exactly one entry to the history.");
            CalculationRecord added = after.get(after.size() - 1);
            assertEquals(expected.toString(), added.toString(), "The entry is the calculation that was asked for.");
            assertEquals(0, expected.getResultValue().compareTo(added.getResultValue()), "The entry holds the computed value.");
            assertEquals(added.getFormattedResult(), view.resultValue(), "The displayed result is the entry's result.");
            assertEquals(0, ArithmeticOracle.parse(view.resultValue()).compareTo(added.getResultValue()),
                    "The reusable value of the last calculation is the displayed result.");
            assertEquals(view.resultValue(), view.firstInput(), "The result is ready to be reused as the first operand.");
            assertEquals(after.size() - 1, view.selectedHistoryIndex(), "The new entry is selected.");
            assertEquals(StatusTone.SUCCESS, view.statusTone(), "A successful calculation reports a success.");
            finish(before, 1);
        } else {
            assertEquals("--", view.resultValue(), "A refused calculation shows no result.");
            assertEquals(StatusTone.ERROR, view.statusTone(), "A refused calculation reports an error.");
            assertEquals(refusal.getMessage(), view.statusMessage(), "The status is the engine's message.");
            boolean secondTargeted = refusal.getField() == InputField.SECOND && operation.requiresSecondOperand();
            assertEquals(secondTargeted ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER, view.focusTarget(),
                    "The focus goes to the field the engine named.");
            finish(before, 0);
        }
    }

    public void clearFields() {
        trace.add("clearFields");
        List<CalculationRecord> before = history();
        view = presenter.onClearFields(firstField, secondField);
        assertEquals("", view.firstInput());
        assertEquals("", view.secondInput());
        assertEquals("--", view.resultValue());
        finish(before, 0);
    }

    public void reuseLast() {
        trace.add("reuseLast");
        List<CalculationRecord> before = history();
        String firstBefore = firstField;
        view = presenter.onReuseLastResult(firstField, secondField);
        if (before.isEmpty()) {
            assertEquals(StatusTone.ERROR, view.statusTone(), "Reusing with an empty history is reported as an error.");
            assertEquals(firstBefore, view.firstInput(), "Nothing to reuse leaves the first field alone.");
        } else {
            CalculationRecord last = before.get(before.size() - 1);
            assertEquals(last.getFormattedResult(), view.firstInput(), "The last result is put in the first field.");
            assertEquals("", view.secondInput());
            assertEquals(before.size() - 1, view.selectedHistoryIndex(), "The last entry is selected.");
            assertEquals(StatusTone.SUCCESS, view.statusTone());
        }
        finish(before, 0);
    }

    public void reuseSelected(ReuseTarget target) {
        trace.add("reuseSelected(" + target + ")");
        List<CalculationRecord> before = history();
        int selected = view.selectedHistoryIndex();
        Operation operation = view.selectedOperation();
        String firstBefore = firstField;
        String secondBefore = operation.requiresSecondOperand() ? secondField : "";
        view = presenter.onReuseHistoryResult(target, firstField, secondField);
        if (before.isEmpty()) {
            assertEquals(StatusTone.ERROR, view.statusTone(), "Reusing with an empty history is reported as an error.");
            assertEquals(firstBefore, view.firstInput());
        } else if (target == ReuseTarget.SECOND_NUMBER && !operation.requiresSecondOperand()) {
            assertEquals(StatusTone.ERROR, view.statusTone(), "A unary operation has no second field to fill.");
            assertEquals("", view.secondInput());
        } else {
            CalculationRecord record = selected >= 0 && selected < before.size() ? before.get(selected) : before.get(before.size() - 1);
            if (target == ReuseTarget.FIRST_NUMBER) {
                assertEquals(record.getFormattedResult(), view.firstInput(), "The selected result is put in the first field.");
                assertEquals(secondBefore, view.secondInput());
            } else {
                assertEquals(record.getFormattedResult(), view.secondInput(), "The selected result is put in the second field.");
                assertEquals(firstBefore, view.firstInput());
            }
            assertEquals(StatusTone.SUCCESS, view.statusTone());
        }
        finish(before, 0);
    }

    public void select(int index) {
        trace.add("select(" + index + ")");
        List<CalculationRecord> before = history();
        view = presenter.onHistorySelectionChanged(index);
        assertEquals(index >= 0 && index < before.size() ? index : -1, view.selectedHistoryIndex(),
                "A valid index is selected; any other index selects nothing.");
        finish(before, 0);
    }

    public void clearHistory() {
        trace.add("clearHistory");
        List<CalculationRecord> before = history();
        view = presenter.onClearHistory();
        assertTrue(view.historyEntries().isEmpty(), "Clearing the history empties it.");
        assertEquals(-1, view.selectedHistoryIndex(), "Nothing is selected in an empty history.");
        checkInvariants(before, -before.size());
        syncFields();
    }

    private void finish(List<CalculationRecord> before, int growth) {
        checkInvariants(before, growth);
        syncFields();
    }

    private void syncFields() {
        firstField = view.firstInput();
        secondField = view.secondInput();
    }

    private List<CalculationRecord> history() {
        return new ArrayList<>(view.historyEntries());
    }

    /** The checks that hold after any action; {@code growth} is the expected change of the history size. */
    private void checkInvariants(List<CalculationRecord> before, int growth) {
        List<CalculationRecord> now = view.historyEntries();

        // 1. The history changes only as announced: one more entry after a successful calculation, empty after a clearing.
        assertEquals(before.size() + growth, now.size(), () -> "History size after " + trace);
        if (growth >= 0) {
            for (int index = 0; index < before.size(); index++) {
                assertSame(before.get(index), now.get(index), () -> "Earlier entries are never altered, after " + trace);
            }
        }

        // 2. The reusable value of each entry is the value displayed for it.
        for (CalculationRecord record : now) {
            assertEquals(ArithmeticOracle.display(record.getResultValue()), record.getFormattedResult(),
                    () -> "Display of " + record + " after " + trace);
            assertEquals(0, ArithmeticOracle.parse(record.getFormattedResult()).compareTo(record.getResultValue()),
                    () -> "Reusable value of " + record + " after " + trace);
        }

        // 3. The second field is enabled if and only if the operation is binary.
        assertEquals(view.selectedOperation().requiresSecondOperand(), view.secondInputEnabled(),
                () -> "Second field state for " + view.selectedOperation() + " after " + trace);
        if (!view.secondInputEnabled()) {
            assertEquals("", view.secondInput(), () -> "A disabled second field is empty, after " + trace);
            assertFalse(view.reuseAsSecondEnabled());
        }

        // 4. The selection is -1 or a valid index.
        assertTrue(view.selectedHistoryIndex() >= -1 && view.selectedHistoryIndex() < now.size(),
                () -> "Selected index " + view.selectedHistoryIndex() + " with " + now.size() + " entries, after " + trace);

        // Extras: what is displayed is what is persisted, the buttons agree with the history, the focus is on a live field.
        assertEquals(now.size(), store.saved().size(), () -> "Persisted history size after " + trace);
        for (int index = 0; index < now.size(); index++) {
            assertSame(now.get(index), store.saved().get(index), () -> "Persisted entries after " + trace);
        }
        assertEquals(!now.isEmpty(), view.reuseLastResultEnabled());
        assertEquals(!now.isEmpty(), view.clearHistoryEnabled());
        assertNotNull(view.statusMessage());
        assertFalse(view.statusMessage().isBlank(), "The status always says something.");
        if (view.focusTarget() == FocusTarget.SECOND_NUMBER) {
            assertTrue(view.secondInputEnabled(), () -> "The focus never targets the disabled second field, after " + trace);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 24 ? "'" + text + "'" : "'" + text.substring(0, 12) + "...' (" + text.length() + " characters)";
    }
}
