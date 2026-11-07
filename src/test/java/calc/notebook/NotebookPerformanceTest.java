package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import calc.testing.TimingBudget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The budget of docs/adr/0008-semantique-carnet.md, section 7: in a notebook of 1000 lines with references, variables,
 * {@code ans} and comments, an edit of the first line is recalculated (the whole notebook) in less than 200 ms. Measured
 * twice: with exact rationals only (their size grows down the notebook), and with roots, whose intervals spread to
 * every line below. The measure is the best of several runs after a warm-up, printed with every run.
 */
class NotebookPerformanceTest {
    private static final int LINES = 1000;
    private static final long BUDGET_MILLIS = 200;
    private static final int WARM_UP = 20;
    private static final int RUNS = 10;

    @ParameterizedTest(name = "roots: {0}")
    @ValueSource(booleans = {false, true})
    void anEditOfTheFirstLineOfAThousandLinesIsRecalculatedWithinTheBudget(boolean roots) throws NotebookException {
        NotebookEditor editor = new NotebookEditor(Notebook.of(thousandLines(roots)));
        LineId first = editor.notebook().line(1).id();
        EvaluatedNotebook before = editor.current();
        long values = before.results().stream().filter(LineResult::hasValue).count();
        long intervals = before.results().stream().filter(r -> r.hasValue() && !r.value().isExact()).count();
        long references = before.results().stream().mapToLong(r -> r.references().size()).sum();
        assertEquals(LINES, values, "every line computes a value");

        for (int run = 0; run < WARM_UP; run++) {
            editor.apply(new NotebookCommand.Edit(first, run % 2 == 0 ? "taux = 0,25" : "taux = 0,2"));
            editor.current();
        }
        double[] millis = new double[RUNS];
        for (int run = 0; run < RUNS; run++) {
            String text = run % 2 == 0 ? "taux = 0,25" : "taux = 0,2";
            long start = System.nanoTime();
            editor.apply(new NotebookCommand.Edit(first, text));
            editor.current();
            millis[run] = (System.nanoTime() - start) / 1e6;
        }
        long undoStart = System.nanoTime();
        editor.undo();
        editor.current();
        double undoMillis = (System.nanoTime() - undoStart) / 1e6;

        double[] sorted = millis.clone();
        Arrays.sort(sorted);
        System.out.printf("Notebook of %d lines (%d with a value, %d approximate, %d references to lines): edit of"
                        + " line 1 recalculated in best %.1f ms, median %.1f ms, worst %.1f ms (runs %s); undo %.1f ms%n",
                LINES, values, intervals, references, sorted[0], sorted[RUNS / 2], sorted[RUNS - 1], Arrays.toString(millis),
                undoMillis);
        editor.apply(new NotebookCommand.Edit(first, "taux = 0,3"));
        assertNotEquals(before.result(LINES).value(), editor.current().result(LINES).value(), "the edit of line 1 reaches the last line");
        TimingBudget.assertWithin("the edit of line 1, best run", sorted[0], BUDGET_MILLIS, TimeUnit.MILLISECONDS);
    }

    // Lines that refer to each other through $n, ans and two variables, with trailing comments; one root every 100 lines.
    private static List<String> thousandLines(boolean roots) {
        List<String> lines = new ArrayList<>(List.of("taux = 0,2", "v = 1", "taux * 10  # Calculs"));
        for (int number = 4; number <= LINES; number++) {
            lines.add(switch (number % 4) {
                case 0 -> "v = ($" + (number - 1) + " + v) / 2 * (1 + taux)";
                case 1 -> "ans + $" + (number / 2) + " * taux";
                case 2 -> (roots && number % 100 == 2 ? "√2 * v - $" : "v * 3 - $") + (number - 3) + "  # écart";
                default -> "$" + (number - 2) + " / 3 + ans";
            });
        }
        return lines;
    }
}
