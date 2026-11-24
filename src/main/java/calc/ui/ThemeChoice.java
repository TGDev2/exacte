package calc.ui;

/** The theme the user chose in Affichage > Thème: a theme of its own, or the one of the system where it can be read. */
public enum ThemeChoice {
    LIGHT("Clair"),
    DARK("Sombre"),
    SYSTEM("Système");

    private final String label;

    ThemeChoice(String label) {
        this.label = label;
    }

    /** The text of the menu item. */
    public String label() {
        return label;
    }
}
