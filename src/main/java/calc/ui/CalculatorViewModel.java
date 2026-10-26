package calc.ui;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import java.util.List;

/**
 * Immutable state handed to the view. {@code resultValue} is the Auto text of the result (it carries the « ≈ » or « … »
 * marker), {@code resultExact} says whether the result is exact, {@code resultDetail} gives the error bound of an
 * approximate result or the exact value behind a cut text (empty otherwise).
 */
public record CalculatorViewModel(
        Operation selectedOperation,
        String firstInput,
        String secondInput,
        boolean secondInputEnabled,
        String secondInputLabel,
        String secondInputTooltip,
        String inputHint,
        String resultValue,
        boolean resultExact,
        String resultDetail,
        String statusMessage,
        StatusTone statusTone,
        List<CalculationRecord> historyEntries,
        int selectedHistoryIndex,
        FocusTarget focusTarget,
        boolean selectFocusedFieldText) {

    public CalculatorViewModel {
        historyEntries = List.copyOf(historyEntries);
    }

    public boolean reuseAsFirstEnabled() {
        return !historyEntries.isEmpty();
    }

    public boolean reuseAsSecondEnabled() {
        return !historyEntries.isEmpty() && secondInputEnabled;
    }

    public boolean reuseLastResultEnabled() {
        return !historyEntries.isEmpty();
    }

    public boolean clearHistoryEnabled() {
        return !historyEntries.isEmpty();
    }
}
