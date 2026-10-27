package calc.ui.notebook;

import calc.document.DocumentService;
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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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

    private final DocumentService document;
    private final DisplayModeStore modeStore;
    private DisplayMode mode;
    // The active line; null while the notebook has no line (the window then shows one blank row).
    private LineId activeId;
    // The text of the active field, validated or not.
    private String draft = "";
    // The sentence of the last intention, and the one that lasts for the whole session when nothing is saved.
    private Message transientMessage;
    private final Message lastingMessage;

    /**
     * @param document  the document, the untitled current notebook loaded at start
     * @param loaded    what the store reported when it loaded the notebook: the sentence to show first (migration, read only...)
     * @param modeStore where the display mode is remembered
     */
    public NotebookPresenter(DocumentService document, LoadedNotebook loaded, DisplayModeStore modeStore) {
        this.document = Objects.requireNonNull(document, "document");
        this.modeStore = Objects.requireNonNull(modeStore, "modeStore");
        Objects.requireNonNull(loaded, "loaded");
        mode = document.displayMode() != DisplayMode.AUTO ? document.displayMode() : modeStore.load();
        Message first = loaded.statusMessage().map(text -> new Message(text, toneOf(loaded.condition()))).orElse(null);
        lastingMessage = document.isStoreReadOnly()
                ? new Message(first != null ? first.text() : READ_ONLY_STATUS, StatusTone.ERROR)
                : null;
        transientMessage = first;
        if (!document.current().notebook().isEmpty()) {
            activeId = document.current().notebook().line(1).id();
            draft = document.current().notebook().line(1).text();
        }
    }

    /**
     * The presenter of the notebook kept by {@code store}: loads it (migrating the old history on the first start) and
     * shows what the store reports. The caller closes the store.
     */
    public static NotebookPresenter open(NotebookStore store, DisplayModeStore modeStore) {
        LoadedNotebook loaded = store.load();
        return new NotebookPresenter(new DocumentService(store, loaded), loaded, modeStore);
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
        return view(NotebookFocus.NONE);
    }

    /** Enter: validates the active line, then moves to the next one, or adds a line at the end unless the last one is blank. */
    public NotebookViewModel onEnter(String pending) {
        syncDraft(pending);
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        if (activeIndex() < document.current().notebook().size()) {
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
        if (target >= 0 && target <= document.current().notebook().size()) {
            select(target);
        }
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** The user went to the row {@code index} (from 0) with the mouse: the line left is validated. */
    public NotebookViewModel onActivate(int index, String pending) {
        syncDraft(pending);
        if (index == activeIndex() || index < 0 || index > document.current().notebook().size()) {
            return view(NotebookFocus.NONE);
        }
        if (!commitDraft()) {
            return view(NotebookFocus.ACTIVE_LINE);
        }
        select(index);
        return view(NotebookFocus.NONE);
    }

    /** Escape: drops what was typed in the active line since it was last validated. */
    public NotebookViewModel onCancelEdit(String pending) {
        syncDraft(pending);
        if (isDraftPending()) {
            dropDraft();
            say("Saisie annulée : la ligne retrouve son texte validé.", StatusTone.INFO);
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
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        if (apply(new NotebookCommand.Delete(activeId))) {
            select(Math.min(index, document.current().notebook().size() - 1));
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
        if (document.current().notebook().isEmpty()) {
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
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        syncMode();
        document.undo();
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
            return view(NotebookFocus.ACTIVE_LINE);
        }
        int index = activeIndex();
        syncMode();
        document.redo();
        reselect(index);
        say("Modification rétablie.", StatusTone.INFO);
        return view(NotebookFocus.ACTIVE_LINE);
    }

    /** Shows the results in {@code newMode}; nothing is calculated again, the mode is remembered and saved with the notebook. */
    public NotebookViewModel onDisplayModeChanged(DisplayMode newMode, String pending) {
        syncDraft(pending);
        if (newMode != null && newMode != mode) {
            mode = newMode;
            // The notebook file follows at the next command: a change of mode alone never writes (it would end the retry of
            // a history that could not be converted).
            modeStore.save(mode);
            say("Affichage : " + DisplayModeText.label(mode) + ".", StatusTone.INFO);
        }
        return view(NotebookFocus.NONE);
    }

    /** Same as {@link #onDisplayModeChanged} with the mode that follows the current one (the first after the last). */
    public NotebookViewModel onNextDisplayMode(String pending) {
        DisplayMode[] modes = DisplayMode.values();
        return onDisplayModeChanged(modes[(mode.ordinal() + 1) % modes.length], pending);
    }

    /** The window is closing: a pending draft is validated first, so that nothing typed is lost. */
    public NotebookViewModel onClosing(String pending) {
        syncDraft(pending);
        commitDraft();
        return view(NotebookFocus.NONE);
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
        } else if (target < 0 || target >= document.current().notebook().size()) {
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
        return activeId == null ? "" : document.current().notebook().line(activeIndex() + 1).text();
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
            select(document.current().notebook().size() - 1);
            return true;
        }
        return !isDraftPending() || apply(new NotebookCommand.Edit(activeId, draft));
    }

    private boolean apply(NotebookCommand command) {
        syncMode();
        try {
            document.apply(command);
            transientMessage = null;
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
        Notebook notebook = document.current().notebook();
        return activeId == null ? notebook.size() : notebook.numberOf(activeId).orElseThrow() - 1;
    }

    // Makes the row at index (from 0) active; its draft is its text. The row after the last line is the new line, which
    // the model only knows once it holds something. An index out of range means that row.
    private void select(int index) {
        Notebook notebook = document.current().notebook();
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
        Notebook notebook = document.current().notebook();
        if (activeId != null && notebook.contains(activeId)) {
            select(activeIndex());
        } else {
            select(activeId == null ? notebook.size() : Math.min(previousIndex, notebook.size() - 1));
        }
    }

    private void say(String text, StatusTone tone) {
        transientMessage = new Message(text, tone);
    }

    private record Message(String text, StatusTone tone) {
    }

    // ------------------------------------------------------------------------------------------------ view model

    private NotebookViewModel view(NotebookFocus focus) {
        EvaluatedNotebook evaluated = document.current();
        List<NotebookRow> rows = new ArrayList<>(evaluated.results().size() + 1);
        for (LineResult result : evaluated.results()) {
            rows.add(row(result));
        }
        // The new line at the end: always there, it only becomes a line of the notebook when it is validated with a text.
        rows.add(new NotebookRow(rows.size() + 1, "", NotebookRow.Status.EMPTY, "", "", "", -1, -1, "", "Ligne vide"));
        Message status = status();
        boolean showHelp = evaluated.notebook().isEmpty() && draft.isEmpty();
        return new NotebookViewModel(rows, activeIndex(), draft, focus, document.canUndo(), document.canRedo(), mode,
                status.text(), status.tone(), showHelp ? WATERMARK : "");
    }

    // A refusal of the last intention (an error) is always shown, so that a key that did nothing says why. Then the failure of
    // the automatic save, which the user must act on. Then, when the notebook is not being saved, that warning (an
    // information never replaces it), otherwise the last intention, then the usual hint.
    private Message status() {
        if (transientMessage != null && transientMessage.tone() == StatusTone.ERROR) {
            return transientMessage;
        }
        if (document.autosaveProblem().isPresent()) {
            return new Message("Enregistrement automatique impossible : " + document.autosaveProblem().get()
                    + " Le carnet reste disponible dans cette fenêtre.", StatusTone.ERROR);
        }
        if (lastingMessage != null) {
            return lastingMessage;
        }
        return transientMessage != null ? transientMessage : new Message(DEFAULT_STATUS, StatusTone.INFO);
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
