package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.engine.RejectedLine;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An entry written when the outward rounding could still bring a bound to 0: the engine now refuses its recalculation, so the
 * entry is set aside and counted like any other that does not match, and the rest of the file is read (ADR 0004, consequences).
 */
class OldRoundedHistoryEntryTest {
    @Test
    void anEntryWhoseBoundWasRoundedToZeroIsSetAsideAndTheOthersAreKept() throws Exception {
        String tiny = "{ \"op\": \"power\", \"operands\": [{ \"exact\": false, \"lower\": \"1/16\", \"upper\": \"1/8\" }, \"4096\"],"
                + " \"result\": { \"exact\": false, \"lower\": \"0\", \"upper\": \"1/8\" } }";
        String plain = "{ \"op\": \"addition\", \"operands\": [\"1\", \"2\"], \"result\": { \"exact\": true, \"value\": \"3\" } }";
        String file = "{\n  \"format\": \"calc-history\",\n  \"version\": 2,\n  \"entries\": [\n    " + tiny + ",\n    " + plain + "\n  ]\n}\n";

        DecodedHistory decoded = HistoryCodec.decode(file.getBytes(StandardCharsets.UTF_8));

        assertEquals(List.of("3"), decoded.records().stream().map(record -> record.getFormattedResult()).toList());
        assertEquals(List.of(RejectedLine.Cause.INVALID_OPERAND), decoded.rejections().stream().map(RejectedLine::cause).toList());
    }
}
