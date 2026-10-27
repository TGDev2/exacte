package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.format.DisplayMode;
import calc.format.ValueFormatter;
import calc.notebook.EvaluatedNotebook;
import calc.notebook.LineResult;
import calc.notebook.NotebookEvaluator;
import calc.persistence.NotebookCodec;
import calc.persistence.NotebookStore;
import calc.testing.NotebookGenerators;
import calc.testing.TempDirectories;
import calc.ui.DisplayModeStore;
import calc.ui.notebook.NotebookDialogs.ExportFormat;
import calc.ui.notebook.NotebookDialogs.Unsaved;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

/**
 * Stateful test of the notebook presenter, the successor of the one the two-field form carried: random sequences of what a
 * user does in the window (type and validate a line, arrows, Escape, undo, redo, insert, delete, move, clear, change the
 * display mode, and the File menu: new, save, save as, open, export, with the dialogs played by {@link FakeDialogs}).
 * After every step the view model must agree with the real files and with a fresh evaluation of the texts:
 *
 * <ol>
 *   <li>nothing throws, the active row is a row, the last row is the blank new line, the rows are numbered 1..n;
 *   <li>every result shown is the formatted result of a fresh evaluation of the texts, in the mode shown;
 *   <li>an untitled notebook is the autosaved one: the current file holds exactly the validated texts;
 *   <li>a document with a file whose texts differ from that file is marked as modified in the title.
 * </ol>
 */
@Tag("robustness")
class NotebookPresenterStatefulProperties {
    private static final List<String> TEXTS = List.of("1 + 1", "2,5 * 4", "ans / 3", "√2", "x = 10", "x * x", "$1 + $2", "# note",
            "1 / 0", "3 +", "2^10", "ans", "");

    enum Kind {
        LINE, TYPE, UP, DOWN, ESCAPE, UNDO, REDO, INSERT_ABOVE, INSERT_BELOW, DELETE, MOVE_UP, MOVE_DOWN, CLEAR, MODE, NEXT_MODE, NEW,
        SAVE_AS, SAVE, OPEN, EXPORT, QUIT
    }

    record Step(Kind kind, String text, DisplayMode mode) {
        @Override
        public String toString() {
            return kind + (kind == Kind.LINE || kind == Kind.TYPE ? "(" + text + ")" : kind == Kind.MODE ? "(" + mode + ")" : "");
        }
    }

    @Provide
    Arbitrary<List<Step>> sequences() {
        Arbitrary<Step> steps = Arbitraries.of(Kind.values()).flatMap(kind -> Arbitraries.of(TEXTS).flatMap(text ->
                Arbitraries.of(DisplayMode.values()).map(mode -> new Step(kind, text, mode))));
        return steps.list().ofMinSize(1).ofMaxSize(30);
    }

    @Property(tries = 150)
    void theViewAgreesWithTheFilesAndAFreshEvaluationAfterEveryStep(@ForAll("sequences") List<Step> sequence) throws Exception {
        TempDirectories.with(directory -> {
            Path data = Files.createDirectories(directory.resolve("data"));
            Path documents = Files.createDirectories(directory.resolve("documents"));
            try (NotebookStore store = NotebookStore.open(data)) {
                FakeDialogs dialogs = new FakeDialogs();
                NotebookDriver d = new NotebookDriver(NotebookPresenter.open(store, DisplayModeStore.noop(), dialogs));
                List<String> done = new ArrayList<>();
                int saves = 0;
                for (Step step : sequence) {
                    done.add(step.toString());
                    Path file = documents.resolve("f" + (saves % 3) + ".calc.json");
                    switch (step.kind()) {
                        case LINE -> d.line(step.text());
                        case TYPE -> d.type(step.text());
                        case UP -> d.up();
                        case DOWN -> d.down();
                        case ESCAPE -> d.escape();
                        case UNDO -> d.undo();
                        case REDO -> d.redo();
                        case INSERT_ABOVE -> d.insertAbove();
                        case INSERT_BELOW -> d.insertBelow();
                        case DELETE -> d.delete();
                        case MOVE_UP -> d.moveLineUp();
                        case MOVE_DOWN -> d.moveLineDown();
                        case CLEAR -> d.clear();
                        case MODE -> d.mode(step.mode());
                        case NEXT_MODE -> d.nextMode();
                        case NEW -> {
                            dialogs.willAnswer(Unsaved.DISCARD).willConfirmReplaceDraft(true);
                            d.newDocument();
                        }
                        case SAVE_AS -> {
                            dialogs.willSaveTo(file).willConfirmOverwrite(true);
                            d.saveAs();
                            saves++;
                        }
                        case SAVE -> {
                            dialogs.willSaveTo(file).willConfirmOverwrite(true);
                            d.save();
                        }
                        case OPEN -> {
                            dialogs.willOpen(file).willAnswer(Unsaved.DISCARD);
                            d.open();
                        }
                        case EXPORT -> {
                            dialogs.willExportTo(documents.resolve("export")).willConfirmOverwrite(true);
                            d.export(step.mode() == DisplayMode.AUTO ? ExportFormat.CSV : ExportFormat.MARKDOWN);
                        }
                        case QUIT -> {
                            dialogs.willAnswer(Unsaved.DISCARD);
                            d.close();
                        }
                    }
                    check(d, store, documents, done);
                }
            }
        });
    }

    private static void check(NotebookDriver d, NotebookStore store, Path documents, List<String> trace) throws Exception {
        NotebookViewModel view = d.view;
        String context = "after " + trace;
        List<NotebookRow> rows = view.rows();
        assertTrue(view.activeIndex() >= 0 && view.activeIndex() < rows.size(), context);
        assertEquals(NotebookRow.Status.EMPTY, rows.get(rows.size() - 1).status(), "the last row is the new line: " + context);
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).number(), context);
        }

        List<String> texts = rows.subList(0, rows.size() - 1).stream().map(NotebookRow::text).toList();
        EvaluatedNotebook fresh = NotebookEvaluator.evaluate(NotebookGenerators.notebook(texts));
        for (int i = 0; i < texts.size(); i++) {
            LineResult expected = fresh.results().get(i);
            NotebookRow shown = rows.get(i);
            assertEquals(expected.isError(), shown.status() == NotebookRow.Status.ERROR, "row " + (i + 1) + " " + context);
            if (expected.hasValue()) {
                assertEquals(ValueFormatter.format(expected.value(), view.displayMode()).text(), shown.result(),
                        "row " + (i + 1) + " " + context);
            }
        }

        boolean untitled = view.title().startsWith(NotebookPresenter.UNTITLED_NAME);
        if (untitled) {
            if (!store.isReadOnly() && Files.exists(store.currentFile())) {
                assertEquals(texts, NotebookCodec.read(store.currentFile()).notebook().texts(), "the autosave: " + context);
            }
            assertFalse(view.title().startsWith("• "), context);
        } else {
            String name = view.title().replaceFirst("^• ", "").replace(" (lecture seule)", "").replaceFirst(" — Calc$", "");
            Path file = documents.resolve(name);
            if (Files.exists(file) && !view.title().contains("lecture seule")) {
                boolean differs = !texts.equals(NotebookCodec.read(file).notebook().texts());
                assertTrue(!differs || view.title().startsWith("• "), "a document that differs from its file is marked: " + context);
            }
        }
    }
}
