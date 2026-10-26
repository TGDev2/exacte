package calc.engine;

import java.util.List;

public interface CalculationHistoryStore {
    HistoryLoadResult loadHistory() throws CalculationHistoryException;

    /**
     * Replaces the stored history. A store that is {@linkplain #isReadOnly() read only} ignores the call, without any
     * exception: the caller is not told that the write did not happen, it asks {@link #isReadOnly()}.
     */
    void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException;

    /** Whether {@link #saveHistory(List)} writes nothing (another instance holds the history, or a newer version made it). */
    default boolean isReadOnly() {
        return false;
    }

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
