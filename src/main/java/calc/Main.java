package calc;

import calc.engine.CalculatorEngine;
import calc.persistence.JsonHistoryStore;
import calc.ui.CalculatorPresenter;
import calc.ui.CalculatorFrame;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            useSystemLookAndFeel();
            JsonHistoryStore historyStore = JsonHistoryStore.openForCurrentUser();
            // The system releases the lock with the process; releasing it on a normal exit is only tidier.
            Runtime.getRuntime().addShutdownHook(new Thread(historyStore::close, "history-lock-release"));
            CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), historyStore);
            CalculatorFrame calculatorFrame = new CalculatorFrame(presenter);
            calculatorFrame.setVisible(true);
        });
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Cannot apply the system look and feel, using the default one", exception);
        }
    }
}
