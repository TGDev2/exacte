package calc.persistence;

import java.nio.file.Path;

/**
 * A process that opens a store on the directory given as argument, says so on its standard output, then waits for ever:
 * the test kills it, as a crash would, and checks that the lock does not outlive it. Not a test.
 */
public final class LockHolder {
    private LockHolder() {
    }

    public static void main(String[] arguments) throws Exception {
        try (JsonHistoryStore store = JsonHistoryStore.open(Path.of(arguments[0]))) {
            System.out.println(store.isReadOnly() ? "READ_ONLY" : "LOCKED");
            System.out.flush();
            Thread.sleep(Long.MAX_VALUE);
        }
    }
}
