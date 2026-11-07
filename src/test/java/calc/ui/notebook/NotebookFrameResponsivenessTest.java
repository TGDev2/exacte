package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.persistence.NotebookStore;
import calc.testing.TimingBudget;
import calc.ui.DisplayModeStore;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The same budget as NotebookResponsivenessTest, in the window: validating the first line of 500 lines with references updates
 * the 501 rows, lays them out and paints them (off screen) in less than 100 ms. Every step of the measure runs on the event thread.
 */
@Tag("ui")
@DisplayName("Réactivité de la fenêtre du carnet (nécessite un affichage)")
class NotebookFrameResponsivenessTest {
    private static final long BUDGET_MILLIS = 100;
    private static final int WARM_UP = 30;
    private static final int RUNS = 15;

    @TempDir
    Path data;

    @Test
    void validatingTheFirstLineOfFiveHundredLinesUpdatesTheWindowWithinTheBudget() throws Exception {
        double median = measure(NotebookResponsivenessTest.LINES);
        TimingBudget.assertWithin("the median Enter on 500 lines", median, BUDGET_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** The limit of the model: no budget is promised, the measure is reported (docs/adr/0010-editeur-carnet.md). */
    @Test
    void theLimitOfTheModelFiveThousandLinesStaysUsable() throws Exception {
        double median = measure(calc.notebook.Notebook.MAX_LINES);
        TimingBudget.assertWithin("the median Enter at the limit of the model", median, 1000, TimeUnit.MILLISECONDS);
    }

    // Opens a window on a notebook of this many lines and returns the median time of an Enter on line 1, with layout and paint.
    private double measure(int lines) throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");

        try (NotebookStore store = NotebookResponsivenessTest.storeWith(data, NotebookResponsivenessTest.lines(lines, true))) {
            NotebookFrame[] holder = new NotebookFrame[1];
            long openStart = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new NotebookFrame(NotebookPresenter.open(store, DisplayModeStore.noop()),
                        new NotebookShortcuts(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
                holder[0].setVisible(true);
                holder[0].getContentPane().validate();
            });
            NotebookDriver.awaitWindows();
            double openMillis = (System.nanoTime() - openStart) / 1e6;
            NotebookFrame frame = holder[0];
            try {
                assertEquals(lines + 1, frame.rowCount());
                int probe = Math.min(lines, NotebookResponsivenessTest.LINES) - 1; // deeper lines of the benchmark tend to 0, whatever line 1 says
                String before = frame.row(probe).resultLabel.getText();
                BufferedImage image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);

                double[] stateMillis = new double[RUNS];
                double[] fullMillis = new double[RUNS];
                for (int run = -WARM_UP; run < RUNS; run++) {
                    String text = run % 2 == 0 ? "taux = 0,25" : "taux = 0,2";
                    // The user goes back to line 1 and types the new text: the next Enter validates it.
                    activateFirstLine(frame);
                    onEdt(() -> frame.row(0).field.setText(text));
                    long start = System.nanoTime();
                    onEdt(() -> pressEnter(frame));
                    NotebookDriver.awaitWindows();
                    double stateElapsed = (System.nanoTime() - start) / 1e6;
                    onEdt(() -> {
                        frame.getContentPane().validate();
                        frame.printAll(image.getGraphics());
                    });
                    double fullElapsed = (System.nanoTime() - start) / 1e6;
                    if (run >= 0) {
                        stateMillis[run] = stateElapsed;
                        fullMillis[run] = fullElapsed;
                    }
                }
                double[] state = stateMillis.clone();
                double[] full = fullMillis.clone();
                Arrays.sort(state);
                Arrays.sort(full);
                System.out.printf("Window, notebook of %d lines: opening %.0f ms; Enter on line 1 updates the %d rows in best %.1f ms,"
                                + " median %.1f ms, worst %.1f ms; with layout and an off-screen paint: best %.1f ms, median %.1f ms,"
                                + " worst %.1f ms%n",
                        lines, openMillis, frame.rowCount(), state[0], state[RUNS / 2], state[RUNS - 1],
                        full[0], full[RUNS / 2], full[RUNS - 1]);
                assertNotEquals(before, frame.row(probe).resultLabel.getText(),
                        "the edit of line 1 reaches the last line on the screen");
                return full[RUNS / 2];
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }
    }

    // A focus gained on the first field is what the mouse does: the presenter activates the line.
    private static void activateFirstLine(NotebookFrame frame) throws Exception {
        onEdt(() -> {
            if (frame.shown().activeIndex() != 0) {
                for (java.awt.event.FocusListener listener : frame.row(0).field.getFocusListeners()) {
                    listener.focusGained(new java.awt.event.FocusEvent(frame.row(0).field, java.awt.event.FocusEvent.FOCUS_GAINED));
                }
            }
        });
    }

    private static void pressEnter(NotebookFrame frame) {
        JComponent field = frame.row(frame.shown().activeIndex()).field;
        Object key = field.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        Action action = field.getActionMap().get(key);
        action.actionPerformed(new ActionEvent(field, ActionEvent.ACTION_PERFORMED, "enter"));
    }

    private static void onEdt(Runnable runnable) throws Exception {
        SwingUtilities.invokeAndWait(runnable);
    }
}
