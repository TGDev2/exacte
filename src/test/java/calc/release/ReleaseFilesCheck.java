package calc.release;

import calc.persistence.JsonException;
import calc.persistence.ReleaseJson;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks the six files of a release in a folder (names, checksums, SBOM, conformity report, content of the three archives) and
 * writes the evidence of a trial, in the format of {@code docs/essais/*.json}.
 *
 * <p>Run by {@code gradlew verifyReleaseFiles}, itself run by {@code tools/release/verify-release.sh}. It lives in the test source
 * set so that it is tested on every system without being shipped with the application (ADR 0012, amendment). The archives are read
 * in Java only: under Windows, the {@code tar} command may take {@code C:} for a remote host.
 */
public final class ReleaseFilesCheck {
    private static final String SUMS = "SHA256SUMS.txt";
    /** What {@code sha256sum} writes: the lowercase digest, two spaces, the name. */
    private static final Pattern SUM_LINE = Pattern.compile("([0-9a-f]{64})  (.+)");

    private static final String VERDICT = "**Verdict : CONFORME**";
    private static final String BAD_VERDICT = "**Verdict : NON CONFORME**";
    private static final String UI_SENTENCE = "tests d'interface (étiquette `ui`) ont tous été exécutés";

    private static final String CONFIG = "Calc.cfg";
    private static final String RUNTIME_FOLDER = "/runtime/";

    private static final int TAR_BLOCK = 512;
    private static final int TAR_NAME = 0;
    private static final int TAR_NAME_LENGTH = 100;
    private static final int TAR_SIZE = 124;
    private static final int TAR_SIZE_LENGTH = 12;
    private static final int TAR_CHECKSUM = 148;
    private static final int TAR_CHECKSUM_LENGTH = 8;
    private static final int TAR_TYPE = 156;
    private static final int TAR_MAGIC = 257;
    private static final int TAR_PREFIX = 345;
    private static final int TAR_PREFIX_LENGTH = 155;
    /** A POSIX header, the only one whose prefix field holds a path; GNU tar writes {@code ustar}, a space, and uses it otherwise. */
    private static final byte[] USTAR_MAGIC = "ustar\0".getBytes(StandardCharsets.US_ASCII);

    private record Archive(String name, String launcher) {
    }

    private record Contents(Set<String> files, List<String> configs) {
        Contents() {
            this(new HashSet<>(), new ArrayList<>());
        }
    }

    private ReleaseFilesCheck() {
    }

    /**
     * @param directory the folder of the downloaded files
     * @param version   the exact version number, for example {@code 1.0.0}
     * @return one message per problem, each naming the file at fault; empty for a complete release
     */
    public static List<String> problems(Path directory, String version) throws IOException {
        List<String> problems = new ArrayList<>();
        checkFileList(directory, version, problems);
        if (Files.isRegularFile(directory.resolve(SUMS))) {
            checkSums(directory, version, problems);
        }
        for (Archive archive : archives(version)) {
            if (Files.isRegularFile(directory.resolve(archive.name()))) {
                checkArchive(directory.resolve(archive.name()), archive, version, problems);
            }
        }
        if (Files.isRegularFile(directory.resolve(sbomName(version)))) {
            checkSbom(directory.resolve(sbomName(version)), sbomName(version), version, problems);
        }
        if (Files.isRegularFile(directory.resolve(reportName(version)))) {
            checkReport(directory.resolve(reportName(version)), reportName(version), version, problems);
        }
        return List.copyOf(problems);
    }

    /**
     * @param prerelease            whether GitHub shows the status expected for this tag
     * @param notesLinkInstallation whether the notes of the release link the installation guide
     * @param windowsStarted        whether the Windows application started
     * @return the JSON evidence of the trial, with {@code result} {@code ok} only if all of this holds and there is no problem
     */
    public static String evidence(Path directory, String version, String tag, String runId, String commit, boolean prerelease,
            boolean notesLinkInstallation, boolean windowsStarted) throws IOException {
        return render(directory, version, tag, runId, commit, prerelease, notesLinkInstallation, windowsStarted,
                problems(directory, version));
    }

    /**
     * Arguments: folder, version, tag, run, commit, the three checks ({@code true} or {@code false}) in the order of
     * {@link #evidence}, evidence file. Writes the evidence, prints what failed and exits with 1 if the result is {@code failed}.
     */
    public static void main(String[] args) throws IOException {
        if (args.length != 9) {
            throw new IllegalArgumentException("expected 9 arguments (folder, version, tag, run, commit, prerelease, "
                    + "notes link, Windows start, evidence file), got " + args.length);
        }
        Path directory = Path.of(args[0]);
        boolean prerelease = Boolean.parseBoolean(args[5]);
        boolean notes = Boolean.parseBoolean(args[6]);
        boolean windows = Boolean.parseBoolean(args[7]);
        List<String> problems = problems(directory, args[1]);
        String evidence = render(directory, args[1], args[2], args[3], args[4], prerelease, notes, windows, problems);

        Path file = Path.of(args[8]);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, evidence, StandardCharsets.UTF_8);

        problems.forEach(System.out::println);
        if (!prerelease) {
            System.out.println("The status of the release on GitHub is not the one expected for this tag.");
        }
        if (!notes) {
            System.out.println("The notes of the release do not link docs/INSTALLATION.md.");
        }
        if (!windows) {
            System.out.println("The Windows application did not start.");
        }
        boolean passed = passed(problems, prerelease, notes, windows);
        System.out.println("Evidence written to " + file + ": " + result(passed));
        if (!passed) {
            System.exit(1);
        }
    }

    // --- The names of the files ----------------------------------------------------------------------------------------------

    private static List<Archive> archives(String version) {
        return List.of(
                new Archive("Calc-" + version + "-windows-x64.zip", "Calc/Calc.exe"),
                new Archive("Calc-" + version + "-macos-arm64.tar.gz", "Calc.app/Contents/MacOS/Calc"),
                new Archive("Calc-" + version + "-linux-x64.tar.gz", "Calc/bin/Calc"));
    }

    private static String sbomName(String version) {
        return "Calc-" + version + "-sbom.cdx.json";
    }

    private static String reportName(String version) {
        return "Calc-" + version + "-rapport-conformite.md";
    }

    /** The five files that SHA256SUMS.txt lists. */
    private static List<String> summedNames(String version) {
        List<String> names = new ArrayList<>();
        archives(version).forEach(archive -> names.add(archive.name()));
        names.add(sbomName(version));
        names.add(reportName(version));
        return names;
    }

    private static List<String> allNames(String version) {
        List<String> names = summedNames(version);
        names.add(SUMS);
        return names;
    }

    // --- The checks ----------------------------------------------------------------------------------------------------------

    private static void checkFileList(Path directory, String version, List<String> problems) throws IOException {
        List<String> expected = allNames(version);
        for (String name : expected) {
            if (!Files.isRegularFile(directory.resolve(name))) {
                problems.add(name + ": missing from the release");
            }
        }
        try (Stream<Path> listing = Files.list(directory)) {
            listing.map(path -> path.getFileName().toString())
                    .filter(name -> !expected.contains(name))
                    .sorted()
                    .forEach(name -> problems.add(name + ": not one of the six files of a release"));
        }
    }

    private static void checkSums(Path directory, String version, List<String> problems) throws IOException {
        List<String> summed = summedNames(version);
        Map<String, String> listed = new HashMap<>();
        for (String line : readText(directory.resolve(SUMS)).split("\n")) {
            Matcher matcher = SUM_LINE.matcher(line);
            if (!matcher.matches()) {
                problems.add(SUMS + ": invalid line: " + line);
            } else if (!summed.contains(matcher.group(2))) {
                problems.add(SUMS + ": line for an unexpected file: " + line);
            } else if (listed.putIfAbsent(matcher.group(2), matcher.group(1)) != null) {
                problems.add(SUMS + ": several lines for " + matcher.group(2));
            }
        }
        for (String name : summed) {
            Path file = directory.resolve(name);
            if (!listed.containsKey(name)) {
                problems.add(name + ": no valid line in " + SUMS);
            } else if (Files.isRegularFile(file) && !listed.get(name).equals(sha256(file))) {
                problems.add(name + ": its SHA-256 is not the one in " + SUMS);
            }
        }
    }

    private static void checkArchive(Path file, Archive archive, String version, List<String> problems) {
        Contents contents;
        try {
            contents = archive.name().endsWith(".zip") ? readZip(file) : readTarGz(file);
        } catch (IOException e) {
            problems.add(archive.name() + ": cannot be read (" + e + ")");
            return;
        }
        if (!contents.files().contains(archive.launcher())) {
            problems.add(archive.name() + ": no launcher " + archive.launcher());
        }
        String versionLine = "java-options=-Djpackage.app-version=" + version;
        if (contents.configs().isEmpty()) {
            problems.add(archive.name() + ": no " + CONFIG + " outside the runtime folder");
        } else if (!contents.configs().stream().allMatch(config -> config.lines().anyMatch(versionLine::equals))) {
            problems.add(archive.name() + ": its " + CONFIG + " does not hold the line " + versionLine);
        }
    }

    private static void checkSbom(Path file, String name, String version, List<String> problems) throws IOException {
        Object root;
        try {
            root = ReleaseJson.parse(readText(file));
        } catch (JsonException e) {
            problems.add(name + ": not valid JSON (" + e.getMessage() + ")");
            return;
        }
        if (!"CycloneDX".equals(member(root, "bomFormat"))) {
            problems.add(name + ": bomFormat is not CycloneDX");
        }
        if (!(member(root, "specVersion") instanceof String specVersion) || specVersion.isBlank()) {
            problems.add(name + ": no specVersion");
        }
        if (!version.equals(member(member(member(root, "metadata"), "component"), "version"))) {
            problems.add(name + ": metadata.component.version is not " + version);
        }
    }

    private static Object member(Object node, String key) {
        return node instanceof Map<?, ?> map ? map.get(key) : null;
    }

    private static void checkReport(Path file, String name, String version, List<String> problems) throws IOException {
        String report = readText(file);
        if (!report.contains(VERDICT) || report.contains(BAD_VERDICT)) {
            problems.add(name + ": the verdict is not " + VERDICT);
        }
        String versionLine = "- Version : `" + version + "`";
        if (report.lines().noneMatch(versionLine::equals)) {
            problems.add(name + ": no line " + versionLine);
        }
        if (!report.contains(UI_SENTENCE)) {
            problems.add(name + ": it does not say that every interface test ran");
        }
    }

    private static String readText(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    private static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform has SHA-256", e);
        }
    }

    // --- Reading the archives ------------------------------------------------------------------------------------------------

    private static String normalized(String path) {
        String result = path.replace('\\', '/');
        while (result.startsWith("./")) {
            result = result.substring(2);
        }
        return result;
    }

    /** Named exactly Calc.cfg, outside the runtime: the runtime has .cfg files of its own, such as jvm.cfg. */
    private static boolean isApplicationConfig(String path) {
        String rooted = "/" + path;
        return rooted.endsWith("/" + CONFIG) && !rooted.contains(RUNTIME_FOLDER);
    }

    private static Contents readZip(Path file) throws IOException {
        Contents contents = new Contents();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                String path = normalized(entry.getName());
                contents.files().add(path);
                if (isApplicationConfig(path)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        contents.configs().add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return contents;
    }

    private static Contents readTarGz(Path file) throws IOException {
        Contents contents = new Contents();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            for (byte[] header = readTarBlock(in); !isZero(header); header = readTarBlock(in)) {
                readTarEntry(in, header, contents);
            }
            // To the end of the stream, so that gzip also checks its own checksum.
            in.transferTo(OutputStream.nullOutputStream());
        }
        return contents;
    }

    private static byte[] readTarBlock(InputStream in) throws IOException {
        byte[] block = in.readNBytes(TAR_BLOCK);
        if (block.length != TAR_BLOCK) {
            throw new EOFException("the tar content ends before its end blocks");
        }
        return block;
    }

    private static boolean isZero(byte[] block) {
        for (byte value : block) {
            if (value != 0) {
                return false;
            }
        }
        return true;
    }

    /** Only regular files count. The other entries (folders, links, GNU long names, pax headers) are skipped, data included. */
    private static void readTarEntry(InputStream in, byte[] header, Contents contents) throws IOException {
        checkTarChecksum(header);
        long size = tarOctal(header, TAR_SIZE, TAR_SIZE_LENGTH);
        long stored = (size + TAR_BLOCK - 1) / TAR_BLOCK * TAR_BLOCK;
        String path = normalized(tarPath(header));
        byte type = header[TAR_TYPE];
        boolean regular = (type == '0' || type == 0 || type == '7') && !path.endsWith("/");
        if (regular) {
            contents.files().add(path);
        }
        if (regular && isApplicationConfig(path)) {
            byte[] data = in.readNBytes(Math.toIntExact(stored));
            if (data.length != stored) {
                throw new EOFException("the tar content ends inside " + path);
            }
            contents.configs().add(new String(data, 0, (int) size, StandardCharsets.UTF_8));
        } else {
            in.skipNBytes(stored);
        }
    }

    private static void checkTarChecksum(byte[] header) throws IOException {
        long expected = tarOctal(header, TAR_CHECKSUM, TAR_CHECKSUM_LENGTH);
        long actual = 0;
        for (int index = 0; index < TAR_BLOCK; index++) {
            boolean inField = index >= TAR_CHECKSUM && index < TAR_CHECKSUM + TAR_CHECKSUM_LENGTH;
            actual += inField ? ' ' : header[index] & 0xFF;
        }
        if (actual != expected) {
            throw new IOException("bad tar header checksum");
        }
    }

    private static long tarOctal(byte[] header, int offset, int length) throws IOException {
        String digits = tarString(header, offset, length).strip();
        if (!digits.matches("[0-7]+")) {
            throw new IOException("bad octal field in a tar header");
        }
        return Long.parseLong(digits, 8);
    }

    private static String tarPath(byte[] header) {
        String name = tarString(header, TAR_NAME, TAR_NAME_LENGTH);
        boolean ustar = Arrays.equals(header, TAR_MAGIC, TAR_MAGIC + USTAR_MAGIC.length, USTAR_MAGIC, 0, USTAR_MAGIC.length);
        String prefix = ustar ? tarString(header, TAR_PREFIX, TAR_PREFIX_LENGTH) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    /** The text of a field, which ends at its first NUL or fills the field. */
    private static String tarString(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    // --- The evidence --------------------------------------------------------------------------------------------------------

    private static boolean passed(List<String> problems, boolean prerelease, boolean notes, boolean windows) {
        return problems.isEmpty() && prerelease && notes && windows;
    }

    private static String result(boolean ok) {
        return ok ? "ok" : "failed";
    }

    private static String render(Path directory, String version, String tag, String runId, String commit, boolean prerelease,
            boolean notes, boolean windows, List<String> problems) throws IOException {
        List<String> files = new ArrayList<>();
        for (String name : allNames(version)) {
            if (Files.isRegularFile(directory.resolve(name))) {
                files.add(quoted(name) + ": " + quoted(sha256(directory.resolve(name))));
            }
        }
        return "{\n"
                + "  \"result\": " + quoted(result(passed(problems, prerelease, notes, windows))) + ",\n"
                + "  \"version\": " + quoted(version) + ",\n"
                + "  \"tag\": " + quoted(tag) + ",\n"
                + "  \"runId\": " + quoted(runId) + ",\n"
                + "  \"commit\": " + quoted(commit) + ",\n"
                + "  \"prerelease\": " + prerelease + ",\n"
                + "  \"notesLinkInstallation\": " + notes + ",\n"
                + "  \"windowsStart\": " + quoted(result(windows)) + ",\n"
                + "  \"files\": " + block('{', files, '}') + ",\n"
                + "  \"problems\": " + block('[', problems.stream().map(ReleaseFilesCheck::quoted).toList(), ']') + "\n"
                + "}\n";
    }

    private static String block(char open, List<String> items, char close) {
        if (items.isEmpty()) {
            return "" + open + close;
        }
        return open + "\n    " + String.join(",\n    ", items) + "\n  " + close;
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
