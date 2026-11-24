package calc.ui.notebook;

import calc.ui.design.DesignTokens;
import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.util.UIScale;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Objects;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * The command palette on the window (docs/design/DESIGN.md, « Palette de commandes »): a layer of the {@code JLayeredPane},
 * never a modal dialog, with a search field and the list of the commands that match, each with its key aligned to the right
 * in {@code text.secondary}. The keys stay in the search field: ↑ and ↓ move the selection, Enter runs the selected command
 * when it is enabled, Escape closes the palette; the event is consumed there, so it never reaches the line. A click on an
 * entry runs it. What the entries are and what running one does belong to the window ({@link Listener}).
 */
final class CommandPaletteView extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String SEARCH_NAME = "Rechercher une commande";
    private static final String LIST_CARD = "list";
    private static final String EMPTY_CARD = "empty";
    private static final int VISIBLE_ROWS = 10;

    /** What the palette asks of the window. */
    interface Listener {
        /** Runs the entry, an enabled one; the palette is still open. */
        void run(CommandPalette.Entry entry);

        /** Closes the palette; with {@code giveFocusBack}, the focus goes back where it was when the palette opened. */
        void dismiss(boolean giveFocusBack);
    }

    private final transient Listener listener;
    private final JTextField search = new JTextField();
    private final DefaultListModel<CommandPalette.Entry> model = new DefaultListModel<>();
    private final JList<CommandPalette.Entry> list = new JList<>(model);
    private final JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
    private final JLabel empty = new JLabel(CommandPalette.NO_COMMAND);
    private final CardLayout cards = new CardLayout();
    private final JPanel results = new JPanel(cards);
    private final EntryRenderer renderer = new EntryRenderer();
    private transient CommandPalette palette = new CommandPalette(List.of());

    CommandPaletteView(Listener listener) {
        super(new BorderLayout(0, scaled("space.2")));
        this.listener = Objects.requireNonNull(listener, "listener");
        setName("commandPalette");
        setOpaque(false);
        getAccessibleContext().setAccessibleName("Palette de commandes");
        int padding = scaled("space.6");
        setBorder(BorderFactory.createEmptyBorder(padding, padding, padding, padding));

        search.setName("commandPaletteSearch");
        search.getAccessibleContext().setAccessibleName(SEARCH_NAME);
        search.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, SEARCH_NAME);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
            }
        });
        bind(KeyEvent.VK_UP, "palette-previous", () -> moveSelection(-1));
        bind(KeyEvent.VK_DOWN, "palette-next", () -> moveSelection(1));
        bind(KeyEvent.VK_ENTER, "palette-run", this::runSelected);
        bind(KeyEvent.VK_ESCAPE, "palette-close", () -> listener.dismiss(true));
        search.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent event) {
                // The user went elsewhere in the window (a click on a line): the palette goes away and leaves the focus there.
                Component opposite = event.getOppositeComponent();
                if (!event.isTemporary() && opposite != null && !SwingUtilities.isDescendingFrom(opposite, CommandPaletteView.this)) {
                    listener.dismiss(false);
                }
            }
        });

        list.setName("commandPaletteList");
        list.getAccessibleContext().setAccessibleName("Commandes");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(VISIBLE_ROWS);
        // Every row has the height of this one, so the palette keeps its size whatever matches.
        list.setPrototypeCellValue(new CommandPalette.Entry("", "Insérer une ligne au-dessous", "Ctrl+Maj+Entrée", true));
        list.setCellRenderer(renderer);
        // The focus stays in the search: the keys are read there.
        list.setFocusable(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int index = indexAt(event.getPoint());
                if (index >= 0) {
                    list.setSelectedIndex(index);
                    runSelected();
                }
            }
        });
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        empty.setName("commandPaletteEmpty");
        empty.setVerticalAlignment(JLabel.TOP);
        results.setOpaque(false);
        results.add(scroll, LIST_CARD);
        results.add(empty, EMPTY_CARD);

        add(search, BorderLayout.NORTH);
        add(results, BorderLayout.CENTER);
        applyColours();
    }

    /** Shows these entries, all of them, with an empty search. */
    void show(CommandPalette entries) {
        palette = entries;
        if (search.getText().isEmpty()) {
            refresh();
        } else {
            search.setText("");
        }
    }

    JTextField search() {
        return search;
    }

    // The entries that match what is typed, the first one selected; « Aucune commande » without any.
    private void refresh() {
        List<CommandPalette.Entry> matching = palette.filter(search.getText());
        model.clear();
        model.addAll(matching);
        if (matching.isEmpty()) {
            cards.show(results, EMPTY_CARD);
        } else {
            cards.show(results, LIST_CARD);
            list.setSelectedIndex(0);
            list.ensureIndexIsVisible(0);
        }
    }

    private void moveSelection(int step) {
        int size = model.getSize();
        if (size == 0) {
            return;
        }
        int selected = list.getSelectedIndex();
        int next = selected < 0 ? 0 : Math.max(0, Math.min(size - 1, selected + step));
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    // A disabled entry is not run, as its menu item cannot be chosen.
    private void runSelected() {
        CommandPalette.Entry entry = list.getSelectedValue();
        if (entry != null && entry.enabled()) {
            listener.run(entry);
        }
    }

    private int indexAt(Point point) {
        int index = list.locationToIndex(point);
        return index >= 0 && list.getCellBounds(index, index).contains(point) ? index : -1;
    }

    private void bind(int keyCode, String key, Runnable action) {
        search.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(keyCode, 0), key);
        search.getActionMap().put(key, new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    /** The box: {@code surface.raised}, a {@code border.subtle} outline, the corners of {@code radius.large}. */
    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            NotebookPalette colours = NotebookPalette.active();
            int arc = 2 * scaled("radius.large");
            g.setColor(colours.detailBackground());
            g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            g.setColor(colours.border());
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
        } finally {
            g.dispose();
        }
        super.paintComponent(graphics);
    }

    /** A change of theme: the list, the message and the renderer take the colours of the new theme. */
    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of JPanel before the fields exist.
        if (renderer != null) {
            SwingUtilities.updateComponentTreeUI(renderer);
            applyColours();
        }
    }

    private void applyColours() {
        NotebookPalette colours = NotebookPalette.active();
        list.setBackground(colours.detailBackground());
        scroll.getViewport().setBackground(colours.detailBackground());
        empty.setForeground(colours.detailForeground());
        renderer.applyFonts();
    }

    private static int scaled(String token) {
        return UIScale.scale(DesignTokens.load().size(token));
    }

    /** An entry: its label on the left, its key on the right in {@code text.secondary}; the selected one on {@code accent}. */
    private static final class EntryRenderer extends JPanel implements ListCellRenderer<CommandPalette.Entry> {
        private static final long serialVersionUID = 1L;

        private final JLabel label = new JLabel();
        private final JLabel shortcut = new JLabel();

        EntryRenderer() {
            super(new BorderLayout(scaled("space.4"), 0));
            setBorder(BorderFactory.createEmptyBorder(scaled("space.1"), scaled("space.2"), scaled("space.1"), scaled("space.2")));
            add(label, BorderLayout.CENTER);
            add(shortcut, BorderLayout.EAST);
            applyFonts();
        }

        void applyFonts() {
            DesignTokens tokens = DesignTokens.load();
            label.setFont(new Font(tokens.fontFamily("ui"), Font.PLAIN, UIScale.scale(tokens.size("font.size.body"))));
            shortcut.setFont(new Font(tokens.fontFamily("ui"), Font.PLAIN, UIScale.scale(tokens.size("font.size.caption"))));
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends CommandPalette.Entry> list, CommandPalette.Entry entry,
                                                      int index, boolean selected, boolean focused) {
            NotebookPalette colours = NotebookPalette.active();
            label.setText(entry.label());
            shortcut.setText(entry.shortcut());
            Color background;
            Color text;
            Color key;
            if (!entry.enabled()) {
                background = selected ? colours.hoverRowBackground() : colours.detailBackground();
                text = colours.placeholder();
                key = colours.placeholder();
            } else if (selected) {
                background = colours.selectedEntryBackground();
                text = colours.selectedEntryText();
                key = text;
            } else {
                background = colours.detailBackground();
                text = colours.textForeground();
                key = colours.detailForeground();
            }
            setBackground(background);
            setOpaque(true);
            label.setForeground(text);
            shortcut.setForeground(key);
            setEnabled(entry.enabled());
            label.setEnabled(entry.enabled());
            shortcut.setEnabled(entry.enabled());
            getAccessibleContext().setAccessibleName(entry.accessibleName());
            return this;
        }
    }
}
