package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.nio.file.Path;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// Acceptance of E05: the version is readable in Aide > À propos de Calc.
class AboutVersionAcceptanceTest {

    private static final String DEVELOPMENT = "Version de développement";

    @TempDir
    Path data;

    @Test
    @DisplayName("une version renseignée s'affiche « Version x.y.z »")
    void aKnownVersionIsShown() {
        assertEquals("Version 1.0.0", AppVersion.text("1.0.0"));
    }

    @Test
    @DisplayName("une version absente, vide ou blanche donne « Version de développement »")
    void anUnknownVersionIsADevelopmentVersion() {
        assertEquals(DEVELOPMENT, AppVersion.text(null));
        assertEquals(DEVELOPMENT, AppVersion.text(""));
        assertEquals(DEVELOPMENT, AppVersion.text("   "));
    }

    @Test
    @DisplayName("la version courante vient de la propriété jpackage.app-version")
    void theCurrentVersionReadsTheJpackageProperty() {
        assertEquals(AppVersion.text(System.getProperty("jpackage.app-version")), AppVersion.current());
    }

    @Test
    @DisplayName("le texte « À propos » nomme Calc et donne la version")
    void theAboutTextNamesCalcAndTheVersion() {
        String about = NotebookHelp.about("Version 1.0.0");
        assertTrue(about.contains("Calc"), about);
        assertTrue(about.contains("Version 1.0.0"), about);
    }

    @Test
    @Tag("ui")
    @DisplayName("le menu Aide finit par « À propos de Calc » (aboutItem), relié à une action")
    void theHelpMenuEndsWithTheAboutItem() throws Exception {
        boolean headless = GraphicsEnvironment.isHeadless();
        if (headless && Boolean.getBoolean("calc.ui.required")) {
            fail("UI tests require a graphical environment (-PrequireUiTests=true): no display is available, so the ui tests cannot run.");
        }
        assumeFalse(headless, "UI tests require a graphical environment.");
        NotebookStore store = NotebookStore.open(data);
        NotebookFrame[] holder = new NotebookFrame[1];
        try {
            int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
            SwingUtilities.invokeAndWait(() -> holder[0] = new NotebookFrame(
                    NotebookPresenter.open(store, DisplayModeStore.noop()), new NotebookShortcuts(mask)));
            JMenu help = null;
            for (int i = 0; i < holder[0].getJMenuBar().getMenuCount(); i++) {
                JMenu menu = holder[0].getJMenuBar().getMenu(i);
                if ("Aide".equals(menu.getText())) {
                    help = menu;
                }
            }
            assertNotNull(help, "the Aide menu");
            JMenuItem last = help.getItem(help.getItemCount() - 1);
            assertNotNull(last);
            assertEquals("aboutItem", last.getName());
            assertEquals("À propos de Calc", last.getText());
            assertTrue(last.getActionListeners().length >= 1, "the item must do something");
        } finally {
            if (holder[0] != null) {
                SwingUtilities.invokeAndWait(holder[0]::dispose);
            }
            store.close();
        }
    }
}
