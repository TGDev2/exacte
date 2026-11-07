package calc.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The six files of a release are checked by {@code calc.release.ReleaseFilesCheck} before anyone trusts them: names, checksums,
 * SBOM, conformity report and the content of the three archives. It also writes the evidence of a dry run. These tests are
 * hermetic: every release is built on the fly in a temporary folder, with the shape of the real files (paths of the jpackage
 * images, {@code Calc.cfg} with CRLF on Windows, the lines of {@code gradle/conformity.gradle.kts}, the format of
 * {@code sha256sum}). Whether {@code tools/release/verify-release.sh} works against a real release is proved by the dry run of
 * the following task, not here: without a published release, only its content is checked.
 *
 * <p>{@code calc.release.ReleaseFilesCheck} is called by reflection on purpose: a direct reference would stop the whole test
 * source set from compiling until the class exists, and every other test would fail with it.
 */
@Tag("acceptance")
class ReleaseFilesCheckAcceptanceTest {
    private static final String TOOL = "calc.release.ReleaseFilesCheck";
    private static final Path TOOL_SOURCE = Path.of("src", "test", "java", "calc", "release", "ReleaseFilesCheck.java");
    private static final Path SCRIPT = Path.of("tools", "release", "verify-release.sh");
    private static final Path BUILD_SCRIPT = Path.of("build.gradle.kts");
    private static final Path RELEASE_DOC = Path.of("docs", "RELEASE.md");

    private static final String VERSION = "1.0.0";
    private static final String TAG = "test-v1.0.0-12";
    private static final String RUN = "123";
    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";
    private static final String SUMS = "SHA256SUMS.txt";
    private static final String OTHER_HEX = "ab".repeat(32);

    private static final String SBOM = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
            + "\"metadata\":{\"component\":{\"version\":\"%s\"}}}";

    private static final String VERSION_LINE = "- Version : `1.0.0`";
    private static final String VERDICT = "**Verdict : CONFORME**";
    private static final String UI_SENTENCE = "Les 42 tests d'interface (étiquette `ui`) ont tous été exécutés (aucun ignoré).";

    @TempDir
    Path temp;

    /** The three packaged applications, with the paths jpackage really gives them. */
    private enum Layout {
        WINDOWS("windows-x64.zip", "Calc/Calc.exe", "Calc/app/Calc.cfg", "Calc/runtime", "\r\n"),
        MACOS("macos-arm64.tar.gz", "Calc.app/Contents/MacOS/Calc", "Calc.app/Contents/app/Calc.cfg",
                "Calc.app/Contents/runtime/Contents/Home", "\n"),
        LINUX("linux-x64.tar.gz", "Calc/bin/Calc", "Calc/lib/app/Calc.cfg", "Calc/lib/runtime", "\n");

        final String suffix;
        final String launcher;
        final String config;
        final String runtime;
        final String lineEnd;

        Layout(String suffix, String launcher, String config, String runtime, String lineEnd) {
            this.suffix = suffix;
            this.launcher = launcher;
            this.config = config;
            this.runtime = runtime;
            this.lineEnd = lineEnd;
        }

        String fileName(String version) {
            return "Calc-" + version + "-" + suffix;
        }

        Map<String, byte[]> tree(String version) {
            Map<String, byte[]> tree = new LinkedHashMap<>();
            tree.put(launcher, bytes("launcher"));
            tree.put(config.replace("Calc.cfg", "calc.jar"), bytes("jar"));
            tree.put(config, bytes(configText(version, lineEnd)));
            tree.put(runtime + "/lib/jvm.cfg", bytes("-server KNOWN\n"));
            tree.put(runtime + "/release", bytes("MODULES=\"java.base\"\n"));
            return tree;
        }

        byte[] archive(Map<String, byte[]> tree) throws IOException {
            if (this == WINDOWS) {
                return zip(tree);
            }
            return tarGz(entries(tree, this == LINUX ? GNU_MAGIC : USTAR_MAGIC));
        }
    }

    /** A release folder before it is written: the checksums are computed last, so that one defect at a time can be tested. */
    private static final class Release {
        final String version;
        final Map<String, byte[]> files = new LinkedHashMap<>();
        UnaryOperator<List<String>> sumsEdit = lines -> lines;

        Release(String version) throws IOException {
            this.version = version;
            for (Layout layout : Layout.values()) {
                files.put(layout.fileName(version), layout.archive(layout.tree(version)));
            }
            files.put(sbomName(version), bytes(String.format(SBOM, version)));
            files.put(reportName(version), bytes(report(version)));
        }

        Release tree(Layout layout, Consumer<Map<String, byte[]>> edit) throws IOException {
            Map<String, byte[]> tree = layout.tree(version);
            edit.accept(tree);
            files.put(layout.fileName(version), layout.archive(tree));
            return this;
        }

        Release file(String name, byte[] content) {
            files.put(name, content);
            return this;
        }

        Release sums(UnaryOperator<List<String>> edit) {
            sumsEdit = edit;
            return this;
        }

        Path writeTo(Path parent) throws Exception {
            Path directory = Files.createTempDirectory(parent, "release");
            List<String> lines = new ArrayList<>();
            for (String name : new TreeSet<>(files.keySet())) {
                Files.write(directory.resolve(name), files.get(name));
                if (name.startsWith("Calc-")) {
                    lines.add(sha256(files.get(name)) + "  " + name);
                }
            }
            Files.write(directory.resolve(SUMS), bytes(String.join("\n", sumsEdit.apply(lines)) + "\n"));
            return directory;
        }
    }

