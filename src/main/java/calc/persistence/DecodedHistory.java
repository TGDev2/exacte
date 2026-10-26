package calc.persistence;

import calc.engine.CalculationRecord;
import calc.engine.RejectedLine;
import java.util.List;

/**
 * What {@link HistoryCodec#decode(byte[])} found in a history file.
 *
 * @param version        the {@code version} field of the file
 * @param records        the entries that were recalculated and found consistent, in file order
 * @param rejections     one per entry that was not restored; {@link RejectedLine#lineNumber()} is its 1-based position in
 *                       {@code entries}
 * @param entriesRead    false when the file is from a newer version and its entries could not be read at all
 */
public record DecodedHistory(int version, List<CalculationRecord> records, List<RejectedLine> rejections, boolean entriesRead) {
    public DecodedHistory {
        records = List.copyOf(records);
        rejections = List.copyOf(rejections);
    }

    /** Whether the file was written by a version of the format this program does not know. */
    public boolean isNewerVersion() {
        return version > HistoryCodec.VERSION;
    }
}
