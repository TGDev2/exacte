package calc.ui;

import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JRootPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class CalculatorFrameTest {
    @BeforeEach
    void requireGraphicalEnvironment() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (calc.ui.required=true).");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
    }

    @Test
    void testKeyboardShortcutsAndDefaultCalculationWorkflow() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            JLabel resultValueLabel = findComponent(rootPane, "resultValueLabel", JLabel.class);
            AbstractButton calculateButton = findComponent(rootPane, "calculateButton", AbstractButton.class);
            AbstractButton reuseLastResultButton = findComponent(rootPane, "reuseLastResultButton", AbstractButton.class);
            AbstractButton clearHistoryButton = findComponent(rootPane, "clearHistoryButton", AbstractButton.class);
            JList<?> historyList = findComponent(rootPane, "historyList", JList.class);

            assertEquals(calculateButton, rootPane.getDefaultButton(), "The calculate button should be the default button for Enter-driven workflows.");
            assertFalse(reuseLastResultButton.isEnabled(), "Quick reuse should start disabled when no result exists.");
            assertFalse(clearHistoryButton.isEnabled(), "Clearing history should start disabled when the history is empty.");

            runOnEdt(() -> {
                firstNumberField.setText("1,5");
                secondNumberField.setText("2.5");
            });
            clickButton(calculateButton);

            assertEquals("4", resultValueLabel.getText(), "The default calculation flow should update the result label.");
            assertEquals(1, historyList.getModel().getSize(), "A successful calculation should appear in the UI history.");
            assertTrue(reuseLastResultButton.isEnabled(), "A successful calculation should enable quick reuse.");
            assertTrue(clearHistoryButton.isEnabled(), "A successful calculation should enable the history clear action.");

            assertShortcut(rootPane, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear-fields");
            invokeShortcut(rootPane, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            assertEquals("", firstNumberField.getText(), "Escape should clear the first operand.");
            assertEquals("", secondNumberField.getText(), "Escape should clear the second operand.");
            assertEquals("--", resultValueLabel.getText(), "Escape should reset the visible result.");

            assertShortcut(
                    rootPane,
                    KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK),
                    "reuse-last-result");
            invokeShortcut(rootPane, KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK));
            assertEquals("4", firstNumberField.getText(), "Ctrl+R should restore the latest result into the first operand.");
            assertEquals("", secondNumberField.getText(), "Ctrl+R should prepare a fresh second operand.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testUnaryModeAndHistoryReuseFlow() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            JLabel secondNumberLabel = findComponent(rootPane, "secondNumberLabel", JLabel.class);
            JLabel inputHintLabel = findComponent(rootPane, "inputHintLabel", JLabel.class);
            JLabel statusLabel = findComponent(rootPane, "statusLabel", JLabel.class);
            AbstractButton calculateButton = findComponent(rootPane, "calculateButton", AbstractButton.class);
            AbstractButton reuseAsSecondButton = findComponent(rootPane, "reuseAsSecondButton", AbstractButton.class);
            AbstractButton clearHistoryButton = findComponent(rootPane, "clearHistoryButton", AbstractButton.class);
            JList<?> historyList = findComponent(rootPane, "historyList", JList.class);

            runOnEdt(() -> operationSelector.setSelectedItem(Operation.ROOT));
            assertFalse(secondNumberField.isEnabled(), "Unary operations should disable the second operand field in the UI.");
            assertEquals("Second nombre (non utilisé)", secondNumberLabel.getText(), "Unary operations should explain that the second operand is unused.");
            assertFalse(reuseAsSecondButton.isEnabled(), "Reusing into the second operand should stay disabled while the operation is unary.");
            assertEquals(
                    "Racine non exacte : arrondie à 12 décimales et 12 chiffres significatifs minimum.",
                    inputHintLabel.getText(),
                    "The UI should surface the explicit precision rule for roots.");

            runOnEdt(() -> firstNumberField.setText("9"));
            clickButton(calculateButton);

            assertEquals(1, historyList.getModel().getSize(), "Unary calculations should also add entries to history.");
            assertTrue(clearHistoryButton.isEnabled(), "History actions should become available after a successful unary calculation.");

            runOnEdt(() -> operationSelector.setSelectedItem(Operation.ADDITION));
            assertTrue(secondNumberField.isEnabled(), "Switching back to a binary operation should reactivate the second operand.");
            assertTrue(reuseAsSecondButton.isEnabled(), "History reuse into the second operand should reactivate for binary operations.");

            runOnEdt(() -> historyList.setSelectedIndex(0));
            clickButton(reuseAsSecondButton);

            assertEquals("3", secondNumberField.getText(), "Selected history entries should be reusable directly from the UI.");
            assertEquals(
                    "Le résultat historique a été copié dans le second nombre.",
                    statusLabel.getText(),
                    "The UI should explain the result of a history reuse action.");

            clickButton(clearHistoryButton);
            assertEquals(0, historyList.getModel().getSize(), "Clearing history from the UI should empty the visible list.");
            assertFalse(clearHistoryButton.isEnabled(), "Clearing history should disable the clear action again.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testMnemonicsAreDistinctAndLabelsPointToTheirFields() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            String[] labelNames = {"firstNumberLabel", "operationLabel", "secondNumberLabel"};
            String[] labelTargets = {"firstNumberField", "operationSelector", "secondNumberField"};
            String[] buttonNames = {
                "calculateButton", "reuseLastResultButton", "clearButton",
                "reuseAsFirstButton", "reuseAsSecondButton", "clearHistoryButton"
            };

            Set<Integer> mnemonics = new HashSet<>();
            for (int index = 0; index < labelNames.length; index++) {
                JLabel label = findComponent(rootPane, labelNames[index], JLabel.class);
                assertNotEquals(0, label.getDisplayedMnemonic(), labelNames[index] + " should have a mnemonic.");
                assertTrue(mnemonics.add(label.getDisplayedMnemonic()), labelNames[index] + " mnemonic should be unique.");
                assertNotNull(label.getLabelFor(), labelNames[index] + " should label a component.");
                assertEquals(
                        findComponent(rootPane, labelTargets[index], JComponent.class),
                        label.getLabelFor(),
                        labelNames[index] + " should label " + labelTargets[index] + ".");
            }
            for (String buttonName : buttonNames) {
                AbstractButton button = findComponent(rootPane, buttonName, AbstractButton.class);
                assertNotEquals(0, button.getMnemonic(), buttonName + " should have a mnemonic.");
                assertTrue(mnemonics.add(button.getMnemonic()), buttonName + " mnemonic should be unique.");
                assertTrue(button.getDisplayedMnemonicIndex() >= 0, buttonName + " should underline its mnemonic.");
            }
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testKeyComponentsHaveAccessibleNames() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            String[] componentNames = {
                "firstNumberField", "secondNumberField", "operationSelector", "historyList", "resultValueLabel"
            };
            for (String componentName : componentNames) {
                JComponent component = findComponent(rootPane, componentName, JComponent.class);
                String name = component.getAccessibleContext().getAccessibleName();
                String description = component.getAccessibleContext().getAccessibleDescription();
                assertTrue(name != null && !name.isBlank(), componentName + " should have an accessible name.");
                assertTrue(description != null && !description.isBlank(), componentName + " should have an accessible description.");
            }
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testTabOrderFollowsTheReadingOrder() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            runOnEdt(() -> {
                firstNumberField.setText("1");
                secondNumberField.setText("2");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));

            String[] expectedOrder = {
                "firstNumberField", "operationSelector", "secondNumberField", "calculateButton",
                "reuseLastResultButton", "clearButton", "historyList", "reuseAsFirstButton",
                "reuseAsSecondButton", "clearHistoryButton", "firstNumberField"
            };
            Component current = firstNumberField;
            for (int index = 1; index < expectedOrder.length; index++) {
                current = frame.getFocusTraversalPolicy().getComponentAfter(frame, current);
                assertNotNull(current, "Tab should reach " + expectedOrder[index] + ".");
                assertEquals(expectedOrder[index], current.getName(), "Unexpected tab order at step " + index + ".");
            }
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testLongResultIsTruncatedOnScreenButCompleteInTheTooltip() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            JLabel resultValueLabel = findComponent(rootPane, "resultValueLabel", JLabel.class);
            int widthBefore = frame.getWidth();
            int preferredWidthBefore = resultValueLabel.getPreferredSize().width;

            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.POWER);
                firstNumberField.setText("2");
                secondNumberField.setText("999");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));

            String fullValue = new BigDecimal(2).pow(999).toPlainString();
            assertEquals(fullValue, resultValueLabel.getText(), "The label should hold the complete result.");
            String tooltip = resultValueLabel.getToolTipText();
            assertNotNull(tooltip, "A result should come with a tooltip.");
            assertTrue(
                    tooltip.replaceAll("<[^>]*>", "").contains(fullValue),
                    "The tooltip should contain the complete long result.");
            assertEquals(0, resultValueLabel.getMinimumSize().width, "The result label should be allowed to shrink to nothing.");
            assertTrue(
                    resultValueLabel.getPreferredSize().width <= Math.max(preferredWidthBefore, 360),
                    "A long result should not widen the result label.");
            assertEquals(widthBefore, frame.getWidth(), "A long result should not widen the window.");
        } finally {
            disposeFrame(frame);
        }
    }

    private static CalculatorFrame createFrame() throws InterruptedException, InvocationTargetException {
        final CalculatorFrame[] frameHolder = new CalculatorFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            CalculatorFrame frame = new CalculatorFrame(new CalculatorPresenter(new CalculatorEngine()));
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.setVisible(true);
            frameHolder[0] = frame;
        });
        flushEdt();
        return frameHolder[0];
    }

    private static void disposeFrame(CalculatorFrame frame) throws InterruptedException, InvocationTargetException {
        if (frame == null) {
            return;
        }
        SwingUtilities.invokeAndWait(frame::dispose);
    }

    private static void clickButton(AbstractButton button) throws InterruptedException, InvocationTargetException {
        runOnEdt(button::doClick);
        flushEdt();
    }

    private static void invokeShortcut(JRootPane rootPane, KeyStroke keyStroke)
            throws InterruptedException, InvocationTargetException {
        Object actionKey = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(keyStroke);
        Action action = rootPane.getActionMap().get(actionKey);
        runOnEdt(() -> action.actionPerformed(new ActionEvent(rootPane, ActionEvent.ACTION_PERFORMED, actionKey.toString())));
        flushEdt();
    }

    private static void assertShortcut(JRootPane rootPane, KeyStroke keyStroke, String expectedActionKey) {
        Object actionKey = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(keyStroke);
        assertEquals(expectedActionKey, actionKey, "The expected keyboard shortcut should be registered on the root pane.");
        assertNotNull(rootPane.getActionMap().get(actionKey), "The keyboard shortcut action should exist in the action map.");
    }

    private static void runOnEdt(Runnable runnable) throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(runnable);
    }

    private static void flushEdt() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
        });
    }

    private static <T extends Component> T findComponent(Container container, String name, Class<T> componentType) {
        T match = findComponentOrNull(container, name, componentType);
        if (match == null) {
            throw new AssertionError("Unable to find component named " + name + ".");
        }
        return match;
    }

    private static <T extends Component> T findComponentOrNull(Container container, String name, Class<T> componentType) {
        if (componentType.isInstance(container) && name.equals(container.getName())) {
            return componentType.cast(container);
        }

        for (Component child : container.getComponents()) {
            if (componentType.isInstance(child) && name.equals(child.getName())) {
                return componentType.cast(child);
            }
            if (child instanceof Container) {
                T nestedMatch = findComponentOrNull((Container) child, name, componentType);
                if (nestedMatch != null) {
                    return nestedMatch;
                }
            }
        }

        return null;
    }
}
