package calc.ui.notebook;

import calc.format.DisplayMode;
import java.util.List;

/**
 * Plays the part of the view for the presenter tests: it owns the text of the active field, like a JTextField would, and
 * hands it to the presenter with every intention. The keyboard of the user becomes calls on this class.
 */
final class NotebookDriver {
    final NotebookPresenter presenter;
    NotebookViewModel view;
    private String field;

    NotebookDriver(NotebookPresenter presenter) {
        this.presenter = presenter;
        show(presenter.initialView());
    }

    private NotebookViewModel show(NotebookViewModel next) {
        view = next;
        field = next.activeDraft();
        return next;
    }

    /** Types {@code text} into the active field, replacing what it holds (nothing is validated). */
    NotebookDriver type(String text) {
        field = text;
        return this;
    }

    /** The text now in the active field. */
    String field() {
        return field;
    }

    NotebookDriver enter() {
        show(presenter.onEnter(field));
        return this;
    }

    /** Types {@code text} and validates it with Enter. */
    NotebookDriver line(String text) {
        return type(text).enter();
    }

    NotebookDriver up() {
        show(presenter.onMove(-1, field));
        return this;
    }

    NotebookDriver down() {
        show(presenter.onMove(1, field));
        return this;
    }

    NotebookDriver escape() {
        show(presenter.onCancelEdit(field));
        return this;
    }

    NotebookDriver undo() {
        show(presenter.onUndo(field));
        return this;
    }

    NotebookDriver redo() {
        show(presenter.onRedo(field));
        return this;
    }

    NotebookDriver insertAbove() {
        show(presenter.onInsertAbove(field));
        return this;
    }

    NotebookDriver insertBelow() {
        show(presenter.onInsertBelow(field));
        return this;
    }

    NotebookDriver delete() {
        show(presenter.onDelete(field));
        return this;
    }

    NotebookDriver moveLineUp() {
        show(presenter.onMoveLineUp(field));
        return this;
    }

    NotebookDriver moveLineDown() {
        show(presenter.onMoveLineDown(field));
        return this;
    }

    NotebookDriver clear() {
        show(presenter.onClear(field));
        return this;
    }

    NotebookDriver mode(DisplayMode mode) {
        show(presenter.onDisplayModeChanged(mode, field));
        return this;
    }

    NotebookDriver click(int index) {
        show(presenter.onActivate(index, field));
        return this;
    }

    NotebookDriver close() {
        show(presenter.onClosing(field));
        return this;
    }

    // ------------------------------------------------------------------------------------------------ reading

    NotebookRow row(int number) {
        return view.rows().get(number - 1);
    }

    /** The result texts of the rows, in order. */
    List<String> results() {
        return view.rows().stream().map(NotebookRow::result).toList();
    }

    /** The source texts of the rows, in order. */
    List<String> texts() {
        return view.rows().stream().map(NotebookRow::text).toList();
    }

    int active() {
        return view.activeIndex() + 1;
    }
}
