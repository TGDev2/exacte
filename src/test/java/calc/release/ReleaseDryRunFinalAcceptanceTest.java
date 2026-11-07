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
 * The final dry run of the 1.0.0 release proves that {@code main}, as it will be tagged, publishes every file. Its evidence is
 * {@code docs/essais/release-1.0.0-essai-final.json}, written by {@code tools/release/verify-release.sh} and never by hand
 * (ADR-6 of the specification). These tests read only that file, through {@code calc.persistence.ReleaseJson}, and never call
 * {@code gh}: the trial happens on GitHub, the evidence is what the repository keeps of it.
 *
 * <p>Unlike the first trial, the final one has a single acceptable form: {@code result} is {@code ok}, with the version, the
 * pre-release tag, the run, the commit, the three checks, no problem and the six files with their SHA-256. A failed final trial
 * is a blocked task, not an evidence to commit. The tests on synthetic documents prove that nothing else is accepted.
 */
@Tag("acceptance")
class ReleaseDryRunFinalAcceptanceTest {
    private static final Path EVIDENCE = Path.of("docs", "essais", "release-1.0.0-essai-final.json");
    private static final String VERSION = "1.0.0";

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");
    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TAG = Pattern.compile("test-v1\\.0\\.0-[0-9]+");

    /** The six files of a release, in the order of {@code ReleaseFilesCheck}. */
    private static final List<String> FILES = List.of(
            "Calc-1.0.0-windows-x64.zip",
            "Calc-1.0.0-macos-arm64.tar.gz",
            "Calc-1.0.0-linux-x64.tar.gz",
            "Calc-1.0.0-sbom.cdx.json",
            "Calc-1.0.0-rapport-conformite.md",
            "SHA256SUMS.txt");

    // --- The evidence of the final trial, as committed ------------------------------------------------------------------------

