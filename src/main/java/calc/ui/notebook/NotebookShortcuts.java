package calc.ui.notebook;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.swing.KeyStroke;

/**
 * The keyboard of the notebook, in one place: the keys the window binds, their description for the help and the menus.
 * The modifier of the menu shortcuts is a parameter ({@code Toolkit.getMenuShortcutKeyMaskEx()}: Ctrl on Windows and
 * Linux, Cmd on macOS), as do the file keys (new, open, save, save as); the keys of the line (Enter, arrows, Escape,
 * Ctrl+Shift+arrows) and Ctrl+M do not depend on it.
 * docs/adr/0010-editeur-carnet.md lists them and says why they are free.
 */
public final class NotebookShortcuts {
    /** What a key does. */
    public enum Command {
        ENTER("Valider la ligne et passer à la suivante (la dernière ligne est suivie d'une nouvelle ligne)"),
        PREVIOUS("Ligne précédente (valide la ligne quittée)"),
        NEXT("Ligne suivante (valide la ligne quittée)"),
        CANCEL_EDIT("Annuler la saisie en cours dans la ligne"),
        UNDO("Annuler la dernière modification validée"),
        REDO("Rétablir la modification annulée"),
        REDO_ALTERNATE("Rétablir la modification annulée (autre combinaison)"),
        INSERT_BELOW("Insérer une ligne au-dessous"),
        INSERT_ABOVE("Insérer une ligne au-dessus"),
        DELETE_LINE("Supprimer la ligne"),
        MOVE_UP("Monter la ligne"),
        MOVE_DOWN("Descendre la ligne"),
        NEXT_MODE("Passer au mode d'affichage suivant"),
        NEW_DOCUMENT("Nouveau carnet"),
        OPEN_DOCUMENT("Ouvrir un carnet"),
        SAVE("Enregistrer le carnet dans son fichier"),
        SAVE_AS("Enregistrer le carnet sous un autre nom");

        private final String description;

        Command(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final int menuMask;
    private final Map<Command, KeyStroke> strokes = new EnumMap<>(Command.class);

    /** @param menuMask the modifier of the menu shortcuts of the system, for instance {@code InputEvent.CTRL_DOWN_MASK} */
    public NotebookShortcuts(int menuMask) {
        this.menuMask = menuMask;
        int shift = InputEvent.SHIFT_DOWN_MASK;
        strokes.put(Command.ENTER, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0));
        strokes.put(Command.PREVIOUS, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0));
        strokes.put(Command.NEXT, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0));
        strokes.put(Command.CANCEL_EDIT, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0));
        strokes.put(Command.UNDO, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask));
        strokes.put(Command.REDO, KeyStroke.getKeyStroke(KeyEvent.VK_Y, menuMask));
        strokes.put(Command.REDO_ALTERNATE, KeyStroke.getKeyStroke(KeyEvent.VK_Z, menuMask | shift));
        strokes.put(Command.INSERT_BELOW, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask));
        strokes.put(Command.INSERT_ABOVE, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menuMask | shift));
        strokes.put(Command.DELETE_LINE, KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask | shift));
        // Always Ctrl+Shift, like Ctrl+M: the Aqua text field binds Alt+Up and Alt+Down.
        int moveMask = InputEvent.CTRL_DOWN_MASK | shift;
        strokes.put(Command.MOVE_UP, KeyStroke.getKeyStroke(KeyEvent.VK_UP, moveMask));
        strokes.put(Command.MOVE_DOWN, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, moveMask));
        // Ctrl+M of the display modes (docs/adr/0006): always Ctrl, because Cmd+M minimises a window on macOS.
        strokes.put(Command.NEXT_MODE, KeyStroke.getKeyStroke(KeyEvent.VK_M, InputEvent.CTRL_DOWN_MASK));
        // The file menu (E13): the modifier of the system, like undo.
        strokes.put(Command.NEW_DOCUMENT, KeyStroke.getKeyStroke(KeyEvent.VK_N, menuMask));
        strokes.put(Command.OPEN_DOCUMENT, KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask));
        strokes.put(Command.SAVE, KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask));
        strokes.put(Command.SAVE_AS, KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | shift));
    }

    public KeyStroke stroke(Command command) {
        return strokes.get(command);
    }

    public int menuMask() {
        return menuMask;
    }

    /** The key as the user reads it in French: {@code Ctrl+Maj+K}, {@code Ctrl+Maj+↑}, {@code Entrée}. */
    public String text(Command command) {
        KeyStroke stroke = stroke(command);
        List<String> parts = new ArrayList<>();
        int modifiers = stroke.getModifiers();
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) {
            parts.add("Ctrl");
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) {
            parts.add("Cmd");
        }
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) {
            parts.add("Alt");
        }
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) {
            parts.add("Maj");
        }
        parts.add(switch (stroke.getKeyCode()) {
            case KeyEvent.VK_ENTER -> "Entrée";
            case KeyEvent.VK_ESCAPE -> "Échap";
            case KeyEvent.VK_UP -> "↑";
            case KeyEvent.VK_DOWN -> "↓";
            default -> KeyEvent.getKeyText(stroke.getKeyCode());
        });
        return String.join("+", parts);
    }

    /** The lines of the help of the keyboard: the key, a tab, what it does. */
    public List<String> helpLines() {
        List<String> lines = new ArrayList<>();
        for (Command command : Command.values()) {
            lines.add(text(command) + "\t" + command.description());
        }
        return lines;
    }
}
