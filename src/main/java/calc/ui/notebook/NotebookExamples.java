package calc.ui.notebook;

import java.util.List;

/**
 * The three example notebooks that the empty notebook offers. A click on one inserts its lines
 * ({@link NotebookPresenter#onInsertExample}); they are written out, never read from a file, and none of them is in error
 * (NotebookExamplesTest evaluates them with the language itself).
 *
 * <p>{@link #DEVIS} is the Devis cuisine example of the README, line for line (a no-break space groups the thousands, as the
 * language allows).
 */
public enum NotebookExamples {
    /** A quote with variables, a percentage and {@code ans}. */
    DEVIS("Devis", List.of(
            "# Devis cuisine",
            "plan = 1\u00a0250,00          # plan de travail (espace insécable)",
            "meubles = 3\u00a0480",
            "pose = 35 * 12,5",
            "total = plan + meubles + pose     # 5 167,5",
            "taux = 0,2",
            "total * (1 + taux)       # TTC : 6 201",
            "ans / 12                 # mensualité sur un an : 516,75")),

    /** A square root that does not come out even gives an approximate value, one that does stays exact. */
    RACINE("Racine de 2 approchée", List.of(
            "# Racine de 2",
            "√2                       # valeur approchée, avec son erreur maximale",
            "√(9/4)                   # 1,5 : une racine exacte reste exacte")),

    /** {@code $n} designates the result of the line n, which must be above the line that uses it. */
    REFERENCES("Références $n", List.of(
            "# Références",
            "12,5",
            "$2 * 3                   # le résultat de la ligne 2, multiplié par 3",
            "$3 - $2                  # deux références dans une ligne"));

    private final String title;
    private final List<String> lines;

    NotebookExamples(String title, List<String> lines) {
        this.title = title;
        this.lines = lines;
    }

    /** The text of the button of the example. */
    public String title() {
        return title;
    }

    /** The lines the example inserts, in order. */
    public List<String> lines() {
        return lines;
    }
}
