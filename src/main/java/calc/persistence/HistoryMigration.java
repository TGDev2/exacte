package calc.persistence;

import calc.engine.CalculationRecord;
import calc.notebook.HistoryBridge;
import calc.notebook.Notebook;
import calc.notebook.NotebookException;
import java.util.ArrayList;
import java.util.List;

/**
 * The history becomes a notebook: each entry, in order, becomes the line of the same value given by {@link HistoryBridge}
 * (docs/adr/0009-documents-et-exports.md, section 7). An entry that no line can express (an approximate operand, an operand
 * longer than a line) becomes a comment that says what it was, so that nothing disappears without a trace. A notebook
 * holds {@link Notebook#MAX_LINES} lines: when the history is longer, the newest entries are converted and the others
 * stay in the copy of the history.
 */
final class HistoryMigration {
    static final String COMMENT_PREFIX = "# Historique non converti : ";
    private static final int MAX_COMMENT_LENGTH = 400;

    /** The notebook made of the history and how it came to be. */
    record Converted(Notebook notebook, int commentedEntries, int omittedEntries) {
    }

    private HistoryMigration() {
    }

    static Converted convert(List<CalculationRecord> history) {
        int omitted = Math.max(0, history.size() - Notebook.MAX_LINES);
        List<String> texts = new ArrayList<>(history.size() - omitted);
        int commented = 0;
        for (CalculationRecord entry : history.subList(omitted, history.size())) {
            String line = HistoryBridge.toLine(entry).orElse(null);
            if (line == null) {
                commented++;
                line = COMMENT_PREFIX + shorten(entry.toString());
            }
            texts.add(line);
        }
        try {
            return new Converted(Notebook.of(texts), commented, omitted);
        } catch (NotebookException cannotHappen) {
            throw new IllegalStateException("at most " + Notebook.MAX_LINES + " lines were kept", cannotHappen);
        }
    }

    private static String shorten(String text) {
        String flat = text.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= MAX_COMMENT_LENGTH ? flat : flat.substring(0, MAX_COMMENT_LENGTH) + "…";
    }
}
