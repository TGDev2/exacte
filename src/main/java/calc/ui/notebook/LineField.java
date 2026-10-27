package calc.ui.notebook;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;

/**
 * The text field of one line: a watermark while it is empty (the help of an empty notebook, never part of the text), a
 * line under the faulty part of an error, and a bound on what it accepts so that a huge paste cannot fill the memory
 * (the presenter refuses a line over {@link NotebookPresenter#MAX_LINE_LENGTH} with a message; the field lets one more
 * character through so that the message can be given).
 */
final class LineField extends JTextField {
    private static final long serialVersionUID = 1L;
    private static final Highlighter.HighlightPainter UNDERLINE = new UnderlinePainter();

    private String placeholder = "";

    LineField() {
        ((AbstractDocument) getDocument()).setDocumentFilter(new LengthFilter(NotebookPresenter.MAX_LINE_LENGTH + 1));
        // A change of the text moves the faulty part: the mark is only right for the text the evaluator saw.
        getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent event) {
                getHighlighter().removeAllHighlights();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent event) {
                getHighlighter().removeAllHighlights();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent event) {
            }
        });
    }

    void setPlaceholder(String text) {
        if (!placeholder.equals(text)) {
            placeholder = text;
            repaint();
        }
    }

    String placeholder() {
        return placeholder;
    }

    /** Underlines {@code start} to {@code end} (excluded) of the text, or nothing when {@code start} is negative. */
    void markError(int start, int end) {
        getHighlighter().removeAllHighlights();
        if (start >= 0 && end > start && end <= getDocument().getLength()) {
            try {
                getHighlighter().addHighlight(start, end, UNDERLINE);
            } catch (BadLocationException exception) {
                // The mark is a convenience: without it the message still says what is wrong.
            }
        }
    }

    boolean hasErrorMark() {
        return getHighlighter().getHighlights().length > 0;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (placeholder.isEmpty() || !getText().isEmpty()) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(NotebookPalette.PLACEHOLDER);
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            int x = getInsets().left;
            int y = getInsets().top + (getHeight() - getInsets().top - getInsets().bottom - metrics.getHeight()) / 2
                    + metrics.getAscent();
            g.drawString(placeholder, x, y);
        } finally {
            g.dispose();
        }
    }

    private static final class LengthFilter extends DocumentFilter {
        private final int limit;

        LengthFilter(int limit) {
            this.limit = limit;
        }

        @Override
        public void insertString(FilterBypass bypass, int offset, String text, AttributeSet attributes) throws BadLocationException {
            replace(bypass, offset, 0, text, attributes);
        }

        @Override
        public void replace(FilterBypass bypass, int offset, int length, String text, AttributeSet attributes)
                throws BadLocationException {
            String accepted = text;
            if (accepted != null) {
                int room = limit - (bypass.getDocument().getLength() - length);
                if (accepted.length() > room) {
                    accepted = accepted.substring(0, Math.max(0, room));
                }
            }
            super.replace(bypass, offset, length, accepted, attributes);
        }
    }

    private static final class UnderlinePainter implements Highlighter.HighlightPainter {
        @Override
        public void paint(Graphics graphics, int start, int end, Shape bounds, JTextComponent component) {
            try {
                Rectangle from = component.modelToView2D(start).getBounds();
                Rectangle to = component.modelToView2D(end).getBounds();
                if (from.y != to.y) {
                    return;
                }
                Color previous = graphics.getColor();
                graphics.setColor(NotebookPalette.ERROR_UNDERLINE);
                int y = from.y + from.height - 2;
                graphics.fillRect(from.x, y, Math.max(2, to.x - from.x), 2);
                graphics.setColor(previous);
            } catch (BadLocationException exception) {
                // Nothing to draw.
            }
        }
    }
}
