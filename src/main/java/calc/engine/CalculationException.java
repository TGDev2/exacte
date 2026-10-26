package calc.engine;

public class CalculationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final InputField field;

    public CalculationException(String message, InputField field) {
        super(message);
        this.field = field;
    }

    public InputField getField() {
        return field;
    }
}
