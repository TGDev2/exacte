package calc.expression;

import calc.export.NotebookExporterTest;
import calc.notebook.Notebook;
import calc.testing.NotebookGenerators;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.RandomGenerator;

/**
 * Writes the notebooks that the JVM and Node replay in the differential test: one line {@code <id>\t<base64 UTF-8 of the
 * lines joined by \n>} per notebook. The same seed gives the same bytes. Lines that make no sense out of context
 * ({@code ans}, {@code $n}, variables) are kept: their errors are compared too. It sits in this package because
 * {@link ExpressionGenerators} is package-private, and it is not a property: nothing here asserts anything.
 */
public final class ParityCorpusWriter {
    private static final int LINES = 1000;
    private static final int NOTEBOOKS = 50;
    private static final int MAX_LINES_OF_A_NOTEBOOK = 30;
    private static final int GENERATION_SIZE = 1000;

    /** 12345678901234567890 squared: beyond a long, under the root and at its result. */
    private static final String LARGE_PERFECT_SQUARE = "152415787532388367501905199875019052100";

    private ParityCorpusWriter() {
    }

    /** Arguments: the file to write, and the seed, a long since {@code github.run_id} goes beyond 2^31. */
    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected <output file> <seed>.");
        }
        Path output = Path.of(args[0]);
        long seed = Long.parseLong(args[1]);

        List<String> entries = new ArrayList<>();
        entries.add(entry("export", String.join("\n", NotebookExporterTest.REPRESENTATIVE)));
        handLines().forEach((id, line) -> entries.add(entry("hand-" + id, line)));
        entries.addAll(generated(seed));

        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, String.join("\n", entries) + "\n", StandardCharsets.UTF_8);
        String name = output.getFileName().toString();
        int extension = name.lastIndexOf('.');
        String stem = extension < 0 ? name : name.substring(0, extension);
        Files.writeString(output.resolveSibling(stem + ".seed"), seed + "\n", StandardCharsets.UTF_8);
    }

    /**
     * Generators refuse to start outside the lifecycle of jqwik, and {@code sample()} is the public way in, but it draws
     * from a random source of its own. So the sample is only the door: {@code map} runs inside it, and everything is
     * drawn there from the given seed. {@code Arbitraries.just} would not do, its {@code map} runs at once, outside.
     */
    private static List<String> generated(long seed) {
        return Arbitraries.of(seed).map(ParityCorpusWriter::generatedInsideJqwik).sample();
    }

    private static List<String> generatedInsideJqwik(long seed) {
        Random random = new Random(seed);
        List<String> entries = new ArrayList<>();
        RandomGenerator<Node> lines = ExpressionGenerators.lines().generator(GENERATION_SIZE);
        for (int index = 1; index <= LINES; index++) {
            entries.add(entry("line-" + index, CanonicalPrinter.print(lines.next(random).value())));
        }
        RandomGenerator<Notebook> notebooks = NotebookGenerators.notebooks(MAX_LINES_OF_A_NOTEBOOK).generator(GENERATION_SIZE);
        for (int index = 1; index <= NOTEBOOKS; index++) {
            entries.add(entry("notebook-" + index, String.join("\n", notebooks.next(random).value().texts())));
        }
        return entries;
    }

    /** The cases a generator would rarely draw: scripts, characters newer than the Unicode of the JDK, and every limit. */
    private static Map<String, String> handLines() {
        Map<String, String> lines = new LinkedHashMap<>();
        lines.put("accented-letters", "été = 1");
        lines.put("greek-letters", "π = 3");
        lines.put("cyrillic-letters", "цена + 1");
        lines.put("chinese-letters", "价格 * 2");
        lines.put("emoji", character(0x1F600));
        lines.put("no-break-space", "1" + character(0x00A0) + "000");
        lines.put("control-character", "1 " + character(0x0001) + " 2");
        lines.put("u-a7cb", character(0xA7CB));
        lines.put("u-31ef", character(0x31EF));
        lines.put("u-2ebf0", character(0x2EBF0));
        lines.put("u-0130", "RAC" + character(0x0130) + "NE(4)");
        lines.put("large-perfect-square", "√" + LARGE_PERFECT_SQUARE);
        lines.put("power-4095", "2^4095");
        lines.put("power-4096", "2^4096");
        lines.put("power-100000", "2^100000");
        lines.put("division-by-zero", "1/0");
        lines.put("root-of-a-negative", "√(-1)");
        lines.put("depth-100", nested(100));
        lines.put("depth-101", nested(101));
        lines.put("length-1000", "#" + "x".repeat(999));
        lines.put("length-1001", "#" + "x".repeat(1000));
        lines.put("nodes-500", "-1" + "+1".repeat(249));
        lines.put("nodes-501", "1" + "+1".repeat(250));
        return lines;
    }

    private static String character(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static String nested(int depth) {
        return "(".repeat(depth) + "1" + ")".repeat(depth);
    }

    private static String entry(String id, String text) {
        return id + "\t" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }
}
