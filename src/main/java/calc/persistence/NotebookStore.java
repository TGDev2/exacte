package calc.persistence;

import calc.engine.AtomicFileWriter;
import calc.engine.CalculationHistoryException;
import calc.engine.HistoryLoadResult;
import calc.format.DisplayMode;
import calc.notebook.Notebook;
import calc.persistence.LoadedNotebook.Condition;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The current notebook of Calc on disk: {@code carnet-courant.calc.json} in the data directory ({@link DataDirectory}),
 * version 3 of the format (docs/FORMAT-FICHIER.md), decided in docs/adr/0009-documents-et-exports.md.
 *
 * <p>Opening a store takes the exclusive lock {@code history.lock} that {@link JsonHistoryStore} uses (the two share it:
 * a process cannot lock a file twice). When another instance holds it, the store is <em>read only</em>: it loads, it never
 * writes. It is also read only for a file written by a newer version of the format, which is never rewritten. Writes go
 * through {@link AtomicFileWriter}.
 *
 * <p>{@link #load()} also migrates, once: when no current notebook exists and a history does, the history is loaded (a version 1
 * {@code history.tsv} first becomes {@code history.json}, see {@link JsonHistoryStore}), copied to {@code history.json.v2.bak},
 * converted line by line with the bridge of the notebook model and written as the current notebook. A step that fails
 * leaves every source as it was.
 *
 * <p>The log carries paths, counts and causes, never the content of a line. Not thread safe.
 */
