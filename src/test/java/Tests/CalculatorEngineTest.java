package Tests;

import Calcul.CalculationException;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.InputField;
import Calcul.Operation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CalculatorEngineTest {
    private final CalculatorEngine engine = new CalculatorEngine();

    @Test
    void testAdditionSupportsCommaAndPoint() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, "1,5", "2.5");
        assertEquals("4", record.getFormattedResult(), "Addition should normalize comma and point inputs.");
        assertEquals("1,5 + 2,5 = 4", record.toString(), "History should stay readable.");
    }

    @Test
    void testRootUsesOnlyFirstOperand() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ROOT, "9", "");
        assertEquals("3", record.getFormattedResult(), "Root should only use the first operand.");
        assertEquals("\u221A(9) = 3", record.toString(), "Unary operations should be explicit in history.");
    }

    @Test
    void testDecimalAdditionAvoidsFloatingArtifacts() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, "0,1", "0,2");
        assertEquals("0,3", record.getFormattedResult(), "Decimal additions should stay exact for common chained values.");
        assertEquals("0,1 + 0,2 = 0,3", record.toString(), "History should not expose binary floating-point artifacts.");
    }

    @Test
    void testDivisionByZeroFailsClearly() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.DIVISION, "8", "0"),
                "Division by zero should return a targeted error.");
        assertEquals(
                "Le second nombre doit etre different de 0 pour une division.",
                exception.getMessage(),
                "Division by zero should return a targeted error.");
    }

    @Test
    void testNegativeRootFailsClearly() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ROOT, "-4", ""),
                "Negative root should return a targeted error.");
        assertEquals(
                "La racine carree d'un nombre negatif n'est pas definie en reel.",
                exception.getMessage(),
                "Negative root should return a targeted error.");
    }

    @Test
    void testMissingSecondOperandFailsClearly() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.MULTIPLICATION, "7", " "),
                "Missing second operand should be reported before calculation.");
        assertEquals(
                "Saisissez le second nombre.",
                exception.getMessage(),
                "Missing second operand should be reported before calculation.");
    }

    @Test
    void testInvalidFirstOperandFailsClearly() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, "abc", "2"),
                "Invalid first operand should be reported with a correction hint.");
        assertEquals(
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                exception.getMessage(),
                "Invalid first operand should be reported with a correction hint.");
    }

    @Test
    void testFractionalPowerFailsClearly() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, "9", "0,5"),
                "Fractional powers should be rejected explicitly to keep the supported scope predictable.");
        assertEquals(
                "L'exposant doit etre un entier.",
                exception.getMessage(),
                "Fractional powers should be rejected explicitly to keep the supported scope predictable.");
    }

    @Test
    void testDivisionRoundingIsStableForReuse() throws Exception {
        CalculationRecord record = engine.calculate(Operation.DIVISION, "1", "3");
        assertEquals("0,333333333333", record.getFormattedResult(), "Non-terminating divisions should use the shared rounding policy.");
        assertEquals("0,333333333333", record.getReusableValue(), "Reused values should match the displayed rounded result exactly.");
    }

    @Test
    void testPowerCalculationIsFormattedForReuse() throws Exception {
        CalculationRecord record = engine.calculate(Operation.POWER, "2", "3");
        assertEquals("8", record.getReusableValue(), "Reusable result should match the formatted result.");
    }


    @Test
    void testMissingOperationHasNoField() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(null, "1", "2"),
                "A missing operation should be rejected.");
        assertEquals(InputField.NONE, exception.getField(), "A missing operation belongs to no input field.");
    }

    @Test
    void testErrorsCarryTheFaultyField() {
        assertFieldOnError(InputField.FIRST, Operation.ADDITION, "", "2");
        assertFieldOnError(InputField.FIRST, Operation.ADDITION, "abc", "2");
        assertFieldOnError(InputField.SECOND, Operation.ADDITION, "1", "");
        assertFieldOnError(InputField.SECOND, Operation.ADDITION, "1", "abc");
        assertFieldOnError(InputField.SECOND, Operation.DIVISION, "1", "0");
        assertFieldOnError(InputField.FIRST, Operation.ROOT, "-4", "");
        assertFieldOnError(InputField.SECOND, Operation.POWER, "2", "1,5");
        assertFieldOnError(InputField.SECOND, Operation.POWER, "2", "5000");
        assertFieldOnError(InputField.FIRST, Operation.POWER, "0", "-1");
    }

    @Test
    void testHugeIntegerExponentReportsTheRangeNotTheInteger() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, "2", "99999999999"),
                "An out-of-range integer exponent should be rejected.");
        assertEquals(
                "L'exposant doit etre un entier compris entre -999 et 999.",
                exception.getMessage(),
                "An integer exponent beyond the range must not be reported as non-integer.");
    }

    private void assertFieldOnError(InputField expected, Operation operation, String first, String second) {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(operation, first, second),
                operation + " " + first + " " + second + " should be rejected.");
        assertEquals(expected, exception.getField(), operation + " " + first + " " + second + " should blame " + expected + ".");
    }
}