    // --- The public shape of the tool --------------------------------------------------------------------------------------

    @Test
    void theToolIsAPublicFinalClassWithExactlyTheAgreedMethods() throws Exception {
        Class<?> tool = tool();
        assertTrue(Modifier.isPublic(tool.getModifiers()), "public class");
        assertTrue(Modifier.isFinal(tool.getModifiers()), "final class");

        Method problems = problemsMethod();
        assertEquals(List.class, problems.getReturnType());
        assertTrue(List.of(problems.getExceptionTypes()).contains(IOException.class), "problems throws IOException");

        Method evidence = evidenceMethod();
        assertEquals(String.class, evidence.getReturnType());
        assertTrue(List.of(evidence.getExceptionTypes()).contains(IOException.class), "evidence throws IOException");

        Method main = tool.getMethod("main", String[].class);
        assertEquals(void.class, main.getReturnType());
        assertTrue(Modifier.isStatic(main.getModifiers()), "main is static");

        Set<String> publicMethods = new TreeSet<>();
        for (Method method : tool.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())) {
                publicMethods.add(method.getName());
            }
        }
        assertEquals(Set.of("evidence", "main", "problems"), publicMethods);
    }

    @Test
    void theJsonBridgeIsOnePublicMethodThatReturnsATreeOfMapsListsAndStrings() throws Exception {
        Class<?> bridge;
        try {
            bridge = Class.forName("calc.persistence.ReleaseJson");
        } catch (ClassNotFoundException e) {
            fail("calc.persistence.ReleaseJson must exist (src/test/java/calc/persistence/ReleaseJson.java)");
            return;
        }
        assertTrue(Modifier.isPublic(bridge.getModifiers()), "public class");
        List<Method> publicMethods = Arrays.stream(bridge.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .toList();
        assertEquals(1, publicMethods.size(), "one public method: " + publicMethods);
        Method parse = publicMethods.get(0);
        assertTrue(Modifier.isStatic(parse.getModifiers()), "static");
        assertEquals(List.of(String.class), List.of(parse.getParameterTypes()));

        Map<?, ?> tree = assertInstanceOf(Map.class, parse.invoke(null, "{\"a\":[\"b\"]}"));
        assertEquals(List.of("b"), tree.get("a"));
    }

    // --- A complete folder ---------------------------------------------------------------------------------------------------

    @Test
    void aCompleteFolderHasNoProblem() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);

        Set<String> names = new TreeSet<>();
        try (var listing = Files.list(directory)) {
            listing.forEach(path -> names.add(path.getFileName().toString()));
        }
        assertEquals(Set.of("Calc-1.0.0-windows-x64.zip", "Calc-1.0.0-macos-arm64.tar.gz", "Calc-1.0.0-linux-x64.tar.gz",
                "Calc-1.0.0-sbom.cdx.json", "Calc-1.0.0-rapport-conformite.md", "SHA256SUMS.txt"), names);

        assertEquals(List.of(), problems(directory, VERSION));
    }

    @Test
    void theExpectedNamesAndContentsFollowTheVersion() throws Exception {
        Path other = new Release("2.3.4").writeTo(temp);

        assertEquals(List.of(), problems(other, "2.3.4"), "a complete folder of another version");
        assertNames(problems(other, VERSION), "Calc-1.0.0-windows-x64.zip");
    }

    @Test
    void realFilesWithMoreContentThanTheMinimumAreAccepted() throws Exception {
        String sbom = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"urn:uuid:1\",\"version\":1,"
                + "\"metadata\":{\"timestamp\":\"2026-11-07T00:00:00Z\",\"tools\":[{\"name\":\"x\"}],"
                + "\"component\":{\"type\":\"application\",\"name\":\"Calc\",\"version\":\"1.0.0\"}},\"components\":[]}";
        Path directory = new Release(VERSION).file(sbomName(VERSION), bytes(sbom)).writeTo(temp);

        assertEquals(List.of(), problems(directory, VERSION));
    }

    // --- The list of files ---------------------------------------------------------------------------------------------------

    @Test
    void eachMissingFileIsNamed() throws Exception {
        for (String name : allNames(VERSION)) {
            Path directory = new Release(VERSION).writeTo(temp);
            Files.delete(directory.resolve(name));

            assertNames(problems(directory, VERSION), name);
        }
    }

    @Test
    void aSeventhFileIsNamed() throws Exception {
        Path directory = new Release(VERSION).file("notes.txt", bytes("en trop")).writeTo(temp);

        assertNames(problems(directory, VERSION), "notes.txt");
    }

    @Test
    void aSeventhFileListedInTheChecksumsIsStillNamed() throws Exception {
        String extra = "Calc-1.0.0-linux-arm64.tar.gz";
        Path directory = new Release(VERSION).file(extra, bytes("en trop")).writeTo(temp);

        assertNames(problems(directory, VERSION), extra);
    }

    // --- The checksums -------------------------------------------------------------------------------------------------------

    @Test
    void aWrongChecksumNamesTheFileConcerned() throws Exception {
        for (String name : checkedNames(VERSION)) {
            Path directory = new Release(VERSION).sums(replaceLine(name, OTHER_HEX + "  " + name)).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), name);
        }
    }

    @Test
    void aFileChangedAfterTheChecksumsIsNamed() throws Exception {
        for (String name : checkedNames(VERSION)) {
            Release release = new Release(VERSION);
            Path directory = release.writeTo(temp);
            byte[] changed = Arrays.copyOf(release.files.get(name), release.files.get(name).length + 1);
            Files.write(directory.resolve(name), changed);

            assertNames(problems(directory, VERSION), name);
        }
    }

    @Test
    void aLineMissingFromTheChecksumsNamesTheFileConcerned() throws Exception {
        for (String name : checkedNames(VERSION)) {
            Path directory = new Release(VERSION).sums(removeLine(name)).writeTo(temp);

            assertNames(problems(directory, VERSION), name);
        }
    }

    @Test
    void aLineOfInvalidFormatNamesTheFileConcerned() throws Exception {
        for (String name : checkedNames(VERSION)) {
            String goodHex = sha256(new Release(VERSION).files.get(name));
            List<String> invalid = List.of(
                    goodHex.substring(2) + "  " + name,
                    goodHex + "00  " + name,
                    "zz" + goodHex.substring(2) + "  " + name,
                    goodHex + " " + name,
                    goodHex + name,
                    name);
            for (String line : invalid) {
                Path directory = new Release(VERSION).sums(replaceLine(name, line)).writeTo(temp);

                assertNames(problems(directory, VERSION), name);
            }
        }
    }

    @Test
    void aLineThatNamesNoFileIsBlamedOnTheChecksumsFile() throws Exception {
        for (String line : List.of("n'importe quoi", "abc", OTHER_HEX)) {
            Path directory = new Release(VERSION).sums(lines -> append(lines, line)).writeTo(temp);

            assertNames(problems(directory, VERSION), SUMS);
        }
    }

    // --- The SBOM ------------------------------------------------------------------------------------------------------------

    @Test
    void anUnusableSbomIsNamed() throws Exception {
        String component = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{\"component\":%s}}";
        List<String> invalid = List.of(
                "",
                "pas du JSON",
                "{\"bomFormat\":\"CycloneDX\",",
                "[]",
                "\"CycloneDX\"",
                "{\"specVersion\":\"1.6\",\"metadata\":{\"component\":{\"version\":\"1.0.0\"}}}",
                "{\"bomFormat\":\"SPDX\",\"specVersion\":\"1.6\",\"metadata\":{\"component\":{\"version\":\"1.0.0\"}}}",
                "{\"bomFormat\":1,\"specVersion\":\"1.6\",\"metadata\":{\"component\":{\"version\":\"1.0.0\"}}}",
                "{\"bomFormat\":\"CycloneDX\",\"metadata\":{\"component\":{\"version\":\"1.0.0\"}}}",
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}",
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":\"texte\"}",
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"metadata\":{}}",
                String.format(component, "[]"),
                String.format(component, "{}"),
                String.format(component, "{\"version\":1}"),
                String.format(component, "{\"version\":\"1.0.1\"}"),
                String.format(component, "{\"version\":\"1.0.01\"}"));
        for (String sbom : invalid) {
            Path directory = new Release(VERSION).file(sbomName(VERSION), bytes(sbom)).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), sbomName(VERSION), "SBOM: " + sbom);
        }
    }

    // --- The conformity report -----------------------------------------------------------------------------------------------

    @Test
    void aReportWithoutTheExactVerdictIsNamed() throws Exception {
        assertReportRefused(report(VERSION).replace(VERDICT + " — ", ""));
        assertReportRefused(report(VERSION).replace(VERDICT, "Verdict : CONFORME"));
        assertReportRefused(report(VERSION).replace(VERDICT, "**Verdict : conforme**"));
    }

    @Test
    void aNonConformingReportIsRefused() throws Exception {
        String nonConforming = report(VERSION).replace(VERDICT, "**Verdict : NON CONFORME**");
        assertReportRefused(nonConforming);
        assertReportRefused(nonConforming + "\nCitation de l'ancien rapport : " + VERDICT + "\n");
    }

    @Test
    void aReportWithoutTheExactVersionLineIsNamed() throws Exception {
        assertReportRefused(report(VERSION).replace(VERSION_LINE + "\n", ""));
        assertReportRefused(report(VERSION).replace(VERSION_LINE, "- Version : `1.0.1`"));
        assertReportRefused(report(VERSION).replace(VERSION_LINE, "- Version : `1.0.01`"));
        assertReportRefused(report(VERSION).replace(VERSION_LINE, "- Version : 1.0.0"));
        assertReportRefused(report(VERSION).replace(VERSION_LINE, "Note : " + VERSION_LINE));
        assertReportRefused(report(VERSION).replace(VERSION_LINE, VERSION_LINE + " (essai)"));
    }

    @Test
    void aReportWithoutTheUiSentenceIsNamed() throws Exception {
        assertReportRefused(report(VERSION).replace(UI_SENTENCE + "\n", ""));
        assertReportRefused(report(VERSION).replace(UI_SENTENCE, "**Attention : 3 test(s) d'interface ont été IGNORÉS "
                + "(pas d'affichage sur cette machine).** L'interface n'a pas été exécutée pour ce rapport."));
        assertReportRefused(report(VERSION).replace(UI_SENTENCE,
                "Les 42 tests d'interface (etiquette `ui`) ont tous ete executes (aucun ignore)."));
    }

    // --- The launchers -------------------------------------------------------------------------------------------------------

    @Test
    void aMissingLauncherNamesTheArchive() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION).tree(layout, tree -> tree.remove(layout.launcher)).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void aLauncherInAnotherFolderDoesNotCount() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION).tree(layout, tree -> {
                tree.put("Autre/" + layout.launcher, tree.remove(layout.launcher));
            }).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void eachArchiveMustHoldItsOwnLauncher() throws Exception {
        String macos = Layout.MACOS.fileName(VERSION);
        Path directory = new Release(VERSION).file(macos, Layout.MACOS.archive(Layout.LINUX.tree(VERSION))).writeTo(temp);

        assertBlamesOnly(problems(directory, VERSION), macos, "a Linux image in the macOS archive");
    }

    @Test
    void aDamagedArchiveIsNamedInsteadOfStoppingTheCheck() throws Exception {
        for (Layout layout : Layout.values()) {
            String name = layout.fileName(VERSION);
            byte[] archive = new Release(VERSION).files.get(name);

            Path garbage = new Release(VERSION).file(name, bytes("ceci n'est pas une archive")).writeTo(temp);
            assertBlamesOnly(problems(garbage, VERSION), name, layout + " garbage");

            // Half of this small zip would only lose its central directory, which a streaming reader never reads: cut the launcher.
            int kept = layout == Layout.WINDOWS ? 20 : archive.length / 2;
            Path truncated = new Release(VERSION).file(name, Arrays.copyOf(archive, kept)).writeTo(temp);
            assertBlamesOnly(problems(truncated, VERSION), name, layout + " truncated");
        }
    }

    // --- Calc.cfg ------------------------------------------------------------------------------------------------------------

    @Test
    void anArchiveWithoutCalcCfgIsNamed() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION).tree(layout, tree -> tree.remove(layout.config)).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void aCalcCfgOfAnotherVersionNamesTheArchive() throws Exception {
        for (Layout layout : Layout.values()) {
            for (String other : List.of("1.0.1", "1.0.01", "1.0.0-rc1", "11.0.0", "0.0.0-local")) {
                Path directory = new Release(VERSION)
                        .tree(layout, tree -> tree.put(layout.config, bytes(configText(other, layout.lineEnd))))
                        .writeTo(temp);

                assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout + " " + other);
            }
        }
    }

    @Test
    void aCalcCfgWithoutTheVersionOptionNamesTheArchive() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION)
                    .tree(layout, tree -> tree.put(layout.config, bytes("[Application]\napp.mainclass=calc.Main\n")))
                    .writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void aCalcCfgInsideTheRuntimeFolderDoesNotCount() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION).tree(layout, tree -> {
                tree.put(layout.runtime + "/conf/Calc.cfg", tree.remove(layout.config));
            }).writeTo(temp);

            assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void aCalcCfgOfAnotherVersionInsideTheRuntimeFolderIsIgnored() throws Exception {
        for (Layout layout : Layout.values()) {
            Path directory = new Release(VERSION)
                    .tree(layout, tree -> tree.put(layout.runtime + "/conf/Calc.cfg", bytes(configText("9.9.9", "\n"))))
                    .writeTo(temp);

            assertEquals(List.of(), problems(directory, VERSION), layout.name());
        }
    }

    @Test
    void onlyAFileNamedExactlyCalcCfgCounts() throws Exception {
        for (Layout layout : Layout.values()) {
            for (String other : List.of("jvm.cfg", "MonCalc.cfg", "Calc.cfg.bak", "calc.cfg")) {
                Path directory = new Release(VERSION).tree(layout, tree -> {
                    byte[] content = tree.remove(layout.config);
                    tree.put(layout.config.replace("Calc.cfg", other), content);
                }).writeTo(temp);

                assertBlamesOnly(problems(directory, VERSION), layout.fileName(VERSION), layout + " " + other);
            }
        }
    }

    @Test
    void calcCfgIsReadWithEitherLineEnding() throws Exception {
        for (Layout layout : Layout.values()) {
            for (String lineEnd : List.of("\n", "\r\n")) {
                Path directory = new Release(VERSION)
                        .tree(layout, tree -> tree.put(layout.config, bytes(configText(VERSION, lineEnd))))
                        .writeTo(temp);

                assertEquals(List.of(), problems(directory, VERSION), layout.name());
            }
        }
    }

    // --- The tar reader ------------------------------------------------------------------------------------------------------

    @Test
    void tarPathsMayStartWithDotSlash() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            Path directory = withTar(layout, dotSlash(entries(layout.tree(VERSION), USTAR_MAGIC)));

            assertEquals(List.of(), problems(directory, VERSION), layout.name());
        }
    }

    @Test
    void theRulesStillApplyToTarPathsStartingWithDotSlash() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            Map<String, byte[]> withoutLauncher = layout.tree(VERSION);
            withoutLauncher.remove(layout.launcher);
            assertBlamesOnly(problems(withTar(layout, dotSlash(entries(withoutLauncher, USTAR_MAGIC))), VERSION),
                    layout.fileName(VERSION), layout + " no launcher");

            Map<String, byte[]> onlyInRuntime = layout.tree(VERSION);
            onlyInRuntime.put(layout.runtime + "/conf/Calc.cfg", onlyInRuntime.remove(layout.config));
            assertBlamesOnly(problems(withTar(layout, dotSlash(entries(onlyInRuntime, USTAR_MAGIC))), VERSION),
                    layout.fileName(VERSION), layout + " Calc.cfg in the runtime");
        }
    }

    @Test
    void theUstarPrefixIsPartOfThePath() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            List<TarEntry> entries = new ArrayList<>();
            for (Map.Entry<String, byte[]> file : layout.tree(VERSION).entrySet()) {
                int slash = file.getKey().lastIndexOf('/');
                entries.add(new TarEntry(file.getKey().substring(slash + 1), file.getKey().substring(0, slash), '0',
                        file.getValue(), USTAR_MAGIC));
            }
            assertEquals(List.of(), problems(withTar(layout, entries), VERSION), layout.name());
        }
    }

    @Test
    void aFileWhosePrefixIsTheRuntimeFolderDoesNotCount() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            Map<String, byte[]> tree = layout.tree(VERSION);
            byte[] config = tree.remove(layout.config);
            List<TarEntry> entries = new ArrayList<>(entries(tree, USTAR_MAGIC));
            entries.add(new TarEntry("Calc.cfg", layout.runtime + "/conf", '0', config, USTAR_MAGIC));

            assertBlamesOnly(problems(withTar(layout, entries), VERSION), layout.fileName(VERSION), layout.name());
        }
    }

    @Test
    void gnuLongNameEntriesAreIgnoredWithoutError() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            // The data of the long name takes two blocks: the reader must skip them to find the launcher and Calc.cfg after it.
            String longName = "Calc/lib/runtime/legal/" + "x".repeat(600) + "\0";
            String truncated = longName.substring(0, 100);
            List<TarEntry> entries = new ArrayList<>();
            entries.add(new TarEntry("././@LongLink", "", 'L', bytes(longName), GNU_MAGIC));
            entries.add(new TarEntry(truncated, "", '0', bytes("legal"), GNU_MAGIC));
            entries.addAll(entries(layout.tree(VERSION), GNU_MAGIC));

            assertEquals(List.of(), problems(withTar(layout, entries), VERSION), layout.name());
        }
    }

    @Test
    void theOctalSizeIsUsedToFindTheNextEntry() throws Exception {
        for (Layout layout : List.of(Layout.MACOS, Layout.LINUX)) {
            // 1024 is 2000 in octal and fills its blocks exactly; 513 needs a padding of 511 bytes.
            for (int size : List.of(1, 511, 512, 513, 1024, 70_000)) {
                List<TarEntry> entries = new ArrayList<>();
                entries.add(new TarEntry("Calc/lib/gros.bin", "", '0', new byte[size], USTAR_MAGIC));
                entries.addAll(entries(layout.tree(VERSION), USTAR_MAGIC));

                assertEquals(List.of(), problems(withTar(layout, entries), VERSION), layout + " size " + size);
            }
        }
    }

    @Test
    void theCheckNeverStartsAnExternalProcess() throws Exception {
        assertTrue(Files.exists(TOOL_SOURCE), TOOL_SOURCE + " must exist");
        String source = Files.readString(TOOL_SOURCE, StandardCharsets.UTF_8);
        for (String forbidden : List.of("ProcessBuilder", "Runtime.getRuntime", ".exec(")) {
            assertFalse(source.contains(forbidden), "The check reads the archives in Java, without " + forbidden);
        }
    }

    // --- The evidence --------------------------------------------------------------------------------------------------------

    @Test
    void theEvidenceOfASuccessfulTrialFollowsTheSpecification() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);

        Map<?, ?> evidence = evidence(directory, TAG, RUN, COMMIT, true, true, true);

        assertEquals(Set.of("result", "version", "tag", "runId", "commit", "prerelease", "notesLinkInstallation",
                "windowsStart", "files", "problems"), evidence.keySet());
        assertEquals("ok", evidence.get("result"));
        assertEquals(VERSION, evidence.get("version"));
        assertEquals(TAG, evidence.get("tag"));
        assertEquals(RUN, evidence.get("runId"));
        assertEquals(COMMIT, evidence.get("commit"));
        assertEquals(Boolean.TRUE, evidence.get("prerelease"));
        assertEquals(Boolean.TRUE, evidence.get("notesLinkInstallation"));
        assertEquals("ok", evidence.get("windowsStart"));
        assertEquals(List.of(), evidence.get("problems"));
    }

    @Test
    void theEvidenceListsTheSixFilesWithTheirRecomputedChecksums() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);

        Map<?, ?> files = assertInstanceOf(Map.class, evidence(directory, TAG, RUN, COMMIT, true, true, true).get("files"));

        assertEquals(new LinkedHashSet<>(allNames(VERSION)), files.keySet());
        assertEquals(6, files.size());
        for (String name : allNames(VERSION)) {
            assertEquals(sha256(Files.readAllBytes(directory.resolve(name))), files.get(name), name);
        }
    }

    @Test
    void eachFailedCheckMakesTheEvidenceFail() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);

        for (int flags = 0; flags < 7; flags++) {
            boolean prerelease = (flags & 1) != 0;
            boolean notes = (flags & 2) != 0;
            boolean windows = (flags & 4) != 0;
            String label = "prerelease=" + prerelease + " notes=" + notes + " windows=" + windows;

            Map<?, ?> evidence = evidence(directory, TAG, RUN, COMMIT, prerelease, notes, windows);

            assertEquals("failed", evidence.get("result"), label);
            assertEquals(prerelease, evidence.get("prerelease"), label);
            assertEquals(notes, evidence.get("notesLinkInstallation"), label);
            assertEquals(windows ? "ok" : "failed", evidence.get("windowsStart"), label);
            assertEquals(List.of(), evidence.get("problems"), label);
        }
    }

    @Test
    void problemsMakeTheEvidenceFailAndAreCopiedIntoIt() throws Exception {
        Path directory = new Release(VERSION).sums(removeLine(sbomName(VERSION))).writeTo(temp);
        List<String> problems = problems(directory, VERSION);
        assertNames(problems, sbomName(VERSION));

        Map<?, ?> evidence = evidence(directory, TAG, RUN, COMMIT, true, true, true);

        assertEquals("failed", evidence.get("result"));
        assertEquals("ok", evidence.get("windowsStart"), "The application started: only the files are at fault.");
        assertEquals(problems, evidence.get("problems"));
    }

    @Test
    void theEvidenceOfAnIncompleteFolderIsStillWritten() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);
        Files.delete(directory.resolve(Layout.LINUX.fileName(VERSION)));

        Map<?, ?> evidence = evidence(directory, TAG, RUN, COMMIT, true, true, true);

        assertEquals("failed", evidence.get("result"));
        assertNames(castToStrings(evidence.get("problems")), Layout.LINUX.fileName(VERSION));
    }

    @Test
    void theEvidenceStaysValidJsonWhateverItQuotes() throws Exception {
        Path directory = new Release(VERSION)
                .sums(lines -> append(lines, "ligne \"citée\" avec \\ et\ttabulation"))
                .writeTo(temp);
        String commit = "a\"b\\c";

        Map<?, ?> evidence = evidence(directory, TAG, RUN, commit, true, true, true);

        assertEquals(commit, evidence.get("commit"));
        assertEquals("failed", evidence.get("result"));
        assertNames(castToStrings(evidence.get("problems")), SUMS);
    }

    // --- The command line ----------------------------------------------------------------------------------------------------

    @Test
    void mainWritesTheEvidenceAndSucceedsForAGoodRelease() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);
        Path file = temp.resolve("preuve-ok.json");

        Finished finished = runMain(directory, "true", "true", "true", file);

        assertEquals(0, finished.exitCode(), finished.output());
        assertEquals(evidence(directory, TAG, RUN, COMMIT, true, true, true), parseJson(Files.readString(file)));
    }

    @Test
    void mainNeedsNoExternalProgramToReadTheArchives() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);
        Path file = temp.resolve("preuve-sans-path.json");

        Finished finished = runMain(Map.of("PATH", ""), directory, "true", "true", "true", file);

        assertEquals(0, finished.exitCode(), finished.output());
        assertEquals("ok", ((Map<?, ?>) parseJson(Files.readString(file))).get("result"));
    }

    @Test
    void mainPrintsTheProblemsWritesTheEvidenceAndFailsForABadRelease() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);
        Files.delete(directory.resolve(reportName(VERSION)));
        Path file = temp.resolve("preuve-problemes.json");

        Finished finished = runMain(directory, "true", "true", "true", file);

        assertEquals(1, finished.exitCode(), finished.output());
        assertTrue(finished.output().contains(reportName(VERSION)), "The problem is printed: " + finished.output());
        Map<?, ?> evidence = (Map<?, ?>) parseJson(Files.readString(file));
        assertEquals("failed", evidence.get("result"));
        assertNames(castToStrings(evidence.get("problems")), reportName(VERSION));
    }

    @Test
    void mainReadsTheThreeChecksInTheAgreedOrder() throws Exception {
        Path directory = new Release(VERSION).writeTo(temp);
        List<List<String>> cases = List.of(
                List.of("false", "true", "true"),
                List.of("true", "false", "true"),
                List.of("true", "true", "false"));
        for (List<String> flags : cases) {
            Path file = temp.resolve("preuve-" + String.join("-", flags) + ".json");

            Finished finished = runMain(directory, flags.get(0), flags.get(1), flags.get(2), file);

            assertEquals(1, finished.exitCode(), flags + " " + finished.output());
            Map<?, ?> evidence = (Map<?, ?>) parseJson(Files.readString(file));
            assertEquals("failed", evidence.get("result"), flags.toString());
            assertEquals(Boolean.valueOf(flags.get(0)), evidence.get("prerelease"), flags.toString());
            assertEquals(Boolean.valueOf(flags.get(1)), evidence.get("notesLinkInstallation"), flags.toString());
            assertEquals(flags.get(2).equals("true") ? "ok" : "failed", evidence.get("windowsStart"), flags.toString());
        }
    }

    // --- The script, the Gradle task and the documentation -------------------------------------------------------------------

    @Test
    void theScriptExistsWithoutAnyCarriageReturn() throws Exception {
        assertTrue(Files.isRegularFile(SCRIPT), SCRIPT + " must exist");
        byte[] content = Files.readAllBytes(SCRIPT);
        assertTrue(content.length > 0, "not empty");
        for (byte value : content) {
            assertNotEquals((byte) '\r', value, "No CR in a bash script.");
        }
    }

    @Test
    void theScriptDownloadsReadsStartsAndChecks() throws Exception {
        String script = scriptText();
        for (String expected : List.of("gh release download", "--dir", "gh release view", "isPrerelease,body",
                "docs/INSTALLATION.md", "gh run view", "headSha", "smoke-test.sh", "verifyReleaseFiles",
                "gradlew.bat", "./gradlew", "test-v")) {
            assertTrue(script.contains(expected), SCRIPT + " must contain: " + expected);
        }
        assertTrue(script.startsWith("#!/usr/bin/env bash"), "A bash script, like smoke-test.sh.");
    }

    @Test
    void theScriptNeverDeletesAReleaseOrATag() throws Exception {
        String script = scriptText();
        for (String forbidden : List.of("gh release delete", "--cleanup-tag", "git tag", "git push", "DELETE")) {
            assertFalse(script.contains(forbidden), SCRIPT + " must not contain: " + forbidden);
        }
    }

    @Test
    void theGradleTaskRunsTheToolOnTheTestClasspathWithItsProperties() throws Exception {
        String build = Files.readString(BUILD_SCRIPT, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = build.indexOf("tasks.register<JavaExec>(\"verifyReleaseFiles\")");
        assertTrue(start >= 0, "build.gradle.kts must register the JavaExec task verifyReleaseFiles");
        int end = build.indexOf("\n}\n", start);
        String task = end < 0 ? build.substring(start) : build.substring(start, end);

        assertTrue(task.contains("sourceSets[\"test\"].runtimeClasspath"), "On the classpath of the tests:\n" + task);
        assertTrue(task.contains(TOOL), "Runs the tool:\n" + task);
        assertTrue(task.contains("project.version"), "Passes the version of the project:\n" + task);
        for (String property : List.of("releaseDir", "releaseTag", "releaseRun", "releaseCommit", "releasePrerelease",
                "releaseNotesLink", "windowsStarted", "evidenceFile")) {
            assertTrue(task.contains("\"" + property + "\""), "Reads -P" + property + ":\n" + task);
        }
    }

    @Test
    void theDocumentationOfATrialGivesTheCheckCommand() throws Exception {
        String document = Files.readString(RELEASE_DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = document.indexOf("### Faire un essai sans tag");
        assertTrue(start >= 0, "The section exists.");
        int end = document.indexOf("\n### ", start + 1);
        String section = end < 0 ? document.substring(start) : document.substring(start, end);

        assertTrue(section.contains("bash tools/release/verify-release.sh <tag> <run> <fichier>"),
                "The section gives the command:\n" + section);
    }

    // --- Calling the tool ----------------------------------------------------------------------------------------------------

    private static Class<?> tool() {
        try {
            return Class.forName(TOOL);
        } catch (ClassNotFoundException e) {
            return fail(TOOL + " must exist (" + TOOL_SOURCE + ")");
        }
    }

    private static Method problemsMethod() {
        return method("problems", Path.class, String.class);
    }

    private static Method evidenceMethod() {
        return method("evidence", Path.class, String.class, String.class, String.class, String.class, boolean.class,
                boolean.class, boolean.class);
    }

    private static Method method(String name, Class<?>... types) {
        try {
            Method method = tool().getMethod(name, types);
            assertTrue(Modifier.isStatic(method.getModifiers()), name + " must be static");
            return method;
        } catch (NoSuchMethodException e) {
            return fail(TOOL + " must declare public static " + name + ": " + e.getMessage());
        }
    }

    private static Object call(Method method, Object... arguments) throws Exception {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw e;
        }
    }

    private static List<String> problems(Path directory, String version) throws Exception {
        return castToStrings(call(problemsMethod(), directory, version));
    }

    private static Map<?, ?> evidence(Path directory, String tag, String run, String commit, boolean prerelease, boolean notes,
            boolean windows) throws Exception {
        String json = (String) call(evidenceMethod(), directory, VERSION, tag, run, commit, prerelease, notes, windows);
        return assertInstanceOf(Map.class, parseJson(json));
    }

    private static List<String> castToStrings(Object value) {
        List<?> list = assertInstanceOf(List.class, value);
        List<String> strings = new ArrayList<>();
        for (Object element : list) {
            strings.add(assertInstanceOf(String.class, element));
        }
        return strings;
    }

    /** The parser of the application, private to its package: it reads the JSON of the tool, whatever the tool does with it. */
    private static Object parseJson(String json) throws Exception {
        Method parse = Class.forName("calc.persistence.JsonParser").getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        return call(parse, json);
    }

    private record Finished(int exitCode, String output) {
    }

    private static Finished runMain(Path directory, String prerelease, String notes, String windows, Path file) throws Exception {
        return runMain(Map.of(), directory, prerelease, notes, windows, file);
    }

    private static Finished runMain(Map<String, String> environment, Path directory, String prerelease, String notes,
            String windows, Path file) throws Exception {
        tool();
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), TOOL, directory.toString(), VERSION, TAG, RUN, COMMIT, prerelease,
                notes, windows, file.toString()).redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "The tool ended.");
        return new Finished(process.exitValue(), output);
    }

    // --- Assertions ----------------------------------------------------------------------------------------------------------

    private static void assertNames(List<String> problems, String name) {
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(name)),
                "A problem must name " + name + ": " + problems);
    }

    private static void assertBlamesOnly(List<String> problems, String name) {
        assertBlamesOnly(problems, name, name);
    }

    /** The file at fault is named, and the other files of the release, which are good, are not accused. */
    private static void assertBlamesOnly(List<String> problems, String name, String label) {
        assertTrue(problems.stream().anyMatch(problem -> problem.contains(name)),
                label + ": a problem must name " + name + ": " + problems);
        for (String other : checkedNames(VERSION)) {
            if (!other.equals(name)) {
                assertFalse(problems.stream().anyMatch(problem -> problem.contains(other)),
                        label + ": " + other + " is good and must not be named: " + problems);
            }
        }
    }

    private void assertReportRefused(String report) throws Exception {
        assertNotEquals(report(VERSION), report, "The test must change the report.");
        Path directory = new Release(VERSION).file(reportName(VERSION), bytes(report)).writeTo(temp);

        assertBlamesOnly(problems(directory, VERSION), reportName(VERSION), report);
    }

    private static String scriptText() throws IOException {
        assertTrue(Files.isRegularFile(SCRIPT), SCRIPT + " must exist");
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    // --- The files of a release ----------------------------------------------------------------------------------------------

    private static String sbomName(String version) {
        return "Calc-" + version + "-sbom.cdx.json";
    }

    private static String reportName(String version) {
        return "Calc-" + version + "-rapport-conformite.md";
    }

    /** The five files that SHA256SUMS.txt lists. */
    private static List<String> checkedNames(String version) {
        return List.of(Layout.WINDOWS.fileName(version), Layout.MACOS.fileName(version), Layout.LINUX.fileName(version),
                sbomName(version), reportName(version));
    }

    private static List<String> allNames(String version) {
        return append(checkedNames(version), SUMS);
    }

    private static List<String> append(List<String> list, String element) {
        List<String> copy = new ArrayList<>(list);
        copy.add(element);
        return copy;
    }

    private static UnaryOperator<List<String>> removeLine(String name) {
        return lines -> lines.stream().filter(line -> !line.endsWith("  " + name)).toList();
    }

    private static UnaryOperator<List<String>> replaceLine(String name, String replacement) {
        return lines -> lines.stream().map(line -> line.endsWith("  " + name) ? replacement : line).toList();
    }

    /** The lines of gradle/conformity.gradle.kts, for a conforming report. */
    private static String report(String version) {
        return "# Rapport de conformité de Calc\n\n"
                + VERDICT + " — ce rapport est généré par `gradlew conformityReport` à partir des résultats réels des tests, "
                + "de JaCoCo et de PIT ; il échoue si une donnée manque.\n\n"
                + "## Environnement\n\n"
                + "- Version : `" + version + "`\n"
                + "- Commit : `" + COMMIT + "`\n"
                + "- Date (UTC) : 2026-11-07T00:00:00Z\n\n"
                + "## Tests\n\n"
                + "Total : **100** tests dans 10 classes — 100 réussis, 0 échoués, 0 ignorés.\n\n"
                + UI_SENTENCE + "\n\n"
                + "Essais de propriétés (jqwik) : 1000.\n";
    }

    /** The file jpackage writes next to the application. */
    private static String configText(String version, String lineEnd) {
        return "[Application]" + lineEnd
                + "app.classpath=$APPDIR/calc.jar" + lineEnd
                + "app.mainclass=calc.Main" + lineEnd
                + lineEnd
                + "[JavaOptions]" + lineEnd
                + "java-options=-Djpackage.app-version=" + version + lineEnd;
    }

    private Path withTar(Layout layout, List<TarEntry> entries) throws Exception {
        return new Release(VERSION).file(layout.fileName(VERSION), tarGz(entries)).writeTo(temp);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static byte[] zip(Map<String, byte[]> tree) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            for (Map.Entry<String, byte[]> file : tree.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue());
                zip.closeEntry();
            }
        }
        return archive.toByteArray();
    }

    // --- A small tar writer --------------------------------------------------------------------------------------------------

    private static final int BLOCK = 512;
    /** What bsdtar writes (macOS). */
    private static final String USTAR_MAGIC = "ustar\00000";
    /** What GNU tar writes (Linux). */
    private static final String GNU_MAGIC = "ustar  \0";

    private record TarEntry(String name, String prefix, char type, byte[] content, String magic) {
    }

    /** The directories first, then the files, as tar lists an image. */
    private static List<TarEntry> entries(Map<String, byte[]> tree, String magic) {
        Set<String> directories = new LinkedHashSet<>();
        for (String path : tree.keySet()) {
            for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
                directories.add(path.substring(0, slash + 1));
            }
        }
        List<TarEntry> entries = new ArrayList<>();
        for (String directory : directories) {
            entries.add(new TarEntry(directory, "", '5', new byte[0], magic));
        }
        for (Map.Entry<String, byte[]> file : tree.entrySet()) {
            entries.add(new TarEntry(file.getKey(), "", '0', file.getValue(), magic));
        }
        return entries;
    }

    private static List<TarEntry> dotSlash(List<TarEntry> entries) {
        return entries.stream()
                .map(entry -> new TarEntry("./" + entry.name(), entry.prefix(), entry.type(), entry.content(), entry.magic()))
                .toList();
    }

    private static byte[] tarGz(List<TarEntry> entries) throws IOException {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(archive)) {
            for (TarEntry entry : entries) {
                gzip.write(header(entry));
                gzip.write(entry.content());
                int rest = entry.content().length % BLOCK;
                if (rest != 0) {
                    gzip.write(new byte[BLOCK - rest]);
                }
            }
            gzip.write(new byte[2 * BLOCK]);
        }
        return archive.toByteArray();
    }

    private static byte[] header(TarEntry entry) {
        byte[] header = new byte[BLOCK];
        put(header, 0, entry.name());
        put(header, 100, "0000755\0");
        put(header, 108, "0000000\0");
        put(header, 116, "0000000\0");
        put(header, 124, String.format("%011o\0", entry.content().length));
        put(header, 136, "15000000000\0");
        put(header, 148, "        ");
        header[156] = (byte) entry.type();
        put(header, 257, entry.magic());
        put(header, 345, entry.prefix());
        int checksum = 0;
        for (byte value : header) {
            checksum += value & 0xFF;
        }
        put(header, 148, String.format("%06o\0 ", checksum));
        return header;
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] value = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, header, offset, value.length);
    }
}
