package calc.expression;

import calc.number.CertifiedValue;
import java.util.Objects;

/** The answer of a {@link Resolver}: a value, or the reason why there is none. */
public sealed interface Resolution permits Resolution.Value, Resolution.Failure {

    /** Why a name or a reference has no value. */
    enum Reason {
        /** No such variable, no previous result for {@code ans}, no such line. */
        UNKNOWN,
        /** The variable or the line exists but is itself in error. */
        IN_ERROR,
        /** The reference is not allowed here (for instance a reference to a later line). */
        INVALID
    }

    record Value(CertifiedValue value) implements Resolution {
        public Value {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure(Reason reason) implements Resolution {
        public Failure {
            Objects.requireNonNull(reason, "reason");
        }
    }

    static Resolution of(CertifiedValue value) {
        return new Value(value);
    }

    static Resolution unknown() {
        return new Failure(Reason.UNKNOWN);
    }

    static Resolution inError() {
        return new Failure(Reason.IN_ERROR);
    }

    static Resolution invalid() {
        return new Failure(Reason.INVALID);
    }
}
