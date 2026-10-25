package Graphique;

import Calcul.CalculationRecord;
import Calcul.Operation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CalculatorViewModel {
    private final Operation selectedOperation;
    private final String firstInput;
    private final String secondInput;
    private final boolean secondInputEnabled;
    private final String secondInputLabel;
    private final String secondInputTooltip;
    private final String inputHint;
    private final String resultValue;
    private final String statusMessage;
    private final StatusTone statusTone;
    private final List<CalculationRecord> historyEntries;
    private final int selectedHistoryIndex;
    private final boolean reuseAsFirstEnabled;
    private final boolean reuseAsSecondEnabled;
    private final boolean reuseLastResultEnabled;
    private final boolean clearHistoryEnabled;
    private final FocusTarget focusTarget;
    private final boolean selectFocusedFieldText;

    public CalculatorViewModel(
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
            boolean reuseAsFirstEnabled,
            boolean reuseAsSecondEnabled,
            boolean reuseLastResultEnabled,
            boolean clearHistoryEnabled,
            FocusTarget focusTarget,
            boolean selectFocusedFieldText) {
        this.selectedOperation = selectedOperation;
        this.firstInput = firstInput;
        this.secondInput = secondInput;
        this.secondInputEnabled = secondInputEnabled;
        this.secondInputLabel = secondInputLabel;
        this.secondInputTooltip = secondInputTooltip;
        this.inputHint = inputHint;
        this.resultValue = resultValue;
        this.statusMessage = statusMessage;
        this.statusTone = statusTone;
        this.historyEntries = Collections.unmodifiableList(new ArrayList<>(historyEntries));
        this.selectedHistoryIndex = selectedHistoryIndex;
        this.reuseAsFirstEnabled = reuseAsFirstEnabled;
        this.reuseAsSecondEnabled = reuseAsSecondEnabled;
        this.reuseLastResultEnabled = reuseLastResultEnabled;
        this.clearHistoryEnabled = clearHistoryEnabled;
        this.focusTarget = focusTarget;
        this.selectFocusedFieldText = selectFocusedFieldText;
    }

    public Operation getSelectedOperation() {
        return selectedOperation;
    }

    public String getFirstInput() {
        return firstInput;
    }

    public String getSecondInput() {
        return secondInput;
    }

    public boolean isSecondInputEnabled() {
        return secondInputEnabled;
    }

    public String getSecondInputLabel() {
        return secondInputLabel;
    }

    public String getSecondInputTooltip() {
        return secondInputTooltip;
    }

    public String getInputHint() {
        return inputHint;
    }

    public String getResultValue() {
        return resultValue;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public StatusTone getStatusTone() {
        return statusTone;
    }

    public List<CalculationRecord> getHistoryEntries() {
        return historyEntries;
    }

    public int getSelectedHistoryIndex() {
        return selectedHistoryIndex;
    }

    public boolean isReuseAsFirstEnabled() {
        return reuseAsFirstEnabled;
    }

    public boolean isReuseAsSecondEnabled() {
        return reuseAsSecondEnabled;
    }

    public boolean isReuseLastResultEnabled() {
        return reuseLastResultEnabled;
    }

    public boolean isClearHistoryEnabled() {
        return clearHistoryEnabled;
    }

    public FocusTarget getFocusTarget() {
        return focusTarget;
    }

    public boolean shouldSelectFocusedFieldText() {
        return selectFocusedFieldText;
    }
}
