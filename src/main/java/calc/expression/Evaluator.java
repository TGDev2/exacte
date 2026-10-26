package calc.expression;

import calc.engine.NumericMessages;
import calc.number.CertifiedValue;
import calc.number.NumericError;
import calc.number.NumericException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Evaluates a tree with the exact numeric core: every operation is a call to {@link CertifiedValue}, so exact values stay
 * exact, a root that is not exact is a certified interval, and the core's limits apply. Names and references are asked
 * to the caller's {@link Resolver}. Every refusal is an {@link ExpressionException} whose span points at the faulty
 * subtree (the divisor, the exponent, the operand of a root, or the whole operation for a result too large) and whose
 * message is French (docs/adr/0007-grammaire-expressions.md, sections 4 and 5).
 *
 * <p>The walk is a loop over an explicit stack, in post-order and from left to right, so the first error from the left is
 * the one reported and no tree, however deep, can exhaust the thread stack.
 */
public final class Evaluator {
    private final Resolver resolver;

    private Evaluator(Resolver resolver) {
        this.resolver = resolver;
    }

    /** The value of {@code node}; for an assignment, the value assigned. */
    public static CertifiedValue evaluate(Node node, Resolver resolver) throws ExpressionException {
        Objects.requireNonNull(node, "node");
        return new Evaluator(Objects.requireNonNull(resolver, "resolver")).value(node);
    }

    /** A node whose operands are already on the value stack. */
    private record Apply(Node node) {
    }

    private CertifiedValue value(Node root) throws ExpressionException {
        Deque<Object> work = new ArrayDeque<>();
        Deque<CertifiedValue> values = new ArrayDeque<>();
        work.push(root);
        while (!work.isEmpty()) {
            Object item = work.pop();
            if (item instanceof Apply apply) {
                values.push(apply(apply.node(), values));
                continue;
            }
            switch ((Node) item) {
                case Node.NumberLiteral number -> values.push(literal(number));
                case Node.Variable variable -> values.push(resolved(resolver.variable(variable.name()), variable));
                case Node.Ans ans -> values.push(resolved(resolver.ans(), ans));
                case Node.LineRef reference -> values.push(resolved(resolver.line(reference.line()), reference));
                case Node.Unary unary -> {
                    work.push(new Apply(unary));
                    work.push(unary.operand());
                }
                case Node.Binary binary -> {
                    // Popped in reverse order: the left operand is evaluated first.
                    work.push(new Apply(binary));
                    work.push(binary.right());
                    work.push(binary.left());
                }
                case Node.Sqrt sqrt -> {
                    work.push(new Apply(sqrt));
                    work.push(sqrt.operand());
                }
                case Node.Assignment assignment -> work.push(assignment.value());
            }
        }
        return values.pop();
    }

    // The operands of `node` are on top of `values`, the last one on top.
    private static CertifiedValue apply(Node node, Deque<CertifiedValue> values) throws ExpressionException {
        return switch (node) {
            case Node.Unary unary -> unary.sign() == Node.Sign.MINUS ? values.pop().negate() : values.pop();
            case Node.Binary binary -> {
                CertifiedValue right = values.pop();
                yield binary(binary, values.pop(), right);
            }
            case Node.Sqrt sqrt -> sqrt(sqrt, values.pop());
            default -> throw new IllegalStateException("nothing to apply for " + node.getClass().getSimpleName());
        };
    }

    private static CertifiedValue literal(Node.NumberLiteral number) throws ExpressionException {
        try {
            return CertifiedValue.exact(number.value());
        } catch (NumericException exception) {
            // Only a literal built by hand can exceed the core's limits: the lexer checks the typed ones.
            throw new ExpressionException(ExpressionError.NUMERIC, number.span(), Lexer.NUMBER_TOO_LARGE_MESSAGE,
                    exception.error());
        }
    }

    private static CertifiedValue binary(Node.Binary binary, CertifiedValue left, CertifiedValue right)
            throws ExpressionException {
        try {
            return switch (binary.operator()) {
                case ADD -> left.add(right);
                case SUBTRACT -> left.subtract(right);
                case MULTIPLY -> left.multiply(right);
                case DIVIDE -> left.divide(right);
                case POWER -> left.pow(right);
            };
        } catch (NumericException exception) {
            throw refused(binary, exception.error());
        } catch (ArithmeticException exception) {
            throw unexpected(binary.span());
        }
    }

