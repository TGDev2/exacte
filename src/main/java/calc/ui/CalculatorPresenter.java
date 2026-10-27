package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.InputField;
import calc.engine.Operand;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import calc.format.DisplayMode;
import calc.format.FormattedValue;
import calc.format.ValueFormatter;
import calc.number.CertifiedValue;
import calc.ui.widget.HistoryLine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CalculatorPresenter {
    private static final Logger LOGGER = Logger.getLogger(CalculatorPresenter.class.getName());
    private static final String NO_RESULT = "--";
    private static final String EMPTY_HISTORY_STATUS = "Historique vide. Saisissez un calcul puis appuyez sur Entrée ou cliquez sur Calculer.";
    private static final String RESTORED_HISTORY_STATUS = "Historique restauré. Vous pouvez réutiliser un résultat précédent ou lancer un nouveau calcul.";
    private static final String UNAVAILABLE_HISTORY_STATUS = "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage.";
    private static final String BACKUP_NOTICE_PREFIX = "Copie de sauvegarde : ";
    private static final String NEWER_VERSION_STATUS = "Historique créé par une version plus récente de Calc : ouvert en lecture seule.";
    private static final String NEWER_VERSION_NOT_LOADED_STATUS =
            "Historique créé par une version plus récente de Calc : il n'a pas pu être ouvert et ne sera pas modifié.";
    private static final String IN_USE_STATUS = "Calc est déjà ouvert : l'historique de cette fenêtre ne sera pas enregistré.";
    private static final String READ_ONLY_CALCULATION_STATUS =
            "Calcul effectué. Le résultat est prêt pour le calcul suivant, mais l'historique est en lecture seule : il ne sera pas enregistré.";
    private static final String READ_ONLY_CLEARED_STATUS =
            "Historique effacé dans cette fenêtre. Il est en lecture seule : le fichier n'est pas modifié.";

    private final CalculatorEngine engine;
    private final CalculationHistoryStore historyStore;
    private final DisplayModeStore modeStore;
    private final List<CalculationRecord> history;
    // The history entries formatted in the current display mode; emptied when the mode changes or the history is cleared.
    private final Map<CalculationRecord, HistoryLine> historyLines = new IdentityHashMap<>();

    private Operation selectedOperation;
    private String firstInput;
    private String secondInput;
    private DisplayMode displayMode;
    // The value of the result on show and its text in the current display mode; null while there is no result.
    private CertifiedValue shownResult;
    private FormattedValue shownFormatted;
    // The value put in each field and the text written for it; dropped as soon as the text is changed.
    private FieldValue firstBinding;
    private FieldValue secondBinding;
    private String statusMessage;
    private StatusTone statusTone;
    private int selectedHistoryIndex;
    private FocusTarget focusTarget;
    private boolean selectFocusedFieldText;

    public CalculatorPresenter(CalculatorEngine engine) {
        this(engine, CalculationHistoryStore.noop());
    }

    public CalculatorPresenter(CalculatorEngine engine, CalculationHistoryStore historyStore) {
        this(engine, historyStore, DisplayModeStore.noop());
    }

    public CalculatorPresenter(CalculatorEngine engine, CalculationHistoryStore historyStore, DisplayModeStore modeStore) {
        this.engine = Objects.requireNonNull(engine);
        this.historyStore = Objects.requireNonNull(historyStore);
        this.modeStore = Objects.requireNonNull(modeStore);
        displayMode = initialDisplayMode(modeStore);
        history = new ArrayList<>();
        selectedOperation = Operation.ADDITION;
        firstInput = "";
        secondInput = "";
        clearResult();
        statusMessage = EMPTY_HISTORY_STATUS;
        statusTone = StatusTone.INFO;
        selectedHistoryIndex = -1;
        focusTarget = FocusTarget.FIRST_NUMBER;
        restoreHistory();
    }

    public CalculatorViewModel createInitialView() {
        return buildViewModel();
    }

    public CalculatorViewModel onOperationChanged(Operation operation, String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        if (operation != null) {
            selectedOperation = operation;
        }
        if (!selectedOperation.requiresSecondOperand()) {
            secondInput = "";
            secondBinding = null;
        }

        if (!selectedOperation.requiresSecondOperand()) {
            focusTarget = FocusTarget.FIRST_NUMBER;
        } else if (firstInput.trim().isEmpty()) {
            focusTarget = FocusTarget.FIRST_NUMBER;
        } else {
            focusTarget = FocusTarget.SECOND_NUMBER;
        }

        return buildViewModel();
    }

    /** Shows the result and the history in {@code mode}, without calculating anything again; the mode is remembered. */
    public CalculatorViewModel onDisplayModeChanged(DisplayMode mode, String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        if (mode != null && mode != displayMode) {
            displayMode = mode;
            historyLines.clear();
            if (shownResult != null) {
                shownFormatted = ValueFormatter.format(shownResult, displayMode);
            }
            saveDisplayMode();
        }
        focusTarget = FocusTarget.NONE;
        selectFocusedFieldText = false;
        return buildViewModel();
    }

    /** Same as {@link #onDisplayModeChanged} with the mode that follows the current one (the first after the last). */
    public CalculatorViewModel onNextDisplayMode(String currentFirstInput, String currentSecondInput) {
        DisplayMode[] modes = DisplayMode.values();
        return onDisplayModeChanged(modes[(displayMode.ordinal() + 1) % modes.length], currentFirstInput, currentSecondInput);
    }

    public CalculatorViewModel onCalculate(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        try {
            CalculationRecord record = engine.calculate(
                    selectedOperation, operand(firstBinding, firstInput), operand(secondBinding, secondInput));
            history.add(record);
            selectedHistoryIndex = history.size() - 1;
            showResult(record.getResult());
            firstBinding = bind(record);
            firstInput = firstBinding.text();
            secondInput = "";
            secondBinding = null;
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            if (persistHistory()) {
                statusMessage = historyStore.isReadOnly()
                        ? READ_ONLY_CALCULATION_STATUS
                        : "Calcul effectué. Le résultat est prêt pour le calcul suivant et ajouté à l'historique.";
                statusTone = StatusTone.SUCCESS;
            } else {
                statusMessage = "Calcul effectué, mais l'historique n'a pas pu être enregistré.";
                statusTone = StatusTone.ERROR;
            }
            return buildViewModel();
        } catch (CalculationException exception) {
            clearResult();
            statusMessage = exception.getMessage();
            statusTone = StatusTone.ERROR;
            focusTarget = resolveFocusTargetForError(exception);
            selectFocusedFieldText = true;
            return buildViewModel();
        }
    }

    public CalculatorViewModel onClearFields(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        firstInput = "";
        secondInput = "";
        firstBinding = null;
        secondBinding = null;
        clearResult();
        statusMessage = "Champs effacés. Vous pouvez saisir un nouveau calcul.";
        statusTone = StatusTone.INFO;
        focusTarget = FocusTarget.FIRST_NUMBER;
        selectFocusedFieldText = false;
        return buildViewModel();
    }

    public CalculatorViewModel onClearHistory() {
        List<CalculationRecord> previousHistory = new ArrayList<>(history);
        history.clear();
        historyLines.clear();
        selectedHistoryIndex = -1;
        focusTarget = FocusTarget.NONE;
        selectFocusedFieldText = false;

        if (persistHistory()) {
            statusMessage = historyStore.isReadOnly() ? READ_ONLY_CLEARED_STATUS : "Historique effacé.";
            statusTone = StatusTone.INFO;
            return buildViewModel();
        }

        history.addAll(previousHistory);
        statusMessage = "L'historique n'a pas pu être effacé.";
        statusTone = StatusTone.ERROR;
        return buildViewModel();
    }

    public CalculatorViewModel onHistorySelectionChanged(int historyIndex) {
        if (historyIndex >= 0 && historyIndex < history.size()) {
            selectedHistoryIndex = historyIndex;
        } else {
            selectedHistoryIndex = -1;
        }
        focusTarget = FocusTarget.NONE;
        selectFocusedFieldText = false;
        return buildViewModel();
    }

    public CalculatorViewModel onReuseHistoryResult(ReuseTarget target, String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        CalculationRecord record = resolveSelectedOrLatestRecord();
        if (record == null) {
            statusMessage = "Aucun résultat disponible dans l'historique.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.NONE;
            return buildViewModel();
        }

        if (target == ReuseTarget.SECOND_NUMBER && !selectedOperation.requiresSecondOperand()) {
            statusMessage = "Le second nombre n'est pas utilisé pour cette opération.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = true;
            return buildViewModel();
        }

        if (target == ReuseTarget.FIRST_NUMBER) {
            firstBinding = bind(record);
            firstInput = firstBinding.text();
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            statusMessage = "Le résultat historique a été placé pour le calcul en cours.";
        } else {
            secondBinding = bind(record);
            secondInput = secondBinding.text();
            focusTarget = FocusTarget.SECOND_NUMBER;
            selectFocusedFieldText = true;
            statusMessage = "Le résultat historique a été copié dans le second nombre.";
        }

        statusTone = StatusTone.SUCCESS;
        return buildViewModel();
    }

    public CalculatorViewModel onReuseLastResult(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        CalculationRecord record = resolveLatestRecord();
        if (record == null) {
            statusMessage = "Aucun résultat précédent n'est disponible.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.NONE;
            return buildViewModel();
        }

        selectedHistoryIndex = history.size() - 1;
        firstBinding = bind(record);
        firstInput = firstBinding.text();
        secondBinding = null;
        if (selectedOperation.requiresSecondOperand()) {
            secondInput = "";
            focusTarget = FocusTarget.SECOND_NUMBER;
            selectFocusedFieldText = false;
        } else {
            secondInput = "";
            focusTarget = FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = true;
        }
        statusMessage = "Le dernier résultat a été repris pour le calcul suivant.";
        statusTone = StatusTone.SUCCESS;
        return buildViewModel();
    }

    private void syncInputs(String currentFirstInput, String currentSecondInput) {
        firstInput = currentFirstInput == null ? "" : currentFirstInput;
        secondInput = currentSecondInput == null ? "" : currentSecondInput;
        if (!selectedOperation.requiresSecondOperand()) {
            secondInput = "";
        }
        firstBinding = keptIfUnchanged(firstBinding, firstInput);
        secondBinding = keptIfUnchanged(secondBinding, secondInput);
    }

    private static FieldValue keptIfUnchanged(FieldValue binding, String text) {
        return binding != null && binding.text().equals(text) ? binding : null;
    }

    // A field still holding the text written for a value is calculated with that value, interval included.
    private static Operand operand(FieldValue binding, String text) {
        return binding != null ? Operand.known(binding.value()) : Operand.typed(text);
    }

    private static FieldValue bind(CalculationRecord record) {
        return new FieldValue(record.getReusableValue(), record.getResult());
    }

    private void showResult(CertifiedValue result) {
        shownResult = result;
        shownFormatted = ValueFormatter.format(result, displayMode);
    }

    private void clearResult() {
        shownResult = null;
        shownFormatted = null;
    }

    private static DisplayMode initialDisplayMode(DisplayModeStore store) {
        try {
            DisplayMode stored = store.load();
            return stored != null ? stored : DisplayMode.AUTO;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot read the display mode, using Auto", exception);
            return DisplayMode.AUTO;
        }
    }

    private void saveDisplayMode() {
        try {
            modeStore.save(displayMode);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Cannot remember the display mode", exception);
        }
    }

    private HistoryLine historyLine(CalculationRecord record) {
        return historyLines.computeIfAbsent(record, entry -> {
            FormattedValue formatted = displayMode == DisplayMode.AUTO
                    ? entry.getFormatted()
                    : ValueFormatter.format(entry.getResult(), displayMode);
            return new HistoryLine(entry.getExpression(), formatted.text(), formatted.exact(), ResultDescription.describe(formatted));
        });
    }

    private CalculationRecord resolveSelectedOrLatestRecord() {
        if (selectedHistoryIndex >= 0 && selectedHistoryIndex < history.size()) {
            return history.get(selectedHistoryIndex);
        }
        return resolveLatestRecord();
    }

    private CalculationRecord resolveLatestRecord() {
        if (history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1);
    }

    private FocusTarget resolveFocusTargetForError(CalculationException exception) {
        if (exception.getField() == InputField.SECOND && selectedOperation.requiresSecondOperand()) {
            return FocusTarget.SECOND_NUMBER;
        }
        return FocusTarget.FIRST_NUMBER;
    }

    private CalculatorViewModel buildViewModel() {
        boolean secondInputEnabled = selectedOperation.requiresSecondOperand();
        CalculatorViewModel viewModel = new CalculatorViewModel(
                selectedOperation,
                firstInput,
                secondInputEnabled ? secondInput : "",
                secondInputEnabled,
                secondInputEnabled ? "Second nombre" : "Second nombre (non utilisé)",
                secondInputEnabled
                        ? "Saisissez le second nombre."
                        : "Ce champ n'est pas utilisé pour la racine carrée.",
                OperationText.inputHint(selectedOperation),
                displayMode,
                shownFormatted != null,
                shownFormatted != null ? shownFormatted.text() : NO_RESULT,
                shownFormatted == null || shownFormatted.exact(),
                shownFormatted != null && shownFormatted.truncated(),
                shownFormatted != null ? shownFormatted.errorBound().orElse("") : "",
                shownFormatted != null ? ResultDescription.secondaryFraction(shownFormatted) : "",
                shownFormatted != null ? ResultDescription.describe(shownFormatted) : "",
                statusMessage,
                statusTone,
                history,
                history.stream().map(this::historyLine).toList(),
                selectedHistoryIndex,
                focusTarget,
                selectFocusedFieldText);

        focusTarget = FocusTarget.NONE;
        selectFocusedFieldText = false;
        return viewModel;
    }

    private void restoreHistory() {
        try {
            HistoryLoadResult loadResult = historyStore.loadHistory();
            history.addAll(loadResult.records());
            applyRestoreStatus(loadResult);
        } catch (CalculationHistoryException exception) {
            statusMessage = withBackupNotice(UNAVAILABLE_HISTORY_STATUS, exception.getBackupFile());
            statusTone = StatusTone.ERROR;
        }
    }

    private void applyRestoreStatus(HistoryLoadResult loadResult) {
        switch (loadResult.condition()) {
            case NEWER_VERSION_READ_ONLY -> {
                statusMessage = NEWER_VERSION_STATUS;
                statusTone = StatusTone.ERROR;
                return;
            }
            case NEWER_VERSION_NOT_LOADED -> {
                statusMessage = NEWER_VERSION_NOT_LOADED_STATUS;
                statusTone = StatusTone.ERROR;
                return;
            }
            case IN_USE_READ_ONLY -> {
                statusMessage = IN_USE_STATUS;
                statusTone = StatusTone.ERROR;
                return;
            }
            case NORMAL, MIGRATED -> {
            }
        }

        List<RejectedLine> rejections = loadResult.rejections();
        int restored = loadResult.records().size();
        boolean migrated = loadResult.condition() == HistoryLoadResult.Condition.MIGRATED;
        if (rejections.isEmpty()) {
            if (migrated) {
                statusMessage = "Historique converti au nouveau format (" + restored + (restored > 1 ? " entrées)." : " entrée).");
            } else {
                statusMessage = restored > 0 ? RESTORED_HISTORY_STATUS : EMPTY_HISTORY_STATUS;
            }
            statusTone = restored > 0 || migrated ? StatusTone.SUCCESS : StatusTone.INFO;
            return;
        }

        String ignored = describeIgnoredEntries(rejections);
        String summary;
        if (migrated) {
            summary = "Historique converti au nouveau format (" + restored + (restored > 1 ? " entrées" : " entrée") + "), " + ignored + ".";
        } else if (restored == 0) {
            summary = "Aucun calcul restauré, " + ignored + ". Vous pouvez continuer sans blocage.";
        } else {
            summary = restored + (restored == 1 ? " calcul restauré, " : " calculs restaurés, ") + ignored + ".";
        }
        statusMessage = withBackupNotice(summary, loadResult.backupFile());
        statusTone = StatusTone.ERROR;
    }

    private static String describeIgnoredEntries(List<RejectedLine> rejections) {
        long inconsistent = rejections.stream().filter(line -> line.cause() == RejectedLine.Cause.INCONSISTENT_RESULT).count();
        long unreadable = rejections.size() - inconsistent;

        List<String> parts = new ArrayList<>();
        addIgnoredEntries(parts, unreadable, "illisible", "illisibles");
        addIgnoredEntries(parts, inconsistent, "incohérente", "incohérentes");
        return String.join(" et ", parts);
    }

    private static void addIgnoredEntries(List<String> parts, long count, String singular, String plural) {
        if (count == 1) {
            parts.add("1 entrée " + singular + " ignorée");
        } else if (count > 1) {
            parts.add(count + " entrées " + plural + " ignorées");
        }
    }

    private static String withBackupNotice(String message, Path backupFile) {
        return backupFile != null ? message + " " + BACKUP_NOTICE_PREFIX + backupFile : message;
    }

    private record FieldValue(String text, CertifiedValue value) {
    }

    private boolean persistHistory() {
        try {
            historyStore.saveHistory(history);
            return true;
        } catch (CalculationHistoryException exception) {
            LOGGER.log(Level.WARNING, "Cannot persist history", exception);
            return false;
        }
    }
}
