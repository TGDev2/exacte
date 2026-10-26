package calc.testing;

import calc.engine.CalculationRecord;
import java.math.BigDecimal;

/** Reads the value of a calculation result in the form the decimal oracle works with. */
public final class ResultValues {
    private ResultValues() {
    }

    /** The result of the record, which must be exact with a finite decimal expansion. */
    public static BigDecimal exactDecimal(CalculationRecord record) {
        if (!record.isExact()) {
            throw new AssertionError("The result of " + record + " should be exact.");
        }
        return record.getResult().lower().toExactDecimal()
                .orElseThrow(() -> new AssertionError("The result of " + record + " should be a finite decimal."));
    }
}
