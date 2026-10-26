package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/** Every refusal of the engine says the right thing and names the right field, whatever the operands. */
@Tag("property")
class ErrorProperties {
    private static final String INVALID_FIRST =
            "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.";
    private static final String INVALID_SECOND =
            "Le second nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.";

    private final CalculatorEngine engine = new CalculatorEngine();

    @Provide
    Arbitrary<DecimalText> operands() {
        return DecimalGenerators.decimals(60);
    }

    @Provide
    Arbitrary<DecimalText> bareDecimalPointsAtTheBound() {
        return DecimalGenerators.bareDecimalPointAtTheBound();
    }

    @Provide
    Arbitrary<DecimalText> zeros() {
        return DecimalGenerators.zeros();
    }

    @Provide
    Arbitrary<DecimalText> negatives() {
        return DecimalGenerators.nonZero(DecimalGenerators.decimals(60)).map(value -> {
            String unsigned = value.text().replaceFirst("^[+-]", "");
            return new DecimalText("-" + unsigned, value.value().abs().negate());
        });
    }

    @Provide
    Arbitrary<DecimalText> fractionalExponents() {
        return DecimalGenerators.decimals(20).filter(value -> value.value().stripTrailingZeros().scale() > 0);
    }

    @Provide
    Arbitrary<DecimalText> outOfRangeExponents() {
        return Arbitraries.bigIntegers().between(BigDecimal.valueOf(1000).toBigInteger(), new BigDecimal("1E30").toBigInteger())
                .flatMap(magnitude -> Arbitraries.of("", "-").map(sign -> new DecimalText(sign + magnitude, new BigDecimal(sign + magnitude))));
    }

    @Provide
    Arbitrary<DecimalText> inRangeNegativeExponents() {
        return Arbitraries.integers().between(1, 999).map(magnitude -> new DecimalText("-" + magnitude, BigDecimal.valueOf(-magnitude)));
    }

    @Provide
    Arbitrary<String> invalid() {
        return DecimalGenerators.invalidDecimals();
    }

    @Provide
    Arbitrary<String> blanks() {
        return DecimalGenerators.blanks().injectNull(0.1);
    }

    @Provide
    Arbitrary<Operation> binaryOperations() {
        return Arbitraries.of(Operation.ADDITION, Operation.SUBTRACTION, Operation.MULTIPLICATION, Operation.DIVISION, Operation.POWER);
    }

    @Property
    void divisionByZeroBlamesTheSecondNumber(@ForAll("operands") DecimalText dividend, @ForAll("zeros") DecimalText zero) {
        assertRefusal(Operation.DIVISION, dividend.text(), zero.text(), InputField.SECOND,
                "Le second nombre doit être différent de 0 pour une division.");
    }

    /**
     * The canonical text of ".5" is "0,5", one character longer. The history stores the canonical text, so a number
     * of the maximum length with no integer digit is refused: saved, it could not be read back.
     */
    @Property
    void aBareDecimalPointOfTheMaximumLengthIsTooLong(@ForAll("bareDecimalPointsAtTheBound") DecimalText input, @ForAll("operands") DecimalText other) {
        String message = " nombre est trop long (" + CalculationLimits.MAX_INPUT_LENGTH + " caractères maximum).";
        assertEquals(DecimalGenerators.MAX_LENGTH, input.text().length());
        assertRefusal(Operation.ADDITION, input.text(), other.text(), InputField.FIRST, "Le premier" + message);
        assertRefusal(Operation.ADDITION, other.text(), input.text(), InputField.SECOND, "Le second" + message);
    }

    /**
     * Regression of a defect found by the round-trip property: the square root of this 1005-character operand was
     * accepted and saved as "0,777...", which is 1006 characters, so the entry was refused as too long and lost when
     * the history was loaded again.
     */
    @Example
    void aBareDecimalPointOfTheMaximumLengthIsNoLongerAcceptedThenLost() {
        String operand = "," + "7".repeat(CalculationLimits.MAX_INPUT_LENGTH - 1);
        assertRefusal(Operation.ROOT, operand, "", InputField.FIRST,
                "Le premier nombre est trop long (" + CalculationLimits.MAX_INPUT_LENGTH + " caractères maximum).");
    }

    @Property
    void squareRootOfANegativeBlamesTheFirstNumber(@ForAll("negatives") DecimalText negative) {
        assertRefusal(Operation.ROOT, negative.text(), "", InputField.FIRST,
                "La racine carrée d'un nombre négatif n'est pas définie en réel.");
    }

