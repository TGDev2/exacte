package calc.ui.design;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The design tokens of the desktop and of the web page, read from {@code calc/ui/design/tokens.properties}, their single
 * source (ADR 0016). The file holds the colours of each theme by role, the font families, the font sizes, the spaces (on a
 * 4 px grid), the radii and the contrast pairs that the tests measure. It is checked when read: a malformed file fails at
 * once, never with a wrong colour later. The FlatLaf themes and {@code web/site/tokens.css} are generated from it by
 * {@code DesignTokenGenerator} (test source set, Gradle task {@code generateDesignTokens}).
 */
public final class DesignTokens {
    private static final String RESOURCE = "tokens.properties";
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern PIXELS = Pattern.compile("\\d{1,4}");
    private static final Pattern PAIR = Pattern.compile("(\\S+) on (\\S+)");
    private static final Pattern FONT = Pattern.compile("font\\.([a-z]+)\\.family");
    /** The size groups, in the order the generator writes them. */
    private static final List<String> SIZE_GROUPS = List.of("font.size.", "space.", "radius.");
    private static final String TEXT_PAIR = "contrast.text.";
    private static final String UI_PAIR = "contrast.ui.";

    /**
     * A pair of colour roles whose contrast is measured in both themes: a text ({@code contrast.text.*}, at least 4,5:1) or
     * an indicator that is not text ({@code contrast.ui.*}, at least 3:1), drawn on a background role.
     */
    public record ContrastPair(String key, boolean text, String foreground, String background) {
    }

    private final Map<Theme, Map<String, Color>> colours;
    private final Map<String, Integer> sizes;
    private final Map<String, String> fonts;
    private final List<ContrastPair> contrastPairs;

    private DesignTokens(Map<Theme, Map<String, Color>> colours, Map<String, Integer> sizes, Map<String, String> fonts,
            List<ContrastPair> contrastPairs) {
        this.colours = colours;
        this.sizes = sizes;
        this.fonts = fonts;
        this.contrastPairs = contrastPairs;
    }

    /** The tokens of the application, read once from the classpath; the instance is immutable and shared. */
    public static DesignTokens load() {
        return Loaded.TOKENS;
    }

    /** The colour of a role in a theme; an unknown role throws {@link IllegalArgumentException} that names it. */
    public Color color(Theme theme, String role) {
        Color colour = colours.get(theme).get(role);
        if (colour == null) {
            throw new IllegalArgumentException("unknown colour role: " + role);
        }
        return colour;
    }

    /** The colour roles, the same in every theme, in alphabetical order. */
    public List<String> colorRoles() {
        return List.copyOf(colours.get(Theme.LIGHT).keySet());
    }

    /** A size in pixels, by its full key: {@code font.size.body}, {@code space.2}, {@code radius.small}. */
    public int size(String key) {
        Integer size = sizes.get(key);
        if (size == null) {
            throw new IllegalArgumentException("unknown size: " + key);
        }
        return size;
    }

    /** The size keys: font sizes, then spaces, then radii, each group from the smallest. */
    public List<String> sizeKeys() {
        List<String> keys = new ArrayList<>(sizes.keySet());
        keys.sort(Comparator.comparingInt(DesignTokens::sizeGroup).thenComparing(sizes::get).thenComparing(Comparator.naturalOrder()));
        return List.copyOf(keys);
    }

    /** A font family by its name in {@code font.<name>.family}: {@code ui} (Inter) or {@code mono} (JetBrains Mono). */
    public String fontFamily(String name) {
        String family = fonts.get(name);
        if (family == null) {
            throw new IllegalArgumentException("unknown font: " + name);
        }
        return family;
    }

    /** The contrast pairs, text pairs first, each kind in the order of its keys. */
    public List<ContrastPair> contrastPairs() {
        return contrastPairs;
    }

