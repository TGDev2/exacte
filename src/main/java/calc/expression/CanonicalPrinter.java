package calc.expression;

import calc.number.Rational;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Writes a tree as the one canonical text that parses back to the same tree (positions aside), with the fewest
 * parentheses: ASCII operators {@code + - * / ^}, spaces around {@code + - * /}, {@code √x} before a primary and
 * {@code √(...)} otherwise, numbers with a decimal comma, plain up to 30 characters and {@code d,ddde±n} beyond.
 */
public final class CanonicalPrinter {
    private static final int MAX_PLAIN_NUMBER_LENGTH = 30;

    // Binding strength of each form: an operand weaker than its place requires parentheses.
    private static final int ADDITIVE = 1;
    private static final int MULTIPLICATIVE = 2;
    private static final int UNARY = 3;
    private static final int POWER = 4;
    private static final int PRIMARY = 5;

    private CanonicalPrinter() {
    }

    /** A node to write where an operand of at least {@code required} strength is expected. */
    private record Operand(Node node, int required) {
    }

    /** The canonical text of {@code node}. */
    public static String print(Node node) {
        Objects.requireNonNull(node, "node");
        StringBuilder text = new StringBuilder();
        // A loop over an explicit stack of operands and literal texts: no tree can exhaust the thread stack.
        Deque<Object> work = new ArrayDeque<>();
        work.push(new Operand(node, 0));
        while (!work.isEmpty()) {
            Object item = work.pop();
            if (item instanceof String literal) {
                text.append(literal);
                continue;
            }
            Operand operand = (Operand) item;
            Node current = operand.node();
            if (strength(current) < operand.required()) {
                work.push(")");
                work.push(new Operand(current, 0));
                text.append('(');
                continue;
            }
            switch (current) {
                case Node.NumberLiteral number -> text.append(number(number.value()));
                case Node.Variable variable -> text.append(variable.name());
                case Node.Ans ans -> text.append("ans");
                case Node.LineRef reference -> text.append('$').append(reference.line());
                case Node.Unary unary -> {
                    text.append(unary.sign() == Node.Sign.MINUS ? '-' : '+');
                    work.push(new Operand(unary.operand(), UNARY));
                }
                case Node.Binary binary when binary.operator() == Node.Operator.POWER -> {
                    work.push(new Operand(binary.right(), UNARY));
                    work.push("^");
                    work.push(new Operand(binary.left(), PRIMARY));
                }
                case Node.Binary binary -> {
                    int strength = strength(binary);
                    // Left associative: the left operand may have the same strength, the right one must be stronger.
                    work.push(new Operand(binary.right(), strength + 1));
                    work.push(" " + symbol(binary.operator()) + " ");
                    work.push(new Operand(binary.left(), strength));
                }
                case Node.Sqrt sqrt -> {
                    text.append(Lexer.ROOT_SIGN);
                    work.push(new Operand(sqrt.operand(), PRIMARY));
                }
                case Node.Assignment assignment -> {
                    // Node forbids an assignment as an operand, so it can only be the whole line.
                    text.append(assignment.name()).append(" = ");
                    work.push(new Operand(assignment.value(), 0));
                }
            }
        }
        return text.toString();
    }

    private static int strength(Node node) {
        return switch (node) {
            case Node.Binary binary -> switch (binary.operator()) {
                case ADD, SUBTRACT -> ADDITIVE;
                case MULTIPLY, DIVIDE -> MULTIPLICATIVE;
                case POWER -> POWER;
            };
            case Node.Unary unary -> UNARY;
            case Node.Assignment assignment -> 0;
            case Node.NumberLiteral number -> PRIMARY;
            case Node.Variable variable -> PRIMARY;
            case Node.Ans ans -> PRIMARY;
            case Node.LineRef reference -> PRIMARY;
            case Node.Sqrt sqrt -> PRIMARY;
        };
    }

    private static char symbol(Node.Operator operator) {
        return switch (operator) {
            case ADD -> '+';
            case SUBTRACT -> '-';
            case MULTIPLY -> '*';
            case DIVIDE -> '/';
            case POWER -> '^';
        };
    }

    /** A number literal's value with a decimal comma: plain when short, otherwise {@code d,ddde±n}. */
    static String number(Rational value) {
        BigDecimal decimal = value.toExactDecimal().orElseThrow().stripTrailingZeros();
        String plain = decimal.toPlainString();
        if (plain.length() <= MAX_PLAIN_NUMBER_LENGTH) {
            return plain.replace('.', ',');
        }
        String digits = decimal.unscaledValue().toString();
        long exponent = digits.length() - 1L - decimal.scale();
        String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "," + digits.substring(1);
        return mantissa + "e" + exponent;
    }
}
