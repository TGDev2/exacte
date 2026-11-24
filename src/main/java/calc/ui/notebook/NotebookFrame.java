package calc.ui.notebook;

import calc.format.DisplayMode;
import calc.ui.StatusTone;
import calc.ui.ThemeChoice;
import calc.ui.design.DesignTokens;
import calc.ui.notebook.NotebookShortcuts.Command;
import calc.ui.theme.ThemeManager;
import calc.ui.widget.DisplayModeSelector;
import calc.ui.widget.DisplayModeText;
import calc.ui.widget.Palette;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.KeyboardFocusManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
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
import javax.swing.JLayeredPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The main window: the notebook, one {@link NotebookRowView} per line plus the new line at the end. A view only: it shows
 * the {@link NotebookViewModel} the presenter returns and forwards what the user does (keys, menus, mouse) together with
 * the text now in the active field. The choice of this stack of rows over a table is in docs/adr/0010-editeur-carnet.md.
 */
public final class NotebookFrame extends JFrame {
    private static final long serialVersionUID = 1L;
    private static final String NEXT_MODE_ACTION = "next-display-mode";
    private static final String CANCEL_EDIT_ACTION = "cancel-edit-or-calculation";
    private static final String COMMAND_PALETTE_ACTION = "command-palette";
    private static final String COMMAND_PALETTE_ITEM = "commandPaletteItem";
    // The width of the command palette, before the scale of the screen; a narrow window gets a narrower one.
    private static final int PALETTE_WIDTH = 560;
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
    // The one detail of the window, under the active line.
    private final LineDetailView lineDetail = new LineDetailView();
    private final JScrollPane scrollPane;
    private final JLabel statusLabel = new JLabel(" ");
    private final DisplayModeSelector modeSelector = new DisplayModeSelector();
    private final transient Map<DisplayMode, JRadioButtonMenuItem> modeItems = new EnumMap<>(DisplayMode.class);
    private final transient Map<ThemeChoice, JRadioButtonMenuItem> themeItems = new EnumMap<>(ThemeChoice.class);
    private final transient Action saveAction;
    private final transient Action undoAction;
    private final transient Action redoAction;
    private final CommandPaletteView commandPalette;
    // Where the focus was when the palette opened: it goes back there when the palette closes.
    private transient Component focusBeforePalette;

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
        scrollPane.getViewport().setBackground(NotebookPalette.active().rowBackground());
        scrollPane.getAccessibleContext().setAccessibleName("Carnet de calcul");
        scrollPane.getViewport().addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                // The empty space under the lines: go to the new line at the end, which does not exist yet while the rows appear.
                if (!growing) {
                    rows.get(rows.size() - 1).field.requestFocusInWindow();
                }
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
        // The title of the window names the document: the header only holds the display modes.
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JPanel modePanel = new JPanel(new BorderLayout(8, 0));
        modePanel.add(modeLabel, BorderLayout.WEST);
        modePanel.add(modeSelector, BorderLayout.CENTER);
        top.add(modePanel, BorderLayout.WEST);

        statusLabel.setName("statusLabel");
        frameStatusBar();

