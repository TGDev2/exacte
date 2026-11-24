package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import org.junit.jupiter.api.Test;

/**
 * The paste of {@link LineField}, without a window: a text with line breaks goes whole to its {@link LineField.MultiLinePaste}
 * with the selection it replaces, and anything else goes into the field as the look and feel puts it.
 */
class LineFieldPasteTest {
    private record Paste(int start, int end, String text) {
    }

    @Test
    void aTextWithLineBreaksGoesWholeToTheListenerWithTheSelection() throws Exception {
        List<Paste> pastes = new ArrayList<>();
        onEdt(() -> {
            LineField field = field("a = bc", pastes);
            field.select(4, 6);

            assertTrue(paste(field, "1\r\n2\n3\r"));

            assertEquals("a = bc", field.getText(), "the field is left to the presenter");
            return null;
        });
        assertEquals(List.of(new Paste(4, 6, "1\r\n2\n3\r")), pastes);
    }

    @Test
    void aTextWithoutLineBreakGoesIntoTheFieldAtTheCaret() throws Exception {
        List<Paste> pastes = new ArrayList<>();
        onEdt(() -> {
            LineField field = field("a = b", pastes);
            field.setCaretPosition(4);

            assertTrue(paste(field, "2 * "));

            assertEquals("a = 2 * b", field.getText());
            assertEquals(8, field.getCaretPosition());
            return null;
        });
        assertTrue(pastes.isEmpty());
    }

    @Test
    void withoutListenerOrWhenNotEditableTheFieldPastesAsBefore() throws Exception {
        List<Paste> pastes = new ArrayList<>();
        onEdt(() -> {
            LineField alone = new LineField();
            assertTrue(paste(alone, "1\n2"));
            assertEquals("1\n2", alone.getText(), "as before: the field keeps it, the presenter makes a space of it");

            LineField readOnly = field("7", pastes);
            readOnly.setEditable(false);
            assertFalse(readOnly.getTransferHandler().canImport(
                    new TransferHandler.TransferSupport(readOnly, new StringSelection("1\n2"))), "Ctrl+V asks first: refused");
            paste(readOnly, "1\n2");

            LineField listening = field("7", pastes);
            listening.setMultiLinePaste(null);
            assertTrue(paste(listening, "1\n2"));
            assertEquals("71\n2", listening.getText());
            return null;
        });
        assertTrue(pastes.isEmpty());
    }

    @Test
    void anImageIsLeftToTheLookAndFeel() throws Exception {
        List<Paste> pastes = new ArrayList<>();
        onEdt(() -> {
            LineField field = field("7", pastes);
            Transferable image = new Transferable() {
                @Override
                public DataFlavor[] getTransferDataFlavors() {
                    return new DataFlavor[] {DataFlavor.imageFlavor};
                }

                @Override
                public boolean isDataFlavorSupported(DataFlavor flavor) {
                    return DataFlavor.imageFlavor.equals(flavor);
                }

                @Override
                public Object getTransferData(DataFlavor flavor) {
                    throw new AssertionError("not read");
                }
            };
            assertFalse(field.getTransferHandler().importData(new TransferHandler.TransferSupport(field, image)));
            assertEquals("7", field.getText());
            return null;
        });
        assertTrue(pastes.isEmpty());
    }

    @Test
    void copyingStillWorksThroughTheHandlerOfTheLookAndFeel() throws Exception {
        String copied = onEdt(() -> {
            LineField field = field("a = 12", new ArrayList<>());
            field.select(4, 6);
            Clipboard clipboard = new Clipboard("test");
            field.getTransferHandler().exportToClipboard(field, clipboard, TransferHandler.COPY);
            assertEquals(TransferHandler.COPY_OR_MOVE, field.getTransferHandler().getSourceActions(field));
            return (String) clipboard.getData(DataFlavor.stringFlavor);
        });
        assertEquals("12", copied);
    }

    @Test
    void aChangeOfLookAndFeelKeepsTheHandler() throws Exception {
        onEdt(() -> {
            LineField field = new LineField();
            TransferHandler handler = field.getTransferHandler();
            field.updateUI();
            assertSame(handler, field.getTransferHandler());
            return null;
        });
    }

    private static LineField field(String text, List<Paste> pastes) {
        LineField field = new LineField();
        field.setText(text);
        field.setMultiLinePaste((start, end, pasted) -> pastes.add(new Paste(start, end, pasted)));
        return field;
    }

    private static boolean paste(LineField field, String text) {
        return field.getTransferHandler().importData(new TransferHandler.TransferSupport(field, new StringSelection(text)));
    }

    private static <T> T onEdt(Callable<T> work) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.complete(work.call());
            } catch (Throwable thrown) {
                result.completeExceptionally(thrown);
            }
        });
        try {
            return result.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception exception) {
                throw exception;
            }
            throw (Error) failed.getCause();
        }
    }
}
