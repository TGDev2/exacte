package calc.ui.widget;

import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.border.Border;

/**
 * « Exact » or « ≈ Approché », with the detail (error bound, exact value behind a cut text) as tooltip. The text always
 * says what the colour says. It is empty and invisible while there is no result.
 */
public final class ExactnessBadge extends JLabel {
    private static final long serialVersionUID = 1L;
    public static final String EXACT_TEXT = "Exact";
    public static final String APPROXIMATE_TEXT = "≈ Approché";
    private static final String NAME = "Exactitude du résultat";

    public ExactnessBadge() {
        super("", SwingConstants.CENTER);
        setOpaque(true);
        clearBadge();
    }

    /** Shows the badge of a result. {@code detail} is the tooltip, empty for none. */
    public void showExactness(boolean exact, String detail) {
        String text = exact ? EXACT_TEXT : APPROXIMATE_TEXT;
        Color foreground = exact ? Palette.EXACT_FOREGROUND : Palette.APPROXIMATE_FOREGROUND;
        setText(text);
        setForeground(foreground);
        setBackground(exact ? Palette.EXACT_BACKGROUND : Palette.APPROXIMATE_BACKGROUND);
        setBorder(border(foreground));
        setToolTipText(detail.isEmpty() ? null : detail);
        setVisible(true);
        getAccessibleContext().setAccessibleName(NAME + " : " + text);
        getAccessibleContext().setAccessibleDescription(detail.isEmpty() ? NAME : detail);
    }

    /** Hides the badge: there is no result to qualify. */
    public void clearBadge() {
        setText("");
        setToolTipText(null);
        setVisible(false);
        getAccessibleContext().setAccessibleName(NAME);
        getAccessibleContext().setAccessibleDescription("Aucun résultat");
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    private static Border border(Color color) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 1, true), BorderFactory.createEmptyBorder(2, 8, 2, 8));
    }
}
