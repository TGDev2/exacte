package calc.ui.notebook;

import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * One start of the application for the acceptance tests of docs/RECETTE.md: the real store on a temporary data directory, the
 * real presenter and document service, the keyboard played by {@link NotebookDriver}, the dialogs by {@link FakeDialogs}.
 * Everything below the window is the production code; no real user data is ever touched.
 */
final class AcceptanceSession implements AutoCloseable {
    private static final String LOCK_FILE = "history.lock";

    final NotebookStore store;
    final FakeDialogs dialogs;
    final NotebookDriver d;

    private AcceptanceSession(NotebookStore store, FakeDialogs dialogs, NotebookDriver driver) {
        this.store = store;
        this.dialogs = dialogs;
        this.d = driver;
    }

    static AcceptanceSession start(Path dataDirectory) {
        return start(NotebookStore.open(dataDirectory));
    }

    static AcceptanceSession start(NotebookStore store) {
        FakeDialogs dialogs = new FakeDialogs();
        return new AcceptanceSession(store, dialogs, new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs)));
    }

    /** The same, with a presenter built by the test (a document service with a failing disk, for instance). */
    static AcceptanceSession of(NotebookStore store, FakeDialogs dialogs, NotebookPresenter presenter) {
        return new AcceptanceSession(store, dialogs, new NotebookDriver(presenter));
    }

    /** The source texts of the notebook, without the blank row the window keeps at the end. */
    List<String> lines() {
        return d.texts().subList(0, d.texts().size() - 1);
    }

    /** The result texts of the notebook, without the blank row the window keeps at the end. */
    List<String> results() {
        return d.results().subList(0, d.results().size() - 1);
    }

    /** The status of every line of the notebook, without the blank row the window keeps at the end. */
    List<NotebookRow.Status> statuses() {
        return d.view.rows().subList(0, d.view.rows().size() - 1).stream().map(NotebookRow::status).toList();
    }

    @Override
    public void close() {
        store.close();
    }

    /**
     * Every file of {@code directory} with the SHA-256 of its bytes: two equal maps mean nothing was created, removed or
     * modified. The lock file is only named: a system may forbid reading it while it is held.
     */
    static Map<String, String> contentOf(Path directory) throws IOException {
        Map<String, String> content = new TreeMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                content.put(name, name.equals(LOCK_FILE) ? "" : sha256(file));
            }
        }
        return content;
    }

    static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
