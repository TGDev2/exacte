package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A line of an old history that does not reach the notebook is announced, with the file that still holds it. */
class MigrationRejectionNoticeTest {
    private static String statusAfterMigrating(Path data, String... lines) throws Exception {
        Files.write(data.resolve("history.tsv"), List.of(lines));
        try (NotebookStore store = NotebookStore.open(data)) {
            return store.load().status();
        }
    }

    @Test
    void linesThatCannotBeTakenOverAreAnnounced(@TempDir Path data) throws Exception {
        String status = statusAfterMigrating(data,
                "ADDITION\t1\t2\t3",
                "POWER\t2\t0,5\t1.4142135623730951",
                "POWER\t1,0001\t999\t1.1051");

        assertEquals("Votre historique a été converti en carnet (1 ligne). 2 lignes n'ont pas pu être reprises : "
                + "elles restent dans history.tsv.v1.bak.", status);
    }

    @Test
    void oneLineThatCannotBeTakenOverIsAnnouncedInTheSingular(@TempDir Path data) throws Exception {
        String status = statusAfterMigrating(data, "ADDITION\t1\t2\t3", "ADDITION\t1\t1\t3");

        assertEquals("Votre historique a été converti en carnet (1 ligne). 1 ligne n'a pas pu être reprise : "
                + "elle reste dans history.tsv.v1.bak.", status);
    }

    @Test
    void nothingIsAddedWhenEveryLineIsTakenOver(@TempDir Path data) throws Exception {
        String status = statusAfterMigrating(data, "ADDITION\t1\t2\t3");

        assertEquals("Votre historique a été converti en carnet (1 ligne).", status);
    }
}
