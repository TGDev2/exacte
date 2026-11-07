package calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The installation guide for people who are not developers, {@code docs/INSTALLATION.md}, and the documents that point to it:
 * the sections it must have, the facts each one must carry (archive names, checksum commands, the warnings of each system,
 * where the data lives), the short download section of the README, and the honest caveats (unsigned applications, untried
 * macOS and Linux steps, partial CSV formula protection). Whether the text is clear to someone who never opened a terminal
 * is for a reader to tell; this test proves that nothing the brief lists is missing.
 */
@Tag("acceptance")
class InstallationGuideAcceptanceTest {
    private static final Path GUIDE = Path.of("docs", "INSTALLATION.md");
    private static final Path README = Path.of("README.md");
    private static final Path RELEASE = Path.of("docs", "RELEASE.md");
    private static final Path USER_GUIDE = Path.of("docs", "GUIDE-UTILISATEUR.md");
    private static final Path DATA_DIRECTORY = Path.of("src", "main", "java", "calc", "persistence", "DataDirectory.java");
    private static final Path NOTEBOOK_STORE = Path.of("src", "main", "java", "calc", "persistence", "NotebookStore.java");

    private static final String RELEASES_URL = "https://github.com/TGDev2/Calculatrice/releases";

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), file + " must exist");
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The level-2 sections of a document by exact title line ({@code ## Title}), body up to the next level-2 title. */
    private static Map<String, String> sections(String document) {
        Map<String, String> sections = new LinkedHashMap<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        boolean fenced = false;
        for (String line : document.split("\n", -1)) {
            if (line.startsWith("```")) {
                fenced = !fenced;
            }
            if (!fenced && line.startsWith("## ")) {
                if (title != null) {
                    sections.put(title, body.toString());
                }
                title = line.stripTrailing();
                body = new StringBuilder();
            } else if (title != null) {
                body.append(line).append('\n');
            }
        }
        if (title != null) {
            sections.put(title, body.toString());
        }
        return sections;
    }

    private static String section(Path file, String title) throws IOException {
        Map<String, String> sections = sections(read(file));
        assertTrue(sections.containsKey(title), file + " must have the exact heading \"" + title + "\", has " + sections.keySet());
        return sections.get(title);
    }

    private static void assertContainsAll(String where, String text, String... expected) {
        List<String> missing = new ArrayList<>();
        for (String item : expected) {
            if (!text.contains(item)) {
                missing.add(item);
            }
        }
        assertTrue(missing.isEmpty(), where + " is missing: " + missing);
    }

    private static void assertContainsAllIgnoringCase(String where, String text, String... expected) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        for (String item : expected) {
            if (!lower.contains(item.toLowerCase(Locale.ROOT))) {
                missing.add(item);
            }
        }
        assertTrue(missing.isEmpty(), where + " is missing: " + missing);
    }

    @Test
    void theGuideHasTheSevenSectionsWithTheirExactTitles() throws IOException {
        Map<String, String> sections = sections(read(GUIDE));
        List<String> expected = List.of(
                "## Windows",
                "## macOS",
                "## Linux",
                "## Où sont vos données",
                "## Mettre à jour sans perdre votre carnet",
                "## Désinstaller",
                "## Si ça ne marche pas");
        assertTrue(sections.keySet().containsAll(expected), "missing headings: " + expected.stream().filter(h -> !sections.containsKey(h)).toList());
    }

    @Test
    void theWindowsSectionGivesTheArchiveTheChecksumAndTheWarningSteps() throws IOException {
        String windows = section(GUIDE, "## Windows");
        assertContainsAll(
                "## Windows",
                windows,
                "windows-x64.zip",
                "SHA256SUMS.txt",
                "Get-FileHash",
                "Calc.exe",
                "Windows a protégé votre ordinateur",
                "Informations complémentaires",
                "Exécuter quand même");
    }

    @Test
    void theMacSectionGivesTheArchiveTheWarningStepsTheHypothesisAndTheIntelLimit() throws IOException {
        String mac = section(GUIDE, "## macOS");
        assertContainsAll(
                "## macOS",
                mac,
                "macos-arm64.tar.gz",
                "SHA256SUMS.txt",
                "shasum -a 256",
                "Calc.app",
                "Réglages Système",
                "Confidentialité et sécurité",
                "Ouvrir quand même",
                "xattr",
                "Intel");
        assertContainsAllIgnoringCase("## macOS", mac, "hypothèse", "non vérifié");
    }

    @Test
    void theLinuxSectionGivesTheArchiveTheCommandsAndSaysTheStepsWereNotTried() throws IOException {
        String linux = section(GUIDE, "## Linux");
        assertContainsAll("## Linux", linux, "linux-x64.tar.gz", "SHA256SUMS.txt", "shasum -a 256", "tar -xzf", "Calc/bin/Calc");
        assertContainsAllIgnoringCase("## Linux", linux, "non vérifié");
    }

    @Test
    void theDataSectionNamesTheFoldersOfEachSystemAndTheNamesStillExistInTheCode() throws IOException {
        String data = section(GUIDE, "## Où sont vos données");
        assertContainsAll(
                "## Où sont vos données",
                data,
                "%APPDATA%\\Calc",
                "~/Library/Application Support/Calc",
                "~/.local/share/calc",
                "XDG_DATA_HOME",
                "carnet-courant.calc.json");
        assertTrue(read(DATA_DIRECTORY).contains("XDG_DATA_HOME"), "XDG_DATA_HOME must still be read by DataDirectory.java");
        assertTrue(read(NOTEBOOK_STORE).contains("carnet-courant.calc.json"), "NotebookStore.java must still name carnet-courant.calc.json");
    }

    @Test
    void theUpdateSectionSaysToReplaceTheApplicationFolderAndKeepTheData() throws IOException {
        String update = section(GUIDE, "## Mettre à jour sans perdre votre carnet");
        assertContainsAllIgnoringCase("## Mettre à jour sans perdre votre carnet", update, "remplacer", "données");
    }

    @Test
    void theUninstallSectionNamesTheApplicationFolderAndTheDataFolder() throws IOException {
        String uninstall = section(GUIDE, "## Désinstaller").toLowerCase(Locale.ROOT);
        assertTrue(Pattern.compile("dossier de l.application").matcher(uninstall).find(), "the application folder must be named");
        assertTrue(Pattern.compile("dossier d(es|e) donn[ée]es").matcher(uninstall).find(), "the data folder must be named");
    }

    @Test
    void theGuideTellsNotToTakeThePreReleasesNamedTest() throws IOException {
        String guide = read(GUIDE);
        assertTrue(guide.contains("test-"), "the guide must name the test- prefix of the pre-releases");
        assertTrue(
                Pattern.compile("pr[ée]-?versions?", Pattern.CASE_INSENSITIVE).matcher(guide).find(),
                "the guide must call them pre-releases (pré-version)");
    }

    @Test
    void theGuideSendsReadersToTheReleasesPageAndTheIssues() throws IOException {
        assertContainsAll("docs/INSTALLATION.md", read(GUIDE), RELEASES_URL, "https://github.com/TGDev2/Calculatrice/issues");
    }

    @Test
    void theReadmeDownloadSectionIsShortAndPointsToTheGuide() throws IOException {
        String download = section(README, "## Téléchargement");
        long lines = download.lines().filter(line -> !line.isBlank()).count();
        assertTrue(lines <= 6, "## Téléchargement must have at most 6 non-blank lines, has " + lines);
        assertContainsAll("README ## Téléchargement", download, "(docs/INSTALLATION.md)", RELEASES_URL);
        assertFalse(download.contains("Get-FileHash"), "the checksum commands belong to the guide");
        assertFalse(
                Pattern.compile("Aucune version n.a encore été publiée").matcher(download).find(),
                "the stale 'no version published' line must go");
    }

    @Test
    void theReadmeDownloadSectionKeepsTheNoJavaAndUnsignedReminders() throws IOException {
        String download = section(README, "## Téléchargement").toLowerCase(Locale.ROOT);
        assertTrue(download.contains("sans java"), "the section must say that no Java is needed");
        assertTrue(Pattern.compile("pas sign|non sign").matcher(download).find(), "the section must remind that the applications are unsigned");
    }

    @Test
    void theReadmeKnownLimitsSayThatCsvFormulaProtectionIsPartialAndNotCheckedInExcel() throws IOException {
        String limits = section(README, "## Limites connues");
        boolean line = limits.lines().anyMatch(l -> l.contains("CSV") && l.contains("Excel"));
        assertTrue(line, "## Limites connues needs one line mentioning both CSV and Excel");
    }

    @Test
    void everyReadmeLineThatSaysNeutralisedAlsoSaysPartial() throws IOException {
        List<String> offending = read(README).lines().filter(l -> l.contains("neutralis") && !l.contains("partielle")).toList();
        assertEquals(List.of(), offending, "a README line with 'neutralis' must also say 'partielle'");
    }

    @Test
    void theReadmeDocumentationListLinksToTheGuide() throws IOException {
        assertTrue(section(README, "## Documentation").contains("docs/INSTALLATION.md"), "the documentation list must link to the guide");
    }

    @Test
    void theReleaseProcedureSendsUsersToTheGuideInItsOpeningAndKeepsItsTitle() throws IOException {
        String release = read(RELEASE);
        int firstSection = release.indexOf("\n## ");
        assertTrue(firstSection > 0, "docs/RELEASE.md must have sections");
        String opening = release.substring(0, firstSection);
        assertTrue(
                Pattern.compile("\\]\\((?:\\./)?INSTALLATION\\.md(?:#[^)]*)?\\)").matcher(opening).find(),
                "the opening of docs/RELEASE.md must link to INSTALLATION.md");
        assertTrue(sections(release).containsKey("## Publier une version"), "docs/RELEASE.md must keep its ## Publier une version heading");
    }

    @Test
    void theUserGuideSaysTheApostropheProtectionOfTheCsvIsPartialAndPointsToTheReadme() throws IOException {
        List<String> lines = read(USER_GUIDE).lines().toList();
        int at = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("apostrophe")) {
                at = i;
                break;
            }
        }
        assertTrue(at >= 0, "the sentence about the apostrophe prefix must still be in docs/GUIDE-UTILISATEUR.md");
        int start = at;
        while (start > 0 && !lines.get(start).startsWith("- ")) {
            start--;
        }
        int end = at;
        while (end + 1 < lines.size() && !lines.get(end + 1).isBlank() && !lines.get(end + 1).startsWith("- ")) {
            end++;
        }
        String bullet = String.join("\n", lines.subList(start, end + 1));
        assertTrue(bullet.contains("partielle"), "the CSV bullet must say that the protection is partial");
        assertTrue(bullet.contains("README.md"), "the CSV bullet must point to the README");
    }
}
