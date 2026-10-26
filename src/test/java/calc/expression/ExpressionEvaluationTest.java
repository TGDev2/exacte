package calc.expression;

import static calc.expression.ExpressionTestSupport.evaluate;
import static calc.expression.ExpressionTestSupport.exact;
import static calc.expression.ExpressionTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.number.CertifiedValue;
import calc.number.Rational;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tables of lines and their exact values: priorities, associativity, notations, names and references. */
class ExpressionEvaluationTest {
    private static final Resolver NAMES = TableResolver.empty()
            .withVariable("prix", exact("100"))
            .withVariable("taux", exact("0.2"))
            .withVariable("Prix", exact("7"))
            .withVariable("ann" + (char) 0x00E9 + "e2", exact("2026"))
            .withVariable("_x", exact("-1"))
            .withAns(exact("1"))
            .withLine(1, exact("10"))
            .withLine(3, exact("21"));

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(delimiter = '|', value = {
            // Priorities and associativity (docs/adr/0007-grammaire-expressions.md, section 1)
            "2+3*4            | 14",
            "-2^2             | -4",
            "2^3^2            | 512",
            "2^-1             | 1/2",
            "(1/3)*3          | 1",
            "--2              | 2",
            "-+-3             | 3",
            "+3               | 3",
            "(-2)^2           | 4",
            "-2^-2            | -1/4",
            "2^-2^2           | 1/16",
            "(2^3)^2          | 64",
            "2 - 3 - 4        | -5",
            "2 - (3 - 4)      | 3",
            "2 / 4 / 2        | 1/4",
            "1 + 2 * 3 ^ 2    | 19",
            "2 * -3           | -6",
            "2 - -3           | 5",
            "1/3              | 1/3",
            // Roots: '√' takes a primary, so √9^2 is (√9)^2
            "{v}9^2           | 9",
            "{v}(9)^2         | 9",
            "{v}16 * {v}(1/4) | 2",
            "{v}{v}16         | 2",
            "{v}(4) * 3       | 6",
            "-{v}4            | -2",
            // Decimal comma, point, exponent
            "0,1 + 0,2        | 3/10",
            "1.5 + 1,5        | 3",
            ",5 + 5,          | 11/2",
            "1,5e3            | 1500",
            "2E-3             | 1/500",
            "1e+2 - 1         | 99",
            // No-break spaces group thousands
            "1{nb}000,5 + 1{nnb}000 | 4001/2",
            "1{nb}234{nb}567 * 2    | 2469134",
            "{nb}2{nb}+{nnb}3{nb}   | 5",
            // Unicode operators
            "6 {x} 7 {:} 2 {-} 1    | 20",
            "{-}2^2                 | -4",
            "10 {:} 4               | 5/2",
            "(12,5 + 3) {x} 2 {:} 4 | 31/4",
            // Reserved words, in any case
            "sqrt(16) + racine(9)   | 7",
            "SQRT(16) + Racine(9) + ANS | 8",
            "ans                    | 1",
            // Variables (case sensitive) and references
            "prix * (1 + taux)      | 120",
            "Prix                   | 7",
            "_x * 2                 | -2",
            "$3 * 2                 | 42",
            "ans + $1               | 11",
            "$003                   | 21",
            // Comments after an expression
            "2 + 2 # total          | 4",
            "2 + 2 #                | 4",
    })
    void exactValues(String text, String expected) throws ExpressionException {
        CertifiedValue value = evaluate(text, NAMES);
        assertTrue(value.isExact(), text);
        assertEquals(exact(expected), value, text);
    }

    @Test
    void aUnicodeVariableName() throws ExpressionException {
        String name = "ann" + (char) 0x00E9 + "e2";
        assertEquals(exact("2026"), evaluate(name + " * 1", NAMES));
    }

    @Test
    void theRootOfTwoSquaredIsAnIntervalThatContainsTwo() throws ExpressionException {
        CertifiedValue value = evaluate("{v}2*{v}2", NAMES);
        assertFalse(value.isExact());
        assertTrue(value.contains(Rational.of(2)), value::toString);
        CertifiedValue sameWithFunctions = evaluate("sqrt(2) * racine(2)", NAMES);
        assertEquals(value, sameWithFunctions);
    }

    @Test
    void theRootOfTwoTimesThree() throws ExpressionException {
        CertifiedValue value = evaluate("{v}(2) * 3", NAMES);
        assertFalse(value.isExact());
        assertTrue(value.lower().multiply(value.lower()).compareTo(Rational.of(18)) <= 0);
        assertTrue(value.upper().multiply(value.upper()).compareTo(Rational.of(18)) >= 0);
    }

