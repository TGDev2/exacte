package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.widget.Contrast;
import calc.ui.widget.Palette;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every text colour of the notebook rows reaches 4,5:1 (WCAG AA) on each background it is drawn on; the ratios are printed. */
class NotebookPaletteContrastTest {
    @Test
    void everyTextColourOfTheRowsHasEnoughContrastOnItsBackgrounds() {
        Color grey = new Color(238, 238, 238);
        Map<String, Color[]> pairs = new LinkedHashMap<>();
        // name -> {foreground, background}
        pairs.put("message d'erreur sur une ligne", new Color[] {Palette.ERROR, NotebookPalette.ROW_BACKGROUND});
        pairs.put("message d'erreur sur la ligne active", new Color[] {Palette.ERROR, NotebookPalette.ACTIVE_ROW_BACKGROUND});
        pairs.put("numéro", new Color[] {NotebookPalette.NUMBER_FOREGROUND, NotebookPalette.ROW_BACKGROUND});
        pairs.put("numéro de la ligne active", new Color[] {NotebookPalette.NUMBER_FOREGROUND, NotebookPalette.ACTIVE_ROW_BACKGROUND});
        pairs.put("numéro d'une définition de variable",
                new Color[] {NotebookPalette.VARIABLE_FOREGROUND, NotebookPalette.VARIABLE_BACKGROUND});
        pairs.put("résultat", new Color[] {NotebookPalette.RESULT_FOREGROUND, NotebookPalette.ROW_BACKGROUND});
        pairs.put("résultat de la ligne active", new Color[] {NotebookPalette.RESULT_FOREGROUND, NotebookPalette.ACTIVE_ROW_BACKGROUND});
        pairs.put("filigrane de l'aide (champ blanc)", new Color[] {NotebookPalette.PLACEHOLDER, Color.WHITE});
        pairs.put("badge exact", new Color[] {Palette.EXACT_FOREGROUND, Palette.EXACT_BACKGROUND});
        pairs.put("badge approché", new Color[] {Palette.APPROXIMATE_FOREGROUND, Palette.APPROXIMATE_BACKGROUND});
        pairs.put("barre d'état, erreur (gris clair)", new Color[] {Palette.ERROR, grey});
        pairs.put("barre d'état, réussite (gris clair)", new Color[] {Palette.SUCCESS, grey});
        pairs.put("barre d'état, information (gris clair)", new Color[] {Palette.INFO, grey});

        StringBuilder report = new StringBuilder("Contrast ratios (minimum " + Contrast.MINIMUM_TEXT_RATIO + "):\n");
        StringBuilder failures = new StringBuilder();
        pairs.forEach((name, colours) -> {
            double ratio = Contrast.ratio(colours[0], colours[1]);
            report.append(String.format("  %-45s %.2f:1%n", name, ratio));
            if (ratio < Contrast.MINIMUM_TEXT_RATIO) {
                failures.append(name).append(String.format(" %.2f%n", ratio));
            }
        });
        System.out.print(report);
        assertTrue(failures.length() == 0, "below 4,5:1: " + failures);
    }
}
