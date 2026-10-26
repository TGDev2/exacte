package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.InputField;
import calc.engine.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class CalculatorErrorFocusTest {
    private static final String BIG = "9".repeat(600);

    @ParameterizedTest(name = "{0} [{1}] [{2}] -> {3}")
    @CsvSource(delimiter = '|', value = {
        "ADDITION       | ''     | 1      | FIRST_NUMBER",
        "ADDITION       | 1      | ''     | SECOND_NUMBER",
        "ADDITION       | abc    | 1      | FIRST_NUMBER",
        "ADDITION       | 1      | abc    | SECOND_NUMBER",
        "SUBTRACTION    | 1e3    | 1      | FIRST_NUMBER",
        "MULTIPLICATION | 1      | 1,5,5  | SECOND_NUMBER",
        "DIVISION       | 8      | 0      | SECOND_NUMBER",
        "ROOT           | -4     | ''     | FIRST_NUMBER",
        "ROOT           | abc    | ''     | FIRST_NUMBER",
        "POWER          | 2      | 1,5    | SECOND_NUMBER",
        "POWER          | 2      | 1000   | SECOND_NUMBER",
        "POWER          | 2      | -1000  | SECOND_NUMBER",
        "POWER          | 2      | 99999999999 | SECOND_NUMBER",
        "POWER          | 0      | -1     | FIRST_NUMBER",
        "POWER          | 99     | 999    | SECOND_NUMBER",
        "POWER          | abc    | 2      | FIRST_NUMBER",
        "POWER          | 2      | abc    | SECOND_NUMBER",
    })
    void testEveryEngineErrorFocusesTheFaultyField(Operation operation, String first, String second, FocusTarget expected) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(operation, first, second);

        CalculatorViewModel view = presenter.onCalculate(first, second);

        assertEquals(StatusTone.ERROR, view.statusTone(), "The input must be refused: " + view.statusMessage());
        assertEquals(expected, view.focusTarget(), view.statusMessage());
    }

    @Test
    void testResultTooLongIsNotTiedToAnOperandAndFocusesTheFirstField() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.MULTIPLICATION, BIG, BIG);

        CalculatorViewModel view = presenter.onCalculate(BIG, BIG);

        assertEquals(StatusTone.ERROR, view.statusTone());
        assertEquals(FocusTarget.FIRST_NUMBER, view.focusTarget());
    }

    @Test
    void testEngineErrorsCarryTheFaultyOperandAsAType() {
        CalculatorEngine engine = new CalculatorEngine();

        assertEquals(InputField.NONE, fieldOf(engine, null, "1", "2"));
        assertEquals(InputField.FIRST, fieldOf(engine, Operation.ADDITION, "x", "2"));
        assertEquals(InputField.SECOND, fieldOf(engine, Operation.ADDITION, "1", "x"));
        assertEquals(InputField.SECOND, fieldOf(engine, Operation.POWER, "2", "99999999999"));
        assertEquals(InputField.NONE, fieldOf(engine, Operation.MULTIPLICATION, BIG, BIG));
    }

    @Test
    void testFocusDoesNotDependOnTheWordsOfTheMessage() {
        CalculatorEngine misleadingEngine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, String first, String second) throws CalculationException {
                throw new CalculationException("Le second nombre second second", InputField.FIRST);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(misleadingEngine);
        presenter.onOperationChanged(Operation.ADDITION, "1", "2");

        CalculatorViewModel view = presenter.onCalculate("1", "2");

        assertEquals(FocusTarget.FIRST_NUMBER, view.focusTarget(), "Only the typed field decides, never the message text.");
    }

    @Test
    void testSecondFieldErrorIsIgnoredForAnOperationWithoutSecondOperand() {
        CalculatorEngine misleadingEngine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, String first, String second) throws CalculationException {
                throw new CalculationException("anything", InputField.SECOND);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(misleadingEngine);
        presenter.onOperationChanged(Operation.ROOT, "4", "");

        assertEquals(FocusTarget.FIRST_NUMBER, presenter.onCalculate("4", "").focusTarget());
    }

    private static InputField fieldOf(CalculatorEngine engine, Operation operation, String first, String second) {
        return assertThrows(CalculationException.class, () -> engine.calculate(operation, first, second)).getField();
    }
}
