package calc.notebook;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.RandomAccess;

/** A notebook and the result of each of its lines, in the same order. Immutable. */
public record EvaluatedNotebook(Notebook notebook, List<LineResult> results) {
    public EvaluatedNotebook {
        Objects.requireNonNull(notebook, "notebook");
        results = new Results(List.copyOf(results));
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

    /**
     * The lines that have the line {@code id} as the target of one of their sources, in the order of the notebook, each
     * once: the inverse of {@link LineResult#sources()}. The index behind it is built on the first call, once.
     */
    public List<LineId> usedBy(LineId id) {
        if (!notebook.contains(id)) {
            throw new IllegalArgumentException("no line " + id + " in this notebook");
        }
        // The constructor always stores a Results.
        List<LineId> users = ((Results) results).users().get(id);
        return users == null ? List.of() : Collections.unmodifiableList(users);
    }

    /**
     * The results, and the index of their users built on demand. It is a list like any other for {@code equals} and
     * {@code hashCode}, so the index never makes two evaluated notebooks differ.
     */
    private static final class Results extends AbstractList<LineResult> implements RandomAccess {
        private final List<LineResult> results;
        private volatile Map<LineId, List<LineId>> users;

        Results(List<LineResult> results) {
            this.results = results;
        }

        @Override
        public LineResult get(int index) {
            return results.get(index);
        }

        @Override
        public int size() {
            return results.size();
        }

        Map<LineId, List<LineId>> users() {
            Map<LineId, List<LineId>> index = users;
            if (index == null) {
                synchronized (this) {
                    index = users;
                    if (index == null) {
                        index = index(results);
                        users = index;
                    }
                }
            }
            return index;
        }

        private static Map<LineId, List<LineId>> index(List<LineResult> results) {
            Map<LineId, List<LineId>> index = new HashMap<>();
            for (LineResult user : results) {
                for (Source source : user.sources()) {
                    List<LineId> users = index.computeIfAbsent(source.target(), target -> new ArrayList<>());
                    // Two sources of one line can share their target ($3 and ans): the line counts once.
                    if (users.isEmpty() || !users.get(users.size() - 1).equals(user.id())) {
                        users.add(user.id());
                    }
                }
            }
            return index;
        }
    }
}
