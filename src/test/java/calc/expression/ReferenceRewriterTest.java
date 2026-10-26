package calc.expression;

import static calc.expression.ExpressionTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Renumbering the references of the original text, and nothing else. */
class ReferenceRewriterTest {
    private static final LineRenumbering THREE_TO_FOUR_FIVE_DELETED = LineRenumbering.table(Map.of(3, 4), Set.of(5));

    @Test
    void theExampleOfTheBrief() throws ExpressionException {
        String original = line("  $3+$5 {x} 1{nb}000,50e0   # voir $3 et $5");

        String rewritten = ReferenceRewriter.rewrite(original, THREE_TO_FOUR_FIVE_DELETED);

        assertEquals(line("  $4+$? {x} 1{nb}000,50e0   # voir $3 et $5"), rewritten);
        ExpressionException deleted = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine(rewritten));
        assertEquals(ExpressionError.DELETED_REFERENCE, deleted.error());
        assertEquals(new Span(5, 7), deleted.span());
        assertEquals("Référence vers une ligne supprimée.", deleted.getMessage());
    }

    @Test
    void renumberedReferencesParseToTheNewNumbers() throws ExpressionException {
        String rewritten = ReferenceRewriter.rewrite("total = $3 * (1 + $10) - $3", LineRenumbering.table(Map.of(3, 4, 10, 9), Set.of()));

        assertEquals("total = $4 * (1 + $9) - $4", rewritten);
        assertEquals(List.of(4, 9, 4), ExpressionParser.parseLine(rewritten).lineReferences());
    }

    @Test
    void unchangedNumbersKeepTheirTextAndTheIdentityChangesNothing() {
        String text = "$03 + $3 + $12 # $3";

        assertEquals(text, ReferenceRewriter.rewrite(text, LineRenumbering.identity()));
        assertEquals("$4 + $4 + $12 # $3", ReferenceRewriter.rewrite(text, THREE_TO_FOUR_FIVE_DELETED));
        assertEquals("$03 + $3 + $13 # $3", ReferenceRewriter.rewrite(text, line -> OptionalInt.of(line == 12 ? 13 : line)));
    }

    @Test
    void linesThatDoNotParseAreRewrittenToo() {
        assertEquals("@ ($4 +", ReferenceRewriter.rewrite("@ ($3 +", THREE_TO_FOUR_FIVE_DELETED));
        assertEquals("$? $0 $ $? $99999999999", ReferenceRewriter.rewrite("$5 $0 $ $? $99999999999", THREE_TO_FOUR_FIVE_DELETED));
        String tooLong = "$3+" + "1".repeat(2000);
        assertEquals("$4+" + "1".repeat(2000), ReferenceRewriter.rewrite(tooLong, THREE_TO_FOUR_FIVE_DELETED));
    }

    @Test
    void aTextWithoutReferenceIsReturnedAsItIs() {
        String text = line("prix {x} 1,20 # pas de r{nb}f{nnb}rence");
        String rewritten = ReferenceRewriter.rewrite(text, THREE_TO_FOUR_FIVE_DELETED);
        assertEquals(text, rewritten);
    }

    @Test
    void anInconsistentTableIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> LineRenumbering.table(Map.of(3, 0), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> LineRenumbering.table(Map.of(3, 4), Set.of(3)));
        assertThrows(IllegalArgumentException.class, () -> ReferenceRewriter.rewrite("$3", line -> OptionalInt.of(0)));
    }
}
