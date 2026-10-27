package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operand;
import calc.engine.Operation;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** History entries turned into lines (docs/adr/0008-semantique-carnet.md, section 8). */
class HistoryBridgeTest {
    private final CalculatorEngine engine = new CalculatorEngine();

    @Test
    void entriesBecomeLinesOfTheSameValue() throws CalculationException {
        assertLine("1,5 + 2,5", Operation.ADDITION, "1,5", "2.5");
        assertLine("8 / 4", Operation.DIVISION, "8", "4");
        assertLine("2 ^ 3", Operation.POWER, "2", "3");
        assertLine("10 - 0,1", Operation.SUBTRACTION, "10", "0,1");
        assertLine("1000000 * 3", Operation.MULTIPLICATION, "1000000", "3");
        assertLine("√(9)", Operation.ROOT, "9", "");
    }

    @Test
    void negativeOperandsAndFractionsAreBetweenParentheses() throws CalculationException {
        assertLine("2 ^ (-3)", Operation.POWER, "2", "-3");
        assertLine("(-2) ^ 2", Operation.POWER, "-2", "2");
        assertLine("(1/3) * 3", Operation.MULTIPLICATION, "1/3", "3");
        assertLine("(-1,5) - (-2)", Operation.SUBTRACTION, "-1,5", "-2");
        assertLine("1 / (2/3)", Operation.DIVISION, "1", "2/3");
        assertLine("√(1/3)", Operation.ROOT, "1/3", "");
    }

    @Test
    void anApproximateRootGivesTheSameInterval() throws CalculationException {
        CalculationRecord entry = engine.calculate(Operation.ROOT, "2", "");
        assertFalse(entry.getResult().isExact());
        assertEquals(Optional.of("√(2)"), HistoryBridge.toLine(entry));
        assertEquals(entry.getResult(), NotebookTestSupport.evaluated("√(2)").result(1).value());
    }

    @Test
    void anEntryThatNoLineCanWriteGivesNoLine() throws CalculationException {
        CalculationRecord root = engine.calculate(Operation.ROOT, "2", "");
        CalculationRecord remembered = engine.calculate(Operation.MULTIPLICATION, Operand.known(root.getResult()),
                Operand.typed("3"));
        assertTrue(remembered.hasApproximateOperand());
        assertEquals(Optional.empty(), HistoryBridge.toLine(remembered), "an interval has no literal");

        CalculationRecord huge = engine.calculate(Operation.ADDITION, "1".repeat(1100), "1");
        assertEquals(Optional.empty(), HistoryBridge.toLine(huge), "the line would be longer than 1000 characters");
    }

    private void assertLine(String expected, Operation operation, String first, String second)
            throws CalculationException {
        CalculationRecord entry = engine.calculate(operation, first, second);
        Optional<String> line = HistoryBridge.toLine(entry);
        assertEquals(Optional.of(expected), line);
        assertEquals(entry.getResult(), NotebookTestSupport.evaluated(expected).result(1).value());
    }
}
