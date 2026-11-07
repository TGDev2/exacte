package calc.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.function.IntFunction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The classification of the JDK 21 is the reference of the core: if the web module completes TeaVM with a table of its
 * own, the table gives the answers of the JDK on every code point. The class only exists when the gap is observed, hence
 * the reflection, and the test is ignored without it.
 */
@DisplayName("CharacterCompat : le même résultat que le JDK sur tous les code points")
class CharacterCompatParityTest {
    private static final String COMPAT = "calc.web.compat.CharacterCompat";
    private static final int CODE_POINTS = 0x110000;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"getType", "isLetter", "isLetterOrDigit", "isDefined", "isSpaceChar", "isWhitespace", "isISOControl"})
    void givesTheAnswerOfTheJdkOnEveryCodePoint(String name) throws Exception {
        Class<?> compat = compatOrNull();
        assumeTrue(compat != null, COMPAT + " does not exist: no Unicode gap was observed.");

        Method method = compat.getMethod(name, int.class);
        assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static.");
        assertEquals(Character.class.getMethod(name, int.class).getReturnType(), method.getReturnType(), name + ": return type.");

        IntFunction<Object> reference = reference(name);
        int differences = 0;
        int first = -1;
        for (int codePoint = 0; codePoint < CODE_POINTS; codePoint++) {
            if (!reference.apply(codePoint).equals(call(method, codePoint))) {
                first = differences == 0 ? codePoint : first;
                differences++;
            }
        }
        assertEquals(0, differences, name + " differs from the JDK, first at U+" + Integer.toHexString(first).toUpperCase(java.util.Locale.ROOT));
    }

    private static IntFunction<Object> reference(String name) {
        return switch (name) {
            case "getType" -> Character::getType;
            case "isLetter" -> Character::isLetter;
            case "isLetterOrDigit" -> Character::isLetterOrDigit;
            case "isDefined" -> Character::isDefined;
            case "isSpaceChar" -> Character::isSpaceChar;
            case "isWhitespace" -> Character::isWhitespace;
            case "isISOControl" -> Character::isISOControl;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static Object call(Method method, int codePoint) throws IllegalAccessException, InvocationTargetException {
        return method.invoke(null, codePoint);
    }

    private static Class<?> compatOrNull() {
        try {
            return Class.forName(COMPAT);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }
}
