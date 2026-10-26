package calc.ui;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.format.DisplayMode;
import calc.ui.widget.HistoryLine;
import java.util.List;

/**
 * Immutable state handed to the view; every text is ready to be shown.
 *
 * <p>The result is described in the {@code displayMode} in force: {@code resultValue} is its main text (with the « ≈ »
 * or « … » marker), {@code resultExact} says whether it is exact, {@code resultTruncated} whether the text leaves out
 * part of the value, {@code resultErrorBound} is the formatted bound of an approximate result, {@code resultFraction}
 * the exact fraction behind a cut text, and {@code resultDetail} the sentence that explains the exactness mark (empty
 * when there is nothing to add). The last three are empty when they do not apply; {@code resultShown} is false while
 * there is no result, and the other result fields are then neutral.
 *
 * <p>{@code historyEntries} are the calculations themselves, {@code historyLines} the same entries formatted for the
 * display mode (same order, same size).
 */
public record CalculatorViewModel(
        Operation selectedOperation,
        String firstInput,
        String secondInput,
        boolean secondInputEnabled,
        String secondInputLabel,
        String secondInputTooltip,
        String inputHint,
        DisplayMode displayMode,
        boolean resultShown,
        String resultValue,
        boolean resultExact,
        boolean resultTruncated,
        String resultErrorBound,
        String resultFraction,
        String resultDetail,
        String statusMessage,
        StatusTone statusTone,
        List<CalculationRecord> historyEntries,
        List<HistoryLine> historyLines,
        int selectedHistoryIndex,
        FocusTarget focusTarget,
        boolean selectFocusedFieldText) {

    public CalculatorViewModel {
        historyEntries = List.copyOf(historyEntries);
        historyLines = List.copyOf(historyLines);
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
