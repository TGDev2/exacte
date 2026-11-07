package calc.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import calc.persistence.JsonException;
import calc.persistence.ReleaseJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The first dry run of the 1.0.0 release leaves its evidence in {@code docs/essais/release-1.0.0-essai-1.json} (ADR-6 of the
 * specification). These tests read only that file, through {@code calc.persistence.ReleaseJson}, and never call {@code gh}: the
 * trial itself happens on GitHub, the evidence is what the repository keeps of it.
 *
 * <p>The evidence takes one of two forms. A trial that reached a release is written by {@code ReleaseFilesCheck.evidence}: its
 * {@code result} is {@code ok} when the six files, the three checks and the absence of problem all hold, {@code failed}
 * otherwise, and the session then completes the file with {@code cause} and {@code fix}. A trial that produced no release at all
 * is written by hand, with {@code result} {@code failed} and the same two fields. Both forms carry the version, the run and the
 * commit. The tests of the file accept both forms; the tests on synthetic documents prove that nothing else is accepted.
 */
@Tag("acceptance")
class ReleaseDryRunFirstAcceptanceTest {
    private static final Path EVIDENCE = Path.of("docs", "essais", "release-1.0.0-essai-1.json");
    private static final String VERSION = "1.0.0";

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TAG = Pattern.compile("test-v1\\.0\\.0-[0-9]+");

    private static final Set<String> RESULTS = Set.of("ok", "failed");
    private static final int FILE_COUNT = 6;

    // --- The evidence of the first trial, as committed ------------------------------------------------------------------------

