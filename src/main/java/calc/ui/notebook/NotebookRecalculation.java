package calc.ui.notebook;

import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;

/**
 * The results of the notebook, calculated off the event thread (docs/adr/0014-calcul-hors-interface.md). The event thread asks
 * for a notebook to be calculated and reads what is known so far; one task on the executor evaluates the lines and hands them
 * back with {@link SwingUtilities#invokeLater}, under the number of its generation. A new request ends the generation: its task
 * stops at the end of the line it is evaluating and whatever it still posts is thrown away.
 *
 * <p>Every method runs on the event thread. Only {@link Generation#run()} runs elsewhere, and it touches nothing else.
 */
final class NotebookRecalculation {
    /** The name of the thread that calculates in the application. */
    static final String THREAD_NAME = "notebook-recalc";

    /** What the owner hears, always on the event thread and never from an ended generation. */
    interface Events {
        /** More lines are known. */
        void progressed();

        /** Every line of the notebook is known. */
        void finished();

        /** The calculation stopped on a failure of its own: the lines known so far stay. */
        void failed();
    }

    private static final Logger LOGGER = Logger.getLogger(NotebookRecalculation.class.getName());

    private final Executor executor;
    private final BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine;
    private final Events events;
    private long currentGeneration;
    private Generation running;
    private Notebook notebook = Notebook.empty();
    // The results of the first lines of the notebook.
    private final List<LineResult> known = new ArrayList<>();

    /**
     * @param executor       what runs the calculation; never the event thread
     * @param aroundEachLine called on the calculating thread with the text of each line really evaluated and its evaluation
     */
    NotebookRecalculation(Executor executor, BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine, Events events) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.aroundEachLine = Objects.requireNonNull(aroundEachLine, "aroundEachLine");
        this.events = Objects.requireNonNull(events, "events");
    }

    /** One daemon thread, which goes away when nothing is left to calculate: it never keeps the process alive. */
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
     * Replaces the calculation under way by that of {@code next}, keeping the results above its first line that changed.
     *
     * @return true when nothing is left to evaluate: every result is known at once, and no event will say so
     */
    boolean calculate(Notebook next) {
        stop();
        known.subList(NotebookEvaluator.reusableResults(notebook, known, next).size(), known.size()).clear();
        notebook = next;
        if (known.size() == next.size()) {
            return true;
        }
        running = new Generation(++currentGeneration, next, List.copyOf(known));
        executor.execute(running);
        return false;
    }

    /** Ends the calculation under way, if any: the lines known so far stay known, and nothing more arrives. */
    void stop() {
        if (running != null) {
            running.cancelled = true;
            running = null;
            currentGeneration++;
        }
    }

    boolean isRunning() {
        return running != null;
    }

    /** Whether every line of the notebook has its result. */
    boolean isComplete() {
        return known.size() == notebook.size();
    }

    /** The results of the first lines of the notebook, as they are now. */
    List<LineResult> known() {
        return List.copyOf(known);
    }

    int knownCount() {
        return known.size();
    }

    LineResult result(int index) {
        return known.get(index);
    }

    /** What arrives from a generation: only the current one is listened to. */
    private void receive(Generation generation) {
        if (generation.number != currentGeneration) {
            return;
        }
        // Read first: once the task says it has ended, the count below is its last one.
        boolean ended = generation.ended;
        for (int index = known.size(), done = generation.done; index < done; index++) {
            known.add(generation.results[index]);
        }
        if (!ended) {
            events.progressed();
            return;
        }
        running = null;
        if (generation.failed) {
            events.failed();
        } else {
            events.finished();
        }
    }

    /** One calculation, from the results kept to the last line of its notebook. */
    private final class Generation implements Runnable {
        final long number;
        final Notebook lines;
        final List<LineResult> kept;
        // Written by the task; "done" published each line, which is what makes the array safe to read up to it.
        final LineResult[] results;
        volatile int done;
        volatile boolean ended;
        volatile boolean failed;
        volatile boolean cancelled;
        // Several lines finished while the event thread is busy make one event.
        final AtomicBoolean posted = new AtomicBoolean();

        Generation(long number, Notebook lines, List<LineResult> kept) {
            this.number = number;
            this.lines = lines;
            this.kept = kept;
            this.results = new LineResult[lines.size()];
            this.done = kept.size();
        }

        @Override
        public void run() {
            boolean completed = false;
            try {
                NotebookEvaluator.LineByLine evaluation = NotebookEvaluator.lineByLine(lines, kept);
                // Between two lines: an operation on huge numbers in progress is never interrupted.
                while (!cancelled && !evaluation.isFinished()) {
                    results[done] = aroundEachLine.apply(evaluation.nextText(), evaluation::evaluateNext);
                    done++;
                    post();
                }
                completed = true;
            } catch (RuntimeException | StackOverflowError | OutOfMemoryError failure) {
                if (!cancelled) {
                    LOGGER.log(Level.SEVERE, "The calculation of the notebook stopped", failure);
                }
            } finally {
                // Whatever ended the task, the event thread hears of it: the lines must not say they are being calculated for ever.
                failed = !completed;
                ended = true;
                post();
            }
        }

        private void post() {
            if (posted.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(() -> {
                    // Before reading: a line finished after this still posts.
                    posted.set(false);
                    receive(this);
                });
            }
        }
    }
}
