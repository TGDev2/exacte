package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The exports of calc.js give, under Node, the very string that WebNotebook gives on the JVM, scenario by scenario, on one
 * notebook kept from one call to the next.
 *
 * <p>The class path of webTest holds the compiled web module, not the core: WebNotebook runs in a class loader of its own,
 * over the classes that Gradle compiled for generateJavaScript, so this test needs no change of the build. Strings cross
 * as UTF-16 units in both directions, the scenario in pure ASCII JSON and the answers in Base64: neither the code page of
 * the system nor an encoding can hide a difference, and an unpaired surrogate survives the trip.
 */
@DisplayName("WebExports : le même instantané sous Node que sur la JVM")
class WebExportsAcceptanceTest {
    private static final String CALC_JS = "web/build/generated/teavm/js/calc.js";
    private static final Path WEB_CLASSES = Path.of("web", "build", "classes", "java", "main");
    private static final Path CORE_CLASSES = Path.of("build", "classes", "java", "main");
    private static final long NODE_WATCHDOG_MINUTES = 5;

    private static final String NO_ERROR = "\"error\":null,\"errorStart\":-1,\"errorEnd\":-1}";
    private static final String CLEAN = "{\"mode\":\"AUTO\",\"canUndo\":false,\"canRedo\":false,\"message\":null,\"lines\":[";

    private static final String RUNNER = """
            'use strict';
            const fs = require('fs');
            const path = require('path');
            const [calcFile, scenarioFile, outputFile] = process.argv.slice(2);
            const calc = require(path.resolve(calcFile));
            const answers = JSON.parse(fs.readFileSync(scenarioFile, 'utf8')).map(([name, ...args]) => {
              const answer = calc[name](...args);
              if (typeof answer !== 'string') {
                throw new Error(name + ' gave a ' + typeof answer + ', not a JavaScript string');
              }
              if (name !== 'texts') {
                JSON.parse(answer);
              }
              return Buffer.from(answer, 'utf16le').toString('base64');
            });
            fs.writeFileSync(outputFile, answers.join('\\n'), 'utf8');
            """;

    @TempDir
    Path temporaryDirectory;

    private record Step(String export, List<Object> arguments) {
    }

    private record NodeRun(int exitCode, String stderr, List<String> answers) {
    }

    private static Step step(String export, Object... arguments) {
        return new Step(export, List.of(arguments));
    }

    // ---- The scenarios of the contract --------------------------------------------------------------------------------

