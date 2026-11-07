package calc.web.compat;

import java.math.BigInteger;

/**
 * {@code BigInteger.sqrtAndRemainder()}, which the class library of TeaVM 0.16.0 lacks although it has {@code sqrt()}. The
 * core calls it in OutwardRounding; {@link CompatCallRewriter} sends those calls here when TeaVM compiles the core.
 */
public final class BigIntegerCompat {
    private BigIntegerCompat() {
    }

    /** The root and the remainder of {@code x}, as the JDK gives them. {@code sqrt()} refuses a negative {@code x}. */
    public static BigInteger[] sqrtAndRemainder(BigInteger x) {
        BigInteger root = x.sqrt();
        return new BigInteger[] {root, x.subtract(root.multiply(root))};
    }
}
