package calc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** No dead link in the documentation: every relative link points to a file that exists and, when it has one, to a heading. */
class DocumentationLinksTest {
    private static final Pattern LINK = Pattern.compile("(?<!!)\\[[^\\]]*]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.*?)\\s*#*$");

    private static List<Path> documents() throws IOException {
        List<Path> documents = new ArrayList<>(List.of(Path.of("README.md"), Path.of("CHANGELOG.md")));
        try (Stream<Path> files = Files.walk(Path.of("docs"))) {
            files.filter(path -> path.toString().endsWith(".md")).sorted().forEach(documents::add);
        }
        return documents;
    }

    /** GitHub's anchor of a heading: lower case, punctuation removed, spaces turned into hyphens. */
    static String anchor(String heading) {
        String plain = heading.replaceAll("`", "").replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1").toLowerCase(Locale.ROOT);
        StringBuilder anchor = new StringBuilder();
        for (char c : plain.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                anchor.append(c);
            } else if (c == ' ') {
                anchor.append('-');
            }
        }
        return anchor.toString();
    }

    private static Set<String> anchors(Path file) throws IOException {
        Set<String> anchors = new HashSet<>();
        boolean fenced = false;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            } else if (!fenced) {
                Matcher heading = HEADING.matcher(line);
                if (heading.matches()) {
                    anchors.add(anchor(heading.group(1)));
                }
            }
        }
        return anchors;
    }

    @Test
    void everyRelativeLinkOfTheDocumentationResolves() throws IOException, URISyntaxException {
        List<String> dead = new ArrayList<>();
        int checked = 0;
        for (Path document : documents()) {
            boolean fenced = false;
            for (String line : Files.readAllLines(document, StandardCharsets.UTF_8)) {
                if (line.startsWith("```")) {
                    fenced = !fenced;
                    continue;
                }
                if (fenced) {
                    continue;
                }
                Matcher matcher = LINK.matcher(line.replaceAll("`[^`]*`", ""));
                while (matcher.find()) {
                    String target = matcher.group(1);
                    URI uri = new URI(target);
                    if (uri.getScheme() != null) {
                        continue; // external: not checked offline
                    }
                    checked++;
                    String path = uri.getPath() == null ? "" : URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8);
                    Path file = path.isEmpty() ? document : document.resolveSibling(path).normalize();
                    if (!Files.exists(file)) {
                        dead.add(document + " -> " + target + " (no such file)");
                    } else if (uri.getFragment() != null && file.toString().endsWith(".md")
                            && !anchors(file).contains(uri.getFragment())) {
                        dead.add(document + " -> " + target + " (no such heading)");
                    }
                }
            }
        }
        assertTrue(checked > 50, "the check should have seen the links of the documentation, saw " + checked);
        assertTrue(dead.isEmpty(), () -> "dead links:\n" + String.join("\n", dead));
    }

    @Test
    void theAnchorRuleMatchesGitHub() {
        org.junit.jupiter.api.Assertions.assertEquals("limites-connues-et-points-non-vérifiés",
                anchor("Limites connues et points non vérifiés"));
        org.junit.jupiter.api.Assertions.assertEquals("téléchargement", anchor("Téléchargement"));
        org.junit.jupiter.api.Assertions.assertEquals("couverture-mutation-et-rapport-de-conformité",
                anchor("Couverture, mutation et rapport de conformité"));
    }
}
