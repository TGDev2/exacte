package calc.web;

import calc.expression.ParsedLine;
import calc.expression.Span;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookEditor;
import calc.notebook.NotebookException;
import java.util.ArrayList;
import java.util.List;

/**
 * The notebook as JavaScript drives it: lines designated by their number, modes by their name, and every answer a JSON
 * snapshot, the same string on the JVM and under TeaVM. Nothing here depends on TeaVM, so this class is tested on the JVM;
 * {@link WebExports} exports it.
 *
 * <p>No exception gets out of a command: a refusal, or any failure, gives a French message and leaves the lines as they
 * were. The lines of the last answer are kept as text, so answering after a failure calculates nothing.
 */
public final class WebNotebook {
    private static final int MAX_LINE_LENGTH = 16384;
    // Written as the desktop shows it, without NumberFormat: its French data would have to reach the browser.
    private static final String LINE_TOO_LONG = "La ligne dépasse 16 384 caractères : raccourcissez-la pour la valider.";
    private static final String UNKNOWN_MODE = "Mode d'affichage inconnu.";
    private static final String COMMAND_FAILED = "Cette commande n'a pas abouti : le carnet n'a pas changé.";
    private static final String HEX_DIGITS = "0123456789abcdef";

    private NotebookEditor editor = new NotebookEditor();
    private DisplayMode mode = DisplayMode.AUTO;
    private String lines = "";
    private Throwable nextFailure;

    /** One command of the notebook; it changes nothing when it is refused. */
    @FunctionalInterface
    private interface Command {
        void run() throws NotebookException, Refusal;
    }

    /** A refusal worded here, as NotebookException can only be built by the core. */
    private static final class Refusal extends Exception {
        private static final long serialVersionUID = 1L;

        Refusal(String message) {
            super(message);
        }
    }

    /** An empty notebook, in the Auto mode. */
    public WebNotebook() {
    }

    /** Replaces the notebook with these lines, separated by a line feed ({@code ""} is no line), with nothing to undo. */
    public String load(String text, String newMode) {
        return run(() -> {
            DisplayMode chosen = modeNamed(newMode);
            List<String> texts = new ArrayList<>();
            if (!text.isEmpty()) {
                for (String line : splitLines(text)) {
                    texts.add(checkedLength(line.replace('\r', ' ')));
                }
            }
            editor = new NotebookEditor(Notebook.of(texts));
            mode = chosen;
        });
    }

    public String snapshot() {
        return answer(null);
    }

    public String append(String text) {
        return run(() -> editor.apply(new NotebookCommand.Append(cleaned(text))));
    }

    public String insertBefore(int number, String text) {
        return run(() -> editor.apply(new NotebookCommand.InsertBefore(line(number), cleaned(text))));
    }

    public String insertAfter(int number, String text) {
        return run(() -> editor.apply(new NotebookCommand.InsertAfter(line(number), cleaned(text))));
    }

    public String edit(int number, String text) {
        return run(() -> editor.apply(new NotebookCommand.Edit(line(number), cleaned(text))));
    }

    public String delete(int number) {
        return run(() -> editor.apply(new NotebookCommand.Delete(line(number))));
    }

    /** Moves the line {@code number} so that its number becomes {@code toNumber}. */
    public String move(int number, int toNumber) {
        return run(() -> {
            LineId moved = line(number);
            line(toNumber);
            editor.apply(new NotebookCommand.Move(moved, toNumber - 1));
        });
    }

    public String clear() {
        return run(() -> editor.apply(new NotebookCommand.Clear()));
    }

    public String undo() {
        return run(() -> {
            if (editor.canUndo()) {
                editor.undo();
            }
        }, () -> editor.redo());
    }

    public String redo() {
        return run(() -> {
            if (editor.canRedo()) {
                editor.redo();
            }
        });
    }

    public String setMode(String newMode) {
        return run(() -> mode = modeNamed(newMode));
    }

    /** The lines joined by a line feed, for saving: {@link #load} reads them back. */
    public String texts() {
        return String.join("\n", editor.notebook().texts());
    }

    /** The next command fails with {@code failure} once it has been applied, the worst moment: for the tests. */
    void failNextCommandWith(Throwable failure) {
        nextFailure = failure;
    }

