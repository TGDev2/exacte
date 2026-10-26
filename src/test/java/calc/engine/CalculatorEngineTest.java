package calc.engine;

import calc.number.CertifiedValue;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorEngineTest {
    private static final String NBSP = String.valueOf((char) 0x00A0);
    private static final String NNBSP = String.valueOf((char) 0x202F);

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
                "Le second nombre doit être différent de 0 pour une division.",
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
                "La racine carrée d'un nombre négatif n'est pas définie en réel.",
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
                "L'exposant doit être un entier.",
                exception.getMessage(),
                "Fractional powers should be rejected explicitly to keep the supported scope predictable.");
    }

    @Test
    void testDivisionIsExactAndReusedExactly() throws Exception {
        CalculationRecord record = engine.calculate(Operation.DIVISION, "1", "3");
        assertEquals("0,333333333333…", record.getFormattedResult(), "Twelve significant digits, then « … ».");
        assertEquals("1/3", record.getReusableValue(), "The reusable value is the exact fraction, not the display.");
        assertTrue(record.isExact());
        CalculationRecord back = engine.calculate(Operation.MULTIPLICATION, record.getReusableValue(), "3");
        assertEquals("1", back.getFormattedResult(), "(1 / 3) * 3 is exactly 1.");
        assertEquals("1/3 * 3 = 1", back.toString());
    }

    @Test
    void testPowerCalculationIsFormattedForReuse() throws Exception {
        CalculationRecord record = engine.calculate(Operation.POWER, "2", "3");
        assertEquals("8", record.getFormattedResult(), "Reusable result should match the formatted result.");
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
        assertFieldOnError(InputField.SECOND, Operation.POWER, "2", "1e9");
        assertFieldOnError(InputField.FIRST, Operation.POWER, "0", "-1");
    }

    @Test
    void testHugeIntegerExponentReportsTheRangeNotTheInteger() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.POWER, "2", "99999999999"),
                "An out-of-range integer exponent should be rejected.");
        assertEquals(
                "L'exposant doit être compris entre -4096 et 4096.",
                exception.getMessage(),
                "An integer exponent beyond the range must not be reported as non-integer.");
    }

    @Test
    void testNoBreakSpacesGroupThousandsInTheIntegerPart() throws Exception {
        assertEquals("1000", engine.calculate(Operation.ADDITION, "1" + NBSP + "000", "0").getFormattedResult(), "A no-break space groups thousands.");
        assertEquals("1000,5", engine.calculate(Operation.ADDITION, "1" + NBSP + "000,5", "0").getFormattedResult(), "Before a decimal part too.");
        assertEquals(
                "1000000",
                engine.calculate(Operation.ADDITION, "1" + NNBSP + "000" + NNBSP + "000", "0").getFormattedResult(),
                "A narrow no-break space groups thousands.");
        assertEquals("12", engine.calculate(Operation.ADDITION, "12" + NBSP, "0").getFormattedResult(), "A trailing no-break space is trimmed.");
        assertEquals("12", engine.calculate(Operation.ADDITION, " \t12" + NBSP + NNBSP, "0").getFormattedResult(), "Edge spaces and tabs are trimmed.");
        assertEquals("-1000", engine.calculate(Operation.ADDITION, "-1" + NBSP + "000", "0").getFormattedResult(), "A sign may precede grouped digits.");
        assertEquals("1000/3", engine.calculate(Operation.ADDITION, "1" + NBSP + "000/3", "0").getReusableValue(), "Each member of a fraction may be grouped.");
    }

    @Test
    void testAnOrdinarySpaceInsideANumberIsRefusedWithItsOwnMessage() {
        for (String input : new String[] {"1 000", "-1 000", "1 000 000,5", "1 000" + NBSP + "000"}) {
            CalculationException exception = assertThrows(
                    CalculationException.class,
                    () -> engine.calculate(Operation.ADDITION, input, "0"),
                    "'" + input + "' should be rejected.");
            assertEquals("Le premier nombre contient une espace : séparez les milliers par une espace insécable ou supprimez l'espace.",
                    exception.getMessage(), "'" + input + "' should say what is wrong.");
            assertEquals(InputField.FIRST, exception.getField());
        }
    }

    @Test
    void testMalformedGroupingIsRejectedWithTheExistingMessage() {
        String expected = "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.";
        for (String input : new String[] {"1" + NBSP + "00", "1" + NBSP + "0000", "1" + NBSP + "000" + NBSP + "00", "1,000.5", "1.000.000",
                "1e", "٣", "1" + NBSP + "000,5" + NBSP + "000", "1\t000", "1 00", "1 000 ,5 0", "1/2/3", "1 / 2"}) {
            CalculationException exception = assertThrows(
                    CalculationException.class,
                    () -> engine.calculate(Operation.ADDITION, input, "0"),
                    "'" + input + "' should be rejected.");
            assertEquals(expected, exception.getMessage(), "'" + input + "' should use the existing invalid message.");
            assertEquals(InputField.FIRST, exception.getField(), "'" + input + "' should blame the first field.");
        }
    }

    @Test
    void testScientificNotationAndFractionsAreReadExactly() throws Exception {
        assertEquals("0,00000015", engine.calculate(Operation.ADDITION, "1,5e-7", "0").getFirstValue());
        assertEquals("1000", engine.calculate(Operation.ADDITION, "1E3", "0").getFirstValue());
        assertEquals("0,75", engine.calculate(Operation.ADDITION, "3/4", "0").getFirstValue());
        assertEquals("1/3", engine.calculate(Operation.ADDITION, "2/6", "0").getFirstValue());
        assertEquals("-0,00375", engine.calculate(Operation.ADDITION, "-1,5/4e2", "0").getFirstValue());
        assertEquals("1e1000", engine.calculate(Operation.ADDITION, "1e1000", "0").getFirstValue(), "Shortest exact text.");
    }

    @Test
    void testTheExplicitCasesOfTheEpic() throws Exception {
        assertEquals("3,33333333333… × 10⁻¹³", engine.calculate(Operation.DIVISION, "1", "3000000000000").getFormattedResult());
        assertEquals("4,85693574962… × 10⁻¹⁵", engine.calculate(Operation.POWER, "3", "-30").getFormattedResult());

        CalculationRecord root = engine.calculate(Operation.ROOT, "2", "");
        assertEquals("≈ 1,41421356237…", root.getFormattedResult());
        assertFalse(root.isExact());
        assertEquals("± 2,1 × 10⁻²⁵", root.getFormatted().errorBound().orElseThrow(), "The error bound around the centre.");
        assertEquals("√(2) ≈ 1,41421356237…", root.toString());

        CalculationRecord quarter = engine.calculate(Operation.ROOT, "9/4", "");
        assertEquals("1,5", quarter.getFormattedResult());
        assertTrue(quarter.isExact(), "The root of 9/4 is exactly 3/2.");

        CalculationException exponent = assertThrows(CalculationException.class, () -> engine.calculate(Operation.POWER, "2", "0,5"));
        assertEquals("L'exposant doit être un entier.", exponent.getMessage());
        assertEquals(InputField.SECOND, exponent.getField());

        assertEquals("1000", engine.calculate(Operation.ADDITION, "1" + NBSP + "000", "0").getFirstValue());
    }

    @Test
    void testEveryRefusalOfTheCoreHasAFrenchMessageAndAField() throws Exception {
        CertifiedValue root = engine.calculate(Operation.ROOT, "2", "").getResult();
        CertifiedValue maybeZero = engine.calculate(Operation.SUBTRACTION, Operand.known(root), Operand.known(root)).getResult();
        assertFalse(maybeZero.isExact(), "An interval minus itself is an interval around 0.");

        assertRefusal(Operation.DIVISION, Operand.typed("1"), Operand.known(maybeZero), InputField.SECOND,
                "Le second nombre est une valeur approchée qui peut valoir 0 : la division est impossible.");
        assertRefusal(Operation.POWER, Operand.known(maybeZero), Operand.typed("-2"), InputField.FIRST,
                "Le premier nombre est une valeur approchée qui peut valoir 0 : il ne peut pas être élevé à une puissance négative.");
        assertRefusal(Operation.ROOT, Operand.known(maybeZero), Operand.typed(""), InputField.FIRST,
                "Le premier nombre est une valeur approchée qui peut être négative : sa racine carrée n'est pas définie.");
        assertRefusal(Operation.POWER, Operand.typed("2"), Operand.known(root), InputField.SECOND,
                "L'exposant est une valeur approchée : il doit être un entier exact.");
        assertRefusal(Operation.MULTIPLICATION, Operand.typed("1e1233"), Operand.typed("1e1233"), InputField.NONE,
                "Le résultat dépasse la taille maximale d'un calcul exact (réduisez les nombres saisis).");
        assertRefusal(Operation.POWER, Operand.typed("3"), Operand.typed("4096"), InputField.SECOND,
                "Le résultat dépasse la taille maximale d'un calcul exact (réduisez l'exposant ou la base).");
        assertRefusal(Operation.ADDITION, Operand.typed("1e1234"), Operand.typed("0"), InputField.FIRST,
                "Le premier nombre est trop grand ou a trop de décimales pour un calcul exact.");
        assertRefusal(Operation.ADDITION, Operand.typed("1"), Operand.typed("1e10000"), InputField.SECOND,
                "L'exposant de la notation scientifique du second nombre doit être compris entre -9999 et 9999.");
        assertRefusal(Operation.ADDITION, Operand.typed("1/0"), Operand.typed("1"), InputField.FIRST,
                "Le premier nombre est une fraction dont le dénominateur vaut 0.");
    }

    @Test
    void testPointStaysADecimalSeparator() throws Exception {
        assertEquals("1", engine.calculate(Operation.ADDITION, "1.000", "0").getFormattedResult(), "1.000 is one, not one thousand.");
    }

    @Test
    void testBlankInputMadeOfNoBreakSpacesIsMissing() {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(Operation.ADDITION, "  ", "0"),
                "Only no-break spaces means no number was typed.");
        assertEquals("Saisissez le premier nombre.", exception.getMessage(), "It should be reported as missing, not invalid.");
    }

    @Test
    void testHistoryShowsTheNormalizedOperandWithoutSpaces() throws Exception {
        CalculationRecord record = engine.calculate(Operation.ADDITION, "1" + NBSP + "000,5", "2" + NNBSP + "000");
        assertEquals("1000,5 + 2000 = 3000,5", record.toString(), "History should show operands without grouping spaces.");
    }

    private void assertRefusal(Operation operation, Operand first, Operand second, InputField field, String message) {
        CalculationException exception = assertThrows(CalculationException.class, () -> engine.calculate(operation, first, second));
        assertEquals(message, exception.getMessage());
        assertEquals(field, exception.getField());
    }

    private void assertFieldOnError(InputField expected, Operation operation, String first, String second) {
        CalculationException exception = assertThrows(
                CalculationException.class,
                () -> engine.calculate(operation, first, second),
                operation + " " + first + " " + second + " should be rejected.");
        assertEquals(expected, exception.getField(), operation + " " + first + " " + second + " should blame " + expected + ".");
    }
}
