package calc.ui.notebook;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The command palette as a model, without Swing (SPEC §4.2 « Découverte »): the commands of the menus as entries, and the
 * search among them. The window derives the entries from its menus each time the palette opens, so that the palette lists
 * exactly what the menus offer, in their order and in their state; {@code CommandPaletteView} shows them.
 *
 * <p>The label of an entry follows one rule, the same on the web: « Mode » and the label of a display mode, « Thème : » and
 * the label of a theme, and the exact text of the menu item for every other command.
 */
public final class CommandPalette {
    /** The text shown in place of the list when no command matches the search. */
    public static final String NO_COMMAND = "Aucune commande";

    /**
     * A command of the palette.
     *
     * @param id       what names the command in the window (the name of its menu item)
     * @param label    what the palette shows and searches
     * @param shortcut the text of its key, {@code ""} without one
     * @param enabled  whether it can run now, as its menu item
     */
    public record Entry(String id, String label, String shortcut, boolean enabled) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(shortcut, "shortcut");
        }

        /** What a screen reader says for the entry: its label, then its key when it has one. */
        public String accessibleName() {
            return shortcut.isEmpty() ? label : label + ", " + shortcut;
        }
    }

    private final List<Entry> entries;

    public CommandPalette(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The label of the entry of a display mode: « Mode Décimal ». */
    public static String modeLabel(String modeLabel) {
        return "Mode " + modeLabel;
    }

    /** The label of the entry of a theme: « Thème : Sombre ». */
    public static String themeLabel(String themeLabel) {
        return "Thème : " + themeLabel;
    }

    public List<Entry> entries() {
        return entries;
    }

    /**
     * The entries whose label contains the search, in their order, ignoring case and accents (« decimal » finds « Mode
     * Décimal »). A blank search keeps every entry.
     */
    public List<Entry> filter(String query) {
        String wanted = normalized(query);
        if (wanted.isEmpty()) {
            return entries;
        }
        return entries.stream().filter(entry -> normalized(entry.label()).contains(wanted)).toList();
    }

    // Without accents (the combining marks of the decomposed form), in lower case, without the spaces around.
    static String normalized(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).strip();
    }
}
