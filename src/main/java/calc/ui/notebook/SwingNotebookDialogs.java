package calc.ui.notebook;

import calc.document.DocumentService;
import java.awt.Component;
import java.io.File;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * The real dialogs of the file menu: {@link JFileChooser} for the files (filtered on {@code .calc.json} to open and save,
 * on {@code .md} or {@code .csv} to export) and {@link JOptionPane} for the questions. Nothing here decides anything: the
 * {@link NotebookPresenter} does, and a test scripts another {@link NotebookDialogs}.
 */
public final class SwingNotebookDialogs implements NotebookDialogs {
    private static final String SAVE = "Enregistrer";
    private static final String DISCARD = "Ne pas enregistrer";
    private static final String CANCEL = "Annuler";

    private Component parent;
    // Where the last chooser ended, so that the next one starts there.
    private File lastDirectory;

    /** The window the dialogs belong to; set once the window exists (it needs the presenter, which needs these dialogs). */
    public void setParent(Component parent) {
        this.parent = parent;
    }

    @Override
    public Optional<Path> chooseFileToOpen(Optional<Path> current) {
        JFileChooser chooser = chooser("Ouvrir un carnet", current.orElse(null), new NotebookFileFilter());
        return choice(chooser, chooser.showOpenDialog(parent));
    }

    @Override
    public Optional<Path> chooseFileToSave(Optional<Path> current) {
        JFileChooser chooser = chooser("Enregistrer le carnet sous", current.orElse(null), new NotebookFileFilter());
        current.map(path -> path.getFileName().toString()).ifPresent(name -> chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), name)));
        return choice(chooser, chooser.showSaveDialog(parent));
    }

    @Override
    public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
        String extension = format.extension().substring(1);
        JFileChooser chooser = chooser("Exporter en " + (format == ExportFormat.CSV ? "CSV" : "Markdown"), null,
                new FileNameExtensionFilter(format.description(), extension));
        chooser.setSelectedFile(new File(chooser.getCurrentDirectory(), suggested));
        return choice(chooser, chooser.showSaveDialog(parent));
    }

    @Override
    public Unsaved askToSave(String name, Action action) {
        Object[] options = {SAVE, DISCARD, CANCEL};
        int answer = JOptionPane.showOptionDialog(parent,
                "« " + name + " » a des modifications non enregistrées.\nVoulez-vous les enregistrer avant de " + action.sentence() + " ?",
                "Modifications non enregistrées", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, SAVE);
        return switch (answer) {
            case 0 -> Unsaved.SAVE;
            case 1 -> Unsaved.DISCARD;
            default -> Unsaved.CANCEL; // Cancel, or the dialog closed with Escape or its close button
        };
    }

    @Override
    public boolean confirmReplaceDraft() {
        return yesNo("Le carnet sans titre mis de côté par l'ouverture d'un fichier sera remplacé par un"
                + " carnet vide.\nContinuer ?", "Remplacer le carnet sans titre", "Remplacer");
    }

    @Override
    public boolean confirmOverwrite(Path file) {
        return yesNo("Le fichier « " + file.getFileName() + " » existe déjà.\nVoulez-vous le remplacer ?", "Remplacer le fichier", "Remplacer");
    }

    private boolean yesNo(String message, String title, String yes) {
        Object[] options = {yes, CANCEL};
        return JOptionPane.showOptionDialog(parent, message, title, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null,
                options, CANCEL) == 0;
    }

    private JFileChooser chooser(String title, Path current, FileFilter filter) {
        JFileChooser chooser = new JFileChooser(startDirectory(current));
        chooser.setDialogTitle(title);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setFileFilter(filter);
        return chooser;
    }

    private File startDirectory(Path current) {
        if (current != null && current.getParent() != null) {
            return current.getParent().toFile();
        }
        return lastDirectory;
    }

    private Optional<Path> choice(JFileChooser chooser, int result) {
        if (result != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return Optional.empty();
        }
        File file = chooser.getSelectedFile();
        lastDirectory = file.getParentFile();
        return Optional.of(file.toPath());
    }

    /** Directories and the files whose name ends with {@code .calc.json}. */
    private static final class NotebookFileFilter extends FileFilter {
        @Override
        public boolean accept(File file) {
            return file.isDirectory() || file.getName().toLowerCase(Locale.ROOT).endsWith(DocumentService.EXTENSION);
        }

        @Override
        public String getDescription() {
            return "Carnets Calc (*" + DocumentService.EXTENSION + ")";
        }
    }
}
