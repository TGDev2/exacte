package calc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.format.DisplayMode;
import calc.number.CertifiedValue;
import calc.testing.MemoryHistoryStore;
import calc.ui.widget.HistoryLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalculatorDisplayModeTest {
    private final MemoryHistoryStore history = new MemoryHistoryStore();
    private final RecordingModeStore modes = new RecordingModeStore(DisplayMode.AUTO);
    private final CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), history, modes);

    @Test
    void startsInTheRememberedModeAndInAutoByDefault() {
        assertEquals(DisplayMode.AUTO, presenter.createInitialView().displayMode());

        RecordingModeStore remembered = new RecordingModeStore(DisplayMode.FRACTION);
        CalculatorPresenter reopened = new CalculatorPresenter(new CalculatorEngine(), history, remembered);
        assertEquals(DisplayMode.FRACTION, reopened.createInitialView().displayMode());

        DisplayModeStore failing = new DisplayModeStore() {
            @Override
            public DisplayMode load() {
                throw new IllegalStateException("no preferences here");
            }

            @Override
            public void save(DisplayMode mode) {
                throw new IllegalStateException("no preferences here");
            }
        };
        CalculatorPresenter restricted = new CalculatorPresenter(new CalculatorEngine(), history, failing);
        assertEquals(DisplayMode.AUTO, restricted.createInitialView().displayMode(), "A failing store means Auto.");
        assertEquals(DisplayMode.DECIMAL, restricted.onDisplayModeChanged(DisplayMode.DECIMAL, "", "").displayMode(),
                "A failing store does not prevent changing the mode.");
    }

    @Test
    void changingTheModeFormatsTheResultAndTheHistoryAgainWithoutCalculating() {
        presenter.onOperationChanged(Operation.DIVISION, "", "");
        CalculatorViewModel auto = presenter.onCalculate("1", "3");
        assertEquals("0,333333333333…", auto.resultValue());
        assertEquals(List.of("1 / 3 = 0,333333333333…"), texts(auto));
        CalculationRecord record = auto.historyEntries().get(0);
        CertifiedValue valueBefore = record.getResult();
        int savesBefore = history.saveCount();

        CalculatorViewModel fraction = presenter.onDisplayModeChanged(DisplayMode.FRACTION, auto.firstInput(), "");
        assertEquals(DisplayMode.FRACTION, fraction.displayMode());
        assertEquals("1/3", fraction.resultValue());
        assertEquals(List.of("1 / 3 = 1/3"), texts(fraction));

        CalculatorViewModel scientific = presenter.onDisplayModeChanged(DisplayMode.SCIENTIFIC, fraction.firstInput(), "");
        assertEquals("3,33333333333… × 10⁻¹", scientific.resultValue());
        assertEquals(List.of("1 / 3 = 3,33333333333… × 10⁻¹"), texts(scientific));

        CalculatorViewModel decimal = presenter.onDisplayModeChanged(DisplayMode.DECIMAL, scientific.firstInput(), "");
        assertEquals("0,333333333333…", decimal.resultValue());

        CalculatorViewModel back = presenter.onDisplayModeChanged(DisplayMode.AUTO, decimal.firstInput(), "");
        assertEquals(texts(auto), texts(back), "Back in Auto, the history reads as it did.");

        assertSame(record, back.historyEntries().get(0), "The entries are the same objects: nothing was calculated again.");
        assertSame(valueBefore, back.historyEntries().get(0).getResult(), "The value is untouched.");
        assertEquals("1/3", back.firstInput(), "The reusable text of the result does not depend on the mode.");
        assertEquals(auto.selectedHistoryIndex(), back.selectedHistoryIndex());
        assertEquals(savesBefore, history.saveCount(), "Changing the mode does not rewrite the history file.");
    }

    @Test
    void theModeIsRememberedWhenItChanges() {
        presenter.onDisplayModeChanged(DisplayMode.SCIENTIFIC, "", "");
        assertEquals(List.of(DisplayMode.SCIENTIFIC), modes.saved);
        presenter.onDisplayModeChanged(DisplayMode.SCIENTIFIC, "", "");
        assertEquals(1, modes.saved.size(), "Choosing the mode already in force remembers nothing again.");
        presenter.onDisplayModeChanged(null, "", "");
        assertEquals(DisplayMode.SCIENTIFIC, presenter.createInitialView().displayMode(), "No mode means no change.");
    }

    @Test
    void theNextModeFollowsTheEnumOrderAndWraps() {
        List<DisplayMode> seen = new ArrayList<>();
        seen.add(presenter.createInitialView().displayMode());
        for (int step = 0; step < DisplayMode.values().length; step++) {
            seen.add(presenter.onNextDisplayMode("", "").displayMode());
        }
        assertEquals(
                List.of(DisplayMode.AUTO, DisplayMode.DECIMAL, DisplayMode.FRACTION, DisplayMode.SCIENTIFIC, DisplayMode.AUTO),
                seen);
    }

    @Test
    void anApproximateRootHasItsBadgeFieldsAndItsBound() {
        presenter.onOperationChanged(Operation.ROOT, "", "");
        CalculatorViewModel root = presenter.onCalculate("2", "");

        assertTrue(root.resultShown());
        assertFalse(root.resultExact());
        assertTrue(root.resultTruncated(), "An approximate text always leaves digits out.");
        assertEquals("≈ 1,41421356237…", root.resultValue());
        assertEquals("± 2,1 × 10⁻²⁵", root.resultErrorBound());
        assertEquals("Erreur maximale : ± 2,1 × 10⁻²⁵", root.resultDetail());
        assertEquals("", root.resultFraction(), "An approximate value has no exact fraction.");
        assertEquals("√(2) ≈ 1,41421356237…", root.historyLines().get(0).text());
        assertFalse(root.historyLines().get(0).exact());
        assertEquals("Erreur maximale : ± 2,1 × 10⁻²⁵", root.historyLines().get(0).detail());
    }

    @Test
    void anExactQuotientIsExactWithoutBoundOrFraction() {
        presenter.onOperationChanged(Operation.DIVISION, "", "");
        CalculatorViewModel quarter = presenter.onCalculate("1", "4");

        assertTrue(quarter.resultExact());
        assertFalse(quarter.resultTruncated());
        assertEquals("0,25", quarter.resultValue());
        assertEquals("", quarter.resultErrorBound());
        assertEquals("", quarter.resultFraction(), "The text shows the whole value: no secondary fraction.");
        assertEquals("", quarter.resultDetail());
        assertEquals("1 / 4 = 0,25", quarter.historyLines().get(0).text());
        assertTrue(quarter.historyLines().get(0).exact());
    }

    @Test
    void aCutExactQuotientOffersItsFractionAsSecondaryInformation() {
        presenter.onOperationChanged(Operation.DIVISION, "", "");
        CalculatorViewModel third = presenter.onCalculate("1", "3");

        assertTrue(third.resultExact());
        assertTrue(third.resultTruncated());
        assertEquals("1/3", third.resultFraction());
        assertEquals("Valeur exacte : 1/3 (affichage arrondi)", third.resultDetail());

        CalculatorViewModel fraction = presenter.onDisplayModeChanged(DisplayMode.FRACTION, third.firstInput(), "");
        assertEquals("1/3", fraction.resultValue());
        assertEquals("", fraction.resultFraction(), "The main text is already the fraction.");
        assertEquals("", fraction.resultDetail());

        CalculatorViewModel scientific = presenter.onDisplayModeChanged(DisplayMode.SCIENTIFIC, fraction.firstInput(), "");
        assertEquals("1/3", scientific.resultFraction(), "A cut scientific text keeps the fraction as secondary information.");
    }

    @Test
    void withoutResultTheModeStillChangesAndNothingIsShown() {
        CalculatorViewModel view = presenter.onDisplayModeChanged(DisplayMode.FRACTION, "", "");
        assertFalse(view.resultShown());
        assertEquals("--", view.resultValue());
        assertEquals("", view.resultDetail());
        assertTrue(view.historyLines().isEmpty());
    }

    @Test
    void theHistoryRestoredFromTheStoreIsFormattedInTheRememberedMode() {
        presenter.onOperationChanged(Operation.DIVISION, "", "");
        presenter.onCalculate("1", "3");

        CalculatorPresenter reopened = new CalculatorPresenter(
                new CalculatorEngine(), history, new RecordingModeStore(DisplayMode.FRACTION));
        List<HistoryLine> lines = reopened.createInitialView().historyLines();
        assertEquals(List.of("1 / 3 = 1/3"), lines.stream().map(HistoryLine::text).toList());
    }

    private static List<String> texts(CalculatorViewModel view) {
        return view.historyLines().stream().map(HistoryLine::text).toList();
    }

    private static final class RecordingModeStore implements DisplayModeStore {
        private final DisplayMode initial;
        private final List<DisplayMode> saved = new ArrayList<>();

        RecordingModeStore(DisplayMode initial) {
            this.initial = initial;
        }

        @Override
        public DisplayMode load() {
            return initial;
        }

        @Override
        public void save(DisplayMode mode) {
            saved.add(mode);
        }
    }
}
