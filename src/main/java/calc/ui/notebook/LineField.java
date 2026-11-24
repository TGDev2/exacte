package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.InputEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.TransferHandler;
import javax.swing.border.AbstractBorder;
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
 * character through so that the message can be given). A paste or a drop of a text with line breaks is handed whole to
 * the {@link MultiLinePaste} of the field, which makes lines of it; any other text goes in as before.
 */
final class LineField extends JTextField {
    private static final long serialVersionUID = 1L;
    private static final Highlighter.HighlightPainter UNDERLINE = new UnderlinePainter();

    private String placeholder = "";
    private transient MultiLinePaste multiLinePaste;

    /** Where a text of several lines goes, with the selection it replaces in the text of the field. */
    @FunctionalInterface
    interface MultiLinePaste {
        void paste(int selectionStart, int selectionEnd, String text);
    }

    LineField() {
        // The field lies on the surface of its row (active, under the pointer, in error): only the focus draws an outline.
        setOpaque(false);
        setBorder(new FocusOutline());
        addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                repaint();
            }
        });
        ((AbstractDocument) getDocument()).setDocumentFilter(new LengthFilter(NotebookPresenter.MAX_LINE_LENGTH + 1));
        // Not a UIResource: a change of look and feel keeps it, around the handler the look and feel gave first.
        setTransferHandler(new LinesTransferHandler(getTransferHandler()));
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

    /** Hands every paste or drop of a text with line breaks to {@code paste}; {@code null} lets the field take it. */
    void setMultiLinePaste(MultiLinePaste paste) {
        multiLinePaste = paste;
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
            g.setColor(NotebookPalette.active().placeholder());
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

    /**
     * The handler of the look and feel, except for a text with line breaks pasted or dropped while a {@link MultiLinePaste}
     * listens: the field would make spaces of the breaks, and a long text would be cut by its {@link LengthFilter}.
     */
    private final class LinesTransferHandler extends TransferHandler {
        private static final long serialVersionUID = 1L;

        private final TransferHandler standard;

        LinesTransferHandler(TransferHandler standard) {
            this.standard = standard;
        }

        @Override
        public boolean canImport(TransferSupport support) {
            return standard.canImport(support);
        }

        @Override
        public boolean importData(TransferSupport support) {
            MultiLinePaste paste = multiLinePaste;
            String text = paste == null || !isEditable() || !isEnabled() ? null : text(support);
            if (text == null || text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
                return standard.importData(support);
            }
            int start = getSelectionStart();
            int end = getSelectionEnd();
            if (support.isDrop() && support.getDropLocation() instanceof JTextComponent.DropLocation drop) {
                start = drop.getIndex();
                end = start;
            }
            paste.paste(start, end, text);
            return true;
        }

        private static String text(TransferSupport support) {
            if (!support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                return null;
            }
            try {
                return (String) support.getTransferable().getTransferData(DataFlavor.stringFlavor);
            } catch (UnsupportedFlavorException | IOException unreadable) {
                return null;
            }
        }

        @Override
        public int getSourceActions(JComponent component) {
            return standard.getSourceActions(component);
        }

        @Override
        public Icon getVisualRepresentation(java.awt.datatransfer.Transferable transferable) {
            return standard.getVisualRepresentation(transferable);
        }

        @Override
        public void exportAsDrag(JComponent component, InputEvent event, int action) {
            standard.exportAsDrag(component, event, action);
        }

        @Override
        public void exportToClipboard(JComponent component, Clipboard clipboard, int action) {
            standard.exportToClipboard(component, clipboard, action);
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

    /**
     * Nothing around a field without the focus; around the field that has it, a 2 px outline of border.focus with the small
     * radius of the tokens, so that the keyboard focus stays visible on every surface of a row (contrast.ui.focus*).
     */
    private static final class FocusOutline extends AbstractBorder {
        private static final long serialVersionUID = 1L;
        private static final int WIDTH = 2;

        @Override
        public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
            if (!component.hasFocus()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(NotebookPalette.active().focusBorder());
                g.setStroke(new BasicStroke(WIDTH));
                float arc = 2f * DesignTokens.load().size("radius.small");
                g.draw(new RoundRectangle2D.Float(x + WIDTH / 2f, y + WIDTH / 2f, width - WIDTH, height - WIDTH, arc, arc));
            } finally {
                g.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component component, Insets insets) {
            int horizontal = UIScale.scale(DesignTokens.load().size("space.2"));
            insets.set(WIDTH + 1, horizontal, WIDTH + 1, horizontal);
            return insets;
        }

        @Override
        public Insets getBorderInsets(Component component) {
            return getBorderInsets(component, new Insets(0, 0, 0, 0));
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
                graphics.setColor(NotebookPalette.active().errorUnderline());
                int y = from.y + from.height - 2;
                graphics.fillRect(from.x, y, Math.max(2, to.x - from.x), 2);
                graphics.setColor(previous);
            } catch (BadLocationException exception) {
                // Nothing to draw.
            }
        }
    }
}
