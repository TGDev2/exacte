package calc.ui.notebook;

import calc.format.DisplayMode;
import java.awt.Frame;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * Plays the part of the view for the presenter tests: it owns the text of the active field, like a JTextField would, and
 * hands it to the presenter with every intention. The keyboard of the user becomes calls on this class. Every intention runs
 * on the event thread and returns once its calculation has ended, so what is read here is the final state of the line.
 */
final class NotebookDriver {
    private static final long PATIENCE_SECONDS = 60;

    final NotebookPresenter presenter;
    NotebookViewModel view;
    private String field;

    NotebookDriver(NotebookPresenter presenter) {
        this.presenter = presenter;
        show(presenter::initialView);
    }

    // An intention of the user, on the event thread as in the window, then the state once the calculation it started has ended.
    // Where the focus goes and whether the window closes are the answer to the intention itself, and only it says so.
    private void show(Supplier<NotebookViewModel> intention) {
        NotebookViewModel told = onEdt(intention);
        awaitIdle();
        NotebookViewModel settled = onEdt(presenter::currentView);
        view = new NotebookViewModel(settled.rows(), settled.activeIndex(), settled.activeDraft(), told.focus(), settled.canUndo(),
                settled.canRedo(), settled.displayMode(), settled.statusMessage(), settled.statusTone(), settled.watermark(),
                settled.title(), settled.canSave(), told.closeWindow());
        field = settled.activeDraft();
    }

    /** Until the calculation under way has ended and the event thread has taken in all it produced. */
    void awaitIdle() {
        await(presenter::isCalculating);
    }

    /** The same for every window of the test. */
    static void awaitWindows() {
        await(() -> {
            for (Frame frame : Frame.getFrames()) {
                if (frame instanceof NotebookFrame window && window.isCalculating()) {
                    return true;
                }
            }
            return false;
        });
    }

    // A calculation that never ends fails the test instead of hanging it.
    private static void await(Supplier<Boolean> calculating) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (onEdt(calculating)) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the calculation has not ended after " + PATIENCE_SECONDS + " s");
            }
            Thread.onSpinWait();
        }
    }

    private static <T> T onEdt(Supplier<T> work) {
        if (SwingUtilities.isEventDispatchThread()) {
            return work.get();
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(work.get());
            } catch (Throwable thrown) {
                result.completeExceptionally(thrown);
            }
        });
        try {
            return result.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Error error) {
                throw error;
            }
            if (failed.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(failed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
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
        show(() -> presenter.onEnter(field));
        return this;
    }

    /** Types {@code text} and validates it with Enter. */
    NotebookDriver line(String text) {
        return type(text).enter();
    }

    NotebookDriver up() {
        show(() -> presenter.onMove(-1, field));
        return this;
    }

    NotebookDriver down() {
        show(() -> presenter.onMove(1, field));
        return this;
    }

    NotebookDriver escape() {
        show(() -> presenter.onCancelEdit(field));
        return this;
    }

    NotebookDriver undo() {
        show(() -> presenter.onUndo(field));
        return this;
    }

    NotebookDriver redo() {
        show(() -> presenter.onRedo(field));
        return this;
    }

    NotebookDriver insertAbove() {
        show(() -> presenter.onInsertAbove(field));
        return this;
    }

    NotebookDriver insertBelow() {
        show(() -> presenter.onInsertBelow(field));
        return this;
    }

    NotebookDriver delete() {
        show(() -> presenter.onDelete(field));
        return this;
    }

    NotebookDriver moveLineUp() {
        show(() -> presenter.onMoveLineUp(field));
        return this;
    }

    NotebookDriver moveLineDown() {
        show(() -> presenter.onMoveLineDown(field));
        return this;
    }

    NotebookDriver clear() {
        show(() -> presenter.onClear(field));
        return this;
    }

    NotebookDriver mode(DisplayMode mode) {
        show(() -> presenter.onDisplayModeChanged(mode, field));
        return this;
    }

    NotebookDriver click(int index) {
        show(() -> presenter.onActivate(index, field));
        return this;
    }

    NotebookDriver close() {
        show(() -> presenter.onQuit(field));
        return this;
    }

    NotebookDriver nextMode() {
        show(() -> presenter.onNextDisplayMode(field));
        return this;
    }

    NotebookDriver newDocument() {
        show(() -> presenter.onNew(field));
        return this;
    }

    NotebookDriver open() {
        show(() -> presenter.onOpen(field));
        return this;
    }

    NotebookDriver save() {
        show(() -> presenter.onSave(field));
        return this;
    }

    NotebookDriver saveAs() {
        show(() -> presenter.onSaveAs(field));
        return this;
    }

    NotebookDriver export(NotebookDialogs.ExportFormat format) {
        show(() -> presenter.onExport(format, field));
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
