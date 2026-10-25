package Calcul;

import java.nio.file.Path;

public class CalculationHistoryException extends Exception {
    private final transient Path backupFile;

    public CalculationHistoryException(String message, Throwable cause) {
        this(message, cause, null);
    }

    public CalculationHistoryException(String message, Throwable cause, Path backupFile) {
        super(message, cause);
        this.backupFile = backupFile;
    }

    public CalculationHistoryException(String message) {
        super(message);
        backupFile = null;
    }

    public Path getBackupFile() {
        return backupFile;
    }
}
