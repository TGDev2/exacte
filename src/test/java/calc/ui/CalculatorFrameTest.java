package calc.ui;

import calc.engine.CalculationHistoryStore;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.format.DisplayMode;
import calc.ui.widget.DisplayModeSelector;
import calc.ui.widget.ExactnessBadge;
import calc.ui.widget.HistoryLine;
import calc.ui.widget.HistoryLineRenderer;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.lang.reflect.InvocationTargetException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.InputMap;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("ui")
@DisplayName("Interface Swing (nécessite un affichage)")
public class CalculatorFrameTest {
    @BeforeEach
    void requireGraphicalEnvironment() {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
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
                    "Racine non exacte : « ≈ » et une borne d'erreur garantie.",
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
                "reuseLastResultButton", "clearButton", "displayModeSelector", "historyList", "reuseAsFirstButton",
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
            assertEquals("5,35754303593… × 10³⁰⁰", resultValueLabel.getText(), "The label shows the Auto text.");
            assertEquals(fullValue, firstNumberField.getText(), "The field receives the complete exact value.");
            String tooltip = resultValueLabel.getToolTipText();
            assertNotNull(tooltip, "A result should come with a tooltip.");
            assertTrue(
                    tooltip.replaceAll("<[^>]*>", "").contains(fullValue),
                    "The tooltip should contain the complete exact value.");
            assertEquals(0, resultValueLabel.getMinimumSize().width, "The result label should be allowed to shrink to nothing.");
            assertTrue(
                    resultValueLabel.getPreferredSize().width <= Math.max(preferredWidthBefore, 360),
                    "A long result should not widen the result label.");
            assertEquals(widthBefore, frame.getWidth(), "A long result should not widen the window.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testApproximateResultShowsItsMarkerAndItsBound() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JLabel resultValueLabel = findComponent(rootPane, "resultValueLabel", JLabel.class);

            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.ROOT);
                firstNumberField.setText("2");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));

            assertEquals("≈ 1,41421356237…", resultValueLabel.getText(), "An approximate result is marked with « ≈ ».");
            assertEquals("Résultat : ≈ 1,41421356237… (valeur approchée)", resultValueLabel.getAccessibleContext().getAccessibleName());
            assertTrue(resultValueLabel.getToolTipText().contains("± 2,1 × 10⁻²⁵"), "The tooltip gives the error bound.");
            assertTrue(resultValueLabel.getAccessibleContext().getAccessibleDescription().contains("± 2,1 × 10⁻²⁵"));
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testModeSelectorReformatsTheResultAndTheHistory() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            DisplayModeSelector selector = findComponent(rootPane, "displayModeSelector", DisplayModeSelector.class);
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            JLabel resultValueLabel = findComponent(rootPane, "resultValueLabel", JLabel.class);
            JList<?> historyList = findComponent(rootPane, "historyList", JList.class);

            assertEquals(DisplayMode.AUTO, selector.mode(), "The selector starts in Auto mode.");
            assertEquals(DisplayMode.values().length, selector.getItemCount(), "Every mode can be chosen.");
            assertTrue(historyList.getCellRenderer() instanceof HistoryLineRenderer, "The history uses the reusable renderer.");

            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.DIVISION);
                firstNumberField.setText("1");
                secondNumberField.setText("3");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));
            assertEquals("0,333333333333…", resultValueLabel.getText());
            assertEquals("1 / 3 = 0,333333333333…", historyText(historyList, 0));

            runOnEdt(() -> selector.setSelectedItem(DisplayMode.FRACTION));
            flushEdt();
            assertEquals("1/3", resultValueLabel.getText(), "Choosing Fraction formats the result again.");
            assertEquals("1 / 3 = 1/3", historyText(historyList, 0), "Choosing Fraction formats the history again.");
            assertEquals("1/3", firstNumberField.getText(), "The field keeps the exact value whatever the mode.");

            runOnEdt(() -> selector.setSelectedItem(DisplayMode.SCIENTIFIC));
            flushEdt();
            assertEquals("3,33333333333… × 10⁻¹", resultValueLabel.getText());
            assertEquals("1 / 3 = 3,33333333333… × 10⁻¹", historyText(historyList, 0));
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testBadgeFollowsTheExactnessOfTheResult() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            ExactnessBadge badge = findComponent(rootPane, "exactnessBadge", ExactnessBadge.class);
            JLabel fractionLabel = findComponent(rootPane, "resultFractionLabel", JLabel.class);
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JTextField secondNumberField = findComponent(rootPane, "secondNumberField", JTextField.class);
            AbstractButton calculateButton = findComponent(rootPane, "calculateButton", AbstractButton.class);

            assertFalse(badge.isVisible(), "There is no badge before a result.");

            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.DIVISION);
                firstNumberField.setText("1");
                secondNumberField.setText("4");
            });
            clickButton(calculateButton);
            assertTrue(badge.isVisible());
            assertEquals("Exact", badge.getText(), "1 ÷ 4 is exact.");
            assertEquals(null, badge.getToolTipText(), "An exact, complete result has nothing to add.");
            assertTrue(fractionLabel.getText().isBlank(), "The complete text needs no secondary fraction.");

            runOnEdt(() -> {
                firstNumberField.setText("1");
                secondNumberField.setText("3");
            });
            clickButton(calculateButton);
            assertEquals("Exact", badge.getText(), "1 ÷ 3 is exact even though its text is cut.");
            assertEquals("Valeur exacte : 1/3 (affichage arrondi)", badge.getToolTipText());
            assertTrue(fractionLabel.isVisible());
            assertEquals("Fraction exacte : 1/3", fractionLabel.getText());

            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.ROOT);
                firstNumberField.setText("2");
            });
            clickButton(calculateButton);
            assertEquals("≈ Approché", badge.getText(), "√2 is approximate.");
            assertEquals("Erreur maximale : ± 2,1 × 10⁻²⁵", badge.getToolTipText());
            assertTrue(fractionLabel.getText().isBlank(), "An approximate value has no exact fraction.");
            assertEquals("Exactitude du résultat : ≈ Approché", badge.getAccessibleContext().getAccessibleName());

            invokeShortcut(rootPane, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
            assertFalse(badge.isVisible(), "Clearing the result hides the badge.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testHistoryRendererShowsTheExactnessMarkOfEachLine() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JComboBox<?> operationSelector = findComponent(rootPane, "operationSelector", JComboBox.class);
            JTextField firstNumberField = findComponent(rootPane, "firstNumberField", JTextField.class);
            JList<?> historyList = findComponent(rootPane, "historyList", JList.class);
            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.ROOT);
                firstNumberField.setText("2");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));
            runOnEdt(() -> {
                operationSelector.setSelectedItem(Operation.ROOT);
                firstNumberField.setText("9");
            });
            clickButton(findComponent(rootPane, "calculateButton", AbstractButton.class));

            assertEquals("√(2) ≈ 1,41421356237…", historyText(historyList, 0));
            assertEquals("√(9) = 3", historyText(historyList, 1));
            assertEquals(List.of("≈ Approché", "Exact"), renderedMarks(historyList));
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testNextModeShortcutCyclesTheModesWithoutConflict() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            DisplayModeSelector selector = findComponent(rootPane, "displayModeSelector", DisplayModeSelector.class);
            KeyStroke nextMode = KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK);

            assertShortcut(rootPane, nextMode, "next-display-mode");
            List<DisplayMode> seen = new ArrayList<>();
            for (int step = 0; step < DisplayMode.values().length; step++) {
                invokeShortcut(rootPane, nextMode);
                seen.add(selector.mode());
            }
            assertEquals(
                    List.of(DisplayMode.DECIMAL, DisplayMode.FRACTION, DisplayMode.SCIENTIFIC, DisplayMode.AUTO), seen);

            // Ctrl+M must not be taken by a component of the window (focused or ancestor-of-focused bindings, with
            // their parent maps, which carry the look and feel's own keys) nor by another window-wide shortcut.
            List<String> conflicts = new ArrayList<>();
            collectBindingConflicts(rootPane, nextMode, conflicts);
            assertEquals(List.of(), conflicts, "No component may bind Ctrl+M itself.");
            Set<KeyStroke> windowWide = new HashSet<>(List.of(rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).keys()));
            assertEquals(
                    Set.of(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), nextMode),
                    windowWide,
                    "The window-wide shortcuts of the window are Escape, Ctrl+R and Ctrl+M only.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testRememberedModeIsShownAtStartAndChoosingOneRemembersIt() throws Exception {
        List<DisplayMode> saved = new ArrayList<>();
        DisplayModeStore store = new DisplayModeStore() {
            @Override
            public DisplayMode load() {
                return DisplayMode.SCIENTIFIC;
            }

            @Override
            public void save(DisplayMode mode) {
                saved.add(mode);
            }
        };
        CalculatorFrame frame = createFrame(new CalculatorPresenter(new CalculatorEngine(), CalculationHistoryStore.noop(), store));
        try {
            DisplayModeSelector selector = findComponent(frame.getRootPane(), "displayModeSelector", DisplayModeSelector.class);
            assertEquals(DisplayMode.SCIENTIFIC, selector.mode(), "The remembered mode is selected at start.");
            assertEquals(List.of(), saved, "Showing the mode does not write it.");
            runOnEdt(() -> selector.setSelectedItem(DisplayMode.DECIMAL));
            assertEquals(List.of(DisplayMode.DECIMAL), saved, "The user's choice is remembered.");
        } finally {
            disposeFrame(frame);
        }
    }

    @Test
    void testNewComponentsHaveAccessibleNamesAndLabels() throws Exception {
        CalculatorFrame frame = createFrame();
        try {
            JRootPane rootPane = frame.getRootPane();
            JLabel modeLabel = findComponent(rootPane, "modeLabel", JLabel.class);
            JComponent selector = findComponent(rootPane, "displayModeSelector", JComponent.class);
            assertEquals(selector, modeLabel.getLabelFor(), "The mode label labels the selector.");
            assertNotEquals(0, modeLabel.getDisplayedMnemonic(), "The mode label has a mnemonic.");
            for (String componentName : new String[] {"displayModeSelector", "exactnessBadge", "resultFractionLabel"}) {
                JComponent component = findComponent(rootPane, componentName, JComponent.class);
                String name = component.getAccessibleContext().getAccessibleName();
                String description = component.getAccessibleContext().getAccessibleDescription();
                assertTrue(name != null && !name.isBlank(), componentName + " should have an accessible name.");
                assertTrue(description != null && !description.isBlank(), componentName + " should have an accessible description.");
            }
            assertEquals("Mode d'affichage", selector.getAccessibleContext().getAccessibleName());
        } finally {
            disposeFrame(frame);
        }
    }

    private static String historyText(JList<?> historyList, int index) {
        return ((HistoryLine) historyList.getModel().getElementAt(index)).text();
    }

    // The exactness mark drawn under each history line, read from the real renderer of the list.
    private static List<String> renderedMarks(JList<?> historyList) throws Exception {
        List<String> marks = new ArrayList<>();
        runOnEdt(() -> {
            for (int index = 0; index < historyList.getModel().getSize(); index++) {
                Component cell = rendererComponent(historyList, index);
                List<JLabel> labels = new ArrayList<>();
                for (Component child : ((Container) cell).getComponents()) {
                    labels.add((JLabel) child);
                }
                marks.add(labels.get(1).getText());
            }
        });
        return marks;
    }

    @SuppressWarnings("unchecked")
    private static Component rendererComponent(JList<?> historyList, int index) {
        JList<HistoryLine> typed = (JList<HistoryLine>) historyList;
        return typed.getCellRenderer().getListCellRendererComponent(typed, typed.getModel().getElementAt(index), index, false, false);
    }

    private static void collectBindingConflicts(Container container, KeyStroke keyStroke, List<String> conflicts) {
        if (container instanceof JComponent component) {
            for (int condition : new int[] {JComponent.WHEN_FOCUSED, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT}) {
                for (InputMap map = component.getInputMap(condition); map != null; map = map.getParent()) {
                    if (map.get(keyStroke) != null) {
                        conflicts.add(component.getClass().getSimpleName() + "/" + component.getName() + " binds " + keyStroke);
                    }
                }
            }
        }
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                collectBindingConflicts(nested, keyStroke, conflicts);
            }
        }
    }

    private static CalculatorFrame createFrame() throws InterruptedException, InvocationTargetException {
        return createFrame(new CalculatorPresenter(new CalculatorEngine()));
    }

    private static CalculatorFrame createFrame(CalculatorPresenter presenter) throws InterruptedException, InvocationTargetException {
        final CalculatorFrame[] frameHolder = new CalculatorFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            CalculatorFrame frame = new CalculatorFrame(presenter);
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
