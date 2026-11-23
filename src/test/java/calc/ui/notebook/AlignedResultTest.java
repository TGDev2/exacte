package calc.ui.notebook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import calc.ui.design.DesignTokens;
import calc.ui.theme.ThemeManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The result of a row, alone: its integer part ends on the column of the commas, whether the row is painted on screen or
 * printed (the screenshots print it), at the scale of a normal screen or of a 150 % one, whatever text hints the look and
 * feel gave the component. The result is measured and drawn by the same Graphics: measured by one and drawn by Swing's
 * drawString, four-digit integers ended 3 px right of the column in the window (NotebookRowsAcceptanceTest, run with the
 * other tests of calc.ui).
 */
class AlignedResultTest {
    private static final List<String> INTEGERS = List.of("0", "437", "1250", "5167", "6201", "123456789");
    private static final List<String> DECIMALS = List.of("437,5", "5167,5", "0,2", "516,75", "0,333333333333…");

    @BeforeAll
    static void installTheFonts() {
        ThemeManager.installFonts();
    }

    static Stream<Arguments> ways() {
        Stream.Builder<Arguments> ways = Stream.builder();
        for (Object antialiasing : new Object[] {"none", RenderingHints.VALUE_TEXT_ANTIALIAS_OFF, RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
                RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB}) {
            for (boolean printed : new boolean[] {false, true}) {
                for (double scale : new double[] {1.0, 1.5}) {
                    ways.add(Arguments.of(printed, scale, antialiasing));
                }
            }
        }
        return ways.build();
    }

    @ParameterizedTest(name = "printed {0}, scale {1}, text antialiasing of the component {2}")
    @MethodSource("ways")
    @DisplayName("la partie entière finit sur la colonne des virgules, peinte ou imprimée, à 100 % ou 150 %, quel que soit le lissage")
    void theIntegerPartEndsOnTheColumnOfTheCommas(boolean printed, double scale, Object antialiasing) {
        NotebookRowView.AlignedResult result = result();
        if (!"none".equals(antialiasing)) {
            // A look and feel gives its components the text hints of the desktop; the result must not depend on them.
            result.putClientProperty(RenderingHints.KEY_TEXT_ANTIALIASING, antialiasing);
        }
        int anchor = (int) Math.round(result.anchor() * scale);
        double cell = result.getFontMetrics(result.getFont()).charWidth('0') * scale;
        for (String integer : INTEGERS) {
            int end = lastInk(render(result, integer, integer, printed, scale)) + 1;
            assertTrue(end >= anchor - cell / 2 && end <= anchor + Math.ceil(scale),
                    integer + " ends at " + end + ", the column of the commas is at " + anchor);
        }
        for (String decimal : DECIMALS) {
            String integer = decimal.substring(0, decimal.indexOf(','));
            boolean[] alone = render(result, integer, integer, printed, scale);
            boolean[] followed = render(result, decimal, integer, printed, scale);
            int before = anchor - (int) Math.ceil(scale);
            for (int column = 0; column < before; column++) {
                assertEquals(alone[column], followed[column],
                        decimal + ": its integer part is drawn where " + integer + " alone is drawn (column " + column + ")");
            }
            assertTrue(lastInk(followed) + 1 >= anchor + cell, decimal + ": its decimals come after the column of the commas");
        }
    }

    private static NotebookRowView.AlignedResult result() {
        NotebookRowView.AlignedResult result = new NotebookRowView.AlignedResult();
        result.setFont(new Font(DesignTokens.load().fontFamily("mono"), Font.PLAIN, DesignTokens.load().size("font.size.result")));
        result.setForeground(Color.BLACK);
        result.setSize(330, 32);
        return result;
    }

    // The columns of the picture that hold ink.
    private static boolean[] render(NotebookRowView.AlignedResult result, String text, String integer, boolean printed,
                                    double scale) {
        result.show(text, integer);
        BufferedImage image = new BufferedImage((int) Math.ceil(result.getWidth() * scale), (int) Math.ceil(result.getHeight() * scale),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
            graphics.scale(scale, scale);
            if (printed) {
                result.printAll(graphics);
            } else {
                result.paint(graphics);
            }
        } finally {
            graphics.dispose();
        }
        boolean[] ink = new boolean[image.getWidth()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int darkness = 3 * 255 - ((rgb >> 16) & 0xFF) - ((rgb >> 8) & 0xFF) - (rgb & 0xFF);
                if (darkness > 96) {
                    ink[x] = true;
                }
            }
        }
        return ink;
    }

    private static int lastInk(boolean[] ink) {
        for (int column = ink.length - 1; column >= 0; column--) {
            if (ink[column]) {
                return column;
            }
        }
        return -1;
    }
}
