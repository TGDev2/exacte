package calc.ui;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import java.util.List;

public record CalculatorViewModel(
        Operation selectedOperation,
        String firstInput,
        String secondInput,
        boolean secondInputEnabled,
        String secondInputLabel,
        String secondInputTooltip,
        String inputHint,
        String resultValue,
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
