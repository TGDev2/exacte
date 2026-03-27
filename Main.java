import Calcul.CalculatorEngine;
import Graphique.CalculatorFrame;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            CalculatorFrame calculatorFrame = new CalculatorFrame(new CalculatorEngine());
            calculatorFrame.setVisible(true);
        });
    }
}
