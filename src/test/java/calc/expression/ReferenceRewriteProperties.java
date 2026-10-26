package calc.expression;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import calc.testing.TextGenerators;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.Tuple;
import net.jqwik.api.statistics.Statistics;

/**
 * Rewriting: renumbering the references of a line, then reading it, gives the renumbered references, and every character
 * outside the reference tokens is unchanged, byte for byte. The expected text is built from the tokens of the lexer, an
 * implementation independent of the rewriter's scan.
 */
@Tag("property")
class ReferenceRewriteProperties {
    private static final int LINES = 12;

    /** A renumbering table over the lines 1 to 12: some lines move (up to 40), some are deleted, the others stay. */
    record Table(Map<Integer, Integer> moved, Set<Integer> deleted) {
        LineRenumbering renumbering() {
            return LineRenumbering.table(moved, deleted);
        }
    }

    @Provide
    Arbitrary<Table> tables() {
        return Arbitraries.integers().between(0, 2).list().ofSize(LINES).flatMap(kinds -> {
            List<Arbitrary<Integer>> targets = new ArrayList<>();
            for (int line = 1; line <= LINES; line++) {
                targets.add(Arbitraries.integers().between(1, 40));
            }
            return Combinators.combine(targets).as(newNumbers -> {
                Map<Integer, Integer> moved = new HashMap<>();
                Set<Integer> deleted = new HashSet<>();
                for (int line = 1; line <= LINES; line++) {
                    switch (kinds.get(line - 1)) {
                        case 1 -> moved.put(line, newNumbers.get(line - 1));
                        case 2 -> deleted.add(line);
                        default -> {
                            // Unchanged.
                        }
                    }
                }
                return new Table(moved, deleted);
            });
        });
    }

    /** Valid lines with references, varied spacing, number notations, names and a comment that mentions references. */
    @Provide
    Arbitrary<String> linesWithReferences() {
        String nbsp = String.valueOf((char) 0x00A0);
        Arbitrary<String> operand = Arbitraries.frequencyOf(
                Tuple.of(5, Arbitraries.integers().between(1, LINES).map(line -> "$" + line)),
                Tuple.of(1, Arbitraries.integers().between(1, LINES).map(line -> "$0" + line)),
                Tuple.of(2, Arbitraries.of("1", "2,50", "1" + nbsp + "000,5", "3.25e-2", ",5", "007")),
                Tuple.of(1, Arbitraries.of("prix", "taux", "ans", "ANS", "(1 + $2)", "sqrt($3)", "racine( $4 )",
                        (char) 0x221A + "$5")));
        Arbitrary<String> operator = Arbitraries.of("+", " + ", "-", "  -  ", "*", " " + (char) 0x00D7 + " ", "/",
                (char) 0x00F7 + "", "^", " - -", "*-");
        Arbitrary<String> comment = Arbitraries.of("", "", " # voir $3 et $5", "#$1", " # note");
        Arbitrary<String> prefix = Arbitraries.of("", "", "total = ", "x=", "  ", "\t");
        return Combinators.combine(prefix, operand.list().ofMinSize(1).ofMaxSize(8), operator.list().ofSize(8), comment)
                .as((start, operands, operators, end) -> {
                    StringBuilder line = new StringBuilder(start).append(operands.get(0));
                    for (int index = 1; index < operands.size(); index++) {
                        line.append(operators.get(index)).append(operands.get(index));
                    }
                    return line.append(end).toString();
                });
    }

