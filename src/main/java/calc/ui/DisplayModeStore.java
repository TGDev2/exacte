package calc.ui;

import calc.format.DisplayMode;

/** Where the chosen display mode is remembered between two launches. Neither method throws. */
public interface DisplayModeStore {
    /** The remembered mode, {@link DisplayMode#AUTO} when there is none or when it cannot be read. */
    DisplayMode load();

    /** Remembers {@code mode}; a failure is logged and otherwise ignored. */
    void save(DisplayMode mode);

    /** A store that remembers nothing. */
    static DisplayModeStore noop() {
        return new DisplayModeStore() {
            @Override
            public DisplayMode load() {
                return DisplayMode.AUTO;
            }

            @Override
            public void save(DisplayMode mode) {
            }
        };
    }
}
