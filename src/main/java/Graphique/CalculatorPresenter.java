package Graphique;

import Calcul.CalculationException;
import Calcul.CalculationHistoryException;
import Calcul.CalculationHistoryStore;
import Calcul.CalculationRecord;
import Calcul.CalculatorEngine;
import Calcul.HistoryLoadResult;
import Calcul.Operation;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CalculatorPresenter {
    private static final Logger LOGGER = Logger.getLogger(CalculatorPresenter.class.getName());
    private static final String EMPTY_HISTORY_STATUS = "Historique vide. Saisissez un calcul puis appuyez sur Entree ou cliquez sur Calculer.";
    private static final String RESTORED_HISTORY_STATUS = "Historique restaure. Vous pouvez reutiliser un resultat precedent ou lancer un nouveau calcul.";
    private static final String UNAVAILABLE_HISTORY_STATUS = "L'historique precedent n'a pas pu etre restaure. Vous pouvez continuer sans blocage.";
    private static final String BACKUP_KEPT_STATUS = "Une copie du fichier d'origine a ete conservee.";

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
            firstInput = record.getReusableValue();
            secondInput = "";
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            if (persistHistory()) {
                statusMessage = "Calcul effectue. Le resultat est pret pour le calcul suivant et ajoute a l'historique.";
                statusTone = StatusTone.SUCCESS;
            } else {
                statusMessage = "Calcul effectue, mais l'historique n'a pas pu etre enregistre.";
                statusTone = StatusTone.ERROR;
            }
            return buildViewModel();
        } catch (CalculationException exception) {
            resultValue = "--";
            statusMessage = exception.getMessage();
            statusTone = StatusTone.ERROR;
            focusTarget = resolveFocusTargetForError(exception.getMessage());
            selectFocusedFieldText = true;
            return buildViewModel();
        }
    }

    public CalculatorViewModel onClearFields(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        firstInput = "";
        secondInput = "";
        resultValue = "--";
        statusMessage = "Champs effaces. Vous pouvez saisir un nouveau calcul.";
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
            statusMessage = "Historique efface.";
            statusTone = StatusTone.INFO;
            return buildViewModel();
        }

        history.addAll(previousHistory);
        statusMessage = "L'historique n'a pas pu etre efface.";
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
            statusMessage = "Aucun resultat disponible dans l'historique.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.NONE;
            return buildViewModel();
        }

        if (target == ReuseTarget.SECOND_NUMBER && !selectedOperation.requiresSecondOperand()) {
            statusMessage = "Le second nombre n'est pas utilise pour cette operation.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = true;
            return buildViewModel();
        }

        if (target == ReuseTarget.FIRST_NUMBER) {
            firstInput = record.getReusableValue();
            focusTarget = selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = !selectedOperation.requiresSecondOperand();
            statusMessage = "Le resultat historique a ete place pour le calcul en cours.";
        } else {
            secondInput = record.getReusableValue();
            focusTarget = FocusTarget.SECOND_NUMBER;
            selectFocusedFieldText = true;
            statusMessage = "Le resultat historique a ete copie dans le second nombre.";
        }

        statusTone = StatusTone.SUCCESS;
        return buildViewModel();
    }

    public CalculatorViewModel onReuseLastResult(String currentFirstInput, String currentSecondInput) {
        syncInputs(currentFirstInput, currentSecondInput);
        CalculationRecord record = resolveLatestRecord();
        if (record == null) {
            statusMessage = "Aucun resultat precedent n'est disponible.";
            statusTone = StatusTone.ERROR;
            focusTarget = FocusTarget.NONE;
            return buildViewModel();
        }

        selectedHistoryIndex = history.size() - 1;
        firstInput = record.getReusableValue();
        if (selectedOperation.requiresSecondOperand()) {
            secondInput = "";
            focusTarget = FocusTarget.SECOND_NUMBER;
            selectFocusedFieldText = false;
        } else {
            secondInput = "";
            focusTarget = FocusTarget.FIRST_NUMBER;
            selectFocusedFieldText = true;
        }
        statusMessage = "Le dernier resultat a ete repris pour le calcul suivant.";
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

    private FocusTarget resolveFocusTargetForError(String message) {
        if (message == null) {
            return FocusTarget.FIRST_NUMBER;
        }

        String lowerMessage = message.toLowerCase(Locale.ROOT);
        if (lowerMessage.contains("second")) {
            return selectedOperation.requiresSecondOperand() ? FocusTarget.SECOND_NUMBER : FocusTarget.FIRST_NUMBER;
        }
        return FocusTarget.FIRST_NUMBER;
    }

    private CalculatorViewModel buildViewModel() {
        boolean secondInputEnabled = selectedOperation.requiresSecondOperand();
        boolean historyAvailable = !history.isEmpty();
        CalculatorViewModel viewModel = new CalculatorViewModel(
                selectedOperation,
                firstInput,
                secondInputEnabled ? secondInput : "",
                secondInputEnabled,
                secondInputEnabled ? "Second nombre" : "Second nombre (non utilise)",
                secondInputEnabled
                        ? "Saisissez le second nombre."
                        : "Ce champ n'est pas utilise pour la racine carree.",
                selectedOperation.getInputHint(),
                resultValue,
                statusMessage,
                statusTone,
                history,
                selectedHistoryIndex,
                historyAvailable,
                historyAvailable && secondInputEnabled,
                historyAvailable,
                historyAvailable,
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
            statusMessage = withBackupNotice(UNAVAILABLE_HISTORY_STATUS, exception.getBackupFile() != null);
            statusTone = StatusTone.ERROR;
        }
    }

    private void applyRestoreStatus(HistoryLoadResult loadResult) {
        int skippedLines = loadResult.skippedLines();
        boolean backupKept = loadResult.backupFile() != null;
        if (skippedLines == 0) {
            boolean restored = !loadResult.records().isEmpty();
            statusMessage = restored ? RESTORED_HISTORY_STATUS : EMPTY_HISTORY_STATUS;
            statusTone = restored ? StatusTone.SUCCESS : StatusTone.INFO;
        } else if (loadResult.records().isEmpty()) {
            statusMessage = withBackupNotice(UNAVAILABLE_HISTORY_STATUS, backupKept);
            statusTone = StatusTone.ERROR;
        } else {
            String skipped = skippedLines == 1 ? "1 entree ignoree" : skippedLines + " entrees ignorees";
            statusMessage = withBackupNotice("Historique restaure partiellement : " + skipped + ".", backupKept);
            statusTone = StatusTone.ERROR;
        }
    }

    private static String withBackupNotice(String message, boolean backupKept) {
        return backupKept ? message + " " + BACKUP_KEPT_STATUS : message;
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