    @Test
    void theEvidenceOfTheFinalTrialIsValidJsonWithResultOkAndVersion100() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), resultProblems(evidence), "result of " + EVIDENCE);
        assertEquals(VERSION, evidence.get("version"), "version of " + EVIDENCE);
    }

    @Test
    void theEvidenceOfTheFinalTrialNamesThePreReleaseTagTheRunAndTheCommit() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), identityProblems(evidence), "tag, runId and commit of " + EVIDENCE);
    }

    @Test
    void theEvidenceOfTheFinalTrialPassesTheThreeChecksWithoutAnyProblem() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), checkProblems(evidence), "prerelease, notesLinkInstallation, windowsStart and problems of " + EVIDENCE);
    }

    @Test
    void theEvidenceOfTheFinalTrialListsExactlyTheSixFilesWithTheirSha256() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), fileProblems(evidence), "files of " + EVIDENCE);
    }

    @Test
    void theEvidenceOfTheFinalTrialSatisfiesEveryCheck() throws Exception {
        Map<?, ?> evidence = readEvidence();

        assertEquals(List.of(), allProblems(evidence), EVIDENCE.toString());
    }

    // --- The checks themselves, proved on synthetic documents ---------------------------------------------------------------

    @Test
    void theOkFormWrittenByTheToolIsAccepted() throws Exception {
        assertEquals(List.of(), allProblems(parse(okEvidence())));
    }

    @Test
    void theEvidenceOfTheFirstTrialHasTheSameFormAndIsAcceptedByTheSameChecks() throws Exception {
        Path first = Path.of("docs", "essais", "release-1.0.0-essai-1.json");
        assertTrue(Files.isRegularFile(first), first + " is the evidence of the first trial, merged before this one");
        Map<?, ?> evidence = assertInstanceOf(Map.class, ReleaseJson.parse(Files.readString(first, StandardCharsets.UTF_8)));

        assertEquals(List.of(), allProblems(evidence), "the tool writes the same form for both trials");
    }

    @Test
    void anyResultOtherThanOkIsRefusedIncludingAFailedTrial() throws Exception {
        for (Object result : new Object[] {"failed", "OK", "Ok", "success", "pending", "", "ok ", true, null, 1}) {
            Map<String, Object> evidence = okEvidence();
            evidence.put("result", result);
            assertFalse(resultProblems(parse(evidence)).isEmpty(), "result " + result + " must be refused");
        }
        Map<String, Object> evidence = okEvidence();
        evidence.remove("result");
        assertFalse(resultProblems(parse(evidence)).isEmpty(), "a missing result must be refused");
    }

    @Test
    void aFailedTrialCompletedWithItsCauseAndItsFixIsStillRefused() throws Exception {
        Map<String, Object> evidence = okEvidence();
        evidence.put("result", "failed");
        evidence.put("windowsStart", "failed");
        evidence.put("cause", "Calc.exe ne démarre pas dans les 5 secondes");
        evidence.put("fix", "smoke-test.sh attend la fenêtre avant de mesurer");

        assertFalse(allProblems(parse(evidence)).isEmpty(), "the final trial has no failed form");
    }

    @Test
    void anyOtherVersionIsRefused() throws Exception {
        for (Object version : new Object[] {"1.0.1", "v1.0.0", "1.0.0-SNAPSHOT", "", null, 1}) {
            Map<String, Object> evidence = okEvidence();
            evidence.put("version", version);
            assertFalse(allProblems(parse(evidence)).isEmpty(), "version " + version + " must be refused");
        }
        Map<String, Object> evidence = okEvidence();
        evidence.remove("version");
        assertFalse(allProblems(parse(evidence)).isEmpty(), "a missing version must be refused");
    }

    @Test
    void aWrongTagRunOrCommitIsRefused() throws Exception {
        Map<String, Object[]> wrong = new LinkedHashMap<>();
        wrong.put("tag", new Object[] {"v1.0.0", "test-v1.0.0", "test-v1.0.0-", "test-v1.0.1-12", "test-v1.0.0-12a",
            " test-v1.0.0-12", "", null, 12});
        wrong.put("runId", new Object[] {"", "12a", " 123", "-1", "1.5", null, 123, true});
        wrong.put("commit", new Object[] {"0123456789abcdef0123456789abcdef0123456",
            "0123456789ABCDEF0123456789abcdef01234567", "0123456789abcdef0123456789abcdef012345678",
            "0123456789abcdef0123456789abcdef0123456g", "", null, 1});
        for (Map.Entry<String, Object[]> field : wrong.entrySet()) {
            for (Object value : field.getValue()) {
                Map<String, Object> evidence = okEvidence();
                evidence.put(field.getKey(), value);
                assertFalse(identityProblems(parse(evidence)).isEmpty(), field.getKey() + " = " + value + " must be refused");
            }
            Map<String, Object> evidence = okEvidence();
            evidence.remove(field.getKey());
            assertFalse(identityProblems(parse(evidence)).isEmpty(), "a missing " + field.getKey() + " must be refused");
        }
    }

    @Test
    void aFailedCheckOrAnyProblemIsRefused() throws Exception {
        Map<String, Object[]> wrong = new LinkedHashMap<>();
        wrong.put("prerelease", new Object[] {false, "true", "yes", null, 1});
        wrong.put("notesLinkInstallation", new Object[] {false, "true", "yes", null, 1});
        wrong.put("windowsStart", new Object[] {"failed", "OK", "ok ", true, "", null});
        wrong.put("problems", new Object[] {List.of("Calc-1.0.0-sbom.cdx.json: missing from the release"), List.of(""),
            Map.of(), "", null});
        for (Map.Entry<String, Object[]> field : wrong.entrySet()) {
            for (Object value : field.getValue()) {
                Map<String, Object> evidence = okEvidence();
                evidence.put(field.getKey(), value);
                assertFalse(checkProblems(parse(evidence)).isEmpty(), field.getKey() + " = " + value + " must be refused");
            }
            Map<String, Object> evidence = okEvidence();
            evidence.remove(field.getKey());
            assertFalse(checkProblems(parse(evidence)).isEmpty(), "a missing " + field.getKey() + " must be refused");
        }
    }

    @Test
    void aMissingRenamedOrExtraFileIsRefused() throws Exception {
        for (String name : FILES) {
            Map<String, Object> evidence = okEvidence();
            Map<String, Object> files = filesOf(evidence);
            files.remove(name);
            evidence.put("files", files);
            assertFalse(fileProblems(parse(evidence)).isEmpty(), "five files without " + name + " must be refused");

            evidence = okEvidence();
            files = filesOf(evidence);
            Object sum = files.remove(name);
            String renamed = name.contains(VERSION) ? name.replace(VERSION, "1.0.1") : name.toLowerCase();
            files.put(renamed, sum);
            evidence.put("files", files);
            assertFalse(fileProblems(parse(evidence)).isEmpty(), "six files with " + name + " renamed must be refused");
        }

        Map<String, Object> sevenFiles = okEvidence();
        Map<String, Object> files = filesOf(sevenFiles);
        files.put("Calc-1.0.0-notes.md", "cd".repeat(32));
        sevenFiles.put("files", files);
        assertFalse(fileProblems(parse(sevenFiles)).isEmpty(), "seven files must be refused");

        for (Object notAnObject : new Object[] {List.of(), List.of(FILES.get(0)), "", null}) {
            Map<String, Object> evidence = okEvidence();
            evidence.put("files", notAnObject);
            assertFalse(fileProblems(parse(evidence)).isEmpty(), "files = " + notAnObject + " must be refused");
        }
        Map<String, Object> evidence = okEvidence();
        evidence.remove("files");
        assertFalse(fileProblems(parse(evidence)).isEmpty(), "missing files must be refused");
    }

    @Test
    void aSumThatIsNot64LowercaseHexadecimalCharactersIsRefused() throws Exception {
        for (String name : FILES) {
            for (Object sum : new Object[] {"ab".repeat(31), "ab".repeat(33), "AB".repeat(32), "g".repeat(64),
                "ab".repeat(32) + " ", "", null, 1, true}) {
                Map<String, Object> evidence = okEvidence();
                Map<String, Object> files = filesOf(evidence);
                files.put(name, sum);
                evidence.put("files", files);
                assertFalse(fileProblems(parse(evidence)).isEmpty(), "files." + name + " = " + sum + " must be refused");
            }
        }
    }

    @Test
    void theOrderOfTheFilesDoesNotMatter() throws Exception {
        Map<String, Object> evidence = okEvidence();
        Map<String, Object> files = filesOf(evidence);
        Map<String, Object> reversed = new LinkedHashMap<>();
        for (int index = FILES.size() - 1; index >= 0; index--) {
            reversed.put(FILES.get(index), files.get(FILES.get(index)));
        }
        evidence.put("files", reversed);

        assertEquals(List.of(), fileProblems(parse(evidence)));
    }

    // --- The checks ---------------------------------------------------------------------------------------------------------

    private static Map<?, ?> readEvidence() throws IOException {
        assertTrue(Files.isRegularFile(EVIDENCE), EVIDENCE + " must exist: it is the evidence of the final dry run");
        String text = Files.readString(EVIDENCE, StandardCharsets.UTF_8);
        Object parsed;
        try {
            parsed = ReleaseJson.parse(text);
        } catch (JsonException e) {
            return fail(EVIDENCE + " must be valid JSON: " + e.getMessage(), e);
        }
        return assertInstanceOf(Map.class, parsed, EVIDENCE + " must be a JSON object");
    }

    /** Every problem of the document: the final trial has one form, the ok one. */
    private static List<String> allProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>(resultProblems(evidence));
        if (!VERSION.equals(evidence.get("version"))) {
            problems.add("version must be " + VERSION + ", not " + evidence.get("version"));
        }
        problems.addAll(identityProblems(evidence));
        problems.addAll(checkProblems(evidence));
        problems.addAll(fileProblems(evidence));
        return problems;
    }

    /** result is ok and nothing else: a failed final trial is a blocked task, not an evidence. */
    private static List<String> resultProblems(Map<?, ?> evidence) {
        Object result = evidence.get("result");
        if ("ok".equals(result)) {
            return List.of();
        }
        return List.of("result must be ok, not " + result);
    }

    /** A tag test-v1.0.0-<number>, a non-empty runId made of digits and a commit of 40 hexadecimal characters. */
    private static List<String> identityProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>();
        if (!matches(evidence.get("tag"), TAG)) {
            problems.add("tag must be test-v1.0.0-<number>, not " + evidence.get("tag"));
        }
        if (!matches(evidence.get("runId"), DIGITS)) {
            problems.add("runId must be a non-empty string of digits, not " + evidence.get("runId"));
        }
        if (!matches(evidence.get("commit"), COMMIT)) {
            problems.add("commit must be 40 hexadecimal characters, not " + evidence.get("commit"));
        }
        return problems;
    }

    /** prerelease and notesLinkInstallation true, windowsStart ok and an empty list of problems. */
    private static List<String> checkProblems(Map<?, ?> evidence) {
        List<String> problems = new ArrayList<>();
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
        return problems;
    }

    /** Exactly the six files of the release, each with a SHA-256 of 64 hexadecimal characters. */
    private static List<String> fileProblems(Map<?, ?> evidence) {
        if (!(evidence.get("files") instanceof Map<?, ?> files)) {
            return List.of("files must be a JSON object, not " + evidence.get("files"));
        }
        List<String> problems = new ArrayList<>();
        if (!files.keySet().equals(Set.copyOf(FILES))) {
            problems.add("files must name exactly " + FILES + ", not " + files.keySet());
        }
        files.forEach((name, sum) -> {
            if (!matches(sum, SHA256)) {
                problems.add("files." + name + " must be 64 hexadecimal characters, not " + sum);
            }
        });
        return problems;
    }

    private static boolean matches(Object value, Pattern pattern) {
        return value instanceof String text && pattern.matcher(text).matches();
    }

    // --- Synthetic documents, rendered and parsed like the real file --------------------------------------------------------

    /** The ok form, as {@code ReleaseFilesCheck.evidence} writes it. */
    private static Map<String, Object> okEvidence() {
        Map<String, Object> files = new LinkedHashMap<>();
        for (int index = 0; index < FILES.size(); index++) {
            files.put(FILES.get(index), String.format("%02d", index + 1).repeat(32));
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("result", "ok");
        evidence.put("version", VERSION);
        evidence.put("tag", "test-v1.0.0-2");
        evidence.put("runId", "123456");
        evidence.put("commit", "0123456789abcdef0123456789abcdef01234567");
        evidence.put("prerelease", true);
        evidence.put("notesLinkInstallation", true);
        evidence.put("windowsStart", "ok");
        evidence.put("files", files);
        evidence.put("problems", List.of());
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
            return "[" + String.join(", ", list.stream().map(ReleaseDryRunFinalAcceptanceTest::render).toList()) + "]";
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
