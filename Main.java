import Calcul.CalculatorEngine;
import Calcul.FileCalculationHistoryStore;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorFrame;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CalculatorPresenter presenter = new CalculatorPresenter(
                    new CalculatorEngine(),
                    FileCalculationHistoryStore.forCurrentUser());
            CalculatorFrame calculatorFrame = new CalculatorFrame(presenter);
            calculatorFrame.setVisible(true);
        });
    }
}
