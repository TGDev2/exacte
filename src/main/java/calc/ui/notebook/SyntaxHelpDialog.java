package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Rectangle;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.ScrollPaneConstants;

/**
 * The help « Syntaxe des expressions »: the summary of the syntax, then the examples of {@link NotebookHelp#INSERTABLE}, each
 * with a button « Insérer » that hands its lines to the window. It is a window of its own that does not block the notebook
 * (it stays open while lines are inserted, and the user may go on typing in the notebook); the buttons are named
 * {@code insertExampleButton<n>} from 0.
 */
final class SyntaxHelpDialog extends JDialog {
    private static final long serialVersionUID = 1L;
    static final String NAME = "syntaxHelp";

    /**
     * @param owner  the window of the notebook
     * @param insert what an « Insérer » button does with the lines of its example
     */
    SyntaxHelpDialog(Frame owner, Consumer<List<String>> insert) {
        super(owner, "Syntaxe des expressions", false);
        setName(NAME);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        Font mono = new Font(DesignTokens.load().fontFamily("mono"), Font.PLAIN, 13);
        JPanel content = new TrackingPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));

        JTextArea summary = text(NotebookHelp.syntax(), mono);
        summary.getAccessibleContext().setAccessibleName("Syntaxe des expressions");
        content.add(summary);

        JLabel heading = new JLabel("Exemples à insérer");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(14, 0, 4, 0));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        content.add(heading);
        JLabel hint = new JLabel("Insérer ajoute les lignes de l'exemple sous la ligne courante ; Annuler les retire d'un coup.");
        hint.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        hint.setAlignmentX(LEFT_ALIGNMENT);
        content.add(hint);

        List<NotebookHelp.Insertable> examples = NotebookHelp.INSERTABLE;
        for (int index = 0; index < examples.size(); index++) {
            content.add(example(index, examples.get(index), mono, insert));
        }

        JScrollPane scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.setPreferredSize(new Dimension(920, 560));
        getContentPane().add(scroll, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(owner);
    }

    /**
     * A closed help is no longer the help window: the JDK keeps a disposed window in its list of windows until it is collected,
     * so the name goes with the window, and a search by name only finds the help that is open.
     */
    @Override
    public void dispose() {
        super.dispose();
        setName(null);
    }

    // One example: its title, its lines, and the button that inserts them.
    private static JPanel example(int index, NotebookHelp.Insertable example, Font mono, Consumer<List<String>> insert) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        row.setAlignmentX(LEFT_ALIGNMENT);

        JLabel title = new JLabel(example.title());
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        JTextArea lines = text(String.join("\n", example.lines()), mono);
        lines.getAccessibleContext().setAccessibleName("Lignes de l'exemple « " + example.title() + " »");
        JPanel body = new JPanel(new BorderLayout());
        body.add(title, BorderLayout.NORTH);
        body.add(lines, BorderLayout.CENTER);
        row.add(body, BorderLayout.CENTER);

        JButton button = new JButton("Insérer");
        button.setName("insertExampleButton" + index);
        button.getAccessibleContext().setAccessibleName("Insérer l'exemple « " + example.title() + " »");
        button.addActionListener(event -> insert.accept(example.lines()));
        JPanel side = new JPanel(new BorderLayout());
        side.add(button, BorderLayout.NORTH);
        row.add(side, BorderLayout.EAST);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    private static JTextArea text(String text, Font font) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(font);
        area.setOpaque(false);
        area.setColumns(60);
        area.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    // The content follows the width of the window, so that the texts wrap instead of widening it.
    private static final class TrackingPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 24;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(24, visible.height - 24);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
