package calc.ui.notebook;

import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * The result a line would have with the text being typed, calculated off the event thread (docs/adr/0014-calcul-hors-interface.md)
 * and never applied to the notebook. The event thread asks for one line of a notebook the presenter edited without applying it,
 * with the results already known above that line; one task on the executor evaluates that line, and only that one, then hands
 * the result back with {@link SwingUtilities#invokeLater}. A new request, or {@link #cancel()}, ends the one before: a task that
 * has not started yet does nothing, and whatever an older task still posts is thrown away.
 *
 * <p>It shares nothing with {@link NotebookRecalculation}: its own executor, its own generations, and the results above the line
 * are a copy. Every method runs on the event thread; only {@link Task#run()} runs elsewhere, and it touches nothing else.
 */
final class NotebookPreview<K> {
    /** The name of the thread that calculates the previews in the application. */
    static final String THREAD_NAME = "notebook-preview";

    private static final Logger LOGGER = Logger.getLogger(NotebookPreview.class.getName());

    private final Executor executor;
    private final BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine;
    private final BiConsumer<K, LineResult> receiver;
    // Written on the event thread, read by the task: a request it no longer answers is skipped before any evaluation.
    private volatile long currentGeneration;

    /**
     * @param executor       what runs the previews; never the event thread
     * @param aroundEachLine called on the calculating thread with the text of each line evaluated and its evaluation
     * @param receiver       hears, on the event thread, the key of the current request and the result of its line, or null
     *                       when the preview failed: either way the request has ended
     */
    NotebookPreview(Executor executor, BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine,
                    BiConsumer<K, LineResult> receiver) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.aroundEachLine = Objects.requireNonNull(aroundEachLine, "aroundEachLine");
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /** One daemon thread, which goes away when nothing is left to preview: it never keeps the process alive. */
    static Executor newBackgroundThread() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /**
     * Replaces the preview asked for by that of the line at {@code index} (from 0) of {@code edited}. {@code kept} are the
     * results of the first lines of {@code edited}, at most {@code index} of them: the lines above that are still to evaluate are
     * evaluated too, the lines below never. {@code key} comes back with the result, so that the owner can tell what it is for.
     */
    void request(Notebook edited, int index, List<LineResult> kept, K key) {
        Objects.requireNonNull(edited, "edited");
        Objects.requireNonNull(key, "key");
        if (index < 0 || index >= edited.size() || kept.size() > index) {
            throw new IllegalArgumentException("line " + index + " of " + edited.size() + " with " + kept.size() + " results kept");
        }
        executor.execute(new Task(++currentGeneration, edited, index, List.copyOf(kept), key));
    }

    /** Ends the preview asked for, if any: nothing more arrives from it. */
    void cancel() {
        currentGeneration++;
    }

    /** The evaluation of one line, from the results kept to that line. */
    private final class Task implements Runnable {
        private final long generation;
        private final Notebook lines;
        private final int index;
        private final List<LineResult> kept;
        private final K key;

        Task(long generation, Notebook lines, int index, List<LineResult> kept, K key) {
            this.generation = generation;
            this.lines = lines;
            this.index = index;
            this.kept = kept;
            this.key = key;
        }

        @Override
        public void run() {
            try {
                NotebookEvaluator.LineByLine evaluation = NotebookEvaluator.lineByLine(lines, kept);
                LineResult result = null;
                // Between two lines: an operation on huge numbers in progress is never interrupted.
                for (int line = kept.size(); line <= index; line++) {
                    if (generation != currentGeneration) {
                        return;
                    }
                    result = aroundEachLine.apply(evaluation.nextText(), evaluation::evaluateNext);
                }
                post(result);
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError failure) {
                // A preview is only a hint: the line shows none, and Enter still calculates and says what went wrong.
                LOGGER.log(Level.WARNING, "The preview of a line stopped", failure);
                post(null);
            }
        }

        private void post(LineResult result) {
            SwingUtilities.invokeLater(() -> {
                if (generation == currentGeneration) {
                    receiver.accept(key, result);
                }
            });
        }
    }
}
