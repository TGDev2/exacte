package calc.persistence;

import calc.engine.AtomicFileWriter;
import calc.engine.CalculationHistoryException;
import calc.engine.CalculationHistoryStore;
import calc.engine.CalculationRecord;
import calc.engine.HistoryLoadResult;
import calc.engine.HistoryLoadResult.Condition;
import calc.engine.RejectedLine;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The history of Calc on disk, version 2: {@code history.json} in the data directory ({@link DataDirectory}), described
 * in docs/FORMAT-FICHIER.md and decided in docs/adr/0005-format-persistance.md.
 *
 * <p>Opening a store takes an exclusive lock on {@code history.lock}. When another instance holds it, the store is
 * <em>read only</em>: it loads, it never writes. It is also read only for a file written by a newer version of the format,
 * which is never rewritten. Writes always go through {@link AtomicFileWriter}.
 *
 * <p>{@link #loadHistory()} also migrates: a version 1 {@code history.tsv} alone is converted to {@code history.json}
 * and renamed {@code history.tsv.v1.bak}; when both exist the JSON file is authoritative and the TSV file is left alone;
 * on macOS and Linux a {@code ~/.calc/history.tsv} is first adopted when the new directory holds no history.
 *
 * <p>The log carries paths, counts and causes, never the content of an entry.
 */
public final class JsonHistoryStore implements CalculationHistoryStore, AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(JsonHistoryStore.class.getName());

    static final String JSON_FILE = "history.json";
    static final String LEGACY_FILE = "history.tsv";
    static final String LOCK_FILE = "history.lock";
    static final String LEGACY_BACKUP_SUFFIX = ".v1.bak";
    private static final DateTimeFormatter BACKUP_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Copies a file; replaceable so that a test can simulate a disk that refuses the copy. */
    @FunctionalInterface
    public interface FileCopier {
        void copy(Path source, Path target) throws IOException;
    }

    private final Path directory;
    private final Path jsonFile;
    private final Path legacyFile;
    private final Path oldLocationFile;
    private final Clock clock;
    private final FileCopier copier;
    private final AtomicFileWriter.Mover mover;
    private final StoreLock lock;
    // False when a NotebookStore shares its lock with this store and releases it itself.
    private final boolean ownsLock;
    // No write at all, silently: another instance holds the history, or a newer version made the file.
    private boolean readOnly;
    // Writes fail loudly: the file on disk must not be replaced (unreadable, over a limit, no backup possible).
    private boolean writeProtected;

    private JsonHistoryStore(Path directory, Path legacyDirectory, Clock clock, FileCopier copier, AtomicFileWriter.Mover mover,
                             StoreLock sharedLock) {
        this.directory = Objects.requireNonNull(directory);
        this.jsonFile = directory.resolve(JSON_FILE);
        this.legacyFile = directory.resolve(LEGACY_FILE);
        this.oldLocationFile = legacyDirectory == null ? null : legacyDirectory.resolve(LEGACY_FILE);
        this.clock = Objects.requireNonNull(clock);
        this.copier = Objects.requireNonNull(copier);
        this.mover = Objects.requireNonNull(mover);
        this.ownsLock = sharedLock == null;
        this.lock = ownsLock ? acquireLock(directory) : sharedLock;
        this.readOnly = lock.state() == StoreLock.State.BUSY;
        LOGGER.info("History directory: " + directory);
    }

    /** The store of the running system: the directory of {@link DataDirectory} and, if any, the {@code ~/.calc} of version 1. */
    public static JsonHistoryStore openForCurrentUser() {
        DataDirectory.Environment environment = DataDirectory.Environment.system();
        return open(DataDirectory.resolve(environment), DataDirectory.legacyDirectory(environment).orElse(null),
                Clock.systemDefaultZone(), Files::copy, Files::move);
    }

    /** A store in {@code directory}, with no old location to adopt. */
    public static JsonHistoryStore open(Path directory) {
        return open(directory, null, Clock.systemDefaultZone(), Files::copy, Files::move);
    }

    /**
     * @param legacyDirectory the version 1 directory ({@code ~/.calc}) to adopt, or null
     * @param mover           the final move of every atomic write, replaceable by a test that simulates a failing disk
     */
    public static JsonHistoryStore open(Path directory, Path legacyDirectory, Clock clock, FileCopier copier,
                                        AtomicFileWriter.Mover mover) {
        return new JsonHistoryStore(directory, legacyDirectory, clock, copier, mover, null);
    }

    /**
     * A store that uses the lock of another store of the same directory instead of taking its own (the same process cannot
     * lock a file twice): the notebook store of the same directory, which closes the lock.
     */
    static JsonHistoryStore openSharing(StoreLock lock, Path directory, Path legacyDirectory, Clock clock, FileCopier copier,
                                        AtomicFileWriter.Mover mover) {
        return new JsonHistoryStore(directory, legacyDirectory, clock, copier, mover, Objects.requireNonNull(lock));
    }

    static StoreLock acquireLock(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot create " + directory + ", the lock will probably be unavailable", exception);
        }
        return StoreLock.acquire(directory.resolve(LOCK_FILE));
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    /** Releases the lock. The store still loads; it no longer protects the file from another instance. */
    @Override
    public void close() {
        if (ownsLock) {
            lock.close();
        }
    }

    // ------------------------------------------------------------------------------------------------ loading

    @Override
    public HistoryLoadResult loadHistory() throws CalculationHistoryException {
        if (Files.exists(jsonFile)) {
            if (Files.exists(legacyFile)) {
                LOGGER.info("Both " + jsonFile.getFileName() + " and " + legacyFile.getFileName() + " exist in " + directory
                        + ": the JSON file is authoritative, the TSV file is left alone");
            }
            return loadCurrent();
        }
        if (!Files.exists(legacyFile)) {
            adoptOldLocation();
        }
        if (Files.exists(legacyFile)) {
            return loadLegacy();
        }
        return new HistoryLoadResult(List.of(), List.of(), null, usualCondition());
    }

    private HistoryLoadResult loadCurrent() throws CalculationHistoryException {
        DecodedHistory decoded;
        try {
            if (Files.size(jsonFile) > StorageLimits.MAX_FILE_BYTES) {
                throw new HistoryFormatException(HistoryFormatException.Reason.TOO_LARGE);
            }
            decoded = HistoryCodec.decode(Files.readAllBytes(jsonFile));
        } catch (HistoryFormatException exception) {
            throw refuse(jsonFile, exception);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot read " + jsonFile + ", writes are now refused", exception);
            writeProtected = true;
            throw new CalculationHistoryException("Impossible de lire l'historique : " + jsonFile, exception, null);
        }
        if (decoded.isNewerVersion()) {
            readOnly = true;
            LOGGER.warning("History " + jsonFile + " has format version " + decoded.version() + " (supported: "
                    + HistoryCodec.VERSION + "): read only, " + decoded.records().size() + " entries shown, never rewritten");
            return new HistoryLoadResult(decoded.records(), decoded.rejections(), null,
                    decoded.entriesRead() ? Condition.NEWER_VERSION_READ_ONLY : Condition.NEWER_VERSION_NOT_LOADED);
        }
        logRejections(jsonFile, decoded.rejections());
        Path backup = decoded.rejections().isEmpty() ? null : backUp(jsonFile);
        return new HistoryLoadResult(decoded.records(), decoded.rejections(), backup, usualCondition());
    }

    private HistoryLoadResult loadLegacy() throws CalculationHistoryException {
        LegacyTsvReader.LegacyHistory legacy;
        try {
            legacy = LegacyTsvReader.read(legacyFile);
        } catch (HistoryFormatException exception) {
            throw refuse(legacyFile, exception);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot read " + legacyFile + ", writes are now refused", exception);
            writeProtected = true;
            throw new CalculationHistoryException("Impossible de lire l'historique : " + legacyFile, exception, null);
        }
        if (legacy.newerVersion().isPresent()) {
            readOnly = true;
            LOGGER.warning("History " + legacyFile + " has format version " + legacy.newerVersion().getAsInt()
                    + ": it is not read and never rewritten");
            return new HistoryLoadResult(List.of(), List.of(), null, Condition.NEWER_VERSION_NOT_LOADED);
        }
        logRejections(legacyFile, legacy.rejections());
        if (readOnly) {
            LOGGER.info("Version 1 history " + legacyFile + " loaded read only, not converted");
            return new HistoryLoadResult(legacy.records(), legacy.rejections(), null, usualCondition());
        }
        try {
            writeCurrent(legacy.records());
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot convert " + legacyFile + ": it is left untouched", exception);
            return new HistoryLoadResult(legacy.records(), legacy.rejections(), null, Condition.NORMAL);
        }
        Path setAside = setAside(legacyFile);
        LOGGER.info("Converted " + legacyFile + " to " + jsonFile + ": " + legacy.records().size() + " entries, "
                + legacy.rejections().size() + " rejected");
        return new HistoryLoadResult(legacy.records(), legacy.rejections(), setAside, Condition.MIGRATED);
    }

    // The old file is renamed, never overwriting an earlier backup. A failure leaves it where it is: the JSON file wins.
    private static Path setAside(Path file) {
        for (int attempt = 0; attempt < 1000; attempt++) {
            Path target = file.resolveSibling(file.getFileName() + LEGACY_BACKUP_SUFFIX + (attempt == 0 ? "" : "." + attempt));
            try {
                Files.move(file, target);
                LOGGER.info("Renamed " + file + " to " + target);
                return target;
            } catch (FileAlreadyExistsException exists) {
                // never overwrite a backup: try the next name
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Cannot rename " + file + ", it stays in place", exception);
                return null;
            }
        }
        LOGGER.warning("Cannot rename " + file + ": too many backups, it stays in place");
        return null;
    }

    // macOS and Linux: ~/.calc/history.tsv becomes the version 1 file of the new directory, the old one is set aside.
    private void adoptOldLocation() {
        if (readOnly || oldLocationFile == null || !Files.isRegularFile(oldLocationFile)) {
            return;
        }
        try {
            Files.copy(oldLocationFile, legacyFile);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot copy " + oldLocationFile + " to " + legacyFile + ", it stays where it is", exception);
            return;
        }
        LOGGER.info("Copied the history of the old location " + oldLocationFile + " to " + legacyFile);
        setAside(oldLocationFile);
    }

    private Condition usualCondition() {
        return readOnly ? Condition.IN_USE_READ_ONLY : Condition.NORMAL;
    }

    private static void logRejections(Path file, List<RejectedLine> rejections) {
        for (RejectedLine rejected : rejections) {
            LOGGER.warning("Rejected entry " + rejected.lineNumber() + " of " + file + " (" + rejected.cause() + "): "
                    + rejected.detail());
        }
    }

    // A file that cannot be read as a whole. A bound exceeded: not touched, writes refused. Damaged: copied, then rewritable.
    private CalculationHistoryException refuse(Path file, HistoryFormatException exception) {
        LOGGER.log(Level.WARNING, "Cannot use " + file + " (" + exception.reason() + ")", exception);
        if (exception.reason().isLimit()) {
            writeProtected = true;
            return new CalculationHistoryException("Historique hors limites : " + file, exception, null);
        }
        return new CalculationHistoryException("Historique illisible : " + file, exception, file.equals(jsonFile) ? backUp(file) : file);
    }

    // The timestamped copy made before anything is replaced; without it writes are refused. None when read only.
    private Path backUp(Path file) {
        if (readOnly) {
            return null;
        }
        String baseName = file.getFileName() + "." + LocalDateTime.now(clock).format(BACKUP_TIMESTAMP);
        try {
            Path backup = BackupCopies.make(file, copier, attempt -> baseName + (attempt == 0 ? "" : "-" + attempt) + ".bak");
            LOGGER.info("Backed up " + file + " to " + backup);
            return backup;
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot back up " + file + ", writes are now refused", exception);
            writeProtected = true;
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ saving

    @Override
    public void saveHistory(List<CalculationRecord> history) throws CalculationHistoryException {
        if (readOnly) {
            LOGGER.fine("History not written: the store is read only");
            return;
        }
        if (writeProtected) {
            LOGGER.warning("Refused to write " + jsonFile + ": the original file is protected");
            throw new CalculationHistoryException("Écriture refusée pour protéger le fichier d'origine : " + jsonFile);
        }
        try {
            writeCurrent(history);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Cannot write history file " + jsonFile, exception);
            throw new CalculationHistoryException("Impossible d'enregistrer l'historique : " + jsonFile, exception);
        }
    }

    private void writeCurrent(List<CalculationRecord> history) throws IOException {
        HistoryCodec.Encoded encoded = HistoryCodec.encode(history);
        if (encoded.dropped() > 0) {
            LOGGER.warning(encoded.dropped() + " oldest entries are left out of " + jsonFile + ": the history is over the limits ("
                    + StorageLimits.MAX_ENTRIES + " entries, " + StorageLimits.MAX_FILE_BYTES + " bytes)");
        }
        Files.createDirectories(directory);
        AtomicFileWriter.write(jsonFile, out -> out.write(encoded.bytes()), mover);
    }
}