    @Property
    void renumberedLinesReadAsTheRenumberedReferences(@ForAll("linesWithReferences") String line, @ForAll("tables") Table table)
            throws ExpressionException {
        List<Token> references = Lexer.tokenize(line).stream().filter(token -> token.type() == Token.Type.REFERENCE).toList();
        ParsedLine original = ExpressionParser.parseLine(line);
        assertEquals(references.stream().map(Token::reference).toList(), original.lineReferences());

        String rewritten = ReferenceRewriter.rewrite(line, table.renumbering());

        // Expected: each reference token replaced, every other character copied.
        StringBuilder expected = new StringBuilder();
        List<Integer> renumbered = new ArrayList<>();
        int firstDeleted = -1;
        int copied = 0;
        for (Token reference : references) {
            String outside = line.substring(copied, reference.span().start());
            expected.append(outside);
            OptionalInt target = table.renumbering().newNumber(reference.reference());
            if (target.isEmpty()) {
                if (firstDeleted < 0) {
                    firstDeleted = expected.length();
                }
                expected.append("$?");
            } else {
                expected.append(target.getAsInt() == reference.reference() ? reference.text() : "$" + target.getAsInt());
                renumbered.add(target.getAsInt());
            }
            copied = reference.span().end();
        }
        expected.append(line.substring(copied));
        assertEquals(expected.toString(), rewritten);
        assertArrayEquals(expected.toString().getBytes(StandardCharsets.UTF_8), rewritten.getBytes(StandardCharsets.UTF_8));

        Statistics.label("deleted reference").collect(firstDeleted >= 0);
        Statistics.label("deleted reference").coverage(coverage -> {
            coverage.check(true).percentage(percent -> percent > 10);
            coverage.check(false).percentage(percent -> percent > 10);
        });
        Statistics.label("references").collect(references.size() > 1 ? "several" : references.size() == 1 ? "one" : "none");
        Statistics.label("references").coverage(coverage -> coverage.check("several").percentage(percent -> percent > 30));
        if (firstDeleted < 0) {
            ParsedLine read = ExpressionParser.parseLine(rewritten);
            assertEquals(renumbered, read.lineReferences());
            assertEquals(original.kind(), read.kind());
            assertEquals(original.variables(), read.variables());
            assertEquals(original.commentText(), read.commentText());
        } else {
            ExpressionException refusal = assertThrows(ExpressionException.class, () -> ExpressionParser.parseLine(rewritten));
            assertEquals(ExpressionError.DELETED_REFERENCE, refusal.error());
            assertEquals(new Span(firstDeleted, firstDeleted + 2), refusal.span());
        }
    }

    @Property
    void identityChangesNothing(@ForAll("linesWithReferences") String line) {
        assertEquals(line, ReferenceRewriter.rewrite(line, LineRenumbering.identity()));
    }

    /**
     * On any text, even one that does not parse, only the {@code $digits} runs before the first {@code #} change, as an
     * independent regular expression finds them.
     */
    @Property
    void anyTextChangesOnlyItsReferences(@ForAll("anyText") String text, @ForAll("tables") Table table) {
        int hash = text.indexOf('#');
        String code = hash < 0 ? text : text.substring(0, hash);
        Matcher matcher = Pattern.compile("\\$([0-9]+)").matcher(code);
        StringBuilder expected = new StringBuilder();
        while (matcher.find()) {
            String digits = matcher.group(1).replaceFirst("^0+(?=.)", "");
            String replacement = matcher.group();
            if (digits.length() <= 9 && Integer.parseInt(digits) >= 1) {
                OptionalInt target = table.renumbering().newNumber(Integer.parseInt(digits));
                if (target.isEmpty()) {
                    replacement = "$?";
                } else if (target.getAsInt() != Integer.parseInt(digits)) {
                    replacement = "$" + target.getAsInt();
                }
            }
            matcher.appendReplacement(expected, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expected);
        expected.append(text.substring(code.length()));

        assertEquals(expected.toString(), ReferenceRewriter.rewrite(text, table.renumbering()));
    }

    @Provide
    Arbitrary<String> anyText() {
        Arbitrary<String> dollars = Arbitraries.strings().withChars("$0123456789#?a +".toCharArray()).ofMaxLength(40);
        return Arbitraries.frequencyOf(
                Tuple.of(3, dollars),
                Tuple.of(2, TextGenerators.anyInput().filter(text -> text != null)),
                Tuple.of(2, linesWithReferences()));
    }
}
