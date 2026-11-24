package calc.ui.notebook;

import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.LoadedNotebook;
import calc.persistence.NotebookStore;
import calc.ui.DisplayModeStore;
import calc.ui.design.Theme;
import calc.ui.theme.ThemeManager;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.swing.JRootPane;
import javax.swing.SwingUtilities;

/**
 * Paints {@link NotebookFrame} off screen, for the pictures of the documentation: the window is built as {@code Main} builds it,
 * on a notebook of a temporary data directory (never the data of the user), made displayable but never shown, painted with
 * {@code printAll} into an image, then disposed.
 *
 * <p>It lives in the test source set, in the package of the window, to reach what the tests reach ({@code isCalculating()},
 * {@code row(i)}, {@code statusLabel()}), and is run by {@code gradlew screenshots}. The window has the theme asked and the
 * embedded fonts of the application: two runs on the same machine give the same pixels.
 */
public final class ScreenshotGenerator {

    /** The "Devis cuisine" of the README (section "Exemples de carnet"), no-break spaces included. */
    public static final List<String> DEMO = List.of(
            "# Devis cuisine",
            "plan = 1 250,00          # plan de travail (espace insécable)",
            "meubles = 3 480",
            "pose = 35 * 12,5",
            "total = plan + meubles + pose     # 5 167,5",
            "taux = 0,2",
            "total * (1 + taux)       # TTC : 6 201",
            "ans / 12                 # mensualité sur un an : 516,75"
    );

    static final int WIDTH = 1100;
    static final int HEIGHT = 720;
    private static final String LIGHT = "clair";
    private static final String DARK = "sombre";
    private static final String BOTH = "tous";
    private static final long PATIENCE_SECONDS = 10;

    private ScreenshotGenerator() {
    }

