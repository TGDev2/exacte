package calc.ui.widget;

import calc.format.DisplayMode;
import java.awt.Component;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;

/**
 * Chooser of the {@link DisplayMode}. A drop-down list rather than segmented buttons: four modes with names of very
 * different lengths fit one compact line, the arrow keys and the first letter change the mode from the keyboard, and
 * assistive technologies announce a combo box as a choice among named items. It knows nothing of the window it sits in:
 * a notebook editor can host it as it is.
 */
public final class DisplayModeSelector extends JComboBox<DisplayMode> {
    private static final long serialVersionUID = 1L;
    private static final String NAME = "Mode d'affichage";
    private static final String DESCRIPTION =
            "Choix de la notation des résultats : automatique, décimale, fraction ou scientifique";

    private boolean updating;

    public DisplayModeSelector() {
        super(DisplayMode.values());
        setRenderer(new ModeRenderer());
        getAccessibleContext().setAccessibleName(NAME);
        getAccessibleContext().setAccessibleDescription(DESCRIPTION);
    }

    public DisplayMode mode() {
        return (DisplayMode) Objects.requireNonNull(getSelectedItem());
    }

    /** Shows {@code mode} without notifying the listeners registered with {@link #onModeChosen}. */
    public void showMode(DisplayMode mode) {
        updating = true;
        try {
            setSelectedItem(mode);
        } finally {
            updating = false;
        }
    }

    /** Calls {@code listener} when the user chooses a mode. */
    public void onModeChosen(Consumer<DisplayMode> listener) {
        Objects.requireNonNull(listener);
        addActionListener(event -> {
            if (!updating) {
                listener.accept(mode());
            }
        });
    }

    private static final class ModeRenderer extends DefaultListCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String text = value instanceof DisplayMode mode ? DisplayModeText.label(mode) : "";
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}
