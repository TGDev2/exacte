package calc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The texts shown to the user are written in correctly accented French. The check reads the string literals of the
 * sources that hold visible texts (window, presenter, engine and store messages) and looks for the unaccented forms
 * that were found in earlier versions. It cannot prove that no other mistake exists, but it keeps the known ones out.
 */
class VisibleTextsTest {
    private static final Path SOURCES = Path.of("src", "main", "java", "calc");
    private static final List<String> VISIBLE_TEXT_SOURCES = List.of(
            "ui", "engine/CalculatorEngine.java", "engine/NumericMessages.java", "engine/OperandParser.java",
            "persistence/JsonHistoryStore.java");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    private static final Pattern FAULTY_FORMS = Pattern.compile(
            "\\b(?:etre|resultats?|precedent\\w*|decimale?s?|reutilis\\w*|carree|negatif|negative|selectionn\\w*|"
                    + "deja|apres|efface (?:le|l'|un)|historique efface|restaure|cree|creer|memoire|libelle|"
                    + "donnees|numero|valeur approchee|derniere|premiere|operation)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void noVisibleTextUsesAKnownUnaccentedForm() throws IOException {
        List<String> faults = new ArrayList<>();
        int literals = 0;
        for (Path file : visibleTextSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher literal = LITERAL.matcher(source);
            while (literal.find()) {
                literals++;
                Matcher faulty = FAULTY_FORMS.matcher(literal.group(1));
                if (faulty.find()) {
                    faults.add(file.getFileName() + ": « " + literal.group(1) + " » contains « " + faulty.group() + " »");
                }
            }
        }
        assertFalse(literals < 100, "The scan must really read the sources (read " + literals + " literals).");
        assertEquals(List.of(), faults);
    }

    @Test
    void theScanRecognisesTheFaultyFormsItLooksFor() {
        for (String faulty : List.of("Operation", "Resultat exact", "doit etre entier", "valeur approchee", "Historique efface",
                "le dernier resultat precedent", "decimales coupees", "racine carree negative")) {
            assertEquals(true, FAULTY_FORMS.matcher(faulty).find(), faulty);
        }
        for (String correct : List.of("Opération", "Résultat exact", "doit être entier", "valeur approchée", "Historique effacé",
                "Échap efface", "décimales coupées", "Erreur maximale : ± 2,1 × 10⁻²⁵")) {
            assertEquals(false, FAULTY_FORMS.matcher(correct).find(), correct);
        }
    }

    private static List<Path> visibleTextSources() throws IOException {
        List<Path> files = new ArrayList<>();
        for (String entry : VISIBLE_TEXT_SOURCES) {
            Path path = SOURCES.resolve(entry);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(file -> file.toString().endsWith(".java")).forEach(files::add);
                }
            } else {
                files.add(path);
            }
        }
        return files;
    }
}
