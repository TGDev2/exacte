package calc.document;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import calc.document.DocumentException.Kind;
import calc.engine.AtomicFileWriter;
import calc.format.DisplayMode;
import calc.notebook.NotebookCommand;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.CaseSensitivity;
import calc.testing.NotebookGenerators;
import calc.ui.DisplayModeStore;
import calc.ui.StatusTone;
import calc.ui.notebook.NotebookDialogs;
import calc.ui.notebook.NotebookPresenter;
import calc.ui.notebook.NotebookViewModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * E19: a file of a newer version than the one Calc writes is never rewritten, and that holds for the <em>target</em> of
 * « Enregistrer sous » too, whether or not that file is the open document (defect D5 of docs/RECETTE.md). The refusal comes
 * before the question « Remplacer ? » and before any byte is written, the temporary file included. A target that is unreadable,
 * that is not a notebook, or whose version is the current one or an older one keeps its behaviour (the confirmation, then the
 * replacement), and so do the exports. Two levels: the {@link DocumentService} alone, and the {@link NotebookPresenter} with
 * its dialogs played by a script. The same check in the real window is left to a human (see the report of the task).
 */
@Tag("acceptance")
class FutureVersionTargetAcceptanceTest {
    private static final String CURRENT = "carnet-courant.calc.json";
    private static final String READ_ONLY_MESSAGE =
            "Ce carnet a été créé par une version plus récente de Calc : il est en lecture seule. Enregistrez-le sous un autre nom.";

    /** A readable file of a version far ahead, with fields this version does not know: it must survive byte for byte. */
    private static final String FUTURE_99 = "{\n  \"format\": \"calc-notebook\",\n  \"version\": 99,\n"
            + "  \"owner\": {\"name\": \"Zoé\", \"tags\": [1, 2, 3]},\n"
            + "  \"lines\": [{\"id\": \"1\", \"source\": \"1 + 1\", \"later\": true}]\n}\n";

    @TempDir
    Path root;

