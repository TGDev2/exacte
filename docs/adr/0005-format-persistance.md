# 0005 - Persistence: versioned JSON history (version 2), migration, lock and data directories

## Status (Statut)

Accepted. Date: 2026-10-26. Epic E07.

## Context (Contexte)

Until E06 the history was a TSV file of four columns (`history.tsv`, version 1): the name of the `Operation` constant, two
operand texts, one result text. Three weaknesses, measured on the code before E07:

| Weakness | Consequence |
|---|---|
| The key is `Operation.name()` | renaming a Java constant breaks every existing file |
| A result is one text; an approximate value (a root) has no place for its interval | a line built from a remembered root is written as the exact recalculation of a *point* of the interval; a power of it above about 48 cannot be stored at all (ADR 0004, section 9) |
| No lock, no format identifier, a version only in a comment line | two windows overwrite each other's history; a file from a later version can only be recognised by its comment |

E07 replaces it with a documented, versioned JSON file (`docs/FORMAT-FICHIER.md`). A later Epic (E11, the notebook of
expression lines) will extend it to version 3: the format identifier, the version number and the rule "unknown fields are
ignored" are there for it, and nothing of the notebook is implemented here.

## Decision (Décision)

### 1. Format

`history.json`, UTF-8, one JSON object: `{"format": "calc-history", "version": 2, "entries": [...]}`. An entry is
`{"op": "<identifier>", "operands": [...], "result": {...}}`.

- **Operation identifiers** are lowercase and stable: `addition`, `subtraction`, `multiplication`, `division`, `power`,
  `sqrt`. They come from an explicit table (`OperationIds`, a `switch` without `default`: a new `Operation` constant does not
  compile until it has an identifier) and are tested against a literal copy of the table, so renaming a constant changes
  nothing in a file. An identifier is never changed nor reused.
- **Values are texts**, never JSON numbers: the canonical text of the numeric core (`Rational.toString`, `p` or `p/q`,
  reduced, denominator positive). `1/3` is written `"1/3"`; a bound of a root is a fraction of two integers. The reader
  accepts only that form (a text that parses but is not canonical, such as `"0,5"`, `"1e3"` or `"2/4"`, rejects the entry), so
  no floating-point literal and no locale-dependent text can enter. The only JSON number of the file is `version`.
- **A result** is `{"exact": true, "value": t}` or `{"exact": false, "lower": t, "upper": t}` with `lower < upper`.
- **An operand** is the text `t` of an exact value, or the same object as a result when it is an approximate value. Why: the
  check below recalculates an entry from its operands; if a remembered root were stored as a point, the recalculation would
  differ from the stored interval (the defect version 1 had to work around). Storing the interval makes the check exact and
  loses nothing. The example of the brief (`"operands": ["1","3"]`) is the usual case; the object form is its extension.
- **Unknown fields are ignored** at every level; they are not kept when the application writes the file again (a file with
  unknown fields of the *same* version is rewritten without them; a file of a *newer* version is never rewritten, see 4).

### 2. Reading: nothing is trusted

1. The file size is checked before the file is read; the bytes must be UTF-8; the JSON is read with the bounds of section 5.
2. The document must have `"format": "calc-history"` and an integer `version`.
3. Each entry is **recalculated by the engine** from its stored operands and compared (`CertifiedValue.equals`, the exact
   bounds) with the stored result. An entry that is not an object, has an unknown operation, a wrong operand count,
   a non-canonical or out-of-range value, operands the engine refuses, or a stored result that differs from the recalculation is
   **rejected and counted** (`RejectedLine`: position, cause, a detail that never repeats the content), never shown.
4. `HistoryLoadResult` carries the entries, the rejections with their causes, the backup path and the `Condition` of the store
   (normal, migrated, newer version read only / not loaded, in use by another instance).

A consequence to keep in mind: the check compares with the *current* engine. If a later Epic changes the way a result is
computed (for instance the default precision of a root), old intervals would be rejected as inconsistent. Such a change must
bump the `version` and keep a reader for version 2, as this Epic does for version 1.

### 3. Writing

Always through `AtomicFileWriter` (temporary file, `force`, atomic move): a failed write leaves the previous file untouched
(tested with a failing move and with an occupied temporary name). The application never writes a file it would refuse to read:
when the history exceeds 10 000 entries or 8 MiB, the **oldest** entries are left out of the file (logged), the window keeps them.
An empty history is written as an empty document (no file deletion: the format identifier stays).

When the last load rejected entries, a timestamped copy of the original (`history.json.yyyyMMdd-HHmmss.bak`, `-1`, `-2`... if the
name exists, never overwritten) is made *at load time*, before any write, as in version 1; if the copy fails, every write is
refused for the lifetime of the store.

### 4. Version policy

