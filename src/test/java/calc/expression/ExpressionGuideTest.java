package calc.expression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The user guide says what the language does: its examples and its table of errors are run as written. */
class ExpressionGuideTest {
    private static final Path GUIDE = Path.of("docs", "GUIDE-EXPRESSIONS.md");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");

    @Test
    void theExamplesOfTheGuideEvaluateLineByLine() throws IOException, ExpressionException {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        int start = guide.indexOf("```text", guide.indexOf("## Exemples")) + "```text".length();
        List<String> lines = guide.substring(start, guide.indexOf("```", start)).strip().lines().toList();
        assertTrue(lines.size() >= 10, lines::toString);

        // A minimal notebook for the test: ans is the previous result, $n the line n, a variable its last assignment.
        TableResolver names = TableResolver.empty();
        List<CertifiedValue> results = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            ParsedLine parsed = ExpressionParser.parseLine(lines.get(index));
            if (parsed.tree().isPresent()) {
                CertifiedValue value = Evaluator.evaluate(parsed.tree().get(), names);
                names = names.withAns(value).withLine(index + 1, value);
                if (parsed.assignedName().isPresent()) {
                    names = names.withVariable(parsed.assignedName().get(), value);
                }
                results.add(value);
            }
        }
        // plan + meubles + pose, then TTC, then the monthly amount, as the comments of the guide say.
        assertEquals(ExpressionTestSupport.exact("5167.5"), results.get(3));
        assertEquals(ExpressionTestSupport.exact("6201"), results.get(5));
        assertEquals(ExpressionTestSupport.exact("516.75"), results.get(6));
        assertEquals(ExpressionTestSupport.exact("-4"), results.get(9));
        assertEquals(ExpressionTestSupport.exact("4"), results.get(10));
        assertEquals(ExpressionTestSupport.exact("10335"), results.get(11));
    }

    @Test
    void everyRowOfTheErrorTableGivesItsMessage() throws IOException {
        String guide = Files.readString(GUIDE, StandardCharsets.UTF_8);
        String table = guide.substring(guide.indexOf("## Erreurs fréquentes"), guide.indexOf("## Ce qui n'est pas"));
        int rows = 0;
        for (String row : table.lines().filter(line -> line.startsWith("| `")).toList()) {
            String[] cells = row.split("\\|");
            String message = cells[2].strip();
            Matcher examples = CODE.matcher(cells[1]);
            while (examples.find()) {
                String line = examples.group(1);
                ExpressionException refusal = assertThrows(ExpressionException.class,
                        () -> Evaluator.evaluate(ExpressionParser.parseLine(line).tree().orElseThrow(), TableResolver.empty()),
                        line);
                assertEquals(message, refusal.getMessage(), line);
                rows++;
            }
        }
        assertTrue(rows >= 16, "rows read: " + rows);
    }
}
