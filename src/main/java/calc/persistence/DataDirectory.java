package calc.persistence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Where the data of Calc lives on each operating system. The environment is injected so that every system can be
 * simulated by a test.
 *
 * <ul>
 *   <li>Windows: {@code %APPDATA%\Calc}, or {@code <home>\AppData\Roaming\Calc} when {@code APPDATA} is not set;</li>
 *   <li>macOS: {@code ~/Library/Application Support/Calc};</li>
 *   <li>Linux and others: {@code $XDG_DATA_HOME/calc} when that variable is an absolute path (the XDG specification
 *       ignores a relative one), otherwise {@code ~/.local/share/calc}.</li>
 * </ul>
 *
 * <p>Version 1 (macOS and Linux) kept the history in {@code ~/.calc}; {@link #legacyDirectory(Environment)} names it.
 */
public final class DataDirectory {
    /** The three families of systems the layout distinguishes. */
    public enum Platform {
        WINDOWS, MACOS, LINUX;

        static Platform of(String osName) {
            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (name.startsWith("windows")) {
                return WINDOWS;
            }
            return name.startsWith("mac") || name.startsWith("darwin") ? MACOS : LINUX;
        }
    }

    /**
     * The facts the layout depends on.
     *
     * @param osName   the {@code os.name} property
     * @param home     the user's home directory
     * @param variable the value of an environment variable, or null when it is not set
     */
    public record Environment(String osName, Path home, UnaryOperator<String> variable) {
        /** The running system. */
        public static Environment system() {
            return new Environment(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System::getenv);
        }

        public Platform platform() {
            return Platform.of(osName);
        }

        String nonBlank(String name) {
            String value = variable.apply(name);
            return value == null || value.isBlank() ? null : value;
        }
    }

    private DataDirectory() {
    }

    /** The directory that holds {@code history.json} and its lock. */
    public static Path resolve(Environment environment) {
        return switch (environment.platform()) {
            case WINDOWS -> {
                String appData = environment.nonBlank("APPDATA");
                yield (appData != null ? Path.of(appData) : environment.home().resolve("AppData").resolve("Roaming")).resolve("Calc");
            }
            case MACOS -> environment.home().resolve("Library").resolve("Application Support").resolve("Calc");
            case LINUX -> {
                Path xdg = absolutePath(environment.nonBlank("XDG_DATA_HOME"));
                yield (xdg != null ? xdg : environment.home().resolve(".local").resolve("share")).resolve("calc");
            }
        };
    }

    /** The directory of version 1 on macOS and Linux ({@code ~/.calc}); empty on Windows, which never used it. */
    public static Optional<Path> legacyDirectory(Environment environment) {
        return environment.platform() == Platform.WINDOWS ? Optional.empty() : Optional.of(environment.home().resolve(".calc"));
    }

    private static Path absolutePath(String text) {
        if (text == null) {
            return null;
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path : null;
        } catch (InvalidPathException invalid) {
            return null;
        }
    }
}
