package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.widget.Palette;
import java.awt.Color;

/**
 * The colours of the notebook rows, read from the design tokens of one theme (ADR 0016). The rows draw on their own
 * backgrounds (the sheet, and the active row) whatever the look and feel, and every text colour reaches 4,5:1 on the
 * backgrounds it is drawn on, in both themes (NotebookPaletteContrastTest prints the ratios). The colour completes a text,
 * it never carries the information alone: an error is a sentence, a variable is named in the tooltip and in the accessible
 * description.
 */
final class NotebookPalette {
    private final Theme theme;

    private NotebookPalette(Theme theme) {
        this.theme = theme;
    }

    /** The palette of the active theme of the window. */
    static NotebookPalette active() {
        return of(Palette.activeTheme());
    }

    static NotebookPalette of(Theme theme) {
        return new NotebookPalette(theme);
    }

    Color rowBackground() {
        return role("surface.base");
    }

    Color activeRowBackground() {
        return role("surface.active");
    }

    Color numberForeground() {
        return role("text.secondary");
    }

    Color resultForeground() {
        return role("text.primary");
    }

    Color placeholder() {
        return role("text.muted");
    }

    /** The number cell of a line that defines a variable. */
    Color variableBackground() {
        return role("source.surface");
    }

    /** The text of the number cell of a line that defines a variable. */
    Color variableForeground() {
        return role("source.mark");
    }

    /** The line under the faulty part of an error (not text: the message below it says the same in words). */
    Color errorUnderline() {
        return role("error.underline");
    }

    /** The rule around a line field and above the status bar. */
    Color border() {
        return role("border.subtle");
    }

    private Color role(String role) {
        return DesignTokens.load().color(theme, role);
    }
}
