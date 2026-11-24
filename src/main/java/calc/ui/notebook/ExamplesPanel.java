package calc.ui.notebook;

import java.awt.FlowLayout;
import java.awt.Font;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * The three example notebooks the empty notebook offers, under its watermark: one button per {@link NotebookExamples}, named
 * {@code exampleButton<NAME>}. The window shows the panel as a whole, never button by button, and only while no line is
 * written ({@link NotebookViewModel#examplesVisible()}). Plain buttons: Tab reaches them and Space or Enter presses them.
 */
final class ExamplesPanel extends JPanel {
    private static final long serialVersionUID = 1L;

    /** @param chosen what the window does with the example whose button was pressed */
    ExamplesPanel(Consumer<NotebookExamples> chosen) {
        setName("examplesPanel");
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        JLabel heading = new JLabel("Ou partir d'un exemple :");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        heading.setAlignmentX(LEFT_ALIGNMENT);
        add(heading);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.setOpaque(false);
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        buttons.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        for (NotebookExamples example : NotebookExamples.values()) {
            JButton button = new JButton(example.title());
            button.setName("exampleButton" + example.name());
            button.getAccessibleContext().setAccessibleDescription("Insère l'exemple « " + example.title() + " » : "
                    + example.lines().size() + " lignes");
            button.addActionListener(event -> chosen.accept(example));
            buttons.add(button);
            buttons.add(Box.createHorizontalStrut(8));
        }
        add(buttons);
    }
}
