import calc.engine.CalculationException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.Operation;
import calc.number.NumericException;
import calc.number.Rational;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replays the inputs of src/test/resources/golden/engine-v1.tsv on the current engine (E06) and writes:
 * <ul>
 *   <li>src/test/resources/golden/engine-v2.tsv, same format and same inputs as v1;</li>
 *   <li>docs/changements-comportement-E06.md, every difference from v1 with its category and its reason.</li>
 * </ul>
 * Not part of the production code. Usage: java -cp <classes> GoldenV2 <repository root>
 */
public final class GoldenV2 {
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("<U\\+([0-9A-F]{4})>");
    private static final Pattern REPEAT = Pattern.compile("<rep:(\\d+):([^>]*)>");
    private static final Pattern HASHED = Pattern.compile("len=(\\d+) sha256=[0-9a-f]{64}");
    private static final String HEADER = "operation\tfirst\tsecond\toutcome\tdisplay\treusable";
    // Texts longer than this are stored as their length and SHA-256, like the two hashed lines of v1.
    private static final int MAX_STORED_LENGTH = 100;

    private static final String IMPROVEMENT = "amélioration attendue";
    private static final String NEW_MESSAGE = "nouveau message";
    private static final String REGRESSION = "régression";

    // fullReusable is the reusable text before it is shortened to a digest (the stored text for v1).
    private record Line(int number, String operation, String first, String second, String outcome, String display, String reusable,
                        String fullReusable) {
        String key() {
            return operation + "\t" + first + "\t" + second;
        }
    }

    private record Change(Line before, Line after, String category, String reason) {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        List<Line> v1 = read(root.resolve("src/test/resources/golden/engine-v1.tsv"));
        List<Line> v2 = new ArrayList<>();
        for (Line line : v1) {
            v2.add(replay(line));
        }
        write(root.resolve("src/test/resources/golden/engine-v2.tsv"), v2);

        List<Change> changes = new ArrayList<>();
        for (int index = 0; index < v1.size(); index++) {
            Line before = v1.get(index);
            Line after = v2.get(index);
            if (!(before.outcome().equals(after.outcome()) && before.display().equals(after.display())
                    && before.reusable().equals(after.reusable()))) {
                changes.add(classify(before, after));
            }
        }
        Files.write(root.resolve("docs/changements-comportement-E06.md"),
                document(v1.size(), changes).getBytes(StandardCharsets.UTF_8));
        System.out.println(v1.size() + " cases, " + changes.size() + " changes, "
                + changes.stream().filter(change -> change.category().equals(REGRESSION)).count() + " regressions");
    }

    private static Line replay(Line line) throws Exception {
        String first = expand(line.first());
        String second = expand(line.second());
        Operation operation = line.operation().equals("(none)") ? null : Operation.valueOf(line.operation());
        try {
            CalculationRecord record = new CalculatorEngine().calculate(operation, first, second);
            String reusable = record.getReusableValue();
            return new Line(line.number(), line.operation(), line.first(), line.second(), "OK",
                    stored(record.getFormattedResult()), stored(reusable), reusable);
        } catch (CalculationException exception) {
            return new Line(line.number(), line.operation(), line.first(), line.second(), "ERROR", exception.getMessage(), "", "");
        }
    }