    /** Reads and checks a set of tokens; a malformed one throws {@link IllegalStateException} that names the faulty key. */
    static DesignTokens parse(Properties properties) {
        Map<Theme, Map<String, Color>> colours = new EnumMap<>(Theme.class);
        for (Theme theme : Theme.values()) {
            colours.put(theme, new TreeMap<>());
        }
        Map<String, Integer> sizes = new TreeMap<>();
        Map<String, String> fonts = new TreeMap<>();
        Map<String, String> pairs = new TreeMap<>();
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key).strip();
            Theme theme = colourTheme(key);
            Matcher font = FONT.matcher(key);
            if (theme != null) {
                require(COLOUR.matcher(value).matches(), key, "is not a #RRGGBB colour: " + value);
                colours.get(theme).put(key.substring(("color." + theme.key() + ".").length()), Color.decode(value));
            } else if (sizeGroup(key) >= 0) {
                require(PIXELS.matcher(value).matches(), key, "is not a number of pixels: " + value);
                int size = Integer.parseInt(value);
                require(!key.startsWith("space.") || size % 4 == 0, key, "is not on the 4 px grid: " + value);
                sizes.put(key, size);
            } else if (font.matches()) {
                require(!value.isEmpty(), key, "names no font family");
                fonts.put(font.group(1), value);
            } else if (key.startsWith(TEXT_PAIR) || key.startsWith(UI_PAIR)) {
                pairs.put(key, value);
            } else {
                throw new IllegalStateException("unknown design token: " + key);
            }
        }
        Map<String, Color> light = colours.get(Theme.LIGHT);
        require(!light.isEmpty(), "color.light.*", "declares no colour");
        for (Theme theme : Theme.values()) {
            require(colours.get(theme).keySet().equals(light.keySet()), "color." + theme.key() + ".*",
                    "declares other roles than the light theme: " + colours.get(theme).keySet());
            colours.put(theme, Collections.unmodifiableMap(colours.get(theme)));
        }
        List<ContrastPair> contrastPairs = new ArrayList<>();
        for (String prefix : List.of(TEXT_PAIR, UI_PAIR)) {
            pairs.forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    Matcher pair = PAIR.matcher(value);
                    require(pair.matches(), key, "does not read \"<role> on <role>\": " + value);
                    require(light.containsKey(pair.group(1)), key, "names an unknown role: " + pair.group(1));
                    require(light.containsKey(pair.group(2)), key, "names an unknown role: " + pair.group(2));
                    contrastPairs.add(new ContrastPair(key, prefix.equals(TEXT_PAIR), pair.group(1), pair.group(2)));
                }
            });
        }
        return new DesignTokens(Collections.unmodifiableMap(colours), Collections.unmodifiableMap(sizes),
                Collections.unmodifiableMap(fonts), List.copyOf(contrastPairs));
    }

    private static Theme colourTheme(String key) {
        for (Theme theme : Theme.values()) {
            if (key.startsWith("color." + theme.key() + ".")) {
                return theme;
            }
        }
        return null;
    }

    private static int sizeGroup(String key) {
        for (int group = 0; group < SIZE_GROUPS.size(); group++) {
            if (key.startsWith(SIZE_GROUPS.get(group))) {
                return group;
            }
        }
        return -1;
    }

    private static void require(boolean condition, String key, String problem) {
        if (!condition) {
            throw new IllegalStateException("design token " + key + " " + problem);
        }
    }

    static DesignTokens read(InputStream input) throws IOException {
        if (input == null) {
            throw new IllegalStateException("calc/ui/design/" + RESOURCE + " is not on the classpath");
        }
        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Properties properties = new Properties();
            properties.load(reader);
            return parse(properties);
        }
    }

    /** Holder of the tokens of the application, read on first use. */
    private static final class Loaded {
        static final DesignTokens TOKENS = readResource();

        private static DesignTokens readResource() {
            try {
                return read(DesignTokens.class.getResourceAsStream(RESOURCE));
            } catch (IOException failure) {
                throw new UncheckedIOException("calc/ui/design/" + RESOURCE + " cannot be read", failure);
            }
        }
    }
}
