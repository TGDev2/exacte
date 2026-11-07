package calc.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.document.DocumentException.Kind;
import calc.engine.AtomicFileWriter;
import calc.notebook.NotebookCommand;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G01: the test of the refusal of a future-version file as the target of a save as no longer assumes a file system that
 * ignores the case of names (ADR-1 of the spec). The contract the tests below hold the work to:
 * <ul>
 * <li>a shared test helper {@code calc.testing.CaseSensitivity.ignoresCase(Path folder)} (public, static, returns a
 * boolean, may throw {@link IOException}) says whether a folder ignores case: it creates a witness file, asks whether the
 * same name in capitals designates it, removes the witness, and answers true in the temporary folder under Windows;</li>
 * <li>the refusal of a path that goes around ({@code sub/../devis.calc.json}) is checked on every system;</li>
 * <li>the refusal of {@code DEVIS.CALC.JSON} is checked only where the folder ignores case; where it does not, a distinct
 * test checks that writing {@code DEVIS.CALC.JSON} leaves {@code devis.calc.json} untouched;</li>
 * <li>{@link FutureVersionTargetAcceptanceTest#theExtensionAddedToTheNameDoesNotHideAFutureFile} is split accordingly,
 * without proving less than before.</li>
 * </ul>
 * The helper is reached by reflection so that a missing helper is a failed test with a clear message and not a source set
 * that no longer compiles. The production code is not part of the task: {@link DocumentService} already does the right thing.
 */
@Tag("acceptance")
class CaseAwareFutureTargetAcceptanceTest {
    private static final String HELPER = "calc.testing.CaseSensitivity";
    private static final String FUTURE = "{\n  \"format\": \"calc-notebook\",\n  \"version\": 99,\n"
            + "  \"owner\": {\"name\": \"Zoé\", \"tags\": [1, 2, 3]},\n"
            + "  \"lines\": [{\"id\": \"1\", \"source\": \"1 + 1\", \"later\": true}]\n}\n";
    private static final Path OLD_TEST = Path.of("src", "test", "java", "calc", "document", "FutureVersionTargetAcceptanceTest.java");
    private static final String SPLIT_METHOD = "void theExtensionAddedToTheNameDoesNotHideAFutureFile()";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private NotebookStore store;
    private final AtomicInteger moves = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        store = NotebookStore.open(data);
    }

    @AfterEach
    void stop() {
        store.close();
    }

    // ------------------------------------------------------------------------------------------------ the shared helper

    /** Calls the shared helper on a folder, as the tests will once it exists. */
    private static boolean ignoresCase(Path folder) throws Exception {
        Class<?> helper;
        try {
            helper = Class.forName(HELPER);
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("the shared test helper " + HELPER + " does not exist yet (src/test/java/calc/testing)", missing);
        }
        Method method;
        try {
            method = helper.getMethod("ignoresCase", Path.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError(HELPER + " must offer a public static boolean ignoresCase(Path folder)", missing);
        }
        assertEquals(boolean.class, method.getReturnType(), "ignoresCase answers a boolean");
        assertTrue(java.lang.reflect.Modifier.isStatic(method.getModifiers()), "ignoresCase is static");
        try {
            return (Boolean) method.invoke(null, folder);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failed;
        }
    }

    /** What the helper must find out, observed by other means: the same name in capitals designates the file just written. */
    private static boolean observedIgnoresCase(Path folder) throws IOException {
        Path witness = Files.createTempFile(folder, "witness-", ".tmp");
        try {
            String name = witness.getFileName().toString();
            return Files.exists(folder.resolve(name.toUpperCase(Locale.ROOT)));
        } finally {
            Files.deleteIfExists(witness);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    @Test
    void theHelperSaysThatTheTemporaryFolderIgnoresCaseUnderWindows() throws Exception {
        assumeTrue(windows(), "the file systems of Windows ignore case; elsewhere the answer depends on the volume");

        assertTrue(ignoresCase(documents), "the temporary folder of the tests, under Windows, ignores the case of names");
    }

    @Test
    void theHelperAgreesWithWhatTheFolderReallyDoes() throws Exception {
        assertEquals(observedIgnoresCase(documents), ignoresCase(documents));
    }

    @Test
    void theHelperLeavesNoWitnessFileBehind() throws Exception {
        Files.writeString(documents.resolve("devis.calc.json"), FUTURE, StandardCharsets.UTF_8);
        Map<String, byte[]> before = snapshot();

        ignoresCase(documents);

        assertSameSnapshot(before, snapshot(), "the helper checked the folder");
    }

    // ------------------------------------------------------------------------------------------------ behaviour of the service

    private DocumentService service() throws Exception {
        AtomicFileWriter.Mover mover = (source, target, options) -> {
            moves.incrementAndGet();
            Files.move(source, target, options);
        };
        DocumentService service = new DocumentService(store, store.load(), mover);
        service.apply(new NotebookCommand.Append("a = 2"));
        service.apply(new NotebookCommand.Append("a * 3"));
        moves.set(0);
        return service;
    }

    private Path future(String name) throws IOException {
        Path file = documents.resolve(name);
        Files.writeString(file, FUTURE, StandardCharsets.UTF_8);
        return file;
    }

    private Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (Path folder : List.of(documents, data)) {
            try (Stream<Path> listed = Files.list(folder)) {
                for (Path file : listed.filter(each -> !each.getFileName().toString().equals("history.lock")).toList()) {
                    files.put(root.relativize(file).toString(), Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0]);
                }
            }
        }
        return files;
    }

    private static void assertSameSnapshot(Map<String, byte[]> before, Map<String, byte[]> after, String message) {
        assertEquals(before.keySet(), after.keySet(), message + " (no file created, none removed)");
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), message + ": " + name));
    }

    @Test
    void aPathThatGoesAroundWithTheSameCaseIsRefusedOnEverySystem() throws Exception {
        Path target = future("devis.calc.json");
        DocumentService service = service();
        Map<String, byte[]> before = snapshot();

        DocumentException refused = assertThrows(DocumentException.class,
                () -> service.saveAs(documents.resolve("sub").resolve("..").resolve("devis.calc.json")));

        assertEquals(Kind.READ_ONLY_VERSION, refused.kind());
        assertEquals(0, moves.get(), "nothing is moved into place");
        assertSameSnapshot(before, snapshot(), "refused");
        assertEquals(FUTURE, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void theCapitalsVariantIsRefusedWhereTheFolderIgnoresCase() throws Exception {
        assumeTrue(ignoresCase(documents), "this system tells devis.calc.json and DEVIS.CALC.JSON apart");
        Path target = future("devis.calc.json");
        DocumentService service = service();
        Map<String, byte[]> before = snapshot();

        DocumentException refused = assertThrows(DocumentException.class,
                () -> service.saveAs(documents.resolve("sub").resolve("..").resolve("DEVIS.CALC.JSON")));

        assertEquals(Kind.READ_ONLY_VERSION, refused.kind());
        assertEquals(0, moves.get(), "nothing is moved into place");
        assertSameSnapshot(before, snapshot(), "refused");
        assertEquals(FUTURE, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void writingTheCapitalsVariantLeavesTheFutureFileIntactWhereTheFolderKeepsCase() throws Exception {
        assumeFalse(ignoresCase(documents), "this system takes DEVIS.CALC.JSON for devis.calc.json: the refusal is tested above");
        Path target = future("devis.calc.json");
        DocumentService service = service();

        Path written = service.saveAs(documents.resolve("DEVIS.CALC.JSON"));

        assertEquals("DEVIS.CALC.JSON", written.getFileName().toString());
        assertEquals(FUTURE, Files.readString(target, StandardCharsets.UTF_8), "the file of the future version is untouched");
        assertEquals(List.of("a = 2", "a * 3"), NotebookCodec.read(written).notebook().texts(), "the other file received the notebook");
    }

    // ------------------------------------------------------------------------------------------------ the split test

    private static String oldTestSource() throws IOException {
        return Files.readString(OLD_TEST, StandardCharsets.UTF_8);
    }

    /** The text of the {@code @Test} method that starts with {@code signature}, up to the next annotation of a method. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "the method " + signature + " still exists");
        int end = source.indexOf("@Test", start);
        return source.substring(start, end < 0 ? source.length() : end);
    }

    @Test
    void theSplitTestRefusesTheAddedExtensionAndTheSameCaseDetourOnEverySystem() throws IOException {
        String body = methodBody(oldTestSource(), SPLIT_METHOD);

        Matcher detour = Pattern.compile("resolve\\(\"\\.\\.\"\\)\\s*\\.resolve\\(\"devis\\.calc\\.json\"\\)").matcher(body);
        assertTrue(detour.find(), "a path sub/../devis.calc.json (same case) is refused too");
        Matcher assumption = Pattern.compile("assumeTrue\\([^;]*ignoresCase\\(").matcher(body);
        assertTrue(assumption.find(), "the capitals variant is guarded by assumeTrue on the detection of a case-insensitive folder");
        assertTrue(detour.start() < assumption.start(), "the same-case detour is checked before the assumption, so on every system");
        assertTrue(body.contains("service.saveAs(documents.resolve(\"devis\"))"), "the name without the extension is still refused");
    }

    @Test
    void theCapitalsVariantOfTheSplitTestComesAfterTheAssumption() throws IOException {
        String body = methodBody(oldTestSource(), SPLIT_METHOD);

        int assumption = body.indexOf("assumeTrue");
        int capitals = body.indexOf("DEVIS.CALC.JSON");
        assertTrue(assumption >= 0 && capitals > assumption, "DEVIS.CALC.JSON is only tried once the assumption has held");
    }

    @Test
    void theSplitTestStillProvesAsMuchAsBefore() throws IOException {
        String body = methodBody(oldTestSource(), SPLIT_METHOD);

        long refusals = Pattern.compile("assertThrows\\(DocumentException\\.class").matcher(body).results().count();
        assertTrue(refusals >= 3, "three refusals: the extension added, the detour, the capitals variant (found " + refusals + ")");
        assertTrue(body.contains("READ_ONLY_VERSION"), "the kind of the refusal is still checked");
        assertTrue(body.contains("moves.get()"), "nothing is moved");
        assertTrue(body.contains("assertSameSnapshot(before, snapshot()"), "nothing is written, no file left behind");
        assertTrue(body.contains("assertEquals(FUTURE_99, Files.readString(target"), "the future file is intact");
    }

    @Test
    void aDistinctTestChecksTheCapitalsVariantWhereCaseIsKept() throws IOException {
        String source = oldTestSource();
        String[] parts = source.split("@(?:Test|ParameterizedTest)");

        boolean found = false;
        for (String part : parts) {
            if (!part.contains(SPLIT_METHOD) && part.contains("DEVIS.CALC.JSON") && part.contains("assumeFalse")
                    && Pattern.compile("assumeFalse\\([^;]*ignoresCase\\(").matcher(part).find()) {
                found = true;
            }
        }
        assertTrue(found, "FutureVersionTargetAcceptanceTest has a test, other than the split one, that assumes a case-keeping folder "
                + "and writes DEVIS.CALC.JSON");
    }
}
