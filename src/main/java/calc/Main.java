package calc;

import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.PreferencesDisplayModeStore;
import calc.ui.PreferencesThemeStore;
import calc.ui.ThemeStore;
import calc.ui.notebook.NotebookFrame;
import calc.ui.notebook.NotebookPresenter;
import calc.ui.notebook.SwingNotebookDialogs;
import calc.ui.theme.SystemThemeDetector;
import calc.ui.theme.ThemeManager;
import calc.ui.theme.WindowsSystemThemeDetector;
import java.awt.Desktop;
import java.awt.desktop.QuitStrategy;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import javax.swing.SwingUtilities;

public class Main {
    public static void main(String[] args) {
        // Opens the data directory (taking the lock that makes a second instance read only), migrates the old history
        // into a notebook on the first start and loads the current notebook. The system releases the lock with the
        // process; releasing it on a normal exit is only tidier. The migration evaluates each old entry: this is not the
        // event thread, where no expression is ever evaluated (docs/adr/0014-calcul-hors-interface.md).
        NotebookStore store = NotebookStore.openForCurrentUser();
        Runtime.getRuntime().addShutdownHook(new Thread(store::close, "notebook-lock-release"));
        LoadedNotebook loaded = store.load();
        // The theme of the system is read once, here: reg query may take a moment, never on the event thread.
        ThemeStore themeStore = PreferencesThemeStore.forCurrentUser();
        SystemThemeDetector systemTheme = new WindowsSystemThemeDetector();
        SwingUtilities.invokeLater(() -> {
            // The embedded fonts and the remembered theme, on the event thread before the window is built (ADR 0017).
            ThemeManager.installFonts();
            ThemeManager.apply(NotebookPresenter.effectiveTheme(themeStore.load(), systemTheme));
            closeWindowsOnQuit();
            NotebookFrame frame = createWindow(store, loaded, PreferencesDisplayModeStore.forCurrentUser(), themeStore, systemTheme);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    System.exit(0);
                }
            });
            frame.setVisible(true);
        });
    }

    /** Same as the main window, without remembering the theme nor reading the one of the system. */
    static NotebookFrame createWindow(NotebookStore store, LoadedNotebook loaded, DisplayModeStore modeStore) {
        return createWindow(store, loaded, modeStore, ThemeStore.noop(), SystemThemeDetector.none());
    }

    /**
     * The main window on the notebook {@code store} has loaded, with the old history migrated into it on the first start, the
     * theme chosen in {@code themeStore}; the caller has applied that theme already.
     */
    static NotebookFrame createWindow(NotebookStore store, LoadedNotebook loaded, DisplayModeStore modeStore, ThemeStore themeStore,
                                      SystemThemeDetector systemTheme) {
        // The dialogs belong to the window, which needs the presenter, which needs the dialogs: the window is given last.
        SwingNotebookDialogs dialogs = new SwingNotebookDialogs();
        // One background thread calculates the notebook; the event thread only ever receives its results.
        NotebookPresenter presenter = NotebookPresenter.open(store, loaded, modeStore, themeStore, systemTheme, dialogs,
                NotebookPresenter.newRecalculationExecutor());
        NotebookFrame frame = new NotebookFrame(presenter);
        dialogs.setParent(frame);
        return frame;
    }

    // Cmd+Q on macOS quits without closing the windows by default: close them, so that a line being typed is validated.
    private static void closeWindowsOnQuit() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
            Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
        }
    }
}
