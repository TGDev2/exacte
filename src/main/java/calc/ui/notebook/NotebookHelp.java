package calc.ui.notebook;

import java.util.List;

/**
 * The texts of the Help menu. The syntax is a summary built into the application (the complete guide,
 * docs/GUIDE-EXPRESSIONS.md, is not part of the packaged application, and a summary works everywhere); its examples are
 * checked against the real evaluator by NotebookHelpTest, so that the help cannot drift from the language.
 */
public final class NotebookHelp {
    /** An expression and the result the notebook shows for it in the Auto mode. */
    public record Example(String expression, String result) {
    }

    /** The examples of the syntax summary. */
    public static final List<Example> EXAMPLES = List.of(
            new Example("12,5 * 3", "37,5"),
            new Example("(1 + 2) × 3", "9"),
            new Example("0,1 + 0,2", "0,3"),
            new Example("2^10", "1024"),
            new Example("1 / 3", "0,333333333333…"),
            new Example("√2", "≈ 1,41421356237…"));

    private NotebookHelp() {
    }

    public static String syntax() {
        StringBuilder text = new StringBuilder("""
                Une ligne contient une expression, une affectation, un commentaire ou rien.

                  prix = 12,5          affectation : donne un nom à une valeur
                  prix * 3             expression : calcule une valeur
                  # Devis cuisine      commentaire : tout ce qui suit # est ignoré
                  prix * 3   # TTC     un commentaire peut suivre une expression

                Nombres : la virgule ou le point marquent les décimales (12,5 ou 12.5), 1,5e3 vaut 1500. Les calculs sont
                exacts : seule une racine carrée qui ne tombe pas juste donne une valeur approchée (badge « ≈ Approché »,
                avec l'erreur maximale en info-bulle).

                Opérateurs : + - * / (ou × ÷), ^ pour la puissance, √ ou sqrt( ) ou racine( ) pour la racine carrée,
                parenthèses. La puissance passe avant la multiplication, qui passe avant l'addition.

                Références : ans est le résultat de la ligne au-dessus la plus proche qui a une valeur ; $3 est le résultat
                de la ligne 3 (une ligne ne peut désigner qu'une ligne située au-dessus d'elle). Une variable n'existe
                qu'en dessous de sa définition. Quand des lignes sont insérées, déplacées ou supprimées, les références
                suivent leur ligne ; vers une ligne supprimée, elles deviennent une erreur explicite.

                Exemples :
                """);
        for (Example example : EXAMPLES) {
            text.append("  ").append(example.expression()).append("   →   ").append(example.result()).append('\n');
        }
        text.append("\nLe guide complet est dans docs/GUIDE-EXPRESSIONS.md.");
        return text.toString();
    }

    public static String shortcuts(NotebookShortcuts shortcuts) {
        StringBuilder text = new StringBuilder();
        for (NotebookShortcuts.Command command : NotebookShortcuts.Command.values()) {
            text.append(String.format("%-16s", shortcuts.text(command))).append(command.description()).append('\n');
        }
        text.append("\nAnnuler et Rétablir portent sur les modifications validées. La saisie en cours dans une ligne\n"
                + "s'abandonne avec Échap. Annuler la retire d'abord, et Rétablir la remet tant que rien d'autre n'a changé.\n"
                + "Sans saisie en cours, Échap interrompt le calcul : les lignes restantes le disent, et la commande suivante\n"
                + "relance le calcul.");
        return text.toString();
    }
}
