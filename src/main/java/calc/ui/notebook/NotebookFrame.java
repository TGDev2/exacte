package calc.ui.notebook;

import calc.format.DisplayMode;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookShortcuts.Command;
import calc.ui.widget.DisplayModeSelector;
import calc.ui.widget.DisplayModeText;
import calc.ui.widget.Palette;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;

/**
 * The main window: the notebook, one {@link NotebookRowView} per line plus the new line at the end. A view only: it shows
 * the {@link NotebookViewModel} the presenter returns and forwards what the user does (keys, menus, mouse) together with
 * the text now in the active field. The choice of this stack of rows over a table is in docs/adr/0010-editeur-carnet.md.
 */
public final class NotebookFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String NEXT_MODE_ACTION = "next-display-mode";
    private static final String CANCEL_EDIT_ACTION = "cancel-edit-or-calculation";
    // A notebook with more rows than this is shown over several events, so that the event thread keeps answering: building or
    // refilling 5000 rows at once takes more than a second (docs/adr/0014-calcul-hors-interface.md).
    private static final int ROWS_AT_ONCE = 50;
    // More lines than this with another text are another document: below the first rows, it builds its rows again.
    private static final int TEXTS_FILLED_AT_ONCE = 200;
    private static final long SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(15);

    // The frame is never serialized: the presenter is deliberately not Serializable.
    @SuppressWarnings("serial")
    private final NotebookPresenter presenter;
    private final transient NotebookShortcuts shortcuts;
    private final transient List<NotebookRowView> rows = new ArrayList<>();
    private final RowsPanel rowsPanel = new RowsPanel();
    private final JScrollPane scrollPane;
    private final JLabel statusLabel = new JLabel(" ");
    private final DisplayModeSelector modeSelector = new DisplayModeSelector();
    private final transient Map<DisplayMode, JRadioButtonMenuItem> modeItems = new EnumMap<>(DisplayMode.class);
    private final transient Action saveAction;
    private final transient Action undoAction;
    private final transient Action redoAction;

    private transient NotebookViewModel shown;
    private boolean applying;
    private boolean focusMoved;
    // Until the window is first shown, every row is built at once: there is nothing to keep answering for.
    private boolean built;
    // Whether an event is on its way to build the next rows.
    private boolean growing;

    public NotebookFrame(NotebookPresenter presenter) {
        this(presenter, new NotebookShortcuts(Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
    }

    /** @param shortcuts the keys of the window, with the menu modifier of the system (replaceable by a test) */
    public NotebookFrame(NotebookPresenter presenter, NotebookShortcuts shortcuts) {
        super("Calc — Carnet de calcul");
        this.presenter = Objects.requireNonNull(presenter, "presenter");
        this.shortcuts = Objects.requireNonNull(shortcuts, "shortcuts");
        saveAction = menuAction("Enregistrer", Command.SAVE);
        undoAction = menuAction("Annuler", Command.UNDO);
        redoAction = menuAction("Rétablir", Command.REDO);

        rowsPanel.setName("rowsPanel");
        rowsPanel.getAccessibleContext().setAccessibleName("Lignes du carnet");
        scrollPane = new JScrollPane(rowsPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setName("notebookScroll");
        scrollPane.getVerticalScrollBar().setUnitIncrement(24);
        scrollPane.getViewport().setBackground(NotebookPalette.ROW_BACKGROUND);
        scrollPane.getAccessibleContext().setAccessibleName("Carnet de calcul");
        scrollPane.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                // The empty space under the lines: go to the new line at the end.
                rows.get(rows.size() - 1).field.requestFocusInWindow();
            }
        });

        modeSelector.setName("modeSelector");
        modeSelector.onModeChosen(mode -> {
            if (!applying) {
                apply(presenter.onDisplayModeChanged(mode, pendingText()));
            }
        });
        JLabel modeLabel = new JLabel("Affichage");
        modeLabel.setLabelFor(modeSelector);
        modeLabel.setDisplayedMnemonic('A');
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JLabel title = new JLabel("Carnet de calcul");
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 3f));
        top.add(title, BorderLayout.WEST);
        JPanel modePanel = new JPanel(new BorderLayout(6, 0));
        modePanel.add(modeLabel, BorderLayout.WEST);
        modePanel.add(modeSelector, BorderLayout.CENTER);
        top.add(modePanel, BorderLayout.EAST);

        statusLabel.setName("statusLabel");
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(200, 200, 200)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scrollPane, BorderLayout.CENTER);
        getContentPane().add(statusLabel, BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());
        installModeShortcut();

        // Closing is the presenter's decision (a line being typed, unsaved changes), never the window's own.
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (shown != null) {
                    NotebookViewModel next = presenter.onQuit(pendingText());
                    apply(next);
                    if (next.closeWindow()) {
                        dispose();
                    }
                }
            }
        });
        setPreferredSize(new Dimension(940, 640));
        setMinimumSize(new Dimension(640, 360));
        pack();
        setLocationRelativeTo(null);

        presenter.setViewListener(this::applyCalculation);
        apply(presenter.initialView());
        built = true;
    }

    // ------------------------------------------------------------------------------------------------ menus

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();
        bar.add(menu("Fichier", KeyEvent.VK_F, 0, file()));
        bar.add(menu("Édition", KeyEvent.VK_E, 0, edit()));
        bar.add(menu("Affichage", KeyEvent.VK_C, 2, display()));
        bar.add(menu("Aide", KeyEvent.VK_I, 1, help()));
        return bar;
    }

    // A null item is a separator.
    private static JMenu menu(String text, int mnemonic, int mnemonicIndex, List<JMenuItem> items) {
        JMenu menu = new JMenu(text);
        menu.setMnemonic(mnemonic);
        menu.setDisplayedMnemonicIndex(mnemonicIndex);
        for (JMenuItem item : items) {
            if (item == null) {
                menu.addSeparator();
            } else {
                menu.add(item);
            }
        }
        return menu;
    }

    private List<JMenuItem> file() {
        List<JMenuItem> items = new ArrayList<>();
        items.add(item(menuAction("Nouveau", Command.NEW_DOCUMENT), Command.NEW_DOCUMENT, "newItem"));
        items.add(item(menuAction("Ouvrir…", Command.OPEN_DOCUMENT), Command.OPEN_DOCUMENT, "openItem"));
        items.add(null);
        items.add(item(saveAction, Command.SAVE, "saveItem"));
        items.add(item(menuAction("Enregistrer sous…", Command.SAVE_AS), Command.SAVE_AS, "saveAsItem"));
        items.add(null);
        items.add(item(exportAction("Exporter en Markdown…", NotebookDialogs.ExportFormat.MARKDOWN), null, "exportMarkdownItem"));
        items.add(item(exportAction("Exporter en CSV…", NotebookDialogs.ExportFormat.CSV), null, "exportCsvItem"));
        items.add(null);
        items.add(item(new AbstractAction("Quitter") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                // The same path as the close button: the presenter asks about unsaved changes, then the window closes.
                dispatchEvent(new WindowEvent(NotebookFrame.this, WindowEvent.WINDOW_CLOSING));
            }
        }, null, "quitItem"));
        return items;
    }

    private Action exportAction(String text, NotebookDialogs.ExportFormat format) {
        return new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                apply(presenter.onExport(format, pendingText()));
            }
        };
    }

    private List<JMenuItem> edit() {
        List<JMenuItem> items = new ArrayList<>();
        items.add(item(undoAction, Command.UNDO, "undoItem"));
        items.add(item(redoAction, Command.REDO, "redoItem"));
        items.add(null);
        items.add(item(menuAction("Insérer une ligne au-dessus", Command.INSERT_ABOVE), Command.INSERT_ABOVE, "insertAboveItem"));
        items.add(item(menuAction("Insérer une ligne au-dessous", Command.INSERT_BELOW), Command.INSERT_BELOW, "insertBelowItem"));
        items.add(item(menuAction("Supprimer la ligne", Command.DELETE_LINE), Command.DELETE_LINE, "deleteItem"));
        items.add(item(menuAction("Monter la ligne", Command.MOVE_UP), Command.MOVE_UP, "moveUpItem"));
        items.add(item(menuAction("Descendre la ligne", Command.MOVE_DOWN), Command.MOVE_DOWN, "moveDownItem"));
        items.add(null);
        items.add(item(new AbstractAction("Tout effacer") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                apply(presenter.onClear(pendingText()));
            }
        }, null, "clearItem"));
        return items;
    }

    private List<JMenuItem> display() {
        List<JMenuItem> items = new ArrayList<>();
        ButtonGroup group = new ButtonGroup();
        for (DisplayMode mode : DisplayMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(DisplayModeText.label(mode));
            item.setName("modeItem" + mode.name());
            item.addActionListener(event -> {
                if (!applying) {
                    apply(presenter.onDisplayModeChanged(mode, pendingText()));
                }
            });
            group.add(item);
            modeItems.put(mode, item);
            items.add(item);
        }
        items.add(null);
        JMenuItem next = new JMenuItem("Mode suivant");
        next.setName("nextModeItem");
        next.setAccelerator(shortcuts.stroke(Command.NEXT_MODE));
        next.addActionListener(event -> apply(presenter.onNextDisplayMode(pendingText())));
        items.add(next);
        return items;
    }

    private List<JMenuItem> help() {
        List<JMenuItem> items = new ArrayList<>();
        JMenuItem syntax = new JMenuItem("Syntaxe des expressions");
        syntax.setName("syntaxItem");
        syntax.addActionListener(event -> showHelp("Syntaxe des expressions", NotebookHelp.syntax()));
        JMenuItem keys = new JMenuItem("Raccourcis clavier");
        keys.setName("shortcutsItem");
        keys.addActionListener(event -> showHelp("Raccourcis clavier", NotebookHelp.shortcuts(shortcuts)));
        items.add(syntax);
        items.add(keys);
        return items;
    }

    private JMenuItem item(Action action, Command command, String name) {
        JMenuItem item = new JMenuItem(action);
        item.setName(name);
        if (command != null) {
            item.setAccelerator(shortcuts.stroke(command));
        }
        return item;
    }

    // An item of a menu: disabled when there is nothing to undo or redo. The keys of the fields do not use this action.
    private Action menuAction(String text, Command command) {
        return new AbstractAction(text) {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                perform(command);
            }
        };
    }

    private void showHelp(String title, String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setCaretPosition(0);
        area.setMargin(new java.awt.Insets(8, 10, 8, 10));
        area.getAccessibleContext().setAccessibleName(title);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 440));
        JOptionPane.showMessageDialog(this, scroll, title, JOptionPane.PLAIN_MESSAGE);
    }

    // ------------------------------------------------------------------------------------------------ keys

    private void installModeShortcut() {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shortcuts.stroke(Command.NEXT_MODE), NEXT_MODE_ACTION);
        getRootPane().getActionMap().put(NEXT_MODE_ACTION, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                perform(Command.NEXT_MODE);
            }
        });
        // A line binds Escape itself. With the focus elsewhere (the selector of the modes), Escape still interrupts a calculation.
        // An open list of the selector keeps the key: its own action is enabled then, and Swing looks no further.
        JComponent content = (JComponent) getContentPane();
        content.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(shortcuts.stroke(Command.CANCEL_EDIT), CANCEL_EDIT_ACTION);
        content.getActionMap().put(CANCEL_EDIT_ACTION, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                perform(Command.CANCEL_EDIT);
            }
        });
    }

    // The keys of a line are bound in the input map of its own field, so that they win over anything the look and feel
    // binds for a text field (NotebookShortcutConflictTest lists what it binds). These actions are always enabled.
    private void bindKeys(NotebookRowView row) {
        for (Command command : Command.values()) {
            if (command == Command.NEXT_MODE) {
                continue;
            }
            String key = "notebook-" + command.name();
            row.field.getInputMap(JComponent.WHEN_FOCUSED).put(shortcuts.stroke(command), key);
            row.field.getActionMap().put(key, new AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(ActionEvent event) {
                    perform(command);
                }
            });
        }
    }

    private void perform(Command command) {
        switch (command) {
            case ENTER -> apply(presenter.onEnter(pendingText()));
            case PREVIOUS -> apply(presenter.onMove(-1, pendingText()));
            case NEXT -> apply(presenter.onMove(1, pendingText()));
            case CANCEL_EDIT -> apply(presenter.onCancelEdit(pendingText()));
            case UNDO -> apply(presenter.onUndo(pendingText()));
            case REDO, REDO_ALTERNATE -> apply(presenter.onRedo(pendingText()));
            case INSERT_BELOW -> apply(presenter.onInsertBelow(pendingText()));
            case INSERT_ABOVE -> apply(presenter.onInsertAbove(pendingText()));
            case DELETE_LINE -> apply(presenter.onDelete(pendingText()));
            case MOVE_UP -> apply(presenter.onMoveLineUp(pendingText()));
            case MOVE_DOWN -> apply(presenter.onMoveLineDown(pendingText()));
            case NEXT_MODE -> apply(presenter.onNextDisplayMode(pendingText()));
            case NEW_DOCUMENT -> apply(presenter.onNew(pendingText()));
            case OPEN_DOCUMENT -> apply(presenter.onOpen(pendingText()));
            case SAVE -> apply(presenter.onSave(pendingText()));
            case SAVE_AS -> apply(presenter.onSaveAs(pendingText()));
        }
    }

    // ------------------------------------------------------------------------------------------------ view model

    // The text of the active field: what the presenter keeps as the draft of the active line.
    private String pendingText() {
        return rows.get(shown.activeIndex()).field.getText();
    }

    // A state the calculation produced, not the answer to something the user did: the line being typed keeps its text (the
    // presenter only learns it with an intention), and neither the caret nor the scroll bar moves. Only what changed is touched:
    // this runs for each line calculated, with up to 5001 rows.
    private void applyCalculation(NotebookViewModel viewModel) {
        applying = true;
        try {
            if (shown == null || shown.rows().size() != viewModel.rows().size() || shown.activeIndex() != viewModel.activeIndex()) {
                // A dialog was open in the middle of an intention: nothing here is typed, the view catches up.
                showRows(viewModel);
                rowsPanel.revalidate();
            } else {
                for (int i = 0; i < rows.size(); i++) {
                    NotebookRow row = viewModel.rows().get(i);
                    if (i == viewModel.activeIndex()) {
                        NotebookRowView active = rows.get(i);
                        active.show(row, true, active.field.getText(), viewModel.watermark());
                    } else if (!row.equals(shown.rows().get(i))) {
                        rows.get(i).show(row, false, row.text(), "");
                    }
                }
            }
            showWindowState(viewModel);
        } finally {
            applying = false;
        }
    }

    private void apply(NotebookViewModel viewModel) {
        applying = true;
        try {
            // The field of a row that does not exist yet holds nothing, as a new field does.
            String before = viewModel.activeIndex() < rows.size() ? rows.get(viewModel.activeIndex()).field.getText() : "";
            boolean moved = shown == null || shown.activeIndex() != viewModel.activeIndex();
            showRows(viewModel);
            showWindowState(viewModel);
            // The caret only moves when another line is active or the text of the active field was replaced.
            focusMoved = moved || !before.equals(rows.get(viewModel.activeIndex()).field.getText());
        } finally {
            applying = false;
        }
        rowsPanel.revalidate();
        rowsPanel.repaint();
        if (viewModel.focus() == NotebookFocus.ACTIVE_LINE) {
            NotebookRowView active = rows.get(viewModel.activeIndex());
            active.field.requestFocusInWindow();
            if (focusMoved) {
                active.field.setCaretPosition(active.field.getText().length());
            }
        }
        scrollToActive();
    }

    // Every row that exists shows the state of now: the rows still to build are simply not there yet, never an old line.
    private void showRows(NotebookViewModel viewModel) {
        int size = viewModel.rows().size();
        int atOnce = built ? Math.min(size, Math.max(ROWS_AT_ONCE, viewModel.activeIndex() + 1)) : size;
        // Another document: the rows below are dropped and built again by slices, rather than filled again all at once.
        removeRowsFrom(built && isAnotherDocument(viewModel) ? atOnce : size);
        while (rows.size() < atOnce) {
            addRow();
        }
        for (int i = 0; i < rows.size(); i++) {
            NotebookRow row = viewModel.rows().get(i);
            boolean active = i == viewModel.activeIndex();
            rows.get(i).show(row, active, active ? viewModel.activeDraft() : row.text(), active ? viewModel.watermark() : "");
        }
    }

    private void removeRowsFrom(int count) {
        while (rows.size() > count) {
            rows.remove(rows.size() - 1);
            rowsPanel.remove(rows.size());
        }
    }

    // Whether so many lines hold another text that this is no longer the same document.
    private boolean isAnotherDocument(NotebookViewModel viewModel) {
        if (shown == null) {
            return false;
        }
        int common = Math.min(shown.rows().size(), viewModel.rows().size());
        int different = Math.abs(shown.rows().size() - viewModel.rows().size());
        for (int i = 0; i < common && different <= TEXTS_FILLED_AT_ONCE; i++) {
            if (!shown.rows().get(i).text().equals(viewModel.rows().get(i).text())) {
                different++;
            }
        }
        return different > TEXTS_FILLED_AT_ONCE;
    }

    // The next rows, for a few milliseconds, then the events waiting: the window answers while a large notebook appears.
    private void growRows() {
        growing = false;
        long deadline = System.nanoTime() + SLICE_NANOS;
        applying = true;
        try {
            while (rows.size() < shown.rows().size() && System.nanoTime() < deadline) {
                NotebookRow row = shown.rows().get(rows.size());
                addRow();
                rows.get(rows.size() - 1).show(row, false, row.text(), "");
            }
        } finally {
            applying = false;
        }
        rowsPanel.revalidate();
        growLater();
    }

    private void growLater() {
        if (!growing && rows.size() < shown.rows().size()) {
            growing = true;
            SwingUtilities.invokeLater(this::growRows);
        }
    }

    private void showWindowState(NotebookViewModel viewModel) {
        setTitle(viewModel.title());
        saveAction.setEnabled(viewModel.canSave());
        undoAction.setEnabled(viewModel.canUndo());
        redoAction.setEnabled(viewModel.canRedo());
        modeSelector.showMode(viewModel.displayMode());
        modeItems.get(viewModel.displayMode()).setSelected(true);
        showStatus(viewModel.statusMessage(), viewModel.statusTone());
        shown = viewModel;
        growLater();
    }

    private void addRow() {
        NotebookRowView row = new NotebookRowView(rows.size());
        row.field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                // The user went to this line with the mouse or Tab: the line left is validated.
                if (!applying && shown != null && row.index != shown.activeIndex()) {
                    apply(presenter.onActivate(row.index, pendingText()));
                }
            }
        });
        bindKeys(row);
        rows.add(row);
        rowsPanel.add(row);
    }

    private void scrollToActive() {
        int index = shown.activeIndex();
        SwingUtilities.invokeLater(() -> {
            if (index < rows.size()) {
                Rectangle bounds = rows.get(index).getBounds();
                rowsPanel.scrollRectToVisible(bounds);
            }
        });
    }

    private void showStatus(String message, StatusTone tone) {
        statusLabel.setText(message);
        statusLabel.setToolTipText(message);
        statusLabel.setForeground(switch (tone) {
            case SUCCESS -> Palette.SUCCESS;
            case ERROR -> Palette.ERROR;
            case INFO -> Palette.INFO;
        });
        statusLabel.getAccessibleContext().setAccessibleName("État");
        statusLabel.getAccessibleContext().setAccessibleDescription(message);
    }

    // ------------------------------------------------------------------------------------------------ for the tests

    /** Whether a calculation is under way or rows are still to build: a test waits for the end. */
    boolean isCalculating() {
        return presenter.isCalculating() || growing;
    }

    /** The view model now shown. */
    NotebookViewModel shown() {
        return shown;
    }

    NotebookRowView row(int index) {
        return rows.get(index);
    }

    int rowCount() {
        return rows.size();
    }

    JLabel statusLabel() {
        return statusLabel;
    }

    DisplayModeSelector modeSelector() {
        return modeSelector;
    }

    /** The panel of the rows: it follows the width of the window and scrolls vertically. */
    private static final class RowsPanel extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        RowsPanel() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(NotebookPalette.ROW_BACKGROUND);
            setOpaque(true);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(900, 480);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 32;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(32, visible.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
