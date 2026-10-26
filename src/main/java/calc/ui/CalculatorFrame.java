package calc.ui;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class CalculatorFrame extends JFrame {
    private static final Color SUCCESS_COLOR = new Color(18, 93, 56);
    private static final Color ERROR_COLOR = new Color(163, 44, 44);
    private static final Color INFO_COLOR = new Color(60, 60, 60);
    private static final String NO_RESULT = "--";
    private static final int RESULT_MAX_PREFERRED_WIDTH = 360;
    private static final int TOOLTIP_LINE_LENGTH = 60;

    private final CalculatorPresenter presenter;
    private final JTextField firstNumberField;
    private final JTextField secondNumberField;
    private final JLabel firstNumberLabel;
    private final JLabel secondNumberLabel;
    private final JLabel operationLabel;
    private final JComboBox<Operation> operationSelector;
    private final JLabel inputHintLabel;
    private final JLabel resultValueLabel;
    private final JLabel statusLabel;
    private final DefaultListModel<CalculationRecord> historyModel;
    private final JList<CalculationRecord> historyList;
    private final JButton calculateButton;
    private final JButton clearButton;
    private final JButton clearHistoryButton;
    private final JButton reuseLastResultButton;
    private final JButton reuseAsFirstButton;
    private final JButton reuseAsSecondButton;

    private boolean applyingViewModel;

    public CalculatorFrame(CalculatorPresenter presenter) {
        super("Calc");
        this.presenter = Objects.requireNonNull(presenter);
        firstNumberField = new JTextField(14);
        secondNumberField = new JTextField(14);
        firstNumberLabel = new JLabel("Premier nombre");
        secondNumberLabel = new JLabel("Second nombre");
        operationLabel = new JLabel("Opération");
        operationSelector = new JComboBox<>(Operation.values());
        inputHintLabel = new JLabel();
        resultValueLabel = new JLabel(NO_RESULT, SwingConstants.RIGHT) {
            // A result can hold up to 1000 digits: let the layout shrink the label (ellipsis)
            // instead of letting the text widen the window.
            @Override
            public Dimension getMinimumSize() {
                return new Dimension(0, super.getMinimumSize().height);
            }

            @Override
            public Dimension getPreferredSize() {
                Dimension size = super.getPreferredSize();
                return new Dimension(Math.min(size.width, RESULT_MAX_PREFERRED_WIDTH), size.height);
            }
        };
        statusLabel = new JLabel();
        historyModel = new DefaultListModel<>();
        historyList = new JList<>(historyModel);
        calculateButton = new JButton("Calculer");
        clearButton = new JButton("Effacer");
        clearHistoryButton = new JButton("Effacer historique");
        reuseLastResultButton = new JButton("Reprendre dernier résultat");
        reuseAsFirstButton = new JButton("Vers nombre 1");
        reuseAsSecondButton = new JButton("Vers nombre 2");

        assignComponentNames();
        configureAccessibility();
        configureFrame();
        bindEvents();
        applyViewModel(presenter.createInitialView());
    }

    private void configureFrame() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(buildContent());
        getRootPane().setDefaultButton(calculateButton);
        pack();
        setMinimumSize(new Dimension(940, 450));
        setLocationRelativeTo(null);
    }

    private JPanel buildContent() {
        JPanel root = new JPanel(new BorderLayout(16, 16));
        root.setBorder(new EmptyBorder(16, 16, 16, 16));
        root.add(buildHeader(), BorderLayout.NORTH);

        JPanel center = new JPanel(new BorderLayout(16, 0));
        center.add(buildFormPanel(), BorderLayout.CENTER);
        center.add(buildHistoryPanel(), BorderLayout.EAST);
        root.add(center, BorderLayout.CENTER);
        return root;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 4));
        JLabel title = new JLabel("Calculatrice fiable");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));

        JLabel subtitle = new JLabel(
                "<html>Entrée calcule, Échap efface, Ctrl+R reprend le dernier résultat. "
                        + "Le résultat se prépare automatiquement pour le calcul suivant.</html>");
        subtitle.setForeground(INFO_COLOR);

        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Calcul"));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1;
        gbc.gridx = 0;
        gbc.gridy = 0;

        panel.add(firstNumberLabel, gbc);

        gbc.gridy++;
        firstNumberField.setToolTipText("Saisissez le premier nombre.");
        panel.add(firstNumberField, gbc);

        gbc.gridy++;
        panel.add(operationLabel, gbc);

        gbc.gridy++;
        operationSelector.setToolTipText("Choisissez l'opération. Le clavier permet d'utiliser les flèches du sélecteur.");
        panel.add(operationSelector, gbc);

        gbc.gridy++;
        panel.add(secondNumberLabel, gbc);

        gbc.gridy++;
        secondNumberField.setToolTipText("Saisissez le second nombre.");
        panel.add(secondNumberField, gbc);

        gbc.gridy++;
        inputHintLabel.setForeground(INFO_COLOR);
        panel.add(inputHintLabel, gbc);

        gbc.gridy++;
        panel.add(buildActionButtons(), gbc);

        gbc.gridy++;
        panel.add(buildResultPanel(), gbc);

        return panel;
    }

    private JPanel buildActionButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(calculateButton);
        buttons.add(reuseLastResultButton);
        buttons.add(clearButton);
        return buttons;
    }

    private JPanel buildResultPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Résultat"));

        resultValueLabel.setFont(resultValueLabel.getFont().deriveFont(Font.BOLD, 24f));
        statusLabel.setForeground(INFO_COLOR);

        JLabel helper = new JLabel(
                "<html>Le résultat affiché est exactement celui qui sera réutilisé et enregistré.<br>"
                        + "Un résultat exact n'est jamais arrondi ; les autres sont arrondis<br>"
                        + "à 12 décimales et 12 chiffres significatifs minimum.</html>");
        helper.setForeground(INFO_COLOR);

        panel.add(resultValueLabel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        panel.add(helper, BorderLayout.NORTH);
        return panel;
    }

    private JPanel buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setPreferredSize(new Dimension(350, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Historique"));

        JLabel helper = new JLabel(
                "<html>Sélectionnez une ligne pour la réutiliser.<br>Sans sélection, le dernier résultat est repris.</html>");
        helper.setForeground(INFO_COLOR);
        panel.add(helper, BorderLayout.NORTH);

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setVisibleRowCount(10);
        panel.add(new JScrollPane(historyList), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actions.add(reuseAsFirstButton);
        actions.add(reuseAsSecondButton);
        actions.add(clearHistoryButton);
        panel.add(actions, BorderLayout.SOUTH);

        return panel;
    }

    private void bindEvents() {
        calculateButton.addActionListener(event -> handleCalculation());
        clearButton.addActionListener(event -> applyViewModel(
                presenter.onClearFields(firstNumberField.getText(), secondNumberField.getText())));
        clearHistoryButton.addActionListener(event -> applyViewModel(presenter.onClearHistory()));
        reuseLastResultButton.addActionListener(event -> applyViewModel(
                presenter.onReuseLastResult(firstNumberField.getText(), secondNumberField.getText())));
        reuseAsFirstButton.addActionListener(event -> applyViewModel(
                presenter.onReuseHistoryResult(ReuseTarget.FIRST_NUMBER, firstNumberField.getText(), secondNumberField.getText())));
        reuseAsSecondButton.addActionListener(event -> applyViewModel(
                presenter.onReuseHistoryResult(ReuseTarget.SECOND_NUMBER, firstNumberField.getText(), secondNumberField.getText())));

        operationSelector.addActionListener(event -> {
            if (applyingViewModel) {
                return;
            }
            applyViewModel(presenter.onOperationChanged(
                    (Operation) operationSelector.getSelectedItem(),
                    firstNumberField.getText(),
                    secondNumberField.getText()));
        });

        historyList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || applyingViewModel) {
                return;
            }
            applyViewModel(presenter.onHistorySelectionChanged(historyList.getSelectedIndex()));
        });

        bindKeyboardActions();
    }

    private void bindKeyboardActions() {
        InputMap inputMap = getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "clear-fields");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK), "reuse-last-result");

        getRootPane().getActionMap().put("clear-fields", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                applyViewModel(presenter.onClearFields(firstNumberField.getText(), secondNumberField.getText()));
            }
        });
        getRootPane().getActionMap().put("reuse-last-result", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                applyViewModel(presenter.onReuseLastResult(firstNumberField.getText(), secondNumberField.getText()));
            }
        });
    }

    private void handleCalculation() {
        applyViewModel(presenter.onCalculate(firstNumberField.getText(), secondNumberField.getText()));
    }

    private void applyViewModel(CalculatorViewModel viewModel) {
        applyingViewModel = true;
        try {
            operationSelector.setSelectedItem(viewModel.getSelectedOperation());
            firstNumberField.setText(viewModel.getFirstInput());
            secondNumberField.setText(viewModel.getSecondInput());
            secondNumberField.setEnabled(viewModel.isSecondInputEnabled());
            secondNumberField.setEditable(viewModel.isSecondInputEnabled());
            secondNumberLabel.setText(viewModel.getSecondInputLabel());
            secondNumberField.getAccessibleContext().setAccessibleName(viewModel.getSecondInputLabel());
            secondNumberField.setToolTipText(viewModel.getSecondInputTooltip());
            inputHintLabel.setText(viewModel.getInputHint());
            showResult(viewModel.getResultValue());
            setStatusMessage(viewModel.getStatusMessage(), resolveStatusColor(viewModel.getStatusTone()));

            historyModel.clear();
            for (CalculationRecord record : viewModel.getHistoryEntries()) {
                historyModel.addElement(record);
            }

            if (viewModel.getSelectedHistoryIndex() >= 0) {
                historyList.setSelectedIndex(viewModel.getSelectedHistoryIndex());
                historyList.ensureIndexIsVisible(viewModel.getSelectedHistoryIndex());
            } else {
                historyList.clearSelection();
            }

            reuseAsFirstButton.setEnabled(viewModel.isReuseAsFirstEnabled());
            reuseAsSecondButton.setEnabled(viewModel.isReuseAsSecondEnabled());
            reuseLastResultButton.setEnabled(viewModel.isReuseLastResultEnabled());
            clearHistoryButton.setEnabled(viewModel.isClearHistoryEnabled());
        } finally {
            applyingViewModel = false;
        }

        requestFocus(viewModel);
    }

    private void requestFocus(CalculatorViewModel viewModel) {
        if (viewModel.getFocusTarget() == FocusTarget.NONE) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            JTextField targetField = null;
            if (viewModel.getFocusTarget() == FocusTarget.FIRST_NUMBER) {
                targetField = firstNumberField;
            } else if (viewModel.getFocusTarget() == FocusTarget.SECOND_NUMBER && secondNumberField.isEnabled()) {
                targetField = secondNumberField;
            }

            if (targetField == null) {
                return;
            }

            targetField.requestFocusInWindow();
            if (viewModel.shouldSelectFocusedFieldText()) {
                targetField.selectAll();
            }
        });
    }

    private Color resolveStatusColor(StatusTone statusTone) {
        if (statusTone == StatusTone.SUCCESS) {
            return SUCCESS_COLOR;
        }
        if (statusTone == StatusTone.ERROR) {
            return ERROR_COLOR;
        }
        return INFO_COLOR;
    }

    private void setStatusMessage(String message, Color color) {
        statusLabel.setForeground(color == null ? UIManager.getColor("Label.foreground") : color);
        statusLabel.setText(message);
    }

    private void showResult(String value) {
        resultValueLabel.setText(value);
        resultValueLabel.setToolTipText(NO_RESULT.equals(value) ? null : toTooltip(value));
        resultValueLabel.getAccessibleContext().setAccessibleName("Résultat : " + value);
    }

    private static String toTooltip(String value) {
        if (value.length() <= TOOLTIP_LINE_LENGTH) {
            return value;
        }
        StringBuilder html = new StringBuilder("<html>");
        for (int start = 0; start < value.length(); start += TOOLTIP_LINE_LENGTH) {
            if (start > 0) {
                html.append("<br>");
            }
            html.append(value, start, Math.min(start + TOOLTIP_LINE_LENGTH, value.length()));
        }
        return html.append("</html>").toString();
    }

    private void configureAccessibility() {
        label(firstNumberLabel, KeyEvent.VK_P, firstNumberField);
        label(operationLabel, KeyEvent.VK_O, operationSelector);
        label(secondNumberLabel, KeyEvent.VK_S, secondNumberField);

        calculateButton.setMnemonic(KeyEvent.VK_C);
        reuseLastResultButton.setMnemonic(KeyEvent.VK_R);
        clearButton.setMnemonic(KeyEvent.VK_E);
        reuseAsFirstButton.setMnemonic(KeyEvent.VK_1);
        reuseAsSecondButton.setMnemonic(KeyEvent.VK_2);
        clearHistoryButton.setMnemonic(KeyEvent.VK_H);

        describe(firstNumberField, "Premier nombre", "Saisie du premier nombre du calcul");
        describe(secondNumberField, "Second nombre", "Saisie du second nombre du calcul");
        describe(operationSelector, "Opération", "Choix de l'opération à effectuer");
        describe(historyList, "Historique des calculs", "Liste des calculs précédents, à sélectionner pour les réutiliser");
        describe(resultValueLabel, "Résultat", "Résultat du dernier calcul");
    }

    private static void label(JLabel label, int mnemonic, JComponent target) {
        label.setDisplayedMnemonic(mnemonic);
        label.setLabelFor(target);
    }

    private static void describe(JComponent component, String name, String description) {
        component.getAccessibleContext().setAccessibleName(name);
        component.getAccessibleContext().setAccessibleDescription(description);
    }

    private void assignComponentNames() {
        firstNumberField.setName("firstNumberField");
        secondNumberField.setName("secondNumberField");
        firstNumberLabel.setName("firstNumberLabel");
        secondNumberLabel.setName("secondNumberLabel");
        operationLabel.setName("operationLabel");
        operationSelector.setName("operationSelector");
        inputHintLabel.setName("inputHintLabel");
        resultValueLabel.setName("resultValueLabel");
        statusLabel.setName("statusLabel");
        historyList.setName("historyList");
        calculateButton.setName("calculateButton");
        clearButton.setName("clearButton");
        clearHistoryButton.setName("clearHistoryButton");
        reuseLastResultButton.setName("reuseLastResultButton");
        reuseAsFirstButton.setName("reuseAsFirstButton");
        reuseAsSecondButton.setName("reuseAsSecondButton");
    }
}
