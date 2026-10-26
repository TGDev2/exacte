package calc.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.persistence.DataDirectory.Environment;
import calc.persistence.DataDirectory.Platform;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The data directory of every system, from a simulated environment: nothing here reads the real one. */
class DataDirectoryTest {
    private static Environment environment(String osName, Path home, Map<String, String> variables) {
        return new Environment(osName, home, variables::get);
    }

    @Test
    void windowsUsesAppDataCalc(@TempDir Path root) {
        Path appData = root.resolve("Roaming");

        Path directory = DataDirectory.resolve(environment("Windows 11", root.resolve("home"), Map.of("APPDATA", appData.toString())));

        assertEquals(appData.resolve("Calc"), directory);
    }

    @Test
    void windowsWithoutAppDataFallsBackToTheRoamingFolderOfTheHome(@TempDir Path root) {
        Path home = root.resolve("home");

        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("Calc"),
                DataDirectory.resolve(environment("Windows 10", home, Map.of())));
        assertEquals(home.resolve("AppData").resolve("Roaming").resolve("Calc"),
                DataDirectory.resolve(environment("Windows 10", home, Map.of("APPDATA", "  "))), "A blank value is no value.");
    }

    @Test
    void macOsUsesApplicationSupportOfTheHome(@TempDir Path root) {
        Path home = root.resolve("home");

        assertEquals(home.resolve("Library").resolve("Application Support").resolve("Calc"),
                DataDirectory.resolve(environment("Mac OS X", home, Map.of("XDG_DATA_HOME", root.resolve("xdg").toString()))),
                "XDG_DATA_HOME does not apply on macOS.");
        assertEquals(Platform.MACOS, environment("Darwin", home, Map.of()).platform());
    }

    @Test
    void linuxUsesXdgDataHomeCalcWhenItIsAnAbsolutePath(@TempDir Path root) {
        Path xdg = root.resolve("xdg-data");

        assertEquals(xdg.resolve("calc"),
                DataDirectory.resolve(environment("Linux", root.resolve("home"), Map.of("XDG_DATA_HOME", xdg.toString()))));
    }

    @Test
    void linuxFallsBackToLocalShareWhenXdgDataHomeIsMissingBlankOrRelative(@TempDir Path root) {
        Path home = root.resolve("home");
        Path expected = home.resolve(".local").resolve("share").resolve("calc");

        assertEquals(expected, DataDirectory.resolve(environment("Linux", home, Map.of())));
        assertEquals(expected, DataDirectory.resolve(environment("Linux", home, Map.of("XDG_DATA_HOME", ""))));
        assertEquals(expected, DataDirectory.resolve(environment("Linux", home, Map.of("XDG_DATA_HOME", "   "))));
        assertEquals(expected, DataDirectory.resolve(environment("Linux", home, Map.of("XDG_DATA_HOME", "relative/dir"))),
                "The XDG specification ignores a relative path.");
    }

    @Test
    void anyOtherSystemIsTreatedLikeLinux(@TempDir Path root) {
        Path home = root.resolve("home");

        for (String name : new String[] {"FreeBSD", "SunOS", "AIX", "", null}) {
            assertEquals(home.resolve(".local").resolve("share").resolve("calc"),
                    DataDirectory.resolve(environment(name, home, Map.of())), String.valueOf(name));
        }
    }

    @Test
    void theOldDirectoryOfVersion1ExistsOnMacOsAndLinuxOnly(@TempDir Path root) {
        Path home = root.resolve("home");

        assertEquals(Optional.of(home.resolve(".calc")), DataDirectory.legacyDirectory(environment("Linux", home, Map.of())));
        assertEquals(Optional.of(home.resolve(".calc")), DataDirectory.legacyDirectory(environment("Mac OS X", home, Map.of())));
        assertEquals(Optional.empty(), DataDirectory.legacyDirectory(environment("Windows 11", home, Map.of())));
    }

    @Test
    void theRunningSystemHasADirectoryEndingWithCalcInAnyCase() {
        Path directory = DataDirectory.resolve(Environment.system());

        assertTrue(directory.getFileName().toString().equalsIgnoreCase("calc"), directory.toString());
    }
}
