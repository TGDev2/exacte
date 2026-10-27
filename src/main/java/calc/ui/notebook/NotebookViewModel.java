package calc.ui.notebook;

import calc.format.DisplayMode;
import calc.ui.StatusTone;
import java.util.List;

/**
 * Immutable state handed to the view of the notebook; every text is ready to be shown.
 *
 * <p>{@code rows} is never empty: after the lines of the notebook comes one blank row, the new line, which becomes a
 * line of the notebook when it is validated with a text. The text of the active row is {@code activeDraft}, what the user is typing, which may differ from the
 * {@code text} of the row until it is validated; every other field shows the text of its row. {@code watermark} is the
 * help shown inside the empty field of an empty notebook, never saved; it is empty otherwise.
 *
 * @param activeIndex the index, from 0, of the active row
 * @param focus       where the focus must go once the view model is shown
 */
public record NotebookViewModel(
        List<NotebookRow> rows,
        int activeIndex,
        String activeDraft,
        NotebookFocus focus,
        boolean canUndo,
        boolean canRedo,
        DisplayMode displayMode,
        String statusMessage,
        StatusTone statusTone,
        String watermark) {

    public NotebookViewModel {
        rows = List.copyOf(rows);
        if (rows.isEmpty() || activeIndex < 0 || activeIndex >= rows.size()) {
            throw new IllegalArgumentException("active row " + activeIndex + " among " + rows.size());
        }
    }

    public NotebookRow activeRow() {
        return rows.get(activeIndex);
    }

    /** Whether the active row holds text the user has not validated yet. */
    public boolean activeDraftPending() {
        return !activeDraft.equals(activeRow().text());
    }
}