    private Path data;
    private Path documents;
    private NotebookStore store;
    private final AtomicInteger moves = new AtomicInteger();
    private final List<NotebookPresenter> presenters = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        data = Files.createDirectories(root.resolve("data"));
        documents = Files.createDirectories(root.resolve("documents"));
        store = NotebookStore.open(data);
    }

    @AfterEach
    void stop() {
        store.close();
    }

    // ------------------------------------------------------------------------------------------------ the future files

    /** Every shape of a file that is ahead of the version written: readable lines, lines that cannot be read, a huge version. */
    static Stream<Arguments> futureFiles() {
        return Stream.of(
                Arguments.of("version 99 with unknown fields", FUTURE_99),
                Arguments.of("version just above the current one (4)",
                        "{\"format\":\"calc-notebook\",\"version\":4,\"lines\":[{\"id\":\"1\",\"source\":\"x = 1\",\"extra\":[null]}],\"more\":1}"),
                Arguments.of("version 9 whose lines cannot be read", "{\"format\":\"calc-notebook\",\"version\":9,\"blocks\":[{\"t\":\"x\"}]}"),
                Arguments.of("a version that does not fit an int", "{\"format\":\"calc-notebook\",\"version\":99999999999999999999,\"lines\":[]}"));
    }

    /** Targets that are not a file of a future version: today's behaviour, the confirmation then the replacement, stays. */
    static Stream<Arguments> otherTargets() throws Exception {
        byte[] sameVersion = NotebookCodec.encode(NotebookGenerators.notebook(List.of("old = 1")), DisplayMode.AUTO).bytes();
        return Stream.of(
                Arguments.of("not JSON", "this is not a notebook".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("empty file", new byte[0]),
                Arguments.of("invalid UTF-8", new byte[] {'{', (byte) 0xC3, (byte) 0x28, '}'}),
                Arguments.of("JSON that is not a notebook", "{\"format\":\"something-else\",\"version\":99,\"lines\":[]}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("JSON array", "[1, 2, 3]".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("notebook without a version", "{\"format\":\"calc-notebook\",\"lines\":[]}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("notebook of an older version (2)",
                        "{\"format\":\"calc-notebook\",\"version\":2,\"lines\":[]}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("notebook of version 0", "{\"format\":\"calc-notebook\",\"version\":0,\"lines\":[]}".getBytes(StandardCharsets.UTF_8)),
                Arguments.of("notebook of the current version", sameVersion),
                Arguments.of("current version with a field of its own",
                        "{\"format\":\"calc-notebook\",\"version\":3,\"lines\":[{\"id\":\"1\",\"source\":\"1 + 1\"}],\"mine\":true}"
                                .getBytes(StandardCharsets.UTF_8)));
    }

    private Path future(String name, String content) throws IOException {
        Path file = documents.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    // ------------------------------------------------------------------------------------------------ service level

    /** The document in the state the user is in when they choose « Enregistrer sous ». */
    enum Situation {
        UNTITLED_WITH_CONTENT,
        DOCUMENT_WITH_A_FILE_MODIFIED,
        ANOTHER_FUTURE_FILE_OPEN_READ_ONLY
    }

    private DocumentService service() {
        AtomicFileWriter.Mover mover = (source, target, options) -> {
            moves.incrementAndGet();
            Files.move(source, target, options);
        };
        return new DocumentService(store, store.load(), mover);
    }

    private DocumentService prepare(Situation situation) throws Exception {
        DocumentService service = service();
        service.apply(new NotebookCommand.Append("a = 2"));
        service.apply(new NotebookCommand.Append("a * 3"));
        switch (situation) {
            case UNTITLED_WITH_CONTENT -> { }
            case DOCUMENT_WITH_A_FILE_MODIFIED -> {
                service.saveAs(documents.resolve("mine"));
                service.apply(new NotebookCommand.Append("a + 1"));
            }
            case ANOTHER_FUTURE_FILE_OPEN_READ_ONLY -> {
                service.open(future("open-future.calc.json", FUTURE_99.replace("\"1 + 1\"", "\"7 * 7\"")));
                service.apply(new NotebookCommand.Append("a + 1"));
            }
            default -> throw new AssertionError(situation);
        }
        moves.set(0);
        return service;
    }

    /** Every file of the folders, by name, with its bytes: a temporary file left behind would show. */
    private Map<String, byte[]> snapshot() throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        for (Path folder : List.of(documents, data)) {
            try (Stream<Path> listed = Files.list(folder)) {
                for (Path file : listed.filter(each -> !each.getFileName().toString().equals("history.lock")).toList()) {
                    files.put(root.relativize(file).toString(), Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0]);
                }
            }
        }
        return files;
    }

    private static void assertSameSnapshot(Map<String, byte[]> before, Map<String, byte[]> after, String message) {
        assertEquals(before.keySet(), after.keySet(), message + " (no file created, none removed, no temporary file left)");
        before.forEach((name, bytes) -> assertArrayEquals(bytes, after.get(name), message + ": " + name));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Situation.class)
    void saveAsToAnotherFileOfAFutureVersionIsRefusedAndTheFileIsUntouched(Situation situation) throws Exception {
        for (Object[] each : futureFiles().map(Arguments::get).toList()) {
            String label = each[0] + " / " + situation;
            Path target = future("target.calc.json", (String) each[1]);
            DocumentService service = prepare(situation);
            Optional<Path> pathBefore = service.path();
            boolean modifiedBefore = service.isModified();
            boolean readOnlyBefore = service.isReadOnlyFile();
            List<String> textsBefore = service.notebook().texts();
            Map<String, byte[]> before = snapshot();

            DocumentException refused = assertThrows(DocumentException.class, () -> service.saveAs(target), label);

            assertEquals(Kind.READ_ONLY_VERSION, refused.kind(), label);
            assertEquals(READ_ONLY_MESSAGE, refused.getMessage(), label + ": the message of a future file that is open");
            assertEquals(0, moves.get(), label + ": nothing is moved into place");
            assertSameSnapshot(before, snapshot(), label);
            assertEquals(pathBefore, service.path(), label + ": still the same document");
            assertEquals(modifiedBefore, service.isModified(), label);
            assertEquals(readOnlyBefore, service.isReadOnlyFile(), label);
            assertEquals(textsBefore, service.notebook().texts(), label + ": the user's work is still there");
            Files.delete(target);
            // The refusal leaves the way open: a name that is free works.
            Path copy = service.saveAs(documents.resolve("copy-" + situation.ordinal()));
            assertEquals(textsBefore, NotebookCodec.read(copy).notebook().texts(), label);
            assertFalse(service.isReadOnlyFile(), label);
            Files.delete(copy);
            Files.deleteIfExists(documents.resolve("mine.calc.json"));
            Files.deleteIfExists(documents.resolve("open-future.calc.json"));
        }
    }

    @Test
    void theExtensionAddedToTheNameDoesNotHideAFutureFile() throws Exception {
        Path target = future("devis.calc.json", FUTURE_99);
        DocumentService service = prepare(Situation.UNTITLED_WITH_CONTENT);
        Map<String, byte[]> before = snapshot();

        assertEquals(Kind.READ_ONLY_VERSION, assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("devis"))).kind());
        assertEquals(Kind.READ_ONLY_VERSION,
                assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("sub").resolve("..").resolve("devis.calc.json"))).kind(),
                "nor does a path that goes around, on every system");
        assertEquals(0, moves.get());
        assertSameSnapshot(before, snapshot(), "refused");
        assertEquals(FUTURE_99, Files.readString(target, StandardCharsets.UTF_8));

        // Only a folder that ignores the case of names takes the name in capitals for the future file.
        assumeTrue(CaseSensitivity.ignoresCase(documents), "this system tells devis.calc.json and DEVIS.CALC.JSON apart");
        assertEquals(Kind.READ_ONLY_VERSION,
                assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("sub").resolve("..").resolve("DEVIS.CALC.JSON"))).kind(),
                "nor does the case of the name, where the system ignores it");
        assertEquals(0, moves.get());
        assertSameSnapshot(before, snapshot(), "refused");
        assertEquals(FUTURE_99, Files.readString(target, StandardCharsets.UTF_8));
    }

    @Test
    void theCapitalsVariantOfTheNameLeavesAFutureFileAloneWhereTheSystemKeepsCase() throws Exception {
        assumeFalse(CaseSensitivity.ignoresCase(documents), "this system takes DEVIS.CALC.JSON for devis.calc.json: refused above");
        Path target = future("devis.calc.json", FUTURE_99);
        DocumentService service = prepare(Situation.UNTITLED_WITH_CONTENT);

        Path written = service.saveAs(documents.resolve("DEVIS.CALC.JSON"));

        assertEquals("DEVIS.CALC.JSON", written.getFileName().toString());
        assertEquals(FUTURE_99, Files.readString(target, StandardCharsets.UTF_8), "another file: the future one is intact");
        assertEquals(List.of("a = 2", "a * 3"), NotebookCodec.read(written).notebook().texts());
    }

    @Test
    void anUntitledDocumentKeepsItsDraftWhenTheSaveAsIsRefused() throws Exception {
        future("target.calc.json", FUTURE_99);
        DocumentService service = prepare(Situation.UNTITLED_WITH_CONTENT);

        assertThrows(DocumentException.class, () -> service.saveAs(documents.resolve("target.calc.json")));

        assertTrue(service.isUntitled(), "the document did not become the future file");
        assertEquals(List.of("a = 2", "a * 3"), NotebookCodec.read(data.resolve(CURRENT)).notebook().texts(),
                "and the autosaved draft was not emptied, since nothing was saved");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTargets")
    void saveAsToAnUnreadableOrOrdinaryTargetStillReplacesIt(String label, byte[] content) throws Exception {
        Path target = documents.resolve("target.calc.json");
        Files.write(target, content);
        DocumentService service = prepare(Situation.UNTITLED_WITH_CONTENT);

        Path written = service.saveAs(target);

        assertEquals(target.toAbsolutePath().normalize(), written, label);
        assertEquals(List.of("a = 2", "a * 3"), NotebookCodec.read(target).notebook().texts(), label + ": replaced");
        assertEquals(1, moves.get(), label);
        assertFalse(service.isReadOnlyFile(), label);
    }

    @Test
    void anOrdinaryDocumentCanStillBeSavedOverAndToANewNameAndToItself() throws Exception {
        DocumentService service = prepare(Situation.DOCUMENT_WITH_A_FILE_MODIFIED);

        service.save();
        Path second = service.saveAs(documents.resolve("second"));
        service.apply(new NotebookCommand.Append("a + 2"));
        service.saveAs(second);

        assertEquals(List.of("a = 2", "a * 3", "a + 1", "a + 2"), NotebookCodec.read(second).notebook().texts());
        assertFalse(service.isModified());
    }

    @Test
    void theOpenFutureFileIsStillRefusedWhateverWayItIsNamed() throws Exception {
        Path file = future("open.calc.json", FUTURE_99);
        DocumentService service = service();
        service.open(file);
        Map<String, byte[]> before = snapshot();

        DocumentException save = assertThrows(DocumentException.class, service::save);
        DocumentException saveAs = assertThrows(DocumentException.class, () -> service.saveAs(file));

        assertEquals(READ_ONLY_MESSAGE, save.getMessage());
        assertEquals(save.getMessage(), saveAs.getMessage(), "one message for « Enregistrer » and « Enregistrer sous »");
        assertSameSnapshot(before, snapshot(), "refused");
    }

    // ------------------------------------------------------------------------------------------------ presenter level

    /** The dialogs of the user, played by a script and recording what was asked. */
    private static final class Script implements NotebookDialogs {
        final List<String> asked = new ArrayList<>();
        Path toOpen;
        Path toSave;
        Path toExport;
        boolean replace;

        @Override
        public Optional<Path> chooseFileToOpen(Optional<Path> current) {
            asked.add("open");
            return Optional.ofNullable(toOpen);
        }

        @Override
        public Optional<Path> chooseFileToSave(Optional<Path> current) {
            asked.add("save as");
            return Optional.ofNullable(toSave);
        }

        @Override
        public Optional<Path> chooseExportFile(ExportFormat format, String suggested) {
            asked.add("export");
            return Optional.ofNullable(toExport);
        }

        @Override
        public Unsaved askToSave(String name, Action action) {
            asked.add("unsaved");
            return Unsaved.DISCARD;
        }

        @Override
        public boolean confirmReplaceDraft() {
            asked.add("replace draft");
            return true;
        }

        @Override
        public boolean confirmAbandonExport() {
            asked.add("abandon export");
            return false;
        }

        @Override
        public boolean confirmOverwrite(Path file) {
            asked.add("overwrite " + file.getFileName());
            return replace;
        }

        List<String> overwriteQuestions() {
            return asked.stream().filter(question -> question.startsWith("overwrite")).toList();
        }
    }

    private NotebookPresenter presenter(Script script) throws Exception {
        // The notebook the user is working on: two lines, in the autosaved draft the application starts from.
        Files.write(data.resolve(CURRENT), NotebookCodec.encode(NotebookGenerators.notebook(List.of("a = 2", "a * 3")), DisplayMode.AUTO).bytes());
        NotebookPresenter presenter = NotebookPresenter.open(store, DisplayModeStore.noop(), script);
        presenters.add(presenter);
        awaitTheCalculation(presenter);
        return presenter;
    }

    // The draft is calculated on a background thread, and while it runs the status bar puts its count before any message:
    // on a slow machine the save would be refused with that prefix. The user starts from a calculated notebook.
    private static void awaitTheCalculation(NotebookPresenter presenter) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (onEdt(presenter::currentView).statusMessage().startsWith("Calcul en cours")) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the calculation of the draft has not ended within 30 s");
            }
            Thread.sleep(10);
        }
    }

    // The text of the line being edited, which the view hands back with every intention.
    private static String pending(NotebookPresenter presenter) {
        return presenter.currentView().activeDraft();
    }

    // Each intention runs on the event thread, as in the window.
    private static NotebookViewModel onEdt(java.util.function.Supplier<NotebookViewModel> intention) throws Exception {
        NotebookViewModel[] told = new NotebookViewModel[1];
        SwingUtilities.invokeAndWait(() -> told[0] = intention.get());
        return told[0];
    }

    @Test
    void theWindowRefusesAFutureTargetWithoutAskingToReplaceIt() throws Exception {
        Script script = new Script();
        script.replace = true; // were the question asked, the user would say yes: that is the defect
        NotebookPresenter presenter = presenter(script);
        Path target = future("target.calc.json", FUTURE_99);
        Map<String, byte[]> before = snapshot();
        script.toSave = target;

        NotebookViewModel view = onEdt(() -> presenter.onSaveAs(pending(presenter)));

        assertEquals(List.of("save as"), script.asked, "no « Remplacer ? » for a file that must not be replaced");
        assertEquals(READ_ONLY_MESSAGE, view.statusMessage());
        assertEquals(StatusTone.ERROR, view.statusTone());
        assertSameSnapshot(before, snapshot(), "refused");
        assertTrue(view.title().startsWith("Carnet sans titre"), "the document is still the untitled one: " + view.title());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("futureFiles")
    void theWindowRefusesEveryShapeOfFutureTarget(String label, String content) throws Exception {
        Script script = new Script();
        script.replace = true;
        NotebookPresenter presenter = presenter(script);
        Path target = future("target.calc.json", content);
        Map<String, byte[]> before = snapshot();
        script.toSave = documents.resolve("target"); // typed without the extension: the presenter adds it

        NotebookViewModel view = onEdt(() -> presenter.onSaveAs(pending(presenter)));

        assertEquals(List.of("save as"), script.asked, label);
        assertEquals(READ_ONLY_MESSAGE, view.statusMessage(), label);
        assertSameSnapshot(before, snapshot(), label);
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target), label);
    }

    @Test
    void theMessageIsTheOneOfAnOpenFutureFileAndTheOpenOneIsStillRefusedToo() throws Exception {
        Script script = new Script();
        script.replace = true;
        NotebookPresenter presenter = presenter(script);
        Path open = future("open.calc.json", FUTURE_99);
        Path other = future("other.calc.json", FUTURE_99.replace("\"1 + 1\"", "\"2 + 2\""));
        script.toOpen = open;
        onEdt(() -> presenter.onOpen(pending(presenter)));
        Map<String, byte[]> before = snapshot();
        script.asked.clear();

        // Rewriting the open future file itself is what the project already refuses: its message is the reference.
        script.toSave = open;
        NotebookViewModel toItself = onEdt(() -> presenter.onSaveAs(pending(presenter)));
        String messageOfSave = toItself.statusMessage();
        assertEquals(StatusTone.ERROR, toItself.statusTone());
        assertEquals(READ_ONLY_MESSAGE, messageOfSave);
        script.toSave = other;
        NotebookViewModel toAnother = onEdt(() -> presenter.onSaveAs(pending(presenter)));

        assertEquals(messageOfSave, toAnother.statusMessage(), "the same message as when the open future file is rewritten");
        assertEquals(messageOfSave, toItself.statusMessage());
        assertEquals(List.of("save as", "save as"), script.asked, "never « Remplacer ? »");
        assertSameSnapshot(before, snapshot(), "refused");
        assertTrue(toAnother.title().contains("open.calc.json") && toAnother.title().contains("lecture seule"), toAnother.title());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTargets")
    void theWindowStillAsksBeforeReplacingAnUnreadableOrOrdinaryTarget(String label, byte[] content) throws Exception {
        for (boolean yes : new boolean[] {false, true}) {
            Script script = new Script();
            script.replace = yes;
            NotebookPresenter presenter = presenter(script);
            Path target = documents.resolve("target.calc.json");
            Files.write(target, content);
            script.toSave = target;

            onEdt(() -> presenter.onSaveAs(pending(presenter)));

            assertEquals(List.of("save as", "overwrite target.calc.json"), script.asked, label + (yes ? " (yes)" : " (no)"));
            if (yes) {
                assertEquals(List.of("a = 2", "a * 3"), NotebookCodec.read(target).notebook().texts(), label);
            } else {
                assertArrayEquals(content, Files.readAllBytes(target), label + ": the user said no");
            }
            Files.delete(target);
        }
    }

    // ------------------------------------------------------------------------------------------------ exports

    @ParameterizedTest(name = "{0}")
    @EnumSource(NotebookDialogs.ExportFormat.class)
    void anExportStillAsksBeforeReplacingAFileWhateverItContains(NotebookDialogs.ExportFormat format) throws Exception {
        Script script = new Script();
        script.replace = true;
        NotebookPresenter presenter = presenter(script);
        Path target = documents.resolve("export" + format.extension());
        byte[] lookalike = FUTURE_99.getBytes(StandardCharsets.UTF_8);
        Files.write(target, lookalike);
        script.toExport = target;

        onEdt(() -> presenter.onExport(format, pending(presenter)));
        awaitChange(target, lookalike);

        assertEquals(List.of("export", "overwrite " + target.getFileName()), script.asked.stream().filter(q -> !q.equals("unsaved")).toList());
        String exported = Files.readString(target, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertTrue(exported.contains("a * 3") || exported.contains("a \\* 3"), "the export was written: " + exported);
    }

    @Test
    void anExportTheUserRefusesToReplaceLeavesTheFileAlone() throws Exception {
        Script script = new Script();
        NotebookPresenter presenter = presenter(script);
        Path target = documents.resolve("export.md");
        byte[] lookalike = FUTURE_99.getBytes(StandardCharsets.UTF_8);
        Files.write(target, lookalike);
        script.toExport = target;

        onEdt(() -> presenter.onExport(NotebookDialogs.ExportFormat.MARKDOWN, pending(presenter)));

        assertEquals(List.of("export", "overwrite export.md"), script.asked);
        assertArrayEquals(lookalike, Files.readAllBytes(target));
    }

    // Waits for the export, which is written once the calculation has ended, off the event thread.
    private static void awaitChange(Path file, byte[] original) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (java.util.Arrays.equals(original, Files.readAllBytes(file))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the export was not written within 30 s: " + file);
            }
            SwingUtilities.invokeAndWait(() -> { });
            Thread.sleep(20);
        }
    }

    // ------------------------------------------------------------------------------------------------ documentation

    private static String read(String first, String... more) throws IOException {
        return Files.readString(Path.of(first, more), StandardCharsets.UTF_8);
    }

    @Test
    void theReportMarksD5AsFixedWithTheTestThatProvesIt() throws IOException {
        List<String> lines = read("docs", "RECETTE.md").lines().toList();

        String defect = lines.stream().filter(line -> line.startsWith("| D5 |")).findFirst().orElseThrow();
        assertTrue(defect.contains("Corrigé"), "D5 is marked as fixed, as D7 is: " + defect);
        assertTrue(defect.contains("FutureVersionTargetAcceptanceTest"), "with the test that proves it: " + defect);

        String guarantee = lines.stream().filter(line -> line.contains("version future jamais réécrite")).findFirst().orElseThrow();
        assertFalse(guarantee.contains("Enregistrer sous peut remplacer"), "the result no longer states the defect: " + guarantee);
        assertFalse(guarantee.trim().endsWith("Réserve |"), "and no longer carries the reservation for it: " + guarantee);
        assertTrue(guarantee.contains("FutureVersionTargetAcceptanceTest"), "its proof is cited: " + guarantee);
    }

    @Test
    void theFileFormatOrTheDecisionStatesTheRuleAlsoHoldsForTheTargetOfASaveAs() throws IOException {
        List<String> lines = new ArrayList<>();
        lines.addAll(read("docs", "FORMAT-FICHIER.md").lines().toList());
        lines.addAll(read("docs", "adr", "0011-fichiers-dans-l-interface.md").lines().toList());

        boolean stated = lines.stream().map(line -> line.toLowerCase(Locale.ROOT)).anyMatch(line ->
                (line.contains("enregistrer sous") || line.contains("save as"))
                        && (line.contains("cible") || line.contains("target"))
                        && (line.contains("version future") || line.contains("plus récente") || line.contains("newer version")
                                || line.contains("future version")));
        assertTrue(stated, "a line of docs/FORMAT-FICHIER.md or of docs/adr/0011-fichiers-dans-l-interface.md must say that a file of a "
                + "future version is not rewritten when it is the target of an « Enregistrer sous » either");
    }
}