    /**
     * {@code main(dossier, thème, échelle)}: writes {@code carnet-devis.png} (the {@link #DEMO}, the total line active) and
     * {@code carnet-vide.png} (an empty notebook) into {@code dossier}, in 1100 × 720 times the scale, in the theme
     * {@code clair} or {@code sombre}. The theme {@code tous} writes both themes, the names ending in {@code -clair} and
     * {@code -sombre}.
     */
    public static void main(String... args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage : ScreenshotGenerator <dossier> <thème : " + LIGHT + ", " + DARK + " ou " + BOTH + "> <échelle>");
        }
        Path folder = Path.of(args[0]);
        // The name suffix of each theme to paint: none for a single theme.
        Map<Theme, String> themes = new LinkedHashMap<>();
        switch (args[1]) {
            case LIGHT -> themes.put(Theme.LIGHT, "");
            case DARK -> themes.put(Theme.DARK, "");
            case BOTH -> {
                themes.put(Theme.LIGHT, "-" + LIGHT);
                themes.put(Theme.DARK, "-" + DARK);
            }
            default -> throw new IllegalArgumentException(
                    "Thème « " + args[1] + " » inconnu : « " + LIGHT + " », « " + DARK + " » ou « " + BOTH + " ».");
        }
        double scale = Double.parseDouble(args[2]);
        if (!(scale > 0)) {
            throw new IllegalArgumentException("L'échelle doit être un nombre positif : " + args[2]);
        }
        for (Map.Entry<Theme, String> theme : themes.entrySet()) {
            BufferedImage quote = render(DEMO, 5, WIDTH, HEIGHT, scale, theme.getKey());
            BufferedImage empty = render(List.of(), 1, WIDTH, HEIGHT, scale, theme.getKey());
            Files.createDirectories(folder);
            write(quote, folder.resolve("carnet-devis" + theme.getValue() + ".png"));
            write(empty, folder.resolve("carnet-vide" + theme.getValue() + ".png"));
        }
    }

    /** Same as {@link #render(List, int, int, int, double, Theme)} in the light theme. */
    public static BufferedImage render(List<String> lines, int activeLine, int width, int height, double scale) throws Exception {
        return render(lines, activeLine, width, height, scale, Theme.LIGHT);
    }

    /**
     * Paints the notebook of {@code lines} in {@code theme} with the line numbered {@code activeLine} (from 1) active; a number out
     * of range keeps the blank line at the end active. The image is {@code round(width × scale)} by {@code round(height × scale)}.
     * No window is ever visible, and none stays displayable.
     */
    public static BufferedImage render(List<String> lines, int activeLine, int width, int height, double scale, Theme theme)
            throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("render waits for the event thread: call it from another thread");
        }
        // As Main: the embedded fonts and the theme, on the event thread before the window is built (ADR 0017).
        onEventThread(() -> {
            ThemeManager.installFonts();
            ThemeManager.apply(theme);
        });
        Path data = Files.createTempDirectory("calc-screenshots");
        NotebookStore store = NotebookStore.open(data);
        AtomicReference<NotebookFrame> holder = new AtomicReference<>();
        try {
            // The notebook is opened in one piece, as at a start: one calculation, not one per line entered.
            if (!lines.isEmpty()) {
                store.saveCurrent(Notebook.of(lines), DisplayMode.AUTO);
            }
            LoadedNotebook loaded = store.load();
            onEventThread(() -> {
                NotebookPresenter presenter = NotebookPresenter.open(store, loaded, DisplayModeStore.noop(),
                        NotebookDialogs.cancelling(), NotebookPresenter.newRecalculationExecutor());
                // The same path as a click, the field left handing over its own text; a number out of range activates the
                // blank line at the end.
                boolean inRange = activeLine >= 1 && activeLine <= lines.size();
                presenter.onActivate(inRange ? activeLine - 1 : lines.size(), presenter.currentView().activeDraft());
                // The JDK lists a window as visible while its constructor runs, and Window.getWindows() takes the lock of
                // Window.class: holding it keeps that moment from being seen, so that no window is ever observed visible.
                NotebookFrame frame;
                synchronized (Window.class) {
                    frame = new NotebookFrame(presenter);
                }
                holder.set(frame);
                frame.pack();
                // The picture is the root pane (menu bar included), without the title bar the system draws around it.
                Insets border = frame.getInsets();
                frame.setSize(width + border.left + border.right, height + border.top + border.bottom);
            });
            NotebookFrame frame = holder.get();
            awaitSettled(frame);
            BufferedImage image = new BufferedImage(
                    (int) Math.round(width * scale), (int) Math.round(height * scale), BufferedImage.TYPE_INT_RGB);
            onEventThread(() -> paint(frame, image, scale));
            return image;
        } finally {
            NotebookFrame frame = holder.get();
            if (frame != null) {
                onEventThread(frame::dispose);
            }
            store.close();
            deleteTree(data);
        }
    }

    private static void paint(NotebookFrame frame, BufferedImage image, double scale) {
        frame.validate();
        for (int i = 0; i < frame.rowCount(); i++) {
            // A blinking caret would make two pictures differ.
            frame.row(i).field.getCaret().setBlinkRate(0);
            frame.row(i).field.getCaret().setVisible(false);
        }
        JRootPane root = frame.getRootPane();
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.scale(scale, scale);
            root.printAll(graphics);
        } finally {
            graphics.dispose();
        }
    }

    /** Waits, up to {@value #PATIENCE_SECONDS} s, until nothing is being calculated, every row is built and the status says so. */
    private static void awaitSettled(NotebookFrame frame) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);
        while (!isSettled(frame)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The notebook is still being calculated after " + PATIENCE_SECONDS + " s");
            }
            Thread.sleep(2);
        }
    }

    private static boolean isSettled(NotebookFrame frame) throws Exception {
        AtomicReference<Boolean> settled = new AtomicReference<>(false);
        onEventThread(() -> settled.set(!frame.isCalculating()
                && frame.rowCount() == frame.shown().rows().size()
                && !frame.statusLabel().getText().contains("Calcul en cours")));
        return settled.get();
    }

    private static void onEventThread(Runnable work) throws Exception {
        try {
            SwingUtilities.invokeAndWait(work);
        } catch (InvocationTargetException failed) {
            if (failed.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw failed;
        }
    }

    private static void write(BufferedImage image, Path file) throws IOException {
        if (!ImageIO.write(image, "png", file.toFile())) {
            throw new IOException("No PNG writer for " + file);
        }
    }

    private static void deleteTree(Path folder) {
        // Best effort: a temporary folder left behind must not fail a picture.
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException ignored) {
            // the operating system cleans its temporary folder
        }
    }
}
