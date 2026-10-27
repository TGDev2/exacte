package calc.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationRecord;
import calc.engine.Operation;
import calc.testing.RecordGenerators;
import java.util.Optional;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.statistics.Statistics;

/**
 * The bridge from the old history (docs/adr/0008-semantique-carnet.md, section 8): for every entry the public engine
 * makes, the line is the documented text and, evaluated as a notebook line, gives exactly the value of the entry (the
 * same interval for a root). The only entries without a line are those the grammar cannot write.
 */
@Tag("property")
class HistoryBridgeProperties {
    private static final int MAX_LINE_LENGTH = 1000;

    @Property
    void everyEntryBecomesALineOfTheSameValue(@ForAll("entries") CalculationRecord entry) {
        String expected = expectedLine(entry);
        Optional<String> line = HistoryBridge.toLine(entry);
        boolean fits = expected.length() <= MAX_LINE_LENGTH;
        Statistics.label("line").collect(fits ? "written" : "operand longer than a line");
        Statistics.label("result").collect(entry.getResult().isExact() ? "exact" : "interval");
        if (!fits) {
            assertEquals(Optional.empty(), line);
            return;
        }
        assertEquals(Optional.of(expected), line);
        LineResult result = NotebookEvaluator.evaluate(NotebookTestSupport.notebook(expected)).result(1);
        assertTrue(result.hasValue(), () -> expected + " -> " + result.failure());
        assertEquals(entry.getResult(), result.value(), expected);
    }

    @Property
    void anEntryCalculatedFromAnApproximateRootGivesNoLine(@ForAll("remembered") CalculationRecord entry) {
        // A remembered root may be exact (√4): it is then an ordinary operand.
        Statistics.label("remembered root").collect(entry.hasApproximateOperand() ? "approximate" : "exact");
        Optional<String> line = HistoryBridge.toLine(entry);
        if (entry.hasApproximateOperand()) {
            assertEquals(Optional.empty(), line, "an interval has no literal in the grammar");
            return;
        }
        assertEquals(Optional.of(expectedLine(entry)), line);
        assertEquals(entry.getResult(), NotebookEvaluator.evaluate(NotebookTestSupport.notebook(line.get())).result(1).value());
    }

    @Provide
    Arbitrary<CalculationRecord> entries() {
        return RecordGenerators.anySizeRecords();
    }

    @Provide
    Arbitrary<CalculationRecord> remembered() {
        return RecordGenerators.rememberedRootRecords();
    }

    // The text the ADR documents, written here from the operand texts of the entry.
    private static String expectedLine(CalculationRecord entry) {
        if (entry.getOperation() == Operation.ROOT) {
            return "√(" + entry.getFirstValue() + ")";
        }
        return operand(entry.getFirstValue()) + " " + entry.getOperation().getHistorySymbol() + " "
                + operand(entry.getSecondValue());
    }

    private static String operand(String text) {
        boolean bare = text.matches("[0-9][0-9,]*(e-?[0-9]+)?");
        return bare ? text : "(" + text + ")";
    }
}
