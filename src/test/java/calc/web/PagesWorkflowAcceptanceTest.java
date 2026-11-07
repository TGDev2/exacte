package calc.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code .github/workflows/pages.yml} read as text, as {@code CiTriggersTest} reads {@code ci.yml}: the workflow that builds
 * the conformity report, proves the parity under Node and a browser, and publishes the page. Nothing here runs Gradle (the
 * tests run inside it) and nothing reaches GitHub: the SHA of an action is checked for its shape and, for three actions,
 * against {@code ci.yml}; that a SHA is the commit of the tag in its comment needs the network.
 *
 * <p>The three jobs are found by what they do, not by their name: the one that runs {@code gradlew conformity}, the one
 * that uploads the Pages artifact and the one that deploys it. Their identifiers are then the ones {@code needs} must use.
 */
@Tag("acceptance")
@DisplayName("pages.yml : conformité, parité, publication de la page")
class PagesWorkflowAcceptanceTest {
    private static final Path PAGES = Path.of(".github", "workflows", "pages.yml");
    private static final Path CI = Path.of(".github", "workflows", "ci.yml");

    private static final Pattern USES_LINE = Pattern.compile("^\\s*(?:-\\s+)?uses:\\s*(\\S+?)@(\\S+)(.*)$");
    private static final Pattern PINNED = Pattern.compile(
            "^\\s*(?:-\\s+)?uses:\\s+[\\w.-]+(?:/[\\w.-]+)+@[0-9a-f]{40} +# ?v\\d+(?:\\.\\d+)*[\\w.+-]*\\s*$");
    private static final List<String> ACTIONS = List.of("actions/checkout", "actions/setup-java", "gradle/actions/setup-gradle",
            "actions/upload-artifact", "actions/download-artifact", "actions/setup-node", "actions/upload-pages-artifact",
            "actions/deploy-pages");

    // ---------------------------------------------------------------------------------------------- the parsing

    private static List<String> lines(Path file) throws IOException {
        return List.of(Files.readString(file, UTF_8).replace("\r\n", "\n").split("\n", -1));
    }

    private static boolean ignorable(String line) {
        return line.isBlank() || line.stripLeading().startsWith("#");
    }

    private static int indent(String line) {
        return line.length() - line.stripLeading().length();
    }