    private static Change classify(Line before, Line after) throws Exception {
        boolean wasOk = before.outcome().equals("OK");
        boolean isOk = after.outcome().equals("OK");
        if (wasOk && isOk) {
            List<String> reasons = new ArrayList<>();
            if (isZero(before.display()) && !isZero(after.display())) {
                reasons.add("valeur non nulle qui n'est plus affichée 0");
            }
            if (after.display().startsWith("≈")) {
                reasons.add("précision : la valeur réutilisée est un point de l'intervalle certifié, et l'intervalle lui-même tant que"
                        + " le champ n'est pas modifié, plus l'arrondi à 12 décimales de la version 1");
            } else if (!sameValue(before.reusable(), after.fullReusable())) {
                reasons.add("exactitude : la valeur réutilisée est la valeur exacte, plus l'arrondi de la version 1");
            } else if (!before.reusable().equals(after.reusable())) {
                reasons.add("nouvelle notation de la valeur réutilisée (même valeur, écriture canonique plus courte)");
            }
            if (!before.display().equals(after.display())) {
                reasons.add(displayReason(after.display()));
            }
            return new Change(before, after, IMPROVEMENT, String.join(" ; ", reasons));
        }
        if (!wasOk && isOk) {
            return new Change(before, after, IMPROVEMENT, before.display().startsWith("Le premier nombre est invalide")
                    ? "nouvelle grammaire : la notation scientifique est acceptée"
                    : "bornes du noyau exact (4096 bits par composante, exposant ±4096, 4100 caractères) : le calcul est désormais possible");
        }
        if (!wasOk) {
            return new Change(before, after, NEW_MESSAGE, "bornes du noyau exact : le message donne les nouvelles limites");
        }
        if (after.display().contains("contient une espace")) {
            return new Change(before, after, NEW_MESSAGE,
                    "grammaire exigée par l'Epic : l'espace ordinaire est refusé dans un nombre (il séparera les éléments"
                            + " d'une expression en E09), avec un message dédié ; l'espace insécable garde la fonction de séparateur");
        }
        return new Change(before, after, REGRESSION, "un calcul accepté par la version 1 est refusé");
    }

    private static String displayReason(String display) {
        if (display.startsWith("≈")) {
            return "nouvelle notation : « ≈ » et seulement les chiffres garantis d'une valeur approchée certifiée";
        }
        List<String> parts = new ArrayList<>();
        if (display.contains("× 10")) {
            parts.add("notation scientifique hors de [10⁻⁶, 10¹⁵[");
        }
        if (display.contains("…")) {
            parts.add("« … » signale des chiffres omis (12 chiffres significatifs)");
        }
        return "nouvelle notation : " + (parts.isEmpty() ? "affichage Auto" : String.join(", ", parts));
    }

    private static boolean isZero(String display) {
        return display.equals("0");
    }

    /**
     * Whether a v1 reusable text and the full v2 reusable text denote the same number. A hashed v1 text (the plain
     * decimal of an exact v1 result) is compared with the digest of the plain decimal of the v2 value.
     */
    private static boolean sameValue(String before, String after) throws Exception {
        if (HASHED.matcher(before).matches()) {
            String plain = Rational.parse(after).toExactDecimal().map(decimal -> decimal.toPlainString().replace('.', ',')).orElse("");
            return before.equals(summary(plain));
        }
        try {
            return Rational.parse(before).equals(Rational.parse(after));
        } catch (NumericException exception) {
            return false;
        }
    }

    private static String document(int cases, List<Change> changes) {
        long improvements = changes.stream().filter(change -> change.category().equals(IMPROVEMENT)).count();
        long newMessages = changes.stream().filter(change -> change.category().equals(NEW_MESSAGE)).count();
        long regressions = changes.stream().filter(change -> change.category().equals(REGRESSION)).count();
        StringBuilder text = new StringBuilder();
        text.append("# Changements de comportement de l'Epic E06\n\n");
        text.append("Généré par `tools/golden-v2/GoldenV2.java` (voir `tools/golden-v2/README.md`) : ne pas modifier à la main.\n\n");
        text.append("Les ").append(cases).append(" calculs de référence de la version 1 (`src/test/resources/golden/engine-v1.tsv`) ont été ")
                .append("rejoués sur le moteur exact. Le résultat est `src/test/resources/golden/engine-v2.tsv`, que le test de ")
                .append("non-régression (`GoldenOutputsTest`) compare désormais ; le fichier v1 est conservé pour l'historique. ")
                .append("Les règles appliquées sont celles de `docs/adr/0004-regles-affichage.md`.\n\n");
        text.append("## Synthèse\n\n");
        text.append("| Catégorie | Lignes |\n|---|---|\n");
        text.append("| Inchangées | ").append(cases - changes.size()).append(" |\n");
        text.append("| Amélioration attendue | ").append(improvements).append(" |\n");
        text.append("| Nouveau message | ").append(newMessages).append(" |\n");
        text.append("| Régression | ").append(regressions).append(" |\n\n");
        text.append("Catégories : **amélioration attendue** (valeur non nulle qui n'est plus 0, exactitude, nouvelle notation, ")
                .append("calcul devenu possible), **nouveau message** (le refus est conservé ou décidé, son texte change), ")
                .append("**régression** (comportement dégradé sans décision).\n\n");
        text.append("## Écarts ligne par ligne\n\n");
        text.append("« Affiché » est le texte du résultat (mode Auto) ou le message d'erreur ; « réutilisé » est le texte placé ")
                .append("dans le premier champ quand le résultat est repris. Un texte de plus de ").append(MAX_STORED_LENGTH)
                .append(" caractères est remplacé par sa longueur et son empreinte SHA-256, comme dans les fichiers de référence.\n\n");
        text.append("| Ligne | Calcul | Version 1 | Version 2 (E06) | Catégorie | Justification |\n|---|---|---|---|---|---|\n");
        for (Change change : changes) {
            text.append("| ").append(change.before().number())
                    .append(" | ").append(cell(calculation(change.before())))
                    .append(" | ").append(cell(outcome(change.before())))
                    .append(" | ").append(cell(outcome(change.after())))
                    .append(" | ").append(change.category())
                    .append(" | ").append(cell(change.reason()))
                    .append(" |\n");
        }
        return text.toString();
    }

