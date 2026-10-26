package calc.ui.widget;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ToolTipManager;

/**
 * Draws a {@link HistoryLine} on two lines: the calculation with its formatted result, then an exactness mark
 * (« Exact » or « ≈ Approché », in words, the colour only completes it). Call {@link #install(JList)} so that the detail
 * of each line shows as a tooltip.
 */
public final class HistoryLineRenderer extends JPanel implements ListCellRenderer<HistoryLine> {
    private static final long serialVersionUID = 1L;

    private final JLabel lineLabel = new JLabel();
    private final JLabel markLabel = new JLabel();

    public HistoryLineRenderer() {
        super(new BorderLayout(0, 1));
        setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
        markLabel.setFont(markLabel.getFont().deriveFont(Font.PLAIN, markLabel.getFont().getSize2D() - 1f));
        add(lineLabel, BorderLayout.CENTER);
        add(markLabel, BorderLayout.SOUTH);
    }

    /** Uses this renderer for {@code list} and enables the tooltips of its cells. */
    public static void install(JList<HistoryLine> list) {
        list.setCellRenderer(new HistoryLineRenderer());
        ToolTipManager.sharedInstance().registerComponent(list);
    }

    @Override
    public Component getListCellRendererComponent(
            JList<? extends HistoryLine> list, HistoryLine line, int index, boolean selected, boolean focused) {
        lineLabel.setText(line.text());
        String mark = line.exact() ? ExactnessBadge.EXACT_TEXT : ExactnessBadge.APPROXIMATE_TEXT;
        markLabel.setText(mark);

        // A selected row keeps the look and feel's own selection colours: they are chosen to contrast with each other.
        setOpaque(true);
        if (selected) {
            setBackground(list.getSelectionBackground());
            lineLabel.setForeground(list.getSelectionForeground());
            markLabel.setForeground(list.getSelectionForeground());
        } else {
            setBackground(list.getBackground());
            lineLabel.setForeground(list.getForeground());
            markLabel.setForeground(line.exact() ? Palette.EXACT_FOREGROUND : Palette.APPROXIMATE_FOREGROUND);
        }

        String tooltip = line.detail().isEmpty() ? null : line.detail();
        setToolTipText(tooltip);
        lineLabel.setToolTipText(tooltip);
        markLabel.setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(line.text() + " (" + mark + ")");
        getAccessibleContext().setAccessibleDescription(line.detail().isEmpty() ? "Entrée de l'historique" : line.detail());
        return this;
    }
}
