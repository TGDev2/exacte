package calc.engine;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * What a store managed to restore: the valid entries, one {@link RejectedLine} per ignored line or entry, the copy of
 * the original file when one was made (null otherwise), and the {@link Condition} the store is in after loading.
 */
public record HistoryLoadResult(List<CalculationRecord> records, List<RejectedLine> rejections, Path backupFile,
                                Condition condition) {
    /** What the user has to be told about the store itself, besides the entries. */
    public enum Condition {
        /** Nothing special. */
        NORMAL,
        /** A version 1 file was converted to the current format; the entries restored are the converted ones. */
        MIGRATED,
        /** The file comes from a newer version of Calc: its entries are shown, and the file is never written. */
        NEWER_VERSION_READ_ONLY,
        /** The file comes from a newer version of Calc and could not be read: nothing is shown or written. */
        NEWER_VERSION_NOT_LOADED,
        /** Another instance of Calc holds the history: this window loads it but does not write it. */
        IN_USE_READ_ONLY
    }

    public HistoryLoadResult {
        records = List.copyOf(records);
        rejections = List.copyOf(rejections);
        Objects.requireNonNull(condition);
    }

    public HistoryLoadResult(List<CalculationRecord> records, List<RejectedLine> rejections, Path backupFile) {
        this(records, rejections, backupFile, Condition.NORMAL);
    }

    public int skippedLines() {
        return rejections.size();
    }
}