    @Property
    void aNonIntegerExponentBlamesTheSecondNumber(@ForAll("operands") DecimalText base, @ForAll("fractionalExponents") DecimalText exponent) {
        assertRefusal(Operation.POWER, base.text(), exponent.text(), InputField.SECOND, "L'exposant doit être un entier.");
    }

    @Property
    void anExponentOutOfRangeBlamesTheSecondNumber(@ForAll("operands") DecimalText base, @ForAll("outOfRangeExponents") DecimalText exponent) {
        assertRefusal(Operation.POWER, base.text(), exponent.text(), InputField.SECOND,
                "L'exposant doit être compris entre -999 et 999.");
    }

    @Property
    void zeroToANegativePowerBlamesTheFirstNumber(@ForAll("zeros") DecimalText zero, @ForAll("inRangeNegativeExponents") DecimalText exponent) {
        assertRefusal(Operation.POWER, zero.text(), exponent.text(), InputField.FIRST, "0 ne peut pas être élevé à une puissance négative.");
    }

    @Property
    void aMissingFirstNumberBlamesTheFirstNumber(@ForAll Operation operation, @ForAll("blanks") String blank, @ForAll("operands") DecimalText other) {
        assertRefusal(operation, blank, other.text(), InputField.FIRST, "Saisissez le premier nombre.");
    }

    @Property
    void aMissingSecondNumberBlamesTheSecondNumber(
            @ForAll("binaryOperations") Operation operation, @ForAll("operands") DecimalText first, @ForAll("blanks") String blank) {
        assertRefusal(operation, first.text(), blank, InputField.SECOND, "Saisissez le second nombre.");
    }

    @Property
    void anInvalidFirstNumberBlamesTheFirstNumber(@ForAll Operation operation, @ForAll("invalid") String bad, @ForAll("operands") DecimalText other) {
        assertRefusal(operation, bad, other.text(), InputField.FIRST, INVALID_FIRST);
    }

    @Property
    void anInvalidSecondNumberBlamesTheSecondNumber(
            @ForAll("binaryOperations") Operation operation, @ForAll("operands") DecimalText first, @ForAll("invalid") String bad) {
        assertRefusal(operation, first.text(), bad, InputField.SECOND, INVALID_SECOND);
    }

    @Property
    void theFirstNumberIsCheckedBeforeTheSecond(@ForAll("binaryOperations") Operation operation, @ForAll("invalid") String first, @ForAll("invalid") String second) {
        assertRefusal(operation, first, second, InputField.FIRST, INVALID_FIRST);
    }

    @Property
    void theSecondNumberOfASquareRootIsNeverRead(@ForAll("operands") DecimalText x, @ForAll("invalid") String ignored) throws CalculationException {
        DecimalText radicand = new DecimalText(x.text().replaceFirst("^-", ""), x.value().abs());
        CalculationRecord record = engine.calculate(Operation.ROOT, radicand.text(), ignored);
        assertEquals("", record.getSecondValue(), "A square root has no second operand.");
    }

    @Property
    void anOperandOverTheBoundIsTooLongAndBlamesItsField(@ForAll("binaryOperations") Operation operation) {
        String tooLong = "1".repeat(CalculationLimits.MAX_INPUT_LENGTH + 1);
        assertRefusal(operation, tooLong, "1", InputField.FIRST, "Le premier nombre est trop long (" + CalculationLimits.MAX_INPUT_LENGTH + " caractères maximum).");
        assertRefusal(operation, "1", tooLong, InputField.SECOND, "Le second nombre est trop long (" + CalculationLimits.MAX_INPUT_LENGTH + " caractères maximum).");
    }

    @Property
    void noOperationIsRefusedWithoutAField(@ForAll("operands") DecimalText first, @ForAll("operands") DecimalText second) {
        CalculationException refusal = assertThrows(CalculationException.class, () -> engine.calculate(null, first.text(), second.text()));
        assertEquals(InputField.NONE, refusal.getField());
        assertEquals("Choisissez une opération.", refusal.getMessage());
    }

    private void assertRefusal(Operation operation, String first, String second, InputField field, String message) {
        CalculationException refusal = assertThrows(CalculationException.class, () -> engine.calculate(operation, first, second),
                () -> operation + " should have been refused: " + abbreviate(first) + " | " + abbreviate(second));
        assertEquals(message, refusal.getMessage());
        assertEquals(field, refusal.getField());
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        return text.length() <= 40 ? "'" + text + "'" : "'" + text.substring(0, 20) + "...' (" + text.length() + " characters)";
    }
}
