package calc.engine;

import calc.number.CertifiedValue;
import java.util.Objects;

/**
 * One operand of {@link CalculatorEngine#calculate(Operation, Operand, Operand)}: either a text typed by the user, parsed
 * by the engine, or a value already known (a result put back in a field), used as it is so that reusing a result never
 * degrades it.
 */
public final class Operand {
    private final String text;
    private final CertifiedValue value;

    private Operand(String text, CertifiedValue value) {
        this.text = text;
        this.value = value;
    }

    /** A typed text, parsed by the engine; {@code null} counts as an empty field. */
    public static Operand typed(String text) {
        return new Operand(text, null);
    }

    /** A value already known, exact or approximate. */
    public static Operand known(CertifiedValue value) {
        return new Operand(null, Objects.requireNonNull(value, "value"));
    }

    CertifiedValue resolve(InputField field) throws CalculationException {
        return value != null ? value : OperandParser.parse(text, field);
    }
}
