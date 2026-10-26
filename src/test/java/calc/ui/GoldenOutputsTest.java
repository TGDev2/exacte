package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Replays {@code golden/engine-v2.tsv}, the reference outputs of the exact engine (E06), and checks that the engine and
 * the presenter still produce exactly the same displayed value, reusable value or error message for every line. The file
 * has the same 70 inputs as {@code golden/engine-v1.tsv} (version 1, kept for the record); every difference between the
 * two is listed and justified in docs/changements-comportement-E06.md (regenerate both with tools/golden-v2).
 */
@DisplayName("Sorties de référence du moteur")
class GoldenOutputsTest {
    private static final String RESOURCE = "/golden/engine-v2.tsv";
    private static final int EXPECTED_CASES = 70;
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("<U\\+([0-9A-F]{4})>");
    private static final Pattern REPEAT = Pattern.compile("<rep:(\\d+):([^>]*)>");
    private static final Pattern HASHED = Pattern.compile("len=\\d+ sha256=[0-9a-f]{64}");

    /** One line of the golden file; inputs are still in the notation documented in tools/baseline/README.md. */
    private record GoldenCase(int lineNumber, String operation, String first, String second,
                              String outcome, String display, String reusable) {
        String title() {
            return "ligne " + lineNumber + " : " + operation + " [" + first + "] [" + second + "] -> " + outcome;
        }
    }

    @Test
    @DisplayName("Le fichier de référence contient les 70 cas capturés")
    void goldenFileHoldsAllCapturedCases() throws IOException {
        assertEquals(EXPECTED_CASES, readCases().size(), "The golden file should keep its 70 captured cases.");
    }

    @TestFactory
    @DisplayName("Chaque ligne du fichier de référence est reproduite à l'identique")
    List<DynamicTest> everyGoldenLineIsReproduced() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (GoldenCase goldenCase : readCases()) {
            tests.add(DynamicTest.dynamicTest(goldenCase.title(), () -> verify(goldenCase)));
        }
        return tests;
    }

    private static void verify(GoldenCase goldenCase) throws CalculationException, NoSuchAlgorithmException {
        String first = expand(goldenCase.first());
        String second = expand(goldenCase.second());
        Operation operation = goldenCase.operation().equals("(none)") ? null : Operation.valueOf(goldenCase.operation());
        CalculatorEngine engine = new CalculatorEngine();

        if (goldenCase.outcome().equals("ERROR")) {
            CalculationException exception = assertThrows(
                    CalculationException.class,
                    () -> engine.calculate(operation, first, second),
                    "The engine should still refuse " + goldenCase.title());
            assertEquals(goldenCase.display(), exception.getMessage(), "The error message should be unchanged.");
            assertEquals("", goldenCase.reusable(), "An error has no reusable value.");
            return;
        }

        assertEquals("OK", goldenCase.outcome(), "Unknown outcome in the golden file.");
        CalculationRecord record = engine.calculate(operation, first, second);
        String display = record.getFormattedResult();
        String reusable = reusableValue(operation, first, second);
        assertEquals(record.getReusableValue(), reusable, "The presenter reuses the canonical text of the result.");
        assertEquals(goldenCase.display(), comparable(goldenCase.display(), display), "The displayed result should be unchanged.");
        assertEquals(goldenCase.reusable(), comparable(goldenCase.reusable(), reusable), "The reusable value should be unchanged.");
    }

    /** Large results are stored as "len=... sha256=..."; summarise the actual value the same way before comparing. */
    private static String comparable(String expected, String actual) throws NoSuchAlgorithmException {
        if (!HASHED.matcher(expected).matches()) {
            return actual;
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(actual.getBytes(StandardCharsets.UTF_8));
        return "len=" + actual.length() + " sha256=" + HexFormat.of().formatHex(digest);
    }

    /** Value the presenter puts back into the first field when the user reuses the last result. */
    private static String reusableValue(Operation operation, String first, String second) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(operation, "", "");
        presenter.onCalculate(first, second);
        return presenter.onReuseLastResult("", "").firstInput();
    }

    private static List<GoldenCase> readCases() throws IOException {
        String content;
        try (InputStream stream = GoldenOutputsTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "The golden file should be on the test classpath: " + RESOURCE);
            content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String[] lines = content.split("\n", -1);
        assertEquals("operation\tfirst\tsecond\toutcome\tdisplay\treusable", lines[0], "Unexpected golden file header.");
        List<GoldenCase> cases = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) {
                continue;
            }
            String[] columns = lines[index].split("\t", -1);
            assertEquals(6, columns.length, "Golden line " + (index + 1) + " should have 6 columns.");
            cases.add(new GoldenCase(index + 1, columns[0], columns[1], columns[2], columns[3], columns[4], columns[5]));
        }
        return cases;
    }

    /** Expands the &lt;U+XXXX&gt; and &lt;rep:N:text&gt; notations used in the golden file. */
    private static String expand(String notation) {
        Matcher repeats = REPEAT.matcher(notation);
        StringBuilder withRepeats = new StringBuilder();
        while (repeats.find()) {
            repeats.appendReplacement(withRepeats,
                    Matcher.quoteReplacement(repeats.group(2).repeat(Integer.parseInt(repeats.group(1)))));
        }
        repeats.appendTail(withRepeats);

        Matcher escapes = UNICODE_ESCAPE.matcher(withRepeats);
        StringBuilder result = new StringBuilder();
        while (escapes.find()) {
            escapes.appendReplacement(result,
                    Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(escapes.group(1), 16))));
        }
        escapes.appendTail(result);
        return result.toString();
    }
}
