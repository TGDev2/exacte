package calc.testing;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.HistoryLoadResult;
import java.util.ArrayList;
import java.util.List;

/** A history store that keeps what it is given in memory and counts the saves; it can be told to fail. */
public final class MemoryHistoryStore implements CalculationHistoryStore {
    private List<CalculationRecord> saved = List.of();
    private int saveCount;
    private boolean failing;

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public int saveCount() {
        return saveCount;
    }

    /** The history as of the last successful save. */
    public List<CalculationRecord> saved() {
        return saved;
    }

    @Override
    public HistoryLoadResult loadHistory() {
        return new HistoryLoadResult(saved, List.of(), null);
    }

    @Override
    public void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException {
        if (failing) {
            throw new CalculationHistoryException("The memory store is set to fail.");
        }
        saved = new ArrayList<>(history);
        saveCount++;
    }
}