    @Test
    void anAssignmentNamesItsValue() throws ExpressionException {
        ParsedLine parsed = ExpressionParser.parseLine("taux = 0,2 # TVA");

        assertEquals(ParsedLine.Kind.ASSIGNMENT, parsed.kind());
        assertEquals(Optional.of("taux"), parsed.assignedName());
        assertEquals(Optional.of(" TVA"), parsed.commentText());
        assertEquals(exact("1/5"), Evaluator.evaluate(parsed.tree().orElseThrow(), NAMES));
    }

    @Test
    void anAssignmentMayUseNamesAndReferences() throws ExpressionException {
        ParsedLine parsed = ExpressionParser.parseLine("total = prix * (1 + taux) + $3");

        assertEquals(ParsedLine.Kind.ASSIGNMENT, parsed.kind());
        assertEquals(Optional.of("total"), parsed.assignedName());
        assertEquals(List.of("prix", "taux"), List.copyOf(parsed.variables()));
        assertEquals(List.of(3), parsed.lineReferences());
        assertEquals(exact("141"), Evaluator.evaluate(parsed.tree().orElseThrow(), NAMES));
    }

    @Test
    void commentsEmptyLinesAndKinds() throws ExpressionException {
        ParsedLine comment = ExpressionParser.parseLine("# Devis cuisine");
        assertEquals(ParsedLine.Kind.COMMENT, comment.kind());
        assertEquals(Optional.of(" Devis cuisine"), comment.commentText());
        assertTrue(comment.tree().isEmpty());

        ParsedLine indented = ExpressionParser.parseLine("   #$3 + @ ( reste libre");
        assertEquals(ParsedLine.Kind.COMMENT, indented.kind());
        assertEquals(List.of(), indented.lineReferences());

        for (String blank : List.of("", "   ", "\t", line("{nb}{nnb}"))) {
            ParsedLine empty = ExpressionParser.parseLine(blank);
            assertEquals(ParsedLine.Kind.EMPTY, empty.kind(), "'" + blank + "'");
            assertTrue(empty.commentText().isEmpty());
        }

        ParsedLine expression = ExpressionParser.parseLine("2 + 2 # total");
        assertEquals(ParsedLine.Kind.EXPRESSION, expression.kind());
        assertTrue(expression.assignedName().isEmpty());
        assertEquals(Optional.of(" total"), expression.commentText());
    }

    @Test
    void theAnalysisListsReferencesNamesAndAns() throws ExpressionException {
        ParsedLine parsed = ExpressionParser.parseLine("$3 + prix * $1 + ans - prix + $3 + Prix");

        assertEquals(List.of(3, 1, 3), parsed.lineReferences());
        assertEquals(List.of("prix", "Prix"), List.copyOf(parsed.variables()));
        assertTrue(parsed.usesAns());

        ParsedLine none = ExpressionParser.parseLine("1 + 2");
        assertEquals(List.of(), none.lineReferences());
        assertTrue(none.variables().isEmpty());
        assertFalse(none.usesAns());
    }

    @Test
    void reservedWordsAreNotVariables() throws ExpressionException {
        assertTrue(ExpressionParser.parseLine("ANS + 1").usesAns());
        assertTrue(ExpressionParser.parseLine("ANS + 1").variables().isEmpty());
        assertFalse(Lexer.isVariableName("ans"));
        assertFalse(Lexer.isVariableName("Sqrt"));
        assertFalse(Lexer.isVariableName("RACINE"));
        assertTrue(Lexer.isVariableName("answer"));
        assertTrue(Lexer.isVariableName("sqrt2"));
        assertTrue(Lexer.isVariableName("_"));
        assertFalse(Lexer.isVariableName("2x"));
        assertFalse(Lexer.isVariableName("a-b"));
        assertFalse(Lexer.isVariableName(""));
    }

    @Test
    void theEvaluatorUsesTheValuesOfTheResolverAsTheyAre() throws ExpressionException {
        CertifiedValue root = evaluate("{v}2", NAMES);
        Resolver resolver = TableResolver.empty().withVariable("r", root).withAns(root).withLine(2, root);

        assertEquals(root, evaluate("r", resolver));
        assertEquals(root, evaluate("ans", resolver));
        assertEquals(root, evaluate("$2", resolver));
        assertEquals(root.negate(), evaluate("-r", resolver));
    }
}
