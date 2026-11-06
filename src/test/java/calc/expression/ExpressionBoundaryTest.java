package calc.expression;

import static calc.expression.ExpressionTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.number.Rational;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The edges of the limits, of the numbering of lines and of the token readers of the expression package. */
class ExpressionBoundaryTest {
    private static final ExpressionParser TWO_LEVELS = new ExpressionParser(new ExpressionLimits(1000, 2, 500));

    @Test
    void theLimitsAcceptTheirSmallestAndLargestValues() {
        assertEquals(1, new ExpressionLimits(1, 1, 1).maxLineLength());
        assertEquals(100, new ExpressionLimits(1, 100, 1).maxDepth());
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(10, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(10, 101, 10));
        assertThrows(IllegalArgumentException.class, () -> new ExpressionLimits(10, 10, 0));
    }

    @Test
    void aSpanKnowsItsLengthAndItsBounds() {
        assertEquals(3, new Span(2, 5).length());
        assertEquals(0, new Span(4, 4).length());
        assertEquals(new Span(1, 7), Span.between(new Span(1, 2), new Span(5, 7)));
        assertThrows(IllegalArgumentException.class, () -> new Span(-1, 2));
        assertThrows(IllegalArgumentException.class, () -> new Span(3, 2));
    }

    @Test
    void aNodeRefusesMissingParts() {
        Node one = new Node.NumberLiteral(Rational.ONE, Span.NONE);

        assertThrows(NullPointerException.class, () -> new Node.Binary(null, one, one, Span.NONE));
        assertThrows(NullPointerException.class, () -> new Node.Binary(Node.Operator.ADD, null, one, Span.NONE));
        assertThrows(NullPointerException.class, () -> new Node.Sqrt(null, Span.NONE));
        assertThrows(NullPointerException.class, () -> new Node.Assignment("x", null, Span.NONE));
        assertThrows(IllegalArgumentException.class, () -> new Node.Assignment("2x", one, Span.NONE));
        assertThrows(NullPointerException.class, () -> new Node.Unary(Node.Sign.MINUS, null, Span.NONE));
    }

    @Test
    void nestingIsCountedAgainstTheDepthOfTheEnclosingConstructOnly() {
        // Each construct is entered and left, so any number of them in a row stays within two levels.
        for (String template : new String[] {"(1)+(1)+(1)+(1)", "-1+-1+-1+-1", "2^1+2^1+2^1+2^1", "sqrt(1)+sqrt(1)+sqrt(1)+sqrt(1)",
                "{v}1+{v}1+{v}1+{v}1", "racine(1)*(1)^-1*{v}1-(-1)"}) {
            assertDoesNotThrow(() -> TWO_LEVELS.parse(line(template)), template);
        }
    }

    @Test
    void theDepthLimitIsExceededByTheNextLevel() {
        assertDoesNotThrow(() -> TWO_LEVELS.parse("((1))"));
        assertEquals(ExpressionError.TOO_DEEP, assertThrows(ExpressionException.class, () -> TWO_LEVELS.parse("(((1)))")).error());
    }

    @Test
    void theNumberOfNodesIsLimitedToTheMaximumIncluded() throws ExpressionException {
        ExpressionParser threeNodes = new ExpressionParser(new ExpressionLimits(1000, 10, 3));

        assertEquals(Node.Operator.ADD, ((Node.Binary) threeNodes.parse("1+2").node()).operator());
        assertEquals(ExpressionError.TOO_MANY_NODES, assertThrows(ExpressionException.class, () -> threeNodes.parse("1+2+3")).error());
    }

    @Test
    void twoNumbersSeparatedByATabAreNotTakenForGroupedThousands() {
        ExpressionException tab = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine("1\t000"));
        ExpressionException space = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine("1 000"));

        assertEquals("Opérateur manquant.", tab.getMessage());
        assertEquals("Opérateur manquant (pour séparer les milliers, utilisez une espace insécable).", space.getMessage());
    }

    @Test
    void aMarkOfDecimalsAloneIsNotANumber() throws Exception {
        for (String text : new String[] {".", ",", "1+.", "1+,"}) {
            assertEquals(ExpressionError.UNEXPECTED_CHARACTER,
                    assertThrows(ExpressionException.class, () -> Lexer.tokenize(text), text).error(), text);
        }
        assertEquals(Rational.of(1, 2), Lexer.tokenize(".5").get(0).number());
        assertEquals(Rational.of(1, 2), Lexer.tokenize("2*,5").get(2).number());
    }

    @Test
    void aDollarAloneOrBeforeAQuestionMarkIsReadWhereverItIs() {
        ExpressionException alone = assertThrows(ExpressionException.class, () -> Lexer.tokenize("1+$"));
        ExpressionException deleted = assertThrows(ExpressionException.class, () -> Lexer.tokenize("1 + $?"));

        assertEquals(ExpressionError.INVALID_REFERENCE, alone.error());
        assertEquals(new Span(2, 3), alone.span());
        assertEquals(ExpressionError.DELETED_REFERENCE, deleted.error());
        assertEquals(new Span(4, 6), deleted.span());
    }

    @Test
    void theNumberOfAReferenceIgnoresLeadingZerosAndStopsAtTheLargestInt() {
        assertEquals(0, Lexer.referenceNumber("$0"));
        assertEquals(0, Lexer.referenceNumber("$000"));
        assertEquals(7, Lexer.referenceNumber("$007"));
        assertEquals(1, Lexer.referenceNumber("$00000000001"));
        assertEquals(Integer.MAX_VALUE, Lexer.referenceNumber("$2147483647"));
        assertEquals(-1, Lexer.referenceNumber("$2147483648"));
        assertEquals(-1, Lexer.referenceNumber("$99999999999"));
    }

    @Test
    void aReferenceToTheLargestLineNumberIsAccepted() throws ExpressionException {
        assertEquals(Integer.MAX_VALUE, Lexer.tokenize("$2147483647").get(0).reference());
        assertEquals(ExpressionError.INVALID_REFERENCE,
                assertThrows(ExpressionException.class, () -> Lexer.tokenize("$0")).error());
    }

    @Test
    void aDollarAtTheEndOfALineIsLeftAsItIsByTheRewriter() {
        LineRenumbering shift = LineRenumbering.table(Map.of(1, 2), Set.of());

        assertEquals("$2+$", ReferenceRewriter.rewrite("$1+$", shift));
        assertEquals("$", ReferenceRewriter.rewrite("$", shift));
        assertEquals("$a $2", ReferenceRewriter.rewrite("$a $1", shift));
    }

    @Test
    void aRenumberingTableAcceptsLineOneAndRefusesLineZero() {
        LineRenumbering table = LineRenumbering.table(Map.of(1, 2), Set.of(3));

        assertEquals(OptionalInt.of(2), table.newNumber(1));
        assertEquals(OptionalInt.empty(), table.newNumber(3));
        assertEquals(OptionalInt.of(9), table.newNumber(9));
        assertThrows(IllegalArgumentException.class, () -> LineRenumbering.table(Map.of(0, 1), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> LineRenumbering.table(Map.of(1, 0), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> LineRenumbering.table(Map.of(1, 2), Set.of(1)));
    }

    @Test
    void aResolverAcceptsLineOneAndRefusesLineZero() {
        TableResolver resolver = TableResolver.empty();

        assertDoesNotThrow(() -> resolver.withLine(1, calc.number.CertifiedValue.ONE));
        assertThrows(IllegalArgumentException.class, () -> resolver.withLine(0, calc.number.CertifiedValue.ONE));
    }
}
