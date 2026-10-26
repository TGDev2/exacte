package calc.ui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HistoryLineTest {
    @Test
    void anExactResultIsJoinedByAnEqualitySign() {
        assertEquals("1 / 4 = 0,25", new HistoryLine("1 / 4", "0,25", true, "").text());
    }

    @Test
    void anApproximateResultCarriesItsOwnSign() {
        HistoryLine line = new HistoryLine("√(2)", "≈ 1,41421356237…", false, "Erreur maximale : ± 2,1 × 10⁻²⁵");
        assertEquals("√(2) ≈ 1,41421356237…", line.text());
        assertEquals(line.text(), line.toString());
    }
}
