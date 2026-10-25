package Calcul;

public class CalculationException extends Exception {
    private final InputField field;

    public CalculationException(String message, InputField field) {
        super(message);
        this.field = field;
    }

    public InputField getField() {
        return field;
    }
}
