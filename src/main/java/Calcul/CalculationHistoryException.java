package Calcul;

public class CalculationHistoryException extends Exception {
    public CalculationHistoryException(String message, Throwable cause) {
        super(message, cause);
    }

    public CalculationHistoryException(String message) {
        super(message);
    }
}
