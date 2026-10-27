package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.Line;
import calc.notebook.Notebook;
import calc.notebook.NotebookEvaluator;
import calc.notebook.NotebookException;
import calc.testing.NotebookGenerators;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;

/**
 * A notebook written to the version 3 file and read back keeps its texts, its identifiers and its display mode, and
 * therefore gives the same results after the recalculation: the file holds the sources and nothing else.
 */
@Tag("property")
class NotebookRoundTripProperties {
    @Provide
    Arbitrary<Notebook> editedNotebooks() {
        // Identifiers are not 1..n once lines were inserted, moved and deleted: the file must keep them as they are.
        Arbitrary<List<Tuple.Tuple2<Integer, Integer>>> moves = Combinators.combine(Arbitraries.integers().between(0, 30),
                Arbitraries.integers().between(0, 3)).as(Tuple::of).list().ofMaxSize(12);
        return Combinators.combine(NotebookGenerators.notebooks(25), moves, NotebookGenerators.lineTexts().list().ofMaxSize(6))
                .as((start, steps, extra) -> edit(start, steps, extra));
    }

    private static Notebook edit(Notebook start, List<Tuple.Tuple2<Integer, Integer>> steps, List<String> extra) {
        Notebook notebook = start;
        int next = 0;
        try {
            for (Tuple.Tuple2<Integer, Integer> step : steps) {
                if (notebook.isEmpty()) {
                    notebook = notebook.append(extra.isEmpty() ? "1 + 1" : extra.get(next++ % extra.size()));
                    continue;
                }
                Line target = notebook.lines().get(step.get1() % notebook.size());
                notebook = switch (step.get2()) {
                    case 0 -> notebook.delete(target.id());
                    case 1 -> notebook.move(target.id(), (step.get1() * 7) % notebook.size());
                    case 2 -> notebook.insertAfter(target.id(), extra.isEmpty() ? "2 * 3" : extra.get(next++ % extra.size()));
                    default -> notebook.edit(target.id(), "ans + " + step.get1());
                };
            }
        } catch (NotebookException exception) {
            throw new AssertionError(exception);
        }
        return notebook;
    }

    @Property(tries = 300)
    void aGeneratedNotebookComesBackWithTheSameSourcesIdsAndResults(@ForAll("editedNotebooks") Notebook notebook,
                                                                   @ForAll DisplayMode mode) throws Exception {
        NotebookCodec.Encoded encoded = NotebookCodec.encode(notebook, mode);

        DecodedNotebook read = NotebookCodec.decode(encoded.bytes());

        assertEquals(notebook.texts(), read.notebook().texts(), "same sources");
        assertEquals(notebook, read.notebook(), "same lines, identifiers included");
        assertEquals(mode, read.displayMode());
        assertEquals(0, read.regeneratedIds());
        assertEquals(0, read.splitBreaks());
        assertTrue(read.linesRead());
        assertEquals(NotebookEvaluator.evaluate(notebook).results(), NotebookEvaluator.evaluate(read.notebook()).results(),
                "same results after the recalculation");
        assertArrayEquals(encoded.bytes(), NotebookCodec.encode(read.notebook(), read.displayMode()).bytes(),
                "reading then writing changes no byte");
    }

    @Property(tries = 100)
    void theFileIsAlwaysValidUtf8WithoutAResultInIt(@ForAll("editedNotebooks") Notebook notebook) throws Exception {
        byte[] bytes = NotebookCodec.encode(notebook, DisplayMode.AUTO).bytes();

        String text = new String(bytes, StandardCharsets.UTF_8);

        assertArrayEquals(bytes, text.getBytes(StandardCharsets.UTF_8), "valid UTF-8: nothing was replaced on the way");
        assertTrue(text.startsWith("{\n  \"format\": \"calc-notebook\",\n  \"version\": 3,\n"));
        assertTrue(!text.contains("\"value\"") && !text.contains("\"exact\"") && !text.contains("\"result\""));
    }
}
