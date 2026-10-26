package calc.expression;

/**
 * The bounds of one line (docs/adr/0007-grammaire-expressions.md, section 3), each checked before the work it protects.
 * The numbers inside a line keep the bounds of the numeric core.
 *
 * @param maxLineLength longest line, in characters, checked before anything is read
 * @param maxDepth      deepest nesting of parentheses, signs, roots and exponents; it bounds the recursion of the parser
 * @param maxNodes      largest number of nodes of the tree; it bounds the work of the evaluator and of the printer, which
 *                      walk the tree with an explicit stack and never recurse
 */
public record ExpressionLimits(int maxLineLength, int maxDepth, int maxNodes) {
    /** The limits of a notebook line. */
    public static final ExpressionLimits DEFAULT = new ExpressionLimits(1000, 100, 500);

    /**
     * Deepest nesting a caller may allow: the parser recurses about five frames per level. Measured in interpreted mode,
     * a 256 KB thread stack (a quarter of the default of 64-bit JVMs) overflows near 150 levels; {@code
     * ExpressionRobustnessTest} parses at this depth on such a stack.
     */
    static final int MAX_SAFE_DEPTH = 100;

    public ExpressionLimits {
        if (maxLineLength < 1) {
            throw new IllegalArgumentException("maxLineLength must be positive: " + maxLineLength);
        }
        if (maxDepth < 1 || maxDepth > MAX_SAFE_DEPTH) {
            throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_SAFE_DEPTH + ": " + maxDepth);
        }
        if (maxNodes < 1) {
            throw new IllegalArgumentException("maxNodes must be positive: " + maxNodes);
        }
    }
}
