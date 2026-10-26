package calc.engine;

import java.nio.file.Path;
import java.util.List;

public record HistoryLoadResult(List<CalculationRecord> records, int skippedLines, Path backupFile) {
    public HistoryLoadResult {
        records = List.copyOf(records);
    }
}
