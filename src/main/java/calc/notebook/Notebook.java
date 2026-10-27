package calc.notebook;

import calc.expression.LineRenumbering;
import calc.expression.ReferenceRewriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * The document of a calculation notebook: an immutable, ordered sequence of {@link Line}s. Every command returns a new
 * notebook (or this one when it changes nothing), so undo and redo only keep the previous notebooks.
 *
 * <p>References stay attached to their line (docs/adr/0008-semantique-carnet.md, section 4): a {@code $n} is bound to
 * the line {@code n} when it is typed, and after every insertion, deletion or move the text of every line is rewritten
 * with {@link ReferenceRewriter} so that it designates the same line under its new number. A reference to a deleted line
 * becomes {@code $?}; a reference past the last line stays past it. The number written in a reference is therefore
 * always the current number of the line it was bound to, and the texts alone describe the whole notebook.
 */
public final class Notebook {
    /** The largest number of lines. */
    public static final int MAX_LINES = 5000;

    private static final Notebook EMPTY = new Notebook(List.of(), 1);

    private final List<Line> lines;
    private final long nextId;
    private final Map<LineId, Integer> indexes;

    private Notebook(List<Line> lines, long nextId) {
        this.lines = Collections.unmodifiableList(lines);
        this.nextId = nextId;
        this.indexes = indexes(lines);
    }

    /** A notebook without any line. */
    public static Notebook empty() {
        return EMPTY;
    }

    /** A notebook of these texts, in this order, with new identifiers (to rebuild a persisted notebook). */
    public static Notebook of(List<String> texts) throws NotebookException {
        requireRoom(texts.size());
        List<Line> lines = new ArrayList<>(texts.size());
        long id = 1;
        for (String text : texts) {
            lines.add(new Line(new LineId(id++), text));
        }
        return new Notebook(lines, id);
    }

    public List<Line> lines() {
        return lines;
    }

    /** The texts of the lines, in order. */
    public List<String> texts() {
        return lines.stream().map(Line::text).toList();
    }

    public int size() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** The line numbered {@code number}, from 1. */
    public Line line(int number) {
        if (number < 1 || number > lines.size()) {
            throw new IndexOutOfBoundsException("no line " + number + " in a notebook of " + lines.size() + " lines");
        }
        return lines.get(number - 1);
    }

    public boolean contains(LineId id) {
        return indexes.containsKey(id);
    }

    /** The current number, from 1, of the line {@code id}; empty if the notebook has no such line. */
    public OptionalInt numberOf(LineId id) {
        Integer index = indexes.get(id);
        return index == null ? OptionalInt.empty() : OptionalInt.of(index + 1);
    }

    /** The text of the line {@code id} replaced by {@code text}; the other lines do not change. */
    public Notebook edit(LineId id, String text) {
        int index = indexOf(id);
        if (lines.get(index).text().equals(text)) {
            return this;
        }
        List<Line> edited = new ArrayList<>(lines);
        edited.set(index, new Line(id, text));
        return new Notebook(edited, nextId);
    }

    /** A new line of text {@code text} just above the line {@code anchor}. */
    public Notebook insertBefore(LineId anchor, String text) throws NotebookException {
        return insertAt(indexOf(anchor), text);
    }

    /** A new line of text {@code text} just below the line {@code anchor}. */
    public Notebook insertAfter(LineId anchor, String text) throws NotebookException {
        return insertAt(indexOf(anchor) + 1, text);
    }

    /** A new line of text {@code text} at the end. */
    public Notebook append(String text) throws NotebookException {
        return insertAt(lines.size(), text);
    }

    /** The line {@code id} removed; the references to it become {@code $?}. */
    public Notebook delete(LineId id) {
        List<Line> order = new ArrayList<>(lines);
        order.remove(indexOf(id));
        return reordered(order, null, nextId);
    }

    /** The line {@code id} moved so that its index (from 0) becomes {@code toIndex}. */
    public Notebook move(LineId id, int toIndex) {
        int from = indexOf(id);
        if (toIndex < 0 || toIndex >= lines.size()) {
            throw new IndexOutOfBoundsException("cannot move to index " + toIndex + " in " + lines.size() + " lines");
        }
        if (from == toIndex) {
            return this;
        }
        List<Line> order = new ArrayList<>(lines);
        order.add(toIndex, order.remove(from));
        return reordered(order, null, nextId);
    }

    /** No line left. Identifiers are not reused afterwards. */
    public Notebook clear() {
        return lines.isEmpty() ? this : new Notebook(new ArrayList<>(), nextId);
    }

    private Notebook insertAt(int index, String text) throws NotebookException {
        requireRoom(lines.size() + 1);
        Line inserted = new Line(new LineId(nextId), text);
        List<Line> order = new ArrayList<>(lines);
        order.add(index, inserted);
        return reordered(order, inserted.id(), nextId + 1);
    }

    /**
     * The lines in their new {@code order} (survivors of this notebook, plus the line {@code fresh} just typed, which is
     * already numbered for its new place), with every reference renumbered to designate the same line as before.
     */
    private Notebook reordered(List<Line> order, LineId fresh, long next) {
        Map<LineId, Integer> newIndexes = indexes(order);
        int oldSize = lines.size();
        int shift = order.size() - oldSize;
        LineRenumbering renumbering = number -> {
            if (number > oldSize) {
                return OptionalInt.of(pastTheEnd(number, shift));
            }
            Integer index = newIndexes.get(lines.get(number - 1).id());
            return index == null ? OptionalInt.empty() : OptionalInt.of(index + 1);
        };
        List<Line> rewritten = new ArrayList<>(order.size());
        for (Line line : order) {
            rewritten.add(line.id().equals(fresh) ? line : rewrite(line, renumbering));
        }
        return new Notebook(rewritten, next);
    }

    // A reference past the last line designates no line: it moves with the size of the notebook, so it stays past the
    // end and never comes to designate a line by accident. A number too large to shift is past the end anyway.
    private static int pastTheEnd(int number, int shift) {
        long shifted = (long) number + shift;
        return shifted > Integer.MAX_VALUE ? number : (int) shifted;
    }

    private static Line rewrite(Line line, LineRenumbering renumbering) {
        if (line.text().indexOf('$') < 0) {
            return line;
        }
        String text = ReferenceRewriter.rewrite(line.text(), renumbering);
        return text.equals(line.text()) ? line : new Line(line.id(), text);
    }

    private int indexOf(LineId id) {
        Integer index = indexes.get(Objects.requireNonNull(id, "id"));
        if (index == null) {
            throw new IllegalArgumentException("no line " + id + " in this notebook");
        }
        return index;
    }

    private static Map<LineId, Integer> indexes(List<Line> lines) {
        Map<LineId, Integer> indexes = new HashMap<>(lines.size() * 2);
        for (int index = 0; index < lines.size(); index++) {
            if (indexes.put(lines.get(index).id(), index) != null) {
                throw new IllegalStateException("duplicate line " + lines.get(index).id());
            }
        }
        return indexes;
    }

    private static void requireRoom(int size) throws NotebookException {
        if (size > MAX_LINES) {
            throw new NotebookException("Le carnet ne peut pas dépasser " + MAX_LINES + " lignes.");
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Notebook notebook && nextId == notebook.nextId && lines.equals(notebook.lines);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lines, nextId);
    }

    @Override
    public String toString() {
        return "Notebook" + lines;
    }
}
