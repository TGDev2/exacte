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
import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The four fields added to every line of the snapshot (name, sources, usedBy, detail) cross TeaVM unchanged: calc.js under
 * Node gives the very string that WebNotebook gives on the JVM, on « Devis cuisine » and on a notebook with every kind of
 * line, in the four modes. The general corpus is replayed by ParityAcceptanceTest, which compares whatever the snapshot
 * holds; this class also checks that the fields are there, so that it fails until they are.
 */
@DisplayName("Snapshot web : nom, sources, utilisations et détail identiques sous Node et sur la JVM")
class WebSnapshotDetailParityAcceptanceTest {
    private static final String CALC_JS = "web/build/generated/teavm/js/calc.js";
    private static final Path WEB_CLASSES = Path.of("web", "build", "classes", "java", "main");
    private static final Path CORE_CLASSES = Path.of("build", "classes", "java", "main");
    private static final long NODE_WATCHDOG_MINUTES = 5;
    private static final List<String> MODES = List.of("AUTO", "DECIMAL", "FRACTION", "SCIENTIFIC");

    private static final String DEVIS_CUISINE = String.join("\n", "# Devis cuisine", "plan = 1\u00a0250,00",
            "meubles = 3\u00a0480", "pose = 35 * 12,5", "total = plan + meubles + pose", "taux = 0,2", "total * (1 + taux)",
            "ans / 12");
    private static final String EVERY_KIND = String.join("\n", DEVIS_CUISINE, "", "   ", "√2", "√2-√2", "1/3", "10^40", "2 +",
            "x = 2 +", "1 / 0", "$99", "$?", "inconnue", "y = $2 * 2", "2 # a\u00a0\"b\\", "# note \"q\" \\ a\u00a0b", "1\t+ 1");

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
              JSON.parse(answer);
              return Buffer.from(answer, 'utf16le').toString('base64');
            });
            fs.writeFileSync(outputFile, answers.join('\\n'), 'utf8');
            """;

    @TempDir
    Path temporaryDirectory;

    private record Step(String export, List<String> arguments) {
    }

    private static Step step(String export, String... arguments) {
        return new Step(export, List.of(arguments));
    }

    @Test
    void theTotalOfTheKitchenQuoteIsTheSameOnBothSidesWithItsFourFields() throws Exception {
        List<String> answers = sameOnBothSides(step("load", DEVIS_CUISINE, "AUTO"), step("snapshot"));

        for (String answer : answers) {
            assertTrue(answer.contains("\"errorStart\":-1,\"errorEnd\":-1,\"name\":\"total\",\"sources\":[2,3,4],\"usedBy\":[7],"
                    + "\"detail\":[\"= 1250 + 3480 + 437,5\",\"= 5167,5\",\"Sources : lignes 2, 3 et 4\",\"Utilisée par 1 ligne\"]}"),
                    answer);
        }
    }

    @Test
    void everyKindOfLineIsTheSameOnBothSidesInEveryModeWithItsFourFields() throws Exception {
        List<Step> steps = new ArrayList<>();
        steps.add(step("load", EVERY_KIND, "AUTO"));
        for (String mode : MODES) {
            steps.add(step("setMode", mode));
        }
        List<String> answers = sameOnBothSides(steps.toArray(Step[]::new));

        for (String answer : answers) {
            int rows = answer.split("\\{\"number\":", -1).length - 1;
            assertTrue(rows >= 20, answer);
            assertEquals(rows, count(answer, "\"name\":"), "every line has a name: " + answer);
            assertEquals(rows, count(answer, "\"sources\":["), "every line has its sources: " + answer);
            assertEquals(rows, count(answer, "\"usedBy\":["), "every line has its users: " + answer);
            assertEquals(rows, count(answer, "\"detail\":["), "every line has its detail: " + answer);
        }
        assertTrue(answers.get(3).contains("\"detail\":[\"= 1/3\""), "the detail is shown in the mode: " + answers.get(3));
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------------

    private static int count(String text, String part) {
        int found = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            found++;
        }
        return found;
    }

    private List<String> sameOnBothSides(Step... steps) throws Exception {
        List<Step> scenario = List.of(steps);
        Path runner = Files.writeString(temporaryDirectory.resolve("run-scenario.js"), RUNNER, UTF_8);
        Path scenarioFile = Files.writeString(temporaryDirectory.resolve("scenario.json"), json(scenario), UTF_8);
        Path output = temporaryDirectory.resolve("answers.txt");
        Path stderr = temporaryDirectory.resolve("node.err");
        Path stdout = temporaryDirectory.resolve("node.out");
        List<String> command = List.of("node", runner.toString(), CALC_JS, scenarioFile.toString(), output.toString());
        Process process = new ProcessBuilder(command).redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(NODE_WATCHDOG_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor();
            fail("node did not finish: " + command);
        }
        assertEquals(0, process.exitValue(), () -> readOrEmpty(stderr));
        List<String> node = new ArrayList<>();
        for (String answer : Files.readString(output, UTF_8).split("\n", -1)) {
            node.add(ByteBuffer.wrap(Base64.getDecoder().decode(answer)).order(ByteOrder.LITTLE_ENDIAN).asCharBuffer().toString());
        }
        List<String> jvm = onTheJvm(scenario);
        assertEquals(scenario.size(), node.size());
        for (int index = 0; index < scenario.size(); index++) {
            assertEquals(jvm.get(index), node.get(index), "step " + index + ": " + scenario.get(index).export());
        }
        return node;
    }

    private static String readOrEmpty(Path file) {
        try {
            return Files.readString(file, UTF_8);
        } catch (IOException unreadable) {
            return unreadable.toString();
        }
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
                Class<?>[] parameters = step.arguments().stream().map(argument -> String.class).toArray(Class<?>[]::new);
                answers.add((String) type.getMethod(step.export(), parameters).invoke(notebook, step.arguments().toArray()));
            }
            return answers;
        }
    }

    /** The scenario as an array of {@code [export, ...arguments]}, in pure ASCII. */
    private static String json(List<Step> scenario) {
        StringJoiner steps = new StringJoiner(",", "[", "]");
        for (Step step : scenario) {
            StringJoiner call = new StringJoiner(",", "[", "]");
            call.add(jsonString(step.export()));
            step.arguments().forEach(argument -> call.add(jsonString(argument)));
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
