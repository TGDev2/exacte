package calc.persistence;

import calc.engine.Operation;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The stable identifiers of the operations in the file, lowercase and independent of the names of the Java constants:
 * renaming a constant never breaks a file. The table is explicit. An identifier, once written, is never changed or reused
 * for another operation; a new operation gets a new identifier (docs/FORMAT-FICHIER.md).
 */
final class OperationIds {
    private static final Map<String, Operation> BY_ID = new HashMap<>();

    static {
        for (Operation operation : Operation.values()) {
            BY_ID.put(idOf(operation), operation);
        }
    }

    private OperationIds() {
    }

    /** No {@code default}: adding a constant to {@link Operation} does not compile until it has an identifier here. */
    static String idOf(Operation operation) {
        return switch (operation) {
            case ADDITION -> "addition";
            case SUBTRACTION -> "subtraction";
            case MULTIPLICATION -> "multiplication";
            case DIVISION -> "division";
            case POWER -> "power";
            case ROOT -> "sqrt";
        };
    }

    static Optional<Operation> fromId(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }
}
