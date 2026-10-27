package calc.document;

import calc.engine.AtomicFileWriter;
import calc.format.DisplayMode;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookEditor;
import calc.notebook.NotebookException;
import calc.persistence.DecodedNotebook;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookFormatException;
import calc.persistence.NotebookStore;
import calc.persistence.NotebookStoreException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The document the user is working on, ready for a presenter (docs/adr/0009-documents-et-exports.md, section 4): no dialog
 * here, the path of a file is a parameter.
 *
 * <p>A document is either <strong>untitled</strong>, the current notebook, which is saved automatically by the
 * {@link NotebookStore} after every command that changes it, or <strong>associated with a path</strong> (after
 * {@link #open} or {@link #saveAs}), which is never saved automatically: it changes the file only on {@link #save()}, and
 * {@link #isModified()} tells whether it differs from the file. A document from a newer version of the format is never
 * written back. Not thread safe: one thread (the Swing event thread) drives it.
 */
public final class DocumentService {
    private static final Logger LOGGER = Logger.getLogger(DocumentService.class.getName());

    /** The extension of a notebook file. */
    public static final String EXTENSION = ".calc.json";

    private final NotebookStore store;
    private final AtomicFileWriter.Mover mover;
    private NotebookEditor editor;
    private DisplayMode displayMode;
    // Null for the untitled document, otherwise the absolute, normalised path of its file.
    private Path path;
    private Notebook savedNotebook;
    private DisplayMode savedMode;
    private boolean newerVersion;
    private String autosaveProblem;

    /** A service on the notebook that {@code store} has just loaded, as the untitled document. */
    public DocumentService(NotebookStore store, LoadedNotebook loaded) {
        this(store, loaded, Files::move);
    }

    /** @param mover the final move of every explicit save, replaceable by a test that simulates a failing disk */
    public DocumentService(NotebookStore store, LoadedNotebook loaded, AtomicFileWriter.Mover mover) {
        this.store = Objects.requireNonNull(store, "store");
        this.mover = Objects.requireNonNull(mover, "mover");
        this.editor = new NotebookEditor(loaded.notebook());
        this.displayMode = loaded.displayMode();
    }

    // ------------------------------------------------------------------------------------------------ state

    /** The notebook and the result of each of its lines. */
    public EvaluatedNotebook current() {
        return editor.current();
    }

    public DisplayMode displayMode() {
        return displayMode;
    }

    /** The file of the document, empty for the untitled document. */
    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    public boolean isUntitled() {
        return path == null;
    }

    /**
     * Whether a document associated with a file differs from that file (a later undo to the saved state clears it). Always
     * false for the untitled document: it is saved automatically.
     */
    public boolean isModified() {
        return path != null && (!editor.notebook().equals(savedNotebook) || displayMode != savedMode);
    }

    /** Whether the document comes from a newer version of the format and is never written back. */
    public boolean isReadOnlyFile() {
        return newerVersion;
    }

    /** Whether the untitled document is not being saved: another instance holds the data directory, or its file is from a newer version. */
    public boolean isStoreReadOnly() {
        return store.isReadOnly();
    }

    /** Why the last automatic save failed, empty when it succeeded or none was needed. */
    public Optional<String> autosaveProblem() {
        return Optional.ofNullable(autosaveProblem);
    }

    /** Whether replacing the document (new, open) would lose something that is not in a file: ask the user first. */
    public boolean wouldLoseWork() {
        return path == null ? !editor.notebook().isEmpty() : isModified();
    }

    // ------------------------------------------------------------------------------------------------ editing

    /** Applies {@code command}; an untitled document is saved automatically when it changed. */
    public EvaluatedNotebook apply(NotebookCommand command) throws NotebookException {
        Notebook before = editor.notebook();
        EvaluatedNotebook result = editor.apply(command);
        if (!result.notebook().equals(before)) {
            autosave();
        }
        return result;
    }

    public boolean canUndo() {
        return editor.canUndo();
    }

    public boolean canRedo() {
        return editor.canRedo();
    }

    public EvaluatedNotebook undo() {
        EvaluatedNotebook result = editor.undo();
        autosave();
        return result;
    }

    public EvaluatedNotebook redo() {
        EvaluatedNotebook result = editor.redo();
        autosave();
        return result;
    }

    /** The display mode saved with the document: a change of it modifies a document associated with a file. */
    public void setDisplayMode(DisplayMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (mode != displayMode) {
            displayMode = mode;
            autosave();
        }
    }

    // ------------------------------------------------------------------------------------------------ documents

    /** An empty untitled document, saved as the current notebook. The undo history is forgotten. */
    public EvaluatedNotebook newDocument() {
        install(Notebook.empty(), null, false);
        autosave();
        return editor.current();
    }

    /**
     * Opens a file, which may come from anyone. Nothing in it is a path or a command, only the texts of the lines are read,
     * under the limits of the format. The autosaved draft is left as it is. A file from a newer version is opened read only
     * when its lines can be read.
     */
    public EvaluatedNotebook open(Path file) throws DocumentException {
        Path target = absolute(file);
        refuseReserved(target);
        DecodedNotebook decoded;
        try {
            decoded = NotebookCodec.read(target);
        } catch (NotebookFormatException exception) {
            throw new DocumentException(DocumentException.Kind.INVALID_FILE, exception.userMessage(), exception);
        } catch (IOException exception) {
            throw new DocumentException(DocumentException.Kind.INPUT_OUTPUT, "Impossible de lire le fichier : " + target.getFileName(),
                    exception);
        }
        if (!decoded.linesRead()) {
            throw new DocumentException(DocumentException.Kind.UNREADABLE_NEWER_VERSION,
                    "Ce carnet a été créé par une version plus récente de Calc : il ne peut pas être lu.");
        }
        displayMode = decoded.displayMode();
        install(decoded.notebook(), target, decoded.isNewerVersion());
        return editor.current();
    }

    /** Writes the document to its file. Only for a document associated with a file ({@link #isUntitled()} is false). */
    public void save() throws DocumentException {
        if (path == null) {
            throw new IllegalStateException("an untitled document has no file: use saveAs");
        }
        if (newerVersion) {
            throw readOnlyVersion();
        }
        write(path);
        markSaved();
    }

    /**
     * Writes the document to {@code file} (with {@value #EXTENSION} added when the name does not end with it) and
     * associates the document with it. The first save of the untitled document also empties the autosaved draft: the
     * content now lives in the file.
     *
     * @return the path actually written
     */
    public Path saveAs(Path file) throws DocumentException {
        Path target = withExtension(absolute(file));
        refuseReserved(target);
        if (newerVersion && sameFile(target, path)) {
            throw readOnlyVersion();
        }
        write(target);
        boolean wasUntitled = path == null;
        path = target;
        newerVersion = false;
        markSaved();
        if (wasUntitled) {
            try {
                store.saveCurrent(Notebook.empty(), displayMode);
            } catch (NotebookStoreException exception) {
                LOGGER.log(Level.WARNING, "The saved document is in " + target + " but the draft could not be emptied", exception);
            }
        }
        return target;
    }

    /** {@code file} with {@value #EXTENSION} appended unless its name already ends with it, whatever the case. */
    public static Path withExtension(Path file) {
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? file : file.resolveSibling(name + EXTENSION);
    }

    // ------------------------------------------------------------------------------------------------ internals

    private void install(Notebook notebook, Path file, boolean newer) {
        editor = new NotebookEditor(notebook);
        path = file;
        newerVersion = newer;
        autosaveProblem = null;
        markSaved();
    }

    private void markSaved() {
        savedNotebook = editor.notebook();
        savedMode = displayMode;
    }

    private void autosave() {
        if (path != null) {
            return;
        }
        try {
            store.saveCurrent(editor.notebook(), displayMode);
            autosaveProblem = null;
        } catch (NotebookStoreException exception) {
            autosaveProblem = exception.getMessage();
        }
    }

    private void write(Path target) throws DocumentException {
        try {
            NotebookCodec.Encoded encoded = NotebookCodec.encode(editor.notebook(), displayMode);
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            AtomicFileWriter.write(target, out -> out.write(encoded.bytes()), mover);
        } catch (NotebookFormatException exception) {
            throw new DocumentException(DocumentException.Kind.TOO_LARGE_TO_SAVE, exception.userMessage(), exception);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot write " + target, exception);
            throw new DocumentException(DocumentException.Kind.INPUT_OUTPUT,
                    "Impossible d'enregistrer le fichier : " + target.getFileName(), exception);
        }
    }

    private void refuseReserved(Path target) throws DocumentException {
        if (store.isReserved(target)) {
            throw new DocumentException(DocumentException.Kind.RESERVED_PATH,
                    "Ce fichier est réservé à Calc : choisissez un autre nom.");
        }
    }

    private static DocumentException readOnlyVersion() {
        return new DocumentException(DocumentException.Kind.READ_ONLY_VERSION,
                "Ce carnet a été créé par une version plus récente de Calc : il est en lecture seule. Enregistrez-le sous un autre nom.");
    }

    private static Path absolute(Path file) {
        return file.toAbsolutePath().normalize();
    }

    private static boolean sameFile(Path first, Path second) {
        if (second == null) {
            return false;
        }
        try {
            return Files.exists(first) && Files.exists(second) ? Files.isSameFile(first, second) : first.equals(second);
        } catch (IOException exception) {
            return first.equals(second);
        }
    }
}
