package Graphique;

import Calcul.CalculationException;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.Operation;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

public class CalculatorFrame extends JFrame {
    private static final Color SUCCESS_COLOR = new Color(18, 93, 56);
    private static final Color ERROR_COLOR = new Color(163, 44, 44);
    private static final Color INFO_COLOR = new Color(60, 60, 60);

    private final CalculatorEngine engine;
    private final JTextField firstNumberField;
    private final JTextField secondNumberField;
    private final JLabel secondNumberLabel;
    private final JComboBox<Operation> operationSelector;
    private final JLabel inputHintLabel;
    private final JLabel resultValueLabel;
    private final JLabel statusLabel;
    private final DefaultListModel<CalculationRecord> historyModel;
    private final JList<CalculationRecord> historyList;
    private final JButton reuseAsFirstButton;
    private final JButton reuseAsSecondButton;

    public CalculatorFrame(CalculatorEngine engine) {
        super("Calc");
        this.engine = Objects.requireNonNull(engine);
        firstNumberField = new JTextField(14);
        secondNumberField = new JTextField(14);
        secondNumberLabel = new JLabel("Second nombre");
        operationSelector = new JComboBox<>(Operation.values());
        inputHintLabel = new JLabel();
        resultValueLabel = new JLabel("--", SwingConstants.RIGHT);
        statusLabel = new JLabel("Saisissez un calcul puis cliquez sur Calculer.");
        historyModel = new DefaultListModel<>();
        historyList = new JList<>(historyModel);
        reuseAsFirstButton = new JButton("Vers nombre 1");
        reuseAsSecondButton = new JButton("Vers nombre 2");

        configureFrame();
        bindEvents();
        updateInputState();
        updateHistoryActionsState();
    }

    private void configureFrame() {
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setContentPane(buildContent());
        pack();
        setMinimumSize(new Dimension(860, 430));
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

        JLabel subtitle = new JLabel("Virgule ou point acceptes. L'historique peut etre reutilise pour les calculs suivants.");
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

        panel.add(new JLabel("Premier nombre"), gbc);

        gbc.gridy++;
        firstNumberField.setToolTipText("Saisissez le premier nombre.");
        panel.add(firstNumberField, gbc);

        gbc.gridy++;
        panel.add(new JLabel("Operation"), gbc);

        gbc.gridy++;
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
        JButton calculateButton = new JButton("Calculer");
        JButton clearButton = new JButton("Effacer");

        calculateButton.addActionListener(event -> handleCalculation());
        clearButton.addActionListener(event -> clearFields());

        buttons.add(calculateButton);
        buttons.add(clearButton);
        return buttons;
    }

    private JPanel buildResultPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Resultat"));

        resultValueLabel.setFont(resultValueLabel.getFont().deriveFont(Font.BOLD, 24f));
        statusLabel.setForeground(INFO_COLOR);

        panel.add(resultValueLabel, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel buildHistoryPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setPreferredSize(new Dimension(330, 0));
        panel.setBorder(BorderFactory.createTitledBorder("Historique"));

        JLabel helper = new JLabel("<html>Selectionnez une ligne pour la reutiliser.<br>Sans selection, le dernier resultat est repris.</html>");
        helper.setForeground(INFO_COLOR);
        panel.add(helper, BorderLayout.NORTH);

        historyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        historyList.setVisibleRowCount(10);
        panel.add(new JScrollPane(historyList), BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        JButton clearHistoryButton = new JButton("Effacer historique");
        reuseAsFirstButton.addActionListener(event -> reuseHistoryResult(firstNumberField, "le premier nombre"));
        reuseAsSecondButton.addActionListener(event -> reuseHistoryResult(secondNumberField, "le second nombre"));
        clearHistoryButton.addActionListener(event -> clearHistory());
        actions.add(reuseAsFirstButton);
        actions.add(reuseAsSecondButton);
        actions.add(clearHistoryButton);
        panel.add(actions, BorderLayout.SOUTH);

        return panel;
    }

    private void bindEvents() {
        operationSelector.addActionListener(event -> updateInputState());
        historyList.addListSelectionListener(event -> updateHistoryActionsState());
    }

    private void handleCalculation() {
        try {
            Operation operation = (Operation) operationSelector.getSelectedItem();
            CalculationRecord record = engine.calculate(operation, firstNumberField.getText(), secondNumberField.getText());
            resultValueLabel.setText(record.getFormattedResult());
            historyModel.addElement(record);
            historyList.setSelectedIndex(historyModel.getSize() - 1);
            historyList.ensureIndexIsVisible(historyModel.getSize() - 1);
            setStatusMessage("Calcul effectue et ajoute a l'historique.", SUCCESS_COLOR);
            updateHistoryActionsState();
        } catch (CalculationException exception) {
            resultValueLabel.setText("--");
            setStatusMessage(exception.getMessage(), ERROR_COLOR);
        }
    }

    private void clearFields() {
        firstNumberField.setText("");
        secondNumberField.setText("");
        resultValueLabel.setText("--");
        setStatusMessage("Champs effaces. Vous pouvez saisir un nouveau calcul.", INFO_COLOR);
        firstNumberField.requestFocusInWindow();
    }

    private void clearHistory() {
        historyModel.clear();
        historyList.clearSelection();
        updateHistoryActionsState();
        setStatusMessage("Historique efface.", INFO_COLOR);
    }

    private void reuseHistoryResult(JTextField targetField, String targetLabel) {
        CalculationRecord record = resolveHistoryRecord();
        if (record == null) {
            setStatusMessage("Aucun resultat disponible dans l'historique.", ERROR_COLOR);
            return;
        }

        targetField.setText(record.getReusableValue());
        targetField.requestFocusInWindow();
        targetField.selectAll();
        setStatusMessage("Le resultat historique a ete copie dans " + targetLabel + ".", SUCCESS_COLOR);
    }

    private CalculationRecord resolveHistoryRecord() {
        CalculationRecord selectedRecord = historyList.getSelectedValue();
        if (selectedRecord != null) {
            return selectedRecord;
        }
        if (historyModel.isEmpty()) {
            return null;
        }
        return historyModel.getElementAt(historyModel.getSize() - 1);
    }

    private void updateInputState() {
        Operation operation = (Operation) operationSelector.getSelectedItem();
        boolean requiresSecondOperand = operation != null && operation.requiresSecondOperand();

        secondNumberField.setEnabled(requiresSecondOperand);
        secondNumberField.setEditable(requiresSecondOperand);
        secondNumberLabel.setText(requiresSecondOperand ? "Second nombre" : "Second nombre (non utilise)");
        secondNumberField.setToolTipText(requiresSecondOperand
                ? "Saisissez le second nombre."
                : "Ce champ n'est pas utilise pour la racine carree.");

        if (!requiresSecondOperand) {
            secondNumberField.setText("");
        }

        if (operation == null) {
            inputHintLabel.setText("Choisissez une operation.");
        } else {
            inputHintLabel.setText(operation.getInputHint());
        }

        updateHistoryActionsState();
    }

    private void updateHistoryActionsState() {
        boolean hasHistory = !historyModel.isEmpty();
        reuseAsFirstButton.setEnabled(hasHistory);
        Operation operation = (Operation) operationSelector.getSelectedItem();
        boolean canReuseAsSecond = hasHistory && operation != null && operation.requiresSecondOperand();
        reuseAsSecondButton.setEnabled(canReuseAsSecond);
    }

    private void setStatusMessage(String message, Color color) {
        statusLabel.setForeground(color == null ? UIManager.getColor("Label.foreground") : color);
        statusLabel.setText(message);
    }
}
