package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import calc.ui.notebook.NotebookRow.Kind;
import calc.ui.notebook.NotebookRow.Sign;
import calc.ui.notebook.NotebookRow.Status;
import com.formdev.flatlaf.util.UIScale;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.LineMetrics;
import java.util.Locale;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * One row of the notebook (docs/design/DESIGN.md « Ligne du carnet »): a margin of fixed width with the number, the sign of
 * the exactness and the mark of a source; the expression; the name of an assignment; the result, aligned on the column of
 * the decimal commas shared by every row; below the line, the message of a line in error. It only draws the
 * {@link NotebookRow} it is given; it holds no logic. Nothing in it is ever hidden or shown line by line (each hide or show
 * makes AWT ask the system which window is under the mouse): an empty state empties a text.
 */
final class NotebookRowView extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final int RESULT_WIDTH = 330;
    /**
     * Room right of the column of the commas, in characters: the comma, 12 decimals and « … » (0,333333333333…), and 2 more
     * so that a rounding of the advances never pushes such a result left of the column. A longer one (« × 10ⁿ ») moves left.
     */
    private static final int DECIMAL_CHARACTERS = 16;

    final int index;
    final LineField field = new LineField();
    final JLabel numberLabel = new JLabel("", SwingConstants.RIGHT);
    final JLabel exactnessSign = new JLabel("", SwingConstants.CENTER);
    final JLabel sourceMark = new JLabel("", SwingConstants.CENTER);
    final JLabel nameLabel = new JLabel("", SwingConstants.RIGHT);
    final AlignedResult resultLabel = new AlignedResult();
    final JLabel errorLabel = new JLabel("");

    private final Margin margin;
    private final Font expressionFont;
    private final Font commentFont;
    private final Font titleFont;
    private transient NotebookRow shownRow;
    private boolean shownActive;
    private boolean hovered;

    NotebookRowView(int index) {
        super(new GridBagLayout());
        this.index = index;
        setName("row");
        setOpaque(true);

        // Two voices (DESIGN.md, Typographie): JetBrains Mono for what is typed and what the notebook answers, Inter for the
        // rest; the sizes of the tokens follow the scale of the system fonts.
        DesignTokens tokens = DesignTokens.load();
        String mono = tokens.fontFamily("mono");
        String ui = tokens.fontFamily("ui");
        expressionFont = new Font(mono, Font.PLAIN, scaled("font.size.body"));
        commentFont = new Font(ui, Font.PLAIN, scaled("font.size.body"));
        titleFont = new Font(ui, Font.BOLD, scaled("font.size.title"));
        Font caption = new Font(ui, Font.PLAIN, scaled("font.size.caption"));
        Font result = new Font(mono, Font.PLAIN, scaled("font.size.result"));

        int line = scaled("space.8");
        int space1 = scaled("space.1");
        int space2 = scaled("space.2");
        int space4 = scaled("space.4");

        field.setName("lineField");
        field.setFont(expressionFont);
        field.setPreferredSize(new Dimension(field.getPreferredSize().width, line));
        field.setMinimumSize(new Dimension(field.getMinimumSize().width, line));

        numberLabel.setName("numberLabel");
        numberLabel.setFont(caption);
        numberLabel.getAccessibleContext().setAccessibleName("Numéro");
        exactnessSign.setName("exactnessSign");
        exactnessSign.setFont(result);
        sourceMark.setName("sourceMark");
        sourceMark.setFont(caption);
        sourceMark.getAccessibleContext().setAccessibleName("Source de la ligne active");
        int numberWidth = numberLabel.getFontMetrics(caption.deriveFont(Font.BOLD)).stringWidth("5000");
        int signWidth = exactnessSign.getFontMetrics(result).charWidth('≈') + space1;
        margin = new Margin(numberWidth, signWidth, space1, space2, line);

        nameLabel.setName("nameLabel");
        nameLabel.setFont(caption);
        resultLabel.setName("resultLabel");
        resultLabel.setFont(result);
        resultLabel.setPreferredSize(new Dimension(RESULT_WIDTH, line));
        resultLabel.setMinimumSize(new Dimension(RESULT_WIDTH / 2, line));
        errorLabel.setName("errorLabel");
        errorLabel.setFont(caption);

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.gridheight = 2;
        c.anchor = GridBagConstraints.NORTH;
        c.fill = GridBagConstraints.VERTICAL;
        add(margin, c);
        c.gridheight = 1;
        c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.CENTER;
        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(space1, space4, space1, space2);
        add(field, c);
        c.gridx = 2;
        c.weightx = 0;
        c.fill = GridBagConstraints.NONE;
        c.insets = new Insets(space1, 0, space1, space2);
        add(nameLabel, c);
        c.gridx = 3;
        c.insets = new Insets(space1, 0, space1, space4);
        add(resultLabel, c);
        // The message lies under the line, from the start of the expression.
        c.gridx = 1;
        c.gridy = 1;
        c.gridwidth = 3;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, space4 + field.getInsets().left, 0, space4);
        add(errorLabel, c);

        // The pointer: the surface of the row under it, and a click outside the field puts the caret in the field. Every part
        // hears it, since a part with listeners of its own (a tooltip, the field) takes the events of the mouse from the row.
        MouseAdapter pointer = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hover(true);
            }

            @Override
            public void mouseExited(MouseEvent event) {
                Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), NotebookRowView.this);
                if (!contains(point)) {
                    hover(false);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (event.getComponent() != field) {
                    field.requestFocusInWindow();
                }
            }
        };
        for (Component part : new Component[] {this, margin, numberLabel, exactnessSign, sourceMark, field, nameLabel,
                resultLabel, errorLabel}) {
            part.addMouseListener(pointer);
        }
        colour();
    }

    private static int scaled(String token) {
        return UIScale.scale(DesignTokens.load().size(token));
    }

    /** A change of theme: the row takes the colours of the new theme, for the line it shows. */
    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of JPanel before the parts exist.
        if (field != null) {
            colour();
            if (shownRow != null) {
                showRow(shownRow);
                restyle();
            }
        }
    }

    // The colours that do not depend on the line, from the theme in force.
    private void colour() {
        NotebookPalette palette = NotebookPalette.active();
        nameLabel.setForeground(palette.nameForeground());
        sourceMark.setForeground(palette.sourceMark());
        errorLabel.setForeground(palette.errorText());
        restyle();
    }

    /** A height on the grid of 4 px, the message of an error included. */
    @Override
    public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        int grid = UIScale.scale(DesignTokens.load().size("space.1"));
        return new Dimension(size.width, (size.height + grid - 1) / grid * grid);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    // The rule between the rows.
    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        graphics.setColor(NotebookPalette.active().border());
        graphics.fillRect(0, getHeight() - 1, getWidth(), 1);
    }

    /**
     * The abscissa, in the coordinates of the panel of the rows, of the column of the decimal commas: the comma of the result,
     * or the end of its integer part when it has none. It is the same for every row.
     */
    int decimalAnchorX() {
        return getX() + resultLabel.getX() + resultLabel.anchor();
    }

    /**
     * Shows {@code row}. {@code text} is what the field must hold (the draft for the active row); a field that already
     * holds it is left alone, so that the caret does not move.
     */
    void show(NotebookRow row, boolean active, String text, String watermark) {
        boolean rowChanged = !row.equals(shownRow);
        boolean activeChanged = active != shownActive;
        shownRow = row;
        shownActive = active;
        if (rowChanged) {
            showRow(row);
        }
        if (rowChanged || activeChanged) {
            restyle();
        }
        boolean textChanged = !field.getText().equals(text);
        if (textChanged) {
            field.setText(text);
        }
        field.setPlaceholder(watermark);
        boolean markLost = row.status() == Status.ERROR && !field.hasErrorMark() && text.equals(row.text());
        if (rowChanged || textChanged || markLost) {
            // The mark is only right for the text the evaluator saw.
            field.markError(row.status() == Status.ERROR && text.equals(row.text()) ? row.errorStart() : -1, row.errorEnd());
        }
    }

    private void showRow(NotebookRow row) {
        NotebookPalette palette = NotebookPalette.active();
        numberLabel.setText(String.valueOf(row.number()));

        Sign sign = row.sign();
        exactnessSign.setText(sign.text());
        exactnessSign.setForeground(switch (sign) {
            case EXACT -> palette.exactSign();
            case APPROXIMATE -> palette.approximateSign();
            case ERROR -> palette.errorText();
            case NONE -> palette.numberForeground();
        });
        exactnessSign.getAccessibleContext().setAccessibleName(sign.spoken());

        Kind kind = row.kind();
        field.getAccessibleContext().setAccessibleName(row.name());
        field.getAccessibleContext().setAccessibleDescription(row.accessibleDescription());
        field.setFont(kind == Kind.TITLE ? titleFont : kind == Kind.COMMENT ? commentFont : expressionFont);
        field.setForeground(kind == Kind.COMMENT ? palette.commentForeground() : palette.textForeground());

        nameLabel.setText(row.definedName());

        resultLabel.show(row.result(), row.resultIntegerPart());
        resultLabel.setForeground(switch (row.status()) {
            case APPROXIMATE -> palette.approximateSign();
            case PENDING -> palette.pendingForeground();
            default -> palette.textForeground();
        });
        String tooltip = row.detail().isEmpty() ? row.result() : row.result() + " — " + row.detail();
        resultLabel.setToolTipText(row.result().isEmpty() ? null : tooltip);
        resultLabel.getAccessibleContext().setAccessibleName("Résultat de la " + row.name().toLowerCase(Locale.ROOT));
        resultLabel.getAccessibleContext().setAccessibleDescription(row.accessibleDescription());

        boolean error = row.status() == Status.ERROR;
        // A line left by an interruption is not wrong, it is not calculated: its sentence says so by itself.
        boolean interrupted = NotebookPresenter.INTERRUPTED_LINE.equals(row.errorMessage());
        errorLabel.setText(!error ? "" : interrupted ? row.errorMessage() : "Erreur : " + row.errorMessage());
        errorLabel.setToolTipText(error ? row.errorMessage() : null);
        errorLabel.getAccessibleContext().setAccessibleName("Erreur de la " + row.name().toLowerCase(Locale.ROOT));
        margin.rule(error ? palette.errorUnderline() : palette.border());
    }

    private void hover(boolean on) {
        if (hovered != on) {
            hovered = on;
            restyle();
        }
    }

    // The surface of the row and the number: active, then under the pointer, then in error, else the sheet.
    private void restyle() {
        NotebookPalette palette = NotebookPalette.active();
        boolean error = shownRow != null && shownRow.status() == Status.ERROR;
        setBackground(shownActive ? palette.activeRowBackground()
                : hovered ? palette.hoverRowBackground()
                : error ? palette.errorRowBackground()
                : palette.rowBackground());
        numberLabel.setForeground(shownActive ? palette.activeNumberForeground() : palette.numberForeground());
    }

    /** The margin: number, sign and source mark on the line of the field, and its vertical rule down the whole row. */
    private final class Margin extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int numberWidth;
        private final int signWidth;
        private final int gap;
        private final int padding;
        private final int line;
        private Color rule;

        Margin(int numberWidth, int signWidth, int gap, int padding, int line) {
            super(null);
            this.numberWidth = numberWidth;
            this.signWidth = signWidth;
            this.gap = gap;
            this.padding = padding;
            this.line = line + 2 * gap;
            setOpaque(false);
            add(numberLabel);
            add(exactnessSign);
            add(sourceMark);
            Dimension size = new Dimension(padding + numberWidth + gap + signWidth + gap + signWidth + padding, this.line);
            setPreferredSize(size);
            setMinimumSize(size);
        }

        void rule(Color colour) {
            if (!colour.equals(rule)) {
                rule = colour;
                repaint();
            }
        }

        @Override
        public void doLayout() {
            int x = padding;
            numberLabel.setBounds(x, 0, numberWidth, line);
            x += numberWidth + gap;
            exactnessSign.setBounds(x, 0, signWidth, line);
            x += signWidth + gap;
            sourceMark.setBounds(x, 0, signWidth, line);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (rule != null) {
                graphics.setColor(rule);
                graphics.fillRect(getWidth() - 1, 0, 1, getHeight());
            }
        }
    }

    /**
     * The result, its integer part right-aligned on the column of the commas and the rest after it, so that the units of
     * every row fall in the same column. The text is the one formatted by ValueFormatter, never formatted again: only cut on
     * its first comma. A text too wide for the room is moved left, then cut with « … » (the tooltip keeps it whole).
     */
    static final class AlignedResult extends JLabel {
        private static final long serialVersionUID = 1L;
        private static final FontRenderContext UNSCALED = new FontRenderContext(null, true, false);
        private String integerPart = "";

        AlignedResult() {
            super("", SwingConstants.RIGHT);
        }

        void show(String text, String integer) {
            integerPart = integer;
            setText(text);
            repaint();
        }

        /**
         * The abscissa of the column of the commas, in the coordinates of this label: the same for every row, since it only
         * depends on the width of the label and on its font.
         */
        int anchor() {
            double room = DECIMAL_CHARACTERS * advance(getFont().createGlyphVector(UNSCALED, "0"));
            return (int) Math.max(getInsets().left, Math.round(getWidth() - getInsets().right - room));
        }

        // How far a laid out text goes: the position after its last glyph.
        private static double advance(GlyphVector glyphs) {
            return glyphs.getGlyphPosition(glyphs.getNumGlyphs()).getX();
        }

        /**
         * Draws the result. Every width comes from glyphs laid out by the font itself in the context of the Graphics, and those
         * glyphs are the ones drawn. A FontMetrics would not do: Java keeps the metrics of a font by its name, style and size,
         * so that metrics asked before the fonts were installed (a window opened earlier) stay those of the fallback font, and
         * a result measured with them ended up to 1 px per character right of the column.
         */
        @Override
        protected void paintComponent(Graphics graphics) {
            String text = getText();
            if (text == null || text.isEmpty()) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                if (Toolkit.getDefaultToolkit().getDesktopProperty("awt.font.desktophints") instanceof Map<?, ?> hints) {
                    g.addRenderingHints(hints);
                }
                Font font = getFont();
                g.setFont(font);
                g.setColor(getForeground());
                FontRenderContext context = g.getFontRenderContext();
                Insets insets = getInsets();
                double left = insets.left;
                double right = getWidth() - insets.right;
                GlyphVector glyphs = font.createGlyphVector(context, text);
                double x = anchor() - advance(font.createGlyphVector(context, integerPart));
                if (x + advance(glyphs) > right) {
                    x = right - advance(glyphs);
                }
                String shown = text;
                if (x < left) {
                    // Too wide for the whole room: cut at the end, with « … » (the tooltip keeps it whole).
                    x = left;
                    while (!shown.isEmpty() && advance(glyphs = font.createGlyphVector(context, shown + "…")) > right - left) {
                        shown = shown.substring(0, shown.length() - 1);
                    }
                    shown += "…";
                }
                LineMetrics line = font.getLineMetrics(shown, context);
                float y = insets.top + (getHeight() - insets.top - insets.bottom - line.getAscent() - line.getDescent()) / 2
                        + line.getAscent();
                if (font.canDisplayUpTo(shown) < 0) {
                    g.drawGlyphVector(glyphs, (float) x, y);
                } else {
                    // A character the font lacks: only the Graphics finds it in another font.
                    g.drawString(shown, (float) x, y);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
