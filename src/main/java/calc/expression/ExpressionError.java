package calc.expression;

/** Why a line was refused, grouped by the stage that refuses it (docs/adr/0007-grammaire-expressions.md, section 4). */
public enum ExpressionError {
    /** The line is longer than {@link ExpressionLimits#maxLineLength()}. */
    LINE_TOO_LONG(Stage.LEXICAL),
    /** A character that starts no token ({@code @}, {@code !}, a lone {@code ,}). */
    UNEXPECTED_CHARACTER(Stage.LEXICAL),
    /** A run of digits, separators and decimal marks that is not a number ({@code 1,5,3}). */
    INVALID_NUMBER(Stage.LEXICAL),
    /** A number whose scientific exponent is beyond the bound of the core. */
    SCIENTIFIC_EXPONENT_OUT_OF_RANGE(Stage.LEXICAL),
    /** A number too large, or with too many decimals, for the core. */
    NUMBER_TOO_LARGE(Stage.LEXICAL),
    /** {@code $} without a line number, {@code $0}, or a line number too large. */
    INVALID_REFERENCE(Stage.LEXICAL),
    /** {@code $?}: the line it referred to was deleted. */
    DELETED_REFERENCE(Stage.LEXICAL),

    /** An operator without its operand, or an empty pair of parentheses. */
    MISSING_OPERAND(Stage.SYNTAX),
    /** Two operands side by side ({@code 2 3}, {@code 2x}, {@code 2(3)}): there is no implicit multiplication. */
    MISSING_OPERATOR(Stage.SYNTAX),
    /** An opening parenthesis that is never closed. */
    MISSING_CLOSING_PARENTHESIS(Stage.SYNTAX),
    /** A closing parenthesis without an opening one. */
    UNEXPECTED_CLOSING_PARENTHESIS(Stage.SYNTAX),
    /** {@code sqrt} or {@code racine} not followed by {@code (}. */
    MISSING_OPENING_PARENTHESIS(Stage.SYNTAX),
    /** {@code =} anywhere else than right after a leading name. */
    MISPLACED_EQUALS(Stage.SYNTAX),
    /** A reserved word ({@code ans}, {@code sqrt}, {@code racine}) used as the name of an assignment. */
    RESERVED_NAME(Stage.SYNTAX),
    /** The nesting is deeper than {@link ExpressionLimits#maxDepth()}, or a tree built by hand is too deep. */
    TOO_DEEP(Stage.SYNTAX),
    /** The tree would have more than {@link ExpressionLimits#maxNodes()} nodes. */
    TOO_MANY_NODES(Stage.SYNTAX),

    /** The numeric core refused an operation; {@link ExpressionException#numericError()} tells why. */
    NUMERIC(Stage.EVALUATION),
    /** The resolver does not know the variable, has no previous result for {@code ans}, or no such line. */
    UNKNOWN_NAME(Stage.EVALUATION),
    /** The variable or line referred to is itself in error. */
    REFERENCE_IN_ERROR(Stage.EVALUATION),
    /** The resolver refuses the reference here (for instance a reference to a later line). */
    INVALID_USE(Stage.EVALUATION);

    /** The stage of the analysis that detects an error. */
    public enum Stage {
        LEXICAL,
        SYNTAX,
        EVALUATION
    }

    private final Stage stage;

    ExpressionError(Stage stage) {
        this.stage = stage;
    }

    public Stage stage() {
        return stage;
    }
}
