package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.engine.Operation;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OperationIdsTest {
    /** The identifiers of the format, written out: a change here is a change of the file format (docs/FORMAT-FICHIER.md). */
    private static final Map<Operation, String> DOCUMENTED = new EnumMap<>(Map.of(
            Operation.ADDITION, "addition",
            Operation.SUBTRACTION, "subtraction",
            Operation.MULTIPLICATION, "multiplication",
            Operation.DIVISION, "division",
            Operation.POWER, "power",
            Operation.ROOT, "sqrt"));

    @Test
    void theTableIsTheDocumentedOne() {
        assertEquals(Operation.values().length, DOCUMENTED.size(), "Every operation has a documented identifier.");
        for (Operation operation : Operation.values()) {
            assertEquals(DOCUMENTED.get(operation), OperationIds.idOf(operation), operation.name());
            assertEquals(Optional.of(operation), OperationIds.fromId(DOCUMENTED.get(operation)));
        }
    }

    @Test
    void identifiersAreLowercaseDistinctAndNotTheJavaNames() {
        Set<String> seen = new HashSet<>();
        for (Operation operation : Operation.values()) {
            String id = OperationIds.idOf(operation);
            assertTrue(id.matches("[a-z]+"), id);
            assertTrue(seen.add(id), "Distinct: " + id);
        }
        // sqrt is not named like ROOT: the identifier is a decision of the format, not a copy of the constant.
        assertTrue(!OperationIds.idOf(Operation.ROOT).equals(Operation.ROOT.name().toLowerCase(Locale.ROOT)));
    }

    @Test
    void anUnknownIdentifierIsNotAnOperation() {
        for (String text : new String[] {"", "ADDITION", "Addition", "root", "plus", " addition", "addition ", "ROOT"}) {
            assertEquals(Optional.empty(), OperationIds.fromId(text), text);
        }
    }
}
