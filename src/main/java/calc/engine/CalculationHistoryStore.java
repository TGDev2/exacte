package calc.engine;

import java.util.List;

public interface CalculationHistoryStore {
    HistoryLoadResult loadHistory() throws CalculationHistoryException;

    void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException;

    static CalculationHistoryStore noop() {
        return new CalculationHistoryStore() {
            @Override
            public HistoryLoadResult loadHistory() {
                return new HistoryLoadResult(List.of(), List.of(), null);
            }

            @Override
            public void saveHistory(List<CalculationRecord> history) {
            }
        };
    }
}
