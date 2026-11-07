package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.notebook.NotebookException;
import calc.persistence.NotebookStore;
import calc.persistence.NotebookStoreException;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookRow.Status;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The budget of the brief: in a notebook of 500 lines with references, validating a line at the top (which recalculates the
 * 499 below, builds the 501 rows, and saves the notebook) takes less than 100 ms in the presenter. The measure includes the
 * automatic save, as the user's Enter does. The same notebook is measured in the window by NotebookFrameResponsivenessTest.
 */
class NotebookResponsivenessTest {
    static final int LINES = 500;
    private static final long BUDGET_MILLIS = 100;
    private static final int WARM_UP = 30;
    private static final int RUNS = 15;

    @TempDir
    Path data;

    /** Lines that refer to each other through $n, ans and two variables, with comments; one root every 100 lines. */
    static List<String> fiveHundredLines(boolean roots) {
        return lines(LINES, roots);
    }

    static List<String> lines(int count, boolean roots) {
        List<String> lines = new ArrayList<>(List.of("taux = 0,2", "v = 1", "taux * 10  # Calculs"));
        for (int number = 4; number <= count; number++) {
            lines.add(switch (number % 4) {
                case 0 -> "v = ($" + (number - 1) + " + v) / 2 * (1 + taux)";
                case 1 -> "ans + $" + (number / 2) + " * taux";
                case 2 -> (roots && number % 100 == 2 ? "√2 * v - $" : "v * 3 - $") + (number - 3) + "  # écart";
                default -> "$" + (number - 2) + " / 3 + ans";
            });
        }
        return lines;
    }

    static NotebookStore storeWith(Path directory, List<String> lines) throws NotebookException, NotebookStoreException {
        NotebookStore store = NotebookStore.open(directory);
        store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
        return store;
    }

    @Test
    void validatingALineAtTheTopOfFiveHundredLinesWithReferencesTakesLessThanTheBudget() throws Exception {
        try (NotebookStore store = storeWith(data, fiveHundredLines(true))) {
            NotebookDriver d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop()));
            assertEquals(LINES + 1, d.view.rows().size());
            long values = d.view.rows().stream().filter(NotebookRow::hasValue).count();
            long approximate = d.view.rows().stream().filter(row -> row.status() == Status.APPROXIMATE).count();
            long references = d.view.rows().stream().filter(row -> row.text().contains("$")).count();
            assertEquals(LINES, values, "every line computes a value, the benchmark measures real work");
            String before = d.row(LINES).result();

            double[] millis = new double[RUNS];
            for (int run = -WARM_UP; run < RUNS; run++) {
                d.click(0);
                String text = run % 2 == 0 ? "taux = 0,25" : "taux = 0,2";
                d.type(text);
                long start = System.nanoTime();
                d.enter();
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (run >= 0) {
                    millis[run] = elapsed;
                }
            }
            double[] sorted = millis.clone();
            Arrays.sort(sorted);
            System.out.printf("Presenter, notebook of %d lines (%d with a value, %d approximate, %d with references): Enter on"
                            + " line 1 (recalculation, %d rows, automatic save) best %.1f ms, median %.1f ms, worst %.1f ms (runs %s)%n",
                    LINES, values, approximate, references, d.view.rows().size(), sorted[0], sorted[RUNS / 2], sorted[RUNS - 1],
                    Arrays.toString(millis));
            assertNotEquals(before, d.row(LINES).result(), "the edit of line 1 reaches the last line");
            TimingBudget.assertWithin("the median Enter on line 1", sorted[RUNS / 2], BUDGET_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * The cost an optional preview while typing would have to pay on every pause of the typing: the same recalculation and
     * the same rows, without the save. Measured to decide (docs/adr/0010-editeur-carnet.md, section 6).
     */
    @Test
    void whatAPreviewWhileTypingWouldCost() throws Exception {
        try (NotebookStore store = storeWith(data, fiveHundredLines(true))) {
            calc.document.DocumentService document = new calc.document.DocumentService(store, store.load());
            double[] millis = new double[RUNS];
            calc.notebook.LineId first = document.current().notebook().line(1).id();
            for (int run = -WARM_UP; run < RUNS; run++) {
                String text = run % 2 == 0 ? "taux = 0,25" : "taux = 0,2";
                long start = System.nanoTime();
                document.apply(new calc.notebook.NotebookCommand.Edit(first, text));
                for (calc.notebook.LineResult result : document.current().results()) {
                    if (result.hasValue()) {
                        calc.format.ValueFormatter.format(result.value(), DisplayMode.AUTO);
                    }
                }
                double elapsed = (System.nanoTime() - start) / 1e6;
                if (run >= 0) {
                    millis[run] = elapsed;
                }
            }
            double[] sorted = millis.clone();
            Arrays.sort(sorted);
            System.out.printf("Preview cost, %d lines (recalculation of the lines below line 1 + formatting, no save): best %.1f ms,"
                            + " median %.1f ms, worst %.1f ms%n", LINES, sorted[0], sorted[RUNS / 2], sorted[RUNS - 1]);
            TimingBudget.assertWithin("the median cost of a preview", sorted[RUNS / 2], 150, TimeUnit.MILLISECONDS);
        }
    }
}
