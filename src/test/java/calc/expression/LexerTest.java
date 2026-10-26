package calc.expression;

import static calc.expression.ExpressionTestSupport.line;
import static org.junit.jupiter.api.Assertions.assertEquals;

import calc.number.Rational;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tokens, their source text and their spans. */
class LexerTest {
    @Test
    void anAssignmentWithAComment() throws Exception {
        List<Token> tokens = Lexer.tokenize("taux = 0,2 # TVA");

        assertEquals(List.of(Token.Type.IDENTIFIER, Token.Type.EQUALS, Token.Type.NUMBER, Token.Type.COMMENT),
                tokens.stream().map(Token::type).toList());
        assertEquals(List.of(new Span(0, 4), new Span(5, 6), new Span(7, 10), new Span(11, 16)),
                tokens.stream().map(Token::span).toList());
        assertEquals(List.of("taux", "=", "0,2", "# TVA"), tokens.stream().map(Token::text).toList());
        assertEquals(Rational.parse("1/5"), tokens.get(2).number());
    }

    @Test
    void everyOperatorInAsciiAndUnicode() throws Exception {
        List<Token> tokens = Lexer.tokenize(line("+ - {-} * {x} / {:} ^ {v} ( ) ="));

        assertEquals(List.of(Token.Type.PLUS, Token.Type.MINUS, Token.Type.MINUS, Token.Type.TIMES, Token.Type.TIMES,
                        Token.Type.DIVIDE, Token.Type.DIVIDE, Token.Type.POWER, Token.Type.ROOT, Token.Type.LEFT_PARENTHESIS,
                        Token.Type.RIGHT_PARENTHESIS, Token.Type.EQUALS),
                tokens.stream().map(Token::type).toList());
    }

    @Test
    void numbersAreReadAsAWhole() throws Exception {
        List<Token> grouped = Lexer.tokenize(line("1{nb}000{nnb}000,25e-2+3"));
        assertEquals(Token.Type.NUMBER, grouped.get(0).type());
        assertEquals(new Span(0, 15), grouped.get(0).span());
        assertEquals(Rational.parse("10000.0025"), grouped.get(0).number());

        // A no-break space that is not between two digits only separates tokens.
        List<Token> separated = Lexer.tokenize(line("2{nb}+{nb}3"));
        assertEquals(List.of(new Span(0, 1), new Span(2, 3), new Span(4, 5)),
                separated.stream().map(Token::span).toList());

        // "2e3" is one number; "2e" and "2ex" are the number 2 followed by a name.
        assertEquals(List.of(Token.Type.NUMBER), Lexer.tokenize("2e3").stream().map(Token::type).toList());
        assertEquals(List.of(Token.Type.NUMBER, Token.Type.IDENTIFIER),
                Lexer.tokenize("2ex").stream().map(Token::type).toList());
        assertEquals(new Span(1, 3), Lexer.tokenize("2ex").get(1).span());
        assertEquals(List.of(Token.Type.NUMBER), Lexer.tokenize(",5").stream().map(Token::type).toList());
    }

    @Test
    void reservedWordsAndReferences() throws Exception {
        List<Token> tokens = Lexer.tokenize("Ans RACINE Sqrt answer $12");

        assertEquals(List.of(Token.Type.ANS, Token.Type.SQRT_WORD, Token.Type.SQRT_WORD, Token.Type.IDENTIFIER,
                Token.Type.REFERENCE), tokens.stream().map(Token::type).toList());
        assertEquals(12, tokens.get(4).reference());
        assertEquals(new Span(23, 26), tokens.get(4).span());
    }

    @Test
    void namesTakeUnicodeLettersDigitsAndCombiningMarks() throws Exception {
        String decomposed = "cafe" + (char) 0x0301 + "_2";
        List<Token> tokens = Lexer.tokenize(decomposed + " + " + (char) 0x03C0);

        assertEquals(Token.Type.IDENTIFIER, tokens.get(0).type());
        assertEquals(decomposed, tokens.get(0).text());
        assertEquals(Token.Type.IDENTIFIER, tokens.get(2).type());
    }
}