    private static CertifiedValue sqrt(Node.Sqrt sqrt, CertifiedValue operand) throws ExpressionException {
        try {
            return operand.sqrt();
        } catch (NumericException exception) {
            NumericError error = exception.error();
            Span span = sqrt.operand().span();
            throw switch (error) {
                case ROOT_OF_NEGATIVE -> numeric(error, span, NumericMessages.ROOT_OF_NEGATIVE);
                case ROOT_OF_POSSIBLY_NEGATIVE -> numeric(error, span, "La valeur sous la racine est approchée et peut"
                        + " être négative : sa racine carrée n'est pas définie.");
                case SIZE_LIMIT_EXCEEDED -> numeric(error, sqrt.span(), NumericMessages.RESULT_TOO_LARGE);
                default -> numeric(error, sqrt.span(), NumericMessages.UNEXPECTED_ARITHMETIC);
            };
        } catch (ArithmeticException exception) {
            throw unexpected(sqrt.span());
        }
    }

    // The engine's messages when they name no field; the others are worded for an expression.
    private static ExpressionException refused(Node.Binary binary, NumericError error) {
        boolean power = binary.operator() == Node.Operator.POWER;
        Span whole = binary.span();
        Span left = binary.left().span();
        Span right = binary.right().span();
        return switch (error) {
            case DIVISION_BY_ZERO -> power
                    ? numeric(error, whole, NumericMessages.ZERO_TO_NEGATIVE_POWER)
                    : numeric(error, right, "Division par zéro : le diviseur vaut 0.");
            case DIVISOR_MAY_BE_ZERO -> power
                    ? numeric(error, left, "La base est une valeur approchée qui peut valoir 0 : elle ne peut pas être"
                            + " élevée à une puissance négative.")
                    : numeric(error, right, "Le diviseur est une valeur approchée qui peut valoir 0 : la division est"
                            + " impossible.");
            case EXPONENT_NOT_INTEGER -> numeric(error, right, NumericMessages.EXPONENT_NOT_INTEGER);
            case EXPONENT_NOT_EXACT -> numeric(error, right, NumericMessages.EXPONENT_NOT_EXACT);
            case EXPONENT_OUT_OF_RANGE -> numeric(error, right, NumericMessages.EXPONENT_OUT_OF_RANGE);
            case SIZE_LIMIT_EXCEEDED -> numeric(error, whole,
                    power ? NumericMessages.POWER_TOO_LARGE : NumericMessages.RESULT_TOO_LARGE);
            // A binary operation never raises the codes of roots or of parsing.
            case ROOT_OF_NEGATIVE, ROOT_OF_POSSIBLY_NEGATIVE, MALFORMED_INPUT ->
                    numeric(error, whole, NumericMessages.UNEXPECTED_ARITHMETIC);
        };
    }

    private static ExpressionException numeric(NumericError error, Span span, String message) {
        return new ExpressionException(ExpressionError.NUMERIC, span, message, error);
    }

    // A safety net: the core's limits make an ArithmeticException unreachable.
    private static ExpressionException unexpected(Span span) {
        return new ExpressionException(ExpressionError.NUMERIC, span, NumericMessages.UNEXPECTED_ARITHMETIC);
    }

    private static CertifiedValue resolved(Resolution resolution, Node node) throws ExpressionException {
        return switch (Objects.requireNonNull(resolution, "the resolver returned null")) {
            case Resolution.Value value -> value.value();
            case Resolution.Failure failure -> throw unresolved(node, failure.reason());
        };
    }

    private static ExpressionException unresolved(Node node, Resolution.Reason reason) {
        String[] messages = switch (node) {
            case Node.Variable variable -> new String[] {
                    "Variable inconnue « " + variable.name() + " ».",
                    "La variable « " + variable.name() + " » est en erreur.",
                    "La variable « " + variable.name() + " » ne peut pas être utilisée ici."};
            case Node.LineRef reference -> new String[] {
                    "La ligne " + reference.line() + " n'existe pas.",
                    "La ligne " + reference.line() + " est en erreur.",
                    "La ligne " + reference.line() + " ne peut pas être utilisée ici."};
            default -> new String[] {
                    "« ans » n'a pas de valeur : aucune ligne précédente n'a de résultat.",
                    "« ans » désigne une ligne en erreur.",
                    "« ans » ne peut pas être utilisé ici."};
        };
        return switch (reason) {
            case UNKNOWN -> new ExpressionException(ExpressionError.UNKNOWN_NAME, node.span(), messages[0]);
            case IN_ERROR -> new ExpressionException(ExpressionError.REFERENCE_IN_ERROR, node.span(), messages[1]);
            case INVALID -> new ExpressionException(ExpressionError.INVALID_USE, node.span(), messages[2]);
        };
    }
}
