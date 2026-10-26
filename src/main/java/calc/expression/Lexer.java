package calc.expression;

import calc.engine.CalculationLimits;
import calc.engine.NumberLiterals;
import calc.number.NumericException;
import calc.number.Rational;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Cuts a line into {@link Token}s (docs/adr/0007-grammaire-expressions.md, section 1). A loop over the characters, with
 * no recursion: the line length is checked first, then every token is read from left to right and the first lexical
 * error stops the reading with its position. Numbers are read by the engine's {@link NumberLiterals}, so the language
 * accepts exactly the numbers of the operand fields.
 */
public final class Lexer {
    static final char MINUS_SIGN = (char) 0x2212;
    static final char MULTIPLICATION_SIGN = (char) 0x00D7;
    static final char DIVISION_SIGN = (char) 0x00F7;
    static final char ROOT_SIGN = (char) 0x221A;

    static final String NUMBER_TOO_LARGE_MESSAGE = "Nombre trop grand ou avec trop de décimales pour un calcul exact.";

    private Lexer() {
    }

    /** The tokens of {@code line} with the default limits; a comment, if any, is the last token. */
    public static List<Token> tokenize(String line) throws ExpressionException {
        return tokenize(line, ExpressionLimits.DEFAULT);
    }

    /** The tokens of {@code line}; a comment, if any, is the last token. */
    public static List<Token> tokenize(String line, ExpressionLimits limits) throws ExpressionException {
        if (line.length() > limits.maxLineLength()) {
            throw new ExpressionException(ExpressionError.LINE_TOO_LONG, new Span(limits.maxLineLength(), line.length()),
                    "La ligne est trop longue (" + limits.maxLineLength() + " caractères maximum).");
        }
        List<Token> tokens = new ArrayList<>();
        int length = line.length();
        int index = 0;
        while (index < length) {
            char c = line.charAt(index);
            if (isBlank(c)) {
                index++;
            } else if (c == '#') {
                tokens.add(Token.of(Token.Type.COMMENT, line, index, length));
                index = length;
            } else if (isDigit(c) || ((c == ',' || c == '.') && index + 1 < length && isDigit(line.charAt(index + 1)))) {
                Token number = number(line, index);
                tokens.add(number);
                index = number.span().end();
            } else if (c == '$') {
                Token reference = reference(line, index);
                tokens.add(reference);
                index = reference.span().end();
            } else if (isIdentifierStart(line.codePointAt(index))) {
                Token word = word(line, index);
                tokens.add(word);
                index = word.span().end();
            } else {
                Token.Type type = symbol(c);
                if (type == null) {
                    throw unexpectedCharacter(line, index);
                }
                tokens.add(Token.of(type, line, index, index + 1));
                index++;
            }
        }
        return tokens;
    }

    /**
     * True when {@code name} is a valid variable name: a letter or {@code _}, then letters, digits, combining marks or
     * {@code _}, and not a reserved word.
     */
    public static boolean isVariableName(String name) {
        if (name == null || name.isEmpty() || !isIdentifierStart(name.codePointAt(0))) {
            return false;
        }
        int index = Character.charCount(name.codePointAt(0));
        while (index < name.length()) {
            int codePoint = name.codePointAt(index);
            if (!isIdentifierPart(codePoint)) {
                return false;
            }
            index += Character.charCount(codePoint);
        }
        return reservedWord(name) == null;
    }

