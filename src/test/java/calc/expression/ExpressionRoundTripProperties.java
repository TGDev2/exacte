package calc.expression;

import static calc.expression.ExpressionTestSupport.withoutPositions;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayDeque;
import java.util.Deque;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Round trip: a random tree, written by {@link CanonicalPrinter} and read back by {@link ExpressionParser}, is the same
 * tree (positions aside). This checks the precedence, the associativity and the minimal parentheses of the printer
 * against the parser, for every shape of tree.
 */
@Tag("property")
class ExpressionRoundTripProperties {
    // Long lines are allowed: the property is about structure, not about the length limit.
    private static final ExpressionParser PARSER = new ExpressionParser(new ExpressionLimits(100_000, 100, 500));

    @Provide
    Arbitrary<Node> lines() {
        return ExpressionGenerators.lines();
    }

    @Property
    void printedTreesParseBackToTheSameTree(@ForAll("lines") Node tree) throws ExpressionException {
        String printed = CanonicalPrinter.print(tree);

        ParsedLine parsed = PARSER.parse(printed);

        assertEquals(withoutPositions(tree), withoutPositions(parsed.tree().orElseThrow()), printed);
        assertEquals(tree instanceof Node.Assignment ? ParsedLine.Kind.ASSIGNMENT : ParsedLine.Kind.EXPRESSION,
                parsed.kind());
    }

    @Property
    void theCanonicalTextIsAFixedPoint(@ForAll("lines") Node tree) throws ExpressionException {
        String printed = CanonicalPrinter.print(tree);

        assertEquals(printed, CanonicalPrinter.print(PARSER.parse(printed).tree().orElseThrow()));
    }

    @Property
    void theSpanOfEveryNodeReadsBackAsThatNode(@ForAll("lines") Node tree) throws ExpressionException {
        String printed = CanonicalPrinter.print(tree);
        Deque<Node> nodes = new ArrayDeque<>();
        nodes.push(PARSER.parse(printed).tree().orElseThrow());

        while (!nodes.isEmpty()) {
            Node node = nodes.pop();
            String source = printed.substring(node.span().start(), node.span().end());
            assertEquals(withoutPositions(node), withoutPositions(PARSER.parse(source).tree().orElseThrow()),
                    "'" + source + "' in '" + printed + "'");
            switch (node) {
                case Node.Unary unary -> nodes.push(unary.operand());
                case Node.Binary binary -> {
                    nodes.push(binary.left());
                    nodes.push(binary.right());
                }
                case Node.Sqrt sqrt -> nodes.push(sqrt.operand());
                case Node.Assignment assignment -> nodes.push(assignment.value());
                default -> {
                    // A leaf.
                }
            }
        }
    }
}
