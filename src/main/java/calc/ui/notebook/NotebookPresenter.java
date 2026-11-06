package calc.ui.notebook;

import calc.document.DocumentException;
import calc.document.DocumentService;
import calc.export.NotebookExporter;
import calc.expression.ParsedLine;
import calc.expression.Span;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineError;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookException;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.persistence.StorageLimits;
import calc.ui.DisplayModeStore;
import calc.ui.ResultDescription;
import calc.ui.StatusTone;
import calc.ui.widget.DisplayModeText;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * All the interaction logic of the notebook window, without Swing (docs/adr/0010-editeur-carnet.md). It drives a
 * {@link DocumentService} (model, undo and redo, automatic save) and formats the results with the display mode in force.
 * Every intention of the user is a method that returns the new {@link NotebookViewModel}.
 *
 * <p>The view passes, with each intention, the text now in the field of the active line ({@code pending}): the presenter
 * keeps it as the <em>draft</em> of that line. A draft becomes a command of the model only when it is validated (Enter,
 * moving to another line, inserting or moving a line, closing); Escape drops it. Undo and redo act on validated commands:
 * undo first drops a pending draft, so that nothing the user typed is lost by surprise; redo refuses to run while a draft
 * is pending.
 *
 * <p>The model (the texts) changes here, on the event thread; the results come from a {@link NotebookRecalculation}, which
 * calculates elsewhere (docs/adr/0014-calcul-hors-interface.md). A line without its result yet says {@value #PENDING_RESULT}, a
 * view model never holds an old value, and files that only hold texts are written at once. An export needs the results: it
 * waits for them, and follows the notebook until they are there.
 *
 * <p>Not thread safe: the Swing event thread drives it.
 */
public final class NotebookPresenter {
    /** The longest line the window accepts: the longest string the file format reads back (the language keeps 1000). */
    public static final int MAX_LINE_LENGTH = StorageLimits.MAX_STRING_LENGTH;

    /** The examples of the watermark of an empty notebook. They are not saved. */
    public static final List<String> EXAMPLES = List.of("prix = 12,5", "prix * 3", "ans / 2");

    static final String DEFAULT_STATUS = "Une expression par ligne. Entrée valide la ligne, Échap annule la saisie en cours.";
    static final String READ_ONLY_STATUS = "Calc est déjà ouvert ou le carnet est en lecture seule : les modifications ne seront pas enregistrées.";
    private static final String WATERMARK = "Une expression par ligne, par exemple : " + String.join("   ·   ", EXAMPLES);

    /** What the column of the results says of a line not calculated yet. */
    static final String PENDING_RESULT = "Calcul en cours…";
    /** Why a line left by an interruption has no result. */
    static final String INTERRUPTED_LINE = "Non calculée : calcul interrompu";
    private static final BiFunction<String, Supplier<LineResult>, LineResult> JUST_EVALUATE = (text, evaluation) -> evaluation.get();
    // For the callers that do not choose: the application gives its own, one presenter having one calculation at a time.
    private static final Executor SHARED_CALCULATION = NotebookRecalculation.newBackgroundThread();

    private static final Logger LOGGER = Logger.getLogger(NotebookPresenter.class.getName());
    static final String RESERVED_FILE_STATUS = "Ce fichier est réservé à Calc : choisissez un autre nom.";
    static final String UNTITLED_NAME = "Carnet sans titre";
    private static final String TITLE_SUFFIX = " — Calc";
    private static final String MODIFIED_MARKER = "• ";
    static final String READ_ONLY_FILE_STATUS = "Ce carnet vient d'une version plus récente de Calc : il est en lecture seule. "
            + "« Enregistrer sous » en garde une copie.";

    private final DocumentService document;
    private final DisplayModeStore modeStore;
    private final NotebookDialogs dialogs;
    private DisplayMode mode;
    // Set by a quit that may go on; the next view model asks the window to close.
    private boolean closeRequested;
    // The active line; null while the notebook has no line (the window then shows one blank row).
    private LineId activeId;
    // The text of the active field, validated or not.
    private String draft = "";
    // The sentence of the last intention, and the one that lasts for the whole session when nothing is saved.
    private Message transientMessage;
    private final Message lastingMessage;
    private final NotebookRecalculation recalculation;
    // What the window hears of a calculation between two intentions; null until it listens.
    private Consumer<NotebookViewModel> viewListener;
    // Formatted once for the mode in force: a calculation shows a state per line, and 5000 lines are a lot to format each time.
    private final List<ShownResult> shownResults = new ArrayList<>();
    // Set while the lines are left without a result by Escape: what the status bar says about it.
    private Message interruption;
    // The file just opened, whose sentence counts the lines in error and so waits for the end of the calculation.
    private Path openedFile;
    private String openedNotice = "";
    private final List<WaitingExport> waitingExports = new ArrayList<>();

    /**
     * @param document  the document, the untitled current notebook loaded at start
     * @param loaded    what the store reported when it loaded the notebook: the sentence to show first (migration, read only...)
     * @param modeStore where the display mode is remembered
     */
    public NotebookPresenter(DocumentService document, LoadedNotebook loaded, DisplayModeStore modeStore) {
        this(document, loaded, modeStore, NotebookDialogs.cancelling());
    }

    /** @param dialogs how the user is asked for a file or a confirmation (the file menu) */
    public NotebookPresenter(DocumentService document, LoadedNotebook loaded, DisplayModeStore modeStore, NotebookDialogs dialogs) {
        this(document, loaded, modeStore, dialogs, SHARED_CALCULATION, JUST_EVALUATE);
    }

    /**
     * @param executor       what calculates: {@link #newRecalculationExecutor()} in the application, never the event thread
     * @param aroundEachLine called on the calculating thread with the text of each line really evaluated and its evaluation
     */
    NotebookPresenter(DocumentService document, LoadedNotebook loaded, DisplayModeStore modeStore, NotebookDialogs dialogs, Executor executor,
                      BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine) {
        this.document = Objects.requireNonNull(document, "document");
        this.modeStore = Objects.requireNonNull(modeStore, "modeStore");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        Objects.requireNonNull(loaded, "loaded");
        mode = document.displayMode() != DisplayMode.AUTO ? document.displayMode() : modeStore.load();
        Message first = loaded.statusMessage().map(text -> new Message(text, toneOf(loaded.condition()))).orElse(null);
        lastingMessage = document.isStoreReadOnly()
                ? new Message(first != null ? first.text() : READ_ONLY_STATUS, StatusTone.ERROR)
                : null;
        transientMessage = first;
        recalculation = new NotebookRecalculation(executor, aroundEachLine, new NotebookRecalculation.Events() {
            @Override
            public void progressed() {
                tellTheView();
            }

            @Override
            public void finished() {
                announceOpenedFile();
                writeWaitingExports();
                tellTheView();
            }

            @Override
            public void failed() {
                interrupt("Calcul interrompu par une erreur inattendue : ");
                tellTheView();
            }
        });
        if (!document.notebook().isEmpty()) {
            activeId = document.notebook().line(1).id();
            draft = document.notebook().line(1).text();
        }
        recalculate();
    }

    /**
     * The presenter of the notebook kept by {@code store}: loads it (migrating the old history on the first start) and
     * shows what the store reports. The caller closes the store.
     */
    public static NotebookPresenter open(NotebookStore store, DisplayModeStore modeStore) {
        return open(store, modeStore, NotebookDialogs.cancelling());
    }

    /** One background thread named {@value NotebookRecalculation#THREAD_NAME}: what calculates in the application. */
    public static Executor newRecalculationExecutor() {
        return NotebookRecalculation.newBackgroundThread();
    }

    /** Same as {@link #open(NotebookStore, DisplayModeStore)}, with the dialogs the file menu uses. */
    public static NotebookPresenter open(NotebookStore store, DisplayModeStore modeStore, NotebookDialogs dialogs) {
        LoadedNotebook loaded = store.load();
        return new NotebookPresenter(new DocumentService(store, loaded), loaded, modeStore, dialogs);
    }

    /** The presenter of what {@code store} has already loaded (off the event thread: a migration evaluates), calculating on {@code executor}. */
    public static NotebookPresenter open(NotebookStore store, LoadedNotebook loaded, DisplayModeStore modeStore, NotebookDialogs dialogs, Executor executor) {
        return new NotebookPresenter(new DocumentService(store, loaded), loaded, modeStore, dialogs, executor, JUST_EVALUATE);
    }

    static NotebookPresenter open(NotebookStore store, DisplayModeStore modeStore, NotebookDialogs dialogs, Executor executor,
                                  BiFunction<String, Supplier<LineResult>, LineResult> aroundEachLine) {
        LoadedNotebook loaded = store.load();
        return new NotebookPresenter(new DocumentService(store, loaded), loaded, modeStore, dialogs, executor, aroundEachLine);
    }

    /** {@code listener} hears, on the event thread, each new state a calculation produces (the count, the results, the end). */
    void setViewListener(Consumer<NotebookViewModel> listener) {
        viewListener = listener;
    }

    /** Whether a calculation is under way: a test waits for its end. */
    boolean isCalculating() {
        return recalculation.isRunning();
    }

    private static StatusTone toneOf(LoadedNotebook.Condition condition) {
        return condition == LoadedNotebook.Condition.MIGRATED ? StatusTone.SUCCESS : StatusTone.ERROR;
    }

    // ------------------------------------------------------------------------------------------------ intentions

    /** The state at start: the first line active, the focus in its field. */
    public NotebookViewModel initialView() {
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** The current state, without any change. */
    public NotebookViewModel currentView() {
        return snapshot(NotebookFocus.NONE);
    }

    /** Enter: validates the active line, then moves to the next one, or adds a line at the end unless the last one is blank. */
    public NotebookViewModel onEnter(String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        calculateAgainIfInterrupted();
        if (activeIndex() < document.notebook().size()) {
            select(activeIndex() + 1);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** ↑ (−1) or ↓ (+1): validates the active line and moves to the previous or next one; stays on the first or last. */
    public NotebookViewModel onMove(int delta, String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int target = activeIndex() + delta;
        if (target >= 0 && target <= document.notebook().size()) {
            select(target);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** The user went to the row {@code index} (from 0) with the mouse: the line left is validated. */
    public NotebookViewModel onActivate(int index, String pending) {
        syncDraft(pending);
        if (index == activeIndex() || index < 0 || index > document.notebook().size()) {
            return view(NotebookFocus.NONE);
        }
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        select(index);
        return view(NotebookFocus.NONE);
    }

    /**
     * Escape: drops what was typed in the active line since it was last validated. With nothing typed, it interrupts the
     * calculation under way: the lines left say so, and the next command calculates again.
     */
    public NotebookViewModel onCancelEdit(String pending) {
        syncDraft(pending);
        if (isDraftPending()) {
            dropDraft();
            say("Saisie annulée : la ligne retrouve son texte validé.", StatusTone.INFO);
        } else if (recalculation.isRunning() && !recalculation.isComplete()) {
            recalculation.stop();
            interrupt("Calcul interrompu : ");
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** Inserts an empty line below the active one and moves to it. */
    public NotebookViewModel onInsertBelow(String pending) {
        return insert(pending, true);
    }

    /** Inserts an empty line above the active one and moves to it. */
    public NotebookViewModel onInsertAbove(String pending) {
        return insert(pending, false);
    }

    /** Deletes the active line (its pending draft is dropped); references to it become explicit errors. */
    public NotebookViewModel onDelete(String pending) {
        syncDraft(pending);
        if (activeId == null) {
            say("Cette ligne n'est pas encore validée : il n'y a rien à supprimer (Échap abandonne la saisie).", StatusTone.INFO);
            calculateAgainIfInterrupted();
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        if (apply(new NotebookCommand.Delete(activeId))) {
            select(Math.min(index, document.notebook().size() - 1));
            say("Ligne " + (index + 1) + " supprimée. Les références à cette ligne sont signalées en erreur ; Annuler la rétablit.",
                    StatusTone.INFO);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** Moves the active line one place up; the line keeps its references. */
    public NotebookViewModel onMoveLineUp(String pending) {
        return moveLine(-1, pending);
    }

    /** Moves the active line one place down; the line keeps its references. */
    public NotebookViewModel onMoveLineDown(String pending) {
        return moveLine(1, pending);
    }

    /** Deletes every line. */
    public NotebookViewModel onClear(String pending) {
        syncDraft(pending);
        if (document.notebook().isEmpty()) {
            say("Le carnet est déjà vide.", StatusTone.INFO);
        } else if (apply(new NotebookCommand.Clear())) {
            select(0);
            say("Carnet vidé. Annuler le rétablit.", StatusTone.INFO);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /**
     * Undo. A pending draft is dropped first (like Escape); with nothing pending the last validated command is undone and
     * every result is recalculated.
     */
    public NotebookViewModel onUndo(String pending) {
        syncDraft(pending);
        if (isDraftPending()) {
            return onCancelEdit(draft);
        }
        if (!document.canUndo()) {
            say("Il n'y a rien à annuler.", StatusTone.INFO);
            calculateAgainIfInterrupted();
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        syncMode();
        document.undo();
        recalculate();
        reselect(index);
        say("Dernière modification annulée.", StatusTone.INFO);
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** Redo. Refused while a draft is pending, so that typing is never overwritten. */
    public NotebookViewModel onRedo(String pending) {
        syncDraft(pending);
        if (isDraftPending()) {
            say("Validez la saisie en cours (Entrée) ou annulez-la (Échap) avant de rétablir.", StatusTone.INFO);
            return view(NotebookFocus.ACTIVE_LINE);
        }
        if (!document.canRedo()) {
            say("Il n'y a rien à rétablir.", StatusTone.INFO);
            calculateAgainIfInterrupted();
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        syncMode();
        document.redo();
        recalculate();
        reselect(index);
        say("Modification rétablie.", StatusTone.INFO);
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** Shows the results in {@code newMode}; what is known is not calculated again, the mode is remembered and saved with the notebook. */
    public NotebookViewModel onDisplayModeChanged(DisplayMode newMode, String pending) {
        syncDraft(pending);
        if (newMode != null && newMode != mode) {
            mode = newMode;
            shownResults.clear();
            if (!document.isUntitled()) {
                // A document with a file is modified by a change of mode (ADR 0009): the title and the questions say so. The
                // untitled notebook follows at its next command, so that a mode change alone never writes.
                syncMode();
            }
            // The notebook file follows at the next command: a change of mode alone never writes (it would end the retry of
            // a history that could not be converted).
            modeStore.save(mode);
            say("Affichage : " + DisplayModeText.label(mode) + ".", StatusTone.INFO);
            recalculate();
        }
        return view(NotebookFocus.NONE);
    }

    /** Same as {@link #onDisplayModeChanged} with the mode that follows the current one (the first after the last). */
    public NotebookViewModel onNextDisplayMode(String pending) {
        DisplayMode[] modes = DisplayMode.values();
        return onDisplayModeChanged(modes[(mode.ordinal() + 1) % modes.length], pending);
    }

    // ------------------------------------------------------------------------------------------------ files

    /**
     * File > New: an empty untitled notebook. Asks first when that would lose work no file holds: unsaved changes of a
     * document with a file, and also an untitled notebook that is not empty, because the new one replaces the autosaved file.
     */
    public NotebookViewModel onNew(String pending) {
        syncDraft(pending);
        if (!commitDraft() || !mayLeave(NotebookDialogs.Action.NEW)) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        if (!document.isUntitled()) {
            mode = modeStore.load();
        }
        syncMode();
        document.newDocument();
        recalculate();
        select(0);
        say("Nouveau carnet." + abandonWaitingExports(), StatusTone.INFO);
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /**
     * File > Open: asks for a file, then for what to do with unsaved changes, then opens it. Whatever goes wrong, the document
     * in the window is still there, with its changes.
     */
    public NotebookViewModel onOpen(String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        // The file is chosen first: cancelling the chooser must not cost the user the question about their changes.
        Optional<Path> chosen = dialogs.chooseFileToOpen(document.path());
        if (chosen.isPresent() && mayLeave(NotebookDialogs.Action.OPEN)) {
            openFile(chosen.get());
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** File > Save: writes the document to its file; an untitled document (or one from a newer version) asks for a name. */
    public NotebookViewModel onSave(String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        if (document.isReadOnlyFile()) {
            say(READ_ONLY_FILE_STATUS, StatusTone.ERROR);
        } else {
            saveInteractively();
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** File > Save as: asks for a name, adds the extension, asks before replacing a file, and associates the document with it. */
    public NotebookViewModel onSaveAs(String pending) {
        syncDraft(pending);
        if (commitDraft()) {
            saveAs();
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** File > Export: writes the notebook as it is shown, in the display mode in force, to a file the user names. */
    public NotebookViewModel onExport(NotebookDialogs.ExportFormat format, String pending) {
        syncDraft(pending);
        if (commitDraft()) {
            export(format);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /**
     * The window is closing (File > Quit, the close button, Cmd+Q): a pending draft is validated first, so that nothing typed
     * is lost, then the user is asked about unsaved changes. {@link NotebookViewModel#closeWindow()} tells the window to go.
     */
    public NotebookViewModel onQuit(String pending) {
        syncDraft(pending);
        closeRequested = commitDraft() && mayLeave(NotebookDialogs.Action.QUIT) && mayAbandonWaitingExports();
        if (closeRequested) {
            recalculation.stop();
        }
        return view(closeRequested ? NotebookFocus.NONE : NotebookFocus.ACTIVE_LINE);
    }

    // A file the user asked for is not lost in silence by closing the window: the user decides, as for unsaved changes.
    private boolean mayAbandonWaitingExports() {
        if (waitingExports.isEmpty() || !dialogs.confirmAbandonExport()) {
            return waitingExports.isEmpty();
        }
        abandonWaitingExports();
        return true;
    }

    // Whether the document may be replaced or closed. Unsaved work is asked about: the changes of a document with a file, and
    // the untitled notebook when New would replace the autosaved file (Open leaves it, Quit keeps it for the next start).
    private boolean mayLeave(NotebookDialogs.Action action) {
        boolean unsaved = document.isUntitled()
                ? !document.notebook().isEmpty() && (action == NotebookDialogs.Action.NEW || !isAutosaved())
                : document.isModified();
        if (unsaved) {
            switch (dialogs.askToSave(documentName(), action)) {
                case CANCEL -> {
                    return false;
                }
                case SAVE -> {
                    if (!saveInteractively()) {
                        return false;
                    }
                }
                case DISCARD -> {
                }
            }
        }
        // A new notebook also replaces the autosaved draft that an opened file has taken off the screen.
        return action != NotebookDialogs.Action.NEW || !document.isDraftOffScreen() || dialogs.confirmReplaceDraft();
    }

    // Whether the untitled notebook is really kept by the automatic save: not when another instance holds the data directory,
    // when the file is from a newer version, or when the last automatic save failed.
    private boolean isAutosaved() {
        return !document.isStoreReadOnly() && document.autosaveProblem().isEmpty();
    }

    private void openFile(Path file) {
        try {
            document.open(file);
        } catch (DocumentException exception) {
            say(exception.kind() == DocumentException.Kind.INPUT_OUTPUT
                    ? exception.getMessage()
                    : "Impossible d'ouvrir « " + fileName(file) + " » : " + exception.getMessage(), StatusTone.ERROR);
            return;
        }
        // The file brings its display mode; the remembered preference is only for the untitled notebook.
        mode = document.displayMode();
        shownResults.clear();
        select(0);
        transientMessage = null;
        openedNotice = abandonWaitingExports();
        openedFile = file;
        recalculate();
    }

    // The sentence of a file just opened, now that the lines in error are known.
    private void announceOpenedFile() {
        Path file = openedFile;
        if (file == null) {
            return;
        }
        long errors = recalculation.known().stream().filter(LineResult::isError).count();
        int lines = document.notebook().size();
        String errorSentence = errors == 0 ? "" : errors == 1 ? " 1 ligne contient une erreur." : " " + errors + " lignes contiennent une erreur.";
        if (document.isReadOnlyFile()) {
            say(READ_ONLY_FILE_STATUS + errorSentence + openedNotice, StatusTone.ERROR);
        } else {
            say("« " + fileName(file) + " » ouvert (" + lines + (lines > 1 ? " lignes)." : " ligne).") + errorSentence + openedNotice, StatusTone.INFO);
        }
    }

    // Saves to the file of the document, or asks for one. False when nothing was written (cancelled or failed, and said so).
    private boolean saveInteractively() {
        if (document.isUntitled() || document.isReadOnlyFile()) {
            return saveAs();
        }
        syncMode();
        try {
            document.save();
        } catch (DocumentException exception) {
            say(exception.getMessage(), StatusTone.ERROR);
            return false;
        }
        confirm("« " + documentName() + " » enregistré.");
        return true;
    }

    private boolean saveAs() {
        Optional<Path> chosen = dialogs.chooseFileToSave(document.path());
        if (chosen.isEmpty()) {
            return false;
        }
        Path file = chosen.get();
        if (file.getFileName() != null) {
            Path target = DocumentService.withExtension(file.toAbsolutePath().normalize());
            if (document.isReservedPath(target)) {
                say(RESERVED_FILE_STATUS, StatusTone.ERROR);
                return false;
            }
            boolean sameAsTheDocument = document.path().filter(target::equals).isPresent();
            if (Files.exists(target) && !sameAsTheDocument && !dialogs.confirmOverwrite(target)) {
                return false;
            }
        }
        syncMode();
        try {
            Path written = document.saveAs(file);
            confirm("Carnet enregistré dans " + written + ".");
            return true;
        } catch (DocumentException exception) {
            say(exception.getMessage(), StatusTone.ERROR);
            return false;
        }
    }

    private void export(NotebookDialogs.ExportFormat format) {
        Optional<Path> chosen = dialogs.chooseExportFile(format, exportBaseName() + format.extension());
        if (chosen.isEmpty()) {
            return;
        }
        Path file = chosen.get().toAbsolutePath().normalize();
        if (file.getFileName() == null) {
            say("Ce chemin ne désigne pas un fichier : " + file, StatusTone.ERROR);
            return;
        }
        String name = file.getFileName().toString();
        // The extension is forced, so the target can be neither a file Calc keeps (none ends in .md or .csv) nor the document.
        Path target = name.toLowerCase(Locale.ROOT).endsWith(format.extension()) ? file : file.resolveSibling(name + format.extension());
        if (Files.exists(target) && !dialogs.confirmOverwrite(target)) {
            return;
        }
        waitingExports.add(new WaitingExport(format, target));
        if (!recalculation.isComplete() && !recalculation.isRunning()) {
            recalculate();
        }
    }

    // Writes what waits for the results: they are all known, and they are those of the notebook as it is now.
    private void writeWaitingExports() {
        List<WaitingExport> exports = List.copyOf(waitingExports);
        waitingExports.clear();
        for (WaitingExport export : exports) {
            write(export.format(), export.target(), new EvaluatedNotebook(document.notebook(), recalculation.known()));
        }
    }

    // The exports nobody will write any more, as the sentence that says so ("" if there are none).
    private String abandonWaitingExports() {
        if (waitingExports.isEmpty()) {
            return "";
        }
        waitingExports.clear();
        return " L'export demandé n'a pas été écrit.";
    }

    private void write(NotebookDialogs.ExportFormat format, Path target, EvaluatedNotebook evaluated) {
        try {
            switch (format) {
                case MARKDOWN -> NotebookExporter.writeMarkdown(target, evaluated, exportTitle(), mode);
                case CSV -> NotebookExporter.writeCsv(target, evaluated, mode);
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot export to " + target, exception);
            say("Impossible d'exporter vers " + target + " : le fichier n'a pas pu être écrit.", StatusTone.ERROR);
            return;
        }
        confirm("Exporté vers " + target);
    }

    private String documentName() {
        return document.path().map(NotebookPresenter::fileName).orElse(UNTITLED_NAME);
    }

    private static String fileName(Path file) {
        return file.getFileName() == null ? file.toString() : file.getFileName().toString();
    }

    // The name of the document without the extension of a notebook file, null for the untitled one.
    private String exportTitle() {
        return document.path().map(NotebookPresenter::fileName).map(NotebookPresenter::withoutExtension).orElse(null);
    }

    private String exportBaseName() {
        String title = exportTitle();
        return title == null ? "carnet" : title;
    }

    private static String withoutExtension(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(DocumentService.EXTENSION)
                ? name.substring(0, name.length() - DocumentService.EXTENSION.length())
                : name;
    }

    private String title() {
        return (document.isModified() ? MODIFIED_MARKER : "") + documentName()
                + (document.isReadOnlyFile() ? " (lecture seule)" : "") + TITLE_SUFFIX;
    }

    // ------------------------------------------------------------------------------------------------ internals

    private NotebookViewModel insert(String pending, boolean below) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        NotebookCommand command = activeId == null
                ? new NotebookCommand.Append("")
                : below ? new NotebookCommand.InsertAfter(activeId, "") : new NotebookCommand.InsertBefore(activeId, "");
        if (apply(command)) {
            select(activeId != null && below ? index + 1 : index);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    private NotebookViewModel moveLine(int delta, String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        int target = index + delta;
        if (activeId == null) {
            say("Cette ligne n'est pas encore validée : elle ne peut pas être déplacée.", StatusTone.INFO);
        } else if (target < 0 || target >= document.notebook().size()) {
            say(delta < 0 ? "La ligne est déjà la première." : "La ligne est déjà la dernière.", StatusTone.INFO);
        } else if (apply(new NotebookCommand.Move(activeId, target))) {
            select(target);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    // Records what the field holds. A line break cannot be part of a line: the field never produces one, a caller might.
    private void syncDraft(String pending) {
        if (pending != null) {
            draft = pending.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        }
    }

    private String modelText() {
        return activeId == null ? "" : document.notebook().line(activeIndex() + 1).text();
    }

    private boolean isDraftPending() {
        return !draft.equals(modelText());
    }

    private void dropDraft() {
        draft = modelText();
    }

    // Turns the draft into a command of the model. False, and nothing changed, when the line is refused.
    private boolean commitDraft() {
        if (draft.length() > MAX_LINE_LENGTH) {
            say("La ligne dépasse " + NumberFormat.getIntegerInstance(Locale.FRANCE).format(MAX_LINE_LENGTH)
                    + " caractères : raccourcissez-la pour la valider.", StatusTone.ERROR);
            return false;
        }
        if (activeId == null) {
            if (draft.isBlank()) {
                return true;
            }
            if (!apply(new NotebookCommand.Append(draft))) {
                return false;
            }
            select(document.notebook().size() - 1);
            return true;
        }
        return !isDraftPending() || apply(new NotebookCommand.Edit(activeId, draft));
    }

    private boolean apply(NotebookCommand command) {
        syncMode();
        try {
            Notebook before = document.notebook();
            document.apply(command);
            transientMessage = null;
            openedFile = null;
            if (document.notebook() != before) {
                recalculate();
            }
            return true;
        } catch (NotebookException exception) {
            say(exception.getMessage(), StatusTone.ERROR);
            return false;
        }
    }

    // The mode is saved with the notebook; it follows the presenter at the next write.
    private void syncMode() {
        if (document.displayMode() != mode) {
            document.setDisplayMode(mode);
        }
    }

    private int activeIndex() {
        Notebook notebook = document.notebook();
        return activeId == null ? notebook.size() : notebook.numberOf(activeId).orElseThrow() - 1;
    }

    // Makes the row at index (from 0) active; its draft is its text. The row after the last line is the new line, which
    // the model only knows once it holds something. An index out of range means that row.
    private void select(int index) {
        Notebook notebook = document.notebook();
        if (index < 0 || index >= notebook.size()) {
            activeId = null;
            draft = "";
        } else {
            activeId = notebook.line(index + 1).id();
            draft = notebook.line(index + 1).text();
        }
    }

    // After an undo or a redo: the same line if it still exists, otherwise the line at the same place.
    private void reselect(int previousIndex) {
        Notebook notebook = document.notebook();
        if (activeId != null && notebook.contains(activeId)) {
            select(activeIndex());
        } else {
            select(activeId == null ? notebook.size() : Math.min(previousIndex, notebook.size() - 1));
        }
    }

    private void say(String text, StatusTone tone) {
        transientMessage = new Message(text, tone);
        openedFile = null;
    }

    // ------------------------------------------------------------------------------------------------ the calculation

    // Replaces the calculation under way by that of the notebook as it is now; called as soon as the notebook changes, so that
    // what the window hears during a dialog is never about an old one.
    private void recalculate() {
        interruption = null;
        if (recalculation.calculate(document.notebook())) {
            announceOpenedFile();
        }
    }

    // The next command after an interruption calculates again, even one that changes nothing.
    private void calculateAgainIfInterrupted() {
        if (interruption != null) {
            recalculate();
        }
    }

    private void interrupt(String sentence) {
        transientMessage = null;
        openedFile = null;
        interruption = new Message(sentence + recalculation.knownCount() + " / " + document.notebook().size() + " lignes calculées."
                + " La prochaine commande relance le calcul." + abandonWaitingExports(), StatusTone.ERROR);
    }

    private void tellTheView() {
        if (viewListener != null) {
            viewListener.accept(snapshot(NotebookFocus.NONE));
        }
    }

    private record WaitingExport(NotebookDialogs.ExportFormat format, Path target) {
    }

    private record ShownResult(LineResult result, NotebookRow row) {
    }

    // A confirmation of a file operation (saved, exported) is shown even over the lasting read-only warnings: the user must
    // be able to tell that the operation happened.
    private record Message(String text, StatusTone tone, boolean confirmation) {
        Message(String text, StatusTone tone) {
            this(text, tone, false);
        }
    }

    private void confirm(String text) {
        transientMessage = new Message(text, StatusTone.SUCCESS, true);
        openedFile = null;
    }

    // ------------------------------------------------------------------------------------------------ view model

    // The state an intention ends on. A quit that went on stopped the calculation for good; anything after it calculates again.
    private NotebookViewModel view(NotebookFocus focus) {
        if (!closeRequested && interruption == null && !recalculation.isRunning() && !recalculation.isComplete()) {
            recalculate();
        }
        // Last, so that the sentence of the command does not hide whether the file was written.
        if (recalculation.isComplete()) {
            writeWaitingExports();
        }
        return snapshot(focus);
    }

    private NotebookViewModel snapshot(NotebookFocus focus) {
        Notebook notebook = document.notebook();
        List<NotebookRow> rows = new ArrayList<>(notebook.size() + 1);
        for (int index = 0; index < recalculation.knownCount(); index++) {
            rows.add(shownResult(index));
        }
        for (int number = rows.size() + 1; number <= notebook.size(); number++) {
            String text = notebook.line(number).text();
            rows.add(interruption != null ? interruptedRow(number, text) : pendingRow(number, text));
        }
        // The new line at the end: always there, it only becomes a line of the notebook when it is validated with a text.
        rows.add(new NotebookRow(rows.size() + 1, "", NotebookRow.Status.EMPTY, "", "", "", -1, -1, "", "Ligne vide"));
        Message status = status();
        boolean showHelp = notebook.isEmpty() && draft.isEmpty();
        boolean close = closeRequested;
        closeRequested = false;
        return new NotebookViewModel(rows, activeIndex(), draft, focus, document.canUndo(), document.canRedo(), mode,
                status.text(), status.tone(), showHelp ? WATERMARK : "", title(), !document.isReadOnlyFile(), close);
    }

    // The count of a calculation under way comes first, followed by what cannot wait for its end: see urgentError. Then that
    // error alone, then the interruption of the last calculation. Then, when the notebook is not being saved, that warning (an
    // information never replaces it), otherwise the last intention, then the usual hint.
    private Message status() {
        Message urgent = urgentError();
        if (recalculation.isRunning()) {
            String count = "Calcul en cours : " + recalculation.knownCount() + " / " + document.notebook().size() + " lignes";
            return urgent == null ? new Message(count, StatusTone.INFO) : new Message(count + ". " + urgent.text(), StatusTone.ERROR);
        }
        if (urgent != null) {
            return urgent;
        }
        // Interrupting empties the sentence of the last intention: one that is there now is newer, and says what that intention did.
        if (interruption != null && transientMessage == null) {
            return interruption;
        }
        if (transientMessage != null && transientMessage.confirmation()) {
            return transientMessage;
        }
        if (document.isReadOnlyFile()) {
            return new Message(READ_ONLY_FILE_STATUS, StatusTone.ERROR);
        }
        // The warning about the untitled notebook says nothing of a document that has a file of its own.
        if (lastingMessage != null && document.isUntitled()) {
            return lastingMessage;
        }
        return transientMessage != null ? transientMessage : new Message(DEFAULT_STATUS, StatusTone.INFO);
    }

    // A refusal of the last intention, so that a key that did nothing says why; then the failure of the automatic save, which the
    // user must act on. Null when there is neither.
    private Message urgentError() {
        if (transientMessage != null && transientMessage.tone() == StatusTone.ERROR) {
            return transientMessage;
        }
        if (document.isUntitled() && document.autosaveProblem().isPresent()) {
            return new Message("Enregistrement automatique impossible : " + document.autosaveProblem().get()
                    + " Le carnet reste disponible dans cette fenêtre.", StatusTone.ERROR);
        }
        return null;
    }

    private NotebookRow shownResult(int index) {
        LineResult result = recalculation.result(index);
        if (index < shownResults.size() && shownResults.get(index).result() == result) {
            return shownResults.get(index).row();
        }
        ShownResult shown = new ShownResult(result, row(result));
        if (index < shownResults.size()) {
            shownResults.set(index, shown);
        } else {
            shownResults.add(shown);
        }
        return shown.row();
    }

    private static NotebookRow pendingRow(int number, String text) {
        return new NotebookRow(number, text, NotebookRow.Status.PENDING, PENDING_RESULT, "", "", -1, -1, "", PENDING_RESULT);
    }

    private static NotebookRow interruptedRow(int number, String text) {
        return new NotebookRow(number, text, NotebookRow.Status.ERROR, "", "", INTERRUPTED_LINE, -1, -1, "", INTERRUPTED_LINE);
    }

    private NotebookRow row(LineResult result) {
        String name = result.assignedName().orElse("");
        String suffix = name.isEmpty() ? "" : ", définit la variable " + name;
        ParsedLine.Kind kind = result.kind();
        if (result.isError()) {
            LineError error = result.error();
            Span span = error.span();
            int end = Math.min(span.end(), result.text().length());
            boolean marked = span.length() > 0 && span.start() < end;
            return new NotebookRow(result.number(), result.text(), NotebookRow.Status.ERROR, "", "", error.message(),
                    marked ? span.start() : -1, marked ? end : -1, name, "Erreur : " + error.message() + suffix);
        }
        if (result.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(result.value(), mode);
            String text = formatted.text();
            return new NotebookRow(result.number(), result.text(),
                    formatted.exact() ? NotebookRow.Status.EXACT : NotebookRow.Status.APPROXIMATE, text,
                    ResultDescription.describe(formatted), "", -1, -1, name,
                    "Résultat : " + text + (formatted.exact() ? ", exact" : ", approché") + suffix);
        }
        boolean comment = kind == ParsedLine.Kind.COMMENT;
        return new NotebookRow(result.number(), result.text(), comment ? NotebookRow.Status.COMMENT : NotebookRow.Status.EMPTY,
                "", "", "", -1, -1, "", comment ? "Commentaire" : "Ligne vide");
    }
}
