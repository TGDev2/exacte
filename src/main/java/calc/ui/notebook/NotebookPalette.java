package calc.ui.notebook;

import java.awt.Color;

/**
 * The colours of the notebook rows. The rows draw on their own backgrounds (white, and a light blue for the active row)
 * whatever the look and feel, and every text colour reaches 4,5:1 on the backgrounds it is drawn on
 * (NotebookPaletteContrastTest prints the ratios). The colour completes a text, it never carries the information alone:
 * an error is a sentence, a variable is named in the tooltip and in the accessible description.
 */
final class NotebookPalette {
    static final Color ROW_BACKGROUND = Color.WHITE;
    static final Color ACTIVE_ROW_BACKGROUND = new Color(228, 238, 252);
    static final Color NUMBER_FOREGROUND = new Color(80, 80, 80);
    static final Color RESULT_FOREGROUND = new Color(20, 20, 20);
    static final Color PLACEHOLDER = new Color(96, 96, 96);
    /** The number cell of a line that defines a variable, and its text. */
    static final Color VARIABLE_BACKGROUND = new Color(236, 228, 250);
    static final Color VARIABLE_FOREGROUND = new Color(60, 30, 110);
    /** The line under the faulty part of an error (not text: the message below it says the same in words). */
    static final Color ERROR_UNDERLINE = new Color(163, 44, 44);

    private NotebookPalette() {
    }
}
