package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import calc.ui.widget.Contrast;
import calc.ui.widget.Palette;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Every text colour of the notebook rows reaches 4,5:1 (WCAG AA) on each surface it is drawn on (the sheet, the active row,
 * the row under the pointer, a row in error), in the light and in the dark theme; the ratios are printed. The light theme
 * is also checked on the light grey of a system status bar.
 */
class NotebookPaletteContrastTest {
    @Test
    void theActivePaletteReadsTheTokensOfTheActiveTheme() {
        DesignTokens tokens = DesignTokens.load();
        assertEquals(tokens.color(Palette.activeTheme(), "surface.active"), NotebookPalette.active().activeRowBackground());
        assertEquals(tokens.color(Theme.DARK, "surface.hover"), NotebookPalette.of(Theme.DARK).hoverRowBackground());
        assertEquals(tokens.color(Theme.DARK, "error.surface"), NotebookPalette.of(Theme.DARK).errorRowBackground());
        assertEquals(tokens.color(Theme.DARK, "exact.sign"), NotebookPalette.of(Theme.DARK).exactSign());
        assertEquals(tokens.color(Theme.DARK, "approximate.sign"), NotebookPalette.of(Theme.DARK).approximateSign());
        assertEquals(tokens.color(Theme.DARK, "source.mark"), NotebookPalette.of(Theme.DARK).sourceMark());
        assertEquals(tokens.color(Theme.LIGHT, "border.focus"), NotebookPalette.of(Theme.LIGHT).focusBorder());
    }

    @Test
    void everyTextColourOfTheRowsHasEnoughContrastOnItsBackgroundsInBothThemes() {
        Map<String, Color[]> pairs = new LinkedHashMap<>();
        // name -> {foreground, background}
        for (Theme theme : Theme.values()) {
            NotebookPalette rows = NotebookPalette.of(theme);
            Palette palette = Palette.of(theme);
            String name = theme.name().toLowerCase(Locale.ROOT) + ", ";
            Map<String, Color> surfaces = new LinkedHashMap<>();
            surfaces.put("une ligne", rows.rowBackground());
            surfaces.put("la ligne active", rows.activeRowBackground());
            surfaces.put("la ligne survolée", rows.hoverRowBackground());
            Map<String, Color> texts = new LinkedHashMap<>();
            texts.put("numéro", rows.numberForeground());
            texts.put("nom d'affectation", rows.nameForeground());
            texts.put("expression et résultat", rows.textForeground());
            texts.put("commentaire", rows.commentForeground());
            texts.put("calcul en cours", rows.pendingForeground());
            texts.put("filigrane de l'aide", rows.placeholder());
            texts.put("signe =", rows.exactSign());
            texts.put("signe ≈ et résultat approché", rows.approximateSign());
            texts.put("signe ! et message d'erreur", rows.errorText());
            texts.forEach((text, colour) -> surfaces.forEach((surface, under) ->
                    pairs.put(name + text + " sur " + surface, new Color[] {colour, under})));
            pairs.put(name + "numéro de la ligne active", new Color[] {rows.activeNumberForeground(), rows.activeRowBackground()});
            pairs.put(name + "marque de source", new Color[] {rows.sourceMark(), rows.rowBackground()});
            Map<String, Color> onError = new LinkedHashMap<>();
            onError.put("numéro", rows.numberForeground());
            onError.put("nom d'affectation", rows.nameForeground());
            onError.put("expression", rows.textForeground());
            onError.put("signe ! et message d'erreur", rows.errorText());
            onError.forEach((text, colour) ->
                    pairs.put(name + text + " sur une ligne en erreur", new Color[] {colour, rows.errorRowBackground()}));
            pairs.put(name + "barre d'état, erreur", new Color[] {palette.error(), rows.rowBackground()});
            pairs.put(name + "barre d'état, réussite", new Color[] {palette.success(), rows.rowBackground()});
            pairs.put(name + "barre d'état, information", new Color[] {palette.info(), rows.rowBackground()});
            if (theme == Theme.LIGHT) {
                Color grey = new Color(238, 238, 238);
                pairs.put(name + "barre d'état, erreur (gris clair)", new Color[] {palette.error(), grey});
                pairs.put(name + "barre d'état, réussite (gris clair)", new Color[] {palette.success(), grey});
                pairs.put(name + "barre d'état, information (gris clair)", new Color[] {palette.info(), grey});
            }
        }

        StringBuilder report = new StringBuilder("Contrast ratios (minimum " + Contrast.MINIMUM_TEXT_RATIO + "):\n");
        StringBuilder failures = new StringBuilder();
        pairs.forEach((name, colours) -> {
            double ratio = Contrast.ratio(colours[0], colours[1]);
            report.append(String.format("  %-60s %.2f:1%n", name, ratio));
            if (ratio < Contrast.MINIMUM_TEXT_RATIO) {
                failures.append(name).append(String.format(" %.2f%n", ratio));
            }
        });
        System.out.print(report);
        assertTrue(failures.length() == 0, "below 4,5:1: " + failures);
    }
}
