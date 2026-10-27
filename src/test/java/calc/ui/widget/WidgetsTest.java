package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
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
}
