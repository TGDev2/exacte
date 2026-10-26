package calc.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.testing.DecimalGenerators;
import calc.testing.DecimalGenerators.DecimalText;
import java.math.BigDecimal;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
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
    Arbitrary<String> tooPrecise() {
        // More decimals than the 4096-bit denominator of the core can hold, the last one not a zero; still within the length.
        return Combinators.combine(Arbitraries.of("", "-", "0"), DecimalGenerators.separators(), Arbitraries.integers().between(1300, 4090),
                        Arbitraries.chars().range('1', '9'))
                .as((prefix, separator, decimals, last) -> prefix + separator + "0".repeat(decimals - 1) + last);
    }

    @Provide
    Arbitrary<String> groupedWithOrdinarySpaces() {
        return DecimalGenerators.groupedWithOrdinarySpaces();
    }

    @Provide
    Arbitrary<String> scientificOutOfRange() {
        return Combinators.combine(DecimalGenerators.decimals(10).map(DecimalText::text), Arbitraries.of("e", "E"), Arbitraries.of("", "+", "-"),
                        Arbitraries.integers().between(10_000, 999_999_999))
                .as((mantissa, marker, sign, exponent) -> mantissa + marker + sign + exponent);
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
        return Arbitraries.bigIntegers().between(BigDecimal.valueOf(4097).toBigInteger(), new BigDecimal("1E30").toBigInteger())
                .flatMap(magnitude -> Arbitraries.of("", "-").map(sign -> new DecimalText(sign + magnitude, new BigDecimal(sign + magnitude))));
    }

    @Provide
    Arbitrary<DecimalText> inRangeNegativeExponents() {
        return Arbitraries.integers().between(1, 4096).map(magnitude -> new DecimalText("-" + magnitude, BigDecimal.valueOf(-magnitude)));
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

    @Property
    void tooManyDecimalsForTheCoreBlameTheirField(@ForAll("tooPrecise") String input, @ForAll("operands") DecimalText other) {
        String message = " nombre est trop grand ou a trop de décimales pour un calcul exact.";
        assertRefusal(Operation.ADDITION, input, other.text(), InputField.FIRST, "Le premier" + message);
        assertRefusal(Operation.ADDITION, other.text(), input, InputField.SECOND, "Le second" + message);
    }

    /**
     * Regression of a defect found by the round-trip property (E04): the square root of a 1005-character operand with no
     * integer digit was saved as "0,777...", one character longer than the input bound, and lost when the history was
     * loaded again. Now the bound holds every canonical text: the operand with the most decimals the core accepts is
     * calculated, and its canonical text reads back to the same value.
     */
    @Example
    void aBareDecimalPointWithTheMostDecimalsIsAcceptedAndReadsBack() throws CalculationException {
        String operand = "," + "7".repeat(DecimalGenerators.MAX_DIGITS);
        CalculationRecord record = engine.calculate(Operation.ROOT, operand, "");
        assertTrue(record.getFirstValue().length() <= CalculationLimits.MAX_INPUT_LENGTH, "The canonical operand fits the bound.");
        CalculationRecord again = engine.calculate(Operation.ROOT, record.getFirstValue(), "");
        assertEquals(record.getResult(), again.getResult(), "The canonical operand reads back to the same value.");
    }

    @Property
    void anOrdinarySpaceBetweenGroupsIsRefusedWithItsOwnMessage(
            @ForAll("groupedWithOrdinarySpaces") String grouped, @ForAll("operands") DecimalText other) {
        String message = " nombre contient une espace : séparez les milliers par une espace insécable ou supprimez l'espace.";
        assertRefusal(Operation.ADDITION, grouped, other.text(), InputField.FIRST, "Le premier" + message);
        assertRefusal(Operation.ADDITION, other.text(), grouped, InputField.SECOND, "Le second" + message);
    }

    @Property
    void aScientificExponentOutOfRangeBlamesItsField(@ForAll("scientificOutOfRange") String input, @ForAll("operands") DecimalText other) {
        assertRefusal(Operation.ADDITION, input, other.text(), InputField.FIRST,
                "L'exposant de la notation scientifique du premier nombre doit être compris entre -9999 et 9999.");
        assertRefusal(Operation.ADDITION, other.text(), input, InputField.SECOND,
                "L'exposant de la notation scientifique du second nombre doit être compris entre -9999 et 9999.");
    }

    @Property
    void aFractionWithAZeroDenominatorBlamesItsField(@ForAll("operands") DecimalText top, @ForAll("zeros") DecimalText zero,
                                                     @ForAll("operands") DecimalText other) {
        String fraction = top.text() + "/" + zero.text();
        assertRefusal(Operation.MULTIPLICATION, fraction, other.text(), InputField.FIRST,
                "Le premier nombre est une fraction dont le dénominateur vaut 0.");
        assertRefusal(Operation.MULTIPLICATION, other.text(), fraction, InputField.SECOND,
                "Le second nombre est une fraction dont le dénominateur vaut 0.");
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
                "L'exposant doit être compris entre -4096 et 4096.");
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