        getContentPane().add(top, BorderLayout.NORTH);
        getContentPane().add(scrollPane, BorderLayout.CENTER);
        getContentPane().add(statusLabel, BorderLayout.SOUTH);
        setJMenuBar(createMenuBar());
        installModeShortcut();
        commandPalette = new CommandPaletteView(new CommandPaletteView.Listener() {
            @Override
            public void run(CommandPalette.Entry entry) {
                runFromPalette(entry);
            }

            @Override
            public void dismiss(boolean giveFocusBack) {
                closeCommandPalette(giveFocusBack);
            }
        });
        installCommandPalette();

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
        items.add(null);
        items.add(themeMenu());
        return items;
    }

    // Affichage > Thème: Clair, Sombre, and Système only where the theme of the system can be read (ADR 0017).
    private JMenu themeMenu() {
        JMenu menu = new JMenu("Thème");
        menu.setName("themeMenu");
        ButtonGroup group = new ButtonGroup();
        boolean system = presenter.currentView().systemThemeAvailable();
        for (ThemeChoice choice : ThemeChoice.values()) {
            if (choice == ThemeChoice.SYSTEM && !system) {
                continue;
            }
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(choice.label());
            item.setName("themeItem" + choice.name());
            item.addActionListener(event -> {
                if (!applying) {
                    applyTheme(presenter.onThemeChanged(choice, pendingText()));
                }
            });
            group.add(item);
            themeItems.put(choice, item);
            menu.add(item);
        }
        return menu;
    }

    // The same window, its rows and what is typed kept: the look and feel changes and every component reads its colours again.
    private void applyTheme(NotebookViewModel viewModel) {
        apply(viewModel);
        ThemeManager.apply(viewModel.effectiveTheme());
        scrollPane.getViewport().setBackground(NotebookPalette.active().rowBackground());
        frameStatusBar();
        showStatus(shown.statusMessage(), shown.statusTone());
    }

    private void frameStatusBar() {
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, NotebookPalette.active().border()),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
    }

    private List<JMenuItem> help() {
        List<JMenuItem> items = new ArrayList<>();
        items.add(item(new AbstractAction("Palette de commandes…") {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                openCommandPalette();
            }
        }, Command.COMMAND_PALETTE, COMMAND_PALETTE_ITEM));
        JMenuItem syntax = new JMenuItem("Syntaxe des expressions");
        syntax.setName("syntaxItem");
        syntax.addActionListener(event -> showHelp("Syntaxe des expressions", NotebookHelp.syntax()));
        JMenuItem keys = new JMenuItem("Raccourcis clavier");
        keys.setName("shortcutsItem");
        keys.addActionListener(event -> showHelp("Raccourcis clavier", NotebookHelp.shortcuts(shortcuts)));
        items.add(syntax);
        JMenuItem about = new JMenuItem("À propos de Calc");
        about.setName("aboutItem");
        about.addActionListener(event -> showHelp("À propos de Calc", NotebookHelp.about(AppVersion.current())));
        items.add(keys);
        items.add(about);
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
        area.setFont(new Font(DesignTokens.load().fontFamily("mono"), Font.PLAIN, 13));
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
        // Ctrl+K from anywhere in the window, the selector of the modes included (a line binds it in its own field as well).
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(shortcuts.stroke(Command.COMMAND_PALETTE), COMMAND_PALETTE_ACTION);
        getRootPane().getActionMap().put(COMMAND_PALETTE_ACTION, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                perform(Command.COMMAND_PALETTE);
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
            case COMMAND_PALETTE -> openCommandPalette();
        }
    }

    // ------------------------------------------------------------------------------------------------ command palette

    // The palette lies on the layered pane of the window, above its content, hidden until Ctrl+K (never a modal dialog).
    private void installCommandPalette() {
        JLayeredPane layers = getLayeredPane();
        commandPalette.setVisible(false);
        layers.add(commandPalette, JLayeredPane.MODAL_LAYER);
        layers.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (commandPalette.isVisible()) {
                    placeCommandPalette();
                }
            }
        });
    }

    private void openCommandPalette() {
        if (!commandPalette.isVisible()) {
            focusBeforePalette = KeyboardFocusManager.getCurrentKeyboardFocusManager().getPermanentFocusOwner();
        }
        commandPalette.show(new CommandPalette(paletteEntries()));
        placeCommandPalette();
        commandPalette.setVisible(true);
        commandPalette.search().requestFocusInWindow();
    }

    // Centred, in the upper part of the window, within its margins.
    private void placeCommandPalette() {
        JLayeredPane layers = getLayeredPane();
        int margin = UIScale.scale(DesignTokens.load().size("space.6"));
        int width = Math.max(0, Math.min(UIScale.scale(PALETTE_WIDTH), layers.getWidth() - 2 * margin));
        int height = Math.max(0, Math.min(commandPalette.getPreferredSize().height, layers.getHeight() - 2 * margin));
        int top = Math.max(0, Math.min(layers.getHeight() / 8, layers.getHeight() - height - margin));
        commandPalette.setBounds((layers.getWidth() - width) / 2, top, width, height);
        commandPalette.revalidate();
        commandPalette.repaint();
    }

    /**
     * The entries of the palette, derived from the menus as they are now: one per item, in their order, its key and its state;
     * labelled « Mode … » for a display mode, « Thème : … » for a theme, by the text of the item for everything else.
     */
    private List<CommandPalette.Entry> paletteEntries() {
        List<CommandPalette.Entry> entries = new ArrayList<>();
        for (JMenuItem item : menuCommands()) {
            String label = modeItems.containsValue(item) ? CommandPalette.modeLabel(item.getText())
                    : themeItems.containsValue(item) ? CommandPalette.themeLabel(item.getText())
                    : item.getText();
            KeyStroke key = item.getAccelerator();
            entries.add(new CommandPalette.Entry(item.getName(), label, key == null ? "" : shortcuts.commandText(key), item.isEnabled()));
        }
        return entries;
    }

    // Every item of the menus that runs a command, in order, those of a sub-menu in its place.
    private List<JMenuItem> menuCommands() {
        List<JMenuItem> items = new ArrayList<>();
        JMenuBar bar = getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            addCommands(bar.getMenu(i), items);
        }
        return items;
    }

    private static void addCommands(JMenu menu, List<JMenuItem> items) {
        for (Component each : menu.getMenuComponents()) {
            if (each instanceof JMenu sub) {
                addCommands(sub, items);
            } else if (each instanceof JMenuItem item) {
                items.add(item);
            }
        }
    }

    // The entry does what its menu item does, then the palette closes. The item that opens the palette leaves it open.
    private void runFromPalette(CommandPalette.Entry entry) {
        if (COMMAND_PALETTE_ITEM.equals(entry.id())) {
            return;
        }
        for (JMenuItem item : menuCommands()) {
            if (entry.id().equals(item.getName()) && item.isEnabled()) {
                item.doClick(0);
                break;
            }
        }
        closeCommandPalette(true);
    }

    /**
     * Closes the palette. With {@code giveFocusBack}, the focus goes back to where it was, or to the active line when it was in
     * a line (a command may have made another line active). The palette hides once its search has lost the focus: hiding the
     * component that has the focus would let Swing hand it to the next component, a line that would become active.
     */
    private void closeCommandPalette(boolean giveFocusBack) {
        if (!commandPalette.isVisible()) {
            return;
        }
        if (giveFocusBack && commandPalette.search().isFocusOwner() && focusTarget().requestFocusInWindow()) {
            return;
        }
        focusBeforePalette = null;
        commandPalette.setVisible(false);
        getLayeredPane().repaint();
    }

    private Component focusTarget() {
        Component before = focusBeforePalette;
        boolean inALine = before == null || rows.stream().anyMatch(row -> row.field == before);
        return inALine || !before.isShowing() ? rows.get(shown.activeIndex()).field : before;
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
            showDetail(viewModel);
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
            showDetail(viewModel);
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

    // The detail of the active line goes under it; it only redraws the rows it leaves and reaches.
    private void showDetail(NotebookViewModel viewModel) {
        int active = viewModel.activeIndex();
        if (active < rows.size()) {
            rows.get(active).holdDetail(lineDetail);
        }
        lineDetail.show(viewModel.activeDetail());
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
        JRadioButtonMenuItem themeItem = themeItems.get(viewModel.theme());
        if (themeItem != null) {
            themeItem.setSelected(true);
        }
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
        row.field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                draftChanged(row);
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                draftChanged(row);
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
            }
        });
        bindKeys(row);
        row.field.setMultiLinePaste((start, end, text) -> apply(presenter.onPaste(row.index, start, end, text)));
        rows.add(row);
        rowsPanel.add(row);
    }

    // The user typed in the active line: the presenter keeps the text and asks for its preview. Like a calculation, the answer
    // leaves the field, its caret and the scroll bar alone: nothing here may write into the document that is notifying.
    private void draftChanged(NotebookRowView row) {
        if (!applying && shown != null && row.index == shown.activeIndex()) {
            applyCalculation(presenter.onDraftChanged(row.field.getText()));
        }
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
            case SUCCESS -> Palette.active().success();
            case ERROR -> Palette.active().error();
            case INFO -> Palette.active().info();
        });
        statusLabel.getAccessibleContext().setAccessibleName("État");
        statusLabel.getAccessibleContext().setAccessibleDescription(message);
    }

    // ------------------------------------------------------------------------------------------------ for the tests

    /** Whether a calculation is under way or rows are still to build: a test waits for the end. */
    boolean isCalculating() {
        return presenter.isCalculating() || growing;
    }

    /** The view under the rows, which takes a click on the empty space. */
    JViewport scrollViewport() {
        return scrollPane.getViewport();
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
            setBackground(NotebookPalette.active().rowBackground());
            setOpaque(true);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            // A change of theme: the sheet takes the surface of the new theme.
            setBackground(NotebookPalette.active().rowBackground());
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
