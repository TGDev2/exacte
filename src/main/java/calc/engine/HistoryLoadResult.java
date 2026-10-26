package calc.engine;

import java.nio.file.Path;
import java.util.List;

/**
 * What a store managed to restore: the valid entries, one {@link RejectedLine} per ignored line, and the copy of
 * the original file when one was made (null otherwise).
 */
public record HistoryLoadResult(List<CalculationRecord> records, List<RejectedLine> rejections, Path backupFile) {
    public HistoryLoadResult {
        records = List.copyOf(records);
        rejections = List.copyOf(rejections);
    }

    public int skippedLines() {
        return rejections.size();
    }
}
