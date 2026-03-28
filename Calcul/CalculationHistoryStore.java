package Calcul;

import java.util.List;

public interface CalculationHistoryStore {
    List<CalculationRecord> loadHistory() throws CalculationHistoryException;

    void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException;

    static CalculationHistoryStore noop() {
        return new CalculationHistoryStore() {
            @Override
            public List<CalculationRecord> loadHistory() {
                return List.of();
            }

            @Override
            public void saveHistory(List<CalculationRecord> history) {
            }
        };
    }
}