public final class NotebookStore implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(NotebookStore.class.getName());

    static final String CURRENT_FILE = "carnet-courant.calc.json";
    static final String HISTORY_FILE = "history.json";
    static final String HISTORY_BACKUP_SUFFIX = ".v2.bak";
    private static final String IN_USE = "Calc est déjà ouvert : le carnet de cette fenêtre ne sera pas enregistré.";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Path directory;
    private final Path currentFile;
    private final Clock clock;
    private final JsonHistoryStore.FileCopier copier;
    private final AtomicFileWriter.Mover mover;
    private final StoreLock lock;
    private final JsonHistoryStore history;
    // No write at all, silently: another instance holds the directory, or a newer version made the file.
    private boolean readOnly;
    // Writes fail loudly: the file on disk must not be replaced (unreadable, over a limit, no backup possible).
    private boolean writeProtected;

    private NotebookStore(Path directory, Path legacyDirectory, Clock clock, JsonHistoryStore.FileCopier copier,
                          AtomicFileWriter.Mover mover) {
        this.directory = Objects.requireNonNull(directory);
        this.currentFile = directory.resolve(CURRENT_FILE);
        this.clock = Objects.requireNonNull(clock);
        this.copier = Objects.requireNonNull(copier);
        this.mover = Objects.requireNonNull(mover);
        this.lock = JsonHistoryStore.acquireLock(directory);
        this.history = JsonHistoryStore.openSharing(lock, directory, legacyDirectory, clock, copier, mover);
        this.readOnly = history.isReadOnly();
        LOGGER.info("Notebook directory: " + directory);
    }

    /** The store of the running system: the directory of {@link DataDirectory} and, if any, the {@code ~/.calc} of version 1. */
    public static NotebookStore openForCurrentUser() {
        DataDirectory.Environment environment = DataDirectory.Environment.system();
        return open(DataDirectory.resolve(environment), DataDirectory.legacyDirectory(environment).orElse(null),
                Clock.systemDefaultZone(), Files::copy, Files::move);
    }

    /** A store in {@code directory}, with no old location to adopt. */
    public static NotebookStore open(Path directory) {
        return open(directory, null, Clock.systemDefaultZone(), Files::copy, Files::move);
    }

    /**
     * @param legacyDirectory the version 1 directory ({@code ~/.calc}) to adopt, or null
     * @param copier          every copy made before something is replaced, replaceable by a test that simulates a full disk
     * @param mover           the final move of every atomic write, replaceable by a test that simulates a failing disk
     */
    public static NotebookStore open(Path directory, Path legacyDirectory, Clock clock, JsonHistoryStore.FileCopier copier,
                                     AtomicFileWriter.Mover mover) {
        return new NotebookStore(directory, legacyDirectory, clock, copier, mover);
    }

    /** Whether nothing is written: another instance holds the directory, or the file comes from a newer version. */
    public boolean isReadOnly() {
        return readOnly;
    }

    /** The file of the current notebook. */
    public Path currentFile() {
        return currentFile;
    }

    /**
     * Whether {@code file} is one of the files this store keeps in the data directory (the current notebook, the history, the
     * lock): a document must never be read from or saved over them.
     */
    public boolean isReserved(Path file) {
        Path candidate = file.toAbsolutePath().normalize();
        return Stream.of(CURRENT_FILE, HISTORY_FILE, "history.tsv", "history.lock")
                .map(name -> directory.resolve(name).toAbsolutePath().normalize())
                .anyMatch(reserved -> candidate.equals(reserved) || sameFileOnDisk(candidate, reserved));
    }

    // Another spelling of the same file: a link, a junction, or a trailing dot that Windows ignores.
    private static boolean sameFileOnDisk(Path first, Path second) {
        try {
            return Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
        } catch (IOException | SecurityException exception) {
            return false;
        }
    }

    /** Releases the lock. The store still loads; it no longer protects the files from another instance. */
    @Override
    public void close() {
        history.close();
        lock.close();
    }

    // ------------------------------------------------------------------------------------------------ loading

    /** The current notebook, after the migration of the history if it is due. Never throws: the problem is the condition. */
    public LoadedNotebook load() {
        return Files.exists(currentFile) ? loadCurrent() : migrateHistory();
    }

    private LoadedNotebook loadCurrent() {
        DecodedNotebook decoded;
        try {
            decoded = NotebookCodec.read(currentFile);
        } catch (NotebookFormatException exception) {
            return refuse(exception);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot read " + currentFile + ", writes are now refused", exception);
            writeProtected = true;
            return unreadable("Le carnet courant n'a pas pu être lu : il n'est pas modifié.", null);
        }
        if (decoded.isNewerVersion()) {
            readOnly = true;
            LOGGER.warning("Notebook " + currentFile + " has format version " + decoded.version() + " (supported: "
                    + NotebookCodec.VERSION + "): read only, never rewritten");
            return new LoadedNotebook(decoded.notebook(), decoded.displayMode(),
                    decoded.linesRead() ? Condition.NEWER_VERSION_READ_ONLY : Condition.NEWER_VERSION_NOT_LOADED,
                    decoded.linesRead() ? "Carnet créé par une version plus récente de Calc : ouvert en lecture seule."
                            : "Carnet créé par une version plus récente de Calc : il ne peut pas être lu et n'est pas modifié.",
                    0, 0, 0, null);
        }
        if (readOnly) {
            return new LoadedNotebook(decoded.notebook(), decoded.displayMode(), Condition.IN_USE_READ_ONLY, IN_USE, 0, 0, 0, null);
        }
        return new LoadedNotebook(decoded.notebook(), decoded.displayMode(), Condition.NORMAL, null, 0, 0, 0, null);
    }

    // A file that cannot be read as a whole. A bound exceeded: not touched, writes refused. Damaged: copied, then rewritable.
    private LoadedNotebook refuse(NotebookFormatException exception) {
        LOGGER.log(Level.WARNING, "Cannot use " + currentFile + " (" + exception.reason() + ")", exception);
        if (exception.reason().isLimit()) {
            writeProtected = true;
            return unreadable("Le carnet courant dépasse les limites de lecture : il n'est pas modifié.", null);
        }
        Path backup = backUpDamaged();
        return unreadable(backup == null
                ? "Le carnet courant est illisible et n'a pas pu être copié : il n'est pas modifié."
                : "Le carnet courant est illisible : une copie a été conservée (" + backup.getFileName() + ").", backup);
    }

    private LoadedNotebook unreadable(String status, Path backup) {
        return new LoadedNotebook(Notebook.empty(), DisplayMode.AUTO, Condition.UNREADABLE, status, 0, 0, 0, backup);
    }

    // The timestamped copy made before the damaged file is replaced; without it writes are refused. None when read only.
    private Path backUpDamaged() {
        if (readOnly) {
            return null;
        }
        String baseName = currentFile.getFileName() + "." + LocalDateTime.now(clock).format(BACKUP_TIMESTAMP);
        try {
            Path backup = BackupCopies.make(currentFile, copier, attempt -> baseName + (attempt == 0 ? "" : "-" + attempt) + ".bak");
            LOGGER.info("Backed up " + currentFile + " to " + backup);
            return backup;
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot back up " + currentFile + ", writes are now refused", exception);
            writeProtected = true;
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ migration

    private LoadedNotebook migrateHistory() {
        HistoryLoadResult loaded;
        try {
            loaded = history.loadHistory();
        } catch (CalculationHistoryException exception) {
            LOGGER.log(Level.WARNING, "The history cannot be read, it is not converted: " + exception.getMessage());
            return notConverted("L'historique n'a pas pu être lu : il n'est pas converti en carnet et reste tel quel.");
        }
        if (loaded.condition() == HistoryLoadResult.Condition.NEWER_VERSION_READ_ONLY
                || loaded.condition() == HistoryLoadResult.Condition.NEWER_VERSION_NOT_LOADED) {
            return notConverted("L'historique vient d'une version plus récente de Calc : il n'est pas converti en carnet.");
        }
        Path historyFile = directory.resolve(HISTORY_FILE);
        if (!Files.exists(historyFile) && loaded.records().isEmpty()) {
            return new LoadedNotebook(Notebook.empty(), DisplayMode.AUTO,
                    readOnly ? Condition.IN_USE_READ_ONLY : Condition.NORMAL, readOnly ? IN_USE : null, 0, 0, 0, null);
        }
        HistoryMigration.Converted converted = HistoryMigration.convert(loaded.records());
        int lines = converted.notebook().size();
        if (readOnly) {
            LOGGER.info("History converted in memory only: the store is read only (" + lines + " lines)");
            return new LoadedNotebook(converted.notebook(), DisplayMode.AUTO, Condition.IN_USE_READ_ONLY, IN_USE, lines,
                    converted.commentedEntries(), converted.omittedEntries(), null);
        }
        return writeMigrated(historyFile, converted, loaded);
    }

    private LoadedNotebook writeMigrated(Path historyFile, HistoryMigration.Converted converted, HistoryLoadResult loaded) {
        Path backup = null;
        try {
            if (Files.exists(historyFile)) {
                backup = BackupCopies.make(historyFile, copier,
                        attempt -> historyFile.getFileName() + HISTORY_BACKUP_SUFFIX + (attempt == 0 ? "" : "." + attempt));
                LOGGER.info("Backed up " + historyFile + " to " + backup);
            }
            NotebookCodec.Encoded encoded = NotebookCodec.encode(converted.notebook(), DisplayMode.AUTO);
            Files.createDirectories(directory);
            AtomicFileWriter.write(currentFile, out -> out.write(encoded.bytes()), mover);
        } catch (IOException | NotebookFormatException exception) {
            LOGGER.log(Level.WARNING, "Cannot convert the history to " + currentFile + ": every source is left as it was", exception);
            return notConverted("L'historique n'a pas pu être converti en carnet : il reste tel quel et sera converti au prochain démarrage, tant que vous n'aurez rien saisi.");
        }
        int lines = converted.notebook().size();
        LOGGER.info("Converted " + historyFile + " to " + currentFile + ": " + lines + " lines, " + converted.commentedEntries()
                + " as comments, " + converted.omittedEntries() + " oldest entries left in the backup, "
                + loaded.rejections().size() + " rejected");
        String status = "Votre historique a été converti en carnet (" + lines + (lines <= 1 ? " ligne" : " lignes") + ").";
        if (converted.omittedEntries() > 0) {
            status += " Les " + converted.omittedEntries() + " entrées les plus anciennes restent "
                    + (backup == null ? "dans l'historique d'origine." : "dans la sauvegarde " + backup.getFileName() + ".");
        }
        return new LoadedNotebook(converted.notebook(), DisplayMode.AUTO, Condition.MIGRATED, status, lines,
                converted.commentedEntries(), converted.omittedEntries(), backup);
    }

    private LoadedNotebook notConverted(String status) {
        return new LoadedNotebook(Notebook.empty(), DisplayMode.AUTO, Condition.HISTORY_NOT_CONVERTED, status, 0, 0, 0, null);
    }

    // ------------------------------------------------------------------------------------------------ saving

    /**
     * Writes the current notebook, atomically. Does nothing, silently, when the store is read only ({@link #isReadOnly()}).
     *
     * @return whether the file was written
     * @throws NotebookStoreException when the file is protected, the notebook is over a limit of the format or the disk refuses
     */
    public boolean saveCurrent(Notebook notebook, DisplayMode displayMode) throws NotebookStoreException {
        if (readOnly) {
            LOGGER.fine("Notebook not written: the store is read only");
            return false;
        }
        if (writeProtected) {
            LOGGER.warning("Refused to write " + currentFile + ": the original file is protected");
            throw new NotebookStoreException("Écriture refusée pour protéger le fichier d'origine : " + currentFile);
        }
        try {
            NotebookCodec.Encoded encoded = NotebookCodec.encode(notebook, displayMode);
            Files.createDirectories(directory);
            AtomicFileWriter.write(currentFile, out -> out.write(encoded.bytes()), mover);
            return true;
        } catch (NotebookFormatException exception) {
            LOGGER.log(Level.WARNING, "The notebook is over a limit of the format and is not written (" + exception.reason() + ")");
            throw new NotebookStoreException(exception.userMessage(), exception);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot write notebook file " + currentFile, exception);
            throw new NotebookStoreException("Impossible d'enregistrer le carnet : " + currentFile, exception);
        }
    }
}
