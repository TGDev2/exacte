package calc;

import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.PreferencesDisplayModeStore;
import calc.ui.notebook.NotebookFrame;
import calc.ui.notebook.NotebookPresenter;
import calc.ui.notebook.SwingNotebookDialogs;
import java.awt.Desktop;
import java.awt.desktop.QuitStrategy;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {
        // Opens the data directory (taking the lock that makes a second instance read only), migrates the old history
        // into a notebook on the first start and loads the current notebook. The system releases the lock with the
        // process; releasing it on a normal exit is only tidier. The migration evaluates each old entry: this is not the
        // event thread, where no expression is ever evaluated (docs/adr/0014-calcul-hors-interface.md).
        NotebookStore store = NotebookStore.openForCurrentUser();
        Runtime.getRuntime().addShutdownHook(new Thread(store::close, "notebook-lock-release"));
        LoadedNotebook loaded = store.load();
        SwingUtilities.invokeLater(() -> {
            useSystemLookAndFeel();
            closeWindowsOnQuit();
            NotebookFrame frame = createWindow(store, loaded, PreferencesDisplayModeStore.forCurrentUser());
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosed(WindowEvent event) {
                    System.exit(0);
                }
            });
            frame.setVisible(true);
        });
    }

    /** The main window on the notebook {@code store} has loaded, with the old history migrated into it on the first start. */
    static NotebookFrame createWindow(NotebookStore store, LoadedNotebook loaded, DisplayModeStore modeStore) {
        // The dialogs belong to the window, which needs the presenter, which needs the dialogs: the window is given last.
        SwingNotebookDialogs dialogs = new SwingNotebookDialogs();
        // One background thread calculates the notebook; the event thread only ever receives its results.
        NotebookFrame frame = new NotebookFrame(NotebookPresenter.open(store, loaded, modeStore, dialogs, NotebookPresenter.newRecalculationExecutor()));
        dialogs.setParent(frame);
        return frame;
    }

    // Cmd+Q on macOS quits without closing the windows by default: close them, so that a line being typed is validated.
    private static void closeWindowsOnQuit() {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_QUIT_STRATEGY)) {
            Desktop.getDesktop().setQuitStrategy(QuitStrategy.CLOSE_ALL_WINDOWS);
        }
    }

    private static void useSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Cannot apply the system look and feel, using the default one", exception);
        }
    }
}