    /** The text cut at each line feed; {@code ""} is one empty line. */
    static List<String> splitLines(String text) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int end = text.indexOf('\n'); end >= 0; end = text.indexOf('\n', start)) {
            parts.add(text.substring(start, end));
            start = end + 1;
        }
        parts.add(text.substring(start));
        return parts;
    }

    private String run(Command command) {
        return run(command, () -> editor.undo());
    }

    /**
     * Runs a command and calculates its lines, the part that may really fail. A failure puts the notebook back with
     * {@code takeBack}: the editor has no other way back, so a command that was undone that way can be redone, in place of
     * what could be redone before.
     */
    private String run(Command command, Runnable takeBack) {
        NotebookEditor editorBefore = editor;
        Notebook notebookBefore = editor.notebook();
        DisplayMode modeBefore = mode;
        try {
            command.run();
            throwRequestedFailure();
            lines = calculatedLines();
            return answer(null);
        } catch (NotebookException | Refusal refused) {
            return answer(refused.getMessage());
        } catch (RuntimeException | Error failure) {
            editor = editorBefore;
            mode = modeBefore;
            if (editor.notebook() != notebookBefore) {
                takeBack.run();
            }
            return answer(COMMAND_FAILED);
        }
    }

    private void throwRequestedFailure() {
        Throwable failure = nextFailure;
        nextFailure = null;
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
        }
    }

    private LineId line(int number) throws Refusal {
        if (number < 1 || number > editor.notebook().size()) {
            throw new Refusal("La ligne " + number + " n'existe pas.");
        }
        return editor.notebook().line(number).id();
    }

    private static DisplayMode modeNamed(String name) throws Refusal {
        for (DisplayMode candidate : DisplayMode.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        throw new Refusal(UNKNOWN_MODE);
    }

    // A line break cannot be part of a line, as in NotebookPresenter.syncDraft.
    private static String cleaned(String text) throws Refusal {
        return checkedLength(text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '));
    }

    private static String checkedLength(String text) throws Refusal {
        if (text.length() > MAX_LINE_LENGTH) {
            throw new Refusal(LINE_TOO_LONG);
        }
        return text;
    }

    private String answer(String message) {
        StringBuilder json = new StringBuilder("{\"mode\":\"").append(mode.name()).append("\",\"canUndo\":")
                .append(editor.canUndo()).append(",\"canRedo\":").append(editor.canRedo()).append(",\"message\":");
        appendString(json, message);
        return json.append(",\"lines\":[").append(lines).append("]}").toString();
    }

    private String calculatedLines() {
        StringBuilder json = new StringBuilder();
        for (LineResult result : editor.current().results()) {
            if (json.length() > 0) {
                json.append(',');
            }
            appendLine(json, result);
        }
        return json.toString();
    }

    /** The rules of NotebookPresenter.row, without its dependency on the window. Package-private: no text reaches every rule. */
    void appendLine(StringBuilder json, LineResult result) {
        if (result.isError()) {
            Span span = result.error().span();
            int end = Math.min(span.end(), result.text().length());
            boolean marked = span.length() > 0 && span.start() < end;
            appendLine(json, result, "ERROR", null, null, null, result.error().message(), marked ? span.start() : -1, marked ? end : -1);
        } else if (result.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(result.value(), mode);
            appendLine(json, result, formatted.exact() ? "EXACT" : "APPROXIMATE", formatted.text(),
                    formatted.exactFraction().orElse(null), formatted.errorBound().orElse(null), null, -1, -1);
        } else {
            String status = result.kind() == ParsedLine.Kind.COMMENT ? "COMMENT" : "EMPTY";
            appendLine(json, result, status, null, null, null, null, -1, -1);
        }
    }

    private static void appendLine(StringBuilder json, LineResult result, String status, String shown, String fraction, String bound,
            String error, int errorStart, int errorEnd) {
        json.append("{\"number\":").append(result.number()).append(",\"text\":");
        appendString(json, result.text());
        json.append(",\"status\":\"").append(status).append("\",\"result\":");
        appendString(json, shown);
        json.append(",\"exactFraction\":");
        appendString(json, fraction);
        json.append(",\"errorBound\":");
        appendString(json, bound);
        json.append(",\"error\":");
        appendString(json, error);
        json.append(",\"errorStart\":").append(errorStart).append(",\"errorEnd\":").append(errorEnd).append('}');
    }

    /** Only what JSON forbids is escaped: every other character, even half a surrogate pair, passes as it is. */
    private static void appendString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                json.append('\\').append(character);
            } else if (character < 0x20) {
                json.append("\\u00").append(HEX_DIGITS.charAt(character >> 4)).append(HEX_DIGITS.charAt(character & 0xF));
            } else {
                json.append(character);
            }
        }
        json.append('"');
    }
}
