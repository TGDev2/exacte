package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.CalculationHistoryException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
import calc.testing.JsonHistoryGenerators;
import calc.testing.JsonHistoryGenerators.ExpectedRejection;
import calc.testing.JsonHistoryGenerators.JsonFile;
import calc.testing.TempDirectories;
import calc.testing.TimingBudget;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Fuzzing of the version 2 reader. Whatever the content of the file:
 * <ul>
 *   <li>the only exception is {@link HistoryFormatException} (the file as a whole is refused), never a hang;
 *   <li>the valid entries are restored in order, and every rejected entry is reported with its position and its cause;
 *   <li>unknown fields are ignored;
 *   <li>loading a file never alters it, and a file that cannot be used is copied before anything can replace it.
 * </ul>
 */
@Tag("robustness")
class HistoryJsonFuzzProperties {
    private static final Duration BUDGET = Duration.ofSeconds(10);

    @Provide
    Arbitrary<JsonFile> structured() {
        return JsonHistoryGenerators.structuredFiles();
    }

    @Provide
    Arbitrary<byte[]> anyContent() {
        return JsonHistoryGenerators.anyContent();
    }

    @Provide
    Arbitrary<byte[]> unusable() {
        return JsonHistoryGenerators.unusableContent();
    }

    @Property(tries = 400)
    void validEntriesAreRestoredAndEveryRejectionIsReported(@ForAll("structured") JsonFile file) throws Exception {
        DecodedHistory decoded = TimingBudget.assertCompletesWithin(BUDGET, () -> HistoryCodec.decode(file.bytes()));

        assertEquals(file.expectedEntries(), decoded.records().stream().map(CalculationRecord::toString).toList(),
                () -> "The valid entries, in order, for " + file);
        assertEquals(file.expectedRejections(),
                decoded.rejections().stream().map(rejection -> new ExpectedRejection(rejection.lineNumber(), rejection.cause())).toList(),
                () -> "Position and cause of each rejected entry for " + file);
        assertEquals(2, decoded.version());
    }

    @Property(tries = 600)
    void anyContentGivesAResultOrTheAnnouncedException(@ForAll("anyContent") byte[] content) throws Exception {
        try {
            DecodedHistory decoded = TimingBudget.assertCompletesWithin(BUDGET, () -> HistoryCodec.decode(content));
            decoded.rejections().forEach(rejection -> {
                assertNotNull(rejection.cause());
                assertTrue(rejection.lineNumber() >= 1);
                assertFalse(rejection.detail().isEmpty());
            });
        } catch (Exception caught) {
            if (!(caught instanceof HistoryFormatException refused)) {
                throw caught;
            }
            assertNotNull(refused.reason());
        }
    }

    @Property(tries = 200)
    void aStoreNeverAltersWhatItLoadsAndCopiesWhatItCannotUse(@ForAll("anyContent") byte[] content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.json");
            Files.write(file, content);
            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                HistoryLoadResult result = null;
                CalculationHistoryException refusal = null;
                try {
                    result = TimingBudget.assertCompletesWithin(BUDGET, store::loadHistory);
                } catch (Exception caught) {
                    if (!(caught instanceof CalculationHistoryException exception)) {
                        throw caught;
                    }
                    refusal = exception;
                }
                assertArrayEquals(content, Files.readAllBytes(file), "Loading never alters the file.");

                if (result != null) {
                    if (result.backupFile() != null) {
                        assertArrayEquals(content, Files.readAllBytes(result.backupFile()), "The copy is the original.");
                    }
                    assertEquals(result.rejections().isEmpty(), result.backupFile() == null,
                            "A copy exists exactly when something was rejected.");
                } else if (refusal.getBackupFile() != null) {
                    assertArrayEquals(content, Files.readAllBytes(refusal.getBackupFile()), "The copy is the original.");
                } else {
                    // Over a limit: not copied, and nothing may replace it.
                    CalculationRecord record = new CalculatorEngine().calculate(Operation.ADDITION, "1", "2");
                    assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(record)));
                    assertArrayEquals(content, Files.readAllBytes(file), "A refused write leaves the file as it was.");
                }
            }
        });
    }

    @Property(tries = 200)
    void anUnusableFileIsReplacedOnlyAfterItWasCopied(@ForAll("unusable") byte[] content) throws Exception {
        TempDirectories.with(directory -> {
            Path file = directory.resolve("history.json");
            Files.write(file, content);
            try (JsonHistoryStore store = JsonHistoryStore.open(directory)) {
                try {
                    store.loadHistory();
                } catch (CalculationHistoryException refusal) {
                    CalculationRecord record = new CalculatorEngine().calculate(Operation.ADDITION, "1", "2");
                    if (refusal.getBackupFile() == null) {
                        assertThrows(CalculationHistoryException.class, () -> store.saveHistory(List.of(record)));
                        assertArrayEquals(content, Files.readAllBytes(file), "A protected file is untouched.");
                    } else {
                        store.saveHistory(List.of(record));
                        assertArrayEquals(content, Files.readAllBytes(refusal.getBackupFile()), "The original survives in the copy.");
                    }
                }
            }
        });
    }
}
