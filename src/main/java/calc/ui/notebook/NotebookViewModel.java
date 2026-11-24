package calc.ui.notebook;

import calc.detail.LineDetailText;
import calc.format.DisplayMode;
import calc.ui.StatusTone;
import calc.ui.ThemeChoice;
import calc.ui.design.Theme;
import java.util.List;

/**
 * Immutable state handed to the view of the notebook; every text is ready to be shown.
 *
 * <p>{@code rows} is never empty: after the lines of the notebook comes one blank row, the new line, which becomes a
 * line of the notebook when it is validated with a text. The text of the active row is {@code activeDraft}, what the user is typing, which may differ from the
 * {@code text} of the row until it is validated; every other field shows the text of its row. {@code watermark} is the
 * help shown inside the empty field of an empty notebook, never saved; it is empty otherwise.
 *
 * <p>{@code title} is the title of the window (document name, modified marker), {@code canSave} whether « Enregistrer » can
 * write the document (false for a file from a newer version), {@code closeWindow} that the window must now be closed.
 *
 * <p>{@code theme} is the choice of Affichage > Thème, {@code effectiveTheme} the theme the window draws for it, and
 * {@code systemThemeAvailable} whether the choice of the system theme is offered.
 *
 * <p>{@code activeDetail} is the detail of the active line ({@link LineDetailText}), shown under it; it is empty for a line
 * without a value and while the notebook is being calculated. The rows that are sources of the active line say so
 * ({@link NotebookRow#source()}).
 *
 * <p>{@code saveState}, {@code lineCount} and {@code summary} are the texts of the status bar next to the message: how the document
 * is saved, how many lines the notebook holds (without the new line at the end) and, once the calculation has ended, how many
 * of them are exact, approximate or in error; the summary is empty while the notebook is being calculated or after an interruption.
 *
 * @param activeIndex  the index, from 0, of the active row
 * @param focus        where the focus must go once the view model is shown
 * @param activeDetail the sentences of the detail of the active line
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
        String watermark,
        String title,
        boolean canSave,
        boolean closeWindow,
        ThemeChoice theme,
        Theme effectiveTheme,
        boolean systemThemeAvailable,
        List<LineDetailText.Sentence> activeDetail,
        String saveState,
        String lineCount,
        String summary) {

    public NotebookViewModel {
        rows = List.copyOf(rows);
        activeDetail = List.copyOf(activeDetail);
        if (rows.isEmpty() || activeIndex < 0 || activeIndex >= rows.size()) {
            throw new IllegalArgumentException("active row " + activeIndex + " among " + rows.size());
        }
    }

    /**
     * Whether the three example notebooks are offered: no line is written, which is exactly when the watermark of the empty
     * notebook is shown.
     */
    public boolean examplesVisible() {
        return !watermark.isEmpty();
    }

    public NotebookRow activeRow() {
        return rows.get(activeIndex);
    }

    /** Whether the active row holds text the user has not validated yet. */
    public boolean activeDraftPending() {
        return !activeDraft.equals(activeRow().text());
    }
}
