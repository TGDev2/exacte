package calc.ui.notebook;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * The dialogs of the file menu played by a script: a test queues the answers the user would give, then reads what was asked.
 * A dialog opened with no answer queued fails the test, so that a question the presenter was not meant to ask cannot go
 * unnoticed (and no test ever opens a real dialog).
 */
final class FakeDialogs implements NotebookDialogs {
    /** Every question asked, in order, as a short sentence. */
    final List<String> asked = new ArrayList<>();
    /** The folder-less names proposed for an export. */
    final List<String> suggestedExportNames = new ArrayList<>();

    private final Deque<Optional<Path>> toOpen = new ArrayDeque<>();
    private final Deque<Optional<Path>> toSave = new ArrayDeque<>();
    private final Deque<Optional<Path>> toExport = new ArrayDeque<>();
    private final Deque<Unsaved> unsaved = new ArrayDeque<>();
    private final Deque<Boolean> replaceDraft = new ArrayDeque<>();
    private final Deque<Boolean> overwrite = new ArrayDeque<>();

    FakeDialogs willOpen(Path file) {
        toOpen.add(Optional.ofNullable(file));
        return this;
    }

    FakeDialogs willSaveTo(Path file) {
        toSave.add(Optional.ofNullable(file));
        return this;
    }

    FakeDialogs willExportTo(Path file) {
        toExport.add(Optional.ofNullable(file));
        return this;
    }

    FakeDialogs willAnswer(Unsaved answer) {
        unsaved.add(answer);
        return this;
    }

    FakeDialogs willConfirmReplaceDraft(boolean answer) {
        replaceDraft.add(answer);
        return this;
    }

    FakeDialogs willConfirmOverwrite(boolean answer) {
        overwrite.add(answer);
        return this;
    }

    /** Forgets the answers still queued and the questions asked: a step of a test starts from nothing. */
    void reset() {
        toOpen.clear();
        toSave.clear();
        toExport.clear();
        unsaved.clear();
        replaceDraft.clear();
        overwrite.clear();
        asked.clear();
        suggestedExportNames.clear();
    }

    /** Fails if an answer was queued and never used. */
    void assertAllUsed() {
        if (!toOpen.isEmpty() || !toSave.isEmpty() || !toExport.isEmpty() || !unsaved.isEmpty() || !replaceDraft.isEmpty()
                || !overwrite.isEmpty()) {
            throw new AssertionError("answers queued but never asked for: open " + toOpen + ", save " + toSave + ", export " + toExport
                    + ", unsaved " + unsaved + ", draft " + replaceDraft + ", overwrite " + overwrite);
        }
    }

    @Override
    public Optional<Path> chooseFileToOpen(Optional<Path> current) {
        asked.add("open");
        return next(toOpen, "open");
    }

    @Override
    public Optional<Path> chooseFileToSave(Optional<Path> current) {
        asked.add("save as");
        return next(toSave, "save as");
    }

    @Override
    public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
        asked.add("export " + format);
        suggestedExportNames.add(suggested);
        return next(toExport, "export");
    }

    @Override
    public Unsaved askToSave(String name, Action action) {
        asked.add("unsaved " + name + " " + action);
        return next(unsaved, "unsaved");
    }

    @Override
    public boolean confirmReplaceDraft() {
        asked.add("replace draft");
        return next(replaceDraft, "replace draft");
    }

    @Override
    public boolean confirmOverwrite(Path file) {
        asked.add("overwrite " + file.getFileName());
        return next(overwrite, "overwrite");
    }

    private static <T> T next(Deque<T> queue, String what) {
        T answer = queue.poll();
        if (answer == null) {
            throw new AssertionError("unexpected dialog: " + what);
        }
        return answer;
    }
}
