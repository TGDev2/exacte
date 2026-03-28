package Tests;

import Calcul.CalculationException;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.Operation;

public class CalculatorEngineTest {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        CalculatorEngine engine = new CalculatorEngine();

        testAdditionSupportsCommaAndPoint(engine);
        testDecimalAdditionAvoidsFloatingArtifacts(engine);
        testRootUsesOnlyFirstOperand(engine);
        testDivisionByZeroFailsClearly(engine);
        testNegativeRootFailsClearly(engine);
        testMissingSecondOperandFailsClearly(engine);
        testInvalidFirstOperandFailsClearly(engine);
        testFractionalPowerFailsClearly(engine);
        testDivisionRoundingIsStableForReuse(engine);
        testPowerCalculationIsFormattedForReuse(engine);

        System.out.println("All tests passed (" + assertions + " assertions).");
    }

    private static void testAdditionSupportsCommaAndPoint(CalculatorEngine engine) throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, "1,5", "2.5");
        assertEquals("4", record.getFormattedResult(), "Addition should normalize comma and point inputs.");
        assertEquals("1,5 + 2,5 = 4", record.toString(), "History should stay readable.");
    }

    private static void testRootUsesOnlyFirstOperand(CalculatorEngine engine) throws Exception {
        CalculationRecord record = engine.calculate(Operation.ROOT, "9", "");
        assertEquals("3", record.getFormattedResult(), "Root should only use the first operand.");
        assertEquals("\u221A(9) = 3", record.toString(), "Unary operations should be explicit in history.");
    }

    private static void testDecimalAdditionAvoidsFloatingArtifacts(CalculatorEngine engine) throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, "0,1", "0,2");
        assertEquals("0,3", record.getFormattedResult(), "Decimal additions should stay exact for common chained values.");
        assertEquals("0,1 + 0,2 = 0,3", record.toString(), "History should not expose binary floating-point artifacts.");
    }

    private static void testDivisionByZeroFailsClearly(CalculatorEngine engine) {
        assertThrows(
                "Le second nombre doit etre different de 0 pour une division.",
                () -> engine.calculate(Operation.DIVISION, "8", "0"),
                "Division by zero should return a targeted error.");
    }

    private static void testNegativeRootFailsClearly(CalculatorEngine engine) {
        assertThrows(
                "La racine carree d'un nombre negatif n'est pas definie en reel.",
                () -> engine.calculate(Operation.ROOT, "-4", ""),
                "Negative root should return a targeted error.");
    }

    private static void testMissingSecondOperandFailsClearly(CalculatorEngine engine) {
        assertThrows(
                "Saisissez le second nombre.",
                () -> engine.calculate(Operation.MULTIPLICATION, "7", " "),
                "Missing second operand should be reported before calculation.");
    }

    private static void testInvalidFirstOperandFailsClearly(CalculatorEngine engine) {
        assertThrows(
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                () -> engine.calculate(Operation.ADDITION, "abc", "2"),
                "Invalid first operand should be reported with a correction hint.");
    }

    private static void testFractionalPowerFailsClearly(CalculatorEngine engine) {
        assertThrows(
                "L'exposant doit etre un entier.",
                () -> engine.calculate(Operation.POWER, "9", "0,5"),
                "Fractional powers should be rejected explicitly to keep the supported scope predictable.");
    }

    private static void testDivisionRoundingIsStableForReuse(CalculatorEngine engine) throws Exception {
        CalculationRecord record = engine.calculate(Operation.DIVISION, "1", "3");
        assertEquals("0,333333333333", record.getFormattedResult(), "Non-terminating divisions should use the shared rounding policy.");
        assertEquals("0,333333333333", record.getReusableValue(), "Reused values should match the displayed rounded result exactly.");
    }

    private static void testPowerCalculationIsFormattedForReuse(CalculatorEngine engine) throws Exception {
        CalculationRecord record = engine.calculate(Operation.POWER, "2", "3");
        assertEquals("8", record.getReusableValue(), "Reusable result should match the formatted result.");
    }

    private static void assertEquals(String expected, String actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " Expected: " + expected + ", actual: " + actual);
        }
    }

    private static void assertThrows(String expectedMessage, ThrowingRunnable runnable, String message) {
        assertions++;
        try {
            runnable.run();
            throw new AssertionError(message + " Expected an exception with message: " + expectedMessage);
        } catch (CalculationException exception) {
            if (!expectedMessage.equals(exception.getMessage())) {
                throw new AssertionError(message + " Expected: " + expectedMessage + ", actual: " + exception.getMessage());
            }
        } catch (Exception exception) {
            throw new AssertionError(message + " Unexpected exception type: " + exception.getClass().getName(), exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
