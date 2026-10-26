package calc.expression;

import static calc.expression.ExpressionTestSupport.line;
import static calc.expression.ExpressionTestSupport.withoutPositions;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.Rational;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The canonical text of a line: the fewest parentheses that keep the same tree. */
class CanonicalPrinterTest {
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "-2^2                  | -2^2",
            "(-2)^2                | (-2)^2",
            "2^3^2                 | 2^3^2",
            "(2^3)^2               | (2^3)^2",
            "2^-1                  | 2^-1",
            "2^(-1)                | 2^-1",
            "2^(1 + 1)             | 2^(1 + 1)",
            "a - (b - c)           | a - (b - c)",
            "(a - b) - c           | a - b - c",
            "a - (b + c)           | a - (b + c)",
            "(a * b) + c           | a * b + c",
            "a * (b + c)           | a * (b + c)",
            "a / (b * c)           | a / (b * c)",
            "-(a + b)              | -(a + b)",
            "-(a * b)              | -(a * b)",
            "(-a) * b              | -a * b",
            "2 * -3                | 2 * -3",
            "2 - -3                | 2 - -3",
            "--2                   | --2",
            "+(+2)                 | ++2",
            "2 {x} 3 {:} 4 {-} 1   | 2 * 3 / 4 - 1",
            "sqrt(2 + 2)           | {v}(2 + 2)",
            "racine(2)             | {v}2",
            "{v}{v}16              | {v}{v}16",
            "{v}(-4)               | {v}(-4)",
            "{v}(2^2)              | {v}(2^2)",
            "{v}2^2                | {v}2^2",
            "sqrt(ans) + {v}$3     | {v}ans + {v}$3",
            "taux = 0.20           | taux = 0,2",
            "x=(1)                 | x = 1",
            "1e29                  | 100000000000000000000000000000",
            "1e30                  | 1e30",
            "1,5e-40               | 1,5e-40",
            "1{nb}000,50           | 1000,5",
            "0,000                 | 0",
            "$007 # comment        | $7",
    })
    void canonicalTexts(String source, String expected) throws ExpressionException {
        Node tree = ExpressionParser.parseLine(line(source)).tree().orElseThrow();
        String printed = CanonicalPrinter.print(tree);

        assertEquals(line(expected), printed);
        assertEquals(withoutPositions(tree), withoutPositions(ExpressionParser.parseLine(printed).tree().orElseThrow()));
    }

    @Test
    void anAssignmentInsideAnExpressionCannotBeBuilt() throws Exception {
        Node one = new Node.NumberLiteral(Rational.ONE, Span.NONE);
        Node.Assignment assignment = new Node.Assignment("x", one, Span.NONE);

        assertThrows(IllegalArgumentException.class, () -> new Node.Unary(Node.Sign.MINUS, assignment, Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Node.NumberLiteral(Rational.of(-1), Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Node.NumberLiteral(Rational.parse("1/3"), Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Node.Variable("ans", Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Node.LineRef(0, Span.NONE));
    }
}
