package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaletteContrastTest {
    // The panel backgrounds of the system look and feels: white, the Windows grey and a Nimbus-like grey.
    private static final Map<String, Color> PANELS = Map.of(
            "white", Color.WHITE,
            "grey 240", new Color(240, 240, 240),
            "grey 214/217/223", new Color(214, 217, 223));

    @Test
    void theRatioIsTheWcagOne() {
        assertEquals(21.0, Contrast.ratio(Color.BLACK, Color.WHITE), 1e-9);
        assertEquals(1.0, Contrast.ratio(Color.WHITE, Color.WHITE), 1e-9);
    }

    @Test
    void everyPaletteTextReachesTheMinimumRatioOnItsBackground() {
        Map<String, Double> ratios = new LinkedHashMap<>();
        ratios.put("exact badge", Contrast.ratio(Palette.EXACT_FOREGROUND, Palette.EXACT_BACKGROUND));
        ratios.put("approximate badge", Contrast.ratio(Palette.APPROXIMATE_FOREGROUND, Palette.APPROXIMATE_BACKGROUND));
        Map<String, Color> statuses = Map.of("success", Palette.SUCCESS, "error", Palette.ERROR, "info", Palette.INFO);
        for (Map.Entry<String, Color> status : statuses.entrySet()) {
            for (Map.Entry<String, Color> panel : PANELS.entrySet()) {
                ratios.put(status.getKey() + " on " + panel.getKey(), Contrast.ratio(status.getValue(), panel.getValue()));
            }
        }
        for (Map.Entry<String, Color> panel : PANELS.entrySet()) {
            ratios.put("exact mark on " + panel.getKey(), Contrast.ratio(Palette.EXACT_FOREGROUND, panel.getValue()));
            ratios.put("approximate mark on " + panel.getKey(), Contrast.ratio(Palette.APPROXIMATE_FOREGROUND, panel.getValue()));
        }
        ratios.forEach((name, ratio) -> System.out.printf("contrast %s = %.2f%n", name, ratio));
        ratios.forEach((name, ratio) -> assertTrue(
                ratio >= Contrast.MINIMUM_TEXT_RATIO, name + " has a ratio of " + ratio + ", under " + Contrast.MINIMUM_TEXT_RATIO));
    }
}
