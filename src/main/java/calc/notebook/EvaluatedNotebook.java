package calc.notebook;

import java.util.List;
import java.util.Objects;

/** A notebook and the result of each of its lines, in the same order. Immutable. */
public record EvaluatedNotebook(Notebook notebook, List<LineResult> results) {
    public EvaluatedNotebook {
        Objects.requireNonNull(notebook, "notebook");
        results = List.copyOf(results);
        if (results.size() != notebook.size()) {
            throw new IllegalArgumentException(results.size() + " results for " + notebook.size() + " lines");
        }
    }

    /** The result of the line numbered {@code number}, from 1. */
    public LineResult result(int number) {
        notebook.line(number);
        return results.get(number - 1);
    }

    /** The result of the line {@code id}. */
    public LineResult result(LineId id) {
        return results.get(notebook.numberOf(id)
                .orElseThrow(() -> new IllegalArgumentException("no line " + id + " in this notebook")) - 1);
    }
}
