package calc.ui.notebook;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Everything the notebook window asks the user through a dialog, without Swing: the presenter decides when to ask and what to
 * do with the answer, the implementation decides how to ask. {@link SwingNotebookDialogs} is the real one; the tests script
 * another, so that no test opens a dialog (docs/adr/0011-fichiers-dans-l-interface.md).
 *
 * <p>Every method is called on the Swing event thread and returns once the user has answered.
 */
public interface NotebookDialogs {
    /** What the user is about to do, to word the question about unsaved work. */
    enum Action {
        NEW("créer un nouveau carnet"),
        OPEN("ouvrir un autre carnet"),
        QUIT("quitter Calc");

        private final String sentence;

        Action(String sentence) {
            this.sentence = sentence;
        }

        /** The action as a verb phrase: « créer un nouveau carnet ». */
        public String sentence() {
            return sentence;
        }
    }

    /** The answer to « save the changes first? ». */
    enum Unsaved {
        SAVE,
        DISCARD,
        CANCEL
    }

    /** A kind of export, with the extension it adds to a name that lacks it. */
    enum ExportFormat {
        MARKDOWN(".md", "Markdown (*.md)"),
        CSV(".csv", "CSV pour tableur (*.csv)");

        private final String extension;
        private final String description;

        ExportFormat(String extension, String description) {
            this.extension = extension;
            this.description = description;
        }

        public String extension() {
            return extension;
        }

        public String description() {
            return description;
        }
    }

    /** The notebook file to open, empty when the user cancels. {@code current} is the file of the open document, if any. */
    Optional<Path> chooseFileToOpen(Optional<Path> current);

    /** The file to save the notebook to, empty when the user cancels. The presenter adds the extension and asks about overwriting. */
    Optional<Path> chooseFileToSave(Optional<Path> current);

    /** The file to export to, empty when the user cancels. {@code suggested} is a proposed name (without a folder). */
    Optional<Path> chooseExportFile(ExportFormat format, String suggested);

    /** « Enregistrer / Ne pas enregistrer / Annuler » for the document {@code name}, which has unsaved changes. */
    Unsaved askToSave(String name, Action action);

    /** The autosaved notebook, left off the screen by an opened file, would be replaced by a new one: whether to go on. */
    boolean confirmReplaceDraft();

    /** Whether to replace the existing file {@code file}. */
    boolean confirmOverwrite(Path file);

    /** Dialogs that answer nothing: every choice is cancelled. For a presenter that never opens a file. */
    static NotebookDialogs cancelling() {
        return new NotebookDialogs() {
            @Override
            public Optional<Path> chooseFileToOpen(Optional<Path> current) {
                return Optional.empty();
            }

            @Override
            public Optional<Path> chooseFileToSave(Optional<Path> current) {
                return Optional.empty();
            }

            @Override
            public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
                return Optional.empty();
            }

            @Override
            public Unsaved askToSave(String name, Action action) {
                return Unsaved.CANCEL;
            }

            @Override
            public boolean confirmReplaceDraft() {
                return false;
            }

            @Override
            public boolean confirmOverwrite(Path file) {
                return false;
            }
        };
    }
}