    /** The lines under the top-level key (not the key line itself), blank lines and comment lines left out. */
    private static List<String> block(List<String> lines, String key) {
        List<String> body = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (!inside) {
                inside = line.startsWith(key + ":");
            } else if (!ignorable(line)) {
                if (indent(line) == 0) {
                    break;
                }
                body.add(line);
            }
        }
        return body;
    }

    /** The keys of a block that sit at its own indentation, with their lines: events of {@code on}, ids of {@code jobs}. */
    private static Map<String, List<String>> children(List<String> block) {
        Map<String, List<String>> children = new LinkedHashMap<>();
        if (block.isEmpty()) {
            return children;
        }
        int level = indent(block.get(0));
        String current = null;
        for (String line : block) {
            if (indent(line) == level && line.strip().matches("[\\w-]+:.*")) {
                current = line.strip().substring(0, line.strip().indexOf(':'));
                children.put(current, new ArrayList<>());
            }
            if (current != null) {
                children.get(current).add(line);
            }
        }
        return children;
    }

    private static Map<String, List<String>> jobs() throws IOException {
        return children(block(lines(PAGES), "jobs"));
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines);
    }

    /** The steps of a job: each starts at a list dash at the indentation of the first one after {@code steps:}. */
    private static List<List<String>> steps(List<String> job) {
        List<List<String>> steps = new ArrayList<>();
        int level = -1;
        boolean inside = false;
        for (String line : job) {
            if (!inside) {
                inside = line.strip().equals("steps:");
            } else if (!ignorable(line)) {
                if (level < 0 && line.strip().startsWith("- ")) {
                    level = indent(line);
                }
                if (level >= 0 && indent(line) == level && line.strip().startsWith("- ")) {
                    steps.add(new ArrayList<>());
                }
                if (!steps.isEmpty()) {
                    steps.get(steps.size() - 1).add(line);
                }
            }
        }
        return steps;
    }

    private static boolean uses(List<String> step, String action) {
        return step.stream().anyMatch(line -> {
            Matcher matcher = USES_LINE.matcher(line);
            return matcher.matches() && matcher.group(1).equals(action);
        });
    }

    private static List<String> stepUsing(List<String> job, String action) {
        List<List<String>> found = steps(job).stream().filter(step -> uses(step, action)).toList();
        assertEquals(1, found.size(), "exactly one step uses " + action + " in:\n" + text(job));
        return found.get(0);
    }

    private static int indexOfStep(List<String> job, java.util.function.Predicate<List<String>> wanted) {
        List<List<String>> steps = steps(job);
        for (int i = 0; i < steps.size(); i++) {
            if (wanted.test(steps.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean runs(List<String> step, String regex) {
        return Pattern.compile(regex).matcher(text(step).replaceAll("\\s*\\\\\\n\\s*", " ")).find();
    }

    private static boolean hasLine(List<String> lines, String regex) {
        Pattern pattern = Pattern.compile(regex);
        return lines.stream().anyMatch(line -> pattern.matcher(line).matches());
    }

    /** The jobs listed by {@code needs}, whether written as a scalar, an inline list or a block list. */
    private static Set<String> needs(List<String> job) {
        Set<String> needs = new TreeSet<>();
        for (int i = 0; i < job.size(); i++) {
            String line = job.get(i).strip();
            if (!line.startsWith("needs:") || indent(job.get(i)) != 4) {
                continue;
            }
            String value = line.substring("needs:".length()).strip();
            if (value.isEmpty()) {
                for (int j = i + 1; j < job.size() && job.get(j).strip().startsWith("- "); j++) {
                    needs.add(job.get(j).strip().substring(2).strip());
                }
            } else {
                for (String name : value.replaceAll("[\\[\\]]", "").split(",")) {
                    needs.add(name.strip().replaceAll("^[\"']|[\"']$", ""));
                }
            }
        }
        return needs;
    }

    private static String idOf(Map<String, List<String>> jobs, String marker) {
        List<String> found = jobs.entrySet().stream().filter(job -> text(job.getValue()).contains(marker)).map(Map.Entry::getKey)
                .toList();
        assertEquals(1, found.size(), "exactly one job contains " + marker + ", found " + found);
        return found.get(0);
    }

    private static List<String> job(String marker) throws IOException {
        Map<String, List<String>> jobs = jobs();
        return jobs.get(idOf(jobs, marker));
    }

    // -------------------------------------------------------------------------------------------------- triggers

    @Test
    @DisplayName("déclencheurs : push sur la branche main et workflow_dispatch, sans pull_request ni tags")
    void theTriggersArePushOnMainAndWorkflowDispatch() throws IOException {
        List<String> on = block(lines(PAGES), "on");
        assertFalse(on.isEmpty(), "pages.yml needs an on: block (block form, as in ci.yml)");
        Map<String, List<String>> events = children(on);
        assertEquals(List.of("push", "workflow_dispatch"), events.keySet().stream().sorted().toList(),
                "exactly these two events");
        String push = text(events.get("push"));
        Matcher inline = Pattern.compile("branches:\\s*\\[([^\\]]*)\\]").matcher(push);
        List<String> branches = new ArrayList<>();
        if (inline.find()) {
            for (String name : inline.group(1).split(",")) {
                branches.add(name.strip().replaceAll("^[\"']|[\"']$", ""));
            }
        } else {
            boolean after = false;
            for (String line : events.get("push")) {
                if (after && line.strip().startsWith("- ")) {
                    branches.add(line.strip().substring(2).strip().replaceAll("^[\"']|[\"']$", ""));
                } else if (line.strip().equals("branches:")) {
                    after = true;
                } else if (after) {
                    break;
                }
            }
        }
        assertEquals(List.of("main"), branches, "push only on main, in: " + push);
        String all = text(on);
        assertFalse(all.contains("pull_request"), "no pull_request");
        assertFalse(all.contains("tags"), "no tags, not even tags-ignore");
        assertFalse(all.contains("schedule"), "no schedule");
        assertFalse(push.contains("paths"), "the push is not narrowed by paths: the page is rebuilt on every push to main");
    }

    @Test
    @DisplayName("exactement trois jobs, sur Linux, et une concurrence qui n'annule pas un déploiement en cours")
    void threeLinuxJobsAndAConcurrencyThatDoesNotCancel() throws IOException {
        Map<String, List<String>> jobs = jobs();
        assertEquals(3, jobs.size(), "conformity, site and deploy: " + jobs.keySet());
        for (Map.Entry<String, List<String>> job : jobs.entrySet()) {
            assertTrue(hasLine(job.getValue(), "^ {4}runs-on:\\s*ubuntu-\\S+\\s*$"), job.getKey() + " runs on Linux only");
        }
        List<String> concurrency = block(lines(PAGES), "concurrency");
        assertFalse(concurrency.isEmpty(), "a concurrency: block");
        assertTrue(hasLine(concurrency, "^\\s+cancel-in-progress:\\s*false\\s*$"),
                "cancel-in-progress: false, so that a deployment is never cut: " + concurrency);
    }

    // ------------------------------------------------------------------------------------------------ conformity

    @Test
    @DisplayName("le job de conformité lance gradlew conformity sous xvfb-run, CALC_TIMING_FACTOR à 3, en moins de 60 minutes")
    void theConformityJobRunsTheConformityBuildUnderXvfb() throws IOException {
        List<String> conformity = job("gradlew conformity");
        List<String> run = steps(conformity).stream().filter(step -> text(step).contains("gradlew conformity")).findFirst()
                .orElseThrow();
        assertTrue(runs(run, "xvfb-run\\s+(?:-\\S+\\s+)*\\./gradlew\\s+conformity\\b[^\\n]*-PrequireUiTests=true\\b"),
                "xvfb-run ./gradlew conformity -PrequireUiTests=true: " + text(run));
        List<String> workflowEnv = block(lines(PAGES), "env");
        String where = text(conformity) + "\n" + text(workflowEnv);
        assertTrue(Pattern.compile("CALC_TIMING_FACTOR:\\s*[\"']?3[\"']?\\s*(?:#.*)?$", Pattern.MULTILINE).matcher(where).find(),
                "CALC_TIMING_FACTOR: \"3\" in the job or the workflow env");
        assertTrue(hasLine(conformity, "^ {4}timeout-minutes:\\s*60\\s*$"), "timeout-minutes: 60");
        assertFalse(text(conformity).contains("pages: write"), "the conformity job writes nothing to Pages");
    }

    @Test
    @DisplayName("l'artefact conformity-report a un seul chemin, build/reports/conformite : le rapport est à sa racine")
    void theConformityReportArtifactHasASinglePath() throws IOException {
        List<String> conformity = job("gradlew conformity");
        List<String> upload = steps(conformity).stream()
                .filter(step -> uses(step, "actions/upload-artifact") && hasLine(step, "^\\s+name:\\s*conformity-report\\s*$"))
                .findFirst().orElse(null);
        assertTrue(upload != null, "a step actions/upload-artifact with name: conformity-report in the conformity job");
        List<String> paths = upload.stream().filter(line -> line.strip().startsWith("path:")).toList();
        assertEquals(1, paths.size(), "exactly one path: line, found " + paths);
        assertEquals("build/reports/conformite", paths.get(0).strip().substring("path:".length()).strip().replaceAll("^[\"']|[\"']$", ""),
                "the path, on the same line");
        assertEquals(1, Pattern.compile("build/reports/").matcher(text(upload)).results().count(),
                "no second path (pitest, jacoco...) in the step: " + text(upload));
        assertFalse(paths.get(0).contains("|") || paths.get(0).contains(">"), "no block of paths");
        int run = indexOfStep(conformity, step -> text(step).contains("gradlew conformity"));
        int publish = indexOfStep(conformity, step -> step.equals(upload));
        assertTrue(run >= 0 && publish > run, "the report is uploaded after the build that writes it");
    }

    // ------------------------------------------------------------------------------------------------------ site

    @Test
    @DisplayName("le job du site dépend de la conformité, installe Node 22 et lance webTest avec la graine run_id")
    void theSiteJobProvesTheParityUnderNodeAndABrowser() throws IOException {
        Map<String, List<String>> jobs = jobs();
        List<String> site = jobs.get(idOf(jobs, "actions/upload-pages-artifact@"));
        String conformityId = idOf(jobs, "gradlew conformity");
        assertTrue(needs(site).contains(conformityId), "the site job needs " + conformityId + ", found " + needs(site));

        List<String> node = stepUsing(site, "actions/setup-node");
        assertTrue(hasLine(node, "^\\s+node-version:\\s*[\"']?22(?:\\.x)?[\"']?\\s*$"), "Node 22: " + text(node));

        List<String> webTest = steps(site).stream().filter(step -> text(step).contains(":web:webTest")).findFirst().orElse(null);
        assertTrue(webTest != null, "a step runs :web:webTest");
        assertTrue(runs(webTest, "\\./gradlew\\s+:web:webTest\\b[^\\n]*-PrequireBrowser=true\\b"),
                "./gradlew :web:webTest -PrequireBrowser=true: " + text(webTest));
        assertTrue(runs(webTest, "-PparitySeed=\\$\\{\\{\\s*github\\.run_id\\s*\\}\\}"),
                "-PparitySeed=${{ github.run_id }}: " + text(webTest));
        assertTrue(indexOfStep(site, step -> uses(step, "actions/setup-node")) < indexOfStep(site, step -> step.equals(webTest)),
                "Node is installed before webTest");
    }

    @Test
    @DisplayName("le site reçoit le rapport dans conformity, le vérifie, l'assemble avec -PconformityReport et le publie")
    void theSiteJobAssemblesAndPublishesTheReport() throws IOException {
        List<String> site = job("actions/upload-pages-artifact@");

        List<String> download = steps(site).stream()
                .filter(step -> uses(step, "actions/download-artifact") && hasLine(step, "^\\s+name:\\s*conformity-report\\s*$"))
                .findFirst().orElse(null);
        assertTrue(download != null, "a step actions/download-artifact with name: conformity-report");
        assertTrue(hasLine(download, "^\\s+path:\\s*conformity\\s*$"), "path: conformity: " + text(download));

        int downloaded = indexOfStep(site, step -> step.equals(download));
        int checked = indexOfStep(site, step -> runs(step, "test\\s+-s\\s+conformity/RAPPORT-CONFORMITE\\.md\\b"));
        int assembled = indexOfStep(site, step -> runs(step,
                "\\./gradlew\\s+:web:site\\b[^\\n]*[\"']?-PconformityReport=conformity/RAPPORT-CONFORMITE\\.md[\"']?(?:\\s|$)"));
        List<String> publish = stepUsing(site, "actions/upload-pages-artifact");
        int published = indexOfStep(site, step -> step.equals(publish));
        assertTrue(checked >= 0, "a step runs test -s conformity/RAPPORT-CONFORMITE.md");
        assertTrue(assembled >= 0, "a step runs ./gradlew :web:site -PconformityReport=conformity/RAPPORT-CONFORMITE.md");
        assertTrue(downloaded < checked && checked < assembled && assembled < published,
                "download, then test -s, then the site, then the upload: " + List.of(downloaded, checked, assembled, published));
        assertTrue(hasLine(publish, "^\\s+path:\\s*web/build/site\\s*$"), "the artifact is web/build/site: " + text(publish));
    }

    @Test
    @DisplayName("pages.yml ne reprend pas le chemin conformity/conformite de release.yml")
    void theReportPathIsNotTheOneOfTheReleaseWorkflow() throws IOException {
        String workflow = Files.readString(PAGES, UTF_8);
        assertFalse(workflow.contains("conformity/conformite"),
                "the artifact of pages.yml has a single path: the report is at the root of the download");
    }

    // ---------------------------------------------------------------------------------------------------- deploy

    @Test
    @DisplayName("le déploiement dépend des deux autres jobs, ne tourne que sur refs/heads/main et vise github-pages")
    void theDeployJobDependsOnTheOthersAndRunsOnMainOnly() throws IOException {
        Map<String, List<String>> jobs = jobs();
        String deployId = idOf(jobs, "actions/deploy-pages@");
        List<String> deploy = jobs.get(deployId);
        Set<String> others = new TreeSet<>(jobs.keySet());
        others.remove(deployId);
        assertEquals(2, others.size());
        assertEquals(others, needs(deploy), "needs lists the two other jobs");
        assertTrue(hasLine(deploy, "^ {4}if:\\s*(?:\\$\\{\\{\\s*)?github\\.ref\\s*==\\s*'refs/heads/main'(?:\\s*\\}\\})?\\s*$"),
                "if: github.ref == 'refs/heads/main' on the job: " + text(deploy));
        assertTrue(Pattern.compile("\\benvironment:\\s+(?:name:\\s+)?github-pages\\b").matcher(text(deploy)).find(),
                "environment: github-pages: " + text(deploy));
        stepUsing(deploy, "actions/deploy-pages");
        for (String other : others) {
            assertFalse(text(jobs.get(other)).contains("actions/deploy-pages"), other + " does not deploy");
            assertFalse(hasLine(jobs.get(other), "^ {4}if:.*refs/heads/main.*"), other + " also runs outside main");
        }
    }

    @Test
    @DisplayName("permissions : contents: read pour le workflow, pages: write et id-token: write pour le seul déploiement")
    void onlyTheDeployJobCanWriteToPages() throws IOException {
        List<String> workflow = block(lines(PAGES), "permissions");
        assertEquals(List.of("contents: read"), workflow.stream().map(String::strip).toList(),
                "the permissions of the workflow are contents: read, nothing else");

        Map<String, List<String>> jobs = jobs();
        String deployId = idOf(jobs, "actions/deploy-pages@");
        for (Map.Entry<String, List<String>> job : jobs.entrySet()) {
            String body = text(job.getValue());
            boolean deploy = job.getKey().equals(deployId);
            assertEquals(deploy, Pattern.compile("^\\s+pages:\\s*write\\s*$", Pattern.MULTILINE).matcher(body).find(),
                    "pages: write only in the deploy job, job " + job.getKey());
            assertEquals(deploy, Pattern.compile("^\\s+id-token:\\s*write\\s*$", Pattern.MULTILINE).matcher(body).find(),
                    "id-token: write only in the deploy job, job " + job.getKey());
            assertFalse(Pattern.compile("^\\s+(?:contents|actions|packages|deployments|checks|statuses):\\s*write\\s*$",
                    Pattern.MULTILINE).matcher(body).find(), "no other write permission, job " + job.getKey());
        }
    }

    // --------------------------------------------------------------------------------------------------- the pins

    @Test
    @DisplayName("chaque uses: est épinglé sur un SHA de 40 caractères suivi d'un commentaire de version")
    void everyActionIsPinnedToACommit() throws IOException {
        List<String> uses = lines(PAGES).stream().filter(line -> !ignorable(line) && USES_LINE.matcher(line).matches()).toList();
        assertTrue(uses.size() >= ACTIONS.size(), "the workflow uses its actions: " + uses);
        for (String line : uses) {
            assertTrue(PINNED.matcher(line).matches(), "pinned on 40 hexadecimal characters then # vX.Y.Z: " + line);
        }
        Set<String> used = new TreeSet<>();
        for (String line : uses) {
            Matcher matcher = USES_LINE.matcher(line);
            assertTrue(matcher.matches());
            used.add(matcher.group(1));
        }
        assertEquals(new TreeSet<>(ACTIONS), used, "the actions the three jobs need, and no other");
    }

    @Test
    @DisplayName("checkout, setup-java et setup-gradle ont les mêmes SHA que ci.yml")
    void theSharedActionsAreTheOnesOfCi() throws IOException {
        Map<String, Set<String>> pages = shas(PAGES);
        Map<String, Set<String>> ci = shas(CI);
        for (String action : List.of("actions/checkout", "actions/setup-java", "gradle/actions/setup-gradle")) {
            assertEquals(1, ci.get(action).size(), "ci.yml pins " + action + " once");
            assertEquals(ci.get(action), pages.get(action), action + " has the SHA of ci.yml");
        }
    }

    private static Map<String, Set<String>> shas(Path workflow) throws IOException {
        Map<String, Set<String>> shas = new LinkedHashMap<>();
        for (String line : lines(workflow)) {
            Matcher matcher = USES_LINE.matcher(line);
            if (!ignorable(line) && matcher.matches()) {
                shas.computeIfAbsent(matcher.group(1), key -> new TreeSet<>()).add(matcher.group(2));
            }
        }
        for (String action : List.of("actions/checkout", "actions/setup-java", "gradle/actions/setup-gradle")) {
            shas.putIfAbsent(action, new TreeSet<>());
        }
        return shas;
    }

    @Test
    @DisplayName("comme ci.yml : Java 21 Temurin et wrappers Gradle validés dans les jobs qui lancent gradlew")
    void theGradleJobsAreSetUpAsInCi() throws IOException {
        Map<String, List<String>> jobs = jobs();
        for (String marker : List.of("gradlew conformity", "actions/upload-pages-artifact@")) {
            List<String> job = jobs.get(idOf(jobs, marker));
            List<String> java = stepUsing(job, "actions/setup-java");
            assertTrue(hasLine(java, "^\\s+distribution:\\s*temurin\\s*$"), "Temurin: " + text(java));
            assertTrue(hasLine(java, "^\\s+java-version:\\s*[\"']?21[\"']?\\s*$"), "Java 21: " + text(java));
            List<String> gradle = stepUsing(job, "gradle/actions/setup-gradle");
            assertTrue(hasLine(gradle, "^\\s+validate-wrappers:\\s*true\\s*$"), "validate-wrappers: true: " + text(gradle));

            int checkout = indexOfStep(job, step -> uses(step, "actions/checkout"));
            int setupJava = indexOfStep(job, step -> uses(step, "actions/setup-java"));
            int setupGradle = indexOfStep(job, step -> uses(step, "gradle/actions/setup-gradle"));
            int firstGradle = indexOfStep(job, step -> text(step).contains("./gradlew"));
            assertTrue(checkout >= 0, "checkout is a step");
            assertTrue(checkout < setupJava && setupJava < setupGradle && setupGradle < firstGradle,
                    "checkout, Java, Gradle, then the first ./gradlew: " + List.of(checkout, setupJava, setupGradle, firstGradle));
        }
    }
}
