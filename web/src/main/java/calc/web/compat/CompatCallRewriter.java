package calc.web.compat;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;

/**
 * Sends the calls of the application to the JDK methods TeaVM lacks or gets wrong to their complements. The bytecode of
 * the core stays as javac wrote it: this runs while TeaVM reads it. The complements themselves are left alone, since
 * they call the JDK methods they complete.
 */
final class CompatCallRewriter implements ClassHolderTransformer {
    private static final String APPLICATION_PACKAGE = "calc.";
    private static final String COMPLEMENTS_PACKAGE = "calc.web.compat.";

    private static final ValueType BIG_INTEGER = ValueType.object("java.math.BigInteger");
    private static final ValueType BIG_INTEGER_ARRAY = ValueType.arrayOf(BIG_INTEGER);
    private static final MethodReference SQRT_AND_REMAINDER =
            new MethodReference("java.math.BigInteger", "sqrtAndRemainder", BIG_INTEGER_ARRAY);
    private static final MethodReference SQRT_AND_REMAINDER_COMPLEMENT =
            new MethodReference(COMPLEMENTS_PACKAGE + "BigIntegerCompat", "sqrtAndRemainder", BIG_INTEGER, BIG_INTEGER_ARRAY);

    private static final ValueType STRING = ValueType.object("java.lang.String");
    private static final ValueType PATTERN = ValueType.object("java.util.regex.Pattern");
    private static final MethodReference COMPILE = new MethodReference("java.util.regex.Pattern", "compile", STRING, PATTERN);
    private static final MethodReference COMPILE_COMPLEMENT =
            new MethodReference(COMPLEMENTS_PACKAGE + "PatternCompat", "compile", STRING, PATTERN);

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        String name = cls.getName();
        if (!name.startsWith(APPLICATION_PACKAGE) || name.startsWith(COMPLEMENTS_PACKAGE)) {
            return;
        }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram();
            if (program != null) {
                rewriteCalls(program);
            }
        }
    }

    private static void rewriteCalls(Program program) {
        for (BasicBlock block : program.getBasicBlocks()) {
            for (Instruction instruction : block) {
                if (instruction instanceof InvokeInstruction call) {
                    rewriteCall(call);
                }
            }
        }
    }

    private static void rewriteCall(InvokeInstruction call) {
        if (SQRT_AND_REMAINDER.equals(call.getMethod())) {
            // An instance method becomes a static one: the instance becomes the first argument.
            Variable value = call.getInstance();
            call.setType(InvocationType.SPECIAL);
            call.setInstance(null);
            call.setArguments(value);
            call.setMethod(SQRT_AND_REMAINDER_COMPLEMENT);
        } else if (COMPILE.equals(call.getMethod())) {
            call.setMethod(COMPILE_COMPLEMENT);
        }
    }
}
