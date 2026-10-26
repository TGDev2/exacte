package calc.ui;

import calc.engine.CalculationException;
import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.HistoryLoadResult;
import calc.engine.InputField;
import calc.engine.Operation;
import calc.engine.RejectedLine;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CalculatorPresenter {
    private static final Logger LOGGER = Logger.getLogger(CalculatorPresenter.class.getName());
    private static final String EMPTY_HISTORY_STATUS = "Historique vide. Saisissez un calcul puis appuyez sur Entrée ou cliquez sur Calculer.";
    private static final String RESTORED_HISTORY_STATUS = "Historique restauré. Vous pouvez réutiliser un résultat précédent ou lancer un nouveau calcul.";
    private static final String UNAVAILABLE_HISTORY_STATUS = "L'historique précédent n'a pas pu être restauré. Vous pouvez continuer sans blocage.";
    private static final String BACKUP_NOTICE_PREFIX = "Copie de sauvegarde : ";

    private final CalculatorEngine engine;
    private final CalculationHistoryStore historyStore;
    private final List<CalculationRecord> history;

    private Operation selectedOperation;
    private String firstInput;
    private String secondInput;
    private String resultValue;
    private String statusMessage;
    private StatusTone statusTone;
    private int selectedHistoryIndex;
    private FocusTarget focusTarget;
    private boolean selectFocusedFieldText;

    public CalculatorPresenter(CalculatorEngine engine) {
        this(engine, CalculationHistoryStore.noop());
    }

    public CalculatorPresenter(CalculatorEngine engine, CalculationHistoryStore historyStore) {
        this.engine = Objects.requireNonNull(engine);
        this.historyStore = Objects.requireNonNull(historyStore);
        history = new ArrayList<>();
        selectedOperation = Operation.ADDITION;
        firstInput = "";
        secondInput = "";
        resultValue = "--";
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

    public CalculatorViewModel onCalculate(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        try {
            CalculationRecord record = engine.calculate(selectedOperation, firstInput, secondInput);
            history.add(record);
            selectedHistoryIndex = history.size() - 1;
            resultValue = record.getFormattedResult();
            firstInput = record.getFormattedResult();
            secondInput = "";
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            if (persistHistory()) {
                statusMessage = "Calcul effectué. Le résultat est prêt pour le calcul suivant et ajouté à l'historique.";
                statusTone = StatusTone.SUCCESS;
            } else {
                statusMessage = "Calcul effectué, mais l'historique n'a pas pu être enregistré.";
                statusTone = StatusTone.ERROR;
            }
            return buildViewModel();
        } catch (CalculationException exception) {
            resultValue = "--";
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
        resultValue = "--";
        statusMessage = "Champs effacés. Vous pouvez saisir un nouveau calcul.";
        statusTone = StatusTone.INFO;
        focusTarget = FocusTarget.FIRST_NUMBER;
        selectFocusedFieldText = false;
        return buildViewModel();
    }

    public CalculatorViewModel onClearHistory() {
        List<CalculationRecord> previousHistory = new ArrayList<>(history);
        history.clear();
        selectedHistoryIndex = -1;
        focusTarget = FocusTarget.NONE;
        selectFocusedFieldText = false;

        if (persistHistory()) {
            statusMessage = "Historique effacé.";
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
            firstInput = record.getFormattedResult();
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            statusMessage = "Le résultat historique a été placé pour le calcul en cours.";
        } else {
            secondInput = record.getFormattedResult();
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
        firstInput = record.getFormattedResult();
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
                resultValue,
                statusMessage,
                statusTone,
                history,
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
        List<RejectedLine> rejections = loadResult.rejections();
        int restored = loadResult.records().size();
        if (rejections.isEmpty()) {
            statusMessage = restored > 0 ? RESTORED_HISTORY_STATUS : EMPTY_HISTORY_STATUS;
            statusTone = restored > 0 ? StatusTone.SUCCESS : StatusTone.INFO;
            return;
        }

        String ignored = describeIgnoredLines(rejections);
        String summary = restored == 0
                ? "Aucun calcul restauré, " + ignored + ". Vous pouvez continuer sans blocage."
                : restored + (restored == 1 ? " calcul restauré, " : " calculs restaurés, ") + ignored + ".";
        statusMessage = withBackupNotice(summary, loadResult.backupFile());
        statusTone = StatusTone.ERROR;
    }

    private static String describeIgnoredLines(List<RejectedLine> rejections) {
        long inconsistent = rejections.stream().filter(line -> line.cause() == RejectedLine.Cause.INCONSISTENT_RESULT).count();
        long newerFormat = rejections.stream()
                .filter(line -> line.cause() == RejectedLine.Cause.UNSUPPORTED_FORMAT_VERSION)
                .count();
        long unreadable = rejections.size() - inconsistent - newerFormat;

        List<String> parts = new ArrayList<>();
        addIgnoredLines(parts, unreadable, "illisible", "illisibles");
        addIgnoredLines(parts, inconsistent, "incohérente", "incohérentes");
        addIgnoredLines(parts, newerFormat, "d'un format plus récent", "d'un format plus récent");
        return String.join(" et ", parts);
    }

    private static void addIgnoredLines(List<String> parts, long count, String singular, String plural) {
        if (count == 1) {
            parts.add("1 ligne " + singular + " ignorée");
        } else if (count > 1) {
            parts.add(count + " lignes " + plural + " ignorées");
        }
    }

    private static String withBackupNotice(String message, Path backupFile) {
        return backupFile != null ? message + " " + BACKUP_NOTICE_PREFIX + backupFile : message;
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
