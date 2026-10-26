package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.InputField;
import calc.engine.Operand;
import calc.engine.Operation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CalculatorPresenterTest {
    @Test
    void testBinaryCalculationFeedsNextWorkflow() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel viewModel = presenter.onCalculate("1,5", "2.5");

        assertEquals("4", viewModel.resultValue(), "Binary calculation should expose the formatted result.");
        assertEquals("4", viewModel.firstInput(), "Successful calculations should prepare the result as the next first operand.");
        assertEquals("", viewModel.secondInput(), "Successful binary calculations should clear the second operand for the next step.");
        assertEquals(1, viewModel.historyEntries().size(), "Successful calculations should be added to history.");
        assertEquals(0, viewModel.selectedHistoryIndex(), "The latest history entry should be selected after calculation.");
        assertEquals(StatusTone.SUCCESS, viewModel.statusTone(), "Successful calculations should expose a success status.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.focusTarget(), "Binary workflow should move focus to the second operand.");
        assertTrue(viewModel.reuseLastResultEnabled(), "A result should enable the quick reuse action.");
    }

    @Test
    void testUnaryCalculationDisablesSecondOperandAndKeepsResultReusable() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel operationView = presenter.onOperationChanged(Operation.ROOT, "9", "12");
        CalculatorViewModel resultView = presenter.onCalculate("9", "12");

        assertEquals(Operation.ROOT, operationView.selectedOperation(), "The presenter should update the selected operation.");
        assertFalse(operationView.secondInputEnabled(), "Unary operations should disable the second operand.");
        assertEquals("", operationView.secondInput(), "Unary operations should clear the second operand input.");
        assertEquals("3", resultView.resultValue(), "Unary calculations should compute the expected result.");
        assertEquals("3", resultView.firstInput(), "Unary calculations should also prepare the result for reuse.");
        assertEquals(FocusTarget.FIRST_NUMBER, resultView.focusTarget(), "Unary workflow should return focus to the first operand.");
        assertEquals("\u221A(9) = 3", resultView.historyEntries().get(0).toString(), "Unary history entries should remain explicit.");
    }

    @Test
    void testChainedDecimalWorkflowKeepsVisiblePrecisionStable() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel firstView = presenter.onCalculate("0,1", "0,2");
        CalculatorViewModel secondView = presenter.onCalculate(firstView.firstInput(), "0,3");

        assertEquals("0,3", firstView.resultValue(), "The first rounded result should stay readable.");
        assertEquals("0,3", firstView.firstInput(), "The prepared operand should match the visible result exactly.");
        assertEquals("0,6", secondView.resultValue(), "Chained calculations should reuse the same decimal value without drift.");
        assertEquals("0,3 + 0,3 = 0,6", secondView.historyEntries().get(1).toString(), "History should reflect the same value that was reused.");
    }

    @Test
    void testInvalidInputReturnsActionableErrorState() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel viewModel = presenter.onCalculate("abc", "2");

        assertEquals("--", viewModel.resultValue(), "Failed calculations should reset the visible result.");
        assertEquals(StatusTone.ERROR, viewModel.statusTone(), "Failed calculations should expose an error state.");
        assertEquals(
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                viewModel.statusMessage(),
                "Invalid input should keep the targeted validation message.");
        assertEquals(0, viewModel.historyEntries().size(), "Failed calculations should not create history entries.");
        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.focusTarget(), "First-operand errors should bring focus back to the first operand.");
    }

    @Test
    void testDivisionByZeroKeepsHistorySafe() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.DIVISION, "8", "0");

        CalculatorViewModel viewModel = presenter.onCalculate("8", "0");

        assertEquals(StatusTone.ERROR, viewModel.statusTone(), "Division by zero should surface as an error.");
        assertEquals(0, viewModel.historyEntries().size(), "Division by zero should not mutate history.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.focusTarget(), "Second-operand errors should return focus to the second operand.");
    }

    @Test
    void testPowerHintExplainsSupportedScope() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel viewModel = presenter.onOperationChanged(Operation.POWER, "2", "3");

        assertEquals(
                "L'exposant doit être entier entre -4096 et 4096.",
                viewModel.inputHint(),
                "The UI model should explain the supported exponent range.");
    }

    @Test
    void testHistoryReuseSupportsSelectionAndLatestResultShortcut() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        presenter.onCalculate("2", "3");
        CalculatorViewModel latestCalculation = presenter.onCalculate("5", "4");

        presenter.onHistorySelectionChanged(0);
        CalculatorViewModel selectedReuse = presenter.onReuseHistoryResult(
                ReuseTarget.FIRST_NUMBER,
                latestCalculation.firstInput(),
                latestCalculation.secondInput());

        assertEquals("5", selectedReuse.firstInput(), "Selected history entries should be reusable as the first operand.");
        assertEquals(FocusTarget.SECOND_NUMBER, selectedReuse.focusTarget(), "Reusing a first operand in a binary workflow should move focus to the second operand.");

        CalculatorViewModel latestReuse = presenter.onReuseLastResult(
                selectedReuse.firstInput(),
                selectedReuse.secondInput());

        assertEquals("9", latestReuse.firstInput(), "The quick latest-result action should restore the newest result.");
        assertEquals("", latestReuse.secondInput(), "The latest-result action should prepare a fresh second operand.");
        assertEquals(1, latestReuse.selectedHistoryIndex(), "The latest-result action should select the latest history entry.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,5", "5000", "99999999999"})
    void testInvalidExponentFocusesSecondOperand(String exponent) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "2", exponent);

        CalculatorViewModel viewModel = presenter.onCalculate("2", exponent);

        assertEquals(StatusTone.ERROR, viewModel.statusTone(), "An invalid exponent should surface as an error.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.focusTarget(), "Exponent errors concern the second operand.");
    }

    @Test
    void testOutOfRangeIntegerExponentReportsTheRange() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "2", "99999999999");

        CalculatorViewModel viewModel = presenter.onCalculate("2", "99999999999");

        assertEquals(
                "L'exposant doit être compris entre -4096 et 4096.",
                viewModel.statusMessage(),
                "A huge integer exponent is out of range, not non-integer.");
    }

    @Test
    void testZeroToNegativePowerFocusesFirstOperand() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "0", "-1");

        CalculatorViewModel viewModel = presenter.onCalculate("0", "-1");

        assertEquals(StatusTone.ERROR, viewModel.statusTone(), "0 ^ -1 should surface as an error.");
        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.focusTarget(), "The base is the faulty operand for 0 ^ -1.");
    }

    @Test
    void testFocusFollowsTheTypedFieldNotTheMessageText() {
        CalculatorEngine engine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, Operand firstInput, Operand secondInput)
                    throws CalculationException {
                throw new CalculationException("texte quelconque", InputField.SECOND);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(engine);

        CalculatorViewModel viewModel = presenter.onCalculate("1", "2");

        assertEquals("texte quelconque", viewModel.statusMessage(), "The message is displayed untouched.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.focusTarget(), "Focus must not depend on the message text.");
    }

    @Test
    void testErrorWithoutFieldFocusesFirstOperand() {
        CalculatorEngine engine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, Operand firstInput, Operand secondInput)
                    throws CalculationException {
                throw new CalculationException("texte quelconque", InputField.NONE);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(engine);

        CalculatorViewModel viewModel = presenter.onCalculate("1", "2");

        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.focusTarget(), "A NONE error falls back to the first operand.");
    }

    @Test
    void testOneThirdReusedThenTimesThreeIsExactlyOne() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.DIVISION, "", "");

        CalculatorViewModel third = presenter.onCalculate("1", "3");
        assertEquals("0,333333333333…", third.resultValue(), "The display shows twelve digits and « … ».");
        assertEquals("1/3", third.firstInput(), "The field receives the exact value, not the display.");
        assertTrue(third.resultExact());
        assertEquals("Valeur exacte : 1/3", third.resultDetail());

        presenter.onOperationChanged(Operation.MULTIPLICATION, third.firstInput(), "");
        CalculatorViewModel product = presenter.onCalculate(third.firstInput(), "3");
        assertEquals("1", product.resultValue(), "(1 / 3) * 3 is exactly 1, not 0,999999999999.");
        assertTrue(product.resultExact());
        assertEquals("1/3 * 3 = 1", product.historyEntries().get(1).toString());

        // The same through the history: the first result put back in the first field, then multiplied by 3.
        presenter.onHistorySelectionChanged(0);
        CalculatorViewModel reused = presenter.onReuseHistoryResult(ReuseTarget.FIRST_NUMBER, product.firstInput(), "");
        assertEquals("1/3", reused.firstInput());
        assertEquals("1", presenter.onCalculate(reused.firstInput(), "3").resultValue());
    }

    @Test
    void testARememberedApproximateValueIsUsedUntilItsTextChanges() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.ROOT, "", "");
        CalculatorViewModel root = presenter.onCalculate("2", "");
        assertEquals("≈ 1,41421356237…", root.resultValue());
        assertFalse(root.resultExact());
        assertEquals("Valeur approchée, erreur au plus ± 2,1 × 10⁻²⁵", root.resultDetail());

        presenter.onOperationChanged(Operation.POWER, root.firstInput(), "");
        CalculatorViewModel square = presenter.onCalculate(root.firstInput(), "2");
        assertEquals("≈ 2,00000000000…", square.resultValue(), "The square of the remembered interval is still approximate.");
        assertFalse(square.resultExact());

        // A text changed by the user is read as typed: the link to the interval is gone.
        CalculatorViewModel typed = presenter.onCalculate(root.firstInput() + "0", "2");
        assertTrue(typed.resultExact(), "The typed decimal is exact, and so is its square.");
        assertEquals(StatusTone.SUCCESS, typed.statusTone());
    }

    @Test
    void testAFieldEmptiedByTheOperationForgetsItsValue() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.ROOT, "", "");
        CalculatorViewModel root = presenter.onCalculate("2", "");
        presenter.onOperationChanged(Operation.ADDITION, root.firstInput(), "");
        CalculatorViewModel inSecond = presenter.onReuseHistoryResult(ReuseTarget.SECOND_NUMBER, "1", "");
        assertEquals(root.firstInput(), inSecond.secondInput());

        presenter.onOperationChanged(Operation.ROOT, "1", inSecond.secondInput());
        presenter.onOperationChanged(Operation.ADDITION, "1", "");
        CalculatorViewModel sum = presenter.onCalculate("1", root.firstInput());
        assertTrue(sum.resultExact(), "The second field was emptied by the unary operation: the same text typed again is exact.");
    }
}
