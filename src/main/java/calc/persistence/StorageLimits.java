package calc.persistence;

import calc.number.NumericLimits;

/**
 * The bounds applied when a history file is read, and respected when it is written, so that the application never
 * writes a file it would refuse to read. They are justified in docs/adr/0005-format-persistance.md.
 *
 * <p>Every one is checked before the work it protects: the file size before the file is read, the depth, string and
 * number lengths while the text is scanned, the entry count before any entry is recalculated.
 */
public final class StorageLimits {
    /**
     * Largest history file, in bytes. A usual entry takes 100 to 250 bytes, so 10 000 of them fill about 2 MB; 8 MiB
     * leaves a factor of four for large numbers and bounds the memory used to read a file.
     */
    public static final int MAX_FILE_BYTES = 8 * 1024 * 1024;

    /**
     * Most entries in a file. Every entry is recalculated at load time (about 0.1 ms for a usual one, a few
     * milliseconds with 4096-bit numbers); 10 000 entries are years of daily use.
     */
    public static final int MAX_ENTRIES = 10_000;

    /**
     * Longest JSON string, in characters. The longest value text is a fraction of two 4096-bit components, about 2470
     * characters ({@link NumericLimits#MAX_LITERAL_LENGTH} is 4100); the rest of the room is for the free text of later
     * versions of the format.
     */
    public static final int MAX_STRING_LENGTH = 16_384;

    /** Deepest nesting of arrays and objects. The format uses 5 levels; later versions have room to grow. */
    public static final int MAX_DEPTH = 32;

    /** Longest JSON number literal, in characters. Only {@code version} is a number: no value is ever one. */
    public static final int MAX_NUMBER_LENGTH = 32;

    private StorageLimits() {
    }
}