    @Test
    void oneThirdTimesThreeIsExactlyOneOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "(1/3)*3", "AUTO"), step("snapshot"), step("texts"));

        String expected = CLEAN + "{\"number\":1,\"text\":\"(1/3)*3\",\"status\":\"EXACT\",\"result\":\"1\","
                + "\"exactFraction\":null,\"errorBound\":null," + NO_ERROR + "]}";
        assertEquals(expected, answers.get(0));
        assertEquals(expected, answers.get(1));
        assertEquals("(1/3)*3", answers.get(2));
    }

    /** The characters of these strings are the ones TeaVM has to carry across: ≈ √ … ⁻ ². */
    @Test
    void theRootAndItsBoundKeepTheirCharactersOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "√2", "AUTO"), step("setMode", "SCIENTIFIC"));

        assertEquals(CLEAN + "{\"number\":1,\"text\":\"√2\",\"status\":\"APPROXIMATE\",\"result\":\"≈ 1,41421356237…\","
                + "\"exactFraction\":null,\"errorBound\":\"± 2,1 × 10⁻²⁵\"," + NO_ERROR + "]}", answers.get(0));
        assertEquals("{\"mode\":\"SCIENTIFIC\",\"canUndo\":false,\"canRedo\":false,\"message\":null,\"lines\":["
                + "{\"number\":1,\"text\":\"√2\",\"status\":\"APPROXIMATE\",\"result\":\"≈ 1,41421356237… × 10⁰\","
                + "\"exactFraction\":null,\"errorBound\":\"± 2,1 × 10⁻²⁵\"," + NO_ERROR + "]}", answers.get(1));
    }

    @Test
    void oneThirdInEachModeIsTheSameOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "1/3", "AUTO"), step("setMode", "DECIMAL"),
                step("setMode", "FRACTION"), step("setMode", "SCIENTIFIC"), step("setMode", "AUTO"));

        assertEquals(CLEAN + "{\"number\":1,\"text\":\"1/3\",\"status\":\"EXACT\",\"result\":\"0,333333333333…\","
                + "\"exactFraction\":\"1/3\",\"errorBound\":null," + NO_ERROR + "]}", answers.get(0));
        assertTrue(answers.get(2).contains("\"result\":\"1/3\",\"exactFraction\":\"1/3\""), answers.get(2));
    }

    @Test
    void aLineInErrorIsTheSameOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "2 +", "AUTO"));

        assertEquals(CLEAN + "{\"number\":1,\"text\":\"2 +\",\"status\":\"ERROR\",\"result\":null,\"exactFraction\":null,"
                + "\"errorBound\":null,\"error\":\"Opérande manquant après « + ».\",\"errorStart\":2,\"errorEnd\":3}]}",
                answers.get(0));
    }

    @Test
    void movingALineThenUndoingAndRedoingIsTheSameOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "10\n3\n$1 + 1", "AUTO"), step("move", 2, 1), step("texts"),
                step("undo"), step("texts"), step("redo"), step("texts"));

        assertTrue(answers.get(1).endsWith("{\"number\":3,\"text\":\"$2 + 1\",\"status\":\"EXACT\",\"result\":\"11\","
                + "\"exactFraction\":null,\"errorBound\":null," + NO_ERROR + "]}"), answers.get(1));
        assertEquals("3\n10\n$2 + 1", answers.get(2));
        assertEquals("10\n3\n$1 + 1", answers.get(4));
        assertEquals("3\n10\n$2 + 1", answers.get(6));
    }

    @Test
    void deleteLineLeavesADeletedReferenceOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "1\n2\n$1 + $2", "AUTO"), step("deleteLine", 1), step("texts"),
                step("undo"), step("texts"));

        assertEquals("2\n$? + $1", answers.get(2));
        assertEquals("1\n2\n$1 + $2", answers.get(4));
    }

    // ---- All the exports, on one notebook ------------------------------------------------------------------------------

    @Test
    void everyExportWorksOnTheSameNotebookFromOneCallToTheNext() throws Exception {
        List<String> answers = assertSameOnBothSides(step("snapshot"), step("texts"), step("append", "1"),
                step("insertBefore", 1, "2"), step("insertAfter", 2, "$1 + $2"), step("edit", 1, "√2"), step("texts"),
                step("move", 3, 1), step("deleteLine", 2), step("snapshot"), step("texts"), step("undo"), step("redo"),
                step("setMode", "FRACTION"), step("clear"), step("texts"), step("undo"), step("texts"));

        assertEquals(CLEAN + "]}", answers.get(0));
        assertEquals("", answers.get(1));
        assertEquals("√2\n1\n$1 + $2", answers.get(6));
        assertEquals("", answers.get(15));
    }

    @Test
    void undoAndRedoWithoutHistoryChangeNothingOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("undo"), step("redo"), step("load", "1", "AUTO"), step("undo"), step("redo"));

        assertEquals(CLEAN + "]}", answers.get(0));
        assertEquals(CLEAN + "]}", answers.get(1));
        assertEquals(answers.get(2), answers.get(3));
        assertEquals(answers.get(2), answers.get(4));
    }

    // ---- Refusals -------------------------------------------------------------------------------------------------------

    @Test
    void refusalsGiveTheSameMessageOnBothSides() throws Exception {
        String longest = "# " + "x".repeat(16382);
        List<String> answers = assertSameOnBothSides(step("load", "1\n2", "AUTO"), step("deleteLine", 99), step("edit", 0, "5"),
                step("insertBefore", 3, "5"), step("insertAfter", -1, "5"), step("move", 1, 3), step("move", 3, 1),
                step("setMode", "BOGUS"), step("load", "2", "BOGUS"), step("append", longest + "x"), step("edit", 1, longest + "x"),
                step("load", longest + "x", "AUTO"), step("snapshot"), step("append", longest), step("texts"));

        String clean = answers.get(0);
        for (int refused = 1; refused <= 11; refused++) {
            assertTrue(answers.get(refused).contains("\"message\":\""), answers.get(refused));
            assertEquals(clean, answers.get(refused).replaceFirst("\"message\":\"(?:[^\"\\\\]|\\\\.)*\",", "\"message\":null,"));
        }
        assertEquals(clean, answers.get(12));
        assertEquals("1\n2\n" + longest, answers.get(14));
    }

    @Test
    void the5001stLineIsRefusedWithTheSameMessageOnBothSides() throws Exception {
        String full = String.join("\n", Collections.nCopies(5000, "1"));
        String tooMany = full + "\n1";
        List<String> answers = assertSameOnBothSides(step("load", tooMany, "AUTO"), step("load", full, "AUTO"), step("append", "2"),
                step("insertBefore", 1, "2"), step("insertAfter", 5000, "2"));

        assertTrue(answers.get(0).contains("\"message\":\"Le carnet ne peut pas dépasser 5000 lignes.\""), answers.get(0));
        assertTrue(answers.get(2).contains("\"message\":\"Le carnet ne peut pas dépasser 5000 lignes.\""));
    }

    // ---- Hostile strings ------------------------------------------------------------------------------------------------

    @Test
    void escapesAndSurrogatesAreTheSameOnBothSides() throws Exception {
        List<String> answers = assertSameOnBothSides(step("load", "\"", "AUTO"), step("load", "\\", "AUTO"), step("load", "1\t+ 1", "AUTO"),
                step("load", "\u0001", "AUTO"), step("load", "\u001f", "AUTO"), step("load", "1 😀 2", "AUTO"),
                step("load", "\uD83D", "AUTO"), step("load", "<img src=x onerror=1>", "AUTO"), step("load", "1\r\n2\r+ 2", "AUTO"),
                step("edit", 2, "3\n+\r3"), step("append", "4\n+\r4"), step("texts"));

        assertTrue(answers.get(5).contains("\"errorStart\":2,\"errorEnd\":4"), answers.get(5));
        assertTrue(answers.get(6).contains("\"text\":\"\uD83D\""), "An unpaired surrogate passes as it is.");
        assertEquals("1 \n3 + 3\n4 + 4", answers.get(11));
    }

    // ---- The comparison itself ------------------------------------------------------------------------------------------

    /** A Java object or a number handed to JavaScript would turn into text in a comparison, and hide the defect. */
    @Test
    void anAnswerThatIsNotAJavaScriptStringFailsTheComparison() throws Exception {
        Path stub = Files.writeString(temporaryDirectory.resolve("calc-stub.js"), "exports.snapshot = function () { return {}; };\n", UTF_8);
        NodeRun run = onNode(stub.toString(), List.of(step("snapshot")));
        assertEquals(1, run.exitCode(), run::toString);
        assertTrue(run.stderr().contains("not a JavaScript string"), run::toString);
    }

    @Test
    void aSnapshotThatIsNotJsonFailsTheComparison() throws Exception {
        Path stub = Files.writeString(temporaryDirectory.resolve("calc-stub.js"), "exports.snapshot = function () { return '{'; };\n", UTF_8);
        NodeRun run = onNode(stub.toString(), List.of(step("snapshot")));
        assertEquals(1, run.exitCode(), run::toString);
    }

    @Test
    void theAnswersCrossAsTheyAre() throws Exception {
        Path stub = Files.writeString(temporaryDirectory.resolve("calc-stub.js"),
                "exports.texts = function (first, second) { return first + '|' + second + '|' + (typeof second); };\n", UTF_8);
        NodeRun run = onNode(stub.toString(), List.of(step("texts", "√\uD83D\n\"\\", 7)));
        assertEquals(0, run.exitCode(), run::toString);
        assertEquals(List.of("√\uD83D\n\"\\|7|number"), run.answers());
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------------

    private List<String> assertSameOnBothSides(Step... steps) throws Exception {
        List<Step> scenario = List.of(steps);
        NodeRun run = onNode(CALC_JS, scenario);
        assertEquals(0, run.exitCode(), () -> "calc.js must have every export of the contract: " + run.stderr());
        assertEquals(scenario.size(), run.answers().size(), run::toString);

        List<String> jvm = onTheJvm(scenario);
        for (int index = 0; index < scenario.size(); index++) {
            assertEquals(jvm.get(index), run.answers().get(index), "step " + index + ": " + describe(scenario.get(index)));
        }
        return run.answers();
    }

    private static String describe(Step step) {
        String arguments = step.arguments().toString();
        return step.export() + arguments.substring(0, Math.min(arguments.length(), 80));
    }

    private static List<String> onTheJvm(List<Step> scenario) throws Exception {
        assertTrue(Files.isDirectory(WEB_CLASSES), WEB_CLASSES + " must exist: :web:webTest compiles the web module.");
        assertTrue(Files.isDirectory(CORE_CLASSES), CORE_CLASSES + " must exist: generateJavaScript reads the core.");
        URL[] classes = {WEB_CLASSES.toUri().toURL(), CORE_CLASSES.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classes, ClassLoader.getPlatformClassLoader())) {
            Class<?> type = Class.forName("calc.web.WebNotebook", true, loader);
            Object notebook = type.getConstructor().newInstance();
            List<String> answers = new ArrayList<>();
            for (Step step : scenario) {
                Class<?>[] parameters = step.arguments().stream().map(argument -> argument instanceof Integer ? int.class : String.class)
                        .toArray(Class<?>[]::new);
                String method = step.export().equals("deleteLine") ? "delete" : step.export();
                answers.add((String) type.getMethod(method, parameters).invoke(notebook, step.arguments().toArray()));
            }
            return answers;
        }
    }

    private NodeRun onNode(String calcFile, List<Step> scenario) throws IOException, InterruptedException {
        Path runner = Files.writeString(temporaryDirectory.resolve("run-scenario.js"), RUNNER, UTF_8);
        Path scenarioFile = Files.writeString(temporaryDirectory.resolve("scenario.json"), json(scenario), UTF_8);
        Path output = temporaryDirectory.resolve("answers.txt");
        Files.deleteIfExists(output);
        Path stderr = temporaryDirectory.resolve("node.err");
        Path stdout = temporaryDirectory.resolve("node.out");

        List<String> command = List.of("node", runner.toString(), calcFile, scenarioFile.toString(), output.toString());
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(NODE_WATCHDOG_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            fail("node did not finish: " + command);
        }
        List<String> answers = new ArrayList<>();
        if (process.exitValue() == 0) {
            assertTrue(Files.isRegularFile(output), "The scenario must write its answers.");
            for (String answer : Files.readString(output, UTF_8).split("\n", -1)) {
                answers.add(decode(answer));
            }
        }
        return new NodeRun(process.exitValue(), Files.readString(stderr, UTF_8), answers);
    }

    /** UTF-16 units read one by one: a charset decoder would replace an unpaired surrogate. */
    private static String decode(String base64) {
        return ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().toString();
    }

    /** The scenario as an array of {@code [export, ...arguments]}, in pure ASCII. */
    private static String json(List<Step> scenario) {
        StringJoiner steps = new StringJoiner(",", "[", "]");
        for (Step step : scenario) {
            StringJoiner call = new StringJoiner(",", "[", "]");
            call.add(jsonString(step.export()));
            for (Object argument : step.arguments()) {
                call.add(argument instanceof Integer ? argument.toString() : jsonString((String) argument));
            }
            steps.add(call.toString());
        }
        return steps.toString();
    }

    private static String jsonString(String value) {
        StringBuilder text = new StringBuilder("\"");
        for (char character : value.toCharArray()) {
            if (character < 0x20 || character > 0x7E || character == '"' || character == '\\') {
                text.append(String.format("\\u%04x", (int) character));
            } else {
                text.append(character);
            }
        }
        return text.append('"').toString();
    }
}
