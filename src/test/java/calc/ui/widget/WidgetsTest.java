package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The reusable components, alone: none of them needs the calculator window. */
class WidgetsTest {
    @Test
    void theSelectorListsEveryModeWithItsFrenchName() {
        DisplayModeSelector selector = new DisplayModeSelector();
        assertEquals(DisplayMode.AUTO, selector.mode());
        assertEquals(
                List.of("Auto", "Décimal", "Fraction", "Scientifique"),
                java.util.Arrays.stream(DisplayMode.values()).map(DisplayModeText::label).toList());
        assertEquals("Mode d'affichage", selector.getAccessibleContext().getAccessibleName());
        assertNotNull(selector.getAccessibleContext().getAccessibleDescription());
    }

    @Test
    void theSelectorNotifiesTheUserChoiceButNotTheModeItIsToldToShow() {
        DisplayModeSelector selector = new DisplayModeSelector();
        List<DisplayMode> chosen = new ArrayList<>();
        selector.onModeChosen(chosen::add);

        selector.showMode(DisplayMode.FRACTION);
        assertEquals(DisplayMode.FRACTION, selector.mode());
        assertEquals(List.of(), chosen, "Showing a mode is not a choice.");

        selector.setSelectedItem(DisplayMode.SCIENTIFIC);
        assertEquals(List.of(DisplayMode.SCIENTIFIC), chosen);
    }

    @Test
    void theBadgeSaysExactOrApproximateInWordsWithTheDetailAsTooltip() {
        ExactnessBadge badge = new ExactnessBadge();
        assertFalse(badge.isVisible(), "No result, no badge.");

        badge.showExactness(false, "Erreur maximale : ± 2,1 × 10⁻²⁵");
        assertTrue(badge.isVisible());
        assertEquals("≈ Approché", badge.getText());
        assertEquals("Erreur maximale : ± 2,1 × 10⁻²⁵", badge.getToolTipText());
        assertEquals(Palette.APPROXIMATE_FOREGROUND, badge.getForeground());

        badge.showExactness(true, "");
        assertEquals("Exact", badge.getText());
        assertNull(badge.getToolTipText());
        assertEquals(Palette.EXACT_FOREGROUND, badge.getForeground());
        assertEquals("Exactitude du résultat : Exact", badge.getAccessibleContext().getAccessibleName());

        badge.clearBadge();
        assertFalse(badge.isVisible());
        assertEquals("", badge.getText());
    }

    @Test
    void aBlankBadgePaintsNothingAndKeepsItsVisibility() {
        ExactnessBadge badge = new ExactnessBadge();
        badge.blankBadge();
        assertFalse(badge.isVisible(), "No result yet and no badge before: it stays hidden.");

        badge.showExactness(false, "Erreur maximale : ± 2,1 × 10⁻²⁵");
        badge.blankBadge();
        assertTrue(badge.isVisible(), "Another result is coming: no hide, no show.");
        assertEquals("", badge.getText());
        assertNull(badge.getToolTipText());
        assertFalse(badge.isOpaque(), "No coloured box without a text.");
        assertNull(badge.getBorder());
        assertEquals("Exactitude du résultat", badge.getAccessibleContext().getAccessibleName());
        assertEquals("Aucun résultat", badge.getAccessibleContext().getAccessibleDescription());

        badge.showExactness(true, "");
        assertTrue(badge.isOpaque(), "The box of the badge comes back with its text.");
        assertEquals("Exact", badge.getText());
    }

    @Test
    void aBlankBadgeLeavesNoPixelOnTheRow() {
        ExactnessBadge badge = new ExactnessBadge();
        badge.setSize(96, 24);

        badge.showExactness(true, "");
        assertTrue(paintedPixels(badge) > 0, "A badge with a result is painted: the measure below means something.");

        badge.blankBadge();
        assertEquals(0, paintedPixels(badge), "The row looks the same as with a hidden badge.");
    }

    private static int paintedPixels(ExactnessBadge badge) {
        BufferedImage image = new BufferedImage(badge.getWidth(), badge.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        badge.paint(graphics);
        graphics.dispose();
        int painted = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != Color.WHITE.getRGB()) {
                    painted++;
                }
            }
        }
        return painted;
    }
}
