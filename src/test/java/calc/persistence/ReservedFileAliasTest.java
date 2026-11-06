package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Another spelling of a file the store keeps is still that file: a document must not be saved over the current notebook. */
class ReservedFileAliasTest {
    private static final String CURRENT = "carnet-courant.calc.json";

    @Test
    void aHardLinkToTheCurrentNotebookIsReserved(@TempDir Path root) throws IOException {
        Path data = Files.createDirectories(root.resolve("data"));
        try (NotebookStore store = NotebookStore.open(data)) {
            Path current = Files.writeString(data.resolve(CURRENT), "{}");
            Path alias = Files.createLink(root.resolve("alias.calc.json"), current);

            assertTrue(store.isReserved(alias));
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aTrailingDotThatWindowsIgnoresIsReserved(@TempDir Path data) throws IOException {
        try (NotebookStore store = NotebookStore.open(data)) {
            Files.writeString(data.resolve(CURRENT), "{}");

            assertTrue(store.isReserved(Path.of(data.resolve(CURRENT) + ".")));
        }
    }

    @Test
    void anOrdinaryDocumentIsNotReserved(@TempDir Path root) throws IOException {
        Path data = Files.createDirectories(root.resolve("data"));
        try (NotebookStore store = NotebookStore.open(data)) {
            Files.writeString(data.resolve(CURRENT), "{}");

            assertFalse(store.isReserved(Files.writeString(root.resolve("devis.calc.json"), "{}")));
            assertFalse(store.isReserved(root.resolve("not-created-yet.calc.json")));
        }
    }
}
