package calc.ui;

/** Where the chosen theme is remembered between two launches. Neither method throws. */
public interface ThemeStore {
    /** The remembered choice, {@link ThemeChoice#LIGHT} when there is none or when it cannot be read. */
    ThemeChoice load();

    /** Remembers {@code choice}; a failure is logged and otherwise ignored. */
    void save(ThemeChoice choice);

    /** A store that remembers nothing. */
    static ThemeStore noop() {
        return new ThemeStore() {
            @Override
            public ThemeChoice load() {
                return ThemeChoice.LIGHT;
            }

            @Override
            public void save(ThemeChoice choice) {
            }
        };
    }
}
