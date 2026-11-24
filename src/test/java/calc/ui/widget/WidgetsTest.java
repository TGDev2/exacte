package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        assertEquals("Affichage", selector.getAccessibleContext().getAccessibleName());
        assertEquals(List.of("Auto", "Décimal", "Fraction", "Scientifique"), java.util.Arrays.stream(DisplayMode.values())
                .map(mode -> selector.button(mode).getText()).toList());
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

        selector.button(DisplayMode.SCIENTIFIC).doClick(0);
        assertEquals(List.of(DisplayMode.SCIENTIFIC), chosen);
    }
}