    /** {@code ' '}, tab and the two no-break spaces separate tokens. */
    static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || NumberLiterals.isThousandsSeparator(c);
    }

    static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentifierStart(int codePoint) {
        return codePoint == '_' || Character.isLetter(codePoint);
    }

    private static boolean isIdentifierPart(int codePoint) {
        int type = Character.getType(codePoint);
        return codePoint == '_' || Character.isLetterOrDigit(codePoint)
                || type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static Token.Type reservedWord(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "ans" -> Token.Type.ANS;
            case "sqrt", "racine" -> Token.Type.SQRT_WORD;
            default -> null;
        };
    }

    // Digits, decimal marks and separators placed between two digits, then a well-formed exponent; read as a whole.
    private static Token number(String line, int start) throws ExpressionException {
        int length = line.length();
        int end = start;
        while (end < length) {
            char c = line.charAt(end);
            boolean separator = NumberLiterals.isThousandsSeparator(c) && end > start && isDigit(line.charAt(end - 1))
                    && end + 1 < length && isDigit(line.charAt(end + 1));
            if (!isDigit(c) && c != ',' && c != '.' && !separator) {
                break;
            }
            end++;
        }
        if (end < length && (line.charAt(end) == 'e' || line.charAt(end) == 'E')) {
            int digits = end + 1;
            if (digits < length && (line.charAt(digits) == '+' || line.charAt(digits) == '-')) {
                digits++;
            }
            if (digits < length && isDigit(line.charAt(digits))) {
                while (digits < length && isDigit(line.charAt(digits))) {
                    digits++;
                }
                end = digits;
            }
        }
        String text = line.substring(start, end);
        Span span = new Span(start, end);
        try {
            return new Token(Token.Type.NUMBER, text, span, NumberLiterals.parse(text), 0);
        } catch (NumericException exception) {
            throw switch (exception.error()) {
                case EXPONENT_OUT_OF_RANGE -> new ExpressionException(ExpressionError.SCIENTIFIC_EXPONENT_OUT_OF_RANGE, span,
                        "L'exposant de la notation scientifique doit être compris entre -"
                                + CalculationLimits.MAX_SCIENTIFIC_EXPONENT + " et "
                                + CalculationLimits.MAX_SCIENTIFIC_EXPONENT + ".");
                case SIZE_LIMIT_EXCEEDED -> new ExpressionException(ExpressionError.NUMBER_TOO_LARGE, span,
                        NUMBER_TOO_LARGE_MESSAGE);
                // MALFORMED_INPUT; a number token has no '/', so no other code can come from the reader.
                default -> new ExpressionException(ExpressionError.INVALID_NUMBER, span,
                        "Nombre invalide « " + text + " ».");
            };
        }
    }

    private static Token reference(String line, int start) throws ExpressionException {
        int length = line.length();
        if (start + 1 < length && line.charAt(start + 1) == '?') {
            throw new ExpressionException(ExpressionError.DELETED_REFERENCE, new Span(start, start + 2),
                    "Référence vers une ligne supprimée.");
        }
        int end = start + 1;
        while (end < length && isDigit(line.charAt(end))) {
            end++;
        }
        Span span = new Span(start, end);
        if (end == start + 1) {
            throw new ExpressionException(ExpressionError.INVALID_REFERENCE, span,
                    "Numéro de ligne attendu après « $ » (par exemple $3).");
        }
        String text = line.substring(start, end);
        int number = referenceNumber(text);
        if (number < 0) {
            throw new ExpressionException(ExpressionError.INVALID_REFERENCE, span,
                    "Référence invalide « " + text + " » : numéro de ligne trop grand.");
        }
        if (number == 0) {
            throw new ExpressionException(ExpressionError.INVALID_REFERENCE, span,
                    "Référence invalide « " + text + " » : les lignes sont numérotées à partir de 1.");
        }
        return new Token(Token.Type.REFERENCE, text, span, null, number);
    }

    /** The number of a reference text {@code $ddd}, or -1 when it does not fit an {@code int}. */
    static int referenceNumber(String text) {
        int first = 1;
        while (first < text.length() - 1 && text.charAt(first) == '0') {
            first++;
        }
        String digits = text.substring(first);
        if (digits.length() > 10) {
            return -1;
        }
        long value = Long.parseLong(digits);
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    private static Token word(String line, int start) {
        int end = start + Character.charCount(line.codePointAt(start));
        while (end < line.length() && isIdentifierPart(line.codePointAt(end))) {
            end += Character.charCount(line.codePointAt(end));
        }
        String text = line.substring(start, end);
        Token.Type reserved = reservedWord(text);
        return Token.of(reserved == null ? Token.Type.IDENTIFIER : reserved, line, start, end);
    }

    private static Token.Type symbol(char c) {
        return switch (c) {
            case '+' -> Token.Type.PLUS;
            case '-', MINUS_SIGN -> Token.Type.MINUS;
            case '*', MULTIPLICATION_SIGN -> Token.Type.TIMES;
            case '/', DIVISION_SIGN -> Token.Type.DIVIDE;
            case '^' -> Token.Type.POWER;
            case ROOT_SIGN -> Token.Type.ROOT;
            case '(' -> Token.Type.LEFT_PARENTHESIS;
            case ')' -> Token.Type.RIGHT_PARENTHESIS;
            case '=' -> Token.Type.EQUALS;
            default -> null;
        };
    }

    private static ExpressionException unexpectedCharacter(String line, int index) {
        int codePoint = line.codePointAt(index);
        Span span = new Span(index, index + Character.charCount(codePoint));
        // An invisible character is named by its code, so that the message shows something.
        int type = Character.getType(codePoint);
        // A combining mark is named too: shown alone it would attach to the quotation mark.
        boolean invisible = Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint) || type == Character.FORMAT || type == Character.SURROGATE
                || type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
                || type == Character.COMBINING_SPACING_MARK || !Character.isDefined(codePoint);
        String shown = invisible ? String.format(Locale.ROOT, "U+%04X", codePoint) : line.substring(span.start(), span.end());
        return new ExpressionException(ExpressionError.UNEXPECTED_CHARACTER, span, "Caractère inattendu « " + shown + " ».");
    }
}
