package calc.ui.widget;

import calc.format.DisplayMode;
import calc.ui.design.DesignTokens;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

/**
 * Chooser of the {@link DisplayMode}: a segmented control, one toggle button per mode in a single {@link ButtonGroup}, so that
 * the four notations are visible at once and the one in force is the pressed segment. Tab reaches each segment and Space
 * chooses it; assistive technologies announce the group « Affichage » and each button by its mode. It knows nothing of the
 * window it sits in: a notebook editor can host it as it is.
 */
public final class DisplayModeSelector extends JPanel {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Affichage";
    private static final String DESCRIPTION =
            "Choix de la notation des résultats : automatique, décimale, fraction ou scientifique";

    private final transient Map<DisplayMode, JToggleButton> buttons = new EnumMap<>(DisplayMode.class);
    private final transient List<Consumer<DisplayMode>> listeners = new ArrayList<>();
    private boolean updating;

    public DisplayModeSelector() {
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        setOpaque(false);
        ButtonGroup group = new ButtonGroup();
        for (DisplayMode mode : DisplayMode.values()) {
            JToggleButton button = new JToggleButton(DisplayModeText.label(mode));
            button.setName("modeButton" + mode.name());
            // FlatLaf draws a tool bar button flat, the selected one filled: side by side they make the segments.
            button.putClientProperty("JButton.buttonType", "toolBarButton");
            button.setMargin(new Insets(3, 10, 3, 10));
            button.getAccessibleContext().setAccessibleDescription("Mode d'affichage " + DisplayModeText.label(mode));
            button.addActionListener(event -> {
                if (!updating) {
                    listeners.forEach(listener -> listener.accept(mode));
                }
            });
            group.add(button);
            buttons.put(mode, button);
            add(button);
        }
        buttons.get(DisplayMode.AUTO).setSelected(true);
        getAccessibleContext().setAccessibleName(NAME);
        getAccessibleContext().setAccessibleDescription(DESCRIPTION);
        frame();
    }

    public DisplayMode mode() {
        for (Map.Entry<DisplayMode, JToggleButton> each : buttons.entrySet()) {
            if (each.getValue().isSelected()) {
                return each.getKey();
            }
        }
        throw new IllegalStateException("no mode selected");
    }

    /** Shows {@code mode} without notifying the listeners registered with {@link #onModeChosen}. */
    public void showMode(DisplayMode mode) {
        updating = true;
        try {
            buttons.get(Objects.requireNonNull(mode)).setSelected(true);
        } finally {
            updating = false;
        }
    }

    /** Calls {@code listener} when the user chooses a mode. */
    public void onModeChosen(Consumer<DisplayMode> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** The segment of {@code mode}. */
    public JToggleButton button(DisplayMode mode) {
        return buttons.get(Objects.requireNonNull(mode));
    }

    // The label « Affichage » gives the focus to the control: the segment of the mode in force takes it.
    @Override
    public void requestFocus() {
        button(mode()).requestFocus();
    }

    @Override
    public boolean requestFocusInWindow() {
        return button(mode()).requestFocusInWindow();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Called by the constructor of JPanel before the buttons exist, and again at each change of theme.
        if (buttons != null) {
            frame();
        }
    }

    // The rule around the segments, in the colour of the theme in force.
    private void frame() {
        setBorder(BorderFactory.createLineBorder(DesignTokens.load().color(Palette.activeTheme(), "border.subtle"), 1, true));
    }
}
