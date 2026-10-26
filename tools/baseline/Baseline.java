import calc.engine.CalculationException;
import calc.engine.CalculationHistoryException;
import calc.engine.CalculationRecord;
import calc.engine.CalculatorEngine;
import calc.engine.FileCalculationHistoryStore;
import calc.engine.HistoryLoadResult;
import calc.engine.Operation;
import calc.ui.CalculatorPresenter;
import calc.ui.CalculatorViewModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Standalone capture of the reference behaviour of the current engine and history store.
 * Not part of the production code: it only calls the existing classes as they are.
 *
 * Usage: java -cp <classes> Baseline <repository root>
 * Writes testdata/golden/engine-v1.tsv and testdata/legacy-v1/* (UTF-8, LF unless stated).
 */
public final class Baseline {
    private static final Pattern UNICODE_ESCAPE = Pattern.compile("<U\\+([0-9A-F]{4})>");
    private static final Pattern REPEAT = Pattern.compile("<rep:(\\d+):([^>]*)>");
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    /** One golden case. Inputs use the notation documented in tools/baseline/README.md. */
    private record Case(String operation, String first, String second, boolean hashed) {
    }

    private static Case c(String operation, String first, String second) {
        return new Case(operation, first, second, false);
    }

    private static Case hashed(String operation, String first, String second) {
        return new Case(operation, first, second, true);
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>();
        // ADDITION
        cases.add(c("ADDITION", "0,1", "0,2"));
        cases.add(c("ADDITION", "0.1", "0.2"));
        cases.add(c("ADDITION", "1,5", "2,25"));
        cases.add(c("ADDITION", "-1", "1"));
        cases.add(c("ADDITION", "-0", "-0"));
        cases.add(c("ADDITION", "0,000", "0"));
        cases.add(c("ADDITION", "+5", "-3"));
        cases.add(c("ADDITION", ".5", "5."));
        cases.add(c("ADDITION", "1000000000000000000000", "1"));
        cases.add(c("ADDITION", "0,000000000001", "0,000000000001"));
        // SUBTRACTION
        cases.add(c("SUBTRACTION", "5", "7,5"));
        cases.add(c("SUBTRACTION", "0,3", "0,1"));
        cases.add(c("SUBTRACTION", "-0", "0"));
        cases.add(c("SUBTRACTION", "123456789012345678901234567890", "1"));
        // MULTIPLICATION
        cases.add(c("MULTIPLICATION", "0,1", "3"));
        cases.add(c("MULTIPLICATION", "-2", "-3"));
        cases.add(c("MULTIPLICATION", "0", "-5"));
        cases.add(c("MULTIPLICATION", "123456789,123456789", "987654321,987654321"));
        cases.add(c("MULTIPLICATION", "0,000001", "0,000001"));
        cases.add(c("MULTIPLICATION", "1<rep:999:0>", "10"));
        // DIVISION
        cases.add(c("DIVISION", "1", "3"));
        cases.add(c("DIVISION", "2", "3"));
        cases.add(c("DIVISION", "-2", "3"));
        cases.add(c("DIVISION", "1", "3000000000000"));
        cases.add(c("DIVISION", "6", "3"));
        cases.add(c("DIVISION", "1", "8"));
        cases.add(c("DIVISION", "0", "5"));
        cases.add(c("DIVISION", "8", "0"));
        cases.add(c("DIVISION", "8", "0,000"));
        cases.add(c("DIVISION", "1", "-7"));
        // POWER
        cases.add(c("POWER", "2", "10"));
        cases.add(c("POWER", "2", "-2"));
        cases.add(c("POWER", "-2", "3"));
        cases.add(c("POWER", "-2", "3,0"));
        cases.add(c("POWER", "1,5", "2"));
        cases.add(c("POWER", "3", "-30"));
        cases.add(c("POWER", "10", "-13"));
        cases.add(c("POWER", "0", "0"));
        cases.add(c("POWER", "0", "-1"));
        cases.add(c("POWER", "2", "1000"));
        cases.add(hashed("POWER", "2", "999"));
        cases.add(hashed("POWER", "2", "-999"));
        cases.add(c("POWER", "10", "999"));
        cases.add(c("POWER", "10", "1000"));
        cases.add(c("POWER", "2", "-1000"));
        cases.add(c("POWER", "2", "1,5"));
        cases.add(c("POWER", "2", "99999999999"));
        cases.add(c("POWER", "12345678901", "100"));
        // ROOT
        cases.add(c("ROOT", "2", ""));
        cases.add(c("ROOT", "0,00000000000000000000000001", ""));
        cases.add(c("ROOT", "-4", ""));
        cases.add(c("ROOT", "0", ""));
        cases.add(c("ROOT", "16", ""));
        cases.add(c("ROOT", "0,25", ""));
        cases.add(c("ROOT", "1000000", "abc"));
        // Input handling and every input error message
        cases.add(c("ADDITION", "abc", "1"));
        cases.add(c("ADDITION", "1", "abc"));
        cases.add(c("ADDITION", "1e3", "1"));
        cases.add(c("ADDITION", "1 000", "1"));
        cases.add(c("ADDITION", "1<U+00A0>000", "1"));
        cases.add(c("ADDITION", "1,000.5", "1"));
        cases.add(c("ADDITION", "1<U+202F>000<U+202F>000,5", "0"));
        cases.add(c("ADDITION", "<U+00A0>7<U+00A0>", "1"));
        cases.add(c("ADDITION", "", "1"));
        cases.add(c("ADDITION", "   ", "1"));
        cases.add(c("ADDITION", "1", ""));
        cases.add(c("ADDITION", "<rep:1006:1>", "1"));
        cases.add(c("ADDITION", "1", "<rep:1006:1>"));
        cases.add(c("ROOT", "", ""));
        cases.add(c("(none)", "1", "1"));
        return cases;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: Baseline <repository root>");
        }
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        writeGolden(root.resolve("testdata").resolve("golden").resolve("engine-v1.tsv"));
        writeLegacy(root.resolve("testdata").resolve("legacy-v1"));
    }

    // ---------------------------------------------------------------- golden outputs

    private static void writeGolden(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        CalculatorEngine engine = new CalculatorEngine();
        StringBuilder out = new StringBuilder("operation\tfirst\tsecond\toutcome\tdisplay\treusable\n");
        for (Case testCase : cases()) {
            String first = expand(testCase.first());
            String second = expand(testCase.second());
            String outcome;
            String display;
            String reusable;
            try {
                Operation operation = testCase.operation().equals("(none)") ? null : Operation.valueOf(testCase.operation());
                CalculationRecord record = engine.calculate(operation, first, second);
                outcome = "OK";
                display = record.getFormattedResult();
                reusable = reusableValue(operation, first, second);
                if (!display.equals(reusable)) {
                    throw new IllegalStateException("displayed and reusable values differ for " + testCase);
                }
                if (testCase.hashed()) {
                    display = summary(display);
                    reusable = summary(reusable);
                }
            } catch (CalculationException exception) {
                outcome = "ERROR";
                display = exception.getMessage();
                reusable = "";
            }
            out.append(testCase.operation()).append('\t')
                    .append(testCase.first()).append('\t')
                    .append(testCase.second()).append('\t')
                    .append(outcome).append('\t')
                    .append(display).append('\t')
                    .append(reusable).append('\n');
        }
        Files.write(file, out.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** Value the presenter puts back into the first field when the user reuses the last result. */
    private static String reusableValue(Operation operation, String first, String second) {
        CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine());
        presenter.onOperationChanged(operation, "", "");
        presenter.onCalculate(first, second);
        CalculatorViewModel view = presenter.onReuseLastResult("", "");
        return view.firstInput();
    }

    private static String summary(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return "len=" + value.length() + " sha256=" + HexFormat.of().formatHex(digest);
    }

    /** Expands the &lt;U+XXXX&gt; and &lt;rep:N:text&gt; notations used in the golden file. */
    private static String expand(String notation) {
        Matcher repeats = REPEAT.matcher(notation);
        StringBuilder withRepeats = new StringBuilder();
        while (repeats.find()) {
            repeats.appendReplacement(withRepeats, Matcher.quoteReplacement(repeats.group(2).repeat(Integer.parseInt(repeats.group(1)))));
        }
        repeats.appendTail(withRepeats);

        Matcher escapes = UNICODE_ESCAPE.matcher(withRepeats);
        StringBuilder result = new StringBuilder();
        while (escapes.find()) {
            escapes.appendReplacement(result, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(escapes.group(1), 16))));
        }
        escapes.appendTail(result);
        return result.toString();
    }

    // ---------------------------------------------------------------- legacy history files

    private record LegacyFile(String name, byte[] content, String description) {
    }

    private static void writeLegacy(Path directory) throws Exception {
        Files.createDirectories(directory);
        List<LegacyFile> files = new ArrayList<>();
        files.add(new LegacyFile("nominal.tsv", realStoreFile("nominal.tsv", nominalCalculations()),
                "Written by the real FileCalculationHistoryStore after six calculations (one per operation, the root has an empty column 2)."));
        files.add(new LegacyFile("rounded.tsv", realStoreFile("rounded.tsv", new String[][] {{"DIVISION", "1", "3"}}),
                "Written by the real FileCalculationHistoryStore after `1 / 3` (rounded result)."));
        files.add(new LegacyFile("legacy-float.tsv", utf8("ADDITION\t0,1\t0,2\t0.30000000000000004\n"),
                "Headerless, as written by the oldest versions: a floating-point drifted result `0.30000000000000004` for `0,1 + 0,2`."));
        files.add(new LegacyFile("crlf.tsv", utf8("ADDITION\t1\t2\t3\r\nMULTIPLICATION\t2\t4\t8\r\nROOT\t9\t\t3\r\n"),
                "Headerless, three valid lines with CRLF line endings."));
        files.add(new LegacyFile("bom.tsv", concat(BOM, utf8("ADDITION\t1\t2\t3\nSUBTRACTION\t5\t2\t3\n")),
                "Headerless, two valid lines, file starts with a UTF-8 byte order mark."));
        files.add(new LegacyFile("one-corrupted-line.tsv",
                utf8("ADDITION\t1\t2\t3\nthis line is not a calculation\nMULTIPLICATION\t3\t4\t12\n"),
                "Headerless, two valid lines around one line that is not a calculation."));
        files.add(new LegacyFile("inconsistent.tsv", utf8("ADDITION\tabc\t2\t5\n"),
                "Headerless, a single line whose operand `abc` is not a number (and `abc + 2` is not 5)."));
        files.add(new LegacyFile("empty.tsv", new byte[0], "Zero bytes."));
        files.add(new LegacyFile("wrong-result.tsv", utf8("ADDITION\t1\t2\t5\n"),
                "Headerless, a valid line whose stored result (5) contradicts the operands (1 + 2)."));
        files.add(new LegacyFile("bom-header.tsv", concat(BOM, utf8("#calc-history v1\nADDITION\t1\t2\t3\n")),
                "Versioned header `#calc-history v1` preceded by a UTF-8 byte order mark, one valid line."));
        files.add(new LegacyFile("future-version.tsv", utf8("#calc-history v2\nADDITION\t1\t2\t3\n"),
                "Versioned header `#calc-history v2` (newer than the supported version 1), one line."));

        StringBuilder readme = new StringBuilder();
        readme.append("# testdata/legacy-v1 - reference history files\n\n")
                .append("Generated by `tools/baseline/Baseline.java` (see `tools/baseline/README.md`); do not edit by hand.\n")
                .append("Each file is a `history.tsv` as the application could find it on disk. The bytes are fixed and must not change.\n")
                .append("`.gitattributes` in this folder disables line-ending conversion so the CRLF and BOM files stay byte-exact.\n\n")
                .append("Behaviour OBSERVED when the current code loads each file: the file is copied to a temporary folder, then loaded\n")
                .append("by `FileCalculationHistoryStore` (counts) and by a `CalculatorPresenter` built on it (status message and entries).\n")
                .append("Format of the files: optional `#calc-history v1` header, then one line per calculation with 4 tab-separated columns:\n")
                .append("`Operation` constant name, first operand, second operand (empty for a root), result.\n\n");
        for (LegacyFile file : files) {
            Files.write(directory.resolve(file.name()), file.content());
            readme.append(observe(file));
        }
        Files.write(directory.resolve("README.md"), readme.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve(".gitattributes"), "* -text\n".getBytes(StandardCharsets.UTF_8));
    }

    private static String[][] nominalCalculations() {
        return new String[][] {
            {"ADDITION", "0,1", "0,2"},
            {"SUBTRACTION", "10", "2,5"},
            {"MULTIPLICATION", "1,5", "4"},
            {"DIVISION", "7", "2"},
            {"POWER", "2", "10"},
            {"ROOT", "16", ""},
        };
    }

    /** Runs the calculations through the real presenter and store, then returns the bytes written to disk. */
    private static byte[] realStoreFile(String name, String[][] calculations) throws IOException {
        Path temp = Files.createTempDirectory("baseline-write");
        try {
            Path file = temp.resolve("history.tsv");
            CalculatorPresenter presenter = new CalculatorPresenter(new CalculatorEngine(), new FileCalculationHistoryStore(file));
            for (String[] calculation : calculations) {
                presenter.onOperationChanged(Operation.valueOf(calculation[0]), "", "");
                presenter.onCalculate(calculation[1], calculation[2]);
            }
            return Files.readAllBytes(file);
        } finally {
            deleteTree(temp);
        }
    }

    private static String observe(LegacyFile file) throws Exception {
        Path temp = Files.createTempDirectory("baseline-read");
        try {
            Path storeCopy = temp.resolve("a").resolve("history.tsv");
            Path presenterCopy = temp.resolve("b").resolve("history.tsv");
            for (Path copy : List.of(storeCopy, presenterCopy)) {
                Files.createDirectories(copy.getParent());
                Files.write(copy, file.content());
            }

            StringBuilder text = new StringBuilder();
            text.append("## ").append(file.name()).append("\n\n").append(file.description()).append("\n\n");
            text.append("- Size: ").append(file.content().length).append(" bytes\n");
            try {
                HistoryLoadResult result = new FileCalculationHistoryStore(storeCopy, FIXED_CLOCK, Files::copy).loadHistory();
                text.append("- Store: ").append(result.records().size()).append(" entries restored, ")
                        .append(result.skippedLines()).append(" lines skipped, backup copy ")
                        .append(result.backupFile() != null ? "created" : "not created").append("\n");
            } catch (CalculationHistoryException exception) {
                text.append("- Store: loading throws `").append(exception.getMessage().replace(temp.toString(), "<tmp>")).append("`\n");
            }

            CalculatorPresenter presenter = new CalculatorPresenter(
                    new CalculatorEngine(), new FileCalculationHistoryStore(presenterCopy, FIXED_CLOCK, Files::copy));
            CalculatorViewModel view = presenter.createInitialView();
            text.append("- Status (").append(view.statusTone()).append("): ").append(view.statusMessage()).append("\n");
            if (view.historyEntries().isEmpty()) {
                text.append("- Entries displayed: none\n");
            } else {
                text.append("- Entries displayed:\n");
                for (CalculationRecord record : view.historyEntries()) {
                    text.append("  - `").append(record).append("`\n");
                }
            }
            text.append("\n");
            return text.toString();
        } finally {
            deleteTree(temp);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = new byte[first.length + second.length];
        System.arraycopy(first, 0, joined, 0, first.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : (Iterable<Path>) paths.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(path);
            }
        }
    }
}
