package calc.persistence;

/**
 * Opens {@link JsonParser}, private to this package, to the release tools of {@code calc.release} (ADR 0012, amendment). It lives
 * in the test source set, like them, so the application itself gains no public JSON API.
 */
public final class ReleaseJson {
    private ReleaseJson() {
    }

    /** The tree of {@code text}: {@code Map}, {@code List} and {@code String}, plus booleans, numbers and {@code null}. */
    public static Object parse(String text) throws JsonException {
        return JsonParser.parse(text);
    }
}
