package calc.ui.notebook;

/** Where the view must put the keyboard focus after showing a view model. */
public enum NotebookFocus {
    /** Leave it where it is. */
    NONE,
    /** In the field of the active line, the caret at its end. */
    ACTIVE_LINE
}