    private static String calculation(Line line) {
        String first = shorten(line.first());
        return line.operation().equals("ROOT")
                ? "ROOT [" + first + "]"
                : line.operation() + " [" + first + "] [" + shorten(line.second()) + "]";
    }

    private static String outcome(Line line) {
        if (line.outcome().equals("ERROR")) {
            return "erreur : « " + line.display() + " »";
        }
        String shown = "affiché `" + line.display() + "`";
        return line.reusable().equals(line.display()) ? shown + ", réutilisé tel quel" : shown + ", réutilisé `" + line.reusable() + "`";
    }

    private static String shorten(String notation) {
        return notation.length() <= 40 ? notation : notation.substring(0, 20) + "…";
    }

    private static String cell(String text) {
        return text.replace("|", "\\|");
    }

    private static String stored(String text) throws Exception {
        return text.length() > MAX_STORED_LENGTH ? summary(text) : text;
    }

    private static String summary(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return "len=" + value.length() + " sha256=" + HexFormat.of().formatHex(digest);
    }

    private static List<Line> read(Path file) throws Exception {
        String[] rows = Files.readString(file, StandardCharsets.UTF_8).split("\n", -1);
        if (!rows[0].equals(HEADER)) {
            throw new IllegalStateException("unexpected header in " + file);
        }
        List<Line> lines = new ArrayList<>();
        for (int index = 1; index < rows.length; index++) {
            if (rows[index].isEmpty()) {
                continue;
            }
            String[] columns = rows[index].split("\t", -1);
            lines.add(new Line(index + 1, columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[5]));
        }
        return lines;
    }

    private static void write(Path file, List<Line> lines) throws Exception {
        StringBuilder text = new StringBuilder(HEADER).append('\n');
        for (Line line : lines) {
            text.append(line.key()).append('\t').append(line.outcome()).append('\t').append(line.display()).append('\t')
                    .append(line.reusable()).append('\n');
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Expands the &lt;U+XXXX&gt; and &lt;rep:N:text&gt; notations of the golden files. */
    private static String expand(String notation) {
        Matcher repeats = REPEAT.matcher(notation);
        StringBuilder withRepeats = new StringBuilder();
        while (repeats.find()) {
            repeats.appendReplacement(withRepeats,
                    Matcher.quoteReplacement(repeats.group(2).repeat(Integer.parseInt(repeats.group(1)))));
        }
        repeats.appendTail(withRepeats);
        Matcher escapes = UNICODE_ESCAPE.matcher(withRepeats);
        StringBuilder result = new StringBuilder();
        while (escapes.find()) {
            escapes.appendReplacement(result,
                    Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(escapes.group(1), 16))));
        }
        escapes.appendTail(result);
        return result.toString();
    }
}
