package calc.ui.theme;

import calc.ui.design.Theme;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The theme of Windows, read once when the detector is built: the value {@code AppsUseLightTheme} of
 * {@code HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize}, 0 for dark, through {@code reg query} (FlatLaf 3.8
 * exposes no detection of its own). Build it off the event thread: the query may take up to {@value #TIMEOUT_SECONDS} s.
 * Under another system, or when the value cannot be read, the detection is unavailable.
 */
public final class WindowsSystemThemeDetector implements SystemThemeDetector {
    static final long TIMEOUT_SECONDS = 2;
    private static final String KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
    private static final String VALUE = "AppsUseLightTheme";
    private static final Pattern DWORD = Pattern.compile("(?m)^\\s*" + VALUE + "\\s+REG_DWORD\\s+0x([0-9a-fA-F]{1,8})\\s*$");
    private static final Logger LOGGER = Logger.getLogger(WindowsSystemThemeDetector.class.getName());

    private final Optional<Theme> theme;

    /** The detector of the system the application runs on. */
    public WindowsSystemThemeDetector() {
        this(System.getProperty("os.name", ""));
    }

    /** @param osName the name of the system: only one that starts with « Windows » is read */
    public WindowsSystemThemeDetector(String osName) {
        theme = osName != null && osName.startsWith("Windows") ? query() : Optional.empty();
    }

    @Override
    public boolean available() {
        return theme.isPresent();
    }

    @Override
    public Optional<Theme> current() {
        return theme;
    }

    /** The theme the output of {@code reg query ... /v AppsUseLightTheme} gives: empty without the value, or on an error. */
    static Optional<Theme> parse(String output) {
        if (output == null) {
            return Optional.empty();
        }
        Matcher value = DWORD.matcher(output);
        if (!value.find()) {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(value.group(1), 16) == 0 ? Theme.DARK : Theme.LIGHT);
    }

    private static Optional<Theme> query() {
        Process process = null;
        try {
            process = new ProcessBuilder("reg", "query", KEY, "/v", VALUE).redirectErrorStream(true).start();
            process.getOutputStream().close();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.log(Level.WARNING, "reg query did not answer in {0} s: the theme of the system is not followed", TIMEOUT_SECONDS);
                return Optional.empty();
            }
            // The output is a few hundred bytes: it fits in the pipe, so waiting before reading cannot block the query.
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
            }
            return process.exitValue() == 0 ? parse(output) : Optional.empty();
        } catch (IOException | RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot read the theme of the system", exception);
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
