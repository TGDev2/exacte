package calc.ui.notebook;

import calc.ui.notebook.NotebookRow.Status;
import calc.ui.widget.ExactnessBadge;
import calc.ui.widget.Palette;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * One row of the notebook: number, field, the result aligned on the right with the exactness badge of the display
 * components, and below the field the message of a line in error, in words. It only draws the {@link NotebookRow} it is
 * given; it holds no logic.
 */
final class NotebookRowView extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int RESULT_WIDTH = 330;
    private static final int BADGE_WIDTH = 96;

    final int index;
    final LineField field = new LineField();
    final JLabel numberLabel = new JLabel("", SwingConstants.RIGHT);
    final JLabel resultLabel = new JLabel("", SwingConstants.RIGHT);
    final JLabel errorLabel = new JLabel("");
    final ExactnessBadge badge = new ExactnessBadge();

    private final Font plainFont;
    private final Font italicFont;
    private final Font boldNumberFont;
    private final Font plainNumberFont;
    private transient NotebookRow shownRow;
    private boolean shownActive;

    NotebookRowView(int index) {
        super(new GridBagLayout());
        this.index = index;
        setName("row");
        setOpaque(true);

        Font base = UIManager.getFont("TextField.font");
        float size = (base != null ? base.getSize2D() : 13f) + 1f;
        plainFont = new Font(Font.MONOSPACED, Font.PLAIN, Math.round(size));
        italicFont = plainFont.deriveFont(Font.ITALIC);
        plainNumberFont = plainFont.deriveFont(size - 1f);
        boldNumberFont = plainNumberFont.deriveFont(Font.BOLD);

        field.setName("lineField");
        field.setFont(plainFont);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 200, 200)), BorderFactory.createEmptyBorder(3, 6, 3, 6)));

        numberLabel.setName("numberLabel");
        numberLabel.setOpaque(true);
        numberLabel.setFont(plainNumberFont);
        numberLabel.setForeground(NotebookPalette.NUMBER_FOREGROUND);
        FontMetrics metrics = numberLabel.getFontMetrics(boldNumberFont);
        numberLabel.setPreferredSize(new Dimension(metrics.stringWidth("5000") + 16, 24));
        numberLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 8));
        numberLabel.getAccessibleContext().setAccessibleName("Numéro");

        resultLabel.setName("resultLabel");
        resultLabel.setFont(plainFont);
        resultLabel.setForeground(NotebookPalette.RESULT_FOREGROUND);
        badge.setName("exactnessBadge");
        badge.setPreferredSize(new Dimension(BADGE_WIDTH, badge.getPreferredSize().height));

        errorLabel.setName("errorLabel");
        errorLabel.setForeground(Palette.ERROR);
        errorLabel.setFont(plainNumberFont);
        errorLabel.setVisible(false);

        JPanel result = new JPanel(new BorderLayout(8, 0));
        result.setOpaque(false);
        result.setPreferredSize(new Dimension(RESULT_WIDTH, 28));
        result.setMinimumSize(new Dimension(RESULT_WIDTH / 2, 28));
        result.add(resultLabel, BorderLayout.CENTER);
        result.add(badge, BorderLayout.EAST);

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.CENTER;
        c.insets = new Insets(3, 0, 1, 0);
        c.gridx = 0;
        c.fill = GridBagConstraints.VERTICAL;
        add(numberLabel, c);
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 4, 1, 8);
        add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(3, 0, 1, 4);
        add(result, c);
        c.gridx = 1;
        c.gridy = 1;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 6, 3, 8);
        add(errorLabel, c);

        // A click anywhere on the row, outside the field, puts the caret in its field.
        MouseAdapter focusField = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                field.requestFocusInWindow();
            }
        };
        addMouseListener(focusField);
        numberLabel.addMouseListener(focusField);
        resultLabel.addMouseListener(focusField);
        errorLabel.addMouseListener(focusField);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Shows {@code row}. {@code text} is what the field must hold (the draft for the active row); a field that already
     * holds it is left alone, so that the caret does not move.
     */
    void show(NotebookRow row, boolean active, String text, String watermark) {
        boolean rowChanged = !row.equals(shownRow);
        if (rowChanged) {
            showRow(row);
        }
        if (rowChanged || active != shownActive) {
            restyle(active, row);
        }
        shownRow = row;
        shownActive = active;
        boolean textChanged = !field.getText().equals(text);
        if (textChanged) {
            field.setText(text);
        }
        field.setPlaceholder(watermark);
        if (rowChanged || textChanged) {
            // The mark is only right for the text the evaluator saw.
            field.markError(row.status() == Status.ERROR && text.equals(row.text()) ? row.errorStart() : -1, row.errorEnd());
        }
    }

    private void showRow(NotebookRow row) {
        numberLabel.setText(String.valueOf(row.number()));
        field.getAccessibleContext().setAccessibleName(row.name());
        field.getAccessibleContext().setAccessibleDescription(row.description());
        field.setFont(row.status() == Status.COMMENT ? italicFont : plainFont);

        resultLabel.setText(row.result());
        String tooltip = row.detail().isEmpty() ? row.result() : row.result() + " — " + row.detail();
        resultLabel.setToolTipText(row.result().isEmpty() ? null : tooltip);
        resultLabel.getAccessibleContext().setAccessibleName("Résultat de la " + row.name().toLowerCase(java.util.Locale.ROOT));
        resultLabel.getAccessibleContext().setAccessibleDescription(row.description());
        if (row.hasValue()) {
            badge.showExactness(row.status() == Status.EXACT, row.detail());
        } else {
            badge.clearBadge();
        }

        boolean error = row.status() == Status.ERROR;
        errorLabel.setVisible(error);
        errorLabel.setText(error ? "Erreur : " + row.errorMessage() : "");
        errorLabel.setToolTipText(error ? row.errorMessage() : null);
        errorLabel.getAccessibleContext().setAccessibleName("Erreur de la " + row.name().toLowerCase(java.util.Locale.ROOT));

        numberLabel.setToolTipText(row.definesVariable() ? "Définit la variable « " + row.definedName() + " »" : null);
    }

    private void restyle(boolean active, NotebookRow row) {
        Color background = active ? NotebookPalette.ACTIVE_ROW_BACKGROUND : NotebookPalette.ROW_BACKGROUND;
        setBackground(background);
        numberLabel.setFont(active ? boldNumberFont : plainNumberFont);
        if (row.definesVariable()) {
            numberLabel.setBackground(NotebookPalette.VARIABLE_BACKGROUND);
            numberLabel.setForeground(NotebookPalette.VARIABLE_FOREGROUND);
        } else {
            numberLabel.setBackground(background);
            numberLabel.setForeground(NotebookPalette.NUMBER_FOREGROUND);
        }
    }
}
