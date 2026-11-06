package calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The GitHub CI runs on demand only (private repository, limited free minutes): the local {@code check} is what counts
 * before a merge. These tests pin the triggers of the workflows and the documentation that says so.
 */
class CiTriggersTest {
    private static final Path CI = Path.of(".github/workflows/ci.yml");
    private static final Path RELEASE = Path.of(".github/workflows/release.yml");

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The lines of the top-level {@code on:} block (the line itself included), comments left out. */
    private static List<String> onBlock(String workflow) {
        List<String> block = new ArrayList<>();
        boolean inside = false;
        for (String line : workflow.split("\n", -1)) {
            if (line.startsWith("on:")) {
                inside = true;
                block.add(line);
            } else if (inside) {
                boolean topLevelKey = !line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && line.charAt(0) != '#';
                if (topLevelKey) {
                    break;
                }
                if (!line.isBlank() && !line.stripLeading().startsWith("#")) {
                    block.add(line);
                }
            }
        }
        return block;
    }

    /** The names of the events that trigger the workflow, in block or inline form. */
    private static List<String> events(String workflow) {
        List<String> events = new ArrayList<>();
        for (String line : onBlock(workflow)) {
            if (line.startsWith("on:")) {
                String inline = line.substring(3).replaceAll("[\\[\\]{}]", " ").trim();
                for (String name : inline.split("[,\\s]+")) {
                    if (!name.isEmpty()) {
                        events.add(name.replace(":", ""));
                    }
                }
            } else if (line.matches("^ {1,4}[A-Za-z_]+:.*") && !line.matches("^ {5,}.*")) {
                int indent = line.length() - line.stripLeading().length();
                if (indent == onIndent(workflow)) {
                    events.add(line.strip().split(":")[0]);
                }
            }
        }
        return events;
    }

    private static int onIndent(String workflow) {
        for (String line : onBlock(workflow).subList(1, onBlock(workflow).size())) {
            return line.length() - line.stripLeading().length();
        }
        return 2;
    }

    @Test
    void ciWorkflowIsTriggeredByWorkflowDispatchOnly() throws IOException {
        List<String> events = events(read(CI));
        assertTrue(events.contains("workflow_dispatch"), "ci.yml must keep workflow_dispatch, found " + events);
        assertFalse(events.contains("push"), "ci.yml must not run on push, found " + events);
        assertFalse(events.contains("pull_request"), "ci.yml must not run on pull_request, found " + events);
        assertFalse(events.contains("pull_request_target"), "ci.yml must not run on pull_request_target, found " + events);
        assertEquals(List.of("workflow_dispatch"), events, "ci.yml must be triggered by workflow_dispatch only");
    }

    @Test
    void ciWorkflowKeepsItsJobsAndItsThreeSystemMatrix() throws IOException {
        String ci = read(CI);
        assertTrue(ci.contains("name: check (${{ matrix.os }})"), "job check");
        assertTrue(ci.contains("os: [ubuntu-latest, windows-latest, macos-latest]"), "matrix ubuntu/windows/macos");
        assertTrue(ci.contains("name: reproducible jar"), "job reproducible jar");
        assertTrue(ci.contains("name: conformity report"), "job conformity report");
        assertTrue(ci.contains("-PrequireUiTests=true"), "UI tests stay mandatory in the jobs");
    }

    @Test
    void releaseWorkflowIsUnchanged() throws IOException {
        String release = read(RELEASE);
        List<String> block = onBlock(release);
        String joined = String.join("\n", block);
        assertTrue(joined.contains("push:"), "release.yml keeps its push trigger");
        assertTrue(joined.contains("tags: ['v*.*.*']"), "release.yml keeps the vX.Y.Z tag trigger");
        assertTrue(joined.contains("workflow_dispatch:"), "release.yml keeps workflow_dispatch");
        assertFalse(joined.contains("pull_request"), "release.yml has no pull_request trigger");
        assertFalse(joined.contains("branches:"), "release.yml is not triggered by branches");
    }

    @Test
    void ciDocumentationExplainsOnDemandCi() throws IOException {
        String doc = read(Path.of("docs/CI.md"));
        String lower = doc.toLowerCase(Locale.ROOT);
        assertTrue(lower.contains("à la demande"), "says the CI runs on demand");
        assertTrue(lower.contains("minutes gratuites"), "explains the free minutes");
        assertTrue(lower.contains("dépôt privé"), "mentions the private repository");
        assertTrue(lower.contains("windows") && lower.contains("double"), "a Windows minute counts double");
        assertTrue(lower.contains("macos") && lower.contains("dix fois"), "a macOS minute counts ten times");
        assertTrue(doc.contains("gradlew.bat check -PrequireUiTests=true"), "the local check is what counts");
        assertTrue(lower.contains("statut de commit"), "published as a commit status");
        assertTrue(doc.contains("« check »"), "the commit status is named « check »");
        assertTrue(doc.contains("Run workflow"), "how to run it from the Actions tab");
        assertTrue(doc.contains("gh workflow run ci.yml --ref"), "how to run it with gh");
        assertTrue(lower.contains("avant une publication"), "when: before a release");
        assertTrue(lower.contains("mise à jour de dépendance") || lower.contains("mises à jour de dépendances"),
                "when: after a dependency update");
        assertTrue(doc.contains("actions/workflows/ci.yml/badge.svg"), "the CI badge stays in the doc");
    }

    @Test
    void ciDocumentationRequiresTheCheckStatusForMain() throws IOException {
        String doc = read(Path.of("docs/CI.md"));
        int start = doc.indexOf("## Protection de la branche");
        assertTrue(start >= 0, "section on the protection of main");
        int end = doc.indexOf("\n## ", start + 1);
        String section = end < 0 ? doc.substring(start) : doc.substring(start, end);
        assertTrue(section.contains("« check »"), "main requires the « check » verification");
        assertFalse(section.contains("check (ubuntu-latest)"), "no more per-system check jobs required");
        assertFalse(section.contains("check (windows-latest)"), "no more per-system check jobs required");
        assertFalse(section.contains("check (macos-latest)"), "no more per-system check jobs required");
    }

    @Test
    void documentsNoLongerSayTheCiRunsOnEveryPullRequestOrPush() throws IOException {
        List<String> documents = List.of("README.md", "docs/CI.md", "docs/ETAT-DES-LIEUX.md", "docs/RELEASE.md",
                "docs/adr/0012-packaging-et-releases.md");
        List<String> forbidden = List.of(
                "à chaque pull request", "à chaque `push`", "à chaque push", "sur chaque push", "sur chaque pull request",
                "every pull request", "every push", "on each pull request", "on every push",
                "sur `main` et à la demande", "sur les pull requests");
        for (String document : documents) {
            String text = read(Path.of(document)).toLowerCase(Locale.ROOT);
            for (String phrase : forbidden) {
                assertFalse(text.contains(phrase.toLowerCase(Locale.ROOT)), document + " still says: " + phrase);
            }
        }
    }

    @Test
    void readmeKeepsTheCiBadge() throws IOException {
        String readme = read(Path.of("README.md"));
        assertTrue(readme.contains("actions/workflows/ci.yml/badge.svg"), "README keeps the CI badge");
    }
}
