package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookFormatException.Reason;
import calc.testing.NotebookGenerators;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;

/**
 * Hostile and damaged notebook files: the loader returns a notebook or a {@link NotebookFormatException}, never anything
 * else, quickly; the lines of a readable file are all kept, in order; and whatever is loaded can be evaluated.
 */
@Tag("robustness")
class NotebookFileFuzzProperties {
    private static final Duration BUDGET = Duration.ofSeconds(10);

    // ---- independent writer of the format (a second statement of it, not the production code)

    private static String json(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            if (c >= 0x20 && c < 0x7F && c != '"' && c != '\\') {
                out.append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.append('"').toString();
    }

    private static String file(String extra, List<String> elements) {
        return "{" + extra + "\"format\":\"calc-notebook\",\"version\":3,\"lines\":[" + String.join(",", elements) + "]}";
    }

    @Provide
    Arbitrary<List<String>> lineTexts() {
        return NotebookGenerators.lineTexts().list().ofMaxSize(60);
    }

    @Provide
    Arbitrary<String> junk() {
        return Arbitraries.of("null", "true", "12", "\"s\"", "[]", "{}", "[1,[2,[3]]]", "{\"a\":{\"b\":{\"c\":[]}}}", "-0.5e3", "\"\\ud800\"");
    }

    /** Runs the loader, which may refuse with a {@link NotebookFormatException} and with nothing else. */
    private static DecodedNotebook load(byte[] bytes) {
        return assertTimeoutPreemptively(BUDGET, () -> {
            try {
                DecodedNotebook read = NotebookCodec.decode(bytes);
                assertTrue(read.notebook().size() <= calc.notebook.Notebook.MAX_LINES);
                // Whatever was loaded can be recalculated, and recalculating never throws.
                assertEquals(read.notebook().size(), NotebookEvaluator.evaluate(read.notebook()).results().size());
                return read;
            } catch (NotebookFormatException refused) {
                return null;
            }
        });
    }

    @Property(tries = 400)
    void everyLineOfAReadableFileIsKeptInOrderWhateverItsText(@ForAll("lineTexts") List<String> texts,
                                                              @ForAll("junk") String unknownValue) {
        List<String> elements = new ArrayList<>();
        for (String text : texts) {
            elements.add("{\"unknown\":" + unknownValue + ",\"source\":" + json(text) + "}");
        }
        String extra = "\"displayMode\":\"decimal\",\"unknown\":" + unknownValue + ",";

        DecodedNotebook read = load(file(extra, elements).getBytes(StandardCharsets.UTF_8));

        assertEquals(texts, read.notebook().texts(), "no line is dropped, repaired or reordered");
        assertEquals(DisplayMode.DECIMAL, read.displayMode());
        assertEquals(texts.size(), read.notebook().lines().stream().map(line -> line.id()).distinct().count(), "distinct identifiers");
    }

    @Property(tries = 300)
    void anElementThatIsNotALineRefusesTheFileAsMalformedAndNothingElse(@ForAll("lineTexts") List<String> texts,
                                                                       @ForAll("junk") String notALine,
                                                                       @ForAll int position) {
        List<String> elements = texts.stream().map(text -> "{\"source\":" + json(text) + "}").collect(Collectors.toCollection(ArrayList::new));
        elements.add(Math.floorMod(position, elements.size() + 1), notALine);

        assertTimeoutPreemptively(BUDGET, () -> {
            NotebookFormatException refused = org.junit.jupiter.api.Assertions.assertThrows(NotebookFormatException.class,
                    () -> NotebookCodec.decode(file("", elements).getBytes(StandardCharsets.UTF_8)));
            assertEquals(Reason.MALFORMED_STRUCTURE, refused.reason());
        });
    }

    @Property(tries = 500)
    void arbitraryBytesAreRefusedOrLoadedNeverWorse(@ForAll byte[] bytes) {
        load(bytes);
    }

    @Property(tries = 500)
    void aDamagedNotebookFileIsRefusedOrLoadedNeverWorse(@ForAll("lineTexts") List<String> texts, @ForAll("damages") Damage damage) throws Exception {
        byte[] original = NotebookCodec.encode(NotebookGenerators.notebook(texts), DisplayMode.AUTO).bytes();

        byte[] damaged = damage.apply(original);

        DecodedNotebook read = load(damaged);
        if (read != null && read.linesRead()) {
            assertTrue(read.notebook().size() <= Math.max(texts.size(), calc.notebook.Notebook.MAX_LINES));
        }
    }

    @Property(tries = 300)
    void anyJsonTextIsRefusedOrLoadedNeverWorse(@ForAll("anyJson") String json) {
        load(json.getBytes(StandardCharsets.UTF_8));
    }

    @Property(tries = 100)
    void deepNestingIsRefusedAsALimitWithoutOverflowingTheStack(@ForAll("depth") int depth, @ForAll("opener") String opener) {
        String hostile = "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[],\"x\":" + opener.repeat(depth) + "}";

        assertTimeoutPreemptively(BUDGET, () -> {
            NotebookFormatException refused = org.junit.jupiter.api.Assertions.assertThrows(NotebookFormatException.class,
                    () -> NotebookCodec.decode(hostile.getBytes(StandardCharsets.UTF_8)));
            assertTrue(refused.reason() == Reason.JSON_LIMIT || refused.reason() == Reason.MALFORMED_JSON, refused.reason().name());
        });
    }

    @Provide
    Arbitrary<Integer> depth() {
        return Arbitraries.of(33, 40, 1000, 100_000, 1_000_000);
    }

    @Provide
    Arbitrary<String> opener() {
        return Arbitraries.of("[", "{\"a\":");
    }

    /** A way to damage a file. */
    interface Damage {
        byte[] apply(byte[] original);
    }

    @Provide
    Arbitrary<Damage> damages() {
        Arbitrary<Damage> truncate = Arbitraries.integers().between(0, 400).map(cut -> original -> java.util.Arrays.copyOf(original, Math.min(cut, original.length)));
        Arbitrary<Damage> flip = Combinators.combine(Arbitraries.integers().between(0, 2000), Arbitraries.bytes())
                .as((position, value) -> original -> {
                    byte[] copy = original.clone();
                    if (copy.length > 0) {
                        copy[position % copy.length] = value;
                    }
                    return copy;
                });
        Arbitrary<Damage> remove = Combinators.combine(Arbitraries.integers().between(0, 2000), Arbitraries.integers().between(1, 40))
                .as((position, length) -> original -> {
                    int from = original.length == 0 ? 0 : position % original.length;
                    int to = Math.min(original.length, from + length);
                    byte[] copy = new byte[original.length - (to - from)];
                    System.arraycopy(original, 0, copy, 0, from);
                    System.arraycopy(original, to, copy, from, original.length - to);
                    return copy;
                });
        Arbitrary<Damage> repeat = Arbitraries.integers().between(2, 30).map(times -> original -> {
            String text = new String(original, StandardCharsets.UTF_8);
            int open = text.indexOf('{', 1);
            return open < 0 ? original : (text.substring(0, open) + text.substring(open).repeat(times)).getBytes(StandardCharsets.UTF_8);
        });
        return Arbitraries.oneOf(truncate, flip, remove, repeat);
    }

    @Provide
    Arbitrary<String> anyJson() {
        Arbitrary<String> scalar = Arbitraries.of("null", "true", "false", "0", "-1", "3", "1.5", "1e400", "\"calc-notebook\"", "\"x\"", "\"\"");
        Arbitrary<String> value = Arbitraries.recursive(() -> scalar,
                inner -> Arbitraries.frequencyOf(
                        Tuple.of(3, inner.list().ofMaxSize(4).map(items -> "[" + String.join(",", items) + "]")),
                        Tuple.of(3, Combinators.combine(Arbitraries.of("format", "version", "lines", "source", "id", "displayMode", "x"), inner)
                                .as((key, item) -> key + "\u0000" + item).list().ofMaxSize(4)
                                .map(members -> "{" + members.stream().map(member -> "\"" + member.replace("\u0000", "\":")).collect(Collectors.joining(",")) + "}")),
                        Tuple.of(1, scalar)),
                0, 4);
        return value;
    }
}
