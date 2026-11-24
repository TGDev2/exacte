package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.widget.Palette;
import java.awt.Color;

/**
 * The colours of the notebook rows, read from the design tokens of one theme (ADR 0016, docs/design/DESIGN.md « Ligne du
 * carnet »). The rows draw on their own surfaces (the sheet, the active row, the row under the pointer, a row in error)
 * whatever the look and feel, and every text colour reaches 4,5:1 on the surfaces it is drawn on, in both themes (the
 * contrast.text.* pairs of tokens.properties, NotebookPaletteContrastTest). The colour completes a text, it never carries the
 * information alone: the sign of the margin is a character, an error is a sentence, a name is written out.
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

    /** The row under the pointer. */
    Color hoverRowBackground() {
        return role("surface.hover");
    }

    /** A row in error that is neither active nor under the pointer. */
    Color errorRowBackground() {
        return role("error.surface");
    }

    /** The number of a line. */
    Color numberForeground() {
        return role("text.muted");
    }

    /** The number of the active line. */
    Color activeNumberForeground() {
        return role("text.secondary");
    }

    /** The name of an assignment, left of its result. */
    Color nameForeground() {
        return role("text.secondary");
    }

    /** The box of the detail of the active line. */
    Color detailBackground() {
        return role("surface.raised");
    }

    /** The sentences of the detail of the active line. */
    Color detailForeground() {
        return role("text.secondary");
    }

    /** What is typed, a title, an exact result. */
    Color textForeground() {
        return role("text.primary");
    }

    /** A whole-line comment that is not a title. */
    Color commentForeground() {
        return role("text.muted");
    }

    /** The sentence of a line that waits for its calculation. */
    Color pendingForeground() {
        return role("text.muted");
    }

    /** The preview of the line being typed, in the place of its result: muted, since nothing is validated. */
    Color previewForeground() {
        return role("text.muted");
    }

    Color placeholder() {
        return role("text.muted");
    }

    /** The sign « = ». */
    Color exactSign() {
        return role("exact.sign");
    }

    /** The sign « ≈ » and the result of an approximate value. */
    Color approximateSign() {
        return role("approximate.sign");
    }

    /** The sign « ! » and the message of a line in error. */
    Color errorText() {
        return role("error.text");
    }

    /** The mark « ◆ » of a source of the active line. */
    Color sourceMark() {
        return role("source.mark");
    }

    /** The line under the faulty part of an error, and the rule of the margin of a row in error (not text). */
    Color errorUnderline() {
        return role("error.underline");
    }

    /** The rule of the margin, between the rows, and above the status bar. */
    Color border() {
        return role("border.subtle");
    }

    /** The outline of the field that has the keyboard focus. */
    Color focusBorder() {
        return role("border.focus");
    }

    private Color role(String role) {
        return DesignTokens.load().color(theme, role);
    }
}