    @Test
    void theEvidenceOfTheFirstTrialIsValidJsonWithTheVersionTheRunAndTheCommit() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), identityProblems(evidence), "version, runId and commit of " + EVIDENCE);
    }

    @Test
    void theResultOfTheFirstTrialIsOkOrFailedAndNothingElse() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), resultProblems(evidence), "result of " + EVIDENCE);
    }

    @Test
    void aSuccessfulFirstTrialHasTheTagTheThreeChecksNoProblemAndSixFiles() throws Exception {
        Map<?, ?> evidence = readEvidence();

        if ("ok".equals(evidence.get("result"))) {
            assertEquals(List.of(), successProblems(evidence), "the ok form of " + EVIDENCE);
        } else {
            assertEquals("failed", evidence.get("result"), "the only other result");
        }
    }

    @Test
    void aFailedFirstTrialNamesItsCauseAndItsFix() throws Exception {
        Map<?, ?> evidence = readEvidence();

        if ("failed".equals(evidence.get("result"))) {
            assertEquals(List.of(), failureProblems(evidence), "the failed form of " + EVIDENCE);
        } else {
            assertEquals("ok", evidence.get("result"), "the only other result");
        }
    }

    @Test
    void theEvidenceOfTheFirstTrialSatisfiesEveryCheckOfItsForm() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), allProblems(evidence), EVIDENCE.toString());
    }

    // --- The checks themselves, proved on synthetic documents ---------------------------------------------------------------

    @Test
    void theOkFormWrittenByTheToolIsAccepted() throws Exception {
        assertEquals(List.of(), allProblems(parse(okEvidence())));
    }

    @Test
    void theFailedFormCompletedByTheSessionIsAccepted() throws Exception {
        Map<String, Object> evidence = okEvidence();
        evidence.put("result", "failed");
        evidence.put("windowsStart", "failed");
        evidence.put("cause", "Calc.exe ne démarre pas dans les 5 secondes");
        evidence.put("fix", "smoke-test.sh attend la fenêtre avant de mesurer");

        assertEquals(List.of(), allProblems(parse(evidence)));
    }

    @Test
    void theFailedFormWrittenByHandIsAccepted() throws Exception {
        assertEquals(List.of(), allProblems(parse(handWrittenFailure())));
    }

    @Test
    void anyOtherResultIsRefused() throws Exception {
        for (Object result : new Object[] {"OK", "Ok", "success", "pending", "", "failed ", true, null, 1}) {
            Map<String, Object> evidence = okEvidence();
            evidence.put("result", result);
            assertFalse(resultProblems(parse(evidence)).isEmpty(), "result " + result + " must be refused");
        }
        Map<String, Object> evidence = okEvidence();
        evidence.remove("result");
        assertFalse(resultProblems(parse(evidence)).isEmpty(), "a missing result must be refused");
    }

    @Test
    void theVersionTheRunAndTheCommitAreCheckedInBothForms() throws Exception {
        Map<String, Object[]> wrong = new LinkedHashMap<>();
        wrong.put("version", new Object[] {"1.0.1", "v1.0.0", "", null, 1});
        wrong.put("runId", new Object[] {"", "12a", " 123", "-1", null, 123});
        wrong.put("commit", new Object[] {"0123456789abcdef0123456789abcdef0123456",
            "0123456789ABCDEF0123456789abcdef01234567", "0123456789abcdef0123456789abcdef012345678", "", null});
        for (Map<String, Object> form : List.of(okEvidence(), handWrittenFailure())) {
            for (Map.Entry<String, Object[]> field : wrong.entrySet()) {
                for (Object value : field.getValue()) {
                    Map<String, Object> evidence = new LinkedHashMap<>(form);
                    evidence.put(field.getKey(), value);
                    assertFalse(identityProblems(parse(evidence)).isEmpty(),
                            field.getKey() + " = " + value + " must be refused in the " + form.get("result") + " form");
                }
                Map<String, Object> evidence = new LinkedHashMap<>(form);
                evidence.remove(field.getKey());
                assertFalse(identityProblems(parse(evidence)).isEmpty(),
                        "a missing " + field.getKey() + " must be refused in the " + form.get("result") + " form");
            }
        }
    }

    @Test
    void anOkResultIsRefusedWhenAnyPartOfTheSuccessIsMissing() throws Exception {
        Map<String, Object[]> wrong = new LinkedHashMap<>();
        wrong.put("tag", new Object[] {"v1.0.0", "test-v1.0.0", "test-v1.0.0-", "test-v1.0.1-12", "test-v1.0.0-12a", "", null});
        wrong.put("prerelease", new Object[] {false, "true", null});
        wrong.put("notesLinkInstallation", new Object[] {false, "true", null});
        wrong.put("windowsStart", new Object[] {"failed", "OK", true, "", null});
        wrong.put("problems", new Object[] {List.of("Calc-1.0.0-sbom.cdx.json: missing from the release"), "", null});
        for (Map.Entry<String, Object[]> field : wrong.entrySet()) {
            for (Object value : field.getValue()) {
                Map<String, Object> evidence = okEvidence();
                evidence.put(field.getKey(), value);
                assertFalse(successProblems(parse(evidence)).isEmpty(), field.getKey() + " = " + value + " must be refused");
            }
            Map<String, Object> evidence = okEvidence();
            evidence.remove(field.getKey());
            assertFalse(successProblems(parse(evidence)).isEmpty(), "a missing " + field.getKey() + " must be refused");
        }
    }

    @Test
    void anOkResultIsRefusedWithoutExactlySixFilesWithTheirSha256() throws Exception {
        Map<String, Object> fiveFiles = okEvidence();
        Map<String, Object> files = filesOf(fiveFiles);
        files.remove("Calc-1.0.0-rapport-conformite.md");
        fiveFiles.put("files", files);
        assertFalse(successProblems(parse(fiveFiles)).isEmpty(), "five files must be refused");

        Map<String, Object> sevenFiles = okEvidence();
        files = filesOf(sevenFiles);
        files.put("Calc-1.0.0-notes.md", "cd".repeat(32));
        sevenFiles.put("files", files);
        assertFalse(successProblems(parse(sevenFiles)).isEmpty(), "seven files must be refused");

        for (Object sum : new Object[] {"ab".repeat(31), "ab".repeat(33), "AB".repeat(32), "", null, 1}) {
            Map<String, Object> evidence = okEvidence();
            files = filesOf(evidence);
            files.put("Calc-1.0.0-windows-x64.zip", sum);
            evidence.put("files", files);
            assertFalse(successProblems(parse(evidence)).isEmpty(), "sum " + sum + " must be refused");
        }

        for (Object notAnObject : new Object[] {List.of(), "", null}) {
            Map<String, Object> evidence = okEvidence();
            evidence.put("files", notAnObject);
            assertFalse(successProblems(parse(evidence)).isEmpty(), "files = " + notAnObject + " must be refused");
        }
        Map<String, Object> evidence = okEvidence();
        evidence.remove("files");
        assertFalse(successProblems(parse(evidence)).isEmpty(), "missing files must be refused");
    }

    @Test
    void aFailedResultIsRefusedWithoutACauseAndAFixInBothForms() throws Exception {
        Map<String, Object> completed = okEvidence();
        completed.put("result", "failed");
        completed.put("problems", List.of("Calc-1.0.0-sbom.cdx.json: missing from the release"));
        completed.put("cause", "le job sbom ne publie pas son fichier");
        completed.put("fix", "release.yml joint bom.json");
        for (Map<String, Object> form : List.of(completed, handWrittenFailure())) {
            assertEquals(List.of(), allProblems(parse(form)), "the complete form is accepted");
            for (String field : List.of("cause", "fix")) {
                for (Object value : new Object[] {"", "   ", null, true}) {
                    Map<String, Object> evidence = new LinkedHashMap<>(form);
                    evidence.put(field, value);
                    assertFalse(failureProblems(parse(evidence)).isEmpty(), field + " = " + value + " must be refused");
                }
                Map<String, Object> evidence = new LinkedHashMap<>(form);
                evidence.remove(field);
                assertFalse(failureProblems(parse(evidence)).isEmpty(), "a missing " + field + " must be refused");
            }
        }
    }

    @Test
    void theChecksOfAFormApplyOnlyToThatForm() throws Exception {
        Map<String, Object> evidence = okEvidence();
        evidence.put("windowsStart", "failed");
        assertFalse(allProblems(parse(evidence)).isEmpty(), "ok with a failed Windows start must be refused");

        evidence = okEvidence();
        evidence.put("result", "failed");
        assertFalse(allProblems(parse(evidence)).isEmpty(), "failed without cause and fix must be refused");

        evidence = handWrittenFailure();
        assertTrue(allProblems(parse(evidence)).isEmpty(), "the hand-written form needs no tag, no check and no file");
    }

    // --- The checks ---------------------------------------------------------------------------------------------------------

    private static Map<?, ?> readEvidence() throws IOException {
        assertTrue(Files.isRegularFile(EVIDENCE), EVIDENCE + " must exist: it is the evidence of the first dry run");
        String text = Files.readString(EVIDENCE, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = ReleaseJson.parse(text);
        } catch (JsonException e) {
            return fail(EVIDENCE + " must be valid JSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(Map.class, parsed, EVIDENCE + " must be a JSON object");
    }

    /** Every problem of the document, whatever its form. */
    private static List<String> allProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>(identityProblems(evidence));
        problems.addAll(resultProblems(evidence));
        if ("ok".equals(evidence.get("result"))) {
            problems.addAll(successProblems(evidence));
        } else if ("failed".equals(evidence.get("result"))) {
            problems.addAll(failureProblems(evidence));
        }
        return problems;
    }

    /** Version equal to 1.0.0, a non-empty runId made of digits and a commit of 40 hexadecimal characters: both forms. */
    private static List<String> identityProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>();
        if (!VERSION.equals(evidence.get("version"))) {
            problems.add("version must be " + VERSION + ", not " + evidence.get("version"));
        }
        if (!matches(evidence.get("runId"), DIGITS)) {
            problems.add("runId must be a non-empty string of digits, not " + evidence.get("runId"));
        }
        if (!matches(evidence.get("commit"), COMMIT)) {
            problems.add("commit must be 40 hexadecimal characters, not " + evidence.get("commit"));
        }
        return problems;
    }

    /** result is ok or failed and nothing else. */
    private static List<String> resultProblems(Map<?, ?> evidence) {
        Object result = evidence.get("result");
        if (result instanceof String text && RESULTS.contains(text)) {
            return List.of();
        }
        return List.of("result must be ok or failed, not " + result);
    }

    /** With result ok: the tag, the three checks, no problem and the six files with their SHA-256. */
    private static List<String> successProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>();
        if (!matches(evidence.get("tag"), TAG)) {
            problems.add("tag must be test-v1.0.0-<number>, not " + evidence.get("tag"));
        }
        for (String check : List.of("prerelease", "notesLinkInstallation")) {
            if (!Boolean.TRUE.equals(evidence.get(check))) {
                problems.add(check + " must be true, not " + evidence.get(check));
            }
        }
        if (!"ok".equals(evidence.get("windowsStart"))) {
            problems.add("windowsStart must be ok, not " + evidence.get("windowsStart"));
        }
        if (!List.of().equals(evidence.get("problems"))) {
            problems.add("problems must be an empty list, not " + evidence.get("problems"));
        }
        if (evidence.get("files") instanceof Map<?, ?> files) {
            if (files.size() != FILE_COUNT) {
                problems.add("files must count " + FILE_COUNT + " entries, not " + files.size());
            }
            files.forEach((name, sum) -> {
                if (!matches(sum, SHA256)) {
                    problems.add("files." + name + " must be 64 hexadecimal characters, not " + sum);
                }
            });
        } else {
            problems.add("files must be a JSON object, not " + evidence.get("files"));
        }
        return problems;
    }

    /** With result failed, written by the tool then completed or written by hand: a non-empty cause and fix. */
    private static List<String> failureProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>();
        for (String field : List.of("cause", "fix")) {
            if (!(evidence.get(field) instanceof String text) || text.isBlank()) {
                problems.add(field + " must be a non-blank string, not " + evidence.get(field));
            }
        }
        return problems;
    }

    private static boolean matches(Object value, Pattern pattern) {
        return value instanceof String text && pattern.matcher(text).matches();
    }

    // --- Synthetic documents, rendered and parsed like the real file --------------------------------------------------------

    /** The ok form, as {@code ReleaseFilesCheck.evidence} writes it. */
    private static Map<String, Object> okEvidence() {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("Calc-1.0.0-windows-x64.zip", "01".repeat(32));
        files.put("Calc-1.0.0-macos-arm64.tar.gz", "02".repeat(32));
        files.put("Calc-1.0.0-linux-x64.tar.gz", "03".repeat(32));
        files.put("Calc-1.0.0-sbom.cdx.json", "04".repeat(32));
        files.put("Calc-1.0.0-rapport-conformite.md", "05".repeat(32));
        files.put("SHA256SUMS.txt", "06".repeat(32));
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("result", "ok");
        evidence.put("version", VERSION);
        evidence.put("tag", "test-v1.0.0-12");
        evidence.put("runId", "123456");
        evidence.put("commit", "0123456789abcdef0123456789abcdef01234567");
        evidence.put("prerelease", true);
        evidence.put("notesLinkInstallation", true);
        evidence.put("windowsStart", "ok");
        evidence.put("files", files);
        evidence.put("problems", List.of());
        return evidence;
    }

    /** The failed form written by hand when no release was published. */
    private static Map<String, Object> handWrittenFailure() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("result", "failed");
        evidence.put("version", VERSION);
        evidence.put("runId", "123457");
        evidence.put("commit", "89abcdef0123456789abcdef0123456789abcdef");
        evidence.put("cause", "le job build (macos) échoue : jpackage absent");
        evidence.put("fix", "release.yml installe le JDK complet");
        return evidence;
    }

    /** Renders the document as JSON and parses it back with {@code ReleaseJson}, so the checks see what the file would give. */
    private static Map<?, ?> parse(Map<String, Object> evidence) throws JsonException {
        return assertInstanceOf(Map.class, ReleaseJson.parse(render(evidence)));
    }

    /** A modifiable copy of the {@code files} of a synthetic document. */
    private static Map<String, Object> filesOf(Map<String, Object> evidence) {
        Map<String, Object> files = new LinkedHashMap<>();
        Map<?, ?> original = assertInstanceOf(Map.class, evidence.get("files"));
        original.forEach((name, sum) -> files.put(name.toString(), sum));
        return files;
    }

    private static String render(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            return quoted(text);
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(quoted(key.toString()) + ": " + render(item)));
            return "{" + String.join(", ", entries) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(", ", list.stream().map(ReleaseDryRunFirstAcceptanceTest::render).toList()) + "]";
        }
        throw new IllegalArgumentException("not rendered: " + value.getClass());
    }

    private static String quoted(String text) {
        StringBuilder json = new StringBuilder("\"");
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"' || current == '\\') {
                json.append('\\').append(current);
            } else if (current < 0x20) {
                json.append(String.format("\\u%04x", (int) current));
            } else {
                json.append(current);
            }
        }
        return json.append('"').toString();
    }
}
