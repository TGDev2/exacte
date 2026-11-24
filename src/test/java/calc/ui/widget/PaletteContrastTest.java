package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.design.DesignTokens;
import calc.ui.design.Theme;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaletteContrastTest {
    // The panel backgrounds of the system look and feels, on which the light theme still draws its status bar: white, the
    // Windows grey and a Nimbus-like grey.
    private static final Map<String, Color> SYSTEM_PANELS = Map.of(
            "white", Color.WHITE,
            "grey 240", new Color(240, 240, 240),
            "grey 214/217/223", new Color(214, 217, 223));

    @Test
    void theRatioIsTheWcagOne() {
        assertEquals(21.0, Contrast.ratio(Color.BLACK, Color.WHITE), 1e-9);
        assertEquals(1.0, Contrast.ratio(Color.WHITE, Color.WHITE), 1e-9);
    }

    @Test
    void theActivePaletteIsTheLightOne() {
        assertEquals(Theme.LIGHT, Palette.activeTheme());
        assertEquals(Theme.LIGHT, Palette.active().theme());
        DesignTokens tokens = DesignTokens.load();
        assertEquals(tokens.color(Theme.LIGHT, "error.text"), Palette.active().error());
        assertEquals(tokens.color(Theme.DARK, "error.text"), Palette.of(Theme.DARK).error());
    }

    @Test
    void everyPaletteTextReachesTheMinimumRatioOnItsBackgroundInBothThemes() {
        Map<String, Double> ratios = new LinkedHashMap<>();
        for (Theme theme : Theme.values()) {
            Palette palette = Palette.of(theme);
            Map<String, Color> backgrounds = new LinkedHashMap<>();
            backgrounds.put("sheet", DesignTokens.load().color(theme, "surface.base"));
            backgrounds.put("raised panel", DesignTokens.load().color(theme, "surface.raised"));
            if (theme == Theme.LIGHT) {
                backgrounds.putAll(SYSTEM_PANELS);
            }
            String name = theme.name().toLowerCase(java.util.Locale.ROOT) + ": ";
            Map<String, Color> foregrounds = new LinkedHashMap<>();
            foregrounds.put("success", palette.success());
            foregrounds.put("error", palette.error());
            foregrounds.put("info", palette.info());
            foregrounds.put("exact sign", palette.exactSign());
            foregrounds.put("approximate sign", palette.approximateSign());
            foregrounds.forEach((foreground, colour) -> backgrounds.forEach((background, under) ->
                    ratios.put(name + foreground + " on " + background, Contrast.ratio(colour, under))));
        }
        ratios.forEach((name, ratio) -> System.out.printf("contrast %s = %.2f%n", name, ratio));
        ratios.forEach((name, ratio) -> assertTrue(
                ratio >= Contrast.MINIMUM_TEXT_RATIO, name + " has a ratio of " + ratio + ", under " + Contrast.MINIMUM_TEXT_RATIO));
    }
}
