package calc.ui.notebook;

import calc.detail.LineDetailText;
import calc.ui.design.DesignTokens;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The detail of the active line (SPEC 4.2 « Détail de la ligne active »): the sentences of {@link LineDetailText}, one per
 * line, the formulas (expansion and result) in JetBrains Mono, the other sentences in Inter, all in {@code text.secondary}.
 * They sit in a box on {@code surface.raised}, outlined in {@code border.subtle} (docs/design/DESIGN.md, « Détail de la ligne
 * active »). The window holds one, which moves under the active line. Without sentences it holds nothing, draws nothing and
 * takes no height.
 */
final class LineDetailView extends JPanel {
    private static final long serialVersionUID = 1L;

    private final Font formulaFont;
    private final Font sentenceFont;
    private final transient List<JLabel> labels = new ArrayList<>();
    private transient List<LineDetailText.Sentence> shown = List.of();

    LineDetailView() {
        setName("lineDetail");
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        getAccessibleContext().setAccessibleName("Détail de la ligne active");
        DesignTokens tokens = DesignTokens.load();
        formulaFont = new Font(tokens.fontFamily("mono"), Font.PLAIN, UIScale.scale(tokens.size("font.size.body")));
        sentenceFont = new Font(tokens.fontFamily("ui"), Font.PLAIN, UIScale.scale(tokens.size("font.size.caption")));
    }

    /** Shows these sentences; the same sentences as before change nothing. */
    void show(List<LineDetailText.Sentence> sentences) {
        if (sentences.equals(shown)) {
            return;
        }
        shown = List.copyOf(sentences);
        while (labels.size() > sentences.size()) {
            remove(labels.remove(labels.size() - 1));
        }
        while (labels.size() < sentences.size()) {
            JLabel label = new JLabel();
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.setForeground(NotebookPalette.active().detailForeground());
            labels.add(label);
            add(label);
        }
        for (int index = 0; index < sentences.size(); index++) {
            LineDetailText.Sentence sentence = sentences.get(index);
            JLabel label = labels.get(index);
            label.setText(sentence.text());
            label.setFont(sentence.formula() ? formulaFont : sentenceFont);
        }
        // The padding of the box and a gap under it, only around sentences: an empty detail takes no height at all.
        int padding = scaled("space.3");
        setBorder(sentences.isEmpty() ? null : BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 0, scaled("space.2"), 0),
                BorderFactory.createEmptyBorder(padding, padding, padding, padding)));
        revalidate();
        repaint();
    }

    /** The box: {@code surface.raised}, a {@code border.subtle} outline, the corners of {@code radius.medium}. */
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (shown.isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            NotebookPalette palette = NotebookPalette.active();
            int arc = 2 * scaled("radius.medium");
            int height = getHeight() - scaled("space.2");
            g.setColor(palette.detailBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, height - 1, arc, arc);
            g.setColor(palette.border());
            g.drawRoundRect(0, 0, getWidth() - 1, height - 1, arc, arc);
        } finally {
            g.dispose();
        }
    }

    /** A change of theme: the sentences take the colour of the new theme. */
    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of JPanel before the labels exist.
        if (labels != null) {
            for (JLabel label : labels) {
                label.setForeground(NotebookPalette.active().detailForeground());
            }
        }
    }

    private static int scaled(String token) {
        return UIScale.scale(DesignTokens.load().size(token));
    }
}