| `version` | Behaviour |
|---|---|
| 2 | read and written |
| above 2 | **read only when possible** (best effort with the rules of version 2: entries that are not understood are skipped), **never rewritten, never copied, never renamed**; status « Historique créé par une version plus récente de Calc : ouvert en lecture seule. ». If `entries` cannot be read, nothing is shown and nothing is written (« ... il n'a pas pu être ouvert et ne sera pas modifié. ») |
| below 2, missing, not an integer | damaged file |
| an integer too large for an `int` | a newer version (found by fuzzing the version 1 header in E04) |

A damaged file (not JSON, wrong format identifier, bad version, structure error) is **copied** (timestamped), reported
(« L'historique précédent n'a pas pu être restauré... Copie de sauvegarde : ... ») and may then be replaced by the next save. A file that
**exceeds a bound** (section 5) is neither read further nor copied, and every write is refused: it was not produced by this
application, so nothing is overwritten without a decision of the user.

### 5. Limits (`StorageLimits`)

| Limit | Value | Justification |
|---|---|---|
| File size | 8 MiB | a usual entry is 100-250 bytes (10 000 entries: 1.2 MB measured); 8 MiB leaves a factor of 7, and bounds the memory used to read |
| Entries | 10 000 | years of use; each entry is recalculated at load time: 10 000 usual entries load in 114 ms, an 8 MiB file of 600-digit entries in 914 ms (measured, `HostileFilesTest`) |
| String length | 16 384 characters | the longest value text is a fraction of two 4096-bit components, about 2470 characters (`NumericLimits.MAX_LITERAL_LENGTH` is 4100); room for the free text of later versions |
| Depth | 32 | the format uses 5 levels; the parser is recursive and the depth is checked before recursing, so no input can overflow the stack |
| Number literal | 32 characters | no value is a JSON number; only `version` is read, as an integer of at most 9 digits |

Each limit is checked before the work it protects: the size before reading, the depth/string/number while scanning (a string or
a number of megabytes is refused as soon as it is too long), the entry count before any recalculation, and `Rational.parse` bounds
every value text before any power of ten is built. A violation is a `HistoryFormatException` with a reason, never a hang nor an
out-of-memory error. Measured (best of three, warm), refusals take 0 to 2 ms for: one million nested arrays, 500 000 nested objects,
a 6 MB string, a 6 MB key, a number of 3 million digits (as `version`, in an unknown field, as an exponent), 10 001 entries, a file
one byte over the limit (not even read: a 100 MB file is refused in 1 ms), a million equal keys.

### 6. JSON library: none, an internal reader

| | Jackson (`jackson-core`) | Gson | Internal reader (chosen) |
|---|---|---|---|
| Version found 2026-10-26 (web search, not checked on the official pages) | 2.21.5 (3.2.1 for `tools.jackson`) | 2.14.0 | - |
| Licence | Apache 2.0 | Apache 2.0 | project |
| Read limits | yes: `StreamReadConstraints` (nesting depth, string length, number length, document length); defaults are large (nesting 1000, string 20 000 000, document unlimited) and must be set explicitly; limit bypasses have been fixed in several releases (e.g. in the byte-based parsers) | nesting limit only (255 by default since 2.12, configurable on `JsonReader`); **no limit on a string, a number or the document** | all of section 5, in 60 lines |
| Dependency | a first runtime dependency (the project has none: `calc.jar` has no classpath, `jpackage` bundles the classes only) | the same | none |
| Numbers | parsed to `double`/`BigDecimal` unless the streaming API is used with care | `JsonReader.nextString()` possible, but the tree API reads `double` | the literal is kept as text, never converted |
| Cost of ownership | updates for security advisories of the parser | idem | about 280 lines and its own tests |

The format is a handful of strings, arrays and objects; every value is a text; the security requirement (hard bounds, no
numeric conversion) is the one thing a general library makes optional. A library would need explicit configuration of every limit
and a decision on each advisory. The internal reader is strict RFC 8259 (no comment, no trailing comma, no duplicate key, no
control character in a string, no leading zero), 280 lines, tested by examples (`JsonParserTest`), by a differential check against the
independent writer of the generators, and by fuzzing (`HistoryJsonFuzzProperties`). If the format grows beyond what is reasonable to
maintain (E11), Jackson with explicit constraints is the replacement; the reader sits behind `HistoryCodec`, nothing else sees JSON.

### 7. Migration of version 1

At startup (`JsonHistoryStore.loadHistory`):

1. `history.json` exists: it is authoritative. A `history.tsv` next to it is **not touched** (logged).
2. Only `history.tsv` exists: it is read by `LegacyTsvReader` (the tolerant version 1 loader, kept for this, without its
   copies and write protection; the version 1 rules of ADR 0004 section 9 apply unchanged). Then **the version 2 file is written
   first** (atomically), and only then `history.tsv` is **renamed** `history.tsv.v1.bak` (`.v1.bak.1`, `.2`... if the name exists:
   a backup is never overwritten). Status « Historique converti au nouveau format (n entrées). », plus the rejected lines
   with the path of the backup. The rename is the backup: the original, corrupted lines included, is kept byte for byte.
3. A failure leaves version 1 intact: if the version 2 file cannot be written nothing is renamed and the next start retries; if only the
   rename fails, the next start finds both files and takes rule 1. The migration is therefore **idempotent**.
4. A version 1 file whose header announces a version above 1 is neither read nor migrated nor renamed (read only,
   « ... il n'a pas pu être ouvert et ne sera pas modifié. »). A file that is not UTF-8 or is over the size limit is refused as in section 4.
5. macOS and Linux: if neither file exists in the data directory and `~/.calc/history.tsv` exists, it is **copied** to the data
   directory, the old one is renamed `history.tsv.v1.bak` in `~/.calc` (kept, not deleted), the event is logged, and rule 2 applies. Windows
   never used `~/.calc` except when `APPDATA` was missing, a case this Epic drops (the fallback is now the Roaming folder of the home).
6. A read-only store (section 8) loads version 1 but does not convert it.

The migration restores the **exact** value of each line (`1 / 3` stored as the rounded `0,333333333333` comes back as `1/3`), the
result column being only checked, as in ADR 0004.

### 8. Lock

At opening, a store takes `FileChannel.tryLock()` on `history.lock` in the data directory (a file that holds no data). If another
instance holds it (an `OverlappingFileLockException` means the same process), the store is **read only**: it loads (history shown),
`saveHistory` returns without writing and without exception, no migration, no backup is made; the presenter says
« Calc est déjà ouvert : l'historique de cette fenêtre ne sera pas enregistré. », and after a calculation that the history is not
saved. The lock is released by `close()` (a shutdown hook in `Main`). It is an operating system lock: it is released when the process
ends, however it ends. **Verified**: `StoreLockTest` starts a process that takes the lock, checks that a store here is read only,
kills it (`destroyForcibly`, no release code runs) and takes the lock again 55 ms after (Windows 11; the test waits up to 10 s for
the system to close the handles of the dead process). The lock file is left in place: removing it would race with an instance that has
just opened it. If the lock cannot be requested at all (no permission, a file system without locks) the store logs it and goes on without
protection: refusing to save the history because of the protection of the history was judged worse.

### 9. Data directories (`DataDirectory`, environment injected)

| System | Directory |
|---|---|
| Windows | `%APPDATA%\Calc`, or `<home>\AppData\Roaming\Calc` when `APPDATA` is missing or blank |
| macOS | `~/Library/Application Support/Calc` |
| Linux and others | `$XDG_DATA_HOME/calc` when it is an absolute path, else `~/.local/share/calc` |

### 10. Logging

`java.util.logging` (the project's choice): data directory, lock outcome, migration (counts, backup paths), rejections (position and cause,
never the content), backups, limits. Warnings for what loses or refuses something.

## Alternatives considered (Alternatives considérées)

- **Jackson or Gson**: see section 6.
- **SQLite or another embedded database**: a dependency with a native part on three systems, for a file of a few hundred KB; the format must
  stay readable and editable by the user.
- **Keeping TSV with a new column**: no nesting for the extension of E11, still no identifier for the unknown fields.
- **Writing an approximate operand as a point** (as version 1): the recalculation check would need a tolerance, which ADR 0004 showed unsound.
- **Rejecting a history that exceeds the limits** instead of leaving out its oldest entries when writing: it would turn the 10 001st calculation
  into a history that cannot be opened.
- **Copying a file that exceeds a limit** before refusing it: it can be as large as the disk; the user can copy it.
- **Deleting the lock file at close**: races with a starting instance.
- **A lock by PID file**: stale after a crash, which the system lock avoids.

## Consequences (Conséquences)

- `FileCalculationHistoryStore` is removed; `LegacyRounding` and the version 1 reader move to `calc.persistence`. `tools/baseline/Baseline.java`
  documents the version 1 behaviour and uses the removed class: it can only be run on a commit before E07 (noted in its README); the
  reference files `src/test/resources/legacy/v1` are unchanged and are now the input of the migration tests.
- The brief speaks of 8 reference files; the repository has 11 (`legacy/v1`), all migrated in `LegacyMigrationTest` and `CalculatorHistoryLegacyFilesTest`.
- Remaining risk: a store stays read only for its lifetime if the other instance closes later; a second window cannot take over.
- A version 2 reader rejects an entry whose interval the engine would compute differently (section 2).
- The user-visible words change from « ligne » to « entrée » in the restore status.
