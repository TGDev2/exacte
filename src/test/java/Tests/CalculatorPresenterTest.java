package Tests;

import Calcul.CalculationException;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.InputField;
import Calcul.Operation;
import Graphique.CalculatorPresenter;
import Graphique.CalculatorViewModel;
import Graphique.FocusTarget;
import Graphique.ReuseTarget;
import Graphique.StatusTone;
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

        assertEquals("4", viewModel.getResultValue(), "Binary calculation should expose the formatted result.");
        assertEquals("4", viewModel.getFirstInput(), "Successful calculations should prepare the result as the next first operand.");
        assertEquals("", viewModel.getSecondInput(), "Successful binary calculations should clear the second operand for the next step.");
        assertEquals(1, viewModel.getHistoryEntries().size(), "Successful calculations should be added to history.");
        assertEquals(0, viewModel.getSelectedHistoryIndex(), "The latest history entry should be selected after calculation.");
        assertEquals(StatusTone.SUCCESS, viewModel.getStatusTone(), "Successful calculations should expose a success status.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.getFocusTarget(), "Binary workflow should move focus to the second operand.");
        assertTrue(viewModel.isReuseLastResultEnabled(), "A result should enable the quick reuse action.");
    }

    @Test
    void testUnaryCalculationDisablesSecondOperandAndKeepsResultReusable() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel operationView = presenter.onOperationChanged(Operation.ROOT, "9", "12");
        CalculatorViewModel resultView = presenter.onCalculate("9", "12");

        assertEquals(Operation.ROOT, operationView.getSelectedOperation(), "The presenter should update the selected operation.");
        assertFalse(operationView.isSecondInputEnabled(), "Unary operations should disable the second operand.");
        assertEquals("", operationView.getSecondInput(), "Unary operations should clear the second operand input.");
        assertEquals("3", resultView.getResultValue(), "Unary calculations should compute the expected result.");
        assertEquals("3", resultView.getFirstInput(), "Unary calculations should also prepare the result for reuse.");
        assertEquals(FocusTarget.FIRST_NUMBER, resultView.getFocusTarget(), "Unary workflow should return focus to the first operand.");
        assertEquals("\u221A(9) = 3", resultView.getHistoryEntries().get(0).toString(), "Unary history entries should remain explicit.");
    }

    @Test
    void testChainedDecimalWorkflowKeepsVisiblePrecisionStable() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel firstView = presenter.onCalculate("0,1", "0,2");
        CalculatorViewModel secondView = presenter.onCalculate(firstView.getFirstInput(), "0,3");

        assertEquals("0,3", firstView.getResultValue(), "The first rounded result should stay readable.");
        assertEquals("0,3", firstView.getFirstInput(), "The prepared operand should match the visible result exactly.");
        assertEquals("0,6", secondView.getResultValue(), "Chained calculations should reuse the same decimal value without drift.");
        assertEquals("0,3 + 0,3 = 0,6", secondView.getHistoryEntries().get(1).toString(), "History should reflect the same value that was reused.");
    }

    @Test
    void testInvalidInputReturnsActionableErrorState() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel viewModel = presenter.onCalculate("abc", "2");

        assertEquals("--", viewModel.getResultValue(), "Failed calculations should reset the visible result.");
        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "Failed calculations should expose an error state.");
        assertEquals(
                "Le premier nombre est invalide. Utilisez des chiffres, un signe et une virgule ou un point.",
                viewModel.getStatusMessage(),
                "Invalid input should keep the targeted validation message.");
        assertEquals(0, viewModel.getHistoryEntries().size(), "Failed calculations should not create history entries.");
        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.getFocusTarget(), "First-operand errors should bring focus back to the first operand.");
    }

    @Test
    void testDivisionByZeroKeepsHistorySafe() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.DIVISION, "8", "0");

        CalculatorViewModel viewModel = presenter.onCalculate("8", "0");

        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "Division by zero should surface as an error.");
        assertEquals(0, viewModel.getHistoryEntries().size(), "Division by zero should not mutate history.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.getFocusTarget(), "Second-operand errors should return focus to the second operand.");
    }

    @Test
    void testPowerHintExplainsSupportedScope() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());

        CalculatorViewModel viewModel = presenter.onOperationChanged(Operation.POWER, "2", "3");

        assertEquals(
                "L'exposant doit etre entier entre -999 et 999.",
                viewModel.getInputHint(),
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
                latestCalculation.getFirstInput(),
                latestCalculation.getSecondInput());

        assertEquals("5", selectedReuse.getFirstInput(), "Selected history entries should be reusable as the first operand.");
        assertEquals(FocusTarget.SECOND_NUMBER, selectedReuse.getFocusTarget(), "Reusing a first operand in a binary workflow should move focus to the second operand.");

        CalculatorViewModel latestReuse = presenter.onReuseLastResult(
                selectedReuse.getFirstInput(),
                selectedReuse.getSecondInput());

        assertEquals("9", latestReuse.getFirstInput(), "The quick latest-result action should restore the newest result.");
        assertEquals("", latestReuse.getSecondInput(), "The latest-result action should prepare a fresh second operand.");
        assertEquals(1, latestReuse.getSelectedHistoryIndex(), "The latest-result action should select the latest history entry.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1,5", "5000", "99999999999"})
    void testInvalidExponentFocusesSecondOperand(String exponent) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "2", exponent);

        CalculatorViewModel viewModel = presenter.onCalculate("2", exponent);

        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "An invalid exponent should surface as an error.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.getFocusTarget(), "Exponent errors concern the second operand.");
    }

    @Test
    void testOutOfRangeIntegerExponentReportsTheRange() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "2", "99999999999");

        CalculatorViewModel viewModel = presenter.onCalculate("2", "99999999999");

        assertEquals(
                "L'exposant doit etre un entier compris entre -999 et 999.",
                viewModel.getStatusMessage(),
                "A huge integer exponent is out of range, not non-integer.");
    }

    @Test
    void testZeroToNegativePowerFocusesFirstOperand() {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(Operation.POWER, "0", "-1");

        CalculatorViewModel viewModel = presenter.onCalculate("0", "-1");

        assertEquals(StatusTone.ERROR, viewModel.getStatusTone(), "0 ^ -1 should surface as an error.");
        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.getFocusTarget(), "The base is the faulty operand for 0 ^ -1.");
    }

    @Test
    void testFocusFollowsTheTypedFieldNotTheMessageText() {
        CalculatorEngine engine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, String firstInput, String secondInput)
                    throws CalculationException {
                throw new CalculationException("texte quelconque", InputField.SECOND);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(engine);

        CalculatorViewModel viewModel = presenter.onCalculate("1", "2");

        assertEquals("texte quelconque", viewModel.getStatusMessage(), "The message is displayed untouched.");
        assertEquals(FocusTarget.SECOND_NUMBER, viewModel.getFocusTarget(), "Focus must not depend on the message text.");
    }

    @Test
    void testErrorWithoutFieldFocusesFirstOperand() {
        CalculatorEngine engine = new CalculatorEngine() {
            @Override
            public CalculationRecord calculate(Operation operation, String firstInput, String secondInput)
                    throws CalculationException {
                throw new CalculationException("texte quelconque", InputField.NONE);
            }
        };
        CalculatorPresenter presenter = new CalculatorPresenter(engine);

        CalculatorViewModel viewModel = presenter.onCalculate("1", "2");

        assertEquals(FocusTarget.FIRST_NUMBER, viewModel.getFocusTarget(), "A NONE error falls back to the first operand.");
    }
}
