package calc.web;

import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.expression.ParsedLine;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.Notebook;
import calc.notebook.NotebookCommand;
import calc.notebook.NotebookEditor;
import calc.notebook.NotebookException;
import java.util.ArrayList;
import java.util.List;
import org.teavm.jso.JSExport;

/**
 * The only entry point of TeaVM: what is reachable from here is what gets compiled to JavaScript. Signatures use String
 * and int only, because a long becomes a BigInt on the JavaScript side.
 */
public final class WebExports {
    private static final String NO_OPERATION = "(none)";
    private static final String[] PARITY_MODES = {"AUTO", "DECIMAL", "FRACTION", "SCIENTIFIC"};
    private static final WebNotebook NOTEBOOK = new WebNotebook();

    private WebExports() {
    }

    /** TeaVM requires a main method on its main class; the scripts and the page only call the exports. */
    public static void main(String[] args) {
    }

    @JSExport
    public static String load(String lines, String mode) {
        return NOTEBOOK.load(lines, mode);
    }

    @JSExport
    public static String snapshot() {
        return NOTEBOOK.snapshot();
    }

    @JSExport
    public static String append(String text) {
        return NOTEBOOK.append(text);
    }

    @JSExport
    public static String insertBefore(int number, String text) {
        return NOTEBOOK.insertBefore(number, text);
    }

    @JSExport
    public static String insertAfter(int number, String text) {
        return NOTEBOOK.insertAfter(number, text);
    }

    @JSExport
    public static String edit(int number, String text) {
        return NOTEBOOK.edit(number, text);
    }

    /** Named deleteLine because delete is a reserved word of JavaScript. */
    @JSExport
    public static String deleteLine(int number) {
        return NOTEBOOK.delete(number);
    }

    @JSExport
    public static String move(int number, int toNumber) {
        return NOTEBOOK.move(number, toNumber);
    }

    @JSExport
    public static String clear() {
        return NOTEBOOK.clear();
    }

    @JSExport
    public static String undo() {
        return NOTEBOOK.undo();
    }

    @JSExport
    public static String redo() {
        return NOTEBOOK.redo();
    }

    @JSExport
    public static String setMode(String mode) {
        return NOTEBOOK.setMode(mode);
    }

    @JSExport
    public static String texts() {
        return NOTEBOOK.texts();
    }

    /**
     * One case of the golden file, on the path of GoldenOutputsTest: {@code OK\t<display>\t<reusable>} or
     * {@code ERROR\t<message>\t}. The operation {@code (none)} stands for no operation at all.
     */
    @JSExport
    public static String goldenCase(String operation, String first, String second) {
        Operation chosen = operation.equals(NO_OPERATION) ? null : Operation.valueOf(operation);
        try {
            CalculationRecord record = new CalculatorEngine().calculate(chosen, first, second);
            return "OK\t" + record.getFormattedResult() + "\t" + record.getReusableValue();
        } catch (CalculationException exception) {
            return "ERROR\t" + exception.getMessage() + "\t";
        }
    }

    /**
     * What the differential test compares with the JVM: a new notebook, so that the one of the page stays as it is, then
     * its snapshots in the modes AUTO, DECIMAL, FRACTION and SCIENTIFIC, joined by line feeds.
     */
    @JSExport
    public static String parityNotebook(String lines) {
        WebNotebook notebook = new WebNotebook();
        notebook.load(lines, PARITY_MODES[0]);
        List<String> snapshots = new ArrayList<>();
        for (String mode : PARITY_MODES) {
            snapshots.add(notebook.setMode(mode));
        }
        return String.join("\n", snapshots);
    }

    /**
     * Forces TeaVM to compile, and Node to run, the path of the notebook: the golden file never reaches calc.expression
     * nor calc.notebook. Gives one line {@code <number>\t<text>\t<status>\t<result>\t<bound>\t<error>} per line of the
     * notebook. When {@code moveNumber > 0}, moves that line first, then adds {@code undo\t<texts>} and
     * {@code redo\t<texts>}. A notebook the core refuses gives {@code REFUSED\t<message>}.
     */
    @JSExport
    public static String trialNotebook(String lines, String mode, int moveNumber, int moveToNumber) {
        DisplayMode displayMode = DisplayMode.valueOf(mode);
        try {
            NotebookEditor editor = new NotebookEditor(Notebook.of(WebNotebook.splitLines(lines)));
            if (moveNumber <= 0) {
                return rows(editor.current(), displayMode);
            }
            editor.apply(new NotebookCommand.Move(editor.notebook().line(moveNumber).id(), moveToNumber - 1));
            String moved = rows(editor.current(), displayMode);
            editor.undo();
            String undone = "undo\t" + String.join("|", editor.notebook().texts());
            editor.redo();
            return moved + "\n" + undone + "\nredo\t" + String.join("|", editor.notebook().texts());
        } catch (NotebookException exception) {
            return "REFUSED\t" + exception.getMessage();
        }
    }

    private static String rows(EvaluatedNotebook evaluated, DisplayMode mode) {
        List<String> rows = new ArrayList<>();
        for (LineResult result : evaluated.results()) {
            rows.add(row(result, mode));
        }
        return String.join("\n", rows);
    }

    /** The status rules of NotebookPresenter.row, without its dependency on the window. */
    private static String row(LineResult result, DisplayMode mode) {
        String status;
        String shown = "";
        String bound = "";
        String error = "";
        if (result.isError()) {
            status = "ERROR";
            error = result.error().message();
        } else if (result.hasValue()) {
            FormattedValue formatted = ValueFormatter.format(result.value(), mode);
            status = formatted.exact() ? "EXACT" : "APPROXIMATE";
            shown = formatted.text();
            bound = formatted.errorBound().orElse("");
        } else {
            status = result.kind() == ParsedLine.Kind.COMMENT ? "COMMENT" : "EMPTY";
        }
        return String.join("\t", Integer.toString(result.number()), result.text(), status, shown, bound, error);
    }
}
