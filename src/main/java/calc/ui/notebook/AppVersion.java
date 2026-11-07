package calc.ui.notebook;

/**
 * The version of the application as the About dialog shows it. jpackage writes
 * {@code -Djpackage.app-version=<version>} in the launcher configuration, so the number is a system property in the
 * packaged application and absent otherwise (tests, gradle run).
 */
public final class AppVersion {
    static final String PROPERTY = "jpackage.app-version";
    private static final String DEVELOPMENT = "Version de développement";

    private AppVersion() {
    }

    public static String current() {
        return text(System.getProperty(PROPERTY));
    }

    public static String text(String property) {
        if (property == null || property.isBlank()) {
            return DEVELOPMENT;
        }
        return "Version " + property.strip();
    }
}
