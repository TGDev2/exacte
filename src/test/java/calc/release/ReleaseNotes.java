package calc.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the text of a GitHub Release from {@code CHANGELOG.md}: the body of the {@code ## [version]} section, with every
 * relative link made absolute, followed by a link to the installation guide.
 *
 * <p>Run by {@code gradlew releaseNotes} (see {@code build.gradle.kts}) and by {@code release.yml}. It lives in the test source
 * set so that it is tested on every system without being shipped with the application (ADR 0012, amendment).
 */
public final class ReleaseNotes {
    private static final String SECTION_PREFIX = "## [";
    private static final String INSTALLATION_GUIDE = "docs/INSTALLATION.md";
    /** {@code ](path)} where the path is neither an absolute web link, a mail link nor an in-page anchor. */
    private static final Pattern RELATIVE_LINK = Pattern.compile("\\]\\((?!https://|http://|mailto:|#)([^)\\s]*)\\)");

    private ReleaseNotes() {
    }

    /**
     * @param changelog  the text of {@code CHANGELOG.md}
     * @param version    the exact version number, for example {@code 1.0.0}
     * @param repository {@code owner/name} on GitHub
     * @param ref        the commit or branch that relative links point to
     * @return the notes of {@code version}
     * @throws IllegalArgumentException if the section of {@code version} is missing or has no content
     */
    public static String build(String changelog, String version, String repository, String ref) {
        requireText(changelog, "changelog");
        requireText(version, "version");
        requireText(repository, "repository");
        requireText(ref, "ref");

        String body = section(changelog, version);
        String blob = "https://github.com/" + repository + "/blob/" + ref + "/";
        return makeLinksAbsolute(body, blob)
                + "\n\n## Installer\n\n"
                + "Pour télécharger Calc, vérifier le fichier et passer l'avertissement du premier lancement, suivez le "
                + "[guide d'installation](" + blob + INSTALLATION_GUIDE + ").\n";
    }

    /** Arguments: changelog, version, repository, ref, output file. Nothing is written when the build fails. */
    public static void main(String[] args) throws IOException {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "expected 5 arguments (changelog, version, repository, ref, output file), got " + args.length);
        }
        String changelog = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String notes = build(changelog, args[1], args[2], args[3]);
        Path output = Path.of(args[4]);
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(output, notes, StandardCharsets.UTF_8);
    }

    private static String section(String changelog, String version) {
        String title = SECTION_PREFIX + version + "]";
        StringBuilder body = new StringBuilder();
        boolean inside = false;
        for (String line : changelog.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith(SECTION_PREFIX)) {
                if (inside) {
                    break;
                }
                // The closing bracket is part of the title, so 1.0.0 never matches 1.0.01, and the dots are literal.
                inside = line.startsWith(title);
                continue;
            }
            if (inside) {
                body.append(line).append('\n');
            }
        }
        String text = body.toString().strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("CHANGELOG.md has no '## [" + version + "]' section with content: "
                    + "write the release notes there before publishing version " + version);
        }
        return text;
    }

    private static String makeLinksAbsolute(String text, String blob) {
        Matcher matcher = RELATIVE_LINK.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.startsWith("./")) {
                path = path.substring(2);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement("](" + blob + path + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
    }
}
