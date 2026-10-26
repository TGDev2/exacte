package calc.expression;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Recursive descent parser of a line, one method per rule of the grammar of docs/adr/0007-grammaire-expressions.md.
 * It recurses only through the nesting constructs (parentheses, signs, roots, exponents), each of which is counted
 * against {@link ExpressionLimits#maxDepth()} before going deeper, so a hostile line ends in an
 * {@link ExpressionException}, never in a {@link StackOverflowError}. The number of nodes is checked as they are built.
 */
public final class ExpressionParser {
    private static final ExpressionParser DEFAULT = new ExpressionParser(ExpressionLimits.DEFAULT);

    private final ExpressionLimits limits;

    public ExpressionParser(ExpressionLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Reads {@code line} with the default limits. */
    public static ParsedLine parseLine(String line) throws ExpressionException {
        return DEFAULT.parse(line);
    }

    public ExpressionLimits limits() {
        return limits;
    }

    /** Reads {@code line}; the first error from left to right is reported with its position. */
    public ParsedLine parse(String line) throws ExpressionException {
        Objects.requireNonNull(line, "line");
        List<Token> tokens = new ArrayList<>(Lexer.tokenize(line, limits));
        String comment = null;
        if (!tokens.isEmpty() && tokens.get(tokens.size() - 1).type() == Token.Type.COMMENT) {
            comment = tokens.remove(tokens.size() - 1).text().substring(1);
        }
        if (tokens.isEmpty()) {
            ParsedLine.Kind kind = comment == null ? ParsedLine.Kind.EMPTY : ParsedLine.Kind.COMMENT;
            return new ParsedLine(kind, null, comment, List.of(), Set.of(), false);
        }
        Run run = new Run(line, tokens);
        Node node = run.line();
        ParsedLine.Kind kind = node instanceof Node.Assignment ? ParsedLine.Kind.ASSIGNMENT : ParsedLine.Kind.EXPRESSION;
        return new ParsedLine(kind, node, comment, run.lineReferences, run.variables, run.usesAns);
    }

    /** The state of one reading. */
    private final class Run {
        private final String line;
        private final List<Token> tokens;
        private final List<Integer> lineReferences = new ArrayList<>();
        private final Set<String> variables = new LinkedHashSet<>();
        private boolean usesAns;
        private int position;
        private int depth;
        private int nodes;

        Run(String line, List<Token> tokens) {
            this.line = line;
            this.tokens = tokens;
        }

        // ligne := affectation | expression (the comment is already removed)
        Node line() throws ExpressionException {
            if (tokens.size() >= 2 && tokens.get(1).type() == Token.Type.EQUALS) {
                Token name = tokens.get(0);
                if (name.type() == Token.Type.ANS || name.type() == Token.Type.SQRT_WORD) {
                    throw new ExpressionException(ExpressionError.RESERVED_NAME, name.span(),
                            "« " + name.text() + " » est un mot réservé : choisissez un autre nom de variable.");
                }
                if (name.type() == Token.Type.IDENTIFIER) {
                    Token equals = tokens.get(1);
                    position = 2;
                    requireOperand(equals);
                    Node value = expression();
                    requireEnd();
                    return count(new Node.Assignment(name.text(), value, Span.between(name.span(), value.span())));
                }
            }
            Node expression = expression();
            requireEnd();
            return expression;
        }

        // expression := additive ; additive := multiplicative (('+' | '-' | '−') multiplicative)*
        private Node expression() throws ExpressionException {
            Node left = multiplicative();
            while (at(Token.Type.PLUS) || at(Token.Type.MINUS)) {
                Token operator = next();
                requireOperand(operator);
                Node right = multiplicative();
                left = count(new Node.Binary(operator.type() == Token.Type.PLUS ? Node.Operator.ADD : Node.Operator.SUBTRACT,
                        left, right, Span.between(left.span(), right.span())));
            }
            return left;
        }

        // multiplicative := unaire (('*' | '×' | '/' | '÷') unaire)*
        private Node multiplicative() throws ExpressionException {
            Node left = unary();
            while (at(Token.Type.TIMES) || at(Token.Type.DIVIDE)) {
                Token operator = next();
                requireOperand(operator);
                Node right = unary();
                left = count(new Node.Binary(
                        operator.type() == Token.Type.TIMES ? Node.Operator.MULTIPLY : Node.Operator.DIVIDE,
                        left, right, Span.between(left.span(), right.span())));
            }
            return left;
        }

        // unaire := ('+' | '-' | '−') unaire | puissance
        private Node unary() throws ExpressionException {
            if (!at(Token.Type.PLUS) && !at(Token.Type.MINUS)) {
                return power();
            }
            Token sign = next();
            enter(sign);
            requireOperand(sign);
            Node operand = unary();
            depth--;
            return count(new Node.Unary(sign.type() == Token.Type.PLUS ? Node.Sign.PLUS : Node.Sign.MINUS, operand,
                    Span.between(sign.span(), operand.span())));
        }

        // puissance := primaire ('^' unaire)?
        private Node power() throws ExpressionException {
            Node base = primary();
            if (!at(Token.Type.POWER)) {
                return base;
            }
            Token operator = next();
            enter(operator);
            requireOperand(operator);
            Node exponent = unary();
            depth--;
            return count(new Node.Binary(Node.Operator.POWER, base, exponent, Span.between(base.span(), exponent.span())));
        }

        // primaire := NOMBRE | IDENT | 'ans' | REF | '(' expression ')' | ('sqrt' | 'racine') '(' expression ')' | '√' primaire
        private Node primary() throws ExpressionException {
            Token token = next();
            switch (token.type()) {
                case NUMBER:
                    return count(new Node.NumberLiteral(token.number(), token.span()));
                case IDENTIFIER:
                    variables.add(token.text());
                    return count(new Node.Variable(token.text(), token.span()));
                case ANS:
                    usesAns = true;
                    return count(new Node.Ans(token.span()));
                case REFERENCE:
                    lineReferences.add(token.reference());
                    return count(new Node.LineRef(token.reference(), token.span()));
                case LEFT_PARENTHESIS: {
                    enter(token);
                    requireOperand(token);
                    Node inner = expression();
                    closing(token);
                    depth--;
                    return inner;
                }
                case SQRT_WORD: {
                    if (!at(Token.Type.LEFT_PARENTHESIS)) {
                        throw new ExpressionException(ExpressionError.MISSING_OPENING_PARENTHESIS, token.span(),
                                "Parenthèse ouvrante attendue après « " + token.text() + " ».");
                    }
                    Token open = next();
                    enter(open);
                    requireOperand(open);
                    Node inner = expression();
                    Token close = closing(open);
                    depth--;
                    return count(new Node.Sqrt(inner, Span.between(token.span(), close.span())));
                }
                case ROOT: {
                    enter(token);
                    if (at(Token.Type.PLUS) || at(Token.Type.MINUS)) {
                        throw new ExpressionException(ExpressionError.MISSING_OPERAND, token.span(),
                                "Opérande manquant après « " + token.text() + " » : un nombre signé se met entre"
                                        + " parenthèses, par exemple « " + token.text() + "(-2) ».");
                    }
                    requireOperand(token);
                    Node operand = primary();
                    depth--;
                    return count(new Node.Sqrt(operand, Span.between(token.span(), operand.span())));
                }
                default:
                    // Only reachable at the start of the line: every other caller checks the operand first.
                    throw noOperandAtStart(token);
            }
        }

        private Token closing(Token open) throws ExpressionException {
            if (at(Token.Type.RIGHT_PARENTHESIS)) {
                return next();
            }
            if (position == tokens.size()) {
                throw new ExpressionException(ExpressionError.MISSING_CLOSING_PARENTHESIS, open.span(),
                        "Parenthèse fermante manquante.");
            }
            throw misplaced(tokens.get(position));
        }

        // After the whole line is read, nothing may remain.
        private void requireEnd() throws ExpressionException {
            if (position < tokens.size()) {
                Token token = tokens.get(position);
                if (token.type() == Token.Type.RIGHT_PARENTHESIS) {
                    throw new ExpressionException(ExpressionError.UNEXPECTED_CLOSING_PARENTHESIS, token.span(),
                            "Parenthèse fermante « ) » sans parenthèse ouvrante.");
                }
                throw misplaced(token);
            }
        }

        // A token where an operator, a closing parenthesis or the end was expected.
        private ExpressionException misplaced(Token token) {
            if (token.type() == Token.Type.EQUALS) {
                return misplacedEquals(token);
            }
            Token previous = tokens.get(position - 1);
            boolean spacedDigits = previous.type() == Token.Type.NUMBER && token.type() == Token.Type.NUMBER
                    && line.substring(previous.span().end(), token.span().start()).chars().allMatch(c -> c == ' ');
            return new ExpressionException(ExpressionError.MISSING_OPERATOR, token.span(), spacedDigits
                    ? "Opérateur manquant (pour séparer les milliers, utilisez une espace insécable)."
                    : "Opérateur manquant.");
        }

        private ExpressionException misplacedEquals(Token token) {
            return new ExpressionException(ExpressionError.MISPLACED_EQUALS, token.span(),
                    "Le signe « = » ne peut suivre qu'un nom de variable en début de ligne.");
        }

        private ExpressionException noOperandAtStart(Token token) {
            return switch (token.type()) {
                case RIGHT_PARENTHESIS -> new ExpressionException(ExpressionError.UNEXPECTED_CLOSING_PARENTHESIS,
                        token.span(), "Parenthèse fermante « ) » sans parenthèse ouvrante.");
                case EQUALS -> misplacedEquals(token);
                default -> new ExpressionException(ExpressionError.MISSING_OPERAND, token.span(),
                        "Opérande manquant avant « " + token.text() + " ».");
            };
        }

        // The token after `after` (an operator, a sign, '(' or '=') must start an operand.
        private void requireOperand(Token after) throws ExpressionException {
            if (position < tokens.size() && startsOperand(tokens.get(position).type())) {
                return;
            }
            String message = switch (after.type()) {
                case LEFT_PARENTHESIS -> "Expression manquante après « ( ».";
                case EQUALS -> "Expression manquante après « = ».";
                default -> "Opérande manquant après « " + after.text() + " ».";
            };
            throw new ExpressionException(ExpressionError.MISSING_OPERAND, after.span(), message);
        }

        private boolean startsOperand(Token.Type type) {
            return switch (type) {
                case NUMBER, IDENTIFIER, ANS, REFERENCE, LEFT_PARENTHESIS, SQRT_WORD, ROOT, PLUS, MINUS -> true;
                default -> false;
            };
        }

        private void enter(Token token) throws ExpressionException {
            depth++;
            if (depth > limits.maxDepth()) {
                throw new ExpressionException(ExpressionError.TOO_DEEP, token.span(),
                        "Trop d'imbrications (" + limits.maxDepth() + " niveaux maximum).");
            }
        }

        private Node count(Node node) throws ExpressionException {
            nodes++;
            if (nodes > limits.maxNodes()) {
                throw new ExpressionException(ExpressionError.TOO_MANY_NODES, tokens.get(position - 1).span(),
                        "Expression trop longue (" + limits.maxNodes() + " éléments maximum).");
            }
            return node;
        }

        private boolean at(Token.Type type) {
            return position < tokens.size() && tokens.get(position).type() == type;
        }

        private Token next() {
            return tokens.get(position++);
        }
    }
}
