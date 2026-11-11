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
 * Every text colour of the notebook rows reaches 4,5:1 (WCAG AA) on each background it is drawn on, in the light and in the
 * dark theme; the ratios are printed. The light theme is also checked on the system backgrounds it still meets (the white
 * line field, the light grey status bar).
 */
class NotebookPaletteContrastTest {
    @Test
    void theActivePaletteReadsTheTokensOfTheActiveTheme() {
        DesignTokens tokens = DesignTokens.load();
        assertEquals(tokens.color(Palette.activeTheme(), "surface.active"), NotebookPalette.active().activeRowBackground());
        assertEquals(tokens.color(Theme.DARK, "source.mark"), NotebookPalette.of(Theme.DARK).variableForeground());
    }

    @Test
    void everyTextColourOfTheRowsHasEnoughContrastOnItsBackgroundsInBothThemes() {
        Map<String, Color[]> pairs = new LinkedHashMap<>();
        // name -> {foreground, background}
        for (Theme theme : Theme.values()) {
            NotebookPalette rows = NotebookPalette.of(theme);
            Palette palette = Palette.of(theme);
            String name = theme.name().toLowerCase(Locale.ROOT) + ", ";
            pairs.put(name + "message d'erreur sur une ligne", new Color[] {palette.error(), rows.rowBackground()});
            pairs.put(name + "message d'erreur sur la ligne active", new Color[] {palette.error(), rows.activeRowBackground()});
            pairs.put(name + "numéro", new Color[] {rows.numberForeground(), rows.rowBackground()});
            pairs.put(name + "numéro de la ligne active", new Color[] {rows.numberForeground(), rows.activeRowBackground()});
            pairs.put(name + "numéro d'une définition de variable", new Color[] {rows.variableForeground(), rows.variableBackground()});
            pairs.put(name + "résultat", new Color[] {rows.resultForeground(), rows.rowBackground()});
            pairs.put(name + "résultat de la ligne active", new Color[] {rows.resultForeground(), rows.activeRowBackground()});
            pairs.put(name + "filigrane de l'aide", new Color[] {rows.placeholder(), rows.rowBackground()});
            pairs.put(name + "badge exact", new Color[] {palette.exactForeground(), palette.exactBackground()});
            pairs.put(name + "badge approché", new Color[] {palette.approximateForeground(), palette.approximateBackground()});
            pairs.put(name + "barre d'état, erreur", new Color[] {palette.error(), rows.rowBackground()});
            pairs.put(name + "barre d'état, réussite", new Color[] {palette.success(), rows.rowBackground()});
            pairs.put(name + "barre d'état, information", new Color[] {palette.info(), rows.rowBackground()});
            if (theme == Theme.LIGHT) {
                Color grey = new Color(238, 238, 238);
                pairs.put(name + "filigrane de l'aide (champ blanc)", new Color[] {rows.placeholder(), Color.WHITE});
                pairs.put(name + "barre d'état, erreur (gris clair)", new Color[] {palette.error(), grey});
                pairs.put(name + "barre d'état, réussite (gris clair)", new Color[] {palette.success(), grey});
                pairs.put(name + "barre d'état, information (gris clair)", new Color[] {palette.info(), grey});
            }
        }

        StringBuilder report = new StringBuilder("Contrast ratios (minimum " + Contrast.MINIMUM_TEXT_RATIO + "):\n");
        StringBuilder failures = new StringBuilder();
        pairs.forEach((name, colours) -> {
            double ratio = Contrast.ratio(colours[0], colours[1]);
            report.append(String.format("  %-52s %.2f:1%n", name, ratio));
            if (ratio < Contrast.MINIMUM_TEXT_RATIO) {
                failures.append(name).append(String.format(" %.2f%n", ratio));
            }
        });
        System.out.print(report);
        assertTrue(failures.length() == 0, "below 4,5:1: " + failures);
    }
}
