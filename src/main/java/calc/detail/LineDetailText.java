package calc.detail;

import calc.format.DisplayMode;
import calc.format.GuaranteedBounds;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineExpansion;
import calc.notebook.LineId;
import calc.notebook.LineResult;
import calc.notebook.Source;
import calc.number.CertifiedValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The detail of a line, in French: how its value was obtained and where it goes (SPEC 4.3 « Texte du détail », ADR-D in
 * docs/ARCHITECTURE.md). In this order:
 *
 * <ul>
 *   <li>the expansion, « = » then {@link LineExpansion}, when the line has one;</li>
 *   <li>the result as the line shows it: « = » then the text of {@link ValueFormatter#format} for an exact value, that text
 *       alone for an approximate one, since it already reads « ≈ … » or « |x| ≤ b »;</li>
 *   <li>« Sources : ligne 2 » or « Sources : lignes 2, 3 et 4 », the lines it reads, in the order of the notebook, when it
 *       reads any;</li>
 *   <li>« Utilisée par 1 ligne », « Utilisée par N lignes » or « Utilisée par aucune ligne »;</li>
 *   <li>for an approximate value, « Valeur garantie entre L et U », from {@link ValueFormatter#guaranteedBounds}.</li>
 * </ul>
 *
 * <p>A line without a value (a comment, an empty line, an error) has no detail. Every number goes through
 * {@link ValueFormatter}, the expansion through the canonical printer of the expressions. Pure Java: the desktop and the web
 * page (compiled by TeaVM) show the same sentences.
 */
public final class LineDetailText {

    /**
     * One sentence of the detail.
     *
     * @param text    the sentence
     * @param formula whether it is a formula (the expansion or the result), written in the font of the expressions
     */
    public record Sentence(String text, boolean formula) {
        public Sentence {
            Objects.requireNonNull(text, "text");
        }
    }

    private LineDetailText() {
    }

    /** The sentences of the detail of the line {@code id} of {@code notebook}, its result shown in {@code mode}. */
    public static List<String> of(EvaluatedNotebook notebook, LineId id, DisplayMode mode) {
        List<String> texts = new ArrayList<>();
        for (Sentence sentence : sentences(notebook, id, mode)) {
            texts.add(sentence.text());
        }
        return List.copyOf(texts);
    }

    /** The same sentences as {@link #of}, each saying whether it is a formula. */
    public static List<Sentence> sentences(EvaluatedNotebook notebook, LineId id, DisplayMode mode) {
        Objects.requireNonNull(mode, "mode");
        LineResult line = notebook.result(id);
        if (!line.hasValue()) {
            return List.of();
        }
        CertifiedValue value = line.value();
        List<Sentence> sentences = new ArrayList<>();
        LineExpansion.expand(notebook, id).ifPresent(expansion -> sentences.add(new Sentence("= " + expansion, true)));
        String shown = ValueFormatter.format(value, mode).text();
        sentences.add(new Sentence(value.isExact() ? "= " + shown : shown, true));
        List<Integer> sources = sourceLines(notebook, line);
        if (!sources.isEmpty()) {
            sentences.add(new Sentence("Sources : " + lines(sources), false));
        }
        sentences.add(new Sentence("Utilisée par " + count(notebook.usedBy(id).size()), false));
        if (!value.isExact()) {
            GuaranteedBounds bounds = ValueFormatter.guaranteedBounds(value);
            sentences.add(new Sentence("Valeur garantie entre " + bounds.lower() + " et " + bounds.upper(), false));
        }
        return List.copyOf(sentences);
    }

    // The numbers of the lines read by the line, once each, in the order of the notebook.
    private static List<Integer> sourceLines(EvaluatedNotebook notebook, LineResult line) {
        TreeSet<Integer> numbers = new TreeSet<>();
        for (Source source : line.sources()) {
            numbers.add(notebook.result(source.target()).number());
        }
        return new ArrayList<>(numbers);
    }

    // « ligne 2 », « lignes 2 et 3 », « lignes 2, 3 et 4 ».
    private static String lines(List<Integer> numbers) {
        StringBuilder text = new StringBuilder(numbers.size() == 1 ? "ligne " : "lignes ");
        for (int index = 0; index < numbers.size(); index++) {
            if (index > 0) {
                text.append(index == numbers.size() - 1 ? " et " : ", ");
            }
            text.append(numbers.get(index));
        }
        return text.toString();
    }

    // « aucune ligne », « 1 ligne », « N lignes ».
    private static String count(int lines) {
        if (lines == 0) {
            return "aucune ligne";
        }
        return lines == 1 ? "1 ligne" : lines + " lignes";
    }
}
